#!/usr/bin/env python3
"""T13: jornada HTTP/eventos real somente em ambiente descartavel proprio."""
import argparse
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import subprocess
import sys
import time
import urllib.error
import urllib.request
import uuid

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "resilience"))
try:
    from environment import Environment, Failure, keycloak
except ImportError:
    print("Instale infra/rabbitmq/requirements-test.txt na .venv conforme o runbook.", file=sys.stderr)
    sys.exit(2)

STATUS = "customer-service.status-change.v1"


def request(env, method, path, payload=None, role="ADMIN", service="customer"):
    headers = {"Content-Type": "application/json"}
    if role == "ADMIN":
        if time.monotonic() >= env.token_until:
            env.refresh_token()
        headers["Authorization"] = "Bearer " + env.token
    elif role == "USER":
        headers["Authorization"] = "Bearer " + env.user_token
    elif role == "INVALID":
        headers["Authorization"] = "Bearer invalid_smoke_token"
    elif role is not None:
        raise Failure("Perfil de teste desconhecido.")
    req = urllib.request.Request("http://127.0.0.1:" + env.ports[service] + path,
        method=method, headers=headers, data=None if payload is None else json.dumps(payload).encode())
    try:
        response = urllib.request.urlopen(req, timeout=25)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        raw = response.read().decode()
        body = json.loads(raw) if raw and "json" in response.headers.get("Content-Type", "") else raw
        return response.status, body, response.headers


def inbox(env, event):
    identifier = str(uuid.UUID(event["eventId"]))
    return env.sql("customer_db", "SELECT outcome || ':' || coalesce(reason_code,'') FROM customer_event_inbox "
        "WHERE event_id = '" + identifier + "'")


def notification(env, identifier):
    identifier = str(uuid.UUID(identifier))
    return env.sql("notification_db", "SELECT row_to_json(n)::text FROM notification_records n WHERE event_id = '" + identifier + "'")


def journey(env):
    for service in ("customer", "notification"):
        status, body, _ = request(env, "GET", "/actuator/health", role=None, service=service)
        env.check(status == 200 and body == {"status": "UP"}, "Health publico e sanitizado: " + service + ".")
    _, discovery = keycloak.request_json("http://localhost:" + env.ports["keycloak"] + "/realms/datum/.well-known/openid-configuration")
    code, verifier = keycloak.login_code(discovery, "api-user", env.env["KEYCLOAK_DEMO_USER_PASSWORD"])
    status, tokens = keycloak.exchange(discovery, code, verifier)
    env.check(status == 200 and "access_token" in tokens, "USER autentica por codigo + PKCE no Keycloak real.")
    env.user_token = tokens["access_token"]
    env.tokens_issued.append(env.user_token)
    env.tokens_issued.append("invalid_smoke_token")
    for role in (None, "INVALID"):
        status, body, _ = request(env, "GET", "/customers", role=role)
        env.check(status == 401 and body.get("code") == "AUTHENTICATION_REQUIRED", "Token ausente/invalido retorna 401 padronizado.")

    status, api, _ = request(env, "GET", "/v3/api-docs", role=None)
    required = {"/customers", "/customers/{id}", "/customers/search", "/customers/{id}/score"}
    env.check(status == 200 and api.get("openapi", "").startswith("3.") and required <= set(api.get("paths", {})),
        "OpenAPI publico descreve os caminhos de clientes e Score.")
    schemes = api.get("components", {}).get("securitySchemes", {}).values()
    env.check(any(scheme.get("type") == "http" and scheme.get("scheme") == "bearer" for scheme in schemes),
        "OpenAPI documenta autenticacao Bearer.")
    env.check(request(env, "GET", "/swagger-ui/index.html", role=None)[0] == 200, "Swagger UI disponivel na imagem real.")

    payload = dict(name="Ana Smoke", cpf="16899535009", email="ana.smoke@example.test", status="ACTIVE")
    second = dict(name="Bruno Smoke", cpf="39053344705", email="bruno.smoke@example.test", status="INACTIVE")
    env.personal.extend([payload["cpf"], payload["email"], payload["name"], second["cpf"], second["email"], second["name"]])
    env.check(request(env, "POST", "/customers", payload, role="USER")[0] == 403, "USER nao cadastra clientes.")
    invalid = dict(name="", cpf="123", email="invalid", status="ACTIVE")
    status, body, _ = request(env, "POST", "/customers", invalid)
    env.check(status == 400 and body.get("code") == "VALIDATION_ERROR", "ADMIN recebe 400 para dados invalidos.")
    env.check(env.sql("customer_db", "SELECT count(*) FROM customers") == "0"
        and env.sql("customer_db", "SELECT count(*) FROM customer_outbox") == "0", "Requisicoes recusadas nao criam cadastro nem evento.")

    status, customer, headers = request(env, "POST", "/customers", payload)
    env.check(status == 201 and customer.get("version") == 0 and headers.get("Location") == "/customers/" + str(customer.get("id")),
        "ADMIN cadastra cliente, version 0 e Location corretos.")
    identifier = customer["id"]
    location = "/customers/" + str(identifier)
    status, other, _ = request(env, "POST", "/customers", second)
    env.check(status == 201, "Segundo cadastro permite verificar pagina e filtro reais.")
    status, body, _ = request(env, "POST", "/customers", payload)
    env.check(status == 409 and body.get("code") == "CPF_ALREADY_REGISTERED", "CPF repetido retorna 409.")
    status, body, headers = request(env, "GET", location, role="USER")
    env.check(status == 200 and body == customer and headers.get("Set-Cookie") is None, "USER consulta cadastro sem sessao.")
    status, page, _ = request(env, "GET", "/customers?page=0&size=1", role="USER")
    env.check(status == 200 and page.get("totalElements") == 2 and page.get("totalPages") == 2
        and [row["id"] for row in page.get("content", [])] == [identifier], "Paginacao e ordenacao observam os dois cadastros.")
    status, page, _ = request(env, "GET", "/customers?status=INACTIVE", role="USER")
    env.check(status == 200 and [row["id"] for row in page.get("content", [])] == [other["id"]], "Filtro retorna somente o cliente INACTIVE.")
    status, page, _ = request(env, "GET", "/customers/search?name=ANA&status=ACTIVE", role="USER")
    env.check(status == 200 and [row["id"] for row in page.get("content", [])] == [identifier], "Busca por nome ignora caixa e combina status.")
    status, score, _ = request(env, "GET", location + "/score", role="USER")
    env.check(status == 200 and score.get("score") == 750 and score.get("classification") == "LOW_RISK", "USER consulta Score pela API e pelo provedor real simulado.")

    for current in (customer, other):
        env.until(lambda: env.notified(env.outbox(current["id"])["event_id"]))
    created = env.outbox(identifier)
    env.check(created["state"] == "PUBLISHED" and env.sql("notification_db", "SELECT count(*) FROM notification_records") == "2",
        "Dois cadastros produzem outbox confirmado e duas notificacoes no banco proprio.")
    original = notification(env, created["event_id"])
    env.run([sys.executable, "infra/rabbitmq/replay_created.py", "--event-id", created["event_id"], *env.arguments])
    env.until(lambda: "eventId=" + created["event_id"] + " code=DUPLICATE" in env.logs("notification"))
    env.check(notification(env, created["event_id"]) == original, "Replay de criacao preserva notification_id e registered_at.")

    changed = dict(payload, name="Ana Smoke Atualizada")
    env.personal.append(changed["name"])
    env.check(request(env, "PUT", location, changed, role="USER")[0] == 403, "USER nao altera cliente.")
    env.check(request(env, "DELETE", location, role="USER")[0] == 403, "USER nao exclui cliente.")
    status, updated, _ = request(env, "PUT", location, changed)
    env.check(status == 200 and updated.get("name") == changed["name"] and updated.get("version") == 1,
        "ADMIN atualiza cliente e incrementa versao.")
    event = env.status_event(identifier, updated["version"])
    raw = env.publish("status_publisher", event)
    env.until(lambda: inbox(env, event) == "APPLIED:")
    status, applied, _ = request(env, "GET", location, role="USER")
    env.check(status == 200 and applied.get("status") == "INACTIVE" and applied.get("version") == 2,
        "Evento de status produz alteracao confirmada e inbox APPLIED.")
    env.publish("status_publisher", event, raw=raw)
    env.until(lambda: "eventId=" + event["eventId"] + " code=DUPLICATE" in env.logs("customer"))
    env.check(request(env, "GET", location, role="USER")[1] == applied, "Evento duplicado nao altera versao nem updatedAt.")
    conflict = dict(env.status_event(identifier, updated["version"]), status="ACTIVE")
    env.publish("status_publisher", conflict)
    env.until(lambda: inbox(env, conflict) == "REJECTED:VERSION_CONFLICT")
    env.until(lambda: env.count(STATUS + ".dlq") == 1)
    env.check(request(env, "GET", location, role="USER")[1] == applied, "Versao obsoleta e rejeitada para DLQ sem mudar cliente.")
    malformed = dict(env.status_event(identifier, applied["version"]), unexpected="not_allowed")
    env.publish("status_publisher", malformed)
    env.until(lambda: env.count(STATUS + ".dlq") == 2)
    env.check(inbox(env, malformed) == "" and request(env, "GET", location, role="USER")[1] == applied,
        "Payload fora do contrato vai a DLQ sem efeito nem falsa deduplicacao.")

    env.check(request(env, "DELETE", location)[0] == 204, "ADMIN executa exclusao logica com 204.")
    for method in ("GET", "DELETE"):
        status, body, _ = request(env, method, location)
        env.check(status == 404 and body.get("code") == "CUSTOMER_NOT_FOUND", "Cliente excluido retorna 404: " + method + ".")
    row = env.sql("customer_db", "SELECT count(*) FROM customers WHERE id = " + str(identifier) + " AND deleted_at IS NOT NULL AND status='INACTIVE'")
    env.check(row == "1", "Exclusao preserva linha e estado de negocio INACTIVE no banco.")
    status, page, _ = request(env, "GET", "/customers", role="USER")
    env.check(status == 200 and [row["id"] for row in page.get("content", [])] == [other["id"]], "Listagem omite cliente excluido.")
    env.check(request(env, "POST", "/customers", payload)[0] == 409, "CPF permanece reservado depois da exclusao logica.")
    env.check(env.sql("customer_db", "SELECT count(*) FROM customer_outbox") == "2"
        and env.sql("notification_db", "SELECT count(*) FROM notification_records") == "2", "PUT, status, duplicata e DELETE nao criam novos eventos de cadastro.")
    private = env.personal + env.tokens_issued + [value for key, value in env.env.items() if key.endswith("PASSWORD")]
    output = env.logs("customer") + env.logs("notification")
    env.check(not any(value in output for value in private), "Logs das aplicacoes sem CPF, email, tokens ou senhas de teste.")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--skip-build", action="store_true", help="Reutiliza imagens :t13 construidas desta revisao")
    parser.add_argument("--result-file", type=Path, help="Resumo tecnico sem dados pessoais; arquivo novo")
    options = parser.parse_args()
    env = Environment(image_tag="t13", app_documentation=True)
    try:
        env.start(build=not options.skip_build)
        journey(env)
    finally:
        env.close()
    # So ha resultado de sucesso depois de verificar a limpeza.
    if options.result_file:
        result = dict(stage="T13", status="passed", checks=env.checks, cleanup=True,
            finishedAt=datetime.now(timezone.utc).isoformat())
        with os.fdopen(os.open(options.result_file, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), "w") as stream:
            json.dump(result, stream, indent=2)
    print("T13 smoke: " + str(env.checks) + " verificacoes aprovadas em ambiente descartavel.", flush=True)


if __name__ == "__main__":
    try:
        main()
    except Failure as error:
        print("FALHA: " + str(error), file=sys.stderr)
        sys.exit(1)
    except Exception:
        print("FALHA: smoke descartavel nao concluiu; nenhum segredo ou payload foi exibido.", file=sys.stderr)
        sys.exit(1)
