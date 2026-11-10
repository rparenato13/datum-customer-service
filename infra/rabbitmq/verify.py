#!/usr/bin/env python3
"""Verifica o RabbitMQ por GETs administrativos, sem consumir ou publicar mensagens."""

import argparse
import base64
import json
import re
import subprocess
import sys
import urllib.error
import urllib.parse
import urllib.request

from model import compose_config, stored_definitions, definitions


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", help="URL HTTP/HTTPS de loopback para o Management")
    options, compose_arguments = parser.parse_known_args()
    config = compose_config(compose_arguments)
    service = config["services"]["rabbitmq"]
    stored = stored_definitions(compose_arguments)
    expected = definitions(service["environment"], stored)
    if stored != expected:
        raise ValueError("Definitions locais divergem do contrato/.env; execute configure.py.")
    port = next(entry["published"] for entry in service["ports"] if entry["target"] == 15672)
    base = options.base_url or "http://localhost:" + str(port)
    parsed = urllib.parse.urlsplit(base)
    if (parsed.scheme not in ("http", "https") or parsed.hostname not in ("localhost", "127.0.0.1", "::1")
            or parsed.username is not None or parsed.path not in ("", "/") or parsed.query or parsed.fragment):
        raise ValueError("A verificacao aceita somente URL de loopback sem credenciais, caminho, query ou fragmento.")
    authorization = "Basic " + base64.b64encode(("rabbitmq_admin:" + service["environment"]["RABBITMQ_ADMIN_PASSWORD"]).encode()).decode()
    opener = urllib.request.build_opener(NoRedirect())

    def get(path):
        request = urllib.request.Request(base.rstrip("/") + path,
                                         headers={"Authorization": authorization, "Accept": "application/json"})
        with opener.open(request, timeout=15) as response:
            return json.load(response)

    checks = 0

    def check(condition, description):
        nonlocal checks
        if not condition:
            raise ValueError(description)
        checks += 1
        print("OK: " + description, flush=True)

    check(get("/api/overview")["rabbitmq_version"] == "4.4.0", "RabbitMQ 4.4.0 responde no Management.")
    check(get("/api/health/checks/alarms").get("status") == "ok", "Broker sem alarmes locais.")
    check({entry["name"] for entry in get("/api/vhosts")} == {"datum"}, "Vhost datum isolado, sem vhost padrao criado.")
    users = get("/api/users")
    check({entry["name"] for entry in users} == {user["name"] for user in expected["users"]}, "Quatro contas segregadas; guest ausente.")
    check(all(("administrator" in entry["tags"]) == (entry["name"] == "rabbitmq_admin") for entry in users),
          "Somente rabbitmq_admin possui papel administrativo.")
    for desired in expected["permissions"]:
        actual = get("/api/permissions/datum/" + desired["user"])
        check(all(actual[key] == desired[key] for key in ("configure", "write", "read")),
              "Permissoes de recursos corretas: " + desired["user"] + ".")
    topic_permissions = get("/api/topic-permissions")
    check(sorted(topic_permissions, key=lambda entry: entry["user"]) == sorted(expected["topic_permissions"], key=lambda entry: entry["user"]),
          "Publicacao por routing key de dominio restrita por papel.")
    for desired in expected["exchanges"]:
        actual = get("/api/exchanges/datum/" + desired["name"])
        check(all(actual[key] == desired[key] for key in ("type", "durable", "auto_delete", "internal")),
              "Exchange correto: " + desired["name"] + ".")
    queues = {entry["name"]: entry for entry in get("/api/queues/datum")}
    check(set(queues) == {entry["name"] for entry in expected["queues"]}, "Dez filas previstas, sem filas adicionais.")
    policies = get("/api/policies/datum")
    check({entry["name"] for entry in policies} == {entry["name"] for entry in expected["policies"]},
          "Dez politicas previstas no vhost.")
    check(not get("/api/operator-policies/datum"), "Sem operator policies alterando os limites aprovados.")
    for desired in expected["queues"]:
        actual = queues[desired["name"]]
        check(actual["durable"] and not actual["auto_delete"] and not actual["exclusive"]
              and actual["type"] == "quorum" and actual["state"] == "running",
              "Fila quorum duravel e disponivel: " + desired["name"] + ".")
        policy = next(entry["definition"] for entry in expected["policies"] if entry["name"] == desired["name"])
        # Campos estatisticos da fila podem ainda nao ter sido coletados logo
        # apos o boot. Validar a policy selecionada por tipo/pattern/prioridade.
        matching = [entry for entry in policies if entry["apply-to"] in ("all", "queues", "quorum_queues")
                    and re.search(entry["pattern"], desired["name"])]
        selected = max(matching, key=lambda entry: entry["priority"])
        check(selected["definition"] == policy and actual.get("effective_policy_definition", policy) == policy,
              "Politica efetiva correta: " + desired["name"] + ".")
    bindings = get("/api/bindings/datum")
    actual_bindings = {(entry["source"], entry["routing_key"], entry["destination"])
                       for entry in bindings if entry["source"].startswith("datum.customer.")}
    desired_bindings = {(entry["source"], entry["routing_key"], entry["destination"]) for entry in expected["bindings"]}
    check(actual_bindings == desired_bindings, "Doze bindings corretos; retries retornam apenas a fila de origem.")
    print(f"RabbitMQ: {checks} verificacoes aprovadas; nenhuma mensagem publicada, consumida ou removida.")


if __name__ == "__main__":
    try:
        main()
    except ValueError as error:
        print("FALHA: " + str(error), file=sys.stderr)
        sys.exit(1)
    except (KeyError, OSError, urllib.error.URLError, subprocess.SubprocessError):
        print("FALHA: confira o broker, a porta e as credenciais atuais; nenhum segredo foi exibido.", file=sys.stderr)
        sys.exit(1)
