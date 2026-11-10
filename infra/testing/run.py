#!/usr/bin/env python3
"""Executa camadas de teste e resume somente evidencias desta execucao."""
import argparse
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import time
import uuid
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
MANIFEST = Path(__file__).with_name("suites.json")


class EvidenceError(ValueError):
    """Falha de evidencia tecnica, sem incluir corpos, credenciais ou logs."""


def junit(directory, expected, started):
    if not expected:
        raise EvidenceError("Grupo JUnit sem classes previstas no manifesto.")
    reports = sorted(directory.glob("TEST-*.xml"))
    seen = set()
    totals = dict(tests=0, failures=0, errors=0, skipped=0, classes=0)
    for path in reports:
        if path.stat().st_mtime < started - 2:
            raise EvidenceError("Relatorio JUnit anterior a esta execucao.")
        suite = ET.parse(path).getroot()
        name = suite.get("name")
        if name in seen:
            raise EvidenceError("Classe JUnit repetida nos relatorios.")
        seen.add(name)
        cases = suite.findall("testcase")
        count = {key: int(suite.get(key, "0")) for key in ("tests", "failures", "errors", "skipped")}
        if count["tests"] <= 0 or count["tests"] != len(cases) or any(value < 0 for value in count.values()):
            raise EvidenceError("Relatorio JUnit vazio ou inconsistente.")
        if any(count[key] for key in ("failures", "errors", "skipped")) or any(
                case.find(tag) is not None for case in cases for tag in ("failure", "error", "skipped", "flakyFailure", "flakyError", "rerunFailure", "rerunError")):
            raise EvidenceError("Ha testes com falha, erro, skip ou rerun; consulte os XMLs e o log privado.")
        for key, value in count.items():
            totals[key] += value
    if seen != set(expected):
        raise EvidenceError("Classes executadas divergem de suites.json; confira testes ausentes ou novos.")
    totals["classes"] = len(seen)
    return totals


def collect_junit(root, manifest, integration, started):
    summaries = []
    for module, groups in manifest.items():
        for kind, engine in [("fast", "surefire"), ("integration", "failsafe")]:
            if kind == "integration" and not integration:
                continue
            result = junit(root / module / "target" / (engine + "-reports"), groups[kind], started)
            summaries.append(dict(module=module, layer=kind, **result))
    return summaries


def smoke_evidence(path):
    result = json.loads(path.read_text())
    if (result.get("stage") != "T13" or result.get("status") != "passed" or result.get("cleanup") is not True
            or type(result.get("checks")) is not int or result["checks"] <= 0):
        raise EvidenceError("Smoke nao apresentou evidencia completa de sucesso e limpeza.")
    return result


def run_step(name, command, directory, steps):
    log = directory / (name + ".log")
    step = dict(name=name, status="running", log=str(log), startedAt=datetime.now(timezone.utc).isoformat())
    steps.append(step)
    print("Executando: " + name + ". Log privado: " + str(log), flush=True)
    start = time.monotonic()
    with os.fdopen(os.open(log, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), "w") as stream:
        try:
            process = subprocess.Popen(command, cwd=ROOT, stdout=stream, stderr=subprocess.STDOUT)
        except OSError as error:
            step.update(status="failed", exceptionClass=type(error).__name__, durationSeconds=round(time.monotonic() - start, 2))
            raise EvidenceError("Nao foi possivel iniciar a etapa " + name + "; confira executaveis e permissoes (" + type(error).__name__ + ").") from None
        with process as child:
            try:
                while True:
                    try:
                        status = child.wait(timeout=20)
                        break
                    except subprocess.TimeoutExpired:
                        print("Em andamento: " + name + ".", flush=True)
            except KeyboardInterrupt:
                child.send_signal(signal.SIGINT)
                child.wait()
                step["status"] = "interrupted"
                raise
    step.update(status="passed" if status == 0 else "failed", exitCode=status, durationSeconds=round(time.monotonic() - start, 2))
    if status:
        raise EvidenceError("Etapa " + name + " falhou; consulte seu log privado. Nenhuma etapa seguinte foi iniciada.")
    print("OK: " + name + ".", flush=True)


def execute(options, directory, report):
    steps = report["steps"]
    if options.suite in ("fast", "integration", "all"):
        started = time.time()
        integration = options.suite != "fast"
        command = [str(ROOT / ("mvnw.cmd" if os.name == "nt" else "mvnw")), "-B", "-ntp"]
        if integration:
            command += ["-Pintegration"]
        run_step("maven", command + ["clean", "verify"], directory, steps)
        manifest = json.loads(MANIFEST.read_text())
        report["junit"] = collect_junit(ROOT, manifest, integration, started)
        total = sum(suite["tests"] for suite in report["junit"])
        print("OK: " + str(total) + " testes Maven, sem falhas, erros ou skips; classes conferidas.", flush=True)
        run_step("contracts", [sys.executable, str(ROOT / "contracts/events/verify.py")], directory, steps)
        for folder, name in [("infra/observability", "collector-tests"), ("infra/testing", "runner-tests")]:
            run_step(name, [sys.executable, "-m", "unittest", "discover", "-s", folder, "-p", "test_*.py"], directory, steps)
    if options.suite in ("smoke", "all"):
        proof = directory / "smoke.json"
        command = [sys.executable, str(ROOT / "infra/testing/smoke.py"), "--result-file", str(proof)]
        if options.skip_build:
            command.append("--skip-build")
        run_step("smoke", command, directory, steps)
        report["smoke"] = smoke_evidence(proof)
        print("OK: smoke com " + str(report["smoke"]["checks"]) + " verificacoes e limpeza confirmada.", flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--suite", choices=("fast", "integration", "smoke", "all"), default="fast")
    parser.add_argument("--skip-build", action="store_true", help="Somente smoke/all: reutiliza imagens :t13 da mesma revisao")
    options = parser.parse_args()
    if options.skip_build and options.suite not in ("smoke", "all"):
        parser.error("--skip-build exige --suite smoke ou all")
    directory = ROOT / ".local/testing" / uuid.uuid4().hex
    directory.mkdir(parents=True, mode=0o700)
    report = dict(stage="T13", suite=options.suite, buildSkipped=options.skip_build,
        startedAt=datetime.now(timezone.utc).isoformat(), status="running", steps=[])
    outcome = 1
    try:
        execute(options, directory, report)
        report["status"] = "passed"
        outcome = 0
    except KeyboardInterrupt:
        report.update(status="interrupted", reason="Execucao interrompida; confira a limpeza no log do smoke.")
        outcome = 130
    except EvidenceError as error:
        report.update(status="failed", reason=str(error))
    except Exception:
        report.update(status="failed", reason="Execucao ou leitura de evidencias indisponivel; confira logs e pre-requisitos.")
    finally:
        report["finishedAt"] = datetime.now(timezone.utc).isoformat()
        target = directory / "summary.json"
        with os.fdopen(os.open(target, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), "w") as stream:
            json.dump(report, stream, indent=2, ensure_ascii=False)
        print("Resumo: " + str(target), flush=True)
    if outcome:
        print("FALHA: " + report["reason"], file=sys.stderr)
    else:
        print("T13: suite " + options.suite + " aprovada.", flush=True)
    return outcome


if __name__ == "__main__":
    sys.exit(main())
