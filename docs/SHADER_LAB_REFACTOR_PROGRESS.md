# Shader Lab Refactor Progress

This file is the execution/status companion to `SHADER_LAB_REFACTOR_ROADMAP.md`.
It exists so partial or blocked roadmap steps are preserved without losing the detailed roadmap.

## Current execution state

- `CURRENT_STEP = R01`
- `R01_STATUS = BLOCKED`
- Do not advance to R02 until the R01 GitHub Actions build is observed succeeding and its artifacts are verified.

## R01 — Build/release harness for phone-only development

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

At R01 execution time:

- Immediate upstream `Muhammedahmed18/mpvFlux` still points to `f2ed015356a20bb7021e850acc599274a5f91450`.
- Fork `master` had not gained external/source changes since the roadmap baseline; only the R01 dev-build infrastructure commits were added during this step.

### Relevant commits / PR

- Branch workflow initial commit: `17ee8bd088759c339199a7e16c95825948b160f6`
- Master workflow infrastructure commit: `413c91f736de619d101c7828999dfca9ffa90468`
- Branch sync merge commit: `1e85c92c43e92acbac17083cd95536ede73794e6`
- Master manual-ref improvement: `edb8dff99b294d77b207e63cacb3f376be44e99d`
- Branch manual-ref improvement: `0fd95f81dd03969d5e57514208f30f817802f71f`
- Draft PR: `#1` — `agent/upstream-refactor` → `master`

### Validation completed

- Workflow file is present on both the default branch and refactor branch.
- Manual workflow target defaults to `agent/upstream-refactor`.
- No signing key/keystore material was added by R01.
- Workflow is configured to fail if either required APK is missing.
- Workflow is configured to fail if APK signature verification fails.
- Workflow YAML structure was sanity-checked locally.

### Blocker

The connected GitHub Actions read surface reports zero PR workflow runs for the R01 head/merge commits, and the available connector does not expose a workflow-dispatch action or an Actions-enable setting. Therefore the roadmap acceptance criterion **“GitHub Actions build succeeds”** has not yet been proven and R01 must not be marked DONE.

### Required completion check

On the next `Continue roadmap`:

1. Re-check upstream and `master`.
2. Re-check PR #1 / Actions for a `Refactor Dev APK` run.
3. If no run exists, verify that Actions is enabled for the fork and manually run **Refactor Dev APK** with the default `ref = agent/upstream-refactor` if the available tooling permits it.
4. Require successful `Signed phone-test APKs` job.
5. Verify both arm64 and universal artifacts exist.
6. Record the run ID/result in the roadmap/progress log.
7. Mark R01 DONE and advance `CURRENT_STEP` to R02 only after that success.
