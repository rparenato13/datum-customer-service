#!/usr/bin/env python3
"""Smoke test local do IdP. Usa apenas Python stdlib, Docker CLI e OpenSSL.

Simula o formulario do Keycloak exclusivamente com os usuarios de demonstracao.
O login interativo normal e documentado no README (Postman + PKCE).
Nao imprime, persiste ou envia tokens a servicos externos.
"""

import argparse
import base64
import hashlib
from html.parser import HTMLParser
import http.cookiejar
import json
from pathlib import Path
import secrets
import subprocess
import sys
import tempfile
import textwrap
import time
import urllib.error
import urllib.parse
import urllib.request


CLIENT_ID = "datum-local"
CALLBACK = "http://127.0.0.1:18091/callback"
API_ROLES = {"USER", "ADMIN"}


class CheckFailed(Exception):
    pass


def check(condition, message):
    if not condition:
        raise CheckFailed(message)


def run(args, data=None):
    result = subprocess.run(args, input=data, capture_output=True, timeout=30)
    check(result.returncode == 0, "Comando local falhou; confira Docker/Compose e OpenSSL.")
    return result.stdout


def request_json(url, data=None):
    encoded = None if data is None else urllib.parse.urlencode(data).encode()
    try:
        with urllib.request.urlopen(url, data=encoded, timeout=15) as response:
            return response.status, json.load(response)
    except urllib.error.HTTPError as error:
        return error.code, json.load(error)


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


class LocalBrowserCookiePolicy(http.cookiejar.DefaultCookiePolicy):
    def return_ok_secure(self, cookie, request):
        # Keycloak emite cookies Secure para localhost. Apenas neste smoke local,
        # reproduzimos o envio em loopback HTTP permitido pelos navegadores.
        if request.type == "http" and urllib.parse.urlsplit(request.full_url).hostname in ("localhost", "127.0.0.1", "::1"):
            return True
        return super().return_ok_secure(cookie, request)


class LoginForm(HTMLParser):
    action = None

    def handle_starttag(self, tag, attrs):
        attrs = dict(attrs)
        if tag == "form" and attrs.get("id") == "kc-form-login":
            self.action = attrs.get("action")


def login_code(discovery, username, password):
    verifier = secrets.token_urlsafe(48)
    challenge = base64.urlsafe_b64encode(hashlib.sha256(verifier.encode()).digest()).rstrip(b"=").decode()
    state = secrets.token_urlsafe(24)
    params = dict(client_id=CLIENT_ID, redirect_uri=CALLBACK, response_type="code", scope="openid",
                  state=state, code_challenge=challenge, code_challenge_method="S256")
    jar = http.cookiejar.CookieJar(policy=LocalBrowserCookiePolicy())
    opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar), NoRedirect())
    with opener.open(discovery["authorization_endpoint"] + "?" + urllib.parse.urlencode(params), timeout=15) as response:
        form = LoginForm()
        form.feed(response.read().decode())
    check(form.action is not None, "Formulario de login nao encontrado.")
    check(urllib.parse.urlsplit(form.action)[:2] == urllib.parse.urlsplit(discovery["issuer"])[:2],
          "Origem inesperada no formulario; credenciais nao enviadas.")
    try:
        opener.open(urllib.request.Request(form.action, data=urllib.parse.urlencode(
            dict(username=username, password=password, credentialId="")).encode()), timeout=15).close()
        return None, verifier
    except urllib.error.HTTPError as response:
        check(response.code in (302, 303), "Resposta de autenticacao inesperada.")
        location = urllib.parse.urlsplit(response.headers.get("Location", ""))
        check(location[:3] == urllib.parse.urlsplit(CALLBACK)[:3], "Callback inesperado.")
        query = urllib.parse.parse_qs(location.query)
        check(query.get("state") == [state], "State do callback invalido.")
        check("code" in query, "Login nao produziu codigo de autorizacao.")
        return query["code"][0], verifier


def exchange(discovery, code, verifier):
    return request_json(discovery["token_endpoint"], dict(client_id=CLIENT_ID, grant_type="authorization_code",
                        redirect_uri=CALLBACK, code=code, code_verifier=verifier))


def decode(segment):
    return base64.urlsafe_b64decode(segment + "=" * (-len(segment) % 4))


def verify_signature(token, jwks):
    parts = token.split(".")
    check(len(parts) == 3, "Formato JWT invalido.")
    header = json.loads(decode(parts[0]))
    check(header.get("alg") == "RS256", "Algoritmo inesperado.")
    key = next((key for key in jwks["keys"] if key.get("kid") == header.get("kid")), None)
    check(key is not None and key.get("kty") == "RSA" and key.get("x5c"), "Chave RSA nao encontrada no JWKS.")
    certificate = ("-----BEGIN CERTIFICATE-----\n" + "\n".join(textwrap.wrap(key["x5c"][0], 64))
                   + "\n-----END CERTIFICATE-----\n").encode()
    public_key = run(["openssl", "x509", "-pubkey", "-noout"], certificate)
    # OpenSSL verifica a assinatura. Apenas chave publica e assinatura vao ao temporario.
    with tempfile.TemporaryDirectory(prefix="datum-jwt-check-") as folder:
        public_path = Path(folder) / "public.pem"
        signature_path = Path(folder) / "signature.bin"
        public_path.write_bytes(public_key)
        signature_path.write_bytes(decode(parts[2]))
        result = subprocess.run(["openssl", "dgst", "-sha256", "-verify", str(public_path),
                                 "-signature", str(signature_path)],
                                input=(parts[0] + "." + parts[1]).encode(), capture_output=True, timeout=10)
        check(result.returncode == 0, "Assinatura JWT invalida.")
    return json.loads(decode(parts[1]))


def internal_json(compose, path):
    response = run(compose + ["exec", "-T", "keycloak", "bash", "-ec",
        "exec 3<>/dev/tcp/keycloak/8080; printf 'GET " + path
        + " HTTP/1.0\\r\\nHost: keycloak:8080\\r\\n\\r\\n' >&3; cat <&3"])
    header, body = response.split(b"\r\n\r\n", 1)
    check(b" 200 " in header.splitlines()[0], "Endpoint interno do Keycloak indisponivel.")
    return json.loads(body)


def verify_api(base_url, tokens):
    parsed = urllib.parse.urlsplit(base_url)
    check(parsed.scheme in ("http", "https") and parsed.hostname in ("localhost", "127.0.0.1", "::1")
          and parsed.username is None and not parsed.query and not parsed.fragment,
          "A verificacao da API aceita somente URL de loopback, sem credenciais ou query.")
    base_url = base_url.rstrip("/")
    opener = urllib.request.build_opener(NoRedirect())

    def call(method, path, token=None, payload=None):
        headers = {"Accept": "application/json"}
        if token is not None:
            headers["Authorization"] = "Bearer " + token
        data = None
        if payload is not None:
            headers["Content-Type"] = "application/json"
            data = json.dumps(payload).encode()
        request = urllib.request.Request(base_url + path, data=data, headers=headers, method=method)
        try:
            with opener.open(request, timeout=15) as response:
                return response.status, json.load(response)
        except urllib.error.HTTPError as error:
            return error.code, json.load(error)

    status, body = call("GET", "/customers")
    check(status == 401 and body.get("code") == "AUTHENTICATION_REQUIRED", "API nao recusou acesso sem token.")
    print("OK: API sem token retorna 401 padronizado.", flush=True)
    status, body = call("GET", "/customers", "not-a-jwt")
    check(status == 401 and body.get("code") == "AUTHENTICATION_REQUIRED", "API nao recusou token malformado.")
    print("OK: API com token malformado retorna 401 padronizado.", flush=True)
    for role in ("USER", "ADMIN"):
        status, _ = call("GET", "/customers", tokens[role])
        check(status == 200, "API nao aceitou consulta com token real valido.")
        print(f"OK: consulta com token real {role} retorna 200.", flush=True)

    # O corpo invalido e o ID inexistente evitam qualquer gravacao, inclusive
    # quando o script e executado contra o banco local de demonstracao.
    valid_payload = dict(name="Cliente de Teste", cpf="52998224725", email="cliente@example.test", status="ACTIVE")
    for method, path, payload, admin_status, admin_code in (
        ("POST", "/customers", {}, 400, "VALIDATION_ERROR"),
        ("PUT", "/customers/9223372036854775807", valid_payload, 404, "CUSTOMER_NOT_FOUND"),
        ("DELETE", "/customers/9223372036854775807", None, 404, "CUSTOMER_NOT_FOUND"),
    ):
        status, body = call(method, path, tokens["USER"], payload)
        check(status == 403 and body.get("code") == "ACCESS_DENIED", "API nao bloqueou escrita USER.")
        print(f"OK: {method} com USER retorna 403 antes do processamento.", flush=True)
        status, body = call(method, path, tokens["ADMIN"], payload)
        check(status == admin_status and body.get("code") == admin_code, "ADMIN nao alcancou processamento esperado da API.")
        print(f"OK: {method} com ADMIN alcanca a aplicacao ({admin_status}), sem gravar dados.", flush=True)


def main():
    parser = argparse.ArgumentParser(description="Verifica Keycloak local e, opcionalmente, a API protegida.")
    parser.add_argument("--api-url", help="URL local da API para provas sem gravacao de dados")
    options, compose_args = parser.parse_known_args()
    compose = ["docker", "compose", *compose_args]
    config = json.loads(run(compose + ["config", "--format", "json"]))
    environment = config["services"]["keycloak"]["environment"]
    issuer = environment["KC_HOSTNAME"].rstrip("/") + "/realms/datum"
    status, discovery = request_json(issuer + "/.well-known/openid-configuration")
    check(status == 200 and discovery["issuer"] == issuer, "Discovery ou issuer inesperado.")
    status, jwks = request_json(discovery["jwks_uri"])
    check(status == 200 and jwks.get("keys"), "JWKS indisponivel.")
    print("OK: discovery e JWKS disponiveis com issuer canonico.", flush=True)

    for callback in ("https://oauth.pstmn.io/v1/callback", "https://oauth.pstmn.io/v1/browser-callback"):
        params = dict(client_id=CLIENT_ID, redirect_uri=callback, response_type="code", scope="openid",
                      state=secrets.token_urlsafe(24), code_challenge="A" * 43, code_challenge_method="S256")
        opener = urllib.request.build_opener(NoRedirect())
        with opener.open(discovery["authorization_endpoint"] + "?" + urllib.parse.urlencode(params), timeout=15) as response:
            form = LoginForm()
            form.feed(response.read().decode())
            check(form.action is not None, "Callback do Postman nao foi aceito.")
    print("OK: callbacks exatos do Postman aceitos, sem redirecionar para servicos externos.", flush=True)

    tokens = []
    api_tokens = {}
    for username, variable, role in (("api-user", "KEYCLOAK_DEMO_USER_PASSWORD", "USER"),
                                     ("api-admin", "KEYCLOAK_DEMO_ADMIN_PASSWORD", "ADMIN")):
        code, verifier = login_code(discovery, username, environment[variable])
        check(code is not None, "Login de demonstracao falhou; confira as senhas do realm inicializado.")
        status, response = exchange(discovery, code, verifier)
        check(status == 200 and response.get("access_token"), "Troca de codigo PKCE falhou.")
        token = response["access_token"]
        claims = verify_signature(token, jwks)
        audience = claims.get("aud", [])
        if isinstance(audience, str):
            audience = [audience]
        check(claims.get("iss") == issuer and "customer-api" in audience, "Issuer ou audiencia invalido.")
        check(claims.get("azp") == CLIENT_ID and claims.get("sub"), "Cliente ou subject invalido.")
        check(claims.get("exp", 0) > time.time() and 0 < claims["exp"] - claims["iat"] <= 300,
              "Validade do token inesperada.")
        check(set(claims.get("realm_access", {}).get("roles", [])) & API_ROLES == {role}, "Perfil incorreto no token.")
        tokens.append(token)
        api_tokens[role] = token
        print(f"OK: {username} autenticou por codigo + PKCE; assinatura, iss, aud, exp e perfil {role} validos.", flush=True)

    parts = tokens[0].split(".")
    signature = bytearray(decode(parts[2]))
    signature[0] ^= 1
    parts[2] = base64.urlsafe_b64encode(signature).rstrip(b"=").decode()
    try:
        verify_signature(".".join(parts), jwks)
    except CheckFailed:
        print("OK: assinatura adulterada foi rejeitada pelo OpenSSL.", flush=True)
    else:
        raise CheckFailed("Assinatura adulterada foi aceita.")

    code, _ = login_code(discovery, "api-user", environment["KEYCLOAK_DEMO_USER_PASSWORD"] + "_invalid")
    check(code is None, "Senha incorreta autenticou.")
    print("OK: senha incorreta nao produziu codigo de autorizacao.", flush=True)

    code, _ = login_code(discovery, "api-user", environment["KEYCLOAK_DEMO_USER_PASSWORD"])
    status, response = exchange(discovery, code, "verificador_incorreto_" + secrets.token_urlsafe(32))
    check(status == 400 and response.get("error") == "invalid_grant", "PKCE incorreto nao foi rejeitado.")
    print("OK: code_verifier incorreto foi rejeitado.", flush=True)

    params = dict(client_id=CLIENT_ID, response_type="code", redirect_uri=CALLBACK, scope="openid",
                  state=secrets.token_urlsafe(24))
    opener = urllib.request.build_opener(NoRedirect())
    try:
        opener.open(discovery["authorization_endpoint"] + "?" + urllib.parse.urlencode(params), timeout=15).close()
    except urllib.error.HTTPError as response:
        check(response.code in (302, 303, 400), "Resposta inesperada sem PKCE.")
        if response.code != 400:
            check(urllib.parse.parse_qs(urllib.parse.urlsplit(response.headers.get("Location", "")).query)
                  .get("error") == ["invalid_request"], "PKCE nao foi exigido.")
    else:
        raise CheckFailed("Autorizacao sem PKCE foi aceita.")
    print("OK: PKCE obrigatorio no cliente.", flush=True)

    status, response = request_json(discovery["token_endpoint"], dict(client_id=CLIENT_ID, grant_type="password",
                                   username="api-user", password=environment["KEYCLOAK_DEMO_USER_PASSWORD"]))
    check(status in (400, 401) and response.get("error") in ("unauthorized_client", "unsupported_grant_type"),
          "Direct grant nao foi bloqueado.")
    print("OK: grant_type=password desabilitado.", flush=True)

    internal_discovery = internal_json(compose, "/realms/datum/.well-known/openid-configuration")
    internal_jwks = internal_json(compose, "/realms/datum/protocol/openid-connect/certs")
    check(internal_discovery["issuer"] == issuer, "Issuer interno diverge do canonico.")
    check({key["kid"] for key in internal_jwks["keys"]} == {key["kid"] for key in jwks["keys"]}, "JWKS interno diverge.")
    print("OK: rede Docker acessa discovery/JWKS mantendo o mesmo issuer.", flush=True)
    if options.api_url:
        verify_api(options.api_url, api_tokens)
    print("Keycloak: verificacao concluida; nenhum token ou senha foi impresso.", flush=True)


if __name__ == "__main__":
    try:
        main()
    except CheckFailed as error:
        print(f"FALHA: {error}", file=sys.stderr)
        sys.exit(1)
    except Exception as error:
        print(f"FALHA inesperada na verificacao ({type(error).__name__}); confira o Keycloak e os pre-requisitos.", file=sys.stderr)
        sys.exit(1)
