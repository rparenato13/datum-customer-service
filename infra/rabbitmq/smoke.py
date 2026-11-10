#!/usr/bin/env python3
"""Testa AMQP somente em um broker descartavel criado por este script."""

import argparse
import json
import logging
import os
from pathlib import Path
import socket
import subprocess
import sys
import tempfile
import time
import uuid

try:
    import pika
except ImportError:
    print("Instale infra/rabbitmq/requirements-test.txt em uma .venv, conforme o README.", file=sys.stderr)
    sys.exit(2)

from model import ROOT, USERS, manifest

logging.getLogger("pika").setLevel(logging.CRITICAL)


def free_port():
    with socket.socket() as listener:
        listener.bind(("127.0.0.1", 0))
        return listener.getsockname()[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--all-retries", action="store_true", help="Espera tambem pelos TTLs reais de 30 e 120 s")
    options = parser.parse_args()
    project = "datum-rabbitmq-check-" + uuid.uuid4().hex[:8]
    checks = 0

    def check(condition, description):
        nonlocal checks
        if not condition:
            raise ValueError(description)
        checks += 1
        print("OK: " + description, flush=True)

    with tempfile.TemporaryDirectory(prefix="datum-rabbitmq-check-") as directory:
        env = dict(os.environ, RABBITMQ_DEFINITIONS_FILE=directory + "/definitions.json",
                   RABBITMQ_AMQP_PORT=str(free_port()), RABBITMQ_MANAGEMENT_PORT=str(free_port()))
        for user, variable in USERS.items():
            env[variable] = "test_" + user + "_only"
        compose_arguments = ["-f", str(ROOT / "docker-compose.yml"), "--env-file", str(ROOT / ".env.example"), "-p", project]
        compose = ["docker", "compose", *compose_arguments]

        def run(args):
            result = subprocess.run(args, cwd=ROOT, env=env, capture_output=True, timeout=240)
            if result.returncode:
                # Subprocessos usam apenas segredos de teste. Ainda assim, nao
                # reproduzir mensagens externas ou dumps de configuracao.
                raise ValueError("Comando do ambiente descartavel falhou: " + Path(args[0]).name)
            return result.stdout.decode()

        def connection(user):
            return pika.BlockingConnection(pika.ConnectionParameters(host="127.0.0.1",
                port=int(env["RABBITMQ_AMQP_PORT"]), virtual_host="datum",
                credentials=pika.PlainCredentials(user, env[USERS[user]]), heartbeat=30,
                socket_timeout=5, stack_timeout=10, blocked_connection_timeout=5, connection_attempts=1))

        def publish(user, exchange, key, body, attempt=None):
            with connection(user) as conn:
                channel = conn.channel()
                channel.confirm_delivery()
                payload = json.loads(body)
                channel.basic_publish(exchange=exchange, routing_key=key, body=body, mandatory=True,
                    properties=pika.BasicProperties(content_type="application/json", content_encoding="UTF-8",
                        delivery_mode=2, message_id=payload["eventId"], type=payload["eventType"],
                        correlation_id=payload["eventId"], headers={} if attempt is None else {"x-retry-attempt": attempt}))

        def receive(queue, timeout=10):
            with connection("rabbitmq_admin") as conn:
                channel = conn.channel()
                deadline = time.monotonic() + timeout
                while time.monotonic() < deadline:
                    method, properties, body = channel.basic_get(queue, auto_ack=False)
                    if method:
                        channel.basic_ack(method.delivery_tag)
                        return properties, body
                    time.sleep(0.1)
            raise ValueError("Mensagem esperada nao chegou a fila descartavel.")

        def denied(user, operation):
            forbidden = False
            with connection(user) as conn:
                channel = conn.channel()
                try:
                    channel.confirm_delivery()
                    operation(channel)
                    conn.process_data_events(time_limit=0.1)
                except pika.exceptions.ChannelClosedByBroker as error:
                    forbidden = error.reply_code == 403
            return forbidden

        contract = manifest()
        created, status = contract["consumers"]
        bodies = {}
        for consumer, stem in ((created, "customer-created"), (status, "customer-status-change")):
            payload = json.loads((ROOT / "contracts/events/examples" / (stem + ".v1.json")).read_text())
            payload["eventId"] = str(uuid.uuid4())
            bodies[consumer["id"]] = json.dumps(payload).encode()

        print("Ambiente descartavel: " + project, flush=True)
        try:
            run([sys.executable, "infra/rabbitmq/configure.py", *compose_arguments])
            read_definitions = compose + ["run", "--rm", "--no-deps", "--entrypoint", "cat", "rabbitmq-init", "/output/definitions.json"]
            original = run(read_definitions)
            run([sys.executable, "infra/rabbitmq/configure.py", *compose_arguments])
            check(run(read_definitions) == original, "Provisionamento idempotente preserva hashes e arquivo do volume.")
            run(compose + ["up", "-d", "--wait", "--wait-timeout", "180", "rabbitmq"])
            print(run([sys.executable, "infra/rabbitmq/verify.py", *compose_arguments]), end="", flush=True)

            for consumer, user in ((created, "customer_app"), (status, "status_publisher")):
                body = bodies[consumer["id"]]
                publish(user, "datum.customer.events", consumer["routingKey"], body)
                with connection("notification_app" if consumer == created else "customer_app") as conn:
                    channel = conn.channel()
                    method, properties, actual = channel.basic_get(consumer["queue"], auto_ack=False)
                    check(method is not None and actual == body and properties.delivery_mode == 2,
                          "Publicacao confirmada e consumo permitido: " + consumer["eventType"] + ".")
                    channel.basic_ack(method.delivery_tag)

            for user, exchange, key in (("status_publisher", "datum.customer.events", created["routingKey"]),
                    ("customer_app", "datum.customer.events", status["routingKey"]),
                    ("notification_app", "datum.customer.events", created["routingKey"]),
                    ("status_publisher", "datum.customer.retry", status["retries"][0]["routingKey"])):
                check(denied(user, lambda ch: ch.basic_publish(exchange, key, bodies[created["id"]], mandatory=True)),
                      "Publicacao indevida bloqueada por ACL: " + user + ".")
            for user, queue in (("customer_app", created["queue"]), ("notification_app", status["queue"]),
                                ("status_publisher", created["queue"])):
                check(denied(user, lambda ch: ch.basic_get(queue, auto_ack=False)),
                      "Leitura de fila alheia bloqueada: " + user + ".")
            for user in ("customer_app", "notification_app", "status_publisher"):
                check(denied(user, lambda ch: ch.queue_declare("unauthorized-probe", durable=True, arguments={"x-queue-type": "quorum"})),
                      "Criacao de recursos bloqueada para " + user + ".")
            returned = False
            try:
                publish("rabbitmq_admin", "datum.customer.events", "unroutable.v1", bodies[created["id"]])
            except pika.exceptions.UnroutableError:
                returned = True
            check(returned, "Mandatory detecta mensagem sem binding, apesar de publisher confirms.")

            oversized = False
            try:
                publish("customer_app", "datum.customer.events", created["routingKey"],
                        bodies[created["id"]] + b" " * contract["publication"]["maxBodyBytes"])
            except pika.exceptions.ChannelClosedByBroker as error:
                oversized = error.reply_code == 406
            check(oversized, "Broker rejeita corpo acima do limite de 16 KiB do contrato.")

            publish("customer_app", "datum.customer.events", created["routingKey"], bodies[created["id"]])
            run(compose + ["up", "-d", "--force-recreate", "--wait", "--wait-timeout", "180", "rabbitmq"])
            properties, body = receive(created["queue"])
            check(body == bodies[created["id"]] and properties.delivery_mode == 2,
                  "Mensagem persistente e topologia sobrevivem a recriacao do container com o mesmo volume.")

            publish("status_publisher", "datum.customer.events", status["routingKey"], bodies[status["id"]])
            with connection("customer_app") as conn:
                channel = conn.channel()
                method, _, _ = channel.basic_get(status["queue"], auto_ack=False)
                channel.basic_nack(method.delivery_tag, requeue=False)
            properties, body = receive(status["deadQueue"])
            check(body == bodies[status["id"]] and properties.headers["x-death"][0]["reason"] == "rejected",
                  "Rejeicao sem requeue encaminha ao DLQ do consumidor correto.")

            pending = {}
            start = time.monotonic()
            for consumer, user in ((created, "notification_app"), (status, "customer_app")):
                for attempt, retry in enumerate(consumer["retries"], 1):
                    if attempt > 1 and not options.all_retries:
                        continue
                    payload = json.loads(bodies[consumer["id"]])
                    payload["eventId"] = str(uuid.uuid4())
                    body = json.dumps(payload).encode()
                    publish(user, "datum.customer.retry", retry["routingKey"], body, attempt)
                    pending[payload["eventId"]] = (consumer["queue"], retry["ttlMs"], attempt, body)
            last_progress = start
            with connection("rabbitmq_admin") as conn:
                channel = conn.channel()
                while pending and time.monotonic() - start < 160:
                    for consumer in (created, status):
                        method, properties, body = channel.basic_get(consumer["queue"], auto_ack=False)
                        if method:
                            expected = pending.pop(properties.message_id, None)
                            check(expected is not None and expected[0] == consumer["queue"] and expected[3] == body
                                  and properties.headers["x-retry-attempt"] == expected[2]
                                  and time.monotonic() - start >= expected[1] / 1000 - 0.5,
                                  "TTL real e retorno ao consumidor correto: " + consumer["id"]
                                  + ", rodada " + str(properties.headers["x-retry-attempt"]) + ".")
                            channel.basic_ack(method.delivery_tag)
                    if time.monotonic() - last_progress >= 30:
                        print("Aguardando TTLs reais; " + str(len(pending)) + " mensagens de teste restantes.", flush=True)
                        last_progress = time.monotonic()
                    conn.process_data_events(time_limit=0.2)
            check(not pending, "Todas as filas de retry selecionadas expiraram dentro do prazo de teste.")
            print(run([sys.executable, "infra/rabbitmq/verify.py", *compose_arguments]), end="", flush=True)
            logs = run(compose + ["logs", "--no-color", "rabbitmq"])
            stored = json.loads(run(read_definitions))
            check(not any(env[key] in logs for key in USERS.values())
                  and not any(user["password_hash"] in logs for user in stored["users"]),
                  "Logs do broker sem senhas ou hashes dos testes.")
            print(f"Smoke AMQP: {checks} verificacoes aprovadas. "
                  + ("TTLs de 5, 30 e 120 s testados." if options.all_retries else "TTL de 5 s testado; --all-retries inclui 30/120 s."), flush=True)
        finally:
            run(compose + ["down", "--volumes"])
            print("Somente o ambiente descartavel foi removido; servicos locais preservados.", flush=True)


if __name__ == "__main__":
    try:
        main()
    except (ValueError, KeyError, OSError, subprocess.SubprocessError, pika.exceptions.AMQPError):
        print("FALHA: teste do broker descartavel nao concluiu; nenhum segredo ou payload foi exibido.", file=sys.stderr)
        sys.exit(1)
