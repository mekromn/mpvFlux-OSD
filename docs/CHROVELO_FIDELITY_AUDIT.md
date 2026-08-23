# Chrovelo Maximum-Fidelity Audit

**Audit date:** 2026-08-23  
**Audited branch:** `agent/upstream-refactor`  
**Audited head at start:** `93f0383bacfcc73838b3874083cae0c0b3d79689`  
**Target:** Google Pixel 9 Pro XL / Android 16  
**Renderer invariant:** `vo=gpu` + Vulkan (`androidvk`)  
**Current roadmap step:** R08 remains `IN_PROGRESS`

## Why this audit exists

The goal is not to assume the inherited mpv/mpvFlux baseline is perfect. Chrovelo is intended to push playback fidelity to the practical limit of the target hardware while preserving the separately proven Pixel expanded-SDR brightness path. Any code, option, shader assumption, Android surface behavior, dependency pin, or hidden default that can unnecessarily alter, quantize, clip, resample, restrict, mis-tag, mistime, or obscure the final image is in scope.

A **fidelity robber** in this document means one of four things:

1. **Destructive:** irreversibly discards source information or produces a known-wrong representation.
2. **Silent processing:** changes pixels/cadence without being an explicit creative user choice.
3. **Capability ceiling:** prevents the pipeline from using fidelity the hardware could otherwise preserve.
4. **Unproven assumption:** may be correct, but is not measured/observable enough to trust in a maximum-fidelity build.

Explicit creative operations such as Crop, Stretch, manual Zoom, or a deliberately selected color preset are not automatically bugs. They become audit findings when they can stack invisibly, survive inconsistently, or contaminate a reference/maximum-fidelity path.

## Device state that triggered the priority change

The 2026-08-23 Pixel device test after Lab-open stability work showed:

- Shader Lab opens and reports `LIVE`.
- source: SDR / `bt.1886`;
- VO: `gpu`;
- resident shader: `ATTACHED`;
- `PARAM opts`: `40`;
- frame/decoder drops shown by the temporary HUD: `0 / 0`;
- changing Luma Master / Chroma Master still produces **no visible rendered-pixel change**.

That is an R08 transport/renderer-consumption blocker, but the user explicitly directed that the full fidelity audit be completed **before** further slider repair. R08 therefore remains active; no roadmap pointer advances because of this audit.

## Repository/upstream state checked before audit

- fork `master`: `83e2b2f64c48abbdc1125cff626cfcbae230bfde`;
- immediate upstream `Muhammedahmed18/mpvFlux`: `f2ed0153134925ad492744ea5330578194ec76a3`;
- neither advanced relative to the documented baseline;
- R07 mpv renderer cutoff remains `d54bad5636924ab3f39cb6e397b94b6aa8a7c433` (2026-02-25);
- R07 libplacebo remains `c93aa134ab62365ce1177efff99b8e1e66a818e7`;
- R07 FFmpeg remains `5ba2525c7affc29cbd99e6266946b382d3fffe8b`.

---

# Ranked findings

Severity is ordered `P0` (must address/prove first) through `P3` (lower-risk, diagnostic, or peripheral). `CONFIRMED` means the behavior is directly present in current source. `MEASURE` means the code exposes an unresolved fidelity ceiling that must be measured on the Pixel before changing it.

## P0 — destructive or reference-breaking

### F001 — Optional `vf=format=yuv420p` is a destructive conversion — **P0 / CONFIRMED**

**Evidence:** `app/src/main/java/app/marlboroadvance/mpvex/ui/player/MPVView.kt`

When `DecoderPreferences.useYUV420P` is enabled, Chrovelo installs `vf=format=yuv420p`.

**Why it robs fidelity:** this can force high-bit-depth, 4:2:2, 4:4:4, RGB, or otherwise richer decoded video through a 4:2:0 planar format. It is incompatible with a maximum-fidelity contract.

**Required action:** remove it from the maximum-fidelity path. If retained as a compatibility escape hatch, rename it as explicitly destructive, make it opt-in, and expose the negotiated before/after pixel formats.

### F002 — Default mpv `fast` profile leaks HDR-quality changes — **P0 / CONFIRMED**

**Evidence:**

- `app/src/main/java/app/marlboroadvance/mpvex/preferences/DecoderPreferences.kt` defaults `mpv_profile` to `fast`.
- `MPVView.kt` applies that profile before Chrovelo's individual overrides.
- exact pinned mpv `etc/builtin.conf` defines `[fast]` with `hdr-compute-peak=no` and `allow-delayed-peak-detect=yes` in addition to low-quality scaler/dither choices.

Chrovelo later overrides the scaler/dither subset, but it does **not** undo those HDR peak-analysis options.

**Why it robs fidelity:** a generic performance profile silently changes tone/HDR analysis in a branch whose stated purpose is image fidelity.

**Required action:** stop using `fast` as the specialized branch default. Build an explicit Chrovelo profile from audited options only, then verify every effective option at runtime.

### F003 — Debanding is hard-enabled even when the preference is `None` — **P0 / CONFIRMED**

**Evidence:**

- `DecoderPreferences.debanding` defaults to `Debanding.None`.
- both managed `mpv.conf` and `MPVView.initOptions()` set `deband=yes` plus fixed parameters.
- `MPVView.postInitOptions()` handles `Debanding.None` with an empty branch, so it does not turn the already-enabled GPU debander off.
- the settings UI can later issue `deband=no`, but fresh playback starts processed.

**Why it robs fidelity:** clean gradients/textures are modified despite the user/reference state saying no debanding.

**Required action:** one authoritative deband owner. `None` must mean bit-exactly no deband stage. Quality presets may opt into it explicitly or automatically only behind measurable artifact detection.

### F004 — Android output colorspace is not explicitly contracted — **P0 / CONFIRMED + MEASURE**

**Evidence:**

- `AndroidManifest.xml` does not request an explicit wide-gamut/HDR color mode for `PlayerActivity`.
- pinned mpv `video/out/vulkan/context_android.c` creates a generic `VkAndroidSurfaceKHR` with zeroed `ra_ctx_params` and reports `VO_NOTIMPL` for Android control.
- pinned `video/out/android_common.c` only wraps the Java `Surface` as an `ANativeWindow`; it does not set Android dataspace/primaries/HDR metadata.
- Chrovelo does not currently provide an app-side dataspace/output-colorspace contract.

**Why it robs fidelity:** the final compositor/output path is not guaranteed to represent the primaries/TRC the renderer thinks it is targeting. Wide-gamut capability can be left unused or silently color-managed through an unintended space.

**Required action:** establish and instrument the Android output contract first; do not assume `rgba16f` implies a wide-gamut output surface.

### F005 — No explicit content-frame-rate matching path — **P0 temporal / CONFIRMED**

**Evidence:**

- no `Surface.setFrameRate` / equivalent content-frame-rate request is present in the player lifecycle inspected here;
- no preferred display-mode selection is present;
- no Chrovelo `video-sync`/cadence policy is defined in the managed config;
- pinned `androidvk` initializes the Vulkan swapchain with `VK_PRESENT_MODE_FIFO_KHR` and its Android control path is `VO_NOTIMPL`.

**Why it robs fidelity:** perfect pixels displayed at the wrong cadence are not faithful playback. 23.976/24/25/30/50/60 fps material can be presented with avoidable cadence judder or duplicate/drop patterns if Android chooses an incompatible display rate.

**Required action:** add an Android-16-aware frame-rate policy and telemetry. Verify actual content FPS, display refresh, requested frame rate, measured vsync cadence, and zero avoidable frame drops.

### F006 — Resident shader assumes Rec.709 as the universal SDR working/source gamut — **P0 / CONFIRMED**

**Evidence:** `pixel9-perceptual-expansion-resident-v3.1.glsl`

- hard-coded Rec.709 luma coefficients `vec3(0.2126, 0.7152, 0.0722)`;
- hard-coded linear-sRGB/Rec.709 <-> Oklab matrices;
- no branch on `video-params/primaries` or source matrix/primaries.

**Why it robs fidelity:** SDR is not synonymous with Rec.709. Wide-gamut SDR and non-709 inputs will be interpreted with the wrong primaries/luma model before the perceptual transform.

**Required action:** make the shader working space explicit. Convert source -> chosen linear working space using metadata before perceptual math, then working space -> target. Do not bake source=709 into the transform.

### F007 — Shader destroys signed linear-light reconstruction values — **P0 / CONFIRMED**

**Evidence:** resident shader begins the processing path with `vec3 rgb = max(src.rgb, vec3(0.0));`.

**Why it robs fidelity:** high-quality reconstruction kernels can legitimately produce small negative linear-light lobes/undershoot. Hard-zeroing them before luminance/Oklab math changes edge energy, hue relationships and ringing behavior rather than handling the signed intermediate deliberately.

**Required action:** retain signed working values through the stages that can support them. Apply a deliberate gamut/output constraint at the correct final boundary instead of an unconditional pre-transform floor.

### F008 — Shader hard-clamps SDR working luminance to `[0,1]` — **P0 / CONFIRMED**

**Evidence:** `expand_luminance()` begins with `y = clamp(y, 0.0, 1.0)` and returns another `[0,1]` clamp.

**Why it robs fidelity:** extended-range/superwhite SDR or reconstruction overshoot above nominal white is collapsed before the output/display mapping has a chance to decide what to do with it. This also makes the shader incapable of representing legitimate scene/output headroom in its own domain.

**Required action:** define nominal white separately from mathematical headroom. Preserve extended values through the working transform and compress/clip only at a measured target boundary.

## P1 — direct quality/capability risks

### F009 — Always-on deband grain injects noise — **P1 / CONFIRMED**

`deband-grain=8` is hard-coded in both `MPVView.kt` and managed `mpv.conf`. Even when grain masks banding, it is added information and must not be an unconditional reference default.

### F010 — Sigmoid upscaling is an always-on nonlinear image transform — **P1 / CONFIRMED**

`sigmoid-upscaling=yes` is hard-coded. It can reduce ringing but deliberately changes interpolation behavior/local contrast. Keep it as an evaluated quality mode, not an unquestioned definition of fidelity.

### F011 — Final swapchain precision is unknown; `rgba16f` proves only the intermediate FBO — **P1 / MEASURE**

Pinned Vulkan context creates the libplacebo swapchain without an explicit surface format in the Android wrapper. The actual Pixel swapchain format/bit depth is not exposed in Chrovelo telemetry.

**Required action:** log the chosen Vulkan surface format, color space, bits/channel, Android dataspace and compositor mode on device.

### F012 — `target-peak=auto` is unverified against the actual Pixel luminance state — **P1 / MEASURE**

Auto can be appropriate, but maximum fidelity requires proving what value is actually selected in Natural/Adaptive display modes, SDR/HDR, different system brightness levels and thermal states.

### F013 — `target-colorspace-hint=no` sits on top of an Android backend with no explicit color signaling — **P1 / MEASURE**

This setting is part of the empirically working expanded-brightness path and must **not** be casually changed. However, the combined pipeline currently has no proven output-dataspace contract. Preserve it until controlled A/B tests show how Android 16 responds.

### F014 — `mediacodec` direct fallback can silently change the validated render path — **P1 / CONFIRMED**

Kotlin config uses `hwdec=mediacodec-copy,mediacodec,no`, while the known-good contract was established primarily with `mediacodec-copy`.

**Required action:** if copy fails, surface the fallback in telemetry and validate the same shader/color/output path before accepting direct MediaCodec as fidelity-equivalent.

### F015 — `hwdec-codecs=all` has no per-format fidelity qualification — **P1 / CONFIRMED**

Hardware decode is allowed broadly without a Chrovelo capability matrix for codec/profile/bit depth/chroma/metadata preservation.

**Required action:** verify codec-by-codec output format and metadata; allow software fallback where the hardware path loses information or produces known driver errors.

### F016 — Renderer/config options have two owners with already-visible drift — **P1 / CONFIRMED**

The same critical options are written in managed `mpv.conf` and again in `MPVView.kt`. They are already not identical (`hwdec` is one example). Split ownership makes the effective pipeline depend on precedence and future edits can silently diverge.

**Required action:** one authoritative generated profile/source of truth, plus effective-option dump at playback start.

### F017 — Conventional mpv image controls can stack with Shader Lab — **P1 / CONFIRMED**

The Video Filters panel and Filter Presets write `brightness`, `contrast`, `gamma`, `saturation`, `hue`, and `sharpen` directly while the Shader Lab processing model expects ordinary mpv image controls to be neutral unless deliberately used.

**Required action:** define processing ownership. Reference/max-fidelity mode must expose every active transform and offer an atomic neutral/bypass state.

### F018 — Persisted filter UI state can disagree with the renderer after restart — **P1 / CONFIRMED**

Filter values are stored in `DecoderPreferences`, but `MPVView.initOptions()` forcibly initializes the corresponding mpv picture controls to zero. The UI can therefore display a remembered processed value while the current renderer starts neutral, until controls are touched again.

**Required action:** either restore them intentionally and visibly or reset the preference state. Never have UI state claim a transform that is not active.

### F019 — Window brightness can override display calibration state — **P1 / CONFIRMED**

`PlayerViewModel.changeBrightnessTo()` writes `WindowManager.LayoutParams.screenBrightness`; remembered brightness may be reapplied by `PlayerActivity`.

**Why it matters:** the Pixel's panel luminance, HDR headroom, tone-mapping behavior and perceived black/white relationship are display-state dependent. A gesture can therefore change the viewing/calibration condition independently of renderer settings.

**Required action:** record window/system brightness in fidelity telemetry; reference tests need a locked, documented display state.

### F020 — Gamut limiter confines expansion to the Rec.709 RGB cube — **P1 / CONFIRMED**

`find_gamut_chroma_scale()` converts Oklab back through the hard-coded Rec.709 matrices and defines in-gamut as each Rec.709 RGB component within `RGB_LOW..RGB_HIGH`.

**Why it robs capability:** this prevents the perceptual expansion stage from deliberately using Pixel wide-gamut color volume beyond Rec.709 primaries, even though that is a stated future goal.

**Required action:** make gamut limiting target-aware. Boundary search should use the actual target gamut/display mapping, not a fixed source-gamut cube.

### F021 — Boundary safety constants shave available gamut/white — **P1/P2 / CONFIRMED**

`RGB_HIGH=0.99995`, `RGB_LOW=0.00005`, and `GAMUT_MARGIN=0.997` deliberately retreat from exact boundaries. This is small but systematic and should be justified by numerical/driver evidence rather than folklore.

### F022 — Luminance model is not source-primaries aware — **P1 / CONFIRMED**

The same Rec.709 luminance vector drives expansion for every SDR input. This is distinct from the Oklab matrix issue: even if a different source gamut survives decoding, target Y is still wrong for non-709 primaries.

### F023 — SDR/HDR classification is transfer-function-only — **P1/P2 / CONFIRMED**

Managed config activates the SDR profile for any non-empty gamma other than `pq`/`hlg`; bridge classification similarly treats non-PQ/HLG values as SDR.

**Required action:** classification should preserve UNKNOWN/unsupported transfer states and include enough source metadata to avoid applying expansion on an inadequately characterized input.

### F024 — Pixel Natural vs Adaptive display state is not part of the render contract — **P1 / MEASURE**

The project already established that Pixel Adaptive is not a simple saturation multiplier. Chrovelo currently has no runtime/test contract recording which display mode is active. A future Adaptive-style shader can therefore double-process an already enhanced display mode unless tests explicitly control this external state.

### F025 — Android HDR/wide-color headroom is not explicitly requested or measured — **P1 / MEASURE**

The app currently relies on the empirically observed `vo=gpu` path to trigger expanded brightness. There is no explicit Android-side headroom/dataspace telemetry proving when the compositor/panel grants HDR or extended luminance headroom.

**Required action:** instrument first; only then evaluate Android 15/16 HDR-headroom APIs or color modes without risking the known brightness behavior.

## P2 — quality risks, observability gaps, and conditional paths

### F026 — `rgba16f` may be a precision ceiling relative to the hardware — **P2 / MEASURE**

Half-float is excellent for mobile rendering, but it is not mathematically lossless. Chrovelo's goal is hardware-limit fidelity, so `rgba16f` vs a feasible higher-precision path must be measured with stress gradients, repeated color transforms and error statistics rather than assumed sufficient.

### F027 — Sharp EWA scaling plus no explicit antiring policy is unbenchmarked — **P2 / MEASURE**

`ewa_lanczossharp` is intentionally sharp and can overshoot; the shader currently floors negative values. That combination must be evaluated together. A scaler cannot be ranked in isolation from the downstream clipping behavior.

### F028 — Dither algorithm/depth is not tied to measured final output depth — **P2 / MEASURE**

`dither=fruit` and `dither-depth=auto` are forced. Dither is valuable when reducing precision, but gratuitous noise is not. Verify the actual swapchain depth and whether the renderer's auto depth matches it.

### F029 — Linear/sigmoid scaling choices cannot be atomically bypassed for reference comparison — **P2 / CONFIRMED**

The Shader Lab original/tuned comparison is shader-oriented; it does not currently define a whole-pipeline reference state that also neutralizes optional deband/sigmoid/filter processing.

### F030 — `vd-lavc-film-grain=cpu` is unconditional — **P2 / MEASURE**

Correct film-grain synthesis can improve fidelity for codecs that carry grain metadata, but forcing a CPU policy globally may affect decode load/cadence and is not proven optimal for every supported codec/hwdec path.

### F031 — CPU debanding adds a separate `gradfun` filter path — **P2 / CONFIRMED**

When selected, Chrovelo adds `@deband:gradfun=radius=12`. Any libavfilter/CPU-filter path must be audited for intermediate pixel format/bit-depth negotiation so a quality feature does not force a lower-precision conversion.

### F032 — Creative filter presets have no explicit reference-mode lockout — **P2 / CONFIRMED**

Presets intentionally alter pixels. The missing piece is a global processing graph/status that makes it impossible to mistake a creative preset for the maximum-fidelity baseline.

### F033 — Geometric transforms are not included in a whole-pipeline reference state — **P2 / CONFIRMED**

Crop (`panscan=1`), Stretch (`video-aspect-override`), custom aspect and `video-zoom` are explicit features, but fidelity validation needs a one-touch canonical geometry state: source aspect, zero panscan, zero zoom/pan, no hidden persistence.

### F034 — Source color/decode metadata is under-observed — **P2 / CONFIRMED**

Current Lab HUD shows gamma and a few renderer properties, but a fidelity proof needs at minimum source pixel format, hardware pixel format, bit depth, chroma subsampling/location, color matrix, levels/range, primaries, transfer, mastering metadata, signal peak and rotation/aspect.

### F035 — Final output metadata is under-observed — **P2 / CONFIRMED**

Chrovelo does not currently surface the actual Vulkan swapchain format, Android dataspace/color mode, compositor color mode, output primaries/TRC, bits/channel or HDR headroom state.

### F036 — Temporal fidelity is under-observed — **P2 / CONFIRMED**

Frame-drop counters alone are insufficient. Add content FPS, display refresh, requested frame rate, estimated display FPS, video-sync mode, mistimed/delayed frames and presentation cadence.

### F037 — No automated pixel-difference acceptance harness exists for the complete device pipeline — **P2 / CONFIRMED**

Unit tests validate metadata/commands, not output pixels. Maximum fidelity requires synthetic source patterns plus controlled captures/readbacks and numerical error metrics for identity mode, scaling, chroma, gamut, SDR expansion and HDR bypass.

### F038 — R08 live-PARAM patch adds per-executing-hook option-cache polling — **P2 temporal / CONFIRMED**

`tools/patch_r08_live_uniform_poll.py` injects `m_config_cache_update(p->shader_opts_cache)` from the executing `user_hook()` path. It is intended to eliminate stale uniforms, but it adds frame-path work and the device still shows no visible PARAM response.

**Required action:** when R08 resumes, profile this path and prefer event-driven invalidation/current uniform state over permanent per-hook polling if possible.

### F039 — Runtime native binary is a large vendored AAR; source/binary parity must remain a hard gate — **P2 / CONFIRMED**

`app/libs/mpv-android-lib-v0.0.1.aar` is what the app actually executes. R08 workflows contain strong lineage/fingerprint checks, including exact R07 FFmpeg restoration and Shaderc gates, but any future manual AAR replacement could bypass source review.

**Required action:** CI must reject an AAR whose native fingerprints cannot be reproduced from the audited recipe.

## P3 — peripheral, lifecycle, or future-hardening findings

### F040 — Screenshot output is not defined as a fidelity-proof stage — **P3 / CONFIRMED**

Snapshots use mpv `screenshot-to-file` with `video` or `subtitles`, but the project does not define whether that capture is pre-VO, post-filter, post-shader, post-tone-map, or display-equivalent for validation purposes.

### F041 — Screenshot completion uses a fixed 200 ms delay — **P3 / CONFIRMED**

`PlayerViewModel` waits 200 ms then tests the temporary file. This is a race/reliability issue for proof captures. Use completion/event semantics.

### F042 — Surface teardown/recreation has an acknowledged native race — **P3 / CONFIRMED IN PINNED WRAPPER**

Pinned `BaseMPVView.surfaceDestroyed()` sets `vo=null`, then detaches the surface; its own comment notes a potential race because setting the property may not wait for VO deinit. Renderer recreation can also invalidate state/telemetry.

### F043 — Generic GPU preferences do not describe the specialized branch's actual renderer — **P3 / CONFIRMED**

`DecoderPreferences` retains `gpuNext`/`useVulkan` preferences while `MPVView` deliberately hard-forces `vo=gpu` + `androidvk`. The hard force is correct for this branch, but stale UI/preferences create configuration ambiguity.

### F044 — Missing post-cutoff stride/BPP correctness fix is a TAKE candidate — **P3 now; elevate if path proves applicable**

Upstream mpv `8d04be2b856c9330178ddd6ab20848c58fada3af` (2026-05-01), `mp_image: force stride to be multiple of bpp`, fixes Vulkan texel-size alignment correctness. This is low visual-intent risk and should be evaluated for a narrow backport after R08 parity is stable.

### F045 — Additional post-cutoff renderer correctness fixes must be mined, not wholesale-upgraded — **P3 tracking**

Verified candidates include:

- `c1a21bb8d9db238054b7029c29d9e319889aad04` — skip invalid shader component overwrite; good Shader Lab robustness candidate.
- `702abfd587b3911d6dbda05a987b015ea9896bae` — use actual rather than logical hwdec texture dimensions; fixes polar-scaler glitches on affected Vulkan hwdec planes. Applicability to the current MediaCodec-copy path must be proven before backporting.

The policy remains narrow backports against the exact R07 renderer, not an unbounded renderer modernization.

---

# Findings that are intentional and must not be “fixed” blindly

The audit does **not** declare the following wrong merely because they alter the source:

- `vo=gpu` — mandatory because this is the Pixel path that actually produced expanded panel brightness.
- Vulkan / `androidvk` — current proven renderer API/context.
- `mediacodec-copy` as the primary hardware decode path — current Pixel validation baseline.
- `SDR-intensity=4.16` — intentional expanded-SDR luminance path; it must be scoped and measured, not casually removed.
- perceptual luminance/chroma expansion itself — this is the product goal, with original/bypass comparison as the reference.
- `inverse-tone-mapping=no` — part of the known-good avoidance of the problematic inverse-tone-map path.
- `gamut-mapping-mode=perceptual` — may be correct, but its ownership relative to the custom gamut limiter must be tested for double compression.
- high-quality EWA scalers — they are candidates for the final path; the audit asks for objective comparison and correct downstream handling, not automatic removal.
- `target-colorspace-hint=no` — preserve until Android-output A/B tests prove a safer replacement because it is intertwined with the known Pixel brightness behavior.

---

# Seven fidelity acceptance gates

No configuration should be called “maximum fidelity” until all seven gates are measurable and pass on the Pixel 9 Pro XL.

## Gate 1 — Decode/source integrity

Must prove:

- no accidental 8-bit/4:2:0 coercion;
- decoded pixel format/bit depth/chroma match the best available source path;
- source matrix, range, primaries, transfer and HDR metadata survive decode;
- hardware/software decoder changes are visible and fidelity-qualified;
- film-grain policy is codec-aware and does not create cadence failures.

## Gate 2 — Working precision integrity

Must prove:

- every intermediate format is known;
- `rgba16f` error is quantified against a higher-precision reference where feasible;
- no premature clamp/floor destroys signed or extended-range intermediate values;
- no filter silently inserts a lower-precision CPU conversion.

## Gate 3 — Spatial/chroma reconstruction integrity

Must prove with synthetic edges/chroma zones:

- luma scale, chroma scale and downscale kernels are measured separately;
- chroma location is preserved;
- ringing/aliasing/blur are quantified;
- sigmoid/antiring policy is chosen from evidence;
- rotation/aspect/zoom reference mode is geometrically neutral.

## Gate 4 — Color/dynamic-range integrity

Must prove:

- working gamut is explicit and source-aware;
- Oklab conversion uses the correct source/working primaries;
- nominal white is separate from mathematical headroom;
- gamut limiting uses the actual target gamut and does not double-compress with mpv;
- SDR expansion and true PQ/HLG HDR are mutually correct;
- target peak/reference white are observed rather than assumed.

## Gate 5 — Android/display-output integrity

Must record and validate:

- Vulkan swapchain format and bits/channel;
- Android dataspace/color mode;
- output primaries/TRC;
- Natural vs Adaptive test state;
- system and window brightness;
- granted HDR/extended luminance headroom;
- compositor does not add an unintended gamut/tone transform.

## Gate 6 — Temporal/presentation integrity

Must prove:

- content FPS and display refresh are compatible;
- frame-rate requests actually take effect;
- no avoidable judder pattern;
- zero unexplained frame drops/mistimed/delayed frames;
- Shader Lab live tuning/diagnostics do not disturb cadence.

## Gate 7 — Reference/regression proof

Must provide:

- atomic whole-pipeline reference mode;
- synthetic identity/gradient/chroma/gamut/HDR test clips;
- numerical pixel/error metrics where capture stage permits;
- stable screenshots/readbacks with a documented stage;
- effective mpv option dump and native fingerprint per proof build;
- A/B device proof for every fidelity-affecting change;
- no advancement based only on property readback when rendered pixels disagree.

---

# Recommended remediation order

This is intentionally ordered to avoid tuning a shader on top of a compromised pipeline.

1. **Remove destructive/silent baseline damage:** F001, F002, F003 and split option ownership F016.
2. **Instrument the final Android output:** F004, F011-F013, F025, F034-F036.
3. **Establish temporal truth:** F005 plus Gate 6.
4. **Repair shader domain assumptions:** F006-F008, F020-F023 before further “look” tuning.
5. **Evaluate optional processing scientifically:** deband/grain, sigmoid, dither, antiring, film grain, CPU filters.
6. **Backport narrow upstream correctness fixes:** start with `8d04be2b` and `c1a21bb8`; validate each against exact R07 parity.
7. **Resume R08 live-PARAM repair only after the reference pipeline is observable enough to tell whether a slider change is real, correctly transformed, and temporally safe.**

---

# Audit policy going forward

- Upstream is evidence, not authority; a stable upstream default can still be a fidelity compromise.
- “Known good” means it reproduced a prior desired result, not that it is mathematically optimal.
- Performance optimizations are allowed only when they are pixel/cadence equivalent or the fidelity tradeoff is explicit.
- Every hidden fallback must be observable.
- Every processing stage must have one owner.
- Every automatic mode must expose the value/path it chose.
- Every visual optimization must be tested against objective patterns as well as real content.
- The Pixel device result outranks a property-level PASS: if the image does not change, the parameter transport is not accepted.

This audit is a living source-controlled document. Findings are removed only after a code change plus the relevant gate proves the robber is gone or that the suspected ceiling is measurably harmless on the target device.
