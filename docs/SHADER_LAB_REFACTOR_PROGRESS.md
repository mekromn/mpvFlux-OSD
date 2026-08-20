# Shader Lab Refactor Progress

This file is the execution/status companion to `SHADER_LAB_REFACTOR_ROADMAP.md`.
It records the latest execution state when a detailed roadmap entry has not yet been rewritten.

## Current execution state

- `CURRENT_STEP = R05`
- `R01_STATUS = DONE`
- `R02_STATUS = DONE`
- `R03_STATUS = DONE`
- `R04_STATUS = DONE`
- `R05_STATUS = TODO`
- R04 is complete. Do not redo it unless installer/update behavior regresses or the user explicitly requests it.
- The next `Continue roadmap` executes **R05 only**: Refactor control catalog into typed domain models.

## Verified repository baseline at R04 completion

- Working repository: `mekromn/mpvFlux-OSD`
- Refactor branch: `agent/upstream-refactor`
- Draft PR: `#1`
- Fork master: `83e2b2f64c48abbdc1125cff626cfcbae230bfde`
- Immediate upstream: `Muhammedahmed18/mpvFlux`
- Immediate upstream remained `f2ed015356a20bb7021e850acc599274a5f91450` (`improved`, 2026-05-22) at R04 execution.
- No newer master/upstream source required integration before R04 implementation.

## Stable Chrovelo package identity and signing

- Regular application ID: `io.github.mekromn.chrovelo`
- Debug application ID: `io.github.mekromn.chrovelo.debug`
- Internal namespace: `app.marlboroadvance.mpvex`
- Persistent signing is supplied only through GitHub Actions repository secrets; no reusable private signing key is committed to Git.
- Verified signer certificate SHA-256: `b582b2f37a1bfbf1089405941b20b184c104f35a0ba38068f8ffde74fd3965a2`.

Relevant infrastructure commits:

- `38593bfa61b8c03b488904dc42a0b5510a305a3d` — stable package IDs and persistent signing support.
- `7da45964579bf52ccdc331532c127295a01a6768` — updateable signed Debug workflow and identity/version/certificate checks.
- `a8be81d11a6d8876ebc248a6ebd28abe34e0de43` — robust current-`apksigner` certificate parsing and faster analyzer lookup.

## R01 — Build/release harness for phone-only development

**Status:** `DONE`

- `Refactor Dev APK` run #11 / run ID `32385673806`: **PASS**.
- arm64 artifact ID `9413012997`.
- universal artifact ID `9413014726`.

## R02 — Readable Shader Lab source tree and migration inventory

**Status:** `DONE`

- Normalized readable legacy v6.1.1 Lua/GLSL/config source under `app/src/main/assets/mpvlab/source/`.
- Added `engine-manifest.json` and `tools/verify_mpvlab_manifest.py`.
- Preserved canonical `/storage/emulated/0/mpv` references.
- Legacy branch remains read-only.
- `Refactor Dev APK` run #23 (`32387282949`): **PASS**.

Relevant commits:

- `62b2b721c14d9e73dfc79176ba66e56ab5b8f491`
- `ff9e808770120075838379ff16f07bdb31865c11`
- `516728c3e78d13f203246ae86a634c86d01639ed`

## R03 — Canonical `/storage/emulated/0/mpv` workspace manager

**Status:** `DONE`

Implemented and validated:

- Canonical root `/storage/emulated/0/mpv`.
- Visible directories: `config/`, `scripts/`, `shaders/`, `shaders/runtime/`, `presets/`, `state/`, `logs/`.
- Isolated engine metadata under `.mpvlab/engine/`.
- Explicit engine-owned vs user-owned roots.
- Android 11+ All Files Access handling without private-storage fallback.
- Non-destructive initialization and read/write probe.
- Unit tests for canonical paths, permission behavior, ownership separation, and preset/state preservation.
- Pixel 9 Pro XL / Android 16 device smoke: **PASS** — all seven visible workspace directories were created successfully.

Key R03 commits:

- `0ab0c2b4ba026829666ad834eb10aad588ebc55f`
- `26c5a5139368820e8ffc8ee0d0f590986d7a3638`
- `24f8977248cb197ad6444ae40c5edc002a9bf8cf`
- `dc0f0d5a4b1d1e04b95d00826f8489c7a75b2c21`
- `00eb41a3245324c2b079c612c367970e36727c4d`
- `9400a6007f469dfb8d296d0d34e17919c73a48df`
- `e60f745a0b153935e09169fe14225865fb92f73e`

## R04 — Versioned Shader Lab installer/updater

**Status:** `DONE`

### Implemented

- Added `ShaderLabEngineInstaller` and typed installer state/outcome model.
- Replaced legacy Base64/ZIP runtime reconstruction with direct reads from the normalized `app/src/main/assets/mpvlab/source/` tree.
- Parses and validates the bundled `engine-manifest.json`.
- Verifies canonical workspace, engine version, schema version, file paths, byte counts, and SHA-256 hashes.
- Verifies every bundled asset before any engine payload write.
- Installs only into engine-owned destinations:
  - `config/*` → `/storage/emulated/0/mpv/config/`
  - `scripts/*` → `/storage/emulated/0/mpv/scripts/`
  - `shaders/*` → `/storage/emulated/0/mpv/shaders/`
  - normalized `docs/*` / `misc/*` reference material → `.mpvlab/engine/reference/`
- The normalized legacy `misc/state/README.txt` is treated as reference material and never copied into user-owned `/state/`.
- Stores installed manifest at `.mpvlab/engine/engine-manifest.json`.
- Stores installed engine/version/managed-file metadata at `.mpvlab/engine/version.json`.
- Hash-checks every initialization; identical files are left untouched.
- Repairs missing/corrupt managed files.
- Removes stale files only when the previous installer marker proves they were installer-managed.
- Preserves untracked engine-root files.
- Uses same-directory temp files and atomic replacement where supported, with rollback-capable fallback behavior.
- Writes the version marker last after post-write hash verification.
- Adds explicit `ShaderLabEngineMigration` hooks; unsupported schema transitions/downgrades are rejected.
- Runs reconciliation asynchronously during app startup after workspace validation.
- Appends diagnostics to `/storage/emulated/0/mpv/logs/shaderlab-installer.log`.
- Registered installer through Koin.

### R04 code commits

- `161e9ef0eb920108c82f515ca28cd2b0f28e6eb5` — versioned installer core.
- `a528d6aafebc2fb9702e725e3db38c8a6d56c952` — installer Koin registration.
- `0d1117c9ae4ddb8a34fc43be520ed83eeec4f1a3` — app-start engine reconciliation.
- `917ea95d413f6bad4969854730095ceda82fe892` — install/repair/preservation/migration tests.
- `6f19b7e8e399cedf1baa8dae6bfd011eab4239bb` — portability/JVM-test hardening; validated R04 code head.

### Automated validation

Expanded R04 unit tests:

- fresh engine installation: **PASS**
- unchanged second initialization/no rewrite: **PASS**
- corrupt managed-file repair: **PASS**
- version upgrade and stale installer-owned cleanup: **PASS**
- preservation of untracked engine-root files: **PASS**
- preservation of user `presets/` and `state/`: **PASS**
- rejection of manifest targets under user-owned state/preset roots: **PASS**
- bundled hash mismatch rejected before payload write: **PASS**
- schema transition requires explicit migration hook: **PASS**

Dedicated phone-test workflow:

- `Refactor Dev APK` run `#69`, run ID `32409643735`, job ID `96556901126`: **PASS**.
- PR merge-test SHA: `9fadbb1e21d0a0c6a34c66382f8dc4cb6849b697`.
- Unit tests: **PASS** — Gradle `BUILD SUCCESSFUL`, 33 actionable tasks.
- Signed Debug build: **PASS**.
- Package/version/signature/certificate verification: **PASS**.
- arm64 versionCode: `1787254652`.
- universal versionCode: `1787254650`.
- signer certificate SHA-256: `b582b2f37a1bfbf1089405941b20b184c104f35a0ba38068f8ffde74fd3965a2`.
- arm64 artifact ID: `9421772862`, digest `sha256:e64d18d370502835ea5a1967e020d958519dbf144fc0ab5238a8db5ab066a49c`.
- universal artifact ID: `9421773792`, digest `sha256:7deeb5bacc50c3f048546c79775be78f0a7fbf16ef211c52323937fd479cfa62`.

Normal CI/CD:

- `CI/CD Build` run `#27`, run ID `32409643876`, job ID `96556900722`: **PASS**.
- Gradle build and arm64/armeabi-v7a/universal/x86/x86_64 uploads: **PASS**.

### Pixel 9 Pro XL / Android 16 device validation

The verified R04 arm64 APK was installed over the existing Chrovelo Debug installation and launched on the target Pixel. The user inspected `/storage/emulated/0/mpv` and confirmed **everything expected by R04 is present as it should be**.

This confirms on the real device:

- engine files populate the canonical visible `config/`, `scripts/`, and `shaders/` locations;
- installer metadata/diagnostic files are produced as intended;
- the R04 update installed over the existing Debug package using the stable package/signing identity;
- the canonical workspace remains usable after the in-place app update;
- no unexpected user-owned `presets/` / `state/` population was observed.

### R04 acceptance result

- Fresh/versioned engine population: **PASS**.
- Engine-owned-only update policy: **PASS**.
- Corrupt/missing file repair logic: **PASS**.
- User preset/state preservation: **PASS**.
- Stable in-place Debug update identity: **PASS**.
- Pixel device file inspection: **PASS**.

**R04 COMPLETE.**

## Next step

### R05 — Refactor control catalog into typed domain models

**Status:** `TODO`

R05 will make the control catalog authoritative and typed, moving min/max/default/step/group/dependency metadata out of UI/bridge code while preserving every intentional legacy control.

Do not implement R06 or later work until R05 is completed and validated.
