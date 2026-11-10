#!/usr/bin/env python3
"""Validacao offline dos contratos T06; nao publica mensagens nem altera servicos."""

from collections import Counter
from copy import deepcopy
import json
from pathlib import Path
import sys

try:
    from jsonschema import Draft202012Validator, FormatChecker
except ImportError:
    print("Instale contracts/events/requirements.txt no ambiente .venv descrito no README.", file=sys.stderr)
    sys.exit(2)


ROOT = Path(__file__).resolve().parent
MAX_BODY_BYTES = 16384
COUNTS = Counter()


def check(condition, group, description):
    if not condition:
        raise ValueError(description)
    COUNTS[group] += 1


def strict_loads(raw):
    if len(raw) > MAX_BODY_BYTES:
        raise ValueError("Corpo maior que 16 KiB.")

    def unique_pairs(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise ValueError("Propriedade duplicada.")
            result[key] = value
        return result

    def invalid_constant(value):
        raise ValueError("Constante numerica fora do JSON.")

    return json.loads(raw.decode("utf-8"), object_pairs_hook=unique_pairs, parse_constant=invalid_constant)


def valid(validator, raw):
    try:
        return not list(validator.iter_errors(strict_loads(raw)))
    except (ValueError, UnicodeError):
        return False


def encoded(payload):
    return json.dumps(payload, ensure_ascii=False, allow_nan=False).encode("utf-8")


def main():
    checker = FormatChecker()
    check(not checker.conforms("2026-02-30T03:00:00Z", "date-time"), "Schema",
          "Validacao de date-time indisponivel; instale rfc3339-validator.")
    validators = {}
    examples = {}
    for stem in ("customer-created", "customer-status-change"):
        schema = strict_loads((ROOT / "schemas" / (stem + ".v1.schema.json")).read_bytes())
        Draft202012Validator.check_schema(schema)
        check("$ref" not in json.dumps(schema), "Schema", "Schemas devem ser autocontidos para validacao offline.")
        validators[stem] = Draft202012Validator(schema, format_checker=checker)
        raw = (ROOT / "examples" / (stem + ".v1.json")).read_bytes()
        check(valid(validators[stem], raw), "Exemplos", "Exemplo valido recusado: " + stem)
        examples[stem] = strict_loads(raw)

    for filename, stem in (
        ("customer-created-with-personal-data.v1.json", "customer-created"),
        ("customer-status-change-without-version.v1.json", "customer-status-change"),
        ("customer-status-change-unknown-status.v1.json", "customer-status-change"),
    ):
        raw = (ROOT / "examples" / "invalid" / filename).read_bytes()
        check(not valid(validators[stem], raw), "Exemplos", "Exemplo invalido aceito: " + filename)

    for stem, original in examples.items():
        validator = validators[stem]
        for field in original:
            payload = deepcopy(original)
            del payload[field]
            check(not valid(validator, encoded(payload)), "Envelope", "Campo obrigatorio aceito como ausente: " + field)
        invalid_values = {
            "eventId": ["", "not-a-uuid", "00000000-0000-0000-0000-000000000000", original["eventId"].upper(), None],
            "eventType": ["OTHER", original["eventType"].lower(), None],
            "schemaVersion": [0, 2, "1", True, None],
            "occurredAt": ["2026-02-30T03:00:00Z", "2026-11-10T03:00:00-03:00", "2026-11-10",
                           "2026-11-10T03:00:00.1234567Z", None],
            "customerId": [0, -1, "123", 1.5, 9223372036854775808, True, None],
        }
        for field, values in invalid_values.items():
            for value in values:
                payload = {**original, field: value}
                check(not valid(validator, encoded(payload)), "Envelope", "Valor invalido aceito no campo " + field)
        for field in ("cpf", "email", "name", "authorization", "unknown"):
            check(not valid(validator, encoded({**original, field: "nao-publicar"})), "Envelope",
                  "Propriedade fora do contrato foi aceita: " + field)
        for customer_id in (1, 9223372036854775807):
            check(valid(validator, encoded({**original, "customerId": customer_id})), "Limites",
                  "Limite valido de customerId recusado.")
        for timestamp in ("2026-11-10T03:00:00Z", "2026-11-10T03:00:00.123456Z"):
            check(valid(validator, encoded({**original, "occurredAt": timestamp})), "Limites",
                  "Timestamp UTC valido recusado.")
        other = examples["customer-status-change" if stem == "customer-created" else "customer-created"]
        check(not valid(validator, encoded(other)), "Compatibilidade", "Tipo de evento errado aceito pelo schema.")

    status = examples["customer-status-change"]
    validator = validators["customer-status-change"]
    for value in ("BLOCKED", "active", "", None, 1):
        check(not valid(validator, encoded({**status, "status": value})), "Status", "Status invalido aceito.")
    for value in (-1, "0", 0.5, 9223372036854775807, True, None):
        check(not valid(validator, encoded({**status, "expectedVersion": value})), "Status", "Versao esperada invalida aceita.")
    for value in (0, 9223372036854775806):
        for business_status in ("ACTIVE", "INACTIVE"):
            check(valid(validator, encoded({**status, "expectedVersion": value, "status": business_status})),
                  "Limites", "Limites validos de status/versao recusados.")

    validator = validators["customer-created"]
    raw = encoded(examples["customer-created"])
    for invalid_raw in (b'{', raw + raw, b'\xff', raw + b' ' * MAX_BODY_BYTES,
                        raw[:-1] + b',"customerId":1}', raw.replace(b'123', b'NaN')):
        check(not valid(validator, invalid_raw), "Transporte JSON", "JSON invalido/excessivo/ambiguo foi aceito.")
    reordered = dict(reversed(list(examples["customer-created"].items())))
    check(valid(validator, json.dumps(reordered, indent=2).encode()), "Compatibilidade",
          "Ordem de campos e espacos nao devem invalidar o evento.")

    topology = strict_loads((ROOT / "topology.v1.json").read_bytes())
    check(topology["proposalStatus"] == "APPROVED" and topology.get("approvalDate") == "2026-11-10"
          and topology.get("approvalSource") == "explicit-user-approval",
          "Topologia", "Registro da aprovacao explicita da T06 ausente ou incoerente.")
    check(topology["contractVersion"] == 1, "Topologia", "Versao de topologia incoerente.")
    exchanges = {entry["name"]: entry for entry in topology["exchanges"]}
    check(len(exchanges) == len(topology["exchanges"]) == 4, "Topologia", "Exchanges ausentes ou duplicados.")
    check(exchanges["datum.customer.events"]["type"] == "topic", "Topologia", "Exchange de dominio deve ser topic.")
    check(all(entry["durable"] for entry in exchanges.values()), "Topologia", "Exchange nao duravel.")
    for name in ("datum.customer.retry", "datum.customer.redelivery", "datum.customer.dead"):
        check(exchanges[name]["type"] == "direct", "Topologia", "Rota interna deve usar exchange direct.")
    defaults = topology["queueDefaults"]
    check(defaults["type"] == "quorum" and defaults["durable"] and not defaults["exclusive"]
          and not defaults["autoDelete"], "Topologia", "Durabilidade/tipo das filas incoerente.")
    check(defaults["overflow"] == "reject-publish" and defaults["maxLength"] > 0 and defaults["maxLengthBytes"] > 0,
          "Topologia", "Filas devem rejeitar excesso em vez de descartar silenciosamente.")
    for key in ("mainQueuePolicy", "retryQueuePolicy"):
        policy = topology[key]
        check(policy["deadLetterStrategy"] == "at-least-once" and policy["deadLetterExchange"] in exchanges,
              "Topologia", "Transferencia dead-letter deve ter destino existente e estrategia explicita.")
    check(topology["retryQueuePolicy"]["deadLetterExchange"] == "datum.customer.redelivery",
          "Topologia", "Retry nao deve voltar ao exchange de dominio e redistribuir a outros consumidores.")
    check(topology["mainQueuePolicy"]["deliveryLimit"] == 5, "Topologia", "Protecao contra redelivery sem limite ausente.")
    publication = topology["publication"]
    check(publication["deliveryMode"] == 2 and publication["mandatory"] and publication["publisherConfirms"],
          "Topologia", "Publicacao deve ser persistente e verificar confirm/return.")
    check(publication["maxBodyBytes"] == MAX_BODY_BYTES, "Topologia", "Limite do transporte diverge do verificador.")
    queues, keys, types = set(), set(), set()
    for consumer in topology["consumers"]:
        check(consumer["eventType"] in {payload["eventType"] for payload in examples.values()},
              "Topologia", "Consumidor aponta para evento sem schema.")
        check(consumer["routingKey"] not in keys and consumer["eventType"] not in types,
              "Topologia", "Rota/evento duplicado na proposta inicial.")
        keys.add(consumer["routingKey"])
        types.add(consumer["eventType"])
        names = [consumer["queue"], consumer["deadQueue"], *[retry["queue"] for retry in consumer["retries"]]]
        check(len(set(names)) == 5 and not queues.intersection(names), "Topologia", "Filas devem ser exclusivas por consumidor.")
        queues.update(names)
        check([retry["ttlMs"] for retry in consumer["retries"]] == [5000, 30000, 120000],
              "Topologia", "Atrasos/numero de retries divergentes.")
        check(all(retry["routingKey"] == retry["queue"] for retry in consumer["retries"]),
              "Topologia", "Cada retry precisa de rota propria.")
    check(len(types) == 2 and len(queues) == 10, "Topologia", "Esperadas duas filas principais, seis de retry e duas DLQs.")

    for group, count in COUNTS.items():
        print(f"OK: {group}: {count} verificacoes.")
    print(f"T06: {sum(COUNTS.values())} verificacoes aprovadas. Contratos APROVADOS em {topology['approvalDate']}.")
    print("Schema/topologia estaticos validados; ACK, transacoes, idempotencia e broker serao testados em T07-T11.")


if __name__ == "__main__":
    try:
        main()
    except (ValueError, KeyError, OSError) as error:
        print("FALHA: " + str(error), file=sys.stderr)
        sys.exit(1)
