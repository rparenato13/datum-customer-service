#!/usr/bin/env python3
"""T14: quickstart, jornada e persistencia usando somente Compose descartavel."""
import argparse
import base64
import json
import sys
import urllib.error
import urllib.request

try:
    from compose_environment import ComposeEnvironment, Failure, ROOT, keycloak
except ImportError:
    print("Instale infra/rabbitmq/requirements-test.txt na .venv conforme o runbook.", file=sys.stderr)
    sys.exit(2)
sys.path.insert(0, str(ROOT / "infra/testing"))
from smoke import journey


def prometheus(env, service, authenticated):
    headers = {}
    if authenticated:
        if service == "customer":
            headers["Authorization"] = "Bearer " + env.token
        else:
            secret = "notification_monitor:" + env.env["NOTIFICATION_MANAGEMENT_PASSWORD"]
            headers["Authorization"] = "Basic " + base64.b64encode(secret.encode()).decode()
    request = urllib.request.Request("http://127.0.0.1:" + env.ports[service] + "/actuator/prometheus", headers=headers)
    try:
        response = urllib.request.urlopen(request, timeout=15)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        return response.status, response.read().decode()


def validate(env):
    for service, attribute in [("customer-service", "customer"), ("customer-notification-service", "notification")]:
        identifier = getattr(env, attribute)
        uid = env.run(["docker", "exec", identifier, "id", "-u"]).strip()
        memory = env.run(["docker", "inspect", "--format", "{{.HostConfig.Memory}}", identifier]).strip()
        env.check(uid == "10001" and int(memory) > 0, "Aplicacao sem root e com limite de memoria: " + service + ".")
    issuer = "http://localhost:" + env.ports["keycloak"] + "/realms/datum"
    _, external = keycloak.request_json(issuer + "/.well-known/openid-configuration")
    internal = keycloak.internal_json(env.compose, "/realms/datum/.well-known/openid-configuration")
    _, public_keys = keycloak.request_json(external["jwks_uri"])
    internal_keys = keycloak.internal_json(env.compose, "/realms/datum/protocol/openid-connect/certs")
    env.check(external["issuer"] == internal["issuer"] == issuer and public_keys == internal_keys,
        "Discovery/JWKS no host e na rede Docker mantem o mesmo issuer canonico.")
    claims = json.loads(base64.urlsafe_b64decode(env.token.split(".")[1] + "=="))
    audience = claims.get("aud", [])
    audience = [audience] if isinstance(audience, str) else audience
    env.check(claims.get("iss") == issuer and "customer-api" in audience, "Token real usa issuer/audiencia esperados pelo Compose.")
    # Verificador administrativo usa somente GET e confere hashes/politicas no volume novo.
    env.run([sys.executable, "infra/rabbitmq/verify.py", *env.arguments])
    env.check(True, "Topologia e quatro credenciais segregadas passam pelas 38 verificacoes RabbitMQ.")
    before = env.run(env.compose + ["exec", "-T", "rabbitmq", "cat", "/etc/rabbitmq/bootstrap/definitions.json"])
    env.run(env.compose + ["run", "--rm", "--no-deps", "rabbitmq-init"])
    env.check(before == env.run(env.compose + ["exec", "-T", "rabbitmq", "cat", "/etc/rabbitmq/bootstrap/definitions.json"]),
        "Nova execucao do bootstrap preserva definitions e hashes.")
    journey(env)

    # Uma entrega confirmada fica duravel no broker enquanto a notificacao esta parada.
    env.run(env.compose + ["stop", "customer-notification-service"])
    pending = env.new_customer()
    env.until(lambda: env.outbox(pending["id"])["state"] == "PUBLISHED")
    event = env.outbox(pending["id"])
    env.until(lambda: env.count("customer-notification-service.created.v1") == 1)
    env.check(not env.notified(event["event_id"]), "Mensagem confirmada fica no broker com notificacao parada.")
    customers = env.sql("customer_db", "SELECT json_agg(c ORDER BY id)::text FROM customers c")
    inbox = env.sql("customer_db", "SELECT json_agg(i ORDER BY event_id)::text FROM customer_event_inbox i")
    records = env.sql("notification_db", "SELECT json_agg(n ORDER BY customer_id)::text FROM notification_records n")
    env.run(env.compose + ["down"])
    env.check(True, "Compose down executado sem remover volumes do ambiente descartavel.")
    env.start(build=False)
    env.until(lambda: env.notified(event["event_id"]))
    env.check(customers == env.sql("customer_db", "SELECT json_agg(c ORDER BY id)::text FROM customers c")
        and inbox == env.sql("customer_db", "SELECT json_agg(i ORDER BY event_id)::text FROM customer_event_inbox i"),
        "Clientes, exclusao logica, versoes e inbox persistem depois de down/up.")
    previous = env.sql("notification_db", "SELECT json_agg(n ORDER BY customer_id)::text FROM notification_records n WHERE customer_id <> " + str(pending["id"]))
    env.check(previous == records and env.sql("notification_db", "SELECT count(*) FROM notification_records") == "3",
        "Mensagem persistida e recuperada uma vez; notificacoes anteriores permanecem iguais.")
    env.check(env.outbox(pending["id"])["payload"] == event["payload"] and env.count("customer-service.status-change.v1.dlq") == 2,
        "Outbox e quarentena DLQ conservados entre reinicializacoes.")

    env.env["APP_PROFILES"] = "observability"
    env.run(env.compose + ["up", "-d", "customer-service", "customer-notification-service"])
    env.applications_ready()
    env.refresh_token()
    for service in ("customer", "notification"):
        env.check(prometheus(env, service, False)[0] == 401, "Metricas exigem autenticacao apos habilitar perfil: " + service + ".")
        status, text = prometheus(env, service, True)
        env.check(status == 200 and "jvm_memory_used_bytes" in text, "Perfil de observabilidade exporta metricas protegidas: " + service + ".")
    private = env.personal + env.tokens_issued + [value for key, value in env.env.items() if key.endswith("PASSWORD")]
    output = env.logs("customer") + env.logs("notification") + env.run(env.compose + ["logs", "--no-color", "rabbitmq-init"])
    env.check(not any(value in output for value in private), "Logs das aplicacoes/bootstrap sem dados pessoais ou segredos de teste.")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--skip-build", action="store_true", help="Reutiliza as tres imagens :t14 da mesma revisao")
    options = parser.parse_args()
    env = ComposeEnvironment()
    try:
        env.start(build=not options.skip_build)
        validate(env)
    finally:
        env.close()
    print("T14: " + str(env.checks) + " verificacoes aprovadas em Compose descartavel.", flush=True)


if __name__ == "__main__":
    try:
        main()
    except Failure as error:
        print("FALHA: " + str(error), file=sys.stderr)
        sys.exit(1)
    except Exception:
        print("FALHA: verificacao Compose nao concluiu; nenhum segredo ou payload foi exibido.", file=sys.stderr)
        sys.exit(1)
