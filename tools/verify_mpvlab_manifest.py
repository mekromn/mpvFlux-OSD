#!/usr/bin/env python3
"""Verify the normalized mpvLab engine and R08 Pixel runtime contract."""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import re
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


def parse_resident_param_defaults(shader: str) -> dict[str, float]:
    lines = shader.splitlines()
    values: dict[str, float] = {}
    for index, raw in enumerate(lines):
        line = raw.strip()
        if not line.startswith("//!PARAM "):
            continue
        name = line.removeprefix("//!PARAM ").strip()
        default: float | None = None
        cursor = index + 1
        while cursor < len(lines):
            candidate = lines[cursor].strip()
            if candidate.startswith("//!PARAM ") or candidate.startswith("//!HOOK "):
                break
            if candidate and not candidate.startswith("//!"):
                try:
                    default = float(candidate)
                except ValueError:
                    pass
                break
            cursor += 1
        if default is None:
            raise ValueError(f"PARAM {name} has no numeric default")
        values[name] = default
    return values


def parse_legacy_lua_defaults(lua: str) -> dict[str, float]:
    values: dict[str, float] = {}
    for line in lua.splitlines():
        key_match = re.search(r'key="([A-Z0-9_-]+)"', line)
        default_match = re.search(r'\bd=([-+]?(?:\d+(?:\.\d*)?|\.\d+)(?:[eE][-+]?\d+)?)', line)
        kind_match = re.search(r'kind="([^"]+)"', line)
        if not (key_match and default_match and kind_match):
            continue
        key = key_match.group(1)
        kind = kind_match.group(1)
        if kind == "shader" or key in {"SHADER_PROOF", "LUMA_MASTER", "CHROMA_MASTER"}:
            values[key] = float(default_match.group(1))
    return values


def parse_kotlin_catalog_defaults(source: str) -> dict[str, float]:
    pattern = re.compile(
        r'spec\(ShaderLabControlId\.([A-Z0-9_]+),\s*'
        r'ShaderLabGroup\.[A-Z0-9_]+,\s*'
        r'ShaderLabControlKind\.[A-Z0-9_]+,\s*'
        r'"[^"]*",\s*'
        r'([-+]?(?:\d+(?:\.\d*)?|\.\d+)(?:[eE][-+]?\d+)?)'
    )
    return {match.group(1): float(match.group(2)) for match in pattern.finditer(source)}


def compare_defaults(
    label: str,
    resident: dict[str, float],
    reference: dict[str, float],
    failures: list[str],
) -> None:
    resident_keys = set(resident)
    reference_keys = set(reference)
    if resident_keys != reference_keys:
        missing = sorted(resident_keys - reference_keys)
        extra = sorted(reference_keys - resident_keys)
        if missing:
            failures.append(f"{label} missing resident keys: {', '.join(missing)}")
        if extra:
            failures.append(f"{label} has extra resident keys: {', '.join(extra)}")
        return

    for key in sorted(resident_keys):
        expected = reference[key]
        actual = resident[key]
        tolerance = max(1e-12, abs(expected) * 1e-12)
        if abs(actual - expected) > tolerance:
            failures.append(
                f"resident PARAM default mismatch vs {label}: "
                f"{key}: shader={actual}, reference={expected}"
            )


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

    forbidden_android_snippets = (
        'setVo(if (decoderPreferences.gpuNext.get()) "gpu-next" else "gpu")',
        '"mediacodec,mediacodec-copy,no"',
    )
    for snippet in forbidden_android_snippets:
        if snippet in source:
            failures.append(f"MPVView contains conflicting generic renderer path: {snippet}")

    subtitle_preferences = repo / (
        "app/src/main/java/app/marlboroadvance/mpvex/preferences/SubtitlesPreferences.kt"
    )
    track_selector = repo / (
        "app/src/main/java/app/marlboroadvance/mpvex/ui/player/TrackSelector.kt"
    )
    subtitle_source = subtitle_preferences.read_text(encoding="utf-8")
    track_source = track_selector.read_text(encoding="utf-8")
    if 'getBoolean("sub_autoload_enabled", false)' not in subtitle_source:
        failures.append("smart subtitle autoload must default OFF")
    if 'if (!subtitlesPreferences.autoloadMatchingSubtitles.get())' not in track_source:
        failures.append("TrackSelector must honor the subtitle autoload preference")
    if 'MPVLib.setPropertyString("sid", "no")' not in track_source:
        failures.append("TrackSelector must be able to enforce subtitles OFF on fresh playback")

    overlay_path = repo / (
        "app/src/main/java/app/marlboroadvance/mpvex/ui/player/controls/"
        "ShaderLabR08OverlayView.kt"
    )
    if not overlay_path.is_file():
        failures.append("R08 native Shader Lab overlay is missing")
    else:
        overlay = overlay_path.read_text(encoding="utf-8")
        for snippet in (
            "ShaderLabControlCatalog.controls.size == 53",
            "ShaderLabControlCatalog.actions.size == 10",
            "surfaceEntries.size == 63",
            "ShaderLabControlCatalog.actions",
            "ShaderLabCommand.Morph",
            "command.requiresConfirmation()",
        ):
            if snippet not in overlay:
                failures.append(f"R08 native surface lost full-catalog contract: {snippet}")

    resident_path = root / "shaders/pixel9-perceptual-expansion-resident-v3.1.glsl"
    lua_path = root / "scripts/pixel9-shader-lab.lua"
    catalog_path = repo / (
        "app/src/main/java/app/marlboroadvance/mpvex/repository/shaderlab/catalog/"
        "ShaderLabControlCatalog.kt"
    )
    if resident_path.is_file():
        shader = resident_path.read_text(encoding="utf-8")
        count = shader.count("//!PARAM ")
        if count != 40:
            failures.append(f"resident shader PARAM count must be 40, got {count}")
        if "//!PARAM SHADER_PROOF" not in shader:
            failures.append("resident shader must expose SHADER_PROOF as a PARAM")
        if "if (SHADER_PROOF != 0)" not in shader or "return vec4(1.0, 0.0, 1.0, src.a);" not in shader:
            failures.append("resident SHADER_PROOF must provide an unambiguous magenta frame")
        if "//!HOOK LINEAR" not in shader or "//!BIND HOOKED" not in shader:
            failures.append("resident shader must remain a LINEAR/HOOKED pass")
        if "@@" in shader:
            failures.append("resident shader contains unresolved template token")

        try:
            resident_defaults = parse_resident_param_defaults(shader)
            lua_defaults = parse_legacy_lua_defaults(lua_path.read_text(encoding="utf-8"))
            catalog_defaults = parse_kotlin_catalog_defaults(catalog_path.read_text(encoding="utf-8"))
            resident_catalog_defaults = {
                key: catalog_defaults[key]
                for key in resident_defaults
                if key in catalog_defaults
            }
            compare_defaults("legacy Lua catalog", resident_defaults, lua_defaults, failures)
            compare_defaults("typed Kotlin catalog", resident_defaults, resident_catalog_defaults, failures)
        except (OSError, ValueError) as error:
            failures.append(f"resident PARAM default audit failed: {error}")


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
