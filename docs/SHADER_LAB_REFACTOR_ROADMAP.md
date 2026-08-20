# mpvFlux / mpvLab Upstream Refactor Roadmap

> **Source of truth for all future Shader Lab / mpvLab work.**
>
> One user prompt executes exactly one roadmap step. Do not skip ahead, bundle steps, or revive old implementation patterns unless the active step explicitly requires it.

## 1. Current baseline

| Item | Value |
|---|---|
| Working repository | `mekromn/mpvFlux-OSD` |
| Immediate upstream/source | `Muhammedahmed18/mpvFlux` |
| Latest immediate-upstream commit | `f2ed015356a20bb7021e850acc599274a5f91450` (`improved`, 2026-05-22) |
| Current fork `master` | `83e2b2f64c48abbdc1125cff626cfcbae230bfde` |
| Clean refactor branch | `agent/upstream-refactor` |
| Legacy Shader Lab branch | `agent/native-shader-lab` |
| Legacy merge base | `39039866cb327d707d0e50ab370fcdbc516e0d80` |

The clean branch was created from current `master`, not from the legacy Shader Lab branch. The legacy branch remains a **read-only behavioral reference** because newer upstream source substantially changed player and gesture/control architecture.

---

## 2. Non-negotiable project requirements

### Device / rendering target

- Primary target: **Google Pixel 9 Pro XL (komodo), Android 16**.
- `vo=gpu` is the validated Pixel expanded-brightness path.
- `gpu-next` previously failed to trigger desired panel brightness and looked washed out/desaturated.
- `sdr-intensity` must remain live-tunable.
- Preserve high precision (`rgba16f` where supported).
- SDR expansion must preserve deep blacks, natural skin, highlight detail, and color volume instead of relying on a fixed saturation boost.
- Native HDR must be detected/protected from SDR-only expansion.

### Storage target

- Canonical user-visible workspace: `/storage/emulated/0/mpv` and subdirectories.
- Never silently relocate the user's source of truth to app-private storage.
- Engine-owned files and user-owned presets/state must remain explicitly separated.

### Input / UX target

- Native Android touch, not emulated key presses.
- Android TV / D-pad / hardware remote is a first-class path.
- Long-press acceleration applies to menu/list navigation only.
- Parameter adjustment does not accelerate unless a separate future mode explicitly adds it.
- Release/cancel/focus loss must stop repeats immediately.
- One-touch bypass.
- Hold-to-preview original with true down/up/cancel lifecycle.
- Preset morphing.
- Gamut/luma clipping diagnostics.
- Group jump navigation.
- Confirmations for destructive operations.
- Preserve unrelated pinch/multitouch gestures.

### Development / safety target

- Never commit signing secrets or reusable private signing material.
- GitHub Actions must produce installable phone-test artifacts.
- Prefer readable source assets over opaque Base64 payloads.
- Each step leaves the branch buildable or clearly records a blocker.
- Stable Chrovelo IDs/signing identity must not be changed casually:
  - regular: `io.github.mekromn.chrovelo`
  - debug: `io.github.mekromn.chrovelo.debug`
  - signer SHA-256: `b582b2f37a1bfbf1089405941b20b184c104f35a0ba38068f8ffde74fd3965a2`

---

## 3. Target architecture

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
        +-- semantic user intents
        +-- validation / confirmations
        |
        +-- ShaderLabRepository / Controller
              +-- typed control catalog + presets
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

1. UI does not use a fixed 200 ms MPV polling loop as primary state transport.
2. Global `GestureHandler` does not own Shader Lab controls.
3. Touch and TV share semantic commands, not gesture code.
4. Press repetition uses an explicitly cancellable lifecycle token/job.
5. Shader generation/application is serialized and coalesced.
6. Canonical control metadata lives in a typed catalog, not UI code.
7. Persistence is versioned and migratable.
8. Legacy branch is reference-only; do not transplant whole obsolete files.

---

## 4. One-prompt execution protocol

When the user says **Continue roadmap**:

1. Read this roadmap first.
2. Read `docs/SHADER_LAB_REFACTOR_PROGRESS.md` for latest execution state.
3. Check fork `master` and immediate upstream before feature work.
4. Execute only `CURRENT_STEP` unless the user explicitly names another step.
5. Do not start the next step in the same prompt.
6. Run that step's validation.
7. Update roadmap/progress with status, commits, workflow/build results, and next pointer.
8. Commit the completion record.
9. Report what changed and the next step.

Status values: `DONE`, `TODO`, `IN_PROGRESS`, `BLOCKED`, `SKIPPED`.

### Current pointer

`CURRENT_STEP = R06`

---

# 5. Detailed roadmap

## R00 — Establish latest-source clean refactor baseline

**Status:** `DONE`

Created `agent/upstream-refactor` from current source, preserved `agent/native-shader-lab` as read-only behavioral reference, and rejected wholesale replay of obsolete legacy architecture.

---

## R01 — Build/release harness for phone-only development

**Status:** `DONE`

Added the phone-triggerable `Refactor Dev APK` workflow with arm64/universal artifacts and signature verification. Stable package/signing infrastructure later superseded disposable runner debug signing.

Original validation: run #11 / `32385673806` — **PASS**.

---

## R02 — Create readable Shader Lab source tree and migration inventory

**Status:** `DONE`

Normalized legacy v6.1.1 Lua/GLSL/config source under `app/src/main/assets/mpvlab/source/`, added `engine-manifest.json`, integrity verification, and migration inventory. Legacy branch remains untouched.

Validation run #23 / `32387282949` — **PASS**.

---

## R03 — Canonical `/storage/emulated/0/mpv` workspace manager

**Status:** `DONE`

Implemented canonical workspace/access policy, explicit All Files Access state, engine/user ownership separation, non-destructive initialization, read/write probe, Koin registration, startup initialization, and tests. Pixel 9 Pro XL device smoke confirmed creation/read/write of the seven visible workspace directories.

Detailed execution evidence is retained in `docs/SHADER_LAB_REFACTOR_PROGRESS.md`.

---

## R04 — Versioned Shader Lab installer/updater

**Status:** `DONE`

Implemented manifest/hash-verified engine installation, atomic replacement, repair, stale managed-file cleanup, migration hooks, diagnostics, and strict preservation of user `presets/` / `state/`. Pixel device inspection confirmed correct engine population after an in-place signed Debug update.

Validated code head: `6f19b7e8e399cedf1baa8dae6bfd011eab4239bb`.
Dedicated validation: run #69 / `32409643735` — **PASS**.
Normal CI: run #27 / `32409643876` — **PASS**.

---

## R05 — Refactor control catalog into typed domain models

**Status:** `DONE`

**Goal:** make control metadata authoritative, typed, testable, and independent of UI/MPV transport.

**Completed work:**

- Added typed domain models:
  - `ShaderLabControlId`
  - `ShaderLabGroup`
  - `ShaderLabControlKind`
  - `ShaderLabControlSpec`
  - `ShaderLabStepMode`
  - `ShaderLabPresetId`
  - `ShaderLabActionId` / `ShaderLabActionSpec`
  - `ShaderLabControlRelationship`
  - `ShaderLabBuiltInPreset`
- Added authoritative `ShaderLabControlCatalog`.
- Ported all **53 value-bearing legacy Android controls** with exact legacy keys, defaults, min/max bounds, fine/normal/coarse steps, precision/formatting, integer/percent semantics, choices, groups, and preset eligibility.
- Accounted for all **10 action-only Lua workstation items** as typed action metadata; execution intentionally belongs to R06.
- Encoded all 12 proven Lua ordered-pair constraints as data using a `0.000001` gap.
- Encoded all five proven virtual-master scaling dependencies as data.
- Added generic clamping, step lookup, formatting, normalization, and effective-backend-value helpers.
- Added typed 10-slot user/built-in preset IDs and preserved all 10 built-in preset names.
- Added `docs/R05_CONTROL_CATALOG_INVENTORY.md` documenting the exact legacy diff/accounting.

**Relevant commits:**

- `4513ab2a01ee0ddbd3808b16e6a5eb204468dd31` — typed catalog/domain model.
- `0d6bdc85609132eda66adcb6afce24579403b923` — catalog validation tests.
- `cf1a0a20d8525b133a4a4b01d790afdd56df1dc7` — legacy parity inventory.

**Acceptance criteria:**

- No clean-branch UI owns an alternate canonical min/max/default/step table: **PASS**.
- Catalog is unit-tested: **PASS**.
- Legacy control set is diffable with no unexplained omissions: **PASS** — exact 53 value keys + exact 10 action-only keys are asserted/documented.

**Validation:**

- `Refactor Dev APK` run #79 / run ID `32416421098`, job `96578517197`: **PASS**.
  - R05/catalog unit tests: **PASS**.
  - signed Debug build: **PASS**.
  - package/version/signing verification: **PASS**.
  - arm64 artifact `9424196537`; universal artifact `9424197412`.
- `CI/CD Build` run #32 / run ID `32416421058`, job `96578589838`: **PASS**.
  - Gradle build: **PASS**.
  - arm64, armeabi-v7a, universal, x86, and x86_64 uploads: **PASS**.

---

## R06 — Build semantic Shader Lab command API

**Status:** `TODO`

**Goal:** create one input-neutral API used by touch, TV remote, presets, and tests.

**Work:**

Define semantic commands/intents such as:

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

**Work:** create `MpvShaderLabBridge`, use MPV property observation where available, publish typed `StateFlow`/events, serialize command entry, normalize Lua↔Android state, expose readiness/source/bypass/preview/slot/apply/error/control/preset/apply-busy state, and use bounded fallback polling only when unavoidable.

**Acceptance criteria:** no infinite 200 ms UI polling; external backend changes appear promptly; backend errors surface as state.

**Validation:** fake-backend tests + device synchronization smoke test.

---

## R08 — Deterministic shader generation and atomic live-apply pipeline

**Status:** `TODO`

**Goal:** make rapid live tuning stable, low-latency, and race-free.

**Work:** preserve useful double-slot/last-known-good behavior, serialize generation/application, coalesce rapid changes, rollback bad shader applies, expose errors, preserve precision, and add a diagnostic proof mode.

**Validation:** rapid-change stress + intentionally invalid shader test.

---

## R09 — ShaderLabViewModel and authoritative UI state

**Status:** `TODO`

Create immutable `ShaderLabUiState`, combine backend/navigation/step/confirmation/focus/status state, and ensure preview/repeat jobs cannot outlive the panel lifecycle.

**Validation:** ViewModel tests.

---

## R10 — Native workstation UI redesign

**Status:** `TODO`

Build a dedicated responsive Shader Lab workstation for portrait/landscape with persistent status, group jump bar, current value, sliders where useful, −/reset/+ controls, fine/normal/coarse mode, help text, and backend/error state. Keep normal video settings separate.

**Validation:** Compose previews/screenshots + Pixel device test.

---

## R11 — Correct touch press lifecycle and comparison controls

**Status:** `TODO`

Implement latched bypass and true down/up/cancel original preview. Preview must end immediately on release, cancel, focus loss, panel close, or disposal and must not hijack unrelated multitouch.

**Validation:** Pixel touch lifecycle tests.

---

## R12 — Unified touch + Android TV/D-pad navigation controller

**Status:** `TODO`

Add deterministic focus order and full D-pad operation using the same semantic commands as touch. Back exits adjustment mode before panel close.

**Validation:** key-event tests + real Android TV hardware when available.

---

## R13 — Fix long-press navigation acceleration correctly

**Status:** `TODO`

Use cancellable repeat jobs for navigation only, with immediate stop on up/cancel/focus loss/mode change/disposal. Parameter adjustment remains one explicit step per press.

**Validation:** repeat-controller tests + Pixel device test.

---

## R14 — Preset system and versioned user-state migration

**Status:** `TODO`

Define versioned preset schema, preserve 10 user slots and built-ins, keep user presets under `/storage/emulated/0/mpv/presets/`, migrate legacy state where present, clamp/report invalid values, and require confirmations for destructive overwrite/clear.

**Validation:** persistence/migration tests + restart test.

---

## R15 — Preset morphing

**Status:** `TODO`

Implement deterministic From/To preset interpolation with 0–100% amount, continuous precision, enum/integer policy, post-interpolation constraints, and the serialized shader path.

**Validation:** interpolation tests + visual device test.

---

## R16 — Gamut/luma clipping diagnostics

**Status:** `TODO`

Modes: Off / Gamut / Luma / Both. Diagnostics must be transient/reversible and must not mutate normal tuning state.

**Validation:** synthetic patterns + device playback.

---

## R17 — SDR/HDR protection and Pixel rendering profile

**Status:** `TODO`

Formalize source HDR detection and SDR eligibility, preserve validated `vo=gpu` Pixel behavior, retain high-precision baseline settings, keep the natural reference tuning as baseline, and do not hardcode experimental fixed saturation boosts.

**Validation:** known SDR + HDR comparison on Pixel 9 Pro XL.

---

## R18 — Gesture coexistence and player regression pass

**Status:** `TODO`

Verify normal single/double tap, pinch, seek, brightness/volume, dynamic speed/hold, and clean Shader Lab event ownership. Do not use bytecode/pointer-coroutine hacks; specifically guard against the prior Compose `VerifyError` class.

**Validation:** device regression checklist + build/tests.

---

## R19 — Performance, frame pacing, and live-tuning stress tests

**Status:** `TODO`

Measure command/apply frequency and latency, coalesce redundant work, prevent unbounded coroutines/file-write storms, minimize unnecessary recomposition, and profile sustained tuning stability.

**Validation:** profiler/log review when available + stress session.

---

## R20 — Branding, package identity, and side-by-side install strategy

**Status:** `TODO`

Stable Chrovelo application IDs and signing identity were intentionally established early. R20 must not rotate them casually; remaining work is branding/internal namespace/final migration cleanup and collision verification unless the user explicitly changes strategy.

**Validation:** install/upgrade/provider-collision test.

---

## R21 — Full test matrix and release candidate

**Status:** `TODO`

Run the complete Pixel 9 Pro XL test matrix plus Android TV/D-pad testing when hardware is available, verify CI artifacts/signing, record final SHA, and produce the first clean refactored release candidate.

---

# 6. Legacy behavior inventory to preserve

Preserve deliberately: native control intent, live MPV/shader tuning, proof mode, bypass, hold original, video-start revert, 10 user slots, built-ins, morphing, clipping views, control groups, fine/normal/coarse granularity, SDR/HDR guard, status/errors, apply coalescing/last-known-good, native panel concept, TV support.

Do **not** preserve as architecture: Base64 payload reconstruction, repo-tracked reusable signing keys, wholesale old-file replacements, fixed 200 ms UI polling, global-gesture ownership of Shader Lab, or repeat implementations that outlive release/cancel.

---

# 7. Completion definition

The refactor is complete when the clean branch remains based on current source architecture, phone-triggered CI produces installable artifacts, engine source is readable/versioned and safely installed to `/storage/emulated/0/mpv`, UI uses typed observable state and semantic commands, touch/TV lifecycles are correct, all comparison/preset/morph/diagnostic/live-tuning features work, HDR is protected, normal player gestures regress cleanly, no signing secrets are stored in source, and the final release-candidate matrix is recorded as passing.
