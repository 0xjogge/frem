#!/usr/bin/env python3
"""Run prebuilt APKs and check JUnit's result, not just adb's exit status."""

import argparse
from pathlib import Path
import re
import subprocess


def tests_passed(output):
    summary = re.search(r"^OK \(([1-9][0-9]*) tests?\)\s*$", output, re.MULTILINE)
    failure = re.search(
        r"FAILURES!!!|INSTRUMENTATION_FAILED|INSTRUMENTATION_ABORTED|"
        r"INSTRUMENTATION_RESULT: shortMsg=|Process crashed",
        output,
    )
    return summary is not None and failure is None


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk_directory", type=Path)
    args = parser.parse_args()
    reports = Path("build/reports/device-tests")
    reports.mkdir(parents=True, exist_ok=True)
    for name in ("app-debug.apk", "app-debug-androidTest.apk"):
        matches = list(args.apk_directory.rglob(name))
        if len(matches) != 1:
            parser.error(f"Expected exactly one {name}, found {len(matches)}")
        subprocess.run(["adb", "install", "-r", str(matches[0])], check=True)
    try:
        result = subprocess.run(
            [
                "adb", "shell", "am", "instrument", "-w", "-r",
                "dk.frem.app.test/androidx.test.runner.AndroidJUnitRunner",
            ],
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            timeout=900,
        )
        output = result.stdout
        success = result.returncode == 0 and tests_passed(output)
    except subprocess.TimeoutExpired as error:
        output = error.stdout or ""
        if isinstance(output, bytes):
            output = output.decode(errors="replace")
        output += "\nInstrumentation timed out after 900 seconds.\n"
        success = False
    (reports / "instrumentation.txt").write_text(output)
    try:
        logcat = subprocess.run(
            ["adb", "logcat", "-d"], capture_output=True, text=True, timeout=30
        )
        (reports / "logcat.txt").write_text(logcat.stdout)
    except subprocess.TimeoutExpired:
        (reports / "logcat.txt").write_text("Collecting logcat timed out.\n")
    print(output)
    if not success:
        raise SystemExit("Instrumentation tests failed or did not report a completed test run.")
    screenshots = reports / "screenshots"
    screenshots.mkdir(exist_ok=True)
    for theme in ("light", "dark"):
        image = subprocess.check_output(
            [
                "adb", "exec-out", "run-as", "dk.frem.app", "cat",
                f"cache/contrast-{theme}-TODAY.png",
            ],
            timeout=30,
        )
        if not image.startswith(b"\x89PNG\r\n\x1a\n"):
            raise SystemExit(f"Invalid {theme} screenshot from the emulator")
        (screenshots / f"frem-{theme}.png").write_bytes(image)


if __name__ == "__main__":
    main()
