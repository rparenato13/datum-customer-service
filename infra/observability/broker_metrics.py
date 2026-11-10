#!/usr/bin/env python3
"""Snapshot Prometheus das dez filas aprovadas; somente GET, sem ler mensagens."""
import argparse
import base64
import json
from pathlib import Path
import sys
import urllib.parse
import urllib.request

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "rabbitmq"))
from model import ROOT, compose_config


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def render(queues):
    contract = json.loads((ROOT / "contracts/events/topology.v1.json").read_text())
    actual = {row["name"]: row for row in queues if row.get("vhost") == "datum"}
    samples = []
    for consumer in contract["consumers"]:
        expected = [(consumer["queue"], "main"), (consumer["deadQueue"], "dlq")]
        expected += [(retry["queue"], "retry" + str(index)) for index, retry in enumerate(consumer["retries"], 1)]
        for queue, kind in expected:
            values = actual[queue]
            labels = 'consumer="' + consumer["id"] + '",kind="' + kind + '"'
            for suffix, field in [("ready", "messages_ready"), ("unacked", "messages_unacknowledged"), ("total", "messages")]:
                value = values[field]
                if type(value) is not int or value < 0:
                    raise ValueError("Estatistica de fila indisponivel.")
                samples.append('datum_broker_messages_' + suffix + '{' + labels + '} ' + str(value))
    return '\n'.join(['# TYPE datum_broker_collection_up gauge', 'datum_broker_collection_up 1',
        '# TYPE datum_broker_messages_ready gauge', '# TYPE datum_broker_messages_unacked gauge',
        '# TYPE datum_broker_messages_total gauge', *samples]) + '\n'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    _, compose_arguments = parser.parse_known_args()
    config = compose_config(compose_arguments)
    broker = config["services"]["rabbitmq"]
    port = int(next(entry["published"] for entry in broker["ports"] if entry["target"] == 15672))
    password = broker["environment"]["RABBITMQ_ADMIN_PASSWORD"]
    authorization = "Basic " + base64.b64encode(("rabbitmq_admin:" + password).encode()).decode()
    request = urllib.request.Request("http://127.0.0.1:" + str(port) + "/api/queues/datum",
        headers={"Authorization": authorization, "Accept": "application/json"})
    with urllib.request.build_opener(NoRedirect()).open(request, timeout=10) as response:
        queues = json.load(response)
    print(render(queues), end="")


if __name__ == "__main__":
    try:
        main()
    except Exception:
        print('# TYPE datum_broker_collection_up gauge\ndatum_broker_collection_up 0')
        print("FALHA: snapshot indisponivel; confira Management, credenciais e coleta de estatisticas.", file=sys.stderr)
        sys.exit(1)
