#!/usr/bin/env python3
"""Verifica fixtures reais do WireMock sem acessar a API ou banco de clientes."""

import argparse
import json
import socket
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request


class CheckFailed(Exception):
    pass


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def check(condition, message):
    if not condition:
        raise CheckFailed(message)
    print("OK: " + message, flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", help="URL de loopback; padrao obtido do Docker Compose")
    options, compose_args = parser.parse_known_args()
    base_url = options.base_url
    if base_url is None:
        result = subprocess.run(["docker", "compose", *compose_args, "config", "--format", "json"],
                                capture_output=True, timeout=30)
        if result.returncode:
            raise CheckFailed("Confira o Docker Compose e as variaveis do .env.")
        config = json.loads(result.stdout)
        port = config["services"]["wiremock"]["ports"][0]["published"]
        base_url = "http://localhost:" + str(port)
    parsed = urllib.parse.urlsplit(base_url)
    if (parsed.scheme not in ("http", "https") or parsed.hostname not in ("localhost", "127.0.0.1", "::1")
            or parsed.username is not None or parsed.query or parsed.fragment or parsed.path not in ("", "/")):
        raise CheckFailed("Use uma URL de loopback, sem credenciais, caminho, query ou fragmento.")
    base_url = base_url.rstrip("/")
    opener = urllib.request.build_opener(NoRedirect())

    def call(path, method="GET", timeout=10):
        request = urllib.request.Request(base_url + path, method=method)
        try:
            with opener.open(request, timeout=timeout) as response:
                return response.status, response.headers, response.read()
        except urllib.error.HTTPError as error:
            return error.code, error.headers, error.read()

    status, _, body = call("/__admin/health")
    check(status == 200 and json.loads(body).get("status") == "healthy", "WireMock saudavel.")

    for label, cpf, score, classification in (
        ("baixo", "16899535009", 750, "LOW_RISK"),
        ("medio", "39053344705", 450, "MEDIUM_RISK"),
        ("alto", "93541134780", 150, "HIGH_RISK"),
    ):
        status, headers, body = call("/scores/" + cpf)
        check(status == 200 and headers.get_content_type() == "application/json"
              and json.loads(body) == dict(cpf=cpf, score=score, classification=classification),
              f"Score de risco {label}: HTTP 200 e contrato esperado.")

    for cpf, expected_status, code in (
        ("52998224725", 404, "SCORE_NOT_FOUND"),
        ("11144477735", 500, "PROVIDER_ERROR"),
        ("12345678909", 503, "PROVIDER_UNAVAILABLE"),
    ):
        status, _, body = call("/scores/" + cpf)
        check(status == expected_status and json.loads(body) == {"code": code},
              f"Falha simulada do provedor: HTTP {expected_status}.")

    status, _, body = call("/scores/98765432100")
    malformed = False
    try:
        json.loads(body)
    except json.JSONDecodeError:
        malformed = True
    check(status == 200 and malformed, "HTTP 200 com JSON deliberadamente invalido.")

    start = time.monotonic()
    status, _, body = call("/scores/01234567890")
    elapsed = time.monotonic() - start
    check(status == 200 and elapsed >= 2.8 and json.loads(body) == dict(
        cpf="01234567890", score=650, classification="LOW_RISK"),
        "Resposta valida apos atraso fixo de 3000 ms.")
    timed_out = False
    try:
        call("/scores/01234567890", timeout=0.3)
    except (TimeoutError, socket.timeout):
        timed_out = True
    except urllib.error.URLError as error:
        timed_out = isinstance(error.reason, (TimeoutError, socket.timeout))
    check(timed_out, "Cliente com timeout de 300 ms interrompe a espera pelo stub lento.")

    for path, method, label in (
        ("/scores/00000000000", "GET", "CPF sem fixture"),
        ("/rota-inexistente", "GET", "rota sem fixture"),
        ("/scores/16899535009", "POST", "metodo sem fixture"),
    ):
        status, _, body = call(path, method)
        check(status == 404 and json.loads(body) == {"code": "STUB_NOT_FOUND"},
              f"Resposta generica sem eco de dados para {label}.")

    status, _, body = call("/__admin/requests")
    # Nesta versao, consultar o journal desligado retorna 500 com essa excecao
    # administrativa especifica, em vez de uma lista JSON vazia.
    check(status == 500 and b"RequestJournalDisabledException" in body,
          "Journal desabilitado: requisicoes nao ficam armazenadas no WireMock.")
    print("WireMock: 14 verificacoes aprovadas; nenhum cliente foi criado ou alterado.")


if __name__ == "__main__":
    try:
        main()
    except CheckFailed as error:
        print("FALHA: " + str(error), file=sys.stderr)
        sys.exit(1)
    except (urllib.error.URLError, TimeoutError, OSError, subprocess.SubprocessError):
        print("FALHA: acesso local indisponivel ou timeout; confira o servico WireMock.", file=sys.stderr)
        sys.exit(1)
    except (ValueError, KeyError, TypeError):
        print("FALHA: resposta ou configuracao inesperada; confira as fixtures e o Compose.", file=sys.stderr)
        sys.exit(1)
