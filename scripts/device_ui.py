#!/usr/bin/env python3
"""Small ADB accessibility helper for inspecting Frem on a physical phone."""
import argparse
import re
import subprocess
import time
import xml.etree.ElementTree as ET


def hierarchy():
    subprocess.run(["adb", "shell", "input", "keyevent", "KEYCODE_WAKEUP"], check=True)
    time.sleep(0.3)
    output = subprocess.check_output(["adb", "exec-out", "uiautomator", "dump", "/dev/tty"], text=True)
    start = output.index("<?xml")
    end = output.index("</hierarchy>") + len("</hierarchy>")
    return ET.fromstring(output[start:end])


parser = argparse.ArgumentParser()
parser.add_argument("command", choices=["inspect", "tap"])
parser.add_argument("label", nargs="?")
parser.add_argument("--contains", action="store_true")
parser.add_argument("--package", default="dk.frem.app", help="Limit inspection output to the target app")
args = parser.parse_args()
nodes = list(hierarchy().iter("node"))
if args.command == "inspect":
    for node in nodes:
        if node.get("package") != args.package:
            continue
        label = node.get("text") or node.get("content-desc")
        if label:
            print(f"{node.get('bounds')}  {label}")
else:
    matches = [n for n in nodes if any(
        args.label in n.get(key, "") if args.contains else args.label == n.get(key, "")
        for key in ("text", "content-desc")
    )]
    if not matches:
        raise SystemExit(f"No visible node matching {args.label!r}")
    node = matches[0]
    left, top, right, bottom = map(int, re.findall(r"\d+", node.get("bounds")))
    subprocess.run(["adb", "shell", "input", "tap", str((left + right) // 2), str((top + bottom) // 2)], check=True)
