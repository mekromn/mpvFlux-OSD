# Shader Lab Refactor Progress

This file is the execution/status companion to `SHADER_LAB_REFACTOR_ROADMAP.md`.
It exists so partial or blocked roadmap steps are preserved without losing the detailed roadmap.

## Current execution state

- `CURRENT_STEP = R03`
- `R01_STATUS = DONE`
- `R02_STATUS = DONE`
- `R03_STATUS = BLOCKED`
- R03 implementation, unit tests, normal CI, persistent Chrovelo signing, package identity, versionCode verification, certificate verification, and phone-test APK production are complete.
- The **only remaining R03 acceptance gate** is the Pixel 9 Pro XL / Android 16 canonical-workspace smoke test on `/storage/emulated/0/mpv`.
- Do not advance to R04 until the device test confirms create/read/write access when All Files Access is granted and confirms existing user `presets/` and `state/` data remain non-destructive.

## Infrastructure amendment — stable Chrovelo package identity and signing

This prerequisite was intentionally moved ahead of R03 device testing because disposable runner debug certificates cannot support repeated in-place phone updates.

### Stable package identities

- Regular application ID: `io.github.mekromn.chrovelo`
- Debug application ID: `io.github.mekromn.chrovelo.debug`
- Internal Android/Kotlin namespace remains `app.marlboroadvance.mpvex`; application identity and source namespace are deliberately separate.
- Regular and Debug can be installed side-by-side because their application IDs differ.
- The first Chrovelo Debug install is a fresh install because the package identity changed; later Debug builds with the same certificate and a greater versionCode are update-compatible.

### Persistent signing policy

- Persistent signing material is supplied only through GitHub Actions repository secrets:
  - `SIGNING_KEYSTORE`
  - `SIGNING_KEY_ALIAS`
  - `SIGNING_STORE_PASSWORD`
  - `KEY_PASSWORD`
- No reusable private signing key is committed to Git.
- CI refuses to fall back to a disposable runner debug key when persistent signing is missing.
- CI generates monotonically increasing build versionCodes and verifies package ID, versionCode, APK signature, and signer certificate before artifact upload.
- Verified signer certificate SHA-256: `b582b2f37a1bfbf1089405941b20b184c104f35a0ba38068f8ffde74fd3965a2`.

### Relevant commits

- `38593bfa61b8c03b488904dc42a0b5510a305a3d` — stable Chrovelo package IDs and persistent signing support in Gradle.
- `7da45964579bf52ccdc331532c127295a01a6768` — updateable signed Debug workflow and package/version/certificate verification.
- `a8be81d11a6d8876ebc248a6ebd28abe34e0de43` — make certificate parsing compatible with current `apksigner` output and speed up `apkanalyzer` lookup.

### Verified Actions result

- Workflow: `Refactor Dev APK`
- Run: `#55`
- Run ID: `32397467734`
- Head commit: `a8be81d11a6d8876ebc248a6ebd28abe34e0de43`
- PR merge-test commit used by GitHub Actions: `3a87d568a0be2512023542a8319f90400e2e4cf8`
- Result: **PASS**.
- Workspace unit tests: **PASS**.
- Persistent signing preparation: **PASS**.
- `:app:assembleStandardDebug`: **PASS**.
- Debug package identity: `io.github.mekromn.chrovelo.debug`: **PASS**.
- arm64 versionCode: `1787246702`: **PASS**.
- universal versionCode: `1787246700`: **PASS**.
- APK Signature Scheme v2: **PASS** for both APKs.
- Same signer certificate on arm64/universal: **PASS**.
- arm64 artifact ID: `9417336415`, artifact digest `sha256:6a9df51f841b45a3672e94210fd21419d99853213cbf4661846454a140276f77`.
- universal artifact ID: `9417337270`, artifact digest `sha256:89ddc40abab8b1ea222401c7d89011cdf641ab28ff717e8cc39241dfe89155d2`.

This establishes the permanent updateable development identity before the R03 device smoke test. R20 must not casually rotate these public application IDs or the signing certificate; later R20 work is limited to branding/internal namespace/final migration decisions unless the user explicitly changes the strategy.

## R01 — Build/release harness for phone-only development

**Status:** `DONE`

### Implemented

- Reviewed the existing build, preview, pre-release, and release workflows against the current Gradle flavors.
- Added `.github/workflows/refactor-dev.yml` for manual, push, and PR development builds.
- Added `workflow_dispatch` with a `ref` input defaulting to `agent/upstream-refactor`, so a phone-triggered run from the default branch can build the refactor branch.
- Required arm64-v8a and universal APK outputs.
- Added APK signature verification before upload.
- Artifact names include ref + checked-out short SHA and are retained for 30 days.
- Installed the workflow on `master` as infrastructure and created long-lived draft PR #1.

### Successful original R01 validation

- Workflow: `Refactor Dev APK`
- Run: `#11`
- Run ID: `32385673806`
- Job ID: `96479458017`
- Result: `success`
- Arm64 artifact ID: `9413012997`
- Universal artifact ID: `9413014726`

R01 is complete. Its original runner-debug signing policy was superseded by the infrastructure amendment above so repeated Chrovelo Debug builds can update in place without storing a private key in Git.

## R02 — Readable Shader Lab source tree and migration inventory

**Status:** `DONE`

### Implemented

- Confirmed immediate upstream remains `Muhammedahmed18/mpvFlux@f2ed015356a20bb7021e850acc599274a5f91450`.
- Inventoried the legacy `ShaderLabRuntime.kt`, bridge/state bus, Base64 workstation payload, Lua controller, configs, shader files, diagnostics, and documentation.
- Reconstructed the legacy workstation only after verifying payload SHA-256 `e498dfebbec204b264fb00bf5a39f9df70ecec6f87bc34fdc224cfc14653dcc6`.
- Normalized 11 readable files into `app/src/main/assets/mpvlab/source/`.
- Preserved canonical `/storage/emulated/0/mpv` references for R03.
- Added `engine-manifest.json`, `tools/verify_mpvlab_manifest.py`, and `docs/R02_SHADER_LAB_MIGRATION_INVENTORY.md`.
- Legacy branch remains read-only.

### Relevant commits

- `62b2b721c14d9e73dfc79176ba66e56ab5b8f491` — manifest verification tool.
- `dd001cf022519cf4fe6e20a5310e6c76ba053fba` — one-shot extraction bootstrap.
- `ff9e808770120075838379ff16f07bdb31865c11` — readable normalized v6.1.1 engine source.
- `516728c3e78d13f203246ae86a634c86d01639ed` — clean R02 validation target.

### Validation

- Legacy payload SHA verification: **PASS**.
- Manifest verification: **PASS**.
- Human-readable source review: **PASS**.
- GitHub Actions: **PASS** — `Refactor Dev APK` run #23 (`32387282949`).
- Arm64 artifact ID `9413531715`.
- Universal artifact ID `9413532666`.

R02 is complete.

## R03 — Canonical `/storage/emulated/0/mpv` workspace manager

**Status:** `BLOCKED`

### Implemented

- Added `ShaderLabWorkspacePaths` with canonical root `/storage/emulated/0/mpv` and required `config/`, `scripts/`, `shaders/`, `shaders/runtime/`, `presets/`, `state/`, and `logs/` directories.
- Added isolated engine metadata at `.mpvlab/engine/` with an engine-version marker reserved for the R04 installer.
- Explicitly separated engine-owned roots (`config`, `scripts`, `shaders`, `.mpvlab/engine`) from user-owned roots (`presets`, `state`).
- Added `ShaderLabWorkspaceManager` with observable `StateFlow` states: unchecked, available, permission required, unavailable, and failure.
- Reused the standard build's existing Android 11+ `MANAGE_EXTERNAL_STORAGE` strategy. Android 11+ without All Files Access returns an actionable permission-required state; scoped-only builds return an explicit unavailable state.
- Added an app-specific All Files Access settings intent with fallback to the system-wide All Files Access settings page.
- Added non-destructive directory initialization and a self-deleting read/write probe under `.mpvlab/engine`; permission denial does not create files and there is no app-private fallback.
- Registered the workspace manager in Koin and initialized it asynchronously on app startup.
- Added JUnit tests for exact canonical paths, directory creation, preset/state preservation, non-destructive permission denial, and engine/user ownership separation.
- Updated the development workflow to run `:app:testStandardDebugUnitTest` before signing/building the phone APK.
- Synchronized the refactor branch with current fork `master` through merge commit `e60f745a0b153935e09169fe14225865fb92f73e`.

### Relevant R03 commits

- `0ab0c2b4ba026829666ad834eb10aad588ebc55f` — canonical workspace path/state model.
- `26c5a5139368820e8ffc8ee0d0f590986d7a3638` — workspace manager/access policy/read-write probe.
- `24f8977248cb197ad6444ae40c5edc002a9bf8cf` — Koin workspace service registration.
- `dc0f0d5a4b1d1e04b95d00826f8489c7a75b2c21` — workspace preservation/path tests.
- `00eb41a3245324c2b079c612c367970e36727c4d` — non-blocking app-start initialization.
- `c8289fe74a7446ddf2a18253e54c6b3609081b59` — JUnit test dependency.
- `9400a6007f469dfb8d296d0d34e17919c73a48df` — run workspace tests before phone build.
- `e60f745a0b153935e09169fe14225865fb92f73e` — synchronize current master history before validation.

### Validation

- Immediate upstream check: **PASS** — as of 2026-08-20, `Muhammedahmed18/mpvFlux` remains `f2ed015356a20bb7021e850acc599274a5f91450` (`improved`, 2026-05-22); no newer upstream source requires integration.
- Current branch head before this progress-only commit: `a8be81d11a6d8876ebc248a6ebd28abe34e0de43`; PR #1 reports mergeable.
- R03 unit tests: **PASS**, including the successful run #55 validation.
- Normal CI: **PASS** — `CI/CD Build` run #20 (`32397467651`) completed successfully on `a8be81d11a6d8876ebc248a6ebd28abe34e0de43`.
- Persistent signing prerequisite: **PASS**.
- Updateable Chrovelo Debug APK production: **PASS**.
- Package/version/signature/certificate verification: **PASS**.
- Pixel 9 Pro XL canonical-workspace smoke test: **PENDING / ONLY REMAINING BLOCKER**.

### Device smoke test required to complete R03

Install the verified arm64 Chrovelo Debug APK on the Pixel 9 Pro XL, grant All Files Access if requested, reopen the app, and inspect `/storage/emulated/0/mpv`.

Confirm:

1. The app opens normally.
2. `/storage/emulated/0/mpv` remains the canonical workspace.
3. `config/`, `scripts/`, `shaders/`, `shaders/runtime/`, `presets/`, `state/`, and `logs/` exist or are created as intended.
4. The app can create/read/write there when permission is granted.
5. Existing content in `presets/` and `state/` remains untouched.
6. If permission is withheld, the app does not silently relocate to private storage or destructively alter the workspace.

R03 is not complete and `CURRENT_STEP` remains R03 until this real-device acceptance test passes. Do not start R04 in the meantime.
