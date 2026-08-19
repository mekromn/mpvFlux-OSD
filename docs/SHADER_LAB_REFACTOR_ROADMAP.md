# mpvFlux / mpvLab Upstream Refactor Roadmap

> **Source of truth for all future Shader Lab / mpvLab work.**
>
> This roadmap is intentionally designed so that **one user prompt executes exactly one roadmap step**. Do not skip ahead, bundle multiple steps, or revive old implementation patterns unless a step explicitly calls for it.

## 1. Current baseline

| Item | Value |
|---|---|
| Working repository | `mekromn/mpvFlux-OSD` |
| Immediate upstream/source | `Muhammedahmed18/mpvFlux` |
| Latest upstream commit in current fork history | `f2ed015356a20bb7021e850acc599274a5f91450` (`improved`, 2026-05-22) |
| Current fork `master` | `c940b2b5e9bd963c5da83764af3001fe75d9c6f9` |
| Clean refactor branch | `agent/upstream-refactor` |
| Legacy Shader Lab branch | `agent/native-shader-lab` |
| Legacy merge base | `39039866cb327d707d0e50ab370fcdbc516e0d80` |
| Legacy divergence at roadmap creation | 47 commits ahead / 18 commits behind `master` |

The clean branch was created **from current `master`**, not from the legacy Shader Lab branch. This is deliberate: the 18 newer source commits heavily refactored the player and gesture/control architecture, including `GestureHandler.kt`, `PlayerActivity.kt`, `PlayerControls*.kt`, `PlayerViewModel.kt`, and `MPVView.kt`.

### Why we are not rebasing/cherry-picking the legacy branch wholesale

The legacy branch contains valuable behavior and experiments, but its implementation overlaps old player architecture that upstream later removed or substantially rewrote. Blindly replaying the 47 commits would risk:

- reintroducing obsolete control architecture;
- recreating the Compose pointer-input `VerifyError` class of failures;
- reviving delayed long-press release behavior;
- overwriting newer source-project player fixes;
- coupling Shader Lab to global player gestures;
- carrying opaque payload/build artifacts that are hard to maintain;
- carrying a repository-tracked development signing keystore into new work.

**Policy:** treat `agent/native-shader-lab` as a read-only behavioral reference. Port intent and proven behavior, not old code wholesale.

---

## 2. Non-negotiable project requirements

These are persistent requirements unless explicitly changed by the user.

### Device / rendering target

- Primary target: **Google Pixel 9 Pro XL (komodo), Android 16**.
- `vo=gpu` is the validated path that triggers the Pixel expanded-brightness behavior.
- `gpu-next` previously failed to trigger the desired panel brightness and looked washed out/desaturated on this device/build.
- `sdr-intensity` must remain live-tunable.
- Preserve high-precision rendering (`rgba16f` where supported by the current source/mpv build).
- SDR expansion must preserve deep blacks, natural skin, highlight detail, and color volume rather than simply increasing saturation.
- HDR material must be detected/protected so SDR-expansion controls do not accidentally distort HDR playback.

### Storage target

- User-facing mpv configuration, scripts, shaders, generated shader state, and presets should live under:
  - `/storage/emulated/0/mpv`
  - and its subdirectories.
- Any Android-scoped-storage compatibility layer must preserve that user-visible path as the canonical workspace when permission is available.
- Never silently move the user's tuning source of truth into an opaque private directory without a documented reason and migration path.

### Input / UX target

- Touch should behave as a **native Android UI**, not as emulated key presses.
- Android TV / D-pad / hardware remote navigation is a first-class input path, not an afterthought.
- Long-press acceleration is allowed for **menu/list navigation only**.
- Parameter adjustment must not accelerate unless the user explicitly adds a separate adjustment mode later.
- Releasing/cancelling a press must stop repeats immediately; no 2–3 second tail.
- One-touch bypass comparison.
- Hold-to-preview original with true pointer-down / pointer-up-or-cancel lifecycle.
- Preset morphing.
- Toggleable gamut-clipping and luma-clipping indicators.
- Control groups with fast jump navigation.
- OSD/native confirmations for destructive operations.
- Multitouch/pinch gestures outside the Shader Lab controls must remain functional.

### Development / safety target

- Never commit signing secrets or reusable private signing material.
- GitHub Actions should produce installable test artifacts without requiring a PC.
- Prefer readable source assets over opaque Base64 blobs when Git can store the files directly.
- Every roadmap step must leave the branch buildable or clearly document a temporary build blocker.
- Do not change package/application ID, signing identity, or user-data migration behavior casually; those changes require their own roadmap step.

---

## 3. Target architecture

The final system should be split into clear layers rather than concentrating everything in the video-settings Composable.

```text
Android UI
  |
  +-- ShaderLabScreen / panels / controls
  |     +-- touch lifecycle
  |     +-- focus + TV/D-pad navigation
  |     +-- visual state only
  |
  +-- ShaderLabViewModel
        +-- StateFlow<ShaderLabUiState>
        +-- user intents
        +-- validation / confirmations
        |
        +-- ShaderLabRepository / Controller
              +-- control catalog + presets
              +-- persistence
              +-- runtime commands
              +-- state observation
              |
              +-- MpvShaderLabBridge
                    +-- MPV properties / script-message API
                    +-- observed state events
                    +-- shader apply/debounce
                    |
                    +-- readable Lua/GLSL assets under /storage/emulated/0/mpv
```

### Architectural rules

1. **UI never polls MPV on a fixed 200 ms loop as its primary state mechanism.** Prefer MPV property observation / callbacks feeding a `StateFlow`. Poll only as a bounded fallback if the current mpv wrapper lacks an observer for a specific field.
2. **Global `GestureHandler` does not own Shader Lab controls.** Shader Lab gets its own input controller and consumes its own pointer/focus events.
3. **Touch and TV share commands, not gesture code.** Both send semantic intents such as `NavigateNext`, `Adjust(+step)`, `PreviewStart`, `PreviewEnd`, `ToggleBypass`.
4. **Press repetition has an explicit cancellable job/token** tied to pointer/focus key lifecycle; `UP`, `CANCEL`, focus loss, disposal, or panel close cancels it immediately.
5. **Shader generation/application is serialized and debounced** so fast slider movement cannot race shader swaps.
6. **Control metadata is data, not UI code.** Min/max/default/fine/normal/coarse steps, group, formatting, dependencies, and HDR eligibility belong in a catalog/model layer.
7. **Persistence is versioned.** User presets/state must survive engine/app upgrades with migration metadata.
8. **The legacy branch is reference-only.** Do not copy entire old modified upstream files over their latest versions.

---

## 4. One-prompt execution protocol

### User command

The user can simply say:

> **Continue roadmap**

The assistant must then:

1. Read this file first.
2. Check `master` and the immediate upstream for new commits.
3. If upstream advanced, record that fact and integrate/sync the clean branch before feature work when safe.
4. Execute **only the first step whose status is `TODO`** unless the user names a specific step ID.
5. Do not start the next TODO step in the same prompt.
6. Run the validation listed for that step.
7. Update this roadmap in the same branch:
   - mark the completed step `DONE`;
   - add commit SHA(s), build/check result, and concise notes;
   - move `CURRENT_STEP` to the next TODO step.
8. Commit the code + roadmap update.
9. Report exactly what changed, validation result, and the next step ID.

### Step selection overrides

- `Continue roadmap R07` → execute only R07 if prerequisites are met.
- `Redo roadmap R07` → reopen R07, explain why, then execute only R07.
- `Skip roadmap R07` → mark `SKIPPED` with user reason; do not silently skip.
- `Roadmap status` → inspect only; do not modify source.

### Step status values

- `DONE`
- `TODO`
- `IN_PROGRESS`
- `BLOCKED`
- `SKIPPED`

### Current pointer

`CURRENT_STEP = R01`

---

# 5. Detailed roadmap

## R00 — Establish latest-source clean refactor baseline

**Status:** `DONE`

**Goal:** stop developing on the diverged legacy branch and create a clean latest-source branch while preserving all historical work.

**Completed work:**

- Confirmed `mekromn/mpvFlux-OSD` is a fork of `Muhammedahmed18/mpvFlux`.
- Confirmed current fork `master` contains latest immediate-upstream commit `f2ed015...` plus fork README change.
- Measured legacy divergence: 47 ahead / 18 behind.
- Identified heavy overlap between newer upstream player architecture and legacy modified files.
- Created `agent/upstream-refactor` from current `master`.
- Preserved `agent/native-shader-lab` as the behavioral reference branch.
- Explicitly rejected wholesale rebase/cherry-pick as migration strategy.

**Validation:** branch creation succeeded and clean branch starts from current `master`.

**Notes:** The legacy branch contains `.github/dev-signing/mpvlab-debug.keystore.b64`; do not copy that credential material into the refactor branch.

---

## R01 — Build/release harness for phone-only development

**Status:** `TODO`

**Goal:** guarantee every following step can be built and downloaded from GitHub Actions without a PC.

**Work:**

- Review current `.github/workflows/build.yml`, `preview.yml`, `pre-release.yml`, and `release.yml` against the current Gradle flavors.
- Add a dedicated manual + PR workflow for `agent/upstream-refactor` if the existing workflow is not ergonomic for rapid testing.
- Build at minimum:
  - arm64-v8a test APK;
  - universal test APK when practical.
- Prefer a debug/dev signing identity generated by the runner or repository-independent debug signing; do not commit reusable private keys.
- Name artifacts with branch + short SHA.
- Add `workflow_dispatch` so builds can be triggered from a phone.
- Keep release signing separate from development artifacts.

**Files likely involved:**

- `.github/workflows/*.yml`
- `app/build.gradle.kts`
- `.gitignore` if needed

**Acceptance criteria:**

- Manual workflow can be launched from GitHub mobile/web.
- arm64 artifact is produced successfully.
- No signing secret is added to Git history.
- Roadmap records workflow run result.

**Validation:** GitHub Actions build succeeds.

**Prompt:** `Continue roadmap`

---

## R02 — Create a readable Shader Lab source tree and migration inventory

**Status:** `TODO`

**Goal:** replace the legacy opaque runtime payload model with maintainable source-controlled Lua/GLSL/config assets while preserving proven v6.1.1 behavior.

**Work:**

- Inventory the legacy branch files:
  - `ShaderLabRuntime.kt`
  - `ShaderLabBridge.kt`
  - `ShaderLabStateBus.kt`
  - bundled workstation payload chunks
  - shader template/runtime files
  - Lua controller
  - `input.conf` / mpv config dependencies
- Extract/reference the actual Lua/GLSL/config source as normal repository files.
- Place canonical source under a clearly named directory, for example:
  - `app/src/main/assets/mpvlab/source/scripts/`
  - `app/src/main/assets/mpvlab/source/shaders/`
  - `app/src/main/assets/mpvlab/source/config/`
- Add an engine manifest containing:
  - engine version;
  - schema version;
  - file hashes;
  - required mpv options;
  - supported control catalog version.
- Do **not** yet wire the engine into playback; this step is source normalization only.

**Acceptance criteria:**

- Human-readable Shader Lab engine source is present in Git.
- No Base64 workstation chunks are needed by the new architecture.
- Legacy branch remains untouched.
- Engine manifest can verify source integrity.

**Validation:** repository build still succeeds; asset manifest test/check passes if added.

---

## R03 — Canonical `/storage/emulated/0/mpv` workspace manager

**Status:** `TODO`

**Goal:** implement the user's required canonical mpv workspace on Android 16 without mixing storage policy into UI code.

**Work:**

- Create a dedicated workspace manager/service.
- Canonical root: `/storage/emulated/0/mpv`.
- Define subdirectories at minimum:
  - `config/`
  - `scripts/`
  - `shaders/`
  - `shaders/runtime/`
  - `presets/`
  - `state/`
  - `logs/` (optional but useful)
- Detect permission/access state explicitly.
- Use the app's existing storage permission strategy where compatible.
- If Android denies canonical-path access, surface a clear actionable state; do not silently relocate user data.
- Version engine files independently from user preset/state files.
- Engine update must not overwrite user presets.

**Acceptance criteria:**

- App can create/read/write the canonical workspace on the Pixel 9 Pro XL test device when permission is granted.
- User state survives engine reinstall/update.
- Failure state is visible and non-destructive.

**Validation:** unit tests for path/migration logic + device smoke test.

---

## R04 — Versioned Shader Lab installer/updater

**Status:** `TODO`

**Goal:** safely install readable bundled engine source into the canonical workspace.

**Work:**

- Replace the legacy Base64 ZIP reconstruction path with direct asset/source copy.
- Compare engine manifest/version before copying.
- Use atomic temp-file + rename writes for generated/config files.
- Preserve user state/presets.
- Validate hashes after install.
- Add explicit migration hooks by engine/schema version.
- Log install/update result for diagnostics.

**Acceptance criteria:**

- Fresh install produces a complete `/storage/emulated/0/mpv` Shader Lab workspace.
- Upgrade replaces only engine-owned files.
- Corrupt/missing engine file is repaired on next initialization.
- User preset files are untouched.

**Validation:** automated install/migration tests + device file inspection.

---

## R05 — Refactor control catalog into typed domain models

**Status:** `TODO`

**Goal:** move control definitions out of a monolithic bridge/Composable and make the catalog authoritative.

**Work:**

- Introduce typed models such as:
  - `ShaderLabControlId`
  - `ShaderLabGroup`
  - `ShaderLabControlKind`
  - `ShaderLabControlSpec`
  - `ShaderLabStepMode`
  - `ShaderLabPresetId`
- Port all proven legacy controls, including:
  - master luma/chroma;
  - mpv brightness/contrast/gamma/saturation/hue;
  - `sdr-intensity`;
  - luma curve/gates;
  - chroma gates;
  - color-volume parameters;
  - skin protection;
  - gamut parameters;
  - output/HDR→SDR control if still relevant;
  - debug/clipping views;
  - preset and morph controls.
- Encode constraints/dependencies in data, not scattered `if` statements.
- Preserve high-precision constants and formatting.
- Add tests for clamping, integer fields, ordering constraints, and step sizes.

**Acceptance criteria:**

- No UI component owns canonical min/max/default/step metadata.
- Catalog is unit-tested.
- Legacy control set can be diffed against new catalog with no unexplained omissions.

**Validation:** unit tests.

---

## R06 — Build semantic Shader Lab command API

**Status:** `TODO`

**Goal:** create one input-neutral API used by touch, TV remote, presets, and tests.

**Work:**

Define commands/intents such as:

- `SetValue(control, value)`
- `Adjust(control, direction, stepMode)`
- `SelectGroup(group)`
- `SelectControl(control)`
- `ToggleBypass`
- `PreviewOriginalStart`
- `PreviewOriginalEnd`
- `RevertVideoStart`
- `ResetAll`
- `SaveUserPreset(slot)`
- `LoadUserPreset(slot)`
- `ClearUserPreset(slot)`
- `LoadBuiltInPreset(slot)`
- `Morph(from, to, amount)`
- `SetDiagnosticView(mode)`

Touch/TV code must call this API rather than MPV directly.

**Acceptance criteria:**

- Semantic API has no Compose dependency.
- Commands can be unit-tested with a fake backend.

**Validation:** unit tests.

---

## R07 — Refactor MPV bridge and observable state transport

**Status:** `TODO`

**Goal:** replace UI polling with an observable, typed bridge to mpv/Lua state.

**Work:**

- Create `MpvShaderLabBridge` (or equivalent) implementing the semantic backend.
- Use current `MPVLib` property observation facilities when available.
- Publish backend state through typed `StateFlow`/events.
- Keep one serialized command path into mpv.
- Normalize Lua ↔ Android state representation; JSON or another versioned structured format is preferred over ad hoc parsing if practical.
- Expose:
  - readiness;
  - source SDR/HDR status;
  - bypass/preview state;
  - active shader slot;
  - apply count;
  - last backend error;
  - all control values;
  - preset existence;
  - shader-dirty/apply-busy status.
- Add bounded fallback polling only if a required property cannot be observed.

**Acceptance criteria:**

- Compose UI does not run an infinite 200 ms polling loop.
- State changes made by TV/Lua/preset load appear in Android state promptly.
- Backend errors surface as state, not only OSD text.

**Validation:** fake-backend tests + device state synchronization smoke test.

---

## R08 — Deterministic shader generation and atomic live-apply pipeline

**Status:** `TODO`

**Goal:** make rapid live tuning stable, low-latency, and race-free.

**Work:**

- Preserve the proven double-slot/atomic shader swap concept where useful.
- Serialize shader generation/application.
- Coalesce rapid slider changes with a short debounce/frame-aware scheduler.
- Never run multiple shader generation/apply operations concurrently.
- Keep last-known-good shader and roll back on compile/apply failure.
- Surface compile/apply errors to Android state and OSD.
- Keep high precision in generated constants.
- Add a diagnostic “proof” mode that makes successful shader reload visually obvious without corrupting normal defaults.

**Acceptance criteria:**

- Fast slider scrubbing does not crash, deadlock, or leave stale shader state.
- Last-known-good output survives a bad shader generation.
- Apply latency is measured and recorded.

**Validation:** stress test rapid changes + intentionally invalid shader test.

---

## R09 — ShaderLabViewModel and authoritative UI state

**Status:** `TODO`

**Goal:** isolate lifecycle/state logic from Composables.

**Work:**

- Create `ShaderLabViewModel` or equivalent scoped state holder.
- Expose immutable `ShaderLabUiState`.
- Combine backend state, selected group/control, step mode, pending confirmations, TV focus hints, and transient status messages.
- Ensure panel close/disposal sends `PreviewOriginalEnd` and cancels repeats.
- Avoid storing mutable maps directly in Composables.

**Acceptance criteria:**

- Main Shader Lab UI is a pure renderer of `UiState` + semantic intents.
- Preview/repeat jobs cannot outlive the screen/panel lifecycle.

**Validation:** ViewModel tests.

---

## R10 — Native workstation UI redesign

**Status:** `TODO`

**Goal:** replace the legacy giant `VideoSettingsPanel.kt` extension with a purpose-built, high-density but touch-friendly Shader Lab workstation.

**Work:**

- Keep normal video settings separate from Shader Lab.
- Add a dedicated entry/button into Shader Lab.
- Design responsive portrait + landscape layouts.
- Minimum touch targets consistent with Material accessibility guidance.
- Provide:
  - persistent header/status;
  - group jump bar;
  - current control name/value;
  - slider where useful;
  - dedicated − / reset / + controls;
  - fine/normal/coarse step selector;
  - contextual help/parameter description;
  - visible backend/error status.
- Avoid forcing horizontal scrolling for core actions on a phone.
- Optimize for one-handed phone interaction in landscape where practical.

**Acceptance criteria:**

- No clipping/overflow like the previously reported broken Shader Lab screen.
- All primary controls are reachable on Pixel 9 Pro XL portrait and landscape.
- UI remains usable at Android font scaling ≥ 1.0 and reasonable larger settings.

**Validation:** Compose previews/screenshots + device test.

---

## R11 — Correct touch press lifecycle and comparison controls

**Status:** `TODO`

**Goal:** implement bypass and original preview with exact down/up/cancel semantics.

**Work:**

- `Bypass`: single tap toggles latched bypass.
- `Hold Original`:
  - pointer down → `PreviewOriginalStart` immediately;
  - pointer up → `PreviewOriginalEnd` immediately;
  - pointer cancel → `PreviewOriginalEnd` immediately;
  - screen dispose/panel close/focus loss → `PreviewOriginalEnd` defensively.
- Do not use delayed long-press recognition for original preview.
- Ensure hold-original control does not hijack unrelated pinch gestures elsewhere on the video surface.
- Add haptic feedback only where it improves state recognition.

**Acceptance criteria:**

- Original preview ends on the same interaction release/cancel with no perceptible tail.
- Bypass and preview cannot leave backend in contradictory state.

**Validation:** device touch test including cancelled gesture and app/panel interruption.

---

## R12 — Unified touch + Android TV/D-pad navigation controller

**Status:** `TODO`

**Goal:** make Shader Lab fully operable by touchscreen and Android TV remote through the same semantic actions.

**Work:**

- Add focusable controls and deterministic focus order.
- D-pad behavior:
  - Up/Down: navigate groups/controls according to current layer.
  - Left/Right: adjust selected value or navigate depending on mode.
  - Center/Enter: enter/exit adjustment mode or activate action.
  - Back: leave adjustment mode before closing panel.
- Touch taps directly select/adjust without emulating D-pad events.
- Keep input-mode state explicit and visible.
- Test hardware key repeat behavior separately from touch repeat behavior.

**Acceptance criteria:**

- Every Shader Lab feature can be reached with a standard Android TV remote.
- Touch and TV produce identical backend commands for equivalent actions.

**Validation:** emulator/key-event tests + real Android TV hardware when available.

---

## R13 — Fix long-press navigation acceleration correctly

**Status:** `TODO`

**Goal:** eliminate the known “continues scrolling 2–3 seconds after finger up” bug and restrict acceleration to navigation only.

**Work:**

- Implement repeat as a cancellable coroutine/job owned by the input controller.
- Start repeat only after navigation hold threshold.
- Optional acceleration curve may reduce repeat interval over time for **navigation only**.
- Stop immediately on:
  - pointer/key up;
  - pointer cancel;
  - focus loss;
  - mode change;
  - screen/panel disposal.
- Parameter adjustment must remain one step per explicit press unless a future separate feature changes this.
- Add tests using a fake clock where possible.

**Acceptance criteria:**

- No repeat action occurs after release/cancel.
- No acceleration occurs while adjusting a parameter.

**Validation:** automated repeat-controller tests + Pixel device test.

---

## R14 — Preset system and versioned user-state migration

**Status:** `TODO`

**Goal:** preserve 10 user slots, built-ins, video-start snapshot, save/load/clear, and robust upgrades.

**Work:**

- Define versioned preset schema.
- Store user presets under `/storage/emulated/0/mpv/presets/`.
- Store runtime/current state separately.
- Keep 10 user slots unless user changes the requirement.
- Port built-in presets with names and source-control definitions.
- Add migration from legacy v6.1.1 state if present.
- Destructive clear/overwrite requires confirmation.
- Loading invalid/out-of-range values clamps + reports migration warnings.

**Acceptance criteria:**

- User presets survive reinstall/engine update when storage remains.
- Legacy preset/state migration is tested.

**Validation:** persistence/migration tests + device restart test.

---

## R15 — Preset morphing

**Status:** `TODO`

**Goal:** implement smooth, predictable interpolation between any two compatible presets.

**Work:**

- Select From/To preset independently.
- Morph amount 0–100%.
- Interpolate continuous values precisely.
- Define deterministic handling for enums/integers/controller-only fields.
- Apply dependency/order constraints after interpolation.
- Morph changes must use the same serialized shader pipeline.
- Provide reset/swap endpoints.

**Acceptance criteria:**

- 0% equals From, 100% equals To.
- No discontinuities for continuous shader values beyond expected enum transitions.

**Validation:** numeric interpolation tests + visual device test.

---

## R16 — Gamut/luma clipping diagnostics

**Status:** `TODO`

**Goal:** make clipping visible and easy to toggle without permanently altering tuning state.

**Work:**

- Modes: Off / Gamut / Luma / Both.
- Diagnostic overlay/view must be transient and reversible.
- Clearly distinguish input clipping, shader-created clipping, and final output threshold where technically possible.
- Preserve normal tuning values when toggling diagnostics.
- Add concise legend in UI.

**Acceptance criteria:**

- Toggle returns pixel pipeline to identical normal state when disabled.
- Diagnostic state is visible in Android UI and backend state.

**Validation:** synthetic test patterns + device playback test.

---

## R17 — SDR/HDR protection and Pixel rendering profile

**Status:** `TODO`

**Goal:** formalize the proven Pixel 9 Pro XL expanded-brightness path without applying SDR expansion to native HDR incorrectly.

**Work:**

- Detect source transfer/primaries/metadata using current mpv properties.
- Define SDR eligibility clearly.
- Preserve `vo=gpu` as the Pixel validated profile unless current mpv testing proves a better path.
- Keep validated baseline options available as a profile, including high-precision FBO and scaling choices where supported.
- Do not hardcode experimental V4 saturation changes as the universal default; preserve the more natural reference tuning as a baseline and expose saturation/tuning live.
- Show protected/disabled reason in Shader Lab UI for HDR input.

**Acceptance criteria:**

- SDR videos enter expansion path.
- HDR videos do not receive SDR-only expansion unless explicitly enabled by a future expert override.

**Validation:** known SDR + HDR sample comparison on Pixel 9 Pro XL.

---

## R18 — Gesture coexistence and player regression pass

**Status:** `TODO`

**Goal:** ensure Shader Lab does not break normal player gestures or controls.

**Work:**

- Verify global player single tap/double tap behavior.
- Verify pinch-to-zoom.
- Verify horizontal seek.
- Verify vertical brightness/volume gestures.
- Verify dynamic speed/hold behavior.
- Ensure Shader Lab panels consume only events they own.
- No Compose pointer coroutine type hacks or bytecode-level patches.
- Specifically guard against the prior `VerifyError` failure class in `GestureHandler`.

**Acceptance criteria:**

- Existing upstream player gesture suite works with Shader Lab enabled/disabled.
- Opening/closing Shader Lab leaves gesture state clean.

**Validation:** device regression checklist + build/tests.

---

## R19 — Performance, frame pacing, and live-tuning stress tests

**Status:** `TODO`

**Goal:** keep Shader Lab interaction smooth and avoid unnecessary MPV/shader churn.

**Work:**

- Measure command frequency during slider scrubbing.
- Measure shader compile/apply latency.
- Coalesce redundant state emissions.
- Avoid recomposing the entire workstation for one control value when practical.
- Confirm no unbounded coroutine accumulation.
- Confirm no file-write storm during adjustment.
- Profile heat/battery only after correctness.

**Acceptance criteria:**

- No visible input lag from UI architecture.
- No delayed release behavior.
- Stable sustained tuning session without crash/leak symptoms.

**Validation:** Android profiler/log review when available + stress script/manual run.

---

## R20 — Branding, package identity, and side-by-side install strategy

**Status:** `TODO`

**Goal:** make source builds install predictably on the user's devices without relying on APK Editor Pro renaming.

**Work:**

- Decide canonical app name/branding for this fork (mpvLab/Chrovelo/etc. only if user confirms).
- Decide whether source application ID should remain upstream-compatible or use a fork-specific ID.
- If changing package/application ID:
  - plan data migration;
  - update manifests/providers/Room schema paths as required;
  - verify intent filters and file provider authority;
  - verify upgrade vs side-by-side behavior.
- Keep signing strategy compatible with future updates.

**Acceptance criteria:**

- Test APK installs/upgrades exactly as intended.
- No provider/package collisions.

**Validation:** install/upgrade test on Pixel device.

---

## R21 — Full test matrix and release candidate

**Status:** `TODO`

**Goal:** produce the first clean refactored release candidate based on latest source architecture.

**Test matrix:**

- Pixel 9 Pro XL / Android 16:
  - SDR expanded-brightness path;
  - HDR protection;
  - portrait + landscape;
  - touch tap/hold/release/cancel;
  - pinch/seek/brightness/volume coexistence;
  - preset save/load/morph;
  - clipping diagnostics;
  - app restart/state persistence.
- Android TV box when available:
  - D-pad navigation;
  - center/back behavior;
  - navigation long-press acceleration;
  - immediate stop on key-up;
  - full feature reachability.
- GitHub Actions:
  - clean build from checkout;
  - arm64 artifact;
  - universal artifact if retained;
  - no repository signing secrets.

**Acceptance criteria:**

- No known critical crash.
- No delayed long-press release.
- Shader changes visibly apply live.
- Native touch and TV input both pass.
- Roadmap contains final build SHA and test notes.

**Validation:** release-candidate workflow + device matrix.

---

# 6. Legacy behavior inventory to preserve

The following legacy work is useful as behavioral reference and should be deliberately reimplemented on the clean architecture:

- Native Shader Lab bridge/control catalog.
- Live MPV property tuning.
- Shader parameter tuning.
- Shader reload proof/debug mode.
- Bypass comparison.
- Hold original preview.
- Video-start revert.
- 10 user preset slots.
- Built-in presets.
- Preset morphing.
- Gamut/luma clipping views.
- Control groups.
- Fine/normal/coarse granularity.
- SDR/HDR guard.
- Status/error feedback.
- Shader apply coalescing / last-known-good concept.
- Android-native Shader Lab panel concept.
- TV remote support target.

The following legacy implementation details are **not** automatically preserved:

- Base64 workstation payload chunks.
- Repo-tracked development keystore.
- Whole-file replacements of older upstream player/control source.
- Fixed 200 ms UI polling as the primary state mechanism.
- Gesture patches that mix Shader Lab navigation with global player pointer handling.
- Any long-press repeat implementation that can outlive release/cancel.

---

# 7. Completion definition

The refactor is complete when:

1. `agent/upstream-refactor` is based on current source architecture and contains no unresolved legacy-code transplant.
2. GitHub Actions produces installable test artifacts from a phone-triggerable workflow.
3. Shader Lab engine source is readable/versioned in Git and installed safely to `/storage/emulated/0/mpv`.
4. Android UI uses typed observable state and semantic commands.
5. Touch preview/repeat lifecycle stops instantly on release/cancel.
6. Long-press acceleration affects navigation only.
7. Android TV/D-pad can operate the entire Shader Lab.
8. Bypass, preview, revert, presets, morphing, clipping diagnostics, and live tuning all work.
9. SDR expansion preserves the validated Pixel `vo=gpu` path and protects HDR.
10. Normal upstream player gestures and playback behavior remain regression-free.
11. No signing secrets/private reusable keys are stored in source.
12. The release-candidate test matrix passes and is recorded here.
