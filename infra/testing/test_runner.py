import contextlib
import io
import json
import os
from pathlib import Path
import sys
import tempfile
import time
import unittest
from unittest.mock import patch
import xml.etree.ElementTree as ET

from run import EvidenceError, execute, junit, run_step, smoke_evidence


class EvidenceTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.started = time.time()

    def report(self, filename="TEST-sample.xml", name="sample.Test", tests=1, **counts):
        values = dict(tests=str(tests), failures="0", errors="0", skipped="0", name=name)
        values.update({key: str(value) for key, value in counts.items()})
        suite = ET.Element("testsuite", values)
        for _ in range(tests):
            ET.SubElement(suite, "testcase", name="scenario")
        target = self.root / filename
        ET.ElementTree(suite).write(target)
        return target

    def test_complete_current_evidence_is_accepted(self):
        self.report(tests=2)
        result = junit(self.root, ["sample.Test"], self.started)
        self.assertEqual(dict(tests=2, failures=0, errors=0, skipped=0, classes=1), result)

    def test_missing_or_unmapped_classes_are_rejected(self):
        self.report()
        for expected in (["sample.Test", "missing.Test"], ["other.Test"]):
            with self.subTest(expected=expected), self.assertRaises(EvidenceError):
                junit(self.root, expected, self.started)

    def test_old_report_cannot_approve_current_run(self):
        target = self.report()
        os.utime(target, (self.started - 60, self.started - 60))
        with self.assertRaises(EvidenceError):
            junit(self.root, ["sample.Test"], self.started)

    def test_empty_class_cannot_satisfy_manifest(self):
        self.report(tests=0)
        with self.assertRaises(EvidenceError):
            junit(self.root, ["sample.Test"], self.started)

    def test_failures_errors_and_skips_are_not_approved(self):
        for count in ("failures", "errors", "skipped"):
            self.report(**{count: 1})
            with self.subTest(count=count), self.assertRaises(EvidenceError):
                junit(self.root, ["sample.Test"], self.started)

    def test_failure_element_is_rejected_even_with_zero_failure_attribute(self):
        for tag in ("failure", "error", "skipped", "flakyFailure", "flakyError", "rerunFailure", "rerunError"):
            target = self.report()
            tree = ET.parse(target)
            ET.SubElement(tree.getroot().find("testcase"), tag)
            tree.write(target)
            with self.subTest(tag=tag), self.assertRaises(EvidenceError):
                junit(self.root, ["sample.Test"], self.started)

    def test_duplicate_class_cannot_inflate_count(self):
        self.report()
        self.report(filename="TEST-duplicate.xml")
        with self.assertRaises(EvidenceError):
            junit(self.root, ["sample.Test"], self.started)

    def test_smoke_requires_successful_checks_and_cleanup(self):
        path = self.root / "smoke.json"
        for data in [dict(stage="T13", status="passed", checks=10, cleanup=False),
                     dict(stage="T13", status="passed", checks=0, cleanup=True),
                     dict(stage="T13", status="failed", checks=10, cleanup=True)]:
            path.write_text(json.dumps(data))
            with self.subTest(data=data), self.assertRaises(EvidenceError):
                smoke_evidence(path)
        path.write_text(json.dumps(dict(stage="T13", status="passed", checks=10, cleanup=True)))
        self.assertEqual(10, smoke_evidence(path)["checks"])

    def test_child_failure_is_visible_without_printing_child_output(self):
        steps = []
        output = io.StringIO()
        with contextlib.redirect_stdout(output), self.assertRaises(EvidenceError):
            run_step("example", [sys.executable, "-c", "print('PRIVATE_SENTINEL'); raise SystemExit(7)"], self.root, steps)
        self.assertEqual("failed", steps[0]["status"])
        self.assertEqual(7, steps[0]["exitCode"])
        self.assertNotIn("PRIVATE_SENTINEL", output.getvalue())
        log = self.root / "example.log"
        self.assertIn("PRIVATE_SENTINEL", log.read_text())
        self.assertEqual(0o600, log.stat().st_mode & 0o777)

    def test_missing_executable_is_reported_as_failed_step(self):
        steps = []
        with contextlib.redirect_stdout(io.StringIO()), self.assertRaises(EvidenceError):
            run_step("missing", [str(self.root / "missing-executable")], self.root, steps)
        self.assertEqual("failed", steps[0]["status"])
        self.assertEqual("FileNotFoundError", steps[0]["exceptionClass"])

    def test_failed_step_prevents_remaining_suites(self):
        from argparse import Namespace
        with patch("run.run_step", side_effect=EvidenceError("failed")) as child:
            with self.assertRaises(EvidenceError):
                execute(Namespace(suite="all", skip_build=False), self.root, dict(steps=[]))
        self.assertEqual(1, child.call_count)


if __name__ == "__main__":
    unittest.main()
