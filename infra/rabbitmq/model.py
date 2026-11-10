"""Modelo de provisionamento derivado do contrato T06, sem acesso ao broker."""

import base64
import hashlib
import hmac
import json
from pathlib import Path
import re
import secrets
import subprocess


ROOT = Path(__file__).resolve().parents[2]
USERS = {
    "rabbitmq_admin": "RABBITMQ_ADMIN_PASSWORD",
    "customer_app": "CUSTOMER_RABBITMQ_PASSWORD",
    "notification_app": "NOTIFICATION_RABBITMQ_PASSWORD",
    "status_publisher": "STATUS_PUBLISHER_RABBITMQ_PASSWORD",
}


def compose_config(arguments):
    result = subprocess.run(["docker", "compose", *arguments, "config", "--format", "json"],
                            cwd=ROOT, capture_output=True, timeout=30)
    if result.returncode:
        raise ValueError("Confira o Compose e as variaveis do .env.")
    return json.loads(result.stdout)


def manifest():
    value = json.loads((ROOT / "contracts/events/topology.v1.json").read_text())
    if value["proposalStatus"] != "APPROVED" or value["contractVersion"] != 1:
        raise ValueError("Provisionamento exige contrato v1 aprovado.")
    return value


def password_hash(password):
    salt = secrets.token_bytes(4)
    return base64.b64encode(salt + hashlib.sha256(salt + password.encode("utf-8")).digest()).decode("ascii")


def password_matches(password, encoded):
    try:
        value = base64.b64decode(encoded, validate=True)
        return len(value) == 36 and hmac.compare_digest(value[4:], hashlib.sha256(value[:4] + password.encode("utf-8")).digest())
    except (ValueError, TypeError):
        return False


def stored_definitions(arguments):
    result = subprocess.run(["docker", "compose", *arguments, "exec", "-T", "rabbitmq", "cat",
                             "/etc/rabbitmq/bootstrap/definitions.json"], cwd=ROOT, capture_output=True, timeout=30)
    if result.returncode:
        raise ValueError("Definitions do volume indisponiveis; confira o broker e o job de bootstrap.")
    return json.loads(result.stdout)


def definitions(environment, previous=None):
    contract = manifest()
    vhost = contract["vhost"]
    result = {"users": [], "vhosts": [{"name": vhost, "default_queue_type": "quorum"}],
              "permissions": [], "topic_permissions": [], "exchanges": [], "queues": [], "bindings": [], "policies": []}
    previous_users = {user["name"]: user for user in (previous or {}).get("users", [])}
    for user, variable in USERS.items():
        password = environment.get(variable, "")
        if not isinstance(password, str) or not password:
            raise ValueError("Senha RabbitMQ ausente para " + user + ".")
        stored = previous_users.get(user, {}).get("password_hash", "")
        encoded = stored if password_matches(password, stored) else password_hash(password)
        result["users"].append({"name": user, "password_hash": encoded,
                                "hashing_algorithm": "rabbit_password_hashing_sha256",
                                "tags": ["administrator"] if user == "rabbitmq_admin" else []})

    for exchange in contract["exchanges"]:
        result["exchanges"].append({**exchange, "vhost": vhost, "auto_delete": False, "internal": False, "arguments": {}})

    defaults = contract["queueDefaults"]
    base_policy = {"max-length": defaults["maxLength"], "max-length-bytes": defaults["maxLengthBytes"],
                   "overflow": defaults["overflow"]}

    def bind(source, key, destination):
        result["bindings"].append({"vhost": vhost, "source": source, "destination": destination,
                                   "destination_type": "queue", "routing_key": key, "arguments": {}})

    def queue(name, specific_policy):
        result["queues"].append({"name": name, "vhost": vhost, "durable": defaults["durable"],
                                 "auto_delete": defaults["autoDelete"], "arguments": {"x-queue-type": defaults["type"]}})
        result["policies"].append({"name": name, "vhost": vhost, "pattern": "^" + re.escape(name) + "$",
                                   "apply-to": "quorum_queues", "priority": 10, "definition": {**base_policy, **specific_policy}})

    for consumer in contract["consumers"]:
        identifier = consumer["id"]
        main_policy = contract["mainQueuePolicy"]
        queue(consumer["queue"], {"dead-letter-exchange": main_policy["deadLetterExchange"],
              "dead-letter-routing-key": identifier, "dead-letter-strategy": main_policy["deadLetterStrategy"],
              "delivery-limit": main_policy["deliveryLimit"]})
        queue(consumer["deadQueue"], {})
        bind("datum.customer.events", consumer["routingKey"], consumer["queue"])
        bind("datum.customer.redelivery", identifier, consumer["queue"])
        bind("datum.customer.dead", identifier, consumer["deadQueue"])
        retry_policy = contract["retryQueuePolicy"]
        for retry in consumer["retries"]:
            queue(retry["queue"], {"message-ttl": retry["ttlMs"],
                  "dead-letter-exchange": retry_policy["deadLetterExchange"], "dead-letter-routing-key": identifier,
                  "dead-letter-strategy": retry_policy["deadLetterStrategy"]})
            bind("datum.customer.retry", retry["routingKey"], retry["queue"])

    scopes = {
        "rabbitmq_admin": (".*", ".*", ".*"),
        "customer_app": ("^$", r"^datum\.customer\.(events|retry)$", r"^customer-service\.status-change\.v1$"),
        "notification_app": ("^$", r"^datum\.customer\.retry$", r"^customer-notification-service\.created\.v1$"),
        "status_publisher": ("^$", r"^datum\.customer\.events$", "^$"),
    }
    for user, (configure, write, read) in scopes.items():
        result["permissions"].append(dict(user=user, vhost=vhost, configure=configure, write=write, read=read))
    for user, write in (("customer_app", r"^customer\.created\.v1$"),
                        ("status_publisher", r"^customer\.status-change\.v1$"), ("notification_app", "^$")):
        result["topic_permissions"].append(dict(user=user, vhost=vhost, exchange="datum.customer.events", write=write, read="^$"))
    return result
