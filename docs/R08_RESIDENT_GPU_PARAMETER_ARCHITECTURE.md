# R08 Resident GPU Parameter Architecture

## Decision

Shader Lab's normal tuning path is moving away from generated GLSL files and A/B runtime shader swapping.

The target is a single resident Pixel shader running on the proven `vo=gpu` + Vulkan path. Shader Lab value changes are transported as tunable GPU parameters, so dragging a control changes parameter values rather than regenerating shader source.

## Why the old R08 was superseded

The previous R08 proposal optimized the legacy mechanism:

`control change -> generate GLSL text -> write inactive file -> remove/append shader -> compile/cache -> display`

That mechanism remains useful only as an emergency compatibility/export path. It is not the architecture to optimize for normal live tuning.

The new normal path is:

`native semantic control -> validated typed value -> mpv glsl-shader-opts -> resident vo=gpu shader uniform -> frame`

MPV-native properties such as `sdr-intensity`, brightness, contrast, gamma, saturation, and hue remain direct MPV properties.

## Version-specific discovery

The currently bundled Android libmpv reports mpv `v0.41.0-224-gd54bad563`. Upstream commit `d54bad5636924ab3f39cb6e397b94b6aa8a7c433` is dated 2026-02-25.

Upstream mpv added initial tunable `//!PARAM` support to `vo=gpu` later, on 2026-04-17, in commit `0d655fe66590009e1d77a17581257d677286531a` (`vo_gpu: initial support for tunable parameters`). Therefore the currently bundled AAR predates the required `vo=gpu` feature.

The upstream implementation also defines `SHADER_MAX_PARAMS` as 16. Shader Lab needs more than 16 tunable shader values in one pass, so Chrovelo requires a narrowly scoped libmpv build with that fixed table enlarged to 64.

## Native renderer strategy

R08 will build an Android libmpv from the same mpv-android lineage while minimizing renderer drift:

1. Start from the empirically proven mpv baseline `d54bad5636924ab3f39cb6e397b94b6aa8a7c433` when practical.
2. Backport/cherry-pick upstream `vo=gpu` tunable-parameter support from `0d655fe66590009e1d77a17581257d677286531a`.
3. Increase only `SHADER_MAX_PARAMS` from 16 to 64.
4. Preserve `vo=gpu`, Vulkan, MediaCodec-copy compatibility, and the existing Android `is.xyz.mpv.MPVLib` API.
5. Keep the resulting native dependency reproducible and version-pinned.

If the targeted backport cannot be built cleanly, the fallback is a pinned newer mpv source that already contains `0d655fe...`, with the same 64-parameter limit patch. Broad renderer rewrites are explicitly out of scope.

## Resident shader strategy

The Pixel perceptual expansion shader will become a stable managed shader file containing `//!PARAM` blocks. Runtime values become uniforms/parameters instead of source-code literals.

The resident shader must preserve the current V3.1 math exactly, including:

- linear-light luminance expansion;
- Oklab color-volume expansion;
- skin-tone protection;
- hue-preserving gamut search;
- deep-black/highlight behavior;
- diagnostic views;
- `LUMA_MASTER` and `CHROMA_MASTER` semantics.

Normal parameter changes must not write the resident shader file and must not remove/re-add the shader.

## Transport strategy

The Android bridge remains the authoritative native boundary established by R07.

For shader parameters, the bridge will publish the complete current parameter set to mpv's `glsl-shader-opts` using maximum useful numeric precision. The complete set is sent so no value is accidentally reset or lost when another slider moves.

Lua remains temporarily for legacy workstation UI, preset/state compatibility, and device diagnostics until the native UI stages replace it. Lua no longer owns normal shader source generation once resident parameter transport is proven.

## Live-update behavior

The UI value itself will update on every pointer event. The parameter transport should be fast enough to follow the display/touch cadence without a fixed release-only apply.

R08 will instrument actual parameter transport latency on the Pixel. Coalescing is allowed only if real device measurements prove it is needed; there is no predetermined 18 ms debounce in the new architecture.

## Failure and fallback behavior

- Invalid/non-finite values are rejected before transport.
- Catalog min/max and ordered-pair constraints remain authoritative.
- Last-known-good parameter state is retained in native state.
- A transport failure restores the previous parameter set without rewriting GLSL.
- Legacy generated A/B shaders may remain temporarily as an explicit compatibility/export fallback, but must not be used during ordinary tuning.
- A deeper custom renderer API is a contingency only if upstream `glsl-shader-opts` proves insufficient in real Pixel latency testing.

## R08 acceptance evidence

Automated:

- custom libmpv contains `vo=gpu` tunable PARAM support;
- `SHADER_MAX_PARAMS >= 64`;
- existing MPVLib API compiles unchanged;
- resident shader has no unresolved template tokens;
- every mapped shader control has exactly one typed parameter definition;
- full Shader Lab unit suite passes;
- signed arm64 Debug APK builds and verifies.

Pixel 9 Pro XL / Android 16:

- `vo=gpu` still triggers the proven expanded-brightness path;
- resident shader visibly responds to parameter changes;
- repeated value changes do not change the resident shader file and do not increment normal shader-swap counters;
- final requested value is the value rendered after input stops;
- HDR remains protected from SDR-only expansion;
- parameter transport latency is recorded;
- no backend error remains after successful changes.

R08 does not build the final workstation UI. R09 remains the ViewModel/authoritative UI-state layer and R10 remains the native workstation UI redesign.
