#!/usr/bin/env python3
"""Executa o mesmo job de definitions usado automaticamente pelo Compose."""
import argparse
import subprocess
import sys
from model import ROOT


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="Confere volume gerado sem modifica-lo")
    options, compose_arguments = parser.parse_known_args()
    command = ["docker", "compose", *compose_arguments, "run", "--rm", "--no-deps", "--build", "rabbitmq-init"]
    if options.check:
        command.append("--check")
    result = subprocess.run(command, cwd=ROOT, capture_output=True, timeout=600)
    if result.returncode:
        raise ValueError("Job de definitions nao concluiu.")
    print("OK: definitions do volume conferidas: 4 exchanges, 10 filas, 12 bindings e 4 usuarios.")
    print("O Compose prepara esse volume automaticamente; nenhum segredo ou hash foi impresso.")


if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError, subprocess.SubprocessError):
        print("FALHA: confira Docker/Compose, contrato e credenciais do bootstrap; nenhum segredo foi exibido.", file=sys.stderr)
        sys.exit(1)
