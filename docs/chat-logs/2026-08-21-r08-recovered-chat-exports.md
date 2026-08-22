# R08 Recovered Chat Exports — 2026-08-21

This source-controlled handoff was reconstructed from two ChatGPT exports supplied by the user after forced chat restarts. It exists so future threads can recover the exact R08 state without relying on conversation memory.

## Provenance

- `ChatGPT_2026-08-21-15-17-35.txt`
  - SHA-256: `569043834af190da9633759bd7b945d91c9950ffdea76db3b40aa6b424aa3719`
- `ChatGPT_2026-08-21-15-19-15.txt`
  - SHA-256: `feb564db2486c2d98a2f6c6b990b21ae2be86622e891dba73f0b3b63e961496b`

The exports overlap heavily. This file preserves the development facts and decisions that matter to the repository rather than duplicating UI/export boilerplate.

## Canonical project state

- Project: Chrovelo / mpvFlux Shader Lab refactor for Pixel 9 Pro XL, Android 16.
- Repository: `mekromn/mpvFlux-OSD`
- Active branch: `agent/upstream-refactor`
- Roadmap step: **R08**.
- R08 is **not DONE** and R09 must not begin until the real Pixel 9 Pro XL acceptance gates pass.
- `agent/native-shader-lab` is older behavioral-reference work, not the active implementation branch.

## R08 architecture already implemented

The resident architecture is intended to be:

`native semantic control -> validated typed value -> mpv glsl-shader-opts -> resident vo=gpu shader PARAM -> frame`

Normal shader tuning must update resident `vo=gpu` PARAM values instead of rewriting/reloading generated shaders.

Important implementation milestones recovered from the chats:

- `ff68a3f5` — FFmpeg/native blocker fixed; custom arm64 `libmpv` built; `vo=gpu` PARAM support / 64-parameter patch passed; MPVLib wrapper compatibility passed; arm64 ELF 16 KB alignment checks passed.
- `4d371c8e` — fixed generated Kotlin scope error where `luaStateSnapshot()` was accidentally placed inside the companion object.
- `a8081226` — integrated resident `libmpv` and Lua state synchronization, including startup state adoption.
- `3dad9930` — added R08 device telemetry and enabled it for production testing.
- `5050fcf7` — runtime packaging repair after the telemetry APK hung on splash because custom `libmpv.so` required `libxml2.so` but the APK did not package it. The repair packages the matching `libxml2` dependency.

A key architectural requirement recovered from the earlier work is that native tuning must silently synchronize Lua's in-memory preset/state bank without causing shader regeneration; otherwise a preset save after resident tuning could capture stale values.

## Device-test history before renderer-parity work

A telemetry-enabled R08 phone build passed the unit suite, APK build, signing, package/version verification, and artifact upload. The first Pixel test APK had SHA-256:

`57e4dab1e56f01ee3b48162b45599fd9a6d1adb078ba4ead08cab4b7cb4358ce`

That APK never got past the splash screen on the Pixel 9 Pro XL. The native dependency inspection found the missing `libxml2.so` packaging defect described above.

The runtime-repair APK then had SHA-256:

`0c7d078c07ce6f31c8bb78028dde2998a7aaa957e2043cfb45a5bd062a491748`

The subsequent visual investigation established that merely making the resident native build run was not sufficient: renderer fidelity had drifted from accepted R07 behavior.

## Exact accepted R07 renderer fingerprint

The accepted R07 APK was inspected and identified as:

```text
mpv        v0.41.0-224-gd54bad563
FFmpeg     N-122998-g5ba2525c7a
libplacebo v7.360.0 (v7.360.0-3-gc93aa134)
Vulkan     enabled
vo         gpu
```

Exact commits to preserve for the parity baseline:

```text
mpv        d54bad5636924ab3f39cb6e397b94b6aa8a7c433
FFmpeg     5ba2525c7affc29cbd99e6266946b382d3fffe8b
libplacebo c93aa134ab62365ce1177efff99b8e1e66a818e7
```

The accepted R07 `libmpv.so` directly requires `libvulkan.so`, and the build configuration/features confirm Vulkan support was enabled.

## Root cause of the renderer-fidelity drift

The exact pinned Secozzi native harness used during R08 was inspected. Its libplacebo build script contained:

```bash
meson setup $build --cross-file "$prefix_dir"/crossfile.txt \
    -Dvulkan=disabled -Ddemos=false
```

It also normally shallow-cloned current libplacebo HEAD rather than preserving the R07 libplacebo revision/fingerprint.

Therefore an R08 build could pass PARAM tests, Gradle, ELF checks, signing, and runtime loading while still having a different renderer from R07. The parity repair must not substitute a newer renderer merely because it compiles.

## Frozen R08 parity target

R08 must first become effectively **R07 renderer + resident PARAM capability**:

```text
mpv        d54bad5636924ab3f39cb6e397b94b6aa8a7c433
FFmpeg     5ba2525c7affc29cbd99e6266946b382d3fffe8b
libplacebo c93aa134ab62365ce1177efff99b8e1e66a818e7
Vulkan     ENABLED
vo         gpu
PARAM      91ceffce -> 0d655fe
PARAM max  64
```

Do **not** add the mined post-R07 improvements to the parity build yet. First establish real Pixel visual equivalence to R07.

## Upstream miner results to preserve for later

The miner was run against the real R07 mpv cutoff `d54bad563` rather than a vague recent-history window.

### Safe/high-value mpv candidates after parity

- `c1a21bb8` — protect shader-hook component overwrites. Verdict: TAKE after parity; low visual risk, high Shader Lab robustness.
- `8d04be2b` — correct Vulkan image-stride alignment. Verdict: TAKE after parity.
- `11b2004b` — scaler-option inheritance correctness; relevant to separate `scale`, `cscale`, and `dscale` settings.
- `702abfd5` — use actual Vulkan texture dimensions; relevant to polar/EWA scaling and padded hardware-decoded dimensions.
- `7eb1ef2f` — resizable filter windows / proper `tscale-radius`; later motion-quality work.
- `7f72f64b` — VO referenced-frame requirements; mainly future reliability/frame-queue work.

### Advanced resident PARAM stack

- `a0b62333` — DEFINE parameters. Useful for infrequent structural/compile-time switches, not rapidly adjusted sliders because DEFINE changes can require shader recompilation.
- `3e0729eb` — ENUM parameters. High-value for named discrete modes without Android-side magic numbers; integer-backed ENUM can remain runtime-selectable.
- `7d1a598b` — PARAM values inside RPN expressions, including `WHEN`, `WIDTH`, and `HEIGHT`. High-value because entire passes can be conditionally skipped instead of merely multiplying output by zero.

Approximate capability chain:

`91ceffce helper -> 0d655fe basic PARAM -> a0b62333 DEFINE -> 3e0729eb ENUM -> supporting auto-param work -> 7d1a598b RPN PARAM`

R08 currently only requires the basic PARAM capability plus the raised 64-PARAM ceiling.

### libplacebo Vulkan candidates for isolated Pixel A/B later

Do not upgrade libplacebo wholesale merely to obtain these; investigate surgical backports onto the exact R07 baseline.

- `738ed4e` — avoid raster/compute image-layout corruption by retaining storage-capable targets in `VK_IMAGE_LAYOUT_GENERAL`. High-priority experiment; Pixel A/B required.
- `65054fab` — validate Vulkan layout transitions before attempting them.
- `f644b2ed` — avoid impossible Vulkan memory-type requests when host-transfer usage removes all valid memory types.
- `464d6eab` + `d4624cb` — Vulkan synchronization/layout-transition correctness sequence, including a write-after-write hazard; treat as a coherent pair/sequence.
- `3dd24a5` — Vulkan texture-transfer correctness with offsets.
- `0d1bded` + `8c5b8d2` — newer Vulkan queue handling; future-proofing/stability rather than reference-renderer work.

### Motion/audio candidate for later

- `68b1d10d` — rewritten display-sync audio drift controller with low-pass-filtered A/V error, proportional correction, gradual recovery, and adaptive slew limits. Keep for a dedicated motion/audio A/B after R08; do not mix into renderer parity.

### Miner negatives

The post-R07 history did **not** reveal a major missing Android-specific `vo=gpu` patch series, compelling post-R07 MediaCodec-copy improvements for this path, or a meaningful post-R07 `vo=gpu` dithering fix.

## Current R08 blocker at the handoff

The renderer-parity workflow reached the true native gate and failed specifically while rebuilding the exact R07 Vulkan renderer. Earlier steps such as checkout/dependency setup/resident source validation passed; normal Chrovelo jobs could succeed, but those normal artifacts are not renderer-parity artifacts and must not be used for acceptance.

Known workflow/job checkpoint:

- R08 renderer-parity job ID: `96881103884`
- Failure location: exact R07 Vulkan renderer native rebuild, before parity AAR/APK packaging.
- Failure diagnostics were collected/uploaded by the workflow.

The precise native compiler/Meson/shaderc/NDK error must be obtained from the failure diagnostic artifact / `native-build.log`. Do not guess the failure cause.

## Immediate next action — no ambiguity

1. Recover the failure-diagnostics artifact for job `96881103884` and read the first actual native-build error.
2. Patch only that proven blocker while keeping the exact mpv/FFmpeg/libplacebo revisions, Vulkan path, `vo=gpu`, basic PARAM support, and 64-PARAM ceiling frozen.
3. Re-run until the renderer-parity job passes native build, AAR/APK packaging, signing, dependency/fingerprint gates, and artifact publication.
4. Install the parity APK on the real Pixel 9 Pro XL and A/B against accepted R07 behavior.
5. Validate resident PARAM immediacy, no shader rewrites/swaps during normal tuning, final slider/render agreement, no blue/flash/hitch regressions, expanded-brightness preservation, telemetry, and HDR protection.
6. Only then mark R08 DONE and move to R09.

## Long-term renderer-fingerprint requirement

After parity is restored, add/keep a CI/runtime renderer fingerprint covering at least:

- mpv / FFmpeg / libplacebo versions and exact revisions where possible
- active GPU API and Vulkan device
- Vulkan availability/features
- swapchain format / bit depth
- FBO format
- decoded pixel format
- scaler kernels/options
- active shader list
- resident PARAM state
- native dependency graph / critical DT_NEEDED libraries

This is intended to prevent a dependency, feature flag, native library, or renderer path from silently drifting again.
