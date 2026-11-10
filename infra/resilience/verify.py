#!/usr/bin/env python3
"""Injeta falhas somente em Compose, bancos e filas descartaveis criados aqui."""

import argparse
import json
import re
import subprocess
import sys
import time
import uuid

try:
    import pika
    from environment import Environment, Failure
except ImportError:
    print("Instale infra/rabbitmq/requirements-test.txt em uma .venv conforme o runbook.", file=sys.stderr)
    sys.exit(2)

CREATED = "customer-notification-service.created.v1"
STATUS = "customer-service.status-change.v1"


def recovered(env, customer):
    event = env.outbox(customer["id"])
    return event and event["state"] == "PUBLISHED" and env.notified(event["event_id"])


def score_failures(env):
    print("Cenario: Score lento, fora do ar e recuperado.", flush=True)
    customer = env.new_customer("16899535009")
    path = "/customers/" + str(customer["id"]) + "/score"
    env.check(env.api("GET", path)[0] == 200, "Score saudavel retorna 200.")
    env.run(env.compose + ["stop", "wiremock"])
    start = time.monotonic()
    status, body = env.api("GET", path)
    print("Diagnostico Score fora: HTTP " + str(status) + ", code=" + str(body.get("code"))
          + ", espera=" + str(round(time.monotonic() - start, 2)) + " s.", flush=True)
    env.check(status in (503, 504) and body["code"] == {503: "SCORE_UNAVAILABLE", 504: "SCORE_TIMEOUT"}.get(status)
              and time.monotonic() - start < 8,
              "Score fora retorna indisponibilidade ou timeout de conexao coerente, em espera limitada.")
    env.check(env.api("GET", "/customers/" + str(customer["id"]))[0] == 200,
              "Falha do Score nao impede leitura do cliente.")
    env.run(env.compose + ["up", "-d", "--wait", "wiremock"])
    env.until(lambda: env.api("GET", path)[0] == 200)
    # A fixture lenta oficial e identificada no manifesto de mappings, sem
    # habilitar journal nem registrar corpos do provedor.
    from pathlib import Path
    from environment import ROOT
    mapping = next(json.loads(p.read_text()) for p in (ROOT / "infra/wiremock/mappings").glob("*.json")
                   if json.loads(p.read_text()).get("response", {}).get("fixedDelayMilliseconds") == 3000)
    slow_cpf = mapping["request"].get("urlPath", mapping["request"].get("url", "")).rsplit("/", 1)[-1]
    slow = env.new_customer(slow_cpf)
    start = time.monotonic()
    status, body = env.api("GET", "/customers/" + str(slow["id"]) + "/score")
    env.check(status == 504 and body["code"] == "SCORE_TIMEOUT" and time.monotonic() - start < 5,
              "Fixture lenta retorna 504 sem esperar os tres segundos do provedor.")


def broker_and_restart(env):
    print("Cenario: broker fora, cadastro confirmado e reinicio com pendencia.", flush=True)
    env.run(env.compose + ["stop", "rabbitmq"])
    customer = env.new_customer()
    before = env.outbox(customer["id"])
    env.until(lambda: env.outbox(customer["id"])["attempt_count"] > 0)
    env.check(env.outbox(customer["id"])["state"] == "PENDING", "Broker fora conserva evento PENDING com tentativa registrada.")
    env.check(env.api("GET", "/actuator/health", auth=False)[0] == 200, "API permanece UP enquanto broker esta indisponivel.")
    env.run(["docker", "restart", env.customer])
    env.until(lambda: env.api("GET", "/actuator/health", auth=False)[0] == 200)
    env.check(env.outbox(customer["id"])["event_id"] == before["event_id"], "Reinicio preserva UUID da pendencia.")
    env.run(env.compose + ["up", "-d", "--wait", "rabbitmq"])
    env.until(lambda: recovered(env, customer), timeout=100, description="broker, reserva/backoff e consumidores")
    after = env.outbox(customer["id"])
    env.check(after["payload"] == before["payload"] and after["occurred_at"] == before["occurred_at"],
              "Retorno do broker publica os mesmos bytes/instante e produz uma notificacao.")
    return customer


def database_outage(env):
    print("Cenario: PostgreSQL fora e recuperacao dos pools.", flush=True)
    before = env.sql("customer_db", "SELECT count(*) FROM customers")
    env.run(env.compose + ["stop", "postgres"])
    try:
        start = time.monotonic()
        status, health = env.api("GET", "/actuator/health", auth=False)
        env.check(status == 503 and health == {"status": "DOWN"} and time.monotonic() - start < 10,
                  "Health detecta banco fora em espera limitada e sem detalhes internos.")
        start = time.monotonic()
        status, problem = env.api("POST", "/customers", dict(name="Database Offline", cpf="52998224725",
            email="offline@example.test", status="ACTIVE"))
        env.check(status == 500 and problem["code"] == "INTERNAL_ERROR" and time.monotonic() - start < 10,
                  "Banco fora retorna 500 sanitizado sem espera indefinida por conexao.")
    finally:
        env.run(env.compose + ["up", "-d", "--wait", "postgres"])
    env.until(lambda: env.api("GET", "/actuator/health", auth=False)[0] == 200, timeout=90)
    env.until(lambda: env.api("GET", "/actuator/health", auth=False, service="notification")[0] == 200, timeout=90)
    env.check(env.sql("customer_db", "SELECT count(*) FROM customers") == before,
              "Falha anterior a transacao nao deixou cadastro parcial.")
    customer = env.new_customer()
    env.until(lambda: recovered(env, customer))
    env.check(True, "Pools e consumidores voltam a processar depois da recuperacao do banco.")


def notification_outage(env):
    print("Cenario: somente conexoes de notificacao indisponiveis.", flush=True)
    env.sql("postgres", "ALTER ROLE notification_app NOLOGIN; SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE usename = 'notification_app'")
    try:
        customer = env.new_customer()
        env.until(lambda: env.count(CREATED + ".retry.1") >= 1)
        event = env.outbox(customer["id"])
        env.check(event["state"] == "PUBLISHED" and not env.notified(event["event_id"]),
                  "Falha da notificacao nao reverte cadastro nem marca processamento inexistente.")
    finally:
        env.sql("postgres", "ALTER ROLE notification_app LOGIN")
    env.until(lambda: recovered(env, customer))
    env.check(True, "Retry registra notificacao depois de restaurar somente suas conexoes.")


def retry_exhaustion(env):
    print("Cenario: tres TTLs reais e exaustao dos dois consumidores (cerca de 155 s).", flush=True)
    env.run(["docker", "stop", env.notification])
    customer = env.new_customer()
    env.until(lambda: env.outbox(customer["id"])["state"] == "PUBLISHED")
    created = env.outbox(customer["id"])
    status_event = env.status_event(customer["id"])
    env.sql("notification_db", "ALTER TABLE notification_event_inbox ADD CONSTRAINT resilience_failure CHECK (event_id <> '"
            + created["event_id"] + "') NOT VALID")
    env.sql("customer_db", "ALTER TABLE customer_event_inbox ADD CONSTRAINT resilience_failure CHECK (event_id <> '"
            + status_event["eventId"] + "') NOT VALID")
    try:
        env.run(["docker", "start", env.notification])
        status_bytes = env.publish("status_publisher", status_event)
        for attempt in (1, 2, 3):
            env.until(lambda: env.count(CREATED + ".retry." + str(attempt)) == 1
                      and env.count(STATUS + ".retry." + str(attempt)) == 1,
                      timeout=45 if attempt < 3 else 60, description="entrada na rodada " + str(attempt))
            env.check(True, "Ambos os consumidores transferiram com confirm para retry " + str(attempt) + ".")
        env.until(lambda: env.count(CREATED + ".dlq") == 1 and env.count(STATUS + ".dlq") == 1,
                  timeout=150, description="TTL de 120 s e DLQs")
        first = env.get(CREATED + ".dlq")
        second = env.get(STATUS + ".dlq")
        env.check(first[2] == created["payload"].encode() and second[2] == status_bytes,
                  "Exaustao preserva os mesmos bytes nas DLQs, sem descarte silencioso.")
        env.check(first[1].headers["x-retry-attempt"] == 3 and second[1].headers["x-retry-attempt"] == 3,
                  "DLQs receberam as falhas da terceira rodada, sem retry 4.")
        env.check(not env.notified(created["event_id"]), "Exaustao de notificacao nao deixou efeito parcial.")
        current = env.api("GET", "/customers/" + str(customer["id"]))[1]
        env.check(current["status"] == "ACTIVE" and current["version"] == 0,
                  "Exaustao de status nao alterou cliente nem versao.")
        env.check(env.sql("customer_db", "SELECT count(*) FROM customer_event_inbox WHERE event_id = '" + status_event["eventId"] + "'") == "0",
                  "Falhas transacionais nao gravaram deduplicacao falsa.")
    finally:
        env.sql("notification_db", "ALTER TABLE notification_event_inbox DROP CONSTRAINT resilience_failure")
        env.sql("customer_db", "ALTER TABLE customer_event_inbox DROP CONSTRAINT resilience_failure")
    # As DLQs nao possuem consumidor automatico. Recuperar o destino nao faz replay.
    env.check(env.count(CREATED + ".dlq") == 1 and env.count(STATUS + ".dlq") == 1,
              "DLQs continuam em quarentena apos remover a causa da falha.")
    env.publish("customer_app", json.loads(created["payload"]), created["payload"].encode())
    env.publish("status_publisher", status_event, status_bytes)
    env.until(lambda: env.notified(created["event_id"]) and env.api("GET", "/customers/" + str(customer["id"]))[1]["version"] == 1)
    env.check(True, "Replay explicito dos mesmos IDs recupera falhas transitorias sem duplicar efeito.")


def confirm_ack_window(env):
    print("Cenario: perda de conexao entre confirm do retry e ACK da origem.", flush=True)
    env.run(["docker", "stop", env.notification])
    customer = env.new_customer()
    env.until(lambda: env.outbox(customer["id"])["state"] == "PUBLISHED")
    created = env.outbox(customer["id"])
    env.run(["docker", "stop", env.customer])
    status = env.status_event(customer["id"])
    env.publish("status_publisher", status)
    for queue, user in ((CREATED, "notification_app"), (STATUS, "customer_app")):
        with env.connection(user) as connection:
            channel = connection.channel()
            method, properties, body = channel.basic_get(queue, auto_ack=False)
            env.check(method is not None, "Origem disponivel para janela de confirm/ACK: " + queue + ".")
            env.publish(user, json.loads(body), body, exchange="datum.customer.retry", key=queue + ".retry.1", attempt=1)
            # Fechar sem ACK deixa original + copia confirmada no retry.
    env.run(["docker", "start", env.customer, env.notification])
    env.until(lambda: env.notified(created["event_id"]) and env.api("GET", "/customers/" + str(customer["id"]))[1]["version"] == 1)
    # Aguarda tambem a copia de retry: o primeiro efeito nao comprova deduplicacao.
    env.until(lambda: ("eventId=" + created["event_id"] + " code=DUPLICATE") in env.logs("notification")
              and ("eventId=" + status["eventId"] + " code=DUPLICATE") in env.logs("customer"), timeout=30)
    env.check(env.sql("notification_db", "SELECT count(*) FROM notification_records WHERE event_id = '" + created["event_id"] + "'") == "1"
              and env.api("GET", "/customers/" + str(customer["id"]))[1]["version"] == 1,
              "Original e retry apos falha de ACK geram um unico efeito por consumidor.")


def saturation(env):
    print("Cenario: fila principal saturada rejeita envio e conserva outbox.", flush=True)
    env.run(["docker", "stop", env.notification])
    env.until(lambda: env.count(CREATED) == 0)
    env.policy(CREATED, **{"max-length": 1})
    env.until(lambda: env.management("GET", "queues/datum/" + CREATED).get("effective_policy_definition", {}).get("max-length") == 1)
    try:
        first = env.new_customer()
        env.until(lambda: env.outbox(first["id"])["state"] == "PUBLISHED")
        customers = [first]
        second = None
        # Quorum pode admitir algumas publicacoes enquanto propaga o estado de
        # overflow ao canal. O aceite e NACK efetivo, nao contagem exata igual a 1.
        for _ in range(8):
            candidate = env.new_customer()
            customers.append(candidate)
            env.until(lambda: env.outbox(candidate["id"])["attempt_count"] > 0)
            env.until(lambda: env.outbox(candidate["id"])["state"] == "PUBLISHED"
                      or env.outbox(candidate["id"])["last_error_code"] is not None)
            result = env.outbox(candidate["id"])
            if result["last_error_code"] == "NACK":
                second = candidate
                break
        if second is None:
            raise Failure("Fila reduzida nao produziu NACK dentro do numero limitado de publicacoes.")
        original = env.outbox(second["id"])
        env.check(original["state"] == "PENDING" and env.count(CREATED) >= 1,
                  "Confirm negativo por saturacao conserva evento PENDING e mensagens anteriores na fila.")
        _, properties, _ = env.get(CREATED)
        env.check(properties.message_id == env.outbox(first["id"])["event_id"], "Primeiro evento permanece na fila, sem drop-head.")
    finally:
        env.policy(CREATED)
        env.run(["docker", "start", env.notification])
    env.until(lambda: all(recovered(env, customer) for customer in customers))
    env.check(env.outbox(second["id"])["payload"] == original["payload"],
              "Capacidade restaurada permite entrega eventual sem alterar bytes do evento.")


def blocked_dead_letter_destination(env):
    print("Cenario: destino de DLQ temporariamente sem binding.", flush=True)
    setting = env.run(env.compose + ["exec", "-T", "rabbitmq", "rabbitmqctl", "eval",
        "application:get_env(rabbit, dead_letter_worker_publisher_confirm_timeout)."])
    match = re.search(r"\{ok,\s*(\d+)\}", setting)
    if match is None or not 1000 <= int(match[1]) <= 300000:
        raise Failure("Intervalo interno de dead-letter fora do limite esperado pelo teste.")
    recovery_seconds = int(match[1]) / 1000
    print("Intervalo interno de recuperacao dead-letter: " + str(int(recovery_seconds)) + " s.", flush=True)
    queue = STATUS + ".dlq"
    # Somente a quarentena artificial desta suite e esvaziada para isolar a prova.
    with env.connection() as connection:
        channel = connection.channel()
        channel.queue_purge(queue)
        channel.queue_unbind(queue, "datum.customer.dead", STATUS)
    rejected = env.status_event(9223372036854775807)
    raw = env.publish("status_publisher", rejected)
    try:
        env.until(lambda: ("eventId=" + rejected["eventId"] + " code=CUSTOMER_NOT_FOUND") in env.logs("customer"))
        env.check(env.count(queue) == 0, "Rejeicao permanente ocorreu enquanto DLQ nao tinha rota.")
        end = time.monotonic() + 3
        while time.monotonic() < end:
            if env.count(queue) != 0:
                raise Failure("Mensagem chegou ao destino sem binding.")
            time.sleep(0.2)
    finally:
        with env.connection() as connection:
            connection.channel().queue_bind(queue, "datum.customer.dead", STATUS)
    env.until(lambda: env.count(queue) == 1, timeout=recovery_seconds + 60, description="recuperacao do dead-letter worker")
    method, properties, body = env.get(queue)
    env.check(method is not None and properties.message_id == rejected["eventId"] and body == raw,
              "Destino restaurado recebe a rejeicao intacta, comprovando retencao at-least-once.")


def delivery_limit(env):
    print("Cenario: delivery-limit limita repetidos crashes sem ACK.", flush=True)
    env.run(["docker", "stop", env.customer, env.notification])
    for queue, user, event_type in ((CREATED, "customer_app", "CUSTOMER_CREATED"), (STATUS, "status_publisher", "CUSTOMER_STATUS_CHANGE")):
        with env.connection() as connection:
            channel = connection.channel()
            channel.queue_purge(queue)
            channel.queue_purge(queue + ".dlq")
        payload = env.status_event(123)
        if event_type == "CUSTOMER_CREATED":
            payload = {key: value for key, value in payload.items() if key not in ("status", "expectedVersion")}
            payload["eventType"] = event_type
        original = env.publish(user, payload)
        for _ in range(9):
            env.until(lambda: env.count(queue) > 0 or env.count(queue + ".dlq") > 0, timeout=10)
            if env.count(queue + ".dlq"):
                break
            method, _, _ = env.get(queue)  # fechamento sem ACK simula crash fisico
            if method is None:
                break
        env.until(lambda: env.count(queue + ".dlq") == 1)
        method, properties, body = env.get(queue + ".dlq")
        env.check(method is not None and body == original and properties.headers["x-death"][0]["reason"] == "delivery_limit",
                  "Crashes repetidos terminam em DLQ por delivery_limit: " + queue + ".")
        env.check(env.count(queue) == 0, "Fila principal sem ciclo infinito: " + queue + ".")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--skip-build", action="store_true", help="Reutiliza imagens locais :t11 ja construidas desta revisao")
    scenarios = {"score": score_failures, "broker": broker_and_restart, "database": database_outage,
        "notification": notification_outage, "retries": retry_exhaustion, "ack": confirm_ack_window,
        "saturation": saturation, "dlq": blocked_dead_letter_destination, "delivery-limit": delivery_limit}
    parser.add_argument("--scenario", choices=list(scenarios), nargs="+", help="Executa somente os cenarios escolhidos, em ambiente novo")
    options = parser.parse_args()
    env = Environment()
    try:
        env.start(build=not options.skip_build)
        for scenario in options.scenario or scenarios:
            scenarios[scenario](env)
        private = [value for key, value in env.env.items() if key.endswith("PASSWORD")] + env.tokens_issued + env.personal
        output = env.logs("customer") + env.logs("notification")
        env.check(not any(value in output for value in private), "Logs das aplicacoes sem senhas, tokens ou dados pessoais de teste.")
        print("T11: " + str(env.checks) + " verificacoes aprovadas em ambiente descartavel.", flush=True)
    finally:
        env.close()


if __name__ == "__main__":
    try:
        main()
    except Failure as error:
        print("FALHA: " + str(error), file=sys.stderr)
        sys.exit(1)
    except (ValueError, KeyError, OSError, subprocess.SubprocessError, pika.exceptions.AMQPError):
        print("FALHA: suite descartavel nao concluiu; nenhum segredo ou payload foi exibido.", file=sys.stderr)
        sys.exit(1)
