#!/usr/bin/env python3
"""Verify the signed APK's identity and version before staging release assets."""

import argparse
import hashlib
import os
from pathlib import Path
import re
import shutil
import subprocess

from release import parse_version


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("tag")
    args = parser.parse_args()
    feature, patch = parse_version(args.tag)
    apk = Path("app/build/outputs/apk/release/app-release.apk")
    tools = Path(os.environ["ANDROID_HOME"]) / "build-tools/36.0.0"
    certificate = Path(".github/release-signing-certificate.sha256").read_text().strip()
    verification = subprocess.check_output(
        [str(tools / "apksigner"), "verify", "--verbose", "--print-certs", str(apk)], text=True
    )
    digests = re.findall(r"Signer #\d+ certificate SHA-256 digest: ([0-9a-f]+)", verification)
    if digests != [certificate]:
        raise SystemExit("The APK is not signed with Frem's pinned release certificate.")
    badging = subprocess.check_output([str(tools / "aapt2"), "dump", "badging", str(apk)], text=True)
    expected = (
        f"package: name='dk.frem.app' versionCode='{feature * 10_000 + patch}' "
        f"versionName='{feature}.{patch}'"
    )
    if expected not in badging or "application-debuggable" in badging:
        raise SystemExit("APK package, version, or debuggable flag does not match the release.")
    output = Path("build/release")
    output.mkdir(parents=True, exist_ok=True)
    assets = [
        (apk, f"frem-{args.tag}-universal.apk"),
        (Path("app/build/outputs/mapping/release/mapping.txt"), f"frem-{args.tag}-mapping.txt"),
    ]
    checksums = []
    for source, name in assets:
        shutil.copyfile(source, output / name)
        digest = hashlib.sha256((output / name).read_bytes()).hexdigest()
        checksums.append(f"{digest}  {name}")
    (output / "SHA256SUMS").write_text("\n".join(checksums) + "\n")
    print(verification)
    print(f"Verified dk.frem.app {feature}.{patch}; release assets staged in {output}")


if __name__ == "__main__":
    main()
