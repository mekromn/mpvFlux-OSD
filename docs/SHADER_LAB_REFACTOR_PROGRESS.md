# Shader Lab Refactor Progress

This file is the execution/status companion to `SHADER_LAB_REFACTOR_ROADMAP.md` and records the latest execution state.

## Current execution state

- `CURRENT_STEP = R06`
- `R01_STATUS = DONE`
- `R02_STATUS = DONE`
- `R03_STATUS = DONE`
- `R04_STATUS = DONE`
- `R05_STATUS = DONE`
- `R06_STATUS = TODO`
- R05 is complete. Do not redo it unless catalog parity/metadata regresses or the user explicitly requests it.
- The next `Continue roadmap` executes **R06 only**: Build semantic Shader Lab command API.

## Verified repository baseline at R05 completion

- Working repository: `mekromn/mpvFlux-OSD`
- Refactor branch: `agent/upstream-refactor`
- Draft PR: `#1`
- Fork master: `83e2b2f64c48abbdc1125cff626cfcbae230bfde`
- Immediate upstream: `Muhammedahmed18/mpvFlux`
- Immediate upstream remains `f2ed015356a20bb7021e850acc599274a5f91450` (`improved`, 2026-05-22).
- No newer master/upstream source required integration before R05.

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

## R05 — Typed authoritative control catalog

**Status:** `DONE`

### Implemented

- Added `app/src/main/java/app/marlboroadvance/mpvex/repository/shaderlab/catalog/ShaderLabControlCatalog.kt`.
- Added typed domain IDs/models:
  - `ShaderLabControlId`
  - `ShaderLabGroup`
  - `ShaderLabControlKind`
  - `ShaderLabControlSpec`
  - `ShaderLabStepMode`
  - `ShaderLabPresetId`
  - `ShaderLabActionId` / `ShaderLabActionSpec`
  - `ShaderLabControlRelationship`
  - `ShaderLabBuiltInPreset`
- Added authoritative `ShaderLabControlCatalog` with generic clamp/step/format/normalization/effective-value helpers.
- Ported all **53** value-bearing legacy Android controls with their exact legacy keys and canonical metadata.
- Represented all **10** Lua action-only workstation items as typed action metadata; execution is intentionally deferred to R06.
- Encoded all **12** proven Lua ordered-pair relationships using `0.000001` minimum gap.
- Encoded all **5** proven virtual-master scaling relationships.
- Added bounded typed user/built-in preset IDs and preserved the 10 legacy built-in names.
- Added `docs/R05_CONTROL_CATALOG_INVENTORY.md` for explicit legacy parity/accounting.

### R05 commits

- `4513ab2a01ee0ddbd3808b16e6a5eb204468dd31` — typed catalog/domain model.
- `0d6bdc85609132eda66adcb6afce24579403b923` — catalog tests.
- `cf1a0a20d8525b133a4a4b01d790afdd56df1dc7` — legacy catalog parity inventory.
- `a3bbda0f54e40f5b6cd40390d35958ae10514f94` — roadmap completion update.

### R05 validation

`ShaderLabControlCatalogTest` verifies:

- exact 53-key value-control parity: **PASS**
- exact 10-key action-only parity: **PASS**
- clamping/integer behavior: **PASS**
- fine/normal/coarse steps: **PASS**
- high-precision constants/formatting: **PASS**
- all 12 ordering relationships: **PASS**
- changed-control-wins normalization: **PASS**
- all 5 virtual-master dependencies: **PASS**
- typed preset bounds/names: **PASS**
- preset eligibility metadata: **PASS**

Dedicated phone-test workflow:

- `Refactor Dev APK` run `#79`
- Run ID: `32416421098`
- Job ID: `96578517197`
- Validated branch code/doc head before completion bookkeeping: `cf1a0a20d8525b133a4a4b01d790afdd56df1dc7`
- Unit tests: **PASS**
- Signed Debug build: **PASS**
- Package/version/signing certificate verification: **PASS**
- arm64 artifact ID: `9424196537`, digest `sha256:81c0b54b1c1d6b920cdb1acb55498862ccc446e3322fb80e3c83c0f6db68152c`
- universal artifact ID: `9424197412`, digest `sha256:8d7a60f6a776fd6f3f60a71d7366a59560f4626ecfad5a809237884f5d3b98a4`

Normal CI/CD:

- `CI/CD Build` run `#32`
- Run ID: `32416421058`
- Job ID: `96578589838`
- Gradle build: **PASS**
- arm64/armeabi-v7a/universal/x86/x86_64 uploads: **PASS**

### R05 acceptance result

- Canonical metadata is typed and outside UI: **PASS**.
- Catalog is unit-tested: **PASS**.
- Legacy catalog has no unexplained omissions: **PASS**.
- Branch builds and signed phone-test APK verifies: **PASS**.
- Device test required by R05: **NO** — roadmap validation is unit tests; no R05 device-only behavior was introduced.

**R05 COMPLETE.**

## Next step

### R06 — Build semantic Shader Lab command API

**Status:** `TODO`

R06 will define the input-neutral semantic command layer used later by touch, TV/D-pad, presets, tests, and the MPV bridge. It must not depend on Compose and must be testable against a fake backend.

Do not implement R07 or later work until R06 is completed and validated.
