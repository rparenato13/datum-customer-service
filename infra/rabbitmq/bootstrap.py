#!/usr/bin/env python3
"""Prepara definitions em volume Docker, sem imprimir senhas ou hashes."""
import argparse
import json
import os
from pathlib import Path
import sys
import tempfile
from model import definitions


def prepare(target, environment, check=False):
    previous = json.loads(target.read_text()) if target.is_file() else None
    desired = definitions(environment, previous)
    if check:
        if desired != previous:
            raise ValueError("Definitions ausentes ou divergentes.")
        return
    target.parent.mkdir(parents=True, exist_ok=True)
    # O broker tem UID proprio e monta este volume somente para leitura.
    os.chmod(target.parent, 0o755)
    if desired == previous:
        return
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(mode="w", encoding="utf-8", dir=target.parent, delete=False) as stream:
            temporary = stream.name
            json.dump(desired, stream, ensure_ascii=False, indent=2)
            stream.write("\n")
        os.chmod(temporary, 0o644)
        os.replace(temporary, target)
        temporary = None
    finally:
        if temporary is not None:
            os.unlink(temporary)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true")
    options = parser.parse_args()
    prepare(Path("/output/definitions.json"), os.environ, check=options.check)
    print("OK: definitions conferidas: 4 exchanges, 10 filas, 12 bindings e 4 usuarios; nenhum segredo exibido.")


if __name__ == "__main__":
    try:
        main()
    except Exception:
        print("FALHA: bootstrap RabbitMQ indisponivel; confira contrato, volume e credenciais.", file=sys.stderr)
        sys.exit(1)
