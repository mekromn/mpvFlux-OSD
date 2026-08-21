# R08 Chat Continuity Checkpoint — 2026-08-21

## Project

Chrovelo / mpvFlux Shader Lab refactor for Pixel 9 Pro XL (komodo), Android 16.

Active fork: `mekromn/mpvFlux-OSD`

Active branch: `agent/upstream-refactor`

Legacy behavioral-reference branch: `agent/native-shader-lab` (read-only)

Draft PR: #1 -> `master`

Roadmap pointer: `CURRENT_STEP = R08`

Do not advance to R09 until R08 is validated on the real Pixel 9 Pro XL.

## Non-negotiable rendering constraints

- Preserve `vo=gpu`; `gpu-next` previously failed to trigger the desired Pixel expanded-brightness behavior and looked washed out/desaturated.
- Preserve high precision (`rgba16f` path).
- Preserve `/storage/emulated/0/mpv` canonical storage layout.
- Preserve `hwdec=mediacodec-copy` baseline.
- Normal tuning must use resident `vo=gpu` shader parameters through `glsl-shader-opts`, not generated shader file swaps.
- Touch lifecycle must cancel immediately on release/cancel/focus loss.
- Android TV / D-pad support remains required.

## R08 target architecture

`native semantic control -> validated typed value -> mpv glsl-shader-opts -> resident vo=gpu shader uniform -> frame`

MPV-native properties remain direct mpv property updates.

R08 raises mpv shader PARAM capacity to 64 because Shader Lab requires more than upstream's prior 16.

## Exact renderer parity target

```text
mpv        d54bad5636924ab3f39cb6e397b94b6aa8a7c433
FFmpeg     5ba2525c7affc29cbd99e6266946b382d3fffe8b
libplacebo c93aa134ab62365ce1177efff99b8e1e66a818e7
Vulkan     ENABLED
vo         gpu
PARAM      91ceffce -> 0d655fe
PARAM max  64
```

Exact R07 runtime fingerprint observed from accepted APK:

- mpv `v0.41.0-224-gd54bad563`
- FFmpeg `N-122998-g5ba2525c7a`
- libplacebo `v7.360.0 (v7.360.0-3-gc93aa134)`
- mpv built with Vulkan enabled
- `libmpv.so` DT_NEEDED includes `libvulkan.so`
- R07 binary contains shaderc and libplacebo Vulkan symbols

## Accepted pre-R08 device state

R07 passed on the real Pixel 9 Pro XL / Android 16 with State-3.

Key state:

```text
status=PASS
stage=snapshot_received
backend=6.1.1-r07-state-3
serial=2
source_gamma=bt.1886
source_kind=SDR
sdr_eligible=true
active_bank=B
bypass=false
preview=false
shader_slot=A
swaps=0
control_count=53
error=none
```

## R08 implementation history

Important commits before this checkpoint:

- `ff68a3f5` — initial native blocker fixed; arm64 custom libmpv built; PARAM/64 patch and wrapper compatibility passed; 16 KB ELF checks passed.
- `4d371c8e` — generator scope bug fixed.
- `a8081226` — resident libmpv and Lua state sync integrated.
- `3dad9930` — device telemetry integration.
- `5050fcf70c8ee99e3f82c0a9015f4a0663e10469` — runtime dependency repair after first telemetry APK got stuck at splash because `libxml2.so` was missing.
- `f251bd01982978d2e5f957f017072495b89a697d` — parity rebuild script updated to preserve exact libplacebo `git describe` fingerprint.
- `f2523f083d71e36075d1c5659e3f6f2392656ee0` — stopped automatic runtime-repair retriggers.
- `e2c5fe44d6c58a49feccdd52ec724f327923b462` — moved renderer parity proof into the known-good `Refactor Dev APK` workflow as a second PR job.

## Renderer-parity rebuild machinery

`tools/r08_renderer_parity_rebuild.sh` freezes:

- Secozzi harness `fd17fb02cfb8c8b5c12621c2c90769685d635d91`
- exact FFmpeg commit
- exact libplacebo commit with sufficient history/tag for `git describe --dirty == v7.360.0-3-gc93aa134`
- exact mpv commit
- PARAM support commits
- `SHADER_MAX_PARAMS = 64`
- Android API 24
- 16 KB page-size compatibility
- Vulkan-enabled libplacebo/mpv path
- shaderc integration

The script hard-gates the exact FFmpeg/libplacebo fingerprints, mpv Vulkan config, `pl_vulkan_create`, `libvulkan.so` dependency, and 16 KB load alignment.

## Current failing workflow

Visible run:

- Workflow: `Refactor Dev APK`
- Run ID: `32517088694`
- Event: pull_request
- PR head at run: `e2c5fe44d6c58a49feccdd52ec724f327923b462`
- Normal build job succeeded.
- R08 renderer-parity job ID: `96881103884`

R08 parity job state:

1. Set up job — success
2. Checkout exact PR head — success
3. Build metadata — success
4. Set up JDK 17 — success
5. Install native deps — success
6. Verify R08 resident source — success
7. **Rebuild exact R07 Vulkan renderer plus R08 PARAM support — FAILED**
8+. Packaging/tests/signing/APK/final verification — skipped
13. Collect R08 failure diagnostics — success
14. Upload R08 failure diagnostics — success

Therefore the current blocker is specifically inside native renderer rebuild step 7. The precise compiler/Meson/shaderc/NDK error must be read from the job logs/diagnostic artifact before patching. Do not guess the cause.

Normal artifacts from this run are NOT parity artifacts and must not be given to the user for parity testing.

## Immediate next action

1. Fetch job logs for `96881103884` and identify the first actual native build error.
2. Fetch the run's failure-diagnostics artifact if available.
3. Patch only the proven blocker while keeping mpv/FFmpeg/libplacebo SHAs frozen.
4. Re-run the PR workflow and iterate until the parity job passes every packaging/signing/fingerprint gate.
5. Produce the parity APK.
6. Perform real Pixel 9 Pro XL A/B acceptance before closing R08.

## Pixel acceptance requirements for R08

- App opens normally; no splash hang.
- Picture and expanded brightness remain equivalent to accepted R07 behavior.
- Resident shader controls visibly update immediately.
- Repeated normal parameter changes do not rewrite the resident shader or increment shader swap count.
- Final rendered parameter equals final slider value.
- HDR playback remains protected.
- Record latency/timing/drop/thermal telemetry.
- No backend error.

## Visual tuning history that must survive future chats

Baseline direction:

```ini
vo=gpu
gpu-api=vulkan
hwdec=mediacodec-copy
fbo-format=rgba16f
scale=ewa_lanczossharp
cscale=ewa_lanczos
dscale=ewa_lanczos
correct-downscaling=yes
sigmoid-upscaling=yes
SDR-intensity=4.16
target-peak=auto
target-colorspace-hint=no
inverse-tone-mapping=no
gamut-mapping-mode=perceptual
```

User visual preference:

- V3 looked more natural than later V4+ experiments.
- A simple saturation increase can make the image feel processed.
- Desired end state: expanded HDR-like brightness, deep blacks, preserved highlight detail, increased color volume, natural skin, no washed-out look.
- Planned later Pixel Adaptive-style mode should be luminance-preserving, hue-aware, gamut-boundary-aware, protect neutrals/skin, and use graceful gamut compression rather than a fixed saturation boost.

## Future Shader Lab controller requirements

- One-touch bypass comparison.
- Hold-to-preview original.
- Preset morphing.
- Toggleable gamut-clipping and luma-clipping indicators.
- Control groups with jump navigation.
- OSD confirmation for destructive actions.
- Long-press acceleration for menu navigation only, never parameter adjustment.
- Long press must stop immediately on finger/key release.
- Android TV remote / D-pad support.

## Chat continuity policy

Future meaningful development checkpoints should be appended as source-controlled records under `docs/chat-logs/` so a new ChatGPT thread can recover the exact working state directly from the repository.

Do not place credentials, signing material, tokens, passwords, or other secrets in these logs.
