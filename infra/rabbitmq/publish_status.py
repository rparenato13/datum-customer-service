#!/usr/bin/env python3
"""Publica solicitacao de status no broker local e preserva arquivo de replay."""

import argparse
import json
import logging
import os
from pathlib import Path
import sys
from datetime import datetime, timezone
import uuid

try:
    import pika
    from jsonschema import Draft202012Validator, FormatChecker
except ImportError:
    print("Instale infra/rabbitmq/requirements-test.txt em uma .venv conforme o README.", file=sys.stderr)
    sys.exit(2)

from model import ROOT, compose_config

logging.getLogger("pika").setLevel(logging.CRITICAL)


def unique_pairs(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("Propriedade duplicada.")
        result[key] = value
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--customer-id", type=int)
    parser.add_argument("--expected-version", type=int)
    parser.add_argument("--status", choices=["ACTIVE", "INACTIVE"])
    parser.add_argument("--file", help="Reenvia os mesmos bytes de um evento salvo, sem gerar outro ID")
    options, compose_arguments = parser.parse_known_args()
    if options.file:
        if any(value is not None for value in (options.customer_id, options.expected_version, options.status)):
            raise ValueError("Use --file sozinho ou os tres campos para uma nova solicitacao.")
        with Path(options.file).open("rb") as stream:
            raw = stream.read(16385)
    else:
        if any(value is None for value in (options.customer_id, options.expected_version, options.status)):
            raise ValueError("Informe --customer-id, --expected-version e --status, ou --file para replay.")
        payload = dict(eventId=str(uuid.uuid4()), eventType="CUSTOMER_STATUS_CHANGE", schemaVersion=1,
                       occurredAt=datetime.now(timezone.utc).isoformat(timespec="microseconds").replace("+00:00", "Z"),
                       customerId=options.customer_id, status=options.status, expectedVersion=options.expected_version)
        raw = (json.dumps(payload, ensure_ascii=False, indent=2) + "\n").encode("utf-8")
    if len(raw) > 16384:
        raise ValueError("Evento excede 16 KiB.")
    payload = json.loads(raw.decode("utf-8"), object_pairs_hook=unique_pairs)
    schema = json.loads((ROOT / "contracts/events/schemas/customer-status-change.v1.schema.json").read_text())
    if list(Draft202012Validator(schema, format_checker=FormatChecker()).iter_errors(payload)):
        raise ValueError("Evento fora do contrato v1; nenhum dado foi enviado.")
    if not options.file:
        folder = ROOT / ".local/rabbitmq/status-events"
        folder.mkdir(parents=True, exist_ok=True, mode=0o700)
        target = folder / (payload["eventId"] + ".json")
        with os.fdopen(os.open(target, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), "wb") as stream:
            stream.write(raw)
        print("Arquivo para replay: " + str(target), flush=True)

    config = compose_config(compose_arguments)
    service = config["services"]["rabbitmq"]
    port = next(entry["published"] for entry in service["ports"] if entry["target"] == 5672)
    parameters = pika.ConnectionParameters(host="127.0.0.1", port=int(port), virtual_host="datum",
        credentials=pika.PlainCredentials("status_publisher", service["environment"]["STATUS_PUBLISHER_RABBITMQ_PASSWORD"]),
        connection_attempts=1, socket_timeout=5, stack_timeout=10, blocked_connection_timeout=5)
    with pika.BlockingConnection(parameters) as connection:
        channel = connection.channel()
        channel.confirm_delivery()
        channel.basic_publish(exchange="datum.customer.events", routing_key="customer.status-change.v1",
            body=raw, mandatory=True, properties=pika.BasicProperties(content_type="application/json",
                content_encoding="UTF-8", delivery_mode=2, message_id=payload["eventId"],
                correlation_id=payload["eventId"], type=payload["eventType"]))
    print("OK: envio confirmado pelo broker. eventId=" + payload["eventId"])
    print("Confira o resultado na API e na inbox; confirm do broker nao significa alteracao aplicada.")


if __name__ == "__main__":
    try:
        main()
    except ValueError:
        print("FALHA: campos/arquivo fora do contrato ou configuracao invalida; nenhum payload foi exibido.", file=sys.stderr)
        sys.exit(1)
    except (OSError, KeyError, pika.exceptions.AMQPError):
        print("FALHA: publicacao nao confirmada; confira broker/credenciais e preserve o arquivo para replay.", file=sys.stderr)
        sys.exit(1)
