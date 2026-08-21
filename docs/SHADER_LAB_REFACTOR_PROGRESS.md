# Shader Lab Refactor Progress

This file is the execution/status companion to `SHADER_LAB_REFACTOR_ROADMAP.md` and records the latest execution state.

## Current execution state

- `CURRENT_STEP = R08`
- `R01_STATUS = DONE`
- `R02_STATUS = DONE`
- `R03_STATUS = DONE`
- `R04_STATUS = DONE`
- `R05_STATUS = DONE`
- `R06_STATUS = DONE`
- `R07_STATUS = DONE`
- `R08_STATUS = IN_PROGRESS`
- R07 closed on a real Pixel 9 Pro XL / Android 16 synchronization smoke using State-3.
- The device produced a typed 53-control snapshot with `status=PASS`, backend `6.1.1-r07-state-3`, positive serial, SDR classification, and no backend error.
- R08 was deliberately redefined before old file-reload implementation landed: ordinary tuning now targets a resident `vo=gpu` shader with native tunable parameters.
- Current bundled mpv `d54bad563...` (2026-02-25) predates upstream `vo=gpu` PARAM support `0d655fe...` (2026-04-17), and upstream's fixed 16-parameter table is too small for Shader Lab; R08 therefore begins with a narrowly scoped Chrovelo libmpv build/backport and a 64-parameter limit.

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

## R07 — Refactor MPV bridge and observable state transport

**Status:** `DONE`

### Implemented

- Added `MpvShaderLabBridge` as the concrete `ShaderLabCommandBackend` for R06.
- Added typed `StateFlow<ShaderLabBackendState>` and typed bridge events.
- Uses native MPV property observation for:
  - `user-data/p9lab/native-state` when the wrapper exposes the nested leaf;
  - top-level `user-data` as the Pixel-proven compatibility path for the same native-state envelope;
  - `video-params/gamma`;
  - all six live MPV-property Shader Lab controls.
- No infinite 200 ms UI polling loop exists in the new bridge.
- Added event-driven Lua state publication for readiness/version, source classification, bank, bypass, preview, shader slot/swaps, apply-busy, errors, all value controls, and user-preset occupancy.
- Added typed semantic command routing through `p9lab-native-*` Lua messages.
- Added visible device round-trip proof file: `/storage/emulated/0/mpv/logs/shaderlab-r07-bridge-sync.txt`.
- R04 engine version advanced through `6.1.1-source-r07-state-3` and manifest integrity remains verified.
- Added canonical controller activation: bridge initialization reconciles the R04 engine, reuses an existing native-state publisher when present, otherwise explicitly loads `/storage/emulated/0/mpv/scripts/pixel9-shader-lab.lua` and requests the state handshake.
- Engine-preparation/transport/backend failures surface through observable typed error state.
- R08 shader coalescing/debounce/rollback behavior was **not** implemented.

### R07 key commits

- bridge + fake transport/tests groundwork: earlier R07 commits on `agent/upstream-refactor`.
- `67cf5a49fb03d206879bba75572969c0086d147a` — event-driven native state transport + Lua wiring.
- `ce39417e45e01ca5d198a84b8bd4cc0470f88272` — ensure canonical controller activation; guarded full unit suite **PASS** before commit.
- `a7f98aefb53ae51a27d8bd9f0b2bfc0e0f700570` — documentation-only original validation trigger.
- `74371e19ca5efafefe1e85c202df429631b2080d` — State-2 user-data transport discriminator.
- `344abbcb101a982ca3fceec10d7b562879369b99` — State-3 top-level `user-data` compatibility repair; guarded manifest/full-unit validation **PASS**.
- `fb1e70b430476ab6a1255946c77e562217248ff2` — final State-3 signed-build/documentation trigger.

### R07 automated validation

Guarded source validation:

- Lua/transport guarded apply workflow: **PASS**.
- Engine manifest verifier: **PASS**.
- Full `:app:testStandardDebugUnitTest`: **PASS**.
- Initial canonical-controller activation attempt failed Koin type inference and was correctly prevented from committing by the guard.
- Corrected activation guarded workflow run #2: **PASS**, including full Shader Lab unit suite.

Final dedicated phone-test workflow:

- `Refactor Dev APK` run `#115`
- Run ID: `32435745934`
- Job ID: `96636529771`
- PR merge-test SHA: `f5e53f881c5cdc0e7ead521bf568f09e22554421`
- Branch head validated: `a7f98aefb53ae51a27d8bd9f0b2bfc0e0f700570`
- Unit tests: **PASS** — Gradle `BUILD SUCCESSFUL`.
- Signed Debug build: **PASS**.
- Package/version/signing certificate verification: **PASS**.
- APK Signature Scheme v2: **PASS**, one signer.
- signer SHA-256 remains `b582b2f37a1bfbf1089405941b20b184c104f35a0ba38068f8ffde74fd3965a2`.
- arm64 versionCode: `1787275042`.
- universal versionCode: `1787275040`.
- arm64 artifact ID: `9430776332`, artifact ZIP digest `sha256:cabf7845b7d41de3e1115b1fcba96054a131e5321d6aa638f911349ba682d6b2`.
- universal artifact ID: `9430776972`, artifact ZIP digest `sha256:7f58856c61fe7a1143695e22591a587e17ba16e634fb50d7a0bbf48fb83938da`.
- extracted Pixel arm64 APK SHA-256: `d34b5f54c32bef94cf34ad1d1fcd955eb90eab03a809194b8332d3002dad9b7d`.
- extracted Pixel arm64 APK size: `62200548` bytes.

Normal CI/CD:

- `CI/CD Build` run `#51`
- Run ID: `32435745914`
- Job ID: `96636527407`
- Gradle build: **PASS**.
- arm64/armeabi-v7a/universal/x86/x86_64 uploads: **PASS**.

### R07 State-2 diagnosis

The Pixel State-2 discriminator proved all of the following:

- the managed R07 Lua controller executed (`script=R07_STATE_2`);
- Lua successfully wrote and immediately read back `user-data/p9lab/lua-probe`;
- Android could read the tiny probe;
- Android could also read the top-level `user-data` map, and that JSON already contained the complete multiline `p9lab/native-state` envelope;
- direct Android `getPropertyString("user-data/p9lab/native-state")` still returned no snapshot.

That isolated the failure to nested multiline leaf-string exposure in the embedded Android MPVLib wrapper, not Lua activation, Lua user-data writes, the payload, or Android access to user-data generally.

### R07 State-3 repair and automated validation

State-3 keeps direct nested-property consumption when available and adds top-level `user-data` observation/readback plus native-state JSON extraction as the compatibility path. The temporary Lua probe was removed after diagnosis.

- State-3 repair commit: `344abbcb101a982ca3fceec10d7b562879369b99`.
- Engine version: `6.1.1-source-r07-state-3`.
- Backend version: `6.1.1-r07-state-3`.
- Guarded manifest integrity: **PASS**.
- Guarded full `:app:testStandardDebugUnitTest`: **PASS**.
- `Refactor Dev APK` run #159 / run ID `32443773015`, job `96659445388`: **PASS**.
  - PR merge-test SHA: `5f5d4db378ca06b0938bd28fb54e8af33b5fb9fc`.
  - unit tests: **PASS**.
  - signed Debug build: **PASS**.
  - package/version/signing verification: **PASS**.
  - arm64 versionCode: `1787283192`.
  - arm64 artifact ID: `9433417733`.
  - signer SHA-256: `b582b2f37a1bfbf1089405941b20b184c104f35a0ba38068f8ffde74fd3965a2`.
- `CI/CD Build` run #75 / run ID `32443773021`, job `96659489167`: **PASS**.

### R07 final Pixel acceptance

Pixel 9 Pro XL / Android 16 device smoke on the State-3 signed arm64 APK: **PASS**.

`/storage/emulated/0/mpv/logs/shaderlab-r07-bridge-sync.txt` reported:

```text
status=PASS
stage=snapshot_received
backend_version=6.1.1-r07-state-3
snapshot_serial=2
source_gamma=bt.1886
source_kind=SDR
sdr_eligible=true
active_bank=B
bypassed=false
preview_original=false
shader_slot=A
shader_swaps=0
apply_busy=false
control_count=53
user_preset_count=0
last_error=
```

Acceptance result:

- Lua controller activation: **PASS**.
- Lua -> mpv user-data publication: **PASS**.
- Android bridge state consumption: **PASS** through the State-3 compatibility path.
- Typed snapshot decode: **PASS**.
- SDR source classification: **PASS**.
- 53-control catalog synchronization: **PASS**.
- backend error field: **CLEAR**.

**R07 COMPLETE.**

## R08 architecture pivot / execution start

**Status:** `IN_PROGRESS`

The old R08 file-regeneration/coalescing plan was superseded before app/shader implementation landed. Temporary scaffolding from that abandoned attempt was removed and the feature branch returned to the clean R07 closeout before this pivot.

New R08 target:

- resident `vo=gpu` Pixel shader;
- live `//!PARAM` uniforms through `glsl-shader-opts`;
- native R07 bridge owns parameter transport;
- no GLSL file generation or A/B shader swap during ordinary adjustment;
- exact V3.1 rendering math and Pixel expanded-brightness behavior preserved.

Native prerequisite discovered during replan:

- installed/bundled mpv source commit: `d54bad5636924ab3f39cb6e397b94b6aa8a7c433`, dated 2026-02-25;
- upstream `vo=gpu` tunable-PARAM support: `0d655fe66590009e1d77a17581257d677286531a`, dated 2026-04-17;
- upstream `SHADER_MAX_PARAMS`: 16; Chrovelo target: 64.

Implementation begins by producing and validating the narrowly scoped Android libmpv prerequisite, then converting the shader/bridge to resident parameter transport. Detailed design is in `docs/R08_RESIDENT_GPU_PARAMETER_ARCHITECTURE.md`.

`CURRENT_STEP` remains R08 until the new native/resident pipeline passes real Pixel validation. R09 is not started.