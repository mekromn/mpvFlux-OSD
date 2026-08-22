#!/usr/bin/env python3
"""Verify the normalized mpvLab engine and R08 Pixel runtime contract."""

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


def active_config_lines(text: str) -> set[str]:
    return {
        line.strip()
        for line in text.splitlines()
        if line.strip() and not line.lstrip().startswith("#")
    }


def verify_r08_runtime_contract(root: Path, manifest: dict, failures: list[str]) -> None:
    """Guard the Pixel-specific defaults that must survive refactors.

    R08 intentionally has one owner for each layer:
      * mpv.conf owns renderer/default/profile policy;
      * Android owns controller loading and resident shader attachment/PARAM I/O.
    """

    if manifest.get("controlCatalogVersion") != "legacy-v6.1.1-typed-1":
        failures.append(
            "controlCatalogVersion must match ShaderLabControlCatalog.VERSION "
            "legacy-v6.1.1-typed-1"
        )

    mpv_conf = root / "config/mpv.conf"
    if mpv_conf.is_file():
        lines = active_config_lines(mpv_conf.read_text(encoding="utf-8"))
        for required in manifest.get("requiredMpvOptions", []):
            if required not in lines:
                failures.append(f"mpv.conf missing required active line: {required}")

        # R08 Android owns these entrypoints. Loading them from mpv.conf as well
        # risks duplicate Lua instances or competing resident shader attachment.
        for forbidden_prefix in ("script=", "glsl-shaders="):
            offenders = sorted(line for line in lines if line.startswith(forbidden_prefix))
            if offenders:
                failures.append(
                    f"mpv.conf must not own R08 {forbidden_prefix[:-1]}: "
                    + ", ".join(offenders)
                )

    repo = Path(__file__).resolve().parents[1]
    mpv_view = repo / "app/src/main/java/app/marlboroadvance/mpvex/ui/player/MPVView.kt"
    if not mpv_view.is_file():
        failures.append(f"MPVView missing: {mpv_view}")
        return

    source = mpv_view.read_text(encoding="utf-8")
    required_android_snippets = (
        'MPVLib.setOptionString("config", "yes")',
        'MPVLib.setOptionString("config-dir", shaderLabPaths.config.absolutePath)',
        'MPVLib.setOptionString("input-conf", shaderLabPaths.config.resolve("input.conf").absolutePath)',
        'setVo("gpu")',
        'MPVLib.setOptionString("gpu-context", "androidvk")',
        'MPVLib.setOptionString("gpu-api", "vulkan")',
        'MPVLib.setOptionString("fbo-format", "rgba16f")',
        '"mediacodec-copy,mediacodec,no"',
        'MPVLib.setOptionString("sid", "no")',
        'MPVLib.setOptionString("secondary-sid", "no")',
    )
    for snippet in required_android_snippets:
        if snippet not in source:
            failures.append(f"MPVView lost Pixel Shader Lab invariant: {snippet}")

    # Generic app preference paths must not be able to supersede this branch's
    # empirically verified vo=gpu/Vulkan path during initialization.
    forbidden_android_snippets = (
        'setVo(if (decoderPreferences.gpuNext.get()) "gpu-next" else "gpu")',
        '"mediacodec,mediacodec-copy,no"',
    )
    for snippet in forbidden_android_snippets:
        if snippet in source:
            failures.append(f"MPVView contains conflicting generic renderer path: {snippet}")

    resident = root / "shaders/pixel9-perceptual-expansion-resident-v3.1.glsl"
    if resident.is_file():
        shader = resident.read_text(encoding="utf-8")
        count = shader.count("//!PARAM ")
        if count != 39:
            failures.append(f"resident shader PARAM count must be 39, got {count}")
        if "//!HOOK LINEAR" not in shader or "//!BIND HOOKED" not in shader:
            failures.append("resident shader must remain a LINEAR/HOOKED pass")
        if "@@" in shader:
            failures.append("resident shader contains unresolved template token")


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

    verify_r08_runtime_contract(root, manifest, failures)

    if failures:
        print("mpvLab engine manifest/runtime verification FAILED", file=sys.stderr)
        for failure in failures:
            print(f"- {failure}", file=sys.stderr)
        return 1

    print(
        "mpvLab engine manifest/runtime verification OK: "
        f"{len(actual_files)} files, engine={manifest.get('engineVersion')}, "
        f"schema={manifest.get('schemaVersion')}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
