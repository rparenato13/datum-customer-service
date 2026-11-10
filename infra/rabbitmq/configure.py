#!/usr/bin/env python3
"""Gera definitions locais com hashes de senha, a partir do contrato aprovado."""

import argparse
import json
import os
import subprocess
import sys
import tempfile

from model import compose_config, definition_file, definitions


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="Confere arquivo gerado sem modifica-lo")
    options, compose_arguments = parser.parse_known_args()
    config = compose_config(compose_arguments)
    target = definition_file(config)
    previous = json.loads(target.read_text()) if target.is_file() else None
    desired = definitions(config["services"]["rabbitmq"]["environment"], previous)
    if options.check:
        if previous != desired:
            raise ValueError("Definitions ausentes ou diferentes do contrato/.env; execute configure.py antes de iniciar o broker.")
        print("OK: definitions locais correspondem ao contrato e as credenciais configuradas.")
        return
    if previous != desired:
        target.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
        os.chmod(target.parent, 0o700)
        temporary = None
        try:
            with tempfile.NamedTemporaryFile(mode="w", encoding="utf-8", dir=target.parent, delete=False) as stream:
                temporary = stream.name
                json.dump(desired, stream, ensure_ascii=False, indent=2)
                stream.write("\n")
            # O diretorio local e privado; o arquivo bind-mounted precisa ser legivel pelo UID do broker.
            os.chmod(temporary, 0o644)
            os.replace(temporary, target)
            temporary = None
        finally:
            if temporary is not None:
                os.unlink(temporary)
    print("OK: definitions locais preparadas: 4 exchanges, 10 filas, 12 bindings e 4 usuarios.")
    print("Nenhuma senha ou hash foi impresso. O arquivo gerado e ignorado pelo Git.")


if __name__ == "__main__":
    try:
        main()
    except (ValueError, KeyError, OSError, subprocess.SubprocessError):
        print("FALHA: confira o Compose, o contrato aprovado e o arquivo local; nenhuma credencial foi exibida.", file=sys.stderr)
        sys.exit(1)
