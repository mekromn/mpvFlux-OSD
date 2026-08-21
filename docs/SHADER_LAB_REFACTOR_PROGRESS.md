# Shader Lab Refactor Progress

This file is the execution/status companion to `SHADER_LAB_REFACTOR_ROADMAP.md` and records the latest execution state.

## Current execution state

- `CURRENT_STEP = R07`
- `R01_STATUS = DONE`
- `R02_STATUS = DONE`
- `R03_STATUS = DONE`
- `R04_STATUS = DONE`
- `R05_STATUS = DONE`
- `R06_STATUS = DONE`
- `R07_STATUS = TODO`
- R06 is complete. Do not redo it unless semantic-command behavior, validation, or transport neutrality regresses or the user explicitly requests it.
- The next `Continue roadmap` executes **R07 only**: Refactor MPV bridge and observable state transport.

## Verified repository baseline at R06 completion

- Working repository: `mekromn/mpvFlux-OSD`
- Refactor branch: `agent/upstream-refactor`
- Draft PR: `#1`
- Fork master: `83e2b2f64c48abbdc1125cff626cfcbae230bfde`
- Immediate upstream: `Muhammedahmed18/mpvFlux`
- Immediate upstream remains `f2ed015356a20bb7021e850acc599274a5f91450` (`improved`, 2026-05-22).
- No newer master/upstream source required integration before R06.

## Stable Chrovelo application identity

- Regular: `io.github.mekromn.chrovelo`
- Debug: `io.github.mekromn.chrovelo.debug`
- Internal namespace: `app.marlboroadvance.mpvex`
- Persistent signer SHA-256: `b582b2f37a1bfbf1089405941b20b184c104f35a0ba38068f8ffde74fd3965a2`
- Reusable private signing material remains outside Git.

## Completed steps

### R01 — Build/release harness

**Status:** `DONE`

- Phone-triggerable `Refactor Dev APK` workflow.
- arm64/universal artifacts with APK verification.
- Original validation: run #11 / `32385673806` — **PASS**.

### R02 — Readable Shader Lab source

**Status:** `DONE`

- Normalized readable v6.1.1 Lua/GLSL/config source under `app/src/main/assets/mpvlab/source/`.
- Engine manifest/integrity tooling and migration inventory.
- Validation run #23 / `32387282949` — **PASS**.

### R03 — Canonical workspace manager

**Status:** `DONE`

- Canonical `/storage/emulated/0/mpv` workspace.
- Explicit All Files Access state; no private fallback.
- Engine/user ownership separation and non-destructive read/write initialization.
- Pixel 9 Pro XL / Android 16 smoke: **PASS**.

### R04 — Versioned installer/updater

**Status:** `DONE`

- Manifest/hash-verified direct asset install.
- Atomic replacement/repair/stale-managed cleanup/migration hooks.
- Preserves user `presets/` and `state/`.
- Dedicated run #69 / `32409643735`: **PASS**.
- Normal CI #27 / `32409643876`: **PASS**.
- Pixel in-place update + file inspection: **PASS**.

### R05 — Typed authoritative control catalog

**Status:** `DONE`

- Added authoritative typed catalog independent of UI/transport.
- Ported all 53 value-bearing legacy controls and 10 action-only workstation items.
- Encoded 12 ordered-pair constraints and 5 virtual-master dependencies.
- Added typed user/built-in preset IDs and parity inventory.
- Dedicated run #79 / `32416421098`: **PASS**.
- Normal CI #32 / `32416421058`: **PASS**.

## R06 — Semantic Shader Lab command API

**Status:** `DONE`

### Implemented

- Added `app/src/main/java/app/marlboroadvance/mpvex/repository/shaderlab/command/ShaderLabCommandApi.kt`.
- Added a single input-neutral `ShaderLabCommand` surface for future touch, TV/D-pad, presets, ViewModel, and tests.
- Implemented semantic commands for:
  - value set/adjust;
  - group/control selection;
  - bypass;
  - original-preview start/end plus legacy toggle fallback;
  - video-start revert and reset-all;
  - user preset save/load/clear;
  - built-in preset load;
  - preset morph;
  - gamut/luma diagnostic view;
  - complete state save/load.
- Added `ShaderLabAdjustDirection` and typed `ShaderLabDiagnosticView` (`OFF`, `GAMUT`, `LUMA`, `BOTH`).
- Added `ShaderLabCommandBackend` as the runtime boundary. It contains no MPV command names, Lua `script-message` strings, Compose types, pointer events, key events, or Android UI types.
- Added typed command effects and results: applied, rejected, and backend failure.
- `SetValue` and `Adjust` reuse the R05 catalog for clamping, exact fine/normal/coarse steps, and ordered-pair normalization.
- Ordered-pair normalization writes only the addressed control and directly related pair member(s); unrelated controls are not silently rewritten.
- Selection commands are local semantic effects and do not touch the runtime backend.
- Preview comparison is explicitly modeled as start/end, enabling true later press lifecycle handling rather than toggle-only behavior.
- Preset operations use bounded typed preset IDs instead of raw slot integers.
- Morph accepts typed built-in/user endpoints, rejects `VideoStart`, rejects non-finite amounts, and clamps the interpolation amount to `0.0..1.0`.
- Destructive-confirmation policy reuses the R05 action metadata instead of creating another canonical destructive-action table.
- Backend exceptions surface as typed `ShaderLabCommandResult.Failed` results.

### R06 commits

- `9528eb33b4afadf3b59ff514439a7e78aad2e0ec` — semantic command API and transport-neutral backend boundary.
- `d693d238fa6e8c6d5e71d08f248d2d73b4ce2ffd` — fake-backend command tests; validated R06 code head.
- `ea5c94f3a7e02adc23442df4bd00ffa9e256bdfb` — detailed roadmap completion record and pointer advance.

### R06 validation

`ShaderLabCommandApiTest` verifies:

- clamping through the R05 catalog: **PASS**
- ordered-pair normalization: **PASS**
- exact fine/normal/coarse adjustment steps: **PASS**
- group/control selection without runtime mutation: **PASS**
- preview start/end/fallback semantics: **PASS**
- typed preset/system action routing: **PASS**
- typed morph validation and amount clamping: **PASS**
- typed diagnostic view mapping: **PASS**
- destructive-confirmation metadata reuse: **PASS**
- NaN/infinite value rejection before backend mutation: **PASS**
- backend exception → typed failure propagation: **PASS**

Source audit:

- Compose dependency: **NONE**.
- Direct MPV/Lua transport dependency: **NONE**.
- Android UI/key/pointer dependency: **NONE**.

Dedicated phone-test workflow:

- `Refactor Dev APK` run `#87`
- Run ID: `32432134649`
- Job ID: `96625836155`
- Validated branch code head: `d693d238fa6e8c6d5e71d08f248d2d73b4ce2ffd`
- PR merge-test SHA: `766c7793107d65fb0407524e015fe5ccf3e2b4e8`
- Unit tests: **PASS** — Gradle `BUILD SUCCESSFUL`.
- Signed Debug build: **PASS**.
- Package/version/signing certificate verification: **PASS**.
- arm64 versionCode: `1787271522`.
- universal versionCode: `1787271520`.
- signer certificate remains `b582b2f37a1bfbf1089405941b20b184c104f35a0ba38068f8ffde74fd3965a2`.
- arm64 artifact ID: `9429556059`, digest `sha256:55ad880f89d8b6b494ab2c0c480fdeecd6fd9964cbbb32154aed268b93bbb16b`.
- universal artifact ID: `9429556615`, digest `sha256:f37d38cb58dd455e16b5c2657a7de014fd70d7fa1dcd88d7e71a8e9649de0a2c`.

Normal CI/CD:

- `CI/CD Build` run `#36`
- Run ID: `32432134575`
- Job ID: `96625836756`
- Gradle build: **PASS**.
- arm64/armeabi-v7a/universal/x86/x86_64 uploads: **PASS**.

### R06 acceptance result

- Semantic API has no Compose dependency: **PASS**.
- Commands are unit-tested with a fake backend: **PASS**.
- Branch builds and signed phone-test APK verifies: **PASS**.
- Device test required by R06: **NO** — R06 validation is unit tests; no concrete MPV bridge/device synchronization was introduced.

**R06 COMPLETE.**

## Next step

### R07 — Refactor MPV bridge and observable state transport

**Status:** `TODO`

R07 will implement the concrete MPV/Lua backend for the R06 semantic API and observable typed state transport, replacing UI-oriented polling architecture where possible. Its roadmap validation includes fake-backend tests plus a real-device synchronization smoke test.

Do not implement R08 or later work until R07 is completed and validated.
