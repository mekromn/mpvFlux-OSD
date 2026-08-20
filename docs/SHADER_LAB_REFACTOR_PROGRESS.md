# Shader Lab Refactor Progress

This file is the execution/status companion to `SHADER_LAB_REFACTOR_ROADMAP.md`.
It exists so partial or blocked roadmap steps are preserved without losing the detailed roadmap.

## Current execution state

- `CURRENT_STEP = R03`
- `R01_STATUS = DONE`
- `R02_STATUS = DONE`
- R02 acceptance criteria are satisfied. The next `Continue roadmap` executes R03 only.

## R01 — Build/release harness for phone-only development

**Status:** `DONE`

### Implemented

- Reviewed the existing build, preview, pre-release, and release workflows against the current Gradle flavors.
- Confirmed the existing PR build produces unsigned `standardRelease` APKs.
- Confirmed preview/pre-release/release signing depends on repository release-signing secrets and is therefore inappropriate for rapid development builds.
- Added `.github/workflows/refactor-dev.yml`.
- Added `workflow_dispatch` with a `ref` input defaulting to `agent/upstream-refactor`, so a phone-triggered run from the default branch builds the refactor branch without relying on a branch picker.
- Added automatic build triggers for pushes to `agent/upstream-refactor` and PRs targeting `master`.
- Development build uses `:app:assembleStandardDebug` so Android/Gradle runner debug signing is used; no reusable private signing key is committed.
- Produces and requires both:
  - arm64-v8a APK (preferred Pixel 9 Pro XL test artifact)
  - universal APK
- Verifies both APK signatures with the runner's Android `apksigner` before artifact upload.
- Artifact names include sanitized branch/ref plus the actual checked-out short SHA.
- APK artifacts are retained for 30 days.
- Added an Actions job summary with phone download/install instructions.
- Installed the workflow on `master` as infrastructure so `workflow_dispatch` is available from GitHub Actions.
- Created long-lived draft PR #1 from `agent/upstream-refactor` to `master` so later roadmap commits have a persistent CI surface without merging unfinished work.

### Upstream check

At R01 completion time:

- Immediate upstream `Muhammedahmed18/mpvFlux` still points to `f2ed015356a20bb7021e850acc599274a5f91450`.
- No newer upstream source commit needs integration before R02.

### Relevant commits / PR

- Branch workflow initial commit: `17ee8bd088759c339199a7e16c95825948b160f6`
- Master workflow infrastructure commit: `413c91f736de619d101c7828999dfca9ffa90468`
- Branch sync merge commit: `1e85c92c43e92acbac17083cd95536ede73794e6`
- Master manual-ref improvement: `edb8dff99b294d77b207e63cacb3f376be44e99d`
- Branch manual-ref improvement: `0fd95f81dd03969d5e57514208f30f817802f71f`
- Draft PR: `#1` — `agent/upstream-refactor` → `master`

### Successful GitHub Actions validation

- Workflow: `Refactor Dev APK`
- Run: `#11`
- Run ID: `32385673806`
- Job: `Signed phone-test APKs`
- Job ID: `96479458017`
- Result: `success`
- Gradle task: `:app:assembleStandardDebug`
- Gradle result: `BUILD SUCCESSFUL`
- APK signature verification: passed for both artifacts using APK Signature Scheme v2.
- Arm64 artifact:
  - ID: `9413012997`
  - name: `mpvFlux-dev-arm64-agent-upstream-refactor-8ca7b4c`
  - archive size: `62039554` bytes
  - artifact SHA-256: `96d81010fd0419b1dd1a2639f8cc4ebdc363fbaa76d85b21c6a138af51e8aee5`
  - expires: `2026-09-19`
- Universal artifact:
  - ID: `9413014726`
  - name: `mpvFlux-dev-universal-agent-upstream-refactor-8ca7b4c`
  - archive size: `124556466` bytes
  - artifact SHA-256: `41937324faa6fc9583da4bdbb24a96fa0efb9113b998a598e657d54fc43cdf59`
  - expires: `2026-09-19`

### Acceptance criteria

- Manual workflow can be launched from GitHub mobile/web: **PASS**.
- arm64 artifact produced successfully: **PASS**.
- universal artifact produced successfully: **PASS**.
- APK signature verification succeeds: **PASS**.
- no signing secret/reusable keystore added by R01: **PASS**.
- successful workflow run recorded in source: **PASS**.

R01 is complete. Do not redo it unless the harness regresses or the user explicitly requests it.

## R02 — Readable Shader Lab source tree and migration inventory

**Status:** `DONE`

### Implemented

- Confirmed immediate upstream remains `Muhammedahmed18/mpvFlux@f2ed015356a20bb7021e850acc599274a5f91450`; no newer upstream source needed integration.
- Inventoried legacy `ShaderLabRuntime.kt`, `ShaderLabBridge.kt`, `ShaderLabStateBus.kt`, eight Base64 workstation payload chunks, Lua controller, configs, shader template/runtime files, diagnostics, and legacy docs.
- Reconstructed the legacy workstation only after verifying payload SHA-256 `e498dfebbec204b264fb00bf5a39f9df70ecec6f87bc34fdc224cfc14653dcc6`.
- Normalized 11 readable files into `app/src/main/assets/mpvlab/source/` under `config/`, `scripts/`, `shaders/`, `docs/`, and `misc/`.
- Preserved the original v6.1.1 `/storage/emulated/0/mpv` paths rather than the old Kotlin private-directory rewrite because R03 owns canonical workspace implementation.
- Added `engine-manifest.json` with engine version `6.1.1-source-normalized-1`, schema version `1`, catalog version `legacy-v6.1.1`, entrypoints, required mpv options, payload provenance, file sizes, and SHA-256 hashes.
- Added `tools/verify_mpvlab_manifest.py` and `docs/R02_SHADER_LAB_MIGRATION_INVENTORY.md`.
- Documented the old Kotlin native-state publisher/apply scheduler as behavior to reimplement cleanly later; it is not silently text-injected into normalized Lua.
- Removed the temporary extraction workflow after use. The legacy branch itself was not modified.

### Relevant commits

- `62b2b721c14d9e73dfc79176ba66e56ab5b8f491` — manifest verification tool.
- `dd001cf022519cf4fe6e20a5310e6c76ba053fba` — one-shot extraction bootstrap.
- `ff9e808770120075838379ff16f07bdb31865c11` — extracted/normalized readable v6.1.1 engine source and migration inventory.
- `516728c3e78d13f203246ae86a634c86d01639ed` — removed temporary extraction workflow; clean validation target.

### Validation

- Legacy payload SHA-256 verification: **PASS**.
- Normalized manifest verification (`python3 tools/verify_mpvlab_manifest.py`): **PASS**.
- Human-readable Lua/GLSL/config source review: **PASS**.
- GitHub Actions build: **PASS** — `Refactor Dev APK` run #23 (`32387282949`), job `Signed phone-test APKs` (`96484949926`).
- Arm64 artifact ID `9413531715`, digest `sha256:c2eee0ac7c77a3fd6f47ab870ccbd5d8c3f8cd806fb2789bec5beb9a0a54036c`.
- Universal artifact ID `9413532666`, digest `sha256:c11828a729b0a59c0b4ee255079c35548c489bb871d409da828a74688c7a0664`.

R02 is complete. The next roadmap step is R03; do not wire the assets into playback before the workspace manager is implemented.
