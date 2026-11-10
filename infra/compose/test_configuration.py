"""Contrato operacional do Compose; so le configuracao, sem iniciar recursos."""
import json
import os
from pathlib import Path
import subprocess
import unittest

ROOT = Path(__file__).resolve().parents[2]
SERVICES = {"postgres", "rabbitmq", "keycloak", "wiremock", "customer-service", "customer-notification-service"}


def configuration(**overrides):
    environment = dict(os.environ, KEYCLOAK_PORT="19190", CUSTOMER_HTTP_PORT="19180", NOTIFICATION_HTTP_PORT="19181",
        CUSTOMER_DB_URL="jdbc:postgresql://localhost:9999/host_only", CUSTOMER_JWT_ISSUER_URI="http://localhost:9999/host_only",
        SCORE_BASE_URL="http://localhost:9999", APP_PROFILES="")
    for name in ("POSTGRES_ADMIN_PASSWORD", "CUSTOMER_DB_PASSWORD", "NOTIFICATION_DB_PASSWORD", "KEYCLOAK_ADMIN_PASSWORD",
                 "KEYCLOAK_DEMO_USER_PASSWORD", "KEYCLOAK_DEMO_ADMIN_PASSWORD", "RABBITMQ_ADMIN_PASSWORD",
                 "CUSTOMER_RABBITMQ_PASSWORD", "NOTIFICATION_RABBITMQ_PASSWORD", "STATUS_PUBLISHER_RABBITMQ_PASSWORD",
                 "NOTIFICATION_MANAGEMENT_PASSWORD"):
        environment[name] = "test_" + name
    environment.update(overrides)
    result = subprocess.run(["docker", "compose", "-f", str(ROOT / "docker-compose.yml"), "--env-file",
        str(ROOT / ".env.example"), "-p", "datum-config-check", "config", "--format", "json"],
        cwd=ROOT, env=environment, capture_output=True, timeout=30)
    if result.returncode:
        raise RuntimeError("Compose nao validou a configuracao de teste.")
    return json.loads(result.stdout)


class ComposeConfigurationTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.config = configuration()
        cls.services = cls.config["services"]

    def test_all_runtime_services_and_bootstrap_are_declared(self):
        self.assertEqual(SERVICES | {"rabbitmq-init"}, set(self.services))
        for name in SERVICES:
            self.assertEqual("unless-stopped", self.services[name]["restart"])
            self.assertNotIn("container_name", self.services[name])
            self.assertGreater(int(self.services[name]["mem_limit"]), 0)

    def test_startup_waits_for_bootstrap_and_healthy_dependencies(self):
        self.assertEqual("service_completed_successfully", self.services["rabbitmq"]["depends_on"]["rabbitmq-init"]["condition"])
        for name, dependencies in [("customer-service", {"postgres", "rabbitmq", "wiremock", "keycloak"}),
                                    ("customer-notification-service", {"postgres", "rabbitmq"})]:
            declared = self.services[name]["depends_on"]
            self.assertEqual(dependencies, set(declared))
            self.assertTrue(all(value["condition"] == "service_healthy" for value in declared.values()))

    def test_bootstrap_needs_no_host_file_socket_or_network(self):
        job = self.services["rabbitmq-init"]
        self.assertEqual("none", job["network_mode"])
        self.assertTrue(job["read_only"])
        self.assertTrue(all(volume["type"] == "volume" for volume in job["volumes"]))
        mounted = next(v for v in self.services["rabbitmq"]["volumes"] if v["target"] == "/etc/rabbitmq/bootstrap")
        self.assertEqual("volume", mounted["type"])
        self.assertTrue(mounted["read_only"])
        self.assertEqual(job["volumes"][0]["source"], mounted["source"])

    def test_applications_receive_only_their_own_credentials(self):
        forbidden = {"POSTGRES_ADMIN_PASSWORD", "KEYCLOAK_ADMIN_PASSWORD", "KEYCLOAK_DEMO_USER_PASSWORD",
                     "KEYCLOAK_DEMO_ADMIN_PASSWORD", "RABBITMQ_ADMIN_PASSWORD", "STATUS_PUBLISHER_RABBITMQ_PASSWORD"}
        for name, prefix, other in [("customer-service", "CUSTOMER", "NOTIFICATION"),
                                     ("customer-notification-service", "NOTIFICATION", "CUSTOMER")]:
            env = self.services[name]["environment"]
            self.assertFalse(forbidden & set(env))
            self.assertNotIn(other + "_DB_PASSWORD", env)
            self.assertNotIn(other + "_RABBITMQ_PASSWORD", env)
            self.assertEqual("test_" + prefix + "_DB_PASSWORD", env[prefix + "_DB_PASSWORD"])
            self.assertEqual("test_" + prefix + "_RABBITMQ_PASSWORD", env[prefix + "_RABBITMQ_PASSWORD"])
            self.assertNotIn("env_file", self.services[name])

    def test_container_urls_ignore_host_only_variables_and_keep_canonical_issuer(self):
        env = self.services["customer-service"]["environment"]
        self.assertEqual("jdbc:postgresql://postgres:5432/customer_db", env["CUSTOMER_DB_URL"])
        self.assertEqual("http://wiremock:8080", env["SCORE_BASE_URL"])
        self.assertEqual("rabbitmq", env["CUSTOMER_RABBITMQ_HOST"])
        self.assertEqual("http://keycloak:8080/realms/datum/protocol/openid-connect/certs", env["CUSTOMER_JWT_JWK_SET_URI"])
        self.assertEqual(self.services["keycloak"]["environment"]["KC_HOSTNAME"] + "/realms/datum", env["CUSTOMER_JWT_ISSUER_URI"])
        self.assertIn(":19190/", env["CUSTOMER_JWT_ISSUER_URI"])

    def test_ports_are_loopback_only_and_http_ports_are_independent(self):
        for name in SERVICES:
            for port in self.services[name].get("ports", []):
                self.assertEqual("127.0.0.1", port["host_ip"])
        self.assertEqual(("19180", 8080), tuple(self.services["customer-service"]["ports"][0][key] for key in ("published", "target")))
        self.assertEqual(("19181", 8081), tuple(self.services["customer-notification-service"]["ports"][0][key] for key in ("published", "target")))
        self.assertTrue(all(not volume.get("external", False) for volume in self.config["volumes"].values()))

    def test_limits_and_observability_profile_are_forwarded_explicitly(self):
        config = configuration(APP_PROFILES="observability", CUSTOMER_DB_POOL_CONNECTION_TIMEOUT_MS="3210",
                               NOTIFICATION_DB_POOL_CONNECTION_TIMEOUT_MS="4321")
        for name, prefix, expected in [("customer-service", "CUSTOMER", "3210"),
                                        ("customer-notification-service", "NOTIFICATION", "4321")]:
            env = config["services"][name]["environment"]
            self.assertEqual("observability", env["SPRING_PROFILES_ACTIVE"])
            self.assertEqual(expected, env[prefix + "_DB_POOL_CONNECTION_TIMEOUT_MS"])
            self.assertEqual(2, len([key for key in env if key.endswith("PASSWORD") and "MANAGEMENT" not in key]))


if __name__ == "__main__":
    unittest.main()
