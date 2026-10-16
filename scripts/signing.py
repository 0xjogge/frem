#!/usr/bin/env python3
"""Create/back up Frem's release key, upload secrets, or run a signed local build."""

import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import secrets
import subprocess

ROOT = Path(__file__).resolve().parents[1]
BACKUP = ROOT / ".signing"
KEYSTORE = BACKUP / "frem-release.p12"
CREDENTIALS = BACKUP / "credentials.json"
CERTIFICATE = ROOT / ".github/release-signing-certificate.sha256"


def signing_environment():
    credentials = json.loads(CREDENTIALS.read_text())
    return {**os.environ, **credentials, "FREM_KEYSTORE_PATH": str(KEYSTORE)}


def setup(upload, repo):
    BACKUP.mkdir(mode=0o700, exist_ok=True)
    if not CREDENTIALS.exists():
        if KEYSTORE.exists():
            raise SystemExit("Restore credentials.json alongside the existing keystore first.")
        password = secrets.token_urlsafe(48)
        credentials = {
            "FREM_KEYSTORE_PASSWORD": password,
            "FREM_KEY_ALIAS": "frem-release",
            "FREM_KEY_PASSWORD": password,
        }
        with open(CREDENTIALS, "x", opener=lambda path, flags: os.open(path, flags, 0o600)) as output:
            json.dump(credentials, output, indent=2)
            output.write("\n")
    environment = signing_environment()
    if not KEYSTORE.exists():
        subprocess.run(
            [
                "keytool", "-genkeypair", "-noprompt", "-storetype", "PKCS12",
                "-keystore", str(KEYSTORE), "-alias", environment["FREM_KEY_ALIAS"],
                "-keyalg", "RSA", "-keysize", "4096", "-validity", "36500",
                "-dname", "CN=Frem, O=Frem",
                "-storepass:env", "FREM_KEYSTORE_PASSWORD", "-keypass:env", "FREM_KEY_PASSWORD",
            ],
            env=environment,
            check=True,
        )
        KEYSTORE.chmod(0o600)
    certificate = subprocess.check_output(
        [
            "keytool", "-exportcert", "-keystore", str(KEYSTORE),
            "-alias", environment["FREM_KEY_ALIAS"], "-storepass:env", "FREM_KEYSTORE_PASSWORD",
        ],
        env=environment,
    )
    digest = hashlib.sha256(certificate).hexdigest()
    if CERTIFICATE.exists() and CERTIFICATE.read_text().strip() != digest:
        raise SystemExit("The backup key does not match the pinned release certificate.")
    CERTIFICATE.write_text(f"{digest}\n")
    if upload:
        values = {name: environment[name] for name in (
            "FREM_KEYSTORE_PASSWORD", "FREM_KEY_ALIAS", "FREM_KEY_PASSWORD"
        )}
        values["FREM_KEYSTORE_BASE64"] = base64.b64encode(KEYSTORE.read_bytes()).decode()
        for name, value in values.items():
            subprocess.run(
                ["gh", "secret", "set", name, "--repo", repo], input=value, text=True, check=True
            )
            print(f"Uploaded {name}")
    print(f"Release-key backup: {BACKUP}")
    print(f"Certificate SHA-256: {digest}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="command", required=True)
    setup_parser = subparsers.add_parser("setup", help="Generate or reuse the permanent release key")
    setup_parser.add_argument("--upload", action="store_true")
    setup_parser.add_argument("--repo", default="0xjogge/frem")
    run_parser = subparsers.add_parser("run", help="Run a command with local signing credentials")
    run_parser.add_argument("args", nargs=argparse.REMAINDER)
    args = parser.parse_args()
    if args.command == "setup":
        setup(args.upload, args.repo)
    else:
        if not args.args:
            parser.error("Supply a command, for example: run ./gradlew :app:assembleRelease")
        raise SystemExit(subprocess.run(args.args, env=signing_environment(), cwd=ROOT).returncode)


if __name__ == "__main__":
    main()
