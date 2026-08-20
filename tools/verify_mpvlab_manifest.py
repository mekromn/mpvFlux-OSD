#!/usr/bin/env python3
"""Verify the normalized mpvLab engine manifest against bundled asset files."""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import sys


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "root",
        nargs="?",
        default="app/src/main/assets/mpvlab/source",
        help="Normalized mpvLab source root",
    )
    args = parser.parse_args()

    root = Path(args.root)
    manifest_path = root / "engine-manifest.json"
    if not manifest_path.is_file():
        print(f"manifest missing: {manifest_path}", file=sys.stderr)
        return 2

    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    failures: list[str] = []
    declared: set[str] = set()

    for entry in manifest.get("files", []):
        rel = entry["path"]
        declared.add(rel)
        path = root / rel
        if not path.is_file():
            failures.append(f"missing: {rel}")
            continue
        actual = sha256(path)
        expected = entry["sha256"]
        if actual != expected:
            failures.append(f"hash mismatch: {rel}: expected {expected}, got {actual}")

    actual_files = {
        str(path.relative_to(root)).replace("\\", "/")
        for path in root.rglob("*")
        if path.is_file() and path.name != "engine-manifest.json"
    }
    failures.extend(f"undeclared: {path}" for path in sorted(actual_files - declared))
    failures.extend(f"declared but absent: {path}" for path in sorted(declared - actual_files))

    if failures:
        print("mpvLab engine manifest verification FAILED", file=sys.stderr)
        for failure in failures:
            print(f"- {failure}", file=sys.stderr)
        return 1

    print(
        "mpvLab engine manifest verification OK: "
        f"{len(actual_files)} files, engine={manifest.get('engineVersion')}, "
        f"schema={manifest.get('schemaVersion')}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
