# Shader Lab Refactor Progress

This file is the execution/status companion to `SHADER_LAB_REFACTOR_ROADMAP.md`.
It records the latest execution state when a detailed roadmap entry has not yet been rewritten.

## Current execution state

- `CURRENT_STEP = R04`
- `R01_STATUS = DONE`
- `R02_STATUS = DONE`
- `R03_STATUS = DONE`
- `R04_STATUS = TODO`
- R03 is complete. Do not redo it unless the canonical workspace regresses or the user explicitly requests it.
- The next `Continue roadmap` executes **R04 only**: Versioned Shader Lab installer/updater.

## Verified repository baseline at R03 completion

- Working repository: `mekromn/mpvFlux-OSD`
- Refactor branch: `agent/upstream-refactor`
- Draft PR: `#1`
- Fork master at validation: `83e2b2f64c48abbdc1125cff626cfcbae230bfde`
- Immediate upstream: `Muhammedahmed18/mpvFlux`
- Immediate upstream remains `f2ed015356a20bb7021e850acc599274a5f91450` (`improved`, 2026-05-22).
- No newer immediate-upstream source required integration before R03 completion.

## Infrastructure amendment — stable Chrovelo package identity and signing

Stable application IDs were established before device testing so development APKs can update in place:

- Regular: `io.github.mekromn.chrovelo`
- Debug: `io.github.mekromn.chrovelo.debug`
- Internal Kotlin/Android namespace remains `app.marlboroadvance.mpvex`.

Persistent signing is supplied only through GitHub Actions repository secrets:

- `SIGNING_KEYSTORE`
- `SIGNING_KEY_ALIAS`
- `SIGNING_STORE_PASSWORD`
- `KEY_PASSWORD`

No reusable private signing key is committed to Git.

Verified signer certificate SHA-256:

`b582b2f37a1bfbf1089405941b20b184c104f35a0ba38068f8ffde74fd3965a2`

Relevant infrastructure commits:

- `38593bfa61b8c03b488904dc42a0b5510a305a3d` — stable package IDs and persistent signing support.
- `7da45964579bf52ccdc331532c127295a01a6768` — updateable signed Debug workflow and identity/version/certificate checks.
- `a8be81d11a6d8876ebc248a6ebd28abe34e0de43` — robust current-`apksigner` certificate parsing and faster analyzer lookup.

Verified signing/build workflow:

- Workflow: `Refactor Dev APK`
- Run: `#55`
- Run ID: `32397467734`
- Result: **PASS**
- R03 workspace unit tests: **PASS**
- Persistent signing preparation: **PASS**
- `:app:assembleStandardDebug`: **PASS**
- Debug package ID `io.github.mekromn.chrovelo.debug`: **PASS**
- arm64 versionCode: `1787246702`
- universal versionCode: `1787246700`
- APK Signature Scheme v2: **PASS**
- arm64/universal signer match: **PASS**
- arm64 artifact ID: `9417336415`
- universal artifact ID: `9417337270`

Normal CI also passed on the validated R03 code head:

- `CI/CD Build` run `#20`
- Run ID: `32397467651`
- Result: **PASS**

## R01 — Build/release harness for phone-only development

**Status:** `DONE`

Original successful validation:

- `Refactor Dev APK` run #11 / run ID `32385673806`
- arm64 artifact ID `9413012997`
- universal artifact ID `9413014726`

R01 remains complete. Its original disposable runner-debug signing behavior was superseded by the stable Chrovelo signing infrastructure above.

## R02 — Readable Shader Lab source tree and migration inventory

**Status:** `DONE`

Key completed work:

- Normalized readable legacy v6.1.1 Lua/GLSL/config source under `app/src/main/assets/mpvlab/source/`.
- Added `engine-manifest.json` and `tools/verify_mpvlab_manifest.py`.
- Preserved canonical `/storage/emulated/0/mpv` references.
- Legacy branch remains read-only.

Relevant commits:

- `62b2b721c14d9e73dfc79176ba66e56ab5b8f491`
- `ff9e808770120075838379ff16f07bdb31865c11`
- `516728c3e78d13f203246ae86a634c86d01639ed`

Validation:

- Legacy payload SHA-256 verification: **PASS**
- Manifest verification: **PASS**
- `Refactor Dev APK` run #23 (`32387282949`): **PASS**

## R03 — Canonical `/storage/emulated/0/mpv` workspace manager

**Status:** `DONE`

Implemented:

- Canonical root `/storage/emulated/0/mpv`.
- Required visible directories:
  - `config/`
  - `scripts/`
  - `shaders/`
  - `shaders/runtime/`
  - `presets/`
  - `state/`
  - `logs/`
- Isolated engine metadata under `.mpvlab/engine/`.
- Explicit engine-owned vs user-owned roots.
- Observable access states: unchecked, available, permission required, unavailable, failure.
- Android 11+ All Files Access handling without private-storage fallback.
- Non-destructive initialization.
- Self-deleting read/write probe under `.mpvlab/engine`.
- App-start asynchronous workspace initialization.
- Unit tests for canonical paths, creation, permission denial, ownership separation, and preservation of existing `presets/` / `state/` content.

Relevant R03 commits:

- `0ab0c2b4ba026829666ad834eb10aad588ebc55f`
- `26c5a5139368820e8ffc8ee0d0f590986d7a3638`
- `24f8977248cb197ad6444ae40c5edc002a9bf8cf`
- `dc0f0d5a4b1d1e04b95d00826f8489c7a75b2c21`
- `00eb41a3245324c2b079c612c367970e36727c4d`
- `c8289fe74a7446ddf2a18253e54c6b3609081b59`
- `9400a6007f469dfb8d296d0d34e17919c73a48df`
- `e60f745a0b153935e09169fe14225865fb92f73e`

### R03 validation

Automated validation:

- Canonical path/unit tests: **PASS**
- Existing preset/state preservation tests: **PASS**
- Permission-required non-destructive behavior tests: **PASS**
- Normal CI build: **PASS**
- Persistent signing/build/package/version/certificate verification: **PASS**

Real-device validation on the Pixel 9 Pro XL / Android 16:

- Verified Chrovelo Debug APK installed and ran on the target device.
- After initialization, the app created the seven expected visible directories inside `/storage/emulated/0/mpv`.
- The directories were empty, which is **expected for R03**: R03 owns workspace creation/access only; R04 owns engine-file installation/population.
- Successful creation of the canonical directories on-device confirms write access to the required canonical path.
- Non-destructive handling of user-owned `presets/` and `state/` is additionally covered by the passing unit tests.

### R03 acceptance result

- Canonical workspace create/read/write path on Pixel with permission granted: **PASS**
- User-owned preset/state preservation logic: **PASS**
- Explicit non-destructive failure/permission behavior: **PASS**
- Device smoke test: **PASS**

**R03 COMPLETE.**

## Next step

### R04 — Versioned Shader Lab installer/updater

**Status:** `TODO`

R04 will safely populate the currently empty canonical workspace with the readable bundled engine assets, using version/hash checks, atomic writes, repair behavior, migrations, and strict preservation of user-owned `presets/` and `state/`.

Do not implement R05 or later work until R04 is completed and validated.
