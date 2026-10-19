#!/usr/bin/env python3
"""Verify all five ABI/universal APKs before staging signed release assets."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import struct
import subprocess
import zipfile

from release import parse_version


ABIS = ("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
ELF_ARCHITECTURES = {
    "arm64-v8a": (2, 183),
    "armeabi-v7a": (1, 40),
    "x86_64": (2, 62),
    "x86": (1, 3),
}
LABELS = {
    None: "Universal · all supported devices",
    "arm64-v8a": "ARM64 · most modern phones",
    "armeabi-v7a": "ARM32 · older 32-bit ARM devices",
    "x86_64": "Intel 64-bit · devices and emulators",
    "x86": "Intel 32-bit · devices and emulators",
}


def read_outputs(directory, tag=None):
    """Use Gradle's active outputs, not globs that might include stale APKs."""
    directory = Path(directory).resolve()
    metadata = json.loads((directory / "output-metadata.json").read_text())
    if (
        metadata.get("applicationId") != "dk.frem.app"
        or metadata.get("variantName") != "release"
        or metadata.get("artifactType", {}).get("type") != "APK"
    ):
        raise ValueError("Expected Gradle metadata for dk.frem.app's release APKs.")
    elements = metadata.get("elements")
    if not isinstance(elements, list) or len(elements) != len(ABIS) + 1:
        raise ValueError(
            "Expected exactly one universal APK and four ABI APKs; "
            "build with -PfremSplitApks=true."
        )
    if tag is None:
        tag = f"{elements[0].get('versionName')}-release"
    feature, patch = parse_version(tag)
    version = f"{feature}.{patch}"
    outputs = {}
    paths = set()
    for element in elements:
        if (
            element.get("versionName") != version
            or element.get("versionCode") != feature * 10_000 + patch
        ):
            raise ValueError(
                "Every APK must have the requested release version and identical version code."
            )
        filters = element.get("filters")
        if filters == []:
            abi = None
        elif (
            isinstance(filters, list)
            and len(filters) == 1
            and filters[0].get("filterType") == "ABI"
            and filters[0].get("value") in ABIS
        ):
            abi = filters[0]["value"]
        else:
            raise ValueError("APK outputs may contain only a single supported ABI filter.")
        if abi in outputs:
            raise ValueError(f"Duplicate APK output for {abi or 'universal'}.")
        filename = element.get("outputFile")
        if not isinstance(filename, str) or Path(filename).is_absolute():
            raise ValueError("APK output filenames must be relative to the release directory.")
        apk = (directory / filename).resolve()
        if not apk.is_relative_to(directory) or apk.suffix != ".apk" or not apk.is_file():
            raise ValueError(f"Missing or invalid APK output: {filename}")
        if apk in paths:
            raise ValueError("Each APK output must reference a distinct file.")
        outputs[abi] = apk
        paths.add(apk)
    if set(outputs) != {None, *ABIS}:
        raise ValueError("Missing universal or supported ABI APK output.")
    return tag, {abi: outputs[abi] for abi in (None, *ABIS)}


def verify_identity(badging, tag):
    feature, patch = parse_version(tag)
    package = re.search(
        r"^package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'",
        badging,
        re.MULTILINE,
    )
    expected = ("dk.frem.app", str(feature * 10_000 + patch), f"{feature}.{patch}")
    if (
        package is None
        or package.groups() != expected
        or re.search(r"^application-debuggable(?:\s|$)", badging, re.MULTILINE)
    ):
        raise ValueError("APK package, version, or debuggable flag does not match the release.")


def verify_certificate(verification, certificate):
    digests = re.findall(r"Signer #\d+ certificate SHA-256 digest: ([0-9a-f]+)", verification)
    if digests != [certificate]:
        raise ValueError("The APK is not signed with Frem's pinned release certificate.")


def native_libraries(apk, abi):
    """Check both ZIP directory names and the actual ELF architecture of every library."""
    libraries = {}
    with zipfile.ZipFile(apk) as archive:
        for entry in archive.infolist():
            if not entry.filename.startswith("lib/") or not entry.filename.endswith(".so"):
                continue
            parts = entry.filename.split("/")
            if len(parts) != 3 or parts[1] not in ABIS or entry.filename in libraries:
                raise ValueError(
                    f"Unexpected native library path in {apk.name}: {entry.filename}"
                )
            with archive.open(entry) as library:
                header = library.read(20)
                digest = hashlib.sha256(header)
                for chunk in iter(lambda: library.read(1024 * 1024), b""):
                    digest.update(chunk)
            if len(header) < 20 or header[:4] != b"\x7fELF" or header[5] not in (1, 2):
                raise ValueError(f"Invalid ELF library in {apk.name}: {entry.filename}")
            machine = struct.unpack_from(
                "<H" if header[5] == 1 else ">H", header, 18
            )[0]
            if (header[4], machine) != ELF_ARCHITECTURES[parts[1]]:
                raise ValueError(
                    f"Native library architecture does not match its ABI: {entry.filename}"
                )
            libraries[entry.filename] = (digest.hexdigest(), entry.file_size)
    expected = set(ABIS) if abi is None else {abi}
    if {name.split("/")[1] for name in libraries} != expected:
        raise ValueError(f"Incorrect native ABI set in {apk.name}; expected {sorted(expected)}.")
    return libraries


def verify_build(outputs, tag, tools, certificate=None):
    contents = {}
    for abi, apk in outputs.items():
        badging = subprocess.check_output(
            [str(tools / "aapt2"), "dump", "badging", str(apk)], text=True
        )
        verify_identity(badging, tag)
        contents[abi] = native_libraries(apk, abi)
        if certificate is not None:
            verification = subprocess.check_output(
                [str(tools / "apksigner"), "verify", "--verbose", "--print-certs", str(apk)],
                text=True,
            )
            verify_certificate(verification, certificate)
        print(f"Checked {abi or 'universal'}: {apk.stat().st_size / 1024**2:.1f} MiB")
    universal = contents[None]
    for abi in ABIS:
        expected = {
            name: value for name, value in universal.items()
            if name.startswith(f"lib/{abi}/")
        }
        if contents[abi] != expected:
            raise ValueError(f"Native libraries in the {abi} APK do not match the universal APK.")


def asset_name(tag, abi):
    return f"frem-{tag}{f'-{abi}' if abi else ''}.apk"


def download_notes(tag, outputs):
    rows = [
        "<!-- frem-downloads -->",
        "",
        "### Downloads",
        "",
        "Each APK is a complete installer. **Most phones: ARM64. Unsure: Universal.**",
        "",
        "| Architecture | APK | Size |",
        "| --- | --- | ---: |",
    ]
    for abi, apk in outputs.items():
        name = asset_name(tag, abi)
        url = f"https://github.com/0xjogge/frem/releases/download/{tag}/{name}"
        rows.append(
            f"| {LABELS[abi]} | [{name}]({url}) | {apk.stat().st_size / 1024**2:.1f} MiB |"
        )
    rows.extend([
        "",
        "All builds use the same package ID, signing key, and version code. "
        "Updating from an older universal build to a compatible architecture build "
        "preserves your data.",
        "",
        "For Obtainium, select the matching architecture or use an explicit APK filename "
        "filter. [Download and filter guidance]"
        "(https://github.com/0xjogge/frem/blob/main/RELEASING.md#obtainium).",
        "",
    ])
    return "\n".join(rows)


def stage_assets(outputs, tag, output):
    # Called only after every APK has passed identity, ABI, content, and certificate checks.
    if any(apk.is_relative_to(output.resolve()) for apk in outputs.values()):
        raise ValueError("The staging directory must not contain input APKs.")
    if output.exists():
        shutil.rmtree(output)
    output.mkdir(parents=True)
    for abi, apk in outputs.items():
        shutil.copyfile(apk, output / asset_name(tag, abi))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("tag", nargs="?")
    parser.add_argument(
        "--apk-directory", type=Path, default=Path("app/build/outputs/apk/release")
    )
    parser.add_argument(
        "--check-build", action="store_true",
        help="Check APK identities and native contents without signing secrets or staging assets",
    )
    parser.add_argument(
        "--notes-file", type=Path,
        help="Append the verified APK download table to release notes",
    )
    args = parser.parse_args()
    if not args.check_build and args.tag is None:
        parser.error("A release tag is required to stage signed APKs.")
    if args.check_build and args.notes_file is not None:
        parser.error("Release notes can only be updated when staging signed APKs.")
    try:
        tag, outputs = read_outputs(args.apk_directory, args.tag)
        tools = Path(os.environ["ANDROID_HOME"]) / "build-tools/36.0.0"
        certificate = (
            None if args.check_build
            else Path(".github/release-signing-certificate.sha256").read_text().strip()
        )
        verify_build(outputs, tag, tools, certificate)
        if args.check_build:
            print("All five APK identities and native contents checked; no release assets staged.")
            return
        stage_assets(outputs, tag, Path("build/release"))
        if args.notes_file is not None:
            original = args.notes_file.read_text().split("<!-- frem-downloads -->", 1)[0].rstrip()
            args.notes_file.write_text(f"{original}\n\n{download_notes(tag, outputs)}")
        print(f"Verified all five signed APKs for {tag}; release assets staged in build/release")
    except (ValueError, OSError, zipfile.BadZipFile, subprocess.CalledProcessError) as error:
        raise SystemExit(str(error)) from error


if __name__ == "__main__":
    main()
