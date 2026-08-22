#!/usr/bin/env python3
"""Verify the Android NDK identity note embedded in an ELF shared object.

R08 renderer parity uses this instead of matching host `file(1)` prose. The
accepted R07 arm64 ELFs carry `.note.android.ident` with SDK 24, NDK r29, and
NDK build 14206865. This verifier reads that ELF note through readelf and
compares the actual encoded fields.
"""

from __future__ import annotations

import argparse
import re
import subprocess
from pathlib import Path

ANDROID_NOTE_RE = re.compile(
    r"Android\s+0x[0-9a-fA-F]+\s+NT_VERSION.*?description data:\s*"
    r"((?:[0-9a-fA-F]{2}\s+)+)"
)


def read_android_ident(path: Path) -> tuple[int, str, str]:
    text = subprocess.check_output(["readelf", "-nW", str(path)], text=True)
    match = ANDROID_NOTE_RE.search(text)
    if not match:
        raise AssertionError(
            f"{path}: missing Android NT_VERSION note (.note.android.ident)"
        )

    description = bytes.fromhex(match.group(1))
    if len(description) < 132:
        raise AssertionError(
            f"{path}: short Android note description: "
            f"{len(description)} bytes, expected at least 132"
        )

    sdk = int.from_bytes(description[0:4], "little")
    ndk = description[4:68].split(b"\0", 1)[0].decode("ascii")
    build = description[68:132].split(b"\0", 1)[0].decode("ascii")
    return sdk, ndk, build


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("elf", nargs="+", type=Path)
    parser.add_argument("--sdk", type=int, required=True)
    parser.add_argument("--ndk", required=True)
    parser.add_argument("--build", required=True)
    args = parser.parse_args()

    for path in args.elf:
        if not path.is_file():
            raise AssertionError(f"{path}: ELF does not exist")
        sdk, ndk, build = read_android_ident(path)
        if sdk != args.sdk:
            raise AssertionError(f"{path}: Android SDK note {sdk}, expected {args.sdk}")
        if ndk != args.ndk:
            raise AssertionError(f"{path}: NDK note {ndk!r}, expected {args.ndk!r}")
        if build != args.build:
            raise AssertionError(
                f"{path}: NDK build note {build!r}, expected {args.build!r}"
            )
        print(
            f"{path}: Android ELF note PASS "
            f"sdk={sdk} ndk={ndk} build={build}"
        )

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
