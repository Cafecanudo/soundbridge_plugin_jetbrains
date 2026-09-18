#!/usr/bin/env python3
"""Gera uma release: pede a versão, bumpa o gradle.properties, commita, cria a tag e envia
(o push da tag dispara o build e a Release no GitHub Actions)."""

import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent
GRADLE_PROPS = ROOT / "gradle.properties"


def git(*args: str, capture: bool = True) -> str:
    result = subprocess.run(["git", *args], cwd=ROOT, text=True, capture_output=capture)
    if result.returncode != 0:
        if capture and result.stderr:
            print(result.stderr.strip())
        sys.exit(f"Falhou: git {' '.join(args)}")
    return (result.stdout or "").strip()


def main() -> None:
    version = input("Versão da release (ex.: 0.2.0): ").strip().lstrip("v")
    if not re.fullmatch(r"\d+\.\d+\.\d+", version):
        sys.exit("Formato inválido. Use X.Y.Z (ex.: 0.2.0).")
    tag = f"v{version}"

    if git("rev-parse", "--abbrev-ref", "HEAD") != "main":
        sys.exit("Não está na branch 'main'.")

    tag_exists = git("tag", "-l", tag) != ""

    if tag_exists:
        print(f"Tag {tag} já existe localmente — vou apenas (re)enviar main + tag.")
    else:
        if git("status", "--porcelain"):
            sys.exit("Working tree com alterações não commitadas — commite antes.")
        print(f"\nRelease {tag}:")
        print("  1. bump pluginVersion no gradle.properties")
        print("  2. commit + tag")
        print("  3. push (dispara o build/Release no GitHub Actions)")

    if input("Continuar? [s/N] ").strip().lower() != "s":
        sys.exit("Cancelado.")

    if not tag_exists:
        text = GRADLE_PROPS.read_text(encoding="utf-8")
        new_text, count = re.subn(r"(?m)^pluginVersion=.*$", f"pluginVersion={version}", text)
        if count == 0:
            sys.exit("pluginVersion não encontrado no gradle.properties.")
        if new_text != text:
            GRADLE_PROPS.write_text(new_text, encoding="utf-8")
            git("add", "gradle.properties")
            git("commit", "-m", f"chore: release {tag}")
        git("tag", "-a", tag, "-m", f"Release {tag}")

    git("push", "origin", "main")
    git("push", "origin", tag)

    repo = git("remote", "get-url", "origin").removesuffix(".git")
    if repo.startswith("git@github.com:"):
        repo = "https://github.com/" + repo[len("git@github.com:"):]

    print(f"\nOK! Release {tag} disparada.")
    print(f"Actions:  {repo}/actions")
    print(f"Release:  {repo}/releases/tag/{tag}")


if __name__ == "__main__":
    main()
