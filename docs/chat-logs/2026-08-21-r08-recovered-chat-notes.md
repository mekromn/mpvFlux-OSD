# R08 Recovered Chat Notes — 2026-08-21

This record was reconstructed from two user-provided ChatGPT exports and is intended to complement `2026-08-21-r08-continuity.md`.

Raw source exports preserved unchanged in this repository:

- `docs/chat-logs/2026-08-21-chat-export-15-17-35.txt`
- `docs/chat-logs/2026-08-21-chat-export-15-19-15.txt`

## Active project state

- Repository: `mekromn/mpvFlux-OSD`
- Active branch: `agent/upstream-refactor`
- Roadmap step: **R08 IN_PROGRESS**
- Do not advance to R09 until the exact R07 renderer plus resident PARAM capability passes the real Pixel 9 Pro XL acceptance gates.

## Recovered R08 implementation history

The exports confirm the following sequence:

- Resident V3.1 `//!PARAM` shader and direct `glsl-shader-opts` transport were integrated.
- R08 bridge/tests, resident SDR configuration, engine-manifest registration, and mpv PARAM prerequisite backport were added.
- `ff68a3f5`: FFmpeg/native blocker repaired; custom arm64 `libmpv` built; vo=gpu PARAM/64 support, wrapper compatibility, and 16 KB ELF checks passed.
- `4d371c8e`: generated Kotlin `luaStateSnapshot()` scope bug repaired.
- `a8081226`: resident libmpv plus Lua state synchronization integrated, including startup state adoption.
- `3dad9930`: R08 device telemetry integrated.
- First telemetry/device build passed automated tests and packaging but the app stalled at splash on the Pixel.
- `5050fcf7`: root cause repaired by packaging the required `libxml2.so` dependency alongside custom `libmpv.so`.

## Exact R07 renderer parity finding

The exported investigation established that the accepted R07 APK used:

```text
mpv        d54bad5636924ab3f39cb6e397b94b6aa8a7c433
FFmpeg     5ba2525c7affc29cbd99e6266946b382d3fffe8b
libplacebo c93aa134ab62365ce1177efff99b8e1e66a818e7
Vulkan     ENABLED
vo         gpu
PARAM      91ceffce -> 0d655fe
PARAM max  64
```

Observed runtime/build fingerprint from the accepted R07 APK:

- mpv `v0.41.0-224-gd54bad563`
- FFmpeg `N-122998-g5ba2525c7a`
- libplacebo `v7.360.0 (v7.360.0-3-gc93aa134)`
- mpv configured with `-Dvulkan=enabled`
- `libmpv.so` directly requires `libvulkan.so`

The pinned Secozzi harness used during the R08 native work was discovered to build libplacebo with:

```bash
meson setup $build --cross-file "$prefix_dir"/crossfile.txt \
    -Dvulkan=disabled -Ddemos=false
```

and normally shallow-cloned current libplacebo rather than pinning the accepted R07 revision. This explains how an R08 custom libmpv could pass PARAM tests, Gradle, ELF alignment, signing, and runtime loading while still producing visibly lower-fidelity output than R07.

## Current hard rule

R08 must first become effectively:

**accepted R07 renderer + resident PARAM capability**

Do **not** add post-R07 mined renderer upgrades until exact renderer parity is restored and accepted visually on the Pixel.

Specifically hold for later:

- `c1a21bb8`
- `8d04be2b`
- `a0b62333`
- `3e0729eb`
- `7d1a598b`
- newer libplacebo Vulkan fixes

## Completed post-R07 mpv miner

The completed mine used the real R07 mpv cutoff `d54bad563` rather than a generic recent-history search.

### Safe/high-value candidates after parity

- `c1a21bb8` — reject invalid shader-hook component overwrites. **TAKE after parity.**
- `8d04be2b` — correct Vulkan image-stride alignment. **TAKE after parity.**

### Advanced resident PARAM stack

- `a0b62333` — DEFINE parameters. Useful for infrequent structural/compile-time switches; not rapid slider tuning.
- `3e0729eb` — ENUM parameters. Valuable for named runtime algorithm/mode selection without magic integers.
- `7d1a598b` — PARAM/ENUM values in shader RPN expressions such as `WHEN`, `WIDTH`, and `HEIGHT`. **Definitely take later.** It allows whole resident passes to be conditionally skipped rather than merely multiplying results by zero.

Expected capability chain:

```text
91ceffce helper
  -> 0d655fe basic PARAM
  -> a0b62333 DEFINE
  -> 3e0729eb ENUM
  -> supporting auto-param work
  -> 7d1a598b RPN PARAM
```

R08 currently requires only the first two plus the 64-PARAM ceiling.

### libplacebo/Vulkan candidates for separate Pixel A/B experiments

- `738ed4e` — keep storage-capable render targets in `VK_IMAGE_LAYOUT_GENERAL` to avoid raster/compute layout corruption on affected Vulkan drivers. High-priority experiment only.
- `65054fab` — validate Vulkan layout transitions before attempting them.
- `f644b2ed` — avoid impossible Vulkan memory-type requests when host-transfer usage would make an image unallocatable.
- `464d6eab` + `d4624cb` — synchronization/layout-transition corrections; treat as a coherent sequence.

Do not upgrade libplacebo wholesale to obtain these. Surgical backports onto the locked R07 baseline must be evaluated one logical family at a time.

### Motion/frame-timing candidate

- `68b1d10d` — rewritten display-sync audio drift controller. Keep for a dedicated motion/audio A/B after R08; do not mix into renderer parity.

### Miner negative findings

The post-R07 mine did **not** find:

- a meaningful new MediaCodec-copy improvement after the R07 cutoff,
- a substantial Android-specific `vo=gpu` patch series after R07,
- a compelling post-R07 `vo=gpu` dithering fix.

## Exact current breakpoint

The exact-R07 Vulkan parity job reached the native rebuild gate and failed there. Earlier steps passed, including checkout, dependencies, resident shader audit, and 39-PARAM source validation. Normal arm64/universal artifacts from that workflow are **not** valid parity test artifacts.

Known failing job from the recovered handoff:

- Workflow: `Refactor Dev APK`
- R08 renderer-parity job ID: `96881103884`
- Failure location: exact R07 Vulkan native renderer rebuild
- Failure diagnostics were uploaded by the workflow.

## Immediate continuation

1. Recover the failure-diagnostics artifact / `native-build.log` for job `96881103884`.
2. Identify the first proven native build blocker.
3. Patch only that blocker while freezing mpv/FFmpeg/libplacebo SHAs and Vulkan-enabled behavior.
4. Re-run until the parity AAR/APK passes native fingerprints, tests, signing, package verification, and 16 KB ELF checks.
5. Test the parity APK on the real Pixel 9 Pro XL against accepted R07 visual behavior.
6. Only after R08 parity and resident tuning acceptance may the mined upgrades be introduced one at a time.
