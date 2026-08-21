from pathlib import Path


def replace_once(path: str, old: str, new: str) -> None:
    p = Path(path)
    text = p.read_text()
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected exactly one match, found {count}")
    p.write_text(text.replace(old, new, 1))

roadmap = "docs/SHADER_LAB_REFACTOR_ROADMAP.md"
progress = "docs/SHADER_LAB_REFACTOR_PROGRESS.md"

old_r08 = '''## R08 — Deterministic shader generation and atomic live-apply pipeline

**Status:** `TODO`

**Goal:** make rapid live tuning stable, low-latency, and race-free.

**Work:** preserve useful double-slot/last-known-good behavior, serialize generation/application, coalesce rapid changes, rollback bad shader applies, expose errors, preserve precision, and add a diagnostic proof mode.

**Validation:** rapid-change stress + intentionally invalid shader test.'''
new_r08 = '''## R08 — Resident `vo=gpu` shader + native parameter transport

**Status:** `IN_PROGRESS`

**Goal:** eliminate GLSL regeneration/file I/O/shader swapping from ordinary live tuning while preserving the empirically proven Pixel `vo=gpu` + Vulkan expanded-brightness path.

**Architecture pivot:** the previous file-reload/coalescing R08 was superseded before any app/shader implementation landed. Normal tuning will use one resident GPU shader with tunable `//!PARAM` values driven through mpv's `glsl-shader-opts`. Generated A/B runtime shaders become compatibility/export fallback only.

**Native prerequisite:** the currently bundled mpv `v0.41.0-224-gd54bad563` predates upstream `vo=gpu` tunable-parameter support. R08 therefore owns a narrowly scoped Chrovelo libmpv build: preserve the proven `d54bad563...` renderer baseline where practical, backport upstream `0d655fe66590009e1d77a17581257d677286531a` (`vo_gpu: initial support for tunable parameters`), and raise `SHADER_MAX_PARAMS` from 16 to 64 so the complete Shader Lab parameter set can remain in one pass.

**Work:**

- produce a reproducible Android libmpv/AAR with the targeted `vo=gpu` PARAM capability and unchanged `is.xyz.mpv.MPVLib` API;
- convert the current Pixel shader into one stable managed resident shader with typed float/int `//!PARAM` definitions and no unresolved runtime template literals;
- preserve V3.1 luminance, Oklab color-volume, skin protection, gamut limiting, diagnostic, and master-control math;
- route shader values through the R07 native bridge to `glsl-shader-opts` at maximum useful numeric precision;
- keep MPV-native controls (`sdr-intensity`, brightness, contrast, gamma, saturation, hue) on direct MPV properties;
- retain last-known-good parameter state and restore it on transport failure without rewriting shader source;
- instrument parameter update count/latency and prove ordinary tuning does not write/swap runtime shader files;
- retain legacy Lua workstation/preset compatibility only until the native UI stages replace it.

**Validation:** full unit/build/signing validation plus Pixel 9 Pro XL / Android 16 proof that `vo=gpu` expanded brightness is unchanged, resident parameters visibly update video, rapid changes end on the exact final value, the resident shader remains unchanged, normal shader-swap count stays flat, HDR remains protected, and parameter latency is recorded.

Detailed design: `docs/R08_RESIDENT_GPU_PARAMETER_ARCHITECTURE.md`.

A deeper renderer API is a contingency only if upstream `glsl-shader-opts` fails the real-device latency requirement; do not resurrect generated-file live tuning as the primary architecture.'''
replace_once(roadmap, old_r08, new_r08)

old_state = '''- `R07_STATUS = DONE`
- `R08_STATUS = TODO`
- R07 closed on a real Pixel 9 Pro XL / Android 16 synchronization smoke using State-3.
- The device produced a typed 53-control snapshot with `status=PASS`, backend `6.1.1-r07-state-3`, positive serial, SDR classification, and no backend error.
- R08 is the next executable roadmap step; no R08 implementation was included in the R07 closeout turn.'''
new_state = '''- `R07_STATUS = DONE`
- `R08_STATUS = IN_PROGRESS`
- R07 closed on a real Pixel 9 Pro XL / Android 16 synchronization smoke using State-3.
- The device produced a typed 53-control snapshot with `status=PASS`, backend `6.1.1-r07-state-3`, positive serial, SDR classification, and no backend error.
- R08 was deliberately redefined before old file-reload implementation landed: ordinary tuning now targets a resident `vo=gpu` shader with native tunable parameters.
- Current bundled mpv `d54bad563...` (2026-02-25) predates upstream `vo=gpu` PARAM support `0d655fe...` (2026-04-17), and upstream's fixed 16-parameter table is too small for Shader Lab; R08 therefore begins with a narrowly scoped Chrovelo libmpv build/backport and a 64-parameter limit.'''
replace_once(progress, old_state, new_state)

old_next = '''## Next step

`CURRENT_STEP = R08` — **Deterministic shader generation and atomic live-apply pipeline**.

R08 is now the next executable step, but it was intentionally **not implemented in this R07 closeout turn**.'''
new_next = '''## R08 architecture pivot / execution start

**Status:** `IN_PROGRESS`

The old R08 file-regeneration/coalescing plan was superseded before app/shader implementation landed. Temporary scaffolding from that abandoned attempt was removed and the feature branch returned to the clean R07 closeout before this pivot.

New R08 target:

- resident `vo=gpu` Pixel shader;
- live `//!PARAM` uniforms through `glsl-shader-opts`;
- native R07 bridge owns parameter transport;
- no GLSL file generation or A/B shader swap during ordinary adjustment;
- exact V3.1 rendering math and Pixel expanded-brightness behavior preserved.

Native prerequisite discovered during replan:

- installed/bundled mpv source commit: `d54bad5636924ab3f39cb6e397b94b6aa8a7c433`, dated 2026-02-25;
- upstream `vo=gpu` tunable-PARAM support: `0d655fe66590009e1d77a17581257d677286531a`, dated 2026-04-17;
- upstream `SHADER_MAX_PARAMS`: 16; Chrovelo target: 64.

Implementation begins by producing and validating the narrowly scoped Android libmpv prerequisite, then converting the shader/bridge to resident parameter transport. Detailed design is in `docs/R08_RESIDENT_GPU_PARAMETER_ARCHITECTURE.md`.

`CURRENT_STEP` remains R08 until the new native/resident pipeline passes real Pixel validation. R09 is not started.'''
replace_once(progress, old_next, new_next)
