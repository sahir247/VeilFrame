#!/usr/bin/env python3
"""Measure VeilFrame cold startup, Java/native memory and UI frame diagnostics with adb.

Examples:
    python scripts/profile_android_runtime.py --runs 7 --out before.json
    python scripts/profile_android_runtime.py --runs 7 --workflow "PDF 300-page export" --out after.json

Runs on Windows, Linux and macOS with platform-tools (adb) on PATH.
Measurements should be collected on the *same physical device*, app build,
thermal state, and input workload before and after a performance change.
"""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
import json
import math
import re
import statistics
import subprocess
import time
from pathlib import Path


def adb(serial: str | None, *args: str, timeout: int = 90) -> str:
    command = ["adb"]
    if serial:
        command += ["-s", serial]
    command.extend(args)
    response = subprocess.run(
        command, text=True, capture_output=True, timeout=timeout, check=False
    )
    if response.returncode != 0:
        raise RuntimeError(
            "adb failed: " + " ".join(command) + "\n" + (response.stderr or response.stdout)
        )
    return response.stdout


def extract_int(pattern: str, text: str) -> int | None:
    match = re.search(pattern, text, re.MULTILINE | re.IGNORECASE)
    return int(match.group(1).replace(",", "")) if match else None


def capture_meminfo(serial: str | None, package: str) -> dict[str, int | None]:
    output = adb(serial, "shell", "dumpsys", "meminfo", package)
    pss = extract_int(r"^\s*TOTAL PSS:\s*([\d,]+)", output)
    if pss is None:
        pss = extract_int(r"^\s*TOTAL\s+([\d,]+)\s+", output)
    return {
        "total_pss_kib": pss,
        "native_pss_kib": extract_int(r"^\s*Native Heap\s+([\d,]+)", output),
        "dalvik_pss_kib": extract_int(r"^\s*Dalvik Heap\s+([\d,]+)", output),
    }


def capture_gfxinfo(serial: str | None, package: str) -> dict[str, int | None]:
    output = adb(serial, "shell", "dumpsys", "gfxinfo", package, timeout=60)
    return {
        "total_frames": extract_int(r"Total frames rendered:\s*([\d,]+)", output),
        "janky_frames": extract_int(r"Janky frames:\s*([\d,]+)", output),
    }


def summarize(samples: list[int]) -> dict[str, float | int] | None:
    if not samples:
        return None
    ordered = sorted(samples)
    return {
        "runs": len(samples),
        "median_ms": statistics.median(ordered),
        "p95_ms": ordered[math.ceil(len(ordered) * 0.95) - 1],
        "min_ms": ordered[0],
        "max_ms": ordered[-1],
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", help="adb device serial; omit for single connected device")
    parser.add_argument("--package", default="com.veilframe.app")
    parser.add_argument("--activity", default=".MainActivity")
    parser.add_argument("--runs", type=int, default=5)
    parser.add_argument("--settle-seconds", type=float, default=1.5)
    parser.add_argument("--workflow", help="Optional manual scenario to capture after startups")
    parser.add_argument("--out", type=Path, default=Path("android_runtime_profile.json"))
    args = parser.parse_args()
    if args.runs < 1 or args.settle_seconds < 0:
        parser.error("runs must be >= 1 and settle-seconds must be >= 0")

    adb(args.serial, "get-state")
    device = {
        "manufacturer": adb(args.serial, "shell", "getprop", "ro.product.manufacturer").strip(),
        "model": adb(args.serial, "shell", "getprop", "ro.product.model").strip(),
        "android_sdk": adb(args.serial, "shell", "getprop", "ro.build.version.sdk").strip(),
    }
    launches: list[dict[str, object]] = []
    component = args.package + "/" + args.activity

    for number in range(1, args.runs + 1):
        adb(args.serial, "shell", "am", "force-stop", args.package)
        output = adb(args.serial, "shell", "am", "start", "-W", "-n", component)
        total_ms = extract_int(r"^\s*TotalTime:\s*(\d+)", output)
        if total_ms is None:
            raise RuntimeError("am start -W did not report TotalTime:\n" + output)
        time.sleep(args.settle_seconds)
        launches.append({
            "iteration": number,
            "cold_start_total_ms": total_ms,
            "memory": capture_meminfo(args.serial, args.package),
            "graphics": capture_gfxinfo(args.serial, args.package),
        })
        print(f"Cold launch {number}/{args.runs}: {total_ms} ms")

    report: dict[str, object] = {
        "generated_utc": datetime.now(timezone.utc).isoformat(),
        "device": device,
        "package": args.package,
        "component": component,
        "method": "am force-stop + am start -W; PSS measured after settle",
        "cold_start": summarize([int(x["cold_start_total_ms"]) for x in launches]),
        "launches": launches,
    }
    if args.workflow:
        input(
            f"Perform the representative '{args.workflow}' operation on-device, "
            "then press Enter to capture memory and frame diagnostics..."
        )
        report["workflow"] = {
            "label": args.workflow,
            "memory": capture_meminfo(args.serial, args.package),
            "graphics": capture_gfxinfo(args.serial, args.package),
        }

    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(f"Saved {args.out.resolve()}")


if __name__ == "__main__":
    main()
