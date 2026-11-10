#!/usr/bin/env python3
"""Reenvia um CUSTOMER_CREATED ja PUBLISHED, preservando os bytes do outbox."""

import argparse
import json
import logging
import subprocess
import sys
import uuid

try:
    import pika
    from jsonschema import Draft202012Validator, FormatChecker
except ImportError:
    print("Instale infra/rabbitmq/requirements-test.txt em uma .venv conforme o README.", file=sys.stderr)
    sys.exit(2)

from model import ROOT, compose_config

logging.getLogger("pika").setLevel(logging.CRITICAL)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--event-id", required=True, type=uuid.UUID)
    options, compose_arguments = parser.parse_known_args()
    identifier = str(options.event_id)
    # UUID ja convertido pelo parser; o SQL nao recebe texto arbitrario.
    sql = ("SELECT json_build_object('payload', payload, 'correlationId', correlation_id) FROM customer_outbox WHERE event_id = '" + identifier
           + "'::uuid AND state = 'PUBLISHED' AND event_type = 'CUSTOMER_CREATED'")
    command = ["docker", "compose", *compose_arguments, "exec", "-T", "postgres", "sh", "-ec",
        'export PGPASSWORD="$CUSTOMER_DB_PASSWORD"\n'
        'exec psql --no-psqlrc --host=127.0.0.1 --username=customer_app --dbname=customer_db '
        '--set=ON_ERROR_STOP=1 --no-align --tuples-only --command "$1"', "replay-created", sql]
    result = subprocess.run(command, cwd=ROOT, capture_output=True, timeout=30)
    if result.returncode:
        raise ValueError("Consulta de leitura ao outbox indisponivel.")
    stored = json.loads(result.stdout)
    raw = stored["payload"].encode("utf-8")
    correlation = str(uuid.UUID(stored["correlationId"]))
    if not raw or len(raw) > 16384:
        raise ValueError("Evento PUBLISHED ausente ou fora do limite.")
    payload = json.loads(raw.decode("utf-8"))
    schema = json.loads((ROOT / "contracts/events/schemas/customer-created.v1.schema.json").read_text())
    if (payload.get("eventId") != identifier
            or list(Draft202012Validator(schema, format_checker=FormatChecker()).iter_errors(payload))):
        raise ValueError("Registro fora do contrato v1.")
    config = compose_config(compose_arguments)
    broker = config["services"]["rabbitmq"]
    port = next(entry["published"] for entry in broker["ports"] if entry["target"] == 5672)
    parameters = pika.ConnectionParameters(host="127.0.0.1", port=int(port), virtual_host="datum",
        credentials=pika.PlainCredentials("customer_app", broker["environment"]["CUSTOMER_RABBITMQ_PASSWORD"]),
        connection_attempts=1, socket_timeout=5, stack_timeout=10, blocked_connection_timeout=5)
    with pika.BlockingConnection(parameters) as connection:
        channel = connection.channel()
        channel.confirm_delivery()
        channel.basic_publish(exchange="datum.customer.events", routing_key="customer.created.v1", body=raw,
            mandatory=True, properties=pika.BasicProperties(content_type="application/json", content_encoding="UTF-8",
                delivery_mode=2, message_id=identifier, correlation_id=identifier, type="CUSTOMER_CREATED", headers={"x-correlation-id": correlation}))
    print("OK: replay confirmado pelo broker. eventId=" + identifier)
    print("Outbox somente consultado; confira DUPLICATE e o mesmo registro no notification_db.")


if __name__ == "__main__":
    try:
        main()
    except (ValueError, KeyError, AttributeError):
        print("FALHA: confira eventId, estado PUBLISHED e configuracao; nenhum payload foi exibido.", file=sys.stderr)
        sys.exit(1)
    except (OSError, subprocess.TimeoutExpired, pika.exceptions.AMQPError):
        print("FALHA: replay nao confirmado; confira infraestrutura e repita com o mesmo eventId.", file=sys.stderr)
        sys.exit(1)
