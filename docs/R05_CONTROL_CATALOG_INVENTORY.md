# R05 Shader Lab typed control catalog inventory

## Purpose

R05 moves Shader Lab control metadata out of the legacy monolithic Android bridge/Lua UI model into an authoritative typed domain catalog at:

`app/src/main/java/app/marlboroadvance/mpvex/repository/shaderlab/catalog/ShaderLabControlCatalog.kt`

R05 is metadata/domain-model work only. It does **not** implement the R06 semantic command API, MPV transport, or UI.

## Legacy sources compared

- `agent/native-shader-lab` — `ShaderLabBridge.kt` value-control list.
- Normalized v6.1.1 Lua source — `app/src/main/assets/mpvlab/source/scripts/pixel9-shader-lab.lua`.

The legacy Android bridge contains **53 value-bearing controls**. The Lua workstation contains those same value controls plus **10 action-only items**. R05 accounts for both sets explicitly.

## Typed domain model

R05 introduces:

- `ShaderLabControlId`
- `ShaderLabGroup`
- `ShaderLabControlKind`
- `ShaderLabControlSpec`
- `ShaderLabStepMode`
- `ShaderLabPresetId`
- `ShaderLabActionId` / `ShaderLabActionSpec`
- `ShaderLabControlRelationship`
- `ShaderLabBuiltInPreset`
- `ShaderLabControlCatalog`

The catalog owns min/max/default values, fine/normal/coarse steps, decimal formatting, integer/percent semantics, choices, preset eligibility, group membership, and legacy key compatibility.

## Value-control parity

The typed catalog contains all **53** legacy Android bridge controls with no omissions:

- Diagnostic/control: `SHADER_PROOF`, `TOUCH_GRANULARITY`.
- Masters: `LUMA_MASTER`, `CHROMA_MASTER`.
- MPV: `sdr-intensity`, `brightness`, `contrast`, `gamma`, `saturation`, `hue`.
- Luma: `LUMA_PIVOT`, `LUMA_CONTRAST`, `LUMA_HIGHLIGHT_START`, `LUMA_HIGHLIGHT_END`, `LUMA_HIGHLIGHT`.
- Chroma gates: `SAT_L_FLOOR`, `SAT_GATE_START`, `SAT_GATE_FULL`, `SHADOW_GATE_START`, `SHADOW_GATE_FULL`.
- Color volume: `MIDTONE_START`, `MIDTONE_FULL`, `MIDTONE_FADE_START`, `MIDTONE_FADE_END`, `BRIGHT_START`, `BRIGHT_FULL`, `BASE_CHROMA`, `MID_CHROMA`, `BRIGHT_CHROMA`.
- Skin: `SKIN_RETAIN`, `SKIN_CENTER`, `SKIN_HUE_INNER`, `SKIN_HUE_OUTER`, `SKIN_L_LOW_START`, `SKIN_L_LOW_FULL`, `SKIN_L_HIGH_START`, `SKIN_L_HIGH_END`, `SKIN_C_LOW_START`, `SKIN_C_LOW_FULL`, `SKIN_C_HIGH_START`, `SKIN_C_HIGH_END`.
- Gamut: `RGB_LOW`, `RGB_HIGH`, `GAMUT_MARGIN`, `GAMUT_ITERATIONS`.
- Output/view: `SDR_COMPRESS`, `DEBUG_VIEW`, `GRAPH_VIEW`.
- Preset/morph selectors: `USER_SLOT`, `BUILTIN_SLOT`, `MORPH_FROM`, `MORPH_TO`, `MORPH_AMOUNT`.

Automated tests compare the exact legacy-key set, not only the count.

## Action-only Lua entries

The following **10** Lua workstation entries are deliberately represented as typed `ShaderLabActionSpec` metadata rather than pretending they are numeric controls:

- `BYPASS_ACTION`
- `PREVIEW_ACTION`
- `LOAD_USER`
- `SAVE_USER`
- `CLEAR_USER`
- `LOAD_BUILTIN`
- `REVERT_VIDEO_START`
- `RESET_ALL_MENU`
- `SAVE_STATE_MENU`
- `LOAD_STATE_MENU`

Their execution is intentionally deferred to **R06**, which owns the semantic input-neutral command API. This is an explained architectural split, not an omission.

## Proven relationships encoded as data

The normalized Lua engine enforces 12 ordered edge pairs with a `0.000001` minimum gap. R05 represents all 12 as `ShaderLabControlRelationship.OrderedPair`:

1. `LUMA_HIGHLIGHT_START < LUMA_HIGHLIGHT_END`
2. `SAT_GATE_START < SAT_GATE_FULL`
3. `SHADOW_GATE_START < SHADOW_GATE_FULL`
4. `MIDTONE_START < MIDTONE_FULL`
5. `MIDTONE_FADE_START < MIDTONE_FADE_END`
6. `BRIGHT_START < BRIGHT_FULL`
7. `SKIN_HUE_INNER < SKIN_HUE_OUTER`
8. `SKIN_L_LOW_START < SKIN_L_LOW_FULL`
9. `SKIN_L_HIGH_START < SKIN_L_HIGH_END`
10. `SKIN_C_LOW_START < SKIN_C_LOW_FULL`
11. `SKIN_C_HIGH_START < SKIN_C_HIGH_END`
12. `RGB_LOW < RGB_HIGH`

The proven virtual-master behavior is represented as five `ScaledBy` relationships:

- `LUMA_CONTRAST × LUMA_MASTER`
- `LUMA_HIGHLIGHT × LUMA_MASTER`
- `BASE_CHROMA × CHROMA_MASTER`
- `MID_CHROMA × CHROMA_MASTER`
- `BRIGHT_CHROMA × CHROMA_MASTER`

The generic catalog normalization/effective-value helpers consume these relationships; control-specific ordering/scaling is not scattered through UI code.

## Presets

`ShaderLabPresetId` provides bounded typed IDs for 10 user slots and 10 built-in slots plus `VideoStart`.

The 10 legacy built-in names are preserved exactly:

1. V3.1 Reference
2. Natural Plus
3. Vivid Clean
4. Cinema
5. Daylight Punch
6. Dark Room
7. Animation
8. Skin Priority
9. Highlight Pop
10. SDR Safe

## Validation target

`ShaderLabControlCatalogTest` verifies:

- exact 53-key legacy value-control parity;
- exact 10-key legacy action parity;
- clamping and integer behavior;
- fine/normal/coarse step sizes;
- high-precision constants and formatting;
- all 12 ordering relationships and changed-control-wins behavior;
- all 5 virtual-master scaling relationships;
- typed preset bounds and built-in names;
- preset eligibility of controller/diagnostic fields.

No current clean-branch UI owns an alternate canonical min/max/default/step table; later UI work is required to consume this catalog rather than reintroduce duplicated metadata.
