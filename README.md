# Chrovelo

**Chrovelo** is an Android video player built on **mpv**, focused on high-quality playback, responsive native controls, and an experimental real-time **Shader Lab** for advanced image tuning.

This project began as an mpvEx/mpvFlux fork and is being progressively refactored into a cleaner native Android architecture while preserving the proven mpv playback path.

> **Development status:** active. The core Shader Lab backend and Android↔mpv state bridge are working; the resident GPU-parameter pipeline and redesigned native workstation UI are still being built.

---

## Highlights

### High-quality mpv playback

- mpv-based Android playback engine.
- Vulkan GPU rendering support.
- Hardware video decoding through Android MediaCodec.
- High-precision `rgba16f` intermediate rendering for the Pixel tuning path.
- High-quality scaling with EWA Lanczos filters.
- Debanding and dithering support.
- Local-file playback and media-library browsing.
- Modern Material 3 Android interface.

### Responsive player experience

- Native Android player controls.
- Touch-oriented interaction.
- Android TV / D-pad support remains a design requirement for the Shader Lab workstation.
- Improved MediaStore scanning.
- Asynchronous library work and caching to reduce UI stalls while scanning large local libraries.
- Reduced unnecessary polling in favor of event-driven state where practical.

### Subtitle support

- Local and online subtitle workflows inherited from the upstream player.
- Hash-assisted subtitle matching using the beginning and end of a video file to improve automatic matching accuracy.

---

# Shader Lab

Shader Lab is Chrovelo's experimental real-time video-processing workstation.

Its goal is not simply to expose generic brightness/saturation sliders. It provides a typed control system around a custom perceptual SDR-expansion shader designed for high-quality live tuning while video is playing.

## Current Shader Lab foundation

The refactor currently includes:

- **53 typed value controls** with authoritative ranges, defaults, precision, and adjustment steps.
- **10 semantic actions** for comparison, presets, reset/revert, state operations, and diagnostics.
- Fine / normal / coarse adjustment modes.
- Ordered-control relationships so dependent ranges remain valid.
- User and built-in preset models.
- Preset morphing model.
- One-touch bypass semantics.
- Original-preview start/end semantics for future press-and-hold comparison.
- Gamut and luminance clipping diagnostic modes.
- Event-driven Android ↔ mpv/Lua state synchronization.
- Typed observable backend state for future native UI binding.
- Explicit SDR / HDR-PQ / HDR-HLG source classification.
- Canonical Shader Lab workspace under `/storage/emulated/0/mpv`.

The Android/mpv bridge has been validated on a **Pixel 9 Pro XL running Android 16**, including synchronization of the complete 53-control state snapshot.

---

## Pixel 9 Pro XL perceptual SDR expansion

The current experimental Pixel profile is built around a rendering combination that has been validated on the Pixel 9 Pro XL:

```text
vo=gpu
gpu-api=vulkan
fbo-format=rgba16f
hwdec=mediacodec-copy
SDR-intensity=4.16
target-peak=auto
target-colorspace-hint=no
inverse-tone-mapping=no
```

The profile is intentionally **SDR-only**. True PQ and HLG HDR sources are excluded from the SDR expansion path.

The shader operates in mpv's linear-light stage and uses perceptual color math rather than a simple global saturation multiplier.

### Processing goals

- Preserve true black and white.
- Lift upper-mid/highlight luminance without a hard clipping knee.
- Preserve a reference-gray pivot.
- Expand color volume rather than blindly increasing RGB saturation.
- Measure and manipulate chroma in **Oklab**.
- Protect near-neutral material.
- Protect skin-like hues from excessive chroma expansion.
- Preserve hue while increasing chroma.
- Gamut-limit before final clipping.
- Provide visual gamut/luminance clipping diagnostics.

The project also plans a Pixel **Adaptive-display-style** color-volume mode based on luminance-preserving, hue-aware, gamut-boundary-aware chroma expansion rather than a fixed saturation boost.

---

# Resident GPU parameter architecture

Shader Lab is currently moving away from this legacy live-tuning path:

```text
control change
    ↓
generate new GLSL source
    ↓
write runtime shader file
    ↓
remove/add shader
    ↓
compile/cache
    ↓
display
```

The new R08 architecture targets:

```text
native control
    ↓
validated typed value
    ↓
mpv glsl-shader-opts
    ↓
already-loaded vo=gpu shader parameter
    ↓
next rendered frame
```

The shader remains GPU code. The improvement is that normal slider movement should update **resident shader parameters** instead of regenerating and swapping shader files.

This is intended to provide much lower tuning latency, eliminate routine shader-file I/O, reduce compilation/pipeline churn, and make the eventual native sliders feel continuously live while they are being dragged.

## Why Chrovelo needs a custom libmpv build

The libmpv currently bundled with the project is based on mpv commit:

```text
d54bad5636924ab3f39cb6e397b94b6aa8a7c433
```

That build predates upstream `vo=gpu` tunable `//!PARAM` support, which was added later in:

```text
0d655fe66590009e1d77a17581257d677286531a
```

Upstream `vo=gpu` also currently defines a fixed 16-parameter shader table, while Shader Lab needs substantially more parameters.

R08 therefore uses a deliberately narrow native change set:

1. Preserve the proven `vo=gpu` renderer behavior where practical.
2. Add/backport upstream `vo=gpu` tunable-parameter support.
3. Raise the shader parameter capacity from 16 to 64.
4. Keep the existing Android `is.xyz.mpv.MPVLib` API compatible.
5. Validate the resulting build again on the Pixel before replacing the proven playback baseline.

---

# Pixel-oriented native optimization

Because R08 now includes a custom Android libmpv build, Chrovelo can also remove avoidable platform overhead while preserving image quality.

Current optimization policy includes:

- true **16 KB ELF/page alignment** for modern Android compatibility;
- arm64-first Pixel development artifacts;
- release optimization with measured LTO/ThinLTO evaluation;
- preservation of ARM64 runtime SIMD dispatch rather than unsafe global CPU assumptions;
- resident Vulkan shader/pipeline use instead of repeated shader rebuilding;
- zero file-I/O and minimal-allocation goals for the live parameter path;
- frame-cadence-aware parameter delivery;
- Mali GPU profiling for instruction count, register pressure, branching, and the Oklab gamut-search loop;
- sustained playback / thermal / dropped-frame measurements rather than relying only on short synthetic benchmarks.

Image fidelity takes priority over benchmark numbers. Chrovelo will not trade away bit depth, color correctness, or the proven Pixel brightness behavior for a small speed increase.

For that reason, `mediacodec-copy` remains the quality-safe hardware-decoding baseline while the new resident shader architecture is validated. Any future direct hardware-buffer/Vulkan decode path must prove identical color, bit depth, HDR/SDR behavior, and Shader Lab compatibility before replacing it.

---

# Shader Lab workspace

Shader Lab uses a single user-visible workspace:

```text
/storage/emulated/0/mpv/
```

Managed and user-owned data are separated into subdirectories such as:

```text
/storage/emulated/0/mpv/config/
/storage/emulated/0/mpv/scripts/
/storage/emulated/0/mpv/shaders/
/storage/emulated/0/mpv/shaders/runtime/
/storage/emulated/0/mpv/presets/
/storage/emulated/0/mpv/state/
/storage/emulated/0/mpv/logs/
/storage/emulated/0/mpv/.mpvlab/engine/
```

The installer verifies managed engine assets and can repair mismatched managed files without destroying user presets or saved state.

---

# Refactor roadmap

The Shader Lab rewrite is deliberately staged so playback remains testable after each architectural change.

| Step | Status | Purpose |
| --- | --- | --- |
| R01 | ✅ Done | Build/release harness |
| R02 | ✅ Done | Readable Shader Lab source |
| R03 | ✅ Done | Canonical `/storage/emulated/0/mpv` workspace |
| R04 | ✅ Done | Versioned engine installer/updater |
| R05 | ✅ Done | Typed 53-control catalog |
| R06 | ✅ Done | Semantic Shader Lab command API |
| R07 | ✅ Done | Observable Android ↔ mpv bridge |
| R08 | 🚧 In progress | Resident `vo=gpu` shader + native parameter transport |
| R09 | Planned | ViewModel and authoritative native UI state |
| R10 | Planned | Full native Shader Lab workstation redesign |
| R11 | Planned | Advanced touch/hold/preview gesture behavior |
| R12 | Planned | Android TV / D-pad navigation and focus behavior |

Later roadmap stages cover additional workstation functionality, cleanup, testing, release hardening, and branding integration.

The authoritative details live in:

- `docs/SHADER_LAB_REFACTOR_ROADMAP.md`
- `docs/SHADER_LAB_REFACTOR_PROGRESS.md`
- `docs/R08_RESIDENT_GPU_PARAMETER_ARCHITECTURE.md`

---

# Planned native Shader Lab UI

The current Lua workstation remains temporarily available for compatibility and testing. It is **not** the final interface.

The planned native workstation includes:

- responsive portrait and landscape layouts;
- continuously live sliders;
- current numeric values;
- minus / reset / plus controls;
- fine / normal / coarse adjustment modes;
- group jump navigation;
- one-touch bypass comparison;
- hold-to-preview original;
- preset save/load and morphing;
- gamut and luminance clipping overlays;
- visible backend/error status;
- destructive-action confirmations;
- touch-first interaction;
- Android TV / D-pad operation;
- long-press acceleration for menu navigation only, stopping immediately on release/cancel/focus loss.

---

# Building

Chrovelo is an Android Gradle project and currently targets Java 17 / Android SDK 36.

Typical local validation uses:

```bash
./gradlew :app:testStandardDebugUnitTest
./gradlew :app:assembleStandardDebug
```

The repository also contains GitHub Actions workflows used during the refactor to build and verify development APKs.

The active Shader Lab refactor is developed on:

```text
agent/upstream-refactor
```

The long-lived draft pull request is intentionally kept separate from `master` until the roadmap's integration and device-validation stages are complete.

---

# Project lineage

Chrovelo is derived from work in the mpv Android ecosystem, including:

- **mpv**
- **mpv-android**
- **mpvEx**
- **mpvFlux**

The project preserves the strengths of the upstream player while experimenting with a more specialized real-time video-processing workstation and Pixel-focused rendering path.

---

# Experimental status

Shader Lab and the Pixel SDR-expansion profile are experimental. They intentionally exercise rendering behavior beyond a conventional stock video-player configuration.

Device-specific behavior can vary across Android versions, GPU drivers, display modes, and mpv revisions. Renderer changes are therefore validated against real-device playback before they are considered complete.

---

# License

This repository is licensed under the **Apache License 2.0**. See [`LICENSE`](LICENSE) for the full terms.
