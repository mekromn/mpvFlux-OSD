# Shader Lab Refactor Progress

This file is the execution/status companion to `SHADER_LAB_REFACTOR_ROADMAP.md`.
It records the latest execution state when a detailed roadmap entry has not yet been rewritten.

## Current execution state

- `CURRENT_STEP = R04`
- `R01_STATUS = DONE`
- `R02_STATUS = DONE`
- `R03_STATUS = DONE`
- `R04_STATUS = BLOCKED`
- R04 implementation and automated/CI validation are complete.
- The **only remaining R04 acceptance gate** is real-device file inspection after installing the verified R04 Chrovelo Debug arm64 APK on the Pixel 9 Pro XL.
- Do not advance to R05 until the device confirms the engine files are populated in `/storage/emulated/0/mpv` and user-owned `presets/` / `state/` remain non-destructive.

## Verified repository baseline at R04 execution

- Working repository: `mekromn/mpvFlux-OSD`
- Refactor branch: `agent/upstream-refactor`
- Draft PR: `#1`
- Fork master: `83e2b2f64c48abbdc1125cff626cfcbae230bfde`
- Immediate upstream: `Muhammedahmed18/mpvFlux`
- Immediate upstream remains `f2ed015356a20bb7021e850acc599274a5f91450` (`improved`, 2026-05-22).
- No newer master/upstream source required integration before R04 implementation.

## Infrastructure amendment — stable Chrovelo package identity and signing

Stable application IDs:

- Regular: `io.github.mekromn.chrovelo`
- Debug: `io.github.mekromn.chrovelo.debug`
- Internal Kotlin/Android namespace remains `app.marlboroadvance.mpvex`.

Persistent signing is supplied only through GitHub Actions repository secrets. No reusable private signing key is committed to Git.

Verified signer certificate SHA-256:

`b582b2f37a1bfbf1089405941b20b184c104f35a0ba38068f8ffde74fd3965a2`

Relevant infrastructure commits:

- `38593bfa61b8c03b488904dc42a0b5510a305a3d` — stable package IDs and persistent signing support.
- `7da45964579bf52ccdc331532c127295a01a6768` — updateable signed Debug workflow and identity/version/certificate checks.
- `a8be81d11a6d8876ebc248a6ebd28abe34e0de43` — robust current-`apksigner` certificate parsing and faster analyzer lookup.

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
- Required visible directories: `config/`, `scripts/`, `shaders/`, `shaders/runtime/`, `presets/`, `state/`, `logs/`.
- Isolated engine metadata under `.mpvlab/engine/`.
- Explicit engine-owned vs user-owned roots.
- Android 11+ All Files Access handling without private-storage fallback.
- Non-destructive initialization and read/write probe.
- Unit tests for canonical paths, permission behavior, ownership separation, and preset/state preservation.

### R03 validation

- Automated tests: **PASS**
- Normal CI: **PASS**
- Signed APK identity/version/certificate validation: **PASS**
- Pixel 9 Pro XL / Android 16 device smoke test: **PASS** — the app created all seven expected visible directories under `/storage/emulated/0/mpv`.

**R03 COMPLETE.**

## R04 — Versioned Shader Lab installer/updater

**Status:** `BLOCKED` — implementation/automated validation complete; device file inspection remains.

### Implemented

- Added `ShaderLabEngineInstaller` and explicit installer state/outcome model.
- Replaced legacy Base64/ZIP runtime reconstruction with direct reads from the normalized `app/src/main/assets/mpvlab/source/` tree.
- Parses the bundled `engine-manifest.json` and verifies canonical workspace, engine version, schema version, file paths, byte counts, and SHA-256 values.
- Verifies **every bundled asset before any engine payload write**.
- Installs runtime engine files only into engine-owned destinations:
  - `config/*` → `/storage/emulated/0/mpv/config/`
  - `scripts/*` → `/storage/emulated/0/mpv/scripts/`
  - `shaders/*` → `/storage/emulated/0/mpv/shaders/`
  - normalized `docs/*` and `misc/*` reference material → hidden `.mpvlab/engine/reference/`
- The normalized legacy `misc/state/README.txt` is intentionally treated as reference material and **never copied into user-owned `/state/`**.
- Copies the authoritative installed manifest to `.mpvlab/engine/engine-manifest.json`.
- Stores installed engine/version/manifest metadata and the exact installer-owned file list in `.mpvlab/engine/version.json`.
- Compares content hashes on every initialization; identical files are left untouched.
- Missing/corrupt managed files are repaired from the verified bundled source.
- Stale files are removed only when the previous installer marker proves they were installer-managed; untracked user/helper files are retained.
- Uses same-directory temp files and atomic replace where supported, with rollback-capable fallback replacement.
- Writes the version marker last, after installed files pass post-write hash verification.
- Adds explicit `ShaderLabEngineMigration` hooks; schema upgrades are rejected unless a matching migration is registered.
- Schema downgrade is rejected.
- App startup now runs the installer asynchronously after canonical-workspace validation; there is no app-private fallback.
- Appends install/update/repair diagnostics to `/storage/emulated/0/mpv/logs/shaderlab-installer.log`.
- Registered installer through Koin.

### R04 commits

- `161e9ef0eb920108c82f515ca28cd2b0f28e6eb5` — versioned installer core.
- `a528d6aafebc2fb9702e725e3db38c8a6d56c952` — installer Koin registration.
- `0d1117c9ae4ddb8a34fc43be520ed83eeec4f1a3` — app-start engine reconciliation.
- `917ea95d413f6bad4969854730095ceda82fe892` — install/repair/preservation/migration tests.
- `6f19b7e8e399cedf1baa8dae6bfd011eab4239bb` — installer portability and JVM-test hardening; validated R04 code head.

### Automated R04 validation

Expanded unit tests cover:

- fresh engine installation: **PASS**
- unchanged second initialization/no rewrite: **PASS**
- corrupt managed-file repair: **PASS**
- version upgrade and stale installer-owned file removal: **PASS**
- preservation of untracked engine-root files: **PASS**
- preservation of user `presets/` and `state/`: **PASS**
- rejection of manifest targets under user-owned state/preset roots: **PASS**
- bundled hash mismatch fails before payload write: **PASS**
- schema transition requires explicit migration hook: **PASS**

Dedicated phone-test workflow:

- Workflow: `Refactor Dev APK`
- Run: `#69`
- Run ID: `32409643735`
- Job ID: `96556901126`
- Branch code head: `6f19b7e8e399cedf1baa8dae6bfd011eab4239bb`
- PR merge-test SHA: `9fadbb1e21d0a0c6a34c66382f8dc4cb6849b697`
- Unit-test Gradle result: **PASS** — `BUILD SUCCESSFUL`, 33 actionable tasks.
- Signed Debug build: **PASS** — `BUILD SUCCESSFUL`.
- Package/version/signature/certificate verification: **PASS**.
- arm64 versionCode: `1787254652` (greater than the previous installed R03 arm64 versionCode `1787246702`, so it is update-compatible).
- universal versionCode: `1787254650`.
- signer certificate remains `b582b2f37a1bfbf1089405941b20b184c104f35a0ba38068f8ffde74fd3965a2`.
- arm64 artifact ID: `9421772862`, artifact digest `sha256:e64d18d370502835ea5a1967e020d958519dbf144fc0ab5238a8db5ab066a49c`.
- universal artifact ID: `9421773792`, artifact digest `sha256:7deeb5bacc50c3f048546c79775be78f0a7fbf16ef211c52323937fd479cfa62`.

Normal CI/CD validation:

- `CI/CD Build` run `#27`
- Run ID: `32409643876`
- Job ID: `96556900722`
- Result: **PASS**.
- Gradle build: **PASS**.
- arm64, armeabi-v7a, universal, x86, and x86_64 artifact uploads: **PASS**.

### Remaining R04 real-device acceptance gate

Install the verified R04 arm64 APK **over the existing Chrovelo Debug installation without uninstalling it**, then launch/relaunch the app with All Files Access still granted.

Device inspection should confirm at least:

- `/storage/emulated/0/mpv/config/input.conf`
- `/storage/emulated/0/mpv/config/mpv.conf`
- `/storage/emulated/0/mpv/scripts/mpvflux-touch-diagnostic.lua`
- `/storage/emulated/0/mpv/scripts/pixel9-shader-lab.lua`
- `/storage/emulated/0/mpv/shaders/pixel9-perceptual-expansion-known-good-v3.1.glsl`
- `/storage/emulated/0/mpv/shaders/pixel9-perceptual-expansion-runtime-a.glsl`
- `/storage/emulated/0/mpv/shaders/pixel9-perceptual-expansion-runtime-b.glsl`
- `/storage/emulated/0/mpv/shaders/pixel9-perceptual-expansion-template.glsl.txt`
- hidden `.mpvlab/engine/version.json`
- hidden `.mpvlab/engine/engine-manifest.json`
- `/storage/emulated/0/mpv/logs/shaderlab-installer.log`

`shaders/runtime/` may remain empty at R04; later shader-generation/live-apply work owns runtime generation. `presets/` and `state/` must remain user-owned and must not receive R04 reference files.

**R04 is not complete yet. `CURRENT_STEP` remains R04. Do not start R05 until this device file inspection passes.**
