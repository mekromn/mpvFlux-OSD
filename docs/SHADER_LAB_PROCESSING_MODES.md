# Shader Lab Processing Modes — Future Architecture Reference

## Status

**Future architectural requirement.**

This document records the processing-mode requirement so R08/R09/R10 work can preserve a clean path to it. It is not authorization to skip the active roadmap step or destabilize the proven R07/R08 renderer parity work.

## User requirement

Shader Lab will support directly selectable processing modes, initially exposed conceptually as:

- **Mode A**
- **Mode B**
- **Mode C**
- **Mode D**

Tapping a mode button must:

1. select that mode's processing pipeline;
2. immediately apply that pipeline to the current video;
3. switch the workstation controls to the controls relevant to that mode;
4. restore that mode's own current parameter values/state;
5. never leave the UI showing controls for one mode while the renderer is executing another.

Mode switching is therefore a first-class semantic operation, not a preset load and not a cosmetic UI tab change.

## Mode A definition

**Mode A is the current Shader Lab processing family.**

Its reference pipeline is the high-fidelity math currently being preserved in the resident R08 shader architecture, including the existing perceptual expansion behavior and controls. The proven visual baseline remains the project reference until a future mode is deliberately compared and accepted on-device.

Mode A must not be silently changed merely because another experimental pipeline exists. Its behavior should remain versionable/reproducible so A/B/C/D comparisons are meaningful.

## Future modes B/C/D

Modes B, C, and D are reserved for alternative processing pipelines. They may contain different or improved mathematical approaches intended to exceed Mode A in fidelity or other image-quality dimensions.

A future mode is allowed to differ from Mode A in, for example:

- luminance-expansion math;
- chroma/color-volume expansion math;
- gamut-boundary handling;
- hue-preservation strategy;
- skin-tone protection logic;
- highlight/black behavior;
- perceptual color model;
- clipping/compression strategy;
- ordering or topology of processing stages;
- diagnostics relevant to that pipeline;
- the set, ranges, defaults, and semantics of exposed controls.

A mode should not inherit a control merely because another mode has it. The visible control surface must be derived from the active mode's declared control schema.

## Core architectural rule

A processing mode consists of at least:

```text
ProcessingMode
  +-- stable mode ID (A/B/C/D/...)
  +-- display label
  +-- pipeline implementation/version
  +-- ordered processing topology
  +-- mode-specific control schema
  +-- mode-specific default values
  +-- mode-specific current value bank
  +-- supported diagnostics
  +-- compatibility/capability metadata
  +-- renderer transport mapping
```

The mode ID is semantic and stable. The pipeline implementation behind a mode must also be versioned so a future revision does not make saved comparisons or presets ambiguous.

## Independent mode state

Each mode owns an independent parameter bank.

Example:

```text
Mode A state -> A controls + A values
Mode B state -> B controls + B values
Mode C state -> C controls + C values
Mode D state -> D controls + D values
```

Switching from A to B must not destroy A's tuning. Returning to A should restore the last valid A state immediately unless the user explicitly requests reset/default behavior.

Mode-specific presets should therefore record at minimum:

- mode ID;
- pipeline/schema version;
- values applicable to that mode;
- any shared/global renderer properties that the preset intentionally owns.

Cross-mode preset migration must be explicit. Values with superficially similar names must not be copied between mathematically different modes unless a defined migration/mapping exists.

## Shared controls versus mode controls

The architecture should distinguish:

### Global/shared renderer controls

Controls that truly have identical semantics across processing modes may remain globally addressable, such as selected direct mpv properties where their meaning is unchanged.

### Mode-local controls

Math-specific shader controls belong to the mode that defines them. Their IDs, ranges, step sizes, constraints, defaults, and transport mappings come from that mode's schema.

The UI must render the union only where explicitly intended. It must not expose irrelevant Mode A controls while Mode B is active.

## Atomic mode switching

Mode switching must be treated as one semantic transaction:

```text
user taps mode
  -> validate target mode
  -> resolve target mode state
  -> prepare renderer transition
  -> activate target pipeline + complete target values
  -> publish active-mode state
  -> UI projects target control schema/value bank
```

The renderer must never intentionally display a frame with:

- the target pipeline and the previous mode's parameter bank;
- the previous pipeline and the target mode's controls/state presented as active;
- a partially published parameter set.

If activation fails, the previous mode remains authoritative and the UI must report the failure rather than claiming the new mode is active.

## Instant-apply requirement

A mode button is expected to feel immediate.

Normal value tuning inside a mode remains on the R08 resident-parameter path and must not regenerate shader source.

Mode changes are less frequent than slider changes and may legitimately require a structural renderer transition when two modes use genuinely different shader topology. However, future implementation should minimize visible stalls by preferring one of these strategies when fidelity permits:

1. **Resident multi-mode topology** — multiple mode kernels/passes remain available and a typed mode parameter selects the active path.
2. **Conditional resident passes** — mode-dependent passes are conditionally enabled while shader source remains stable.
3. **Prevalidated pipeline swap** — if pipelines must be separate shader programs, prepare/validate the complete target pipeline and state before replacing the active one.

The choice is subordinate to fidelity. Do not merge mathematically different pipelines into a compromised common shader merely to make switching cheaper.

## Relationship to planned upstream PARAM capabilities

The already identified future mpv PARAM improvements are especially relevant to this design:

- ENUM-style parameters can represent discrete processing-mode or algorithm selections cleanly.
- PARAM values usable by RPN/WHEN/WIDTH/HEIGHT expressions can enable/disable structural passes at neutral/default states and can support mode-dependent resident topology.

These capabilities remain future work until R08 renderer parity is restored and accepted. They must not be pulled forward merely to implement this document prematurely.

## UI behavior

The future workstation should expose a persistent, high-visibility mode selector.

Minimum behavior:

- one tap selects A/B/C/D;
- selected mode is unmistakably indicated;
- controls below the selector are rebuilt/projected from that mode's schema;
- values shown are that mode's own current values;
- switching back restores the previous mode state;
- touch and Android TV/D-pad invoke the same semantic `SelectProcessingMode` operation;
- mode switching is compatible with bypass/original-preview so comparisons remain unambiguous.

The UI must not encode mode-specific math directly. It renders the active mode descriptor/control schema supplied by the authoritative state layer.

## Comparison and fidelity testing

Modes exist partly to compare alternative math. Therefore future validation should make A/B/C/D comparison reproducible.

For each mode record:

- pipeline version/hash;
- complete active parameter state;
- mpv/libplacebo/native renderer fingerprint;
- input classification and relevant video metadata;
- output diagnostics where available;
- Pixel 9 Pro XL visual/behavioral acceptance notes.

A future mode should not replace Mode A solely from synthetic metrics. Replacement/promotion requires deliberate real-device visual evaluation against the proven baseline.

## Interaction with bypass and original preview

These concepts are separate:

- **Mode A/B/C/D** selects a processing algorithm family.
- **Bypass** temporarily disables Shader Lab processing while retaining the selected mode/state.
- **Hold-to-preview original** temporarily presents the unprocessed comparison for the duration of the press lifecycle.

Releasing preview or bypassing/unbypassing must return to the exact active mode and parameter state; it must not reset the mode.

## Persistence

Persistent state should eventually include:

```text
activeModeId
modeState[A]
modeState[B]
modeState[C]
modeState[D]
modeSchemaVersion[*]
```

Missing future modes must be tolerated when reading older state. Unknown/newer mode schemas must fail safely without corrupting states belonging to other modes.

## R08 boundary

R08 remains focused on exact renderer parity and the resident PARAM architecture for the current Shader Lab pipeline.

For R08:

- treat current pipeline as future **Mode A**;
- avoid hard-coding future UI/state APIs in a way that assumes only one processing pipeline can ever exist;
- do **not** add B/C/D renderer implementations yet;
- do **not** change Mode A math as part of mode scaffolding;
- do **not** disturb the frozen R07 renderer stack while parity is unresolved.

## Future implementation direction

When the roadmap reaches the appropriate state/UI architecture stage, prefer a typed semantic surface along these lines:

```text
ProcessingModeId
ProcessingModeDescriptor
ProcessingModeState
ProcessingModeRegistry
SelectProcessingMode(modeId)
```

The authoritative ViewModel/repository state should expose the active descriptor and control projection. Rendering transport is selected by the descriptor rather than by UI conditionals.

This keeps mode selection compatible with touch, TV/D-pad, presets, diagnostics, testing, and future Mode E+ additions without redesigning the workstation again.

## Acceptance criteria for eventual mode implementation

- Tapping A/B/C/D changes the active processing pipeline immediately.
- UI controls change to exactly the target mode's declared controls.
- Each mode retains/restores its own values independently.
- No transient mixed-pipeline/mixed-parameter state is observable.
- Failed mode activation rolls back atomically.
- Bypass/original-preview return to the exact selected mode and state.
- TV/D-pad and touch use the same semantic mode command.
- Saved state/presets identify the mode and schema/pipeline version.
- Mode A remains reproducible as the historical/current Shader Lab reference.
- Alternative modes can use materially different math/topology without contaminating Mode A.
- Pixel 9 Pro XL comparisons record renderer fingerprint and mode version so fidelity judgments are reproducible.
