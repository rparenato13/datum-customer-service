"""Ambiente T14 fechado, criado integralmente pelo Compose do repositorio."""
import json
from pathlib import Path
import re
import sys
import uuid

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "resilience"))
from environment import Environment, Failure, ROOT, keycloak
SERVICES = ("postgres", "rabbitmq", "keycloak", "wiremock", "customer-service", "customer-notification-service")


class ComposeEnvironment(Environment):
    def __init__(self):
        super().__init__(image_tag="t14", app_documentation=True)
        self.project = "datum-compose-" + uuid.uuid4().hex[:10]
        self.arguments = ["-f", str(ROOT / "docker-compose.yml"), "--env-file", str(ROOT / ".env.example"), "-p", self.project]
        self.compose = ["docker", "compose", *self.arguments]
        self.env.update(CUSTOMER_HTTP_PORT=self.ports["customer"], NOTIFICATION_HTTP_PORT=self.ports["notification"],
            APP_PROFILES="", APP_ENVIRONMENT="test", CUSTOMER_DOCS_ENABLED="true", OUTBOX_PUBLISHER_ENABLED="true",
            STATUS_CONSUMER_ENABLED="true", NOTIFICATION_CONSUMER_ENABLED="true",
            CUSTOMER_MEMORY_LIMIT="1g", NOTIFICATION_MEMORY_LIMIT="1g")

    def service_id(self, name):
        identifier = self.run(self.compose + ["ps", "-aq", name]).strip()
        if not re.fullmatch(r"[a-f0-9]{12,64}", identifier):
            raise Failure("Container do projeto descartavel ausente: " + name)
        return identifier

    def state(self, name):
        return json.loads(self.run(["docker", "inspect", "--format", "{{json .State}}", self.service_id(name)]))

    def healthy(self):
        return all(self.state(service).get("Health", {}).get("Status") == "healthy" for service in SERVICES)

    def applications_ready(self):
        self.customer = self.service_id("customer-service")
        self.notification = self.service_id("customer-notification-service")
        self.until(self.healthy, timeout=180, description="healthchecks dos seis servicos Compose")

    def start(self, build=True):
        print("Ambiente descartavel Compose: " + self.project, flush=True)
        command = ["up", "--build", "-d"] if build else ["up", "--no-build", "-d"]
        # Nao chama configure.py: este e o mesmo comando do quickstart em volume vazio.
        self.run(self.compose + command, timeout=1200)
        self.applications_ready()
        initial = self.state("rabbitmq-init")
        self.check(initial.get("Status") == "exited" and initial.get("ExitCode") == 0,
            "Job de bootstrap concluiu antes do broker, sem arquivo local ou comando manual.")
        self.check(self.healthy(), "Seis servicos do Compose convergiram para healthy.")
        self.refresh_token()

    def close(self):
        try:
            self.run(self.compose + ["down", "--volumes", "--remove-orphans"])
        finally:
            self.directory.cleanup()
        print("Limpeza concluida somente no projeto descartavel Compose: " + self.project, flush=True)
