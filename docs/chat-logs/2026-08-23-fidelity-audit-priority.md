# 2026-08-23 — R08 Fidelity Audit Priority Checkpoint

## Active roadmap state

- Branch: `agent/upstream-refactor`
- Roadmap pointer: `R08`
- R08 status: `IN_PROGRESS`
- No R09 work is authorized by this checkpoint.
- Full audit: `docs/CHROVELO_FIDELITY_AUDIT.md`
- Fidelity-audit commit: `2513c9898585d8adfe3248698fa7fafb0566f632`

## Device result that changed immediate priority

Pixel 9 Pro XL / Android 16 device test on the R08 resident Shader Lab path showed:

- Shader Lab opens and remains usable after the Lab-open stability repair.
- HUD reports bridge `LIVE`.
- source: SDR / `bt.1886`.
- VO: `gpu`.
- resident shader: `ATTACHED`.
- `PARAM opts`: `40`.
- temporary HUD frame/decoder drops: `0 / 0` at the observed moment.
- `LUMA_MASTER` and `CHROMA_MASTER` values are present in the option string.
- Moving Shader Lab sliders still produces **no visible rendered-pixel change**.

This means property/option transport evidence is not sufficient for R08 acceptance. The actual executing renderer still has an unresolved parameter-consumption failure or equivalent rendered-output blocker.

## Stability repair immediately before this checkpoint

The Lab-open instability was traced to recovery/debug work in the synchronous UI click path:

- `64da58635cc34e4c663811978fbce7ec0d2b674d` — Lab button changed to UI-only; removed synchronous bridge reattach/observer replacement/resident reload from the click path.
- `93f0383bacfcc73838b3874083cae0c0b3d79689` — temporary Stats HUD made opt-in and its synchronous JNI/mpv reads moved off the Compose UI thread.

A concrete lock-order deadlock had existed between Shader Lab's `commandLock` and MPVLib's observer lock during repeated synchronous attach. This was an architectural hot-path problem, not a shader-math problem.

## User-directed priority override

The user explicitly directed:

> Sliders do nothing. We need to [do] the fidelity robbers audit first.

Therefore the next work is **not** another speculative R08 live-PARAM patch. The project must first audit and then systematically remove/prove fidelity robbers throughout the playback pipeline.

## Audit result

`docs/CHROVELO_FIDELITY_AUDIT.md` was created and committed to source. It contains:

- 45 ranked findings (`F001` through `F045`);
- severity/confidence classification;
- exact current-source evidence;
- destructive conversions and hidden processing;
- Android/Vulkan output capability/metadata gaps;
- temporal/cadence fidelity gaps;
- source/working-space shader assumptions;
- decode and native-binary risks;
- narrow post-cutoff mpv correctness candidates;
- seven maximum-fidelity acceptance gates;
- explicit invariants that must not be changed blindly;
- a remediation order that precedes further visual tuning.

Highest-priority confirmed issues include:

1. optional destructive `vf=format=yuv420p` conversion;
2. inherited `fast` mpv profile silently disabling HDR peak analysis despite later scaler overrides;
3. GPU debanding hard-enabled even when the user preference says `None`;
4. no explicit/observable Android output colorspace/dataspace contract;
5. no explicit content-frame-rate matching/presentation contract;
6. resident shader hard-coded to Rec.709/Oklab assumptions for all SDR sources;
7. premature flooring of negative linear-light reconstruction values;
8. premature clamp of SDR working luminance to `[0,1]`.

## Repository/upstream check before audit

No source integration was required before the audit:

- fork `master`: `83e2b2f64c48abbdc1125cff626cfcbae230bfde`;
- immediate upstream `Muhammedahmed18/mpvFlux`: `f2ed0153134925ad492744ea5330578194ec76a3`.

The renderer remains based on the R07 mpv cutoff `d54bad5636924ab3f39cb6e397b94b6aa8a7c433`.

## Narrow upstream correctness candidates recorded by the audit

Do not wholesale-modernize the renderer. Candidates to evaluate individually after parity/reference instrumentation include:

- `8d04be2b856c9330178ddd6ab20848c58fada3af` — force image stride to a multiple of bytes-per-pixel; Vulkan texel-size correctness fix.
- `c1a21bb8d9db238054b7029c29d9e319889aad04` — reject invalid user-shader component overwrite.
- `702abfd587b3911d6dbda05a987b015ea9896bae` — use physical rather than logical hwdec texture dimensions for affected polar-scaler paths; applicability to the current Android path must be proven first.

## Next concrete execution order

1. Use `docs/CHROVELO_FIDELITY_AUDIT.md` as the live checklist.
2. Remove/prove the P0 destructive/silent baseline issues first, beginning with F001/F002/F003 and renderer-option ownership F016.
3. Add final Android/output and source/decode telemetry before changing unproven output assumptions.
4. Establish frame-rate/cadence truth.
5. Repair shader working-domain assumptions before doing further perceptual look tuning.
6. Evaluate optional processing scientifically rather than assuming upstream/default behavior is ideal.
7. Resume the R08 live-PARAM renderer-consumption repair only after the reference pipeline is sufficiently observable to distinguish a correct visual change from a hidden conversion, gamut error, or cadence regression.

## Acceptance rule retained

A property/readback `PASS` is never sufficient if the rendered picture disagrees. R08 remains blocked until a real Pixel test shows immediate visible parameter response **without** shader-list churn/reload and without introducing a fidelity/cadence regression.
