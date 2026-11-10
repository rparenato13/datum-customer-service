#!/usr/bin/env python3
"""T12: valida observabilidade somente em ambiente descartavel proprio."""
import argparse
import base64
import json
from pathlib import Path
import subprocess
import sys
import urllib.error
import urllib.request
import uuid

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "resilience"))
try:
    from environment import Environment, Failure, keycloak
except ImportError:
    print("Instale infra/rabbitmq/requirements-test.txt na .venv conforme o runbook.", file=sys.stderr)
    sys.exit(2)


def request(env, service, path, method="GET", payload=None, correlation=None, token=None, monitor=False):
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    if monitor:
        secret = "notification_monitor:" + env.env["NOTIFICATION_MANAGEMENT_PASSWORD"]
        headers["Authorization"] = "Basic " + base64.b64encode(secret.encode()).decode()
    if correlation:
        headers["X-Correlation-Id"] = correlation
    req = urllib.request.Request("http://127.0.0.1:" + env.ports[service] + path, method=method, headers=headers,
        data=None if payload is None else json.dumps(payload).encode())
    try:
        response = urllib.request.urlopen(req, timeout=25)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        return response.status, response.read().decode(), response.headers


def logs(env, service):
    return [json.loads(line) for line in env.logs(service).splitlines() if line.startswith("{")]


def create(env, cpf, correlation):
    email = "observability@example.test"
    env.personal.extend([cpf, email])
    status, raw, headers = request(env, "customer", "/customers", method="POST", token=env.token,
        correlation=correlation, payload=dict(name="Observability Test", cpf=cpf, email=email, status="ACTIVE"))
    env.check(status == 201 and headers.get("X-Correlation-Id") == correlation,
        "Cadastro HTTP devolve a correlacao validada.")
    return json.loads(raw)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--skip-build", action="store_true", help="Reutiliza imagens :t12 construidas desta revisao")
    options = parser.parse_args()
    env = Environment(image_tag="t12", app_profiles="observability")
    try:
        env.start(build=not options.skip_build)
        for service in ("customer", "notification"):
            env.check(request(env, service, "/actuator/health")[0] == 200, "Health publico: " + service + ".")
            env.check(request(env, service, "/actuator/prometheus")[0] == 401, "Prometheus exige autenticacao: " + service + ".")
            status, _, headers = request(env, service, "/actuator/metrics", token=env.token if service == "customer" else None,
                monitor=service == "notification")
            env.check(status == 200 and headers.get("Set-Cookie") is None, "Metricas autenticadas sem sessao: " + service + ".")
        issuer = "http://localhost:" + env.ports["keycloak"] + "/realms/datum"
        _, discovery = keycloak.request_json(issuer + "/.well-known/openid-configuration")
        code, verifier = keycloak.login_code(discovery, "api-user", env.env["KEYCLOAK_DEMO_USER_PASSWORD"])
        status, result = keycloak.exchange(discovery, code, verifier)
        if status != 200:
            raise Failure("Token USER de teste indisponivel.")
        user_token = result["access_token"]
        env.tokens_issued.append(user_token)
        env.check(request(env, "customer", "/actuator/metrics", token=user_token)[0] == 403,
            "USER real nao acessa metricas administrativas.")

        correlation = str(uuid.uuid4())
        customer = create(env, "16899535009", correlation)
        env.until(lambda: env.notified(env.outbox(customer["id"])["event_id"]))
        event = env.outbox(customer["id"])
        env.check(event["correlation_id"] == correlation and event["state"] == "PUBLISHED",
            "Outbox conserva correlacao HTTP e publicacao confirmada.")
        env.until(lambda: any(row.get("operation") == "message.consume" and row.get("correlationId") == correlation
            and row.get("eventId") == event["event_id"] for row in logs(env, "notification")))
        env.check(True, "JSON liga requisicao, outbox e consumidor por correlacao e eventId.")
        env.run([sys.executable, "infra/rabbitmq/replay_created.py", "--event-id", event["event_id"], *env.arguments])
        env.until(lambda: any(row.get("operation") == "message.consume" and row.get("outcome") == "DUPLICATE"
            and row.get("correlationId") == correlation for row in logs(env, "notification")))
        env.check(env.notified(event["event_id"]), "Replay preserva correlacao e registra DUPLICATE sem novo efeito.")

        score_correlation = str(uuid.uuid4())
        status, _, headers = request(env, "customer", "/customers/" + str(customer["id"]) + "/score",
            token=env.token, correlation=score_correlation)
        env.check(status == 200 and headers.get("X-Correlation-Id") == score_correlation, "Score saudavel mantem correlacao HTTP.")
        slow = create(env, "01234567890", str(uuid.uuid4()))
        timeout_correlation = str(uuid.uuid4())
        status, raw, _ = request(env, "customer", "/customers/" + str(slow["id"]) + "/score", token=env.token,
            correlation=timeout_correlation)
        env.check(status == 504 and json.loads(raw)["code"] == "SCORE_TIMEOUT", "Score lento produz timeout classificado.")
        score_logs = [row for row in logs(env, "customer") if row.get("operation") == "score.request"]
        env.check(any(row.get("correlationId") == score_correlation and row.get("outcome") == "success" for row in score_logs)
            and any(row.get("correlationId") == timeout_correlation and row.get("timeout") is True for row in score_logs),
            "Logs distinguem sucesso e timeout na dependencia sem expor URL real.")

        # Uma falha transitoria real prova a correlacao tambem no retorno do retry.
        env.sql("postgres", "ALTER ROLE notification_app NOLOGIN; SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE usename = 'notification_app'")
        retry_correlation = str(uuid.uuid4())
        try:
            retried = create(env, "52998224725", retry_correlation)
            env.until(lambda: env.count("customer-notification-service.created.v1.retry.1") >= 1)
        finally:
            env.sql("postgres", "ALTER ROLE notification_app LOGIN")
        retry_event = env.outbox(retried["id"])
        env.until(lambda: env.notified(retry_event["event_id"]))
        env.until(lambda: any(row.get("correlationId") == retry_correlation and row.get("retryCount", 0) >= 1
            and row.get("outcome") == "NOTIFICATION_REGISTERED" for row in logs(env, "notification")))
        env.check(True, "Retry real por TTL preserva correlacao original e confirma efeito unico.")

        for service in ("customer", "notification"):
            status, exported, _ = request(env, service, "/actuator/prometheus",
                token=env.token if service == "customer" else None, monitor=service == "notification")
            expected = ["jvm_memory_used_bytes", "hikaricp_connections_active", "datum_db_query_duration_seconds"]
            expected += ["datum_messaging_process_duration_seconds"] if service == "notification" else ["datum_score_client_duration_seconds_bucket"]
            env.check(status == 200 and all(name in exported for name in expected),
                "Prometheus exporta JVM, pool, duracoes e histogramas: " + service + ".")
            private = env.personal + [correlation, score_correlation, timeout_correlation, retry_correlation, event["event_id"]]
            env.check(not any(value in exported for value in private), "Labels sem CPF, email ou IDs de rastreamento: " + service + ".")
        snapshot = env.run([sys.executable, "infra/observability/broker_metrics.py", *env.arguments])
        env.check("datum_broker_collection_up 1" in snapshot and sum(line.startswith("datum_broker_messages_")
            for line in snapshot.splitlines()) == 30, "Snapshot de ready/unacked/total cobre as dez filas sem consumir mensagens.")
        env.until(lambda: json.loads(request(env, "customer", "/actuator/metrics/datum.outbox.collection.up", token=env.token)[1])
            ["measurements"][0]["value"] == 1)
        env.check(True, "Backlog/idade do outbox coletados em snapshot fora do scrape.")
        output = env.logs("customer") + env.logs("notification")
        private = env.personal + env.tokens_issued + [value for key, value in env.env.items() if key.endswith("PASSWORD")]
        env.check(not any(value in output for value in private), "Logs sem dados pessoais, tokens ou senhas de teste.")
        completions = [row for service in ("customer", "notification") for row in logs(env, service) if "operation" in row]
        env.check(all(row.get("timestamp", "").endswith("Z") and row.get("service") and row.get("environment")
            and "stack_trace" not in row for row in completions), "Conclusoes JSON tem timestamp UTC, servico e ambiente.")
        print("T12: " + str(env.checks) + " verificacoes aprovadas em ambiente descartavel.", flush=True)
    finally:
        env.close()


if __name__ == "__main__":
    try:
        main()
    except Failure as error:
        print("FALHA: " + str(error), file=sys.stderr)
        sys.exit(1)
    except Exception:
        print("FALHA: verificacao descartavel nao concluiu; nenhum segredo ou payload foi exibido.", file=sys.stderr)
        sys.exit(1)
