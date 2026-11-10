import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from bootstrap import prepare
from model import USERS, password_matches


class BootstrapTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.target = Path(self.directory.name) / "output/definitions.json"
        self.environment = {variable: "private_" + name for name, variable in USERS.items()}

    def test_writes_approved_resources_without_plain_passwords(self):
        prepare(self.target, self.environment)
        text = self.target.read_text()
        for password in self.environment.values():
            self.assertNotIn(password, text)
        data = json.loads(text)
        self.assertEqual((4, 10, 12, 4), tuple(len(data[key]) for key in ("exchanges", "queues", "bindings", "users")))
        self.assertEqual(0o644, self.target.stat().st_mode & 0o777)
        for user in data["users"]:
            self.assertTrue(password_matches(self.environment[USERS[user["name"]]], user["password_hash"]))

    def test_second_start_preserves_bytes_hashes_and_file(self):
        prepare(self.target, self.environment)
        original = (self.target.read_bytes(), self.target.stat().st_mtime_ns)
        prepare(self.target, self.environment)
        self.assertEqual(original, (self.target.read_bytes(), self.target.stat().st_mtime_ns))
        prepare(self.target, self.environment, check=True)

    def test_check_before_bootstrap_fails_without_creating_volume_contents(self):
        with self.assertRaises(ValueError):
            prepare(self.target, self.environment, check=True)
        self.assertFalse(self.target.exists())

    def test_missing_password_does_not_replace_previous_file(self):
        prepare(self.target, self.environment)
        original = self.target.read_bytes()
        environment = dict(self.environment, CUSTOMER_RABBITMQ_PASSWORD="")
        with self.assertRaises(ValueError):
            prepare(self.target, environment)
        self.assertEqual(original, self.target.read_bytes())

    def test_changed_password_is_detected_by_check_before_write(self):
        prepare(self.target, self.environment)
        original = self.target.read_bytes()
        changed = dict(self.environment, CUSTOMER_RABBITMQ_PASSWORD="new_private_password")
        with self.assertRaises(ValueError):
            prepare(self.target, changed, check=True)
        self.assertEqual(original, self.target.read_bytes())
        prepare(self.target, changed)
        user = next(user for user in json.loads(self.target.read_text())["users"] if user["name"] == "customer_app")
        self.assertTrue(password_matches("new_private_password", user["password_hash"]))

    def test_failed_atomic_replace_preserves_last_good_file(self):
        prepare(self.target, self.environment)
        original = self.target.read_bytes()
        with patch("bootstrap.os.replace", side_effect=OSError("simulated failure")):
            with self.assertRaises(OSError):
                prepare(self.target, dict(self.environment, CUSTOMER_RABBITMQ_PASSWORD="new_password"))
        self.assertEqual(original, self.target.read_bytes())
        self.assertEqual([self.target], list(self.target.parent.iterdir()))

    def test_invalid_previous_file_requires_review_instead_of_overwrite(self):
        self.target.parent.mkdir()
        self.target.write_text("not-json")
        with self.assertRaises(ValueError):
            prepare(self.target, self.environment)
        self.assertEqual("not-json", self.target.read_text())


if __name__ == "__main__":
    unittest.main()
