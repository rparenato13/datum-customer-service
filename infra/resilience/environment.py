"""Ambiente descartavel fechado: nunca aceita enderecos/nomes do ambiente local."""

import base64
import copy
import importlib.util
import json
import logging
import os
from pathlib import Path
import socket
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

import pika

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "infra/rabbitmq"))
from model import definitions

spec = importlib.util.spec_from_file_location("datum_keycloak_test", ROOT / "infra/keycloak/verify.py")
keycloak = importlib.util.module_from_spec(spec)
spec.loader.exec_module(keycloak)
logging.getLogger("pika").setLevel(logging.CRITICAL)


class Failure(ValueError):
    """Mensagem tecnica deliberada, segura para exibir sem causa/payload."""


def free_port():
    with socket.socket() as listener:
        listener.bind(("127.0.0.1", 0))
        return str(listener.getsockname()[1])


class Environment:
    def __init__(self, image_tag="t11", app_profiles=None, app_documentation=False):
        if type(app_documentation) is not bool:
            raise ValueError("Configuracao de documentacao de teste invalida.")
        if image_tag not in ("t11", "t12", "t13") or app_profiles not in (None, "observability"):
            raise ValueError("Configuracao de teste nao prevista.")
        self.image_tag = image_tag
        self.app_profiles = app_profiles
        self.app_documentation = app_documentation
        self.directory = tempfile.TemporaryDirectory(prefix="datum-resilience-")
        self.project = "datum-resilience-" + uuid.uuid4().hex[:10]
        self.customer = self.project + "-customer"
        self.notification = self.project + "-notification"
        self.ports = {name: free_port() for name in ("customer", "notification", "postgres", "rabbit", "management", "keycloak", "wiremock")}
        self.env = dict(os.environ, POSTGRES_PORT=self.ports["postgres"], KEYCLOAK_PORT=self.ports["keycloak"],
            WIREMOCK_PORT=self.ports["wiremock"], RABBITMQ_AMQP_PORT=self.ports["rabbit"],
            RABBITMQ_MANAGEMENT_PORT=self.ports["management"],
            RABBITMQ_DEFINITIONS_FILE=self.directory.name + "/definitions.json")
        for variable in ("POSTGRES_ADMIN_PASSWORD", "CUSTOMER_DB_PASSWORD", "NOTIFICATION_DB_PASSWORD", "KEYCLOAK_ADMIN_PASSWORD",
                         "KEYCLOAK_DEMO_ADMIN_PASSWORD", "KEYCLOAK_DEMO_USER_PASSWORD", "RABBITMQ_ADMIN_PASSWORD",
                         "CUSTOMER_RABBITMQ_PASSWORD", "NOTIFICATION_RABBITMQ_PASSWORD", "STATUS_PUBLISHER_RABBITMQ_PASSWORD",
                         "NOTIFICATION_MANAGEMENT_PASSWORD"):
            self.env[variable] = "test_" + uuid.uuid4().hex
        self.arguments = ["-f", str(ROOT / "docker-compose.yml"), "--env-file", str(ROOT / ".env.example"), "-p", self.project]
        self.compose = ["docker", "compose", *self.arguments]
        self.definitions = definitions(self.env)
        self.token = None
        self.tokens_issued = []
        self.personal = []
        self.token_until = 0
        self.checks = 0
        self.sequence = 1000

    def check(self, condition, description):
        if not condition:
            raise Failure(description)
        self.checks += 1
        print("OK: " + description, flush=True)

    def run(self, command, timeout=240, required=True):
        result = subprocess.run(command, cwd=ROOT, env=self.env, capture_output=True, timeout=timeout)
        if required and result.returncode:
            raise Failure("Comando do ambiente descartavel falhou: " + Path(command[0]).name)
        return result.stdout.decode()

    def start(self, build=True):
        print("Ambiente descartavel: " + self.project, flush=True)
        if build:
            for service in ("customer-service", "customer-notification-service"):
                print("Construindo imagem: " + service, flush=True)
                self.run(["docker", "build", "-f", service + "/Dockerfile", "-t", "datum/" + service + ":" + self.image_tag, "."], timeout=600)
        self.run([sys.executable, "infra/rabbitmq/configure.py", *self.arguments])
        self.run(self.compose + ["up", "-d", "--wait", "--wait-timeout", "180", "postgres", "rabbitmq", "keycloak", "wiremock"])
        self.start_app("customer")
        self.start_app("notification")
        self.until(lambda: self.api("GET", "/actuator/health", auth=False)[0] == 200)
        self.until(lambda: self.api("GET", "/actuator/health", auth=False, service="notification")[0] == 200)
        self.refresh_token()
        print("Aplicacoes e dependencias isoladas prontas.", flush=True)

    def start_app(self, service):
        name = getattr(self, service)
        prefix = "CUSTOMER" if service == "customer" else "NOTIFICATION"
        database = "customer_db" if service == "customer" else "notification_db"
        image = "customer-service" if service == "customer" else "customer-notification-service"
        port = "8080" if service == "customer" else "8081"
        values = {prefix + "_DB_URL": "jdbc:postgresql://postgres:5432/" + database,
            prefix + "_DB_PASSWORD": self.env[prefix + "_DB_PASSWORD"], prefix + "_RABBITMQ_HOST": "rabbitmq",
            prefix + "_RABBITMQ_PORT": "5672", prefix + "_RABBITMQ_PASSWORD": self.env[prefix + "_RABBITMQ_PASSWORD"]}
        if service == "customer":
            values.update(CUSTOMER_JWT_ISSUER_URI="http://localhost:" + self.ports["keycloak"] + "/realms/datum",
                CUSTOMER_JWT_JWK_SET_URI="http://keycloak:8080/realms/datum/protocol/openid-connect/certs", SCORE_BASE_URL="http://wiremock:8080",
                OUTBOX_PUBLISHER_ENABLED="true", STATUS_CONSUMER_ENABLED="true", CUSTOMER_DOCS_ENABLED=str(self.app_documentation).lower())
        else:
            values["NOTIFICATION_CONSUMER_ENABLED"] = "true"
        if self.app_profiles:
            values["SPRING_PROFILES_ACTIVE"] = self.app_profiles
            values["APP_ENVIRONMENT"] = "test"
        if service == "notification":
            values["NOTIFICATION_MANAGEMENT_PASSWORD"] = self.env["NOTIFICATION_MANAGEMENT_PASSWORD"]
        command = ["docker", "run", "-d", "--name", name, "--label", "datum.resilience.run=" + self.project,
            "--network", self.project + "_default", "-p", "127.0.0.1:" + self.ports[service] + ":" + port]
        for variable, value in values.items():
            self.env[variable] = value
            command += ["--env", variable]
        self.run(command + ["datum/" + image + ":" + self.image_tag])

    def refresh_token(self):
        issuer = "http://localhost:" + self.ports["keycloak"] + "/realms/datum"
        _, discovery = keycloak.request_json(issuer + "/.well-known/openid-configuration")
        code, verifier = keycloak.login_code(discovery, "api-admin", self.env["KEYCLOAK_DEMO_ADMIN_PASSWORD"])
        status, result = keycloak.exchange(discovery, code, verifier)
        if status != 200:
            raise Failure("Token de teste nao foi emitido.")
        self.token = result["access_token"]
        self.tokens_issued.append(self.token)
        self.token_until = time.monotonic() + min(int(result.get("expires_in", 300)), 180) - 20

    def api(self, method, path, payload=None, auth=True, service="customer"):
        if auth and time.monotonic() >= self.token_until:
            self.refresh_token()
        headers = {"Content-Type": "application/json"}
        if auth:
            headers["Authorization"] = "Bearer " + self.token
        request = urllib.request.Request("http://localhost:" + self.ports[service] + path, headers=headers, method=method,
            data=None if payload is None else json.dumps(payload).encode())
        try:
            response = urllib.request.urlopen(request, timeout=25)
        except urllib.error.HTTPError as error:
            response = error
        except OSError as error:
            raise Failure("Chamada HTTP local interrompida: " + type(error).__name__)
        with response:
            data = response.read()
            return response.status, json.loads(data) if data else None

    def until(self, callback, timeout=60, description="condicao de recuperacao"):
        end = time.monotonic() + timeout
        progress = time.monotonic() + 20
        while time.monotonic() < end:
            try:
                result = callback()
                if result:
                    return result
            except (OSError, ValueError, pika.exceptions.AMQPError):
                pass
            if time.monotonic() >= progress:
                print("Aguardando: " + description + ".", flush=True)
                progress = time.monotonic() + 20
            time.sleep(0.2)
        raise Failure("Prazo excedido: " + description)

    def sql(self, database, query):
        return self.run(self.compose + ["exec", "-T", "postgres", "psql", "--no-psqlrc", "-U", "datum_admin",
            "-d", database, "-At", "-v", "ON_ERROR_STOP=1", "-c", query], timeout=30).strip()

    def connection(self, user="rabbitmq_admin"):
        keys = {"rabbitmq_admin": "RABBITMQ_ADMIN_PASSWORD", "customer_app": "CUSTOMER_RABBITMQ_PASSWORD",
                "notification_app": "NOTIFICATION_RABBITMQ_PASSWORD", "status_publisher": "STATUS_PUBLISHER_RABBITMQ_PASSWORD"}
        return pika.BlockingConnection(pika.ConnectionParameters(host="127.0.0.1", port=int(self.ports["rabbit"]), virtual_host="datum",
            credentials=pika.PlainCredentials(user, self.env[keys[user]]), connection_attempts=1,
            socket_timeout=5, stack_timeout=10, blocked_connection_timeout=5, heartbeat=15))

    def publish(self, user, payload, raw=None, exchange="datum.customer.events", key=None, attempt=None):
        key = key or ("customer.created.v1" if payload["eventType"] == "CUSTOMER_CREATED" else "customer.status-change.v1")
        raw = raw if raw is not None else json.dumps(payload, separators=(",", ":")).encode()
        with self.connection(user) as connection:
            channel = connection.channel()
            channel.confirm_delivery()
            channel.basic_publish(exchange=exchange, routing_key=key, body=raw, mandatory=True,
                properties=pika.BasicProperties(content_type="application/json", content_encoding="UTF-8", delivery_mode=2,
                    message_id=payload["eventId"], correlation_id=payload["eventId"], type=payload["eventType"],
                    headers={} if attempt is None else {"x-retry-attempt": attempt}))
        return raw

    def count(self, queue):
        with self.connection() as connection:
            return connection.channel().queue_declare(queue, passive=True).method.message_count

    def get(self, queue, ack=False):
        with self.connection() as connection:
            return connection.channel().basic_get(queue, auto_ack=ack)

    def management(self, method, path, payload=None):
        auth = base64.b64encode(("rabbitmq_admin:" + self.env["RABBITMQ_ADMIN_PASSWORD"]).encode()).decode()
        request = urllib.request.Request("http://localhost:" + self.ports["management"] + "/api/" + path,
            headers={"Authorization": "Basic " + auth, "Content-Type": "application/json"}, method=method,
            data=None if payload is None else json.dumps(payload).encode())
        with urllib.request.urlopen(request, timeout=10) as response:
            raw = response.read()
            return json.loads(raw) if raw else None

    def policy(self, queue, **changes):
        original = copy.deepcopy(next(p for p in self.definitions["policies"] if p["name"] == queue))
        original["definition"].update(changes)
        original.pop("vhost")
        original.pop("name")
        self.management("PUT", "policies/datum/" + urllib.parse.quote(queue, safe=""), original)
        return original["definition"]

    def logs(self, service):
        result = subprocess.run(["docker", "logs", getattr(self, service)], cwd=ROOT, env=self.env, capture_output=True, timeout=30)
        if result.returncode:
            raise Failure("Logs do container de teste indisponiveis.")
        return (result.stdout + result.stderr).decode()

    def new_customer(self, cpf=None):
        self.sequence += 1
        if cpf is None:
            digits = [int(value) for value in str(self.sequence).zfill(9)]
            for width in (10, 11):
                value = (sum(number * weight for number, weight in zip(digits, range(width, 1, -1))) * 10) % 11
                digits.append(0 if value == 10 else value)
            cpf = "".join(str(value) for value in digits)
        status, customer = self.api("POST", "/customers", dict(name="Resilience Test", cpf=cpf,
            email="resilience@example.test", status="ACTIVE"))
        self.personal.extend([cpf, "resilience@example.test"])
        self.check(status == 201, "Cadastro de teste confirmado com HTTP 201.")
        return customer

    def outbox(self, customer_id):
        raw = self.sql("customer_db", "SELECT row_to_json(o)::text FROM customer_outbox o WHERE customer_id = " + str(int(customer_id)))
        return json.loads(raw) if raw else None

    def notified(self, event_id):
        identifier = str(uuid.UUID(event_id))
        return self.sql("notification_db", "SELECT count(*) FROM notification_records WHERE event_id = '" + identifier + "'") == "1"

    def status_event(self, customer_id, version=0):
        return dict(eventId=str(uuid.uuid4()), eventType="CUSTOMER_STATUS_CHANGE", schemaVersion=1,
            occurredAt="2026-11-10T03:00:00Z", customerId=customer_id, status="INACTIVE", expectedVersion=version)

    def close(self):
        # Nomes criados por esta instancia, sem parametros externos de destino.
        errors = []
        try:
            self.run(["docker", "rm", "-f", self.customer, self.notification], required=False)
        except (OSError, subprocess.SubprocessError):
            errors.append(True)
        try:
            self.run(self.compose + ["down", "--volumes", "--remove-orphans"])
        except (Failure, OSError, subprocess.SubprocessError):
            errors.append(True)
        self.directory.cleanup()
        if errors:
            raise Failure("Confira a limpeza somente do projeto descartavel: " + self.project)
        else:
            print("Limpeza executada apenas no projeto descartavel: " + self.project, flush=True)
