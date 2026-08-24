# Chrovelo fidelity audit

I treated R07 only as a known-working comparator—not as perfect output.

Scope covered:

* All 431 tracked repository files at commit `93f0383b`
* Kotlin playback and preference code
* Lua controller and every GLSL shader
* mpv configuration and managed-file installer
* Build and release workflows
* The committed native AAR
* All nine supplied APKs
* Exact pinned mpv `d54bad563`
* Exact pinned FFmpeg `5ba2525c`
* Exact pinned libplacebo `c93aa134`
* Relevant newer upstream mpv changes

No code was changed.

## Critical problems

### 1. The current repository AAR cannot provide the renderer the app forces

[MPVView.kt](sandbox:/workspace/scratch/6857edfc174a/mpvFlux-OSD/app/src/main/java/app/marlboroadvance/mpvex/ui/player/MPVView.kt) unconditionally selects:

```text
vo=gpu
gpu-context=androidvk
gpu-api=vulkan
```

But the committed arm64 `libmpv.so`:

* Is only 7,699,456 bytes
* Has SHA-256 `40a94d1a…`
* Contains no `androidvk`
* Contains no `pl_vulkan_create`
* Contains no Shaderc compiler
* Does not depend on `libvulkan.so`

The R07 APK library:

* Is 12,087,976 bytes
* Has SHA-256 `265ef6bd…`
* Contains `androidvk`
* Contains Vulkan/libplacebo renderer functions
* Contains Shaderc
* Depends on `libvulkan.so`

All nine supplied APKs contain that same working R07 native library.

Normal build and release workflows use the committed non-Vulkan AAR directly through [app/build.gradle.kts](sandbox:/workspace/scratch/6857edfc174a/mpvFlux-OSD/app/build.gradle.kts). Only specialized R08 parity workflows rebuild the proper renderer.

This is likely the primary explanation for the latest ordinary-build black screen: the app explicitly requests a renderer that is absent from its packaged engine.

---

### 2. R07 does not produce true HDR output

The exact Android Vulkan implementation creates its swapchain with empty platform parameters:

* No target colorspace callback
* No output-depth callback
* No HDR metadata propagation
* No Android dataspace selection
* No display capability reporting
* No functional Android `control` implementation

The Vulkan frame contains colorspace information from libplacebo, but legacy `vo_gpu` does not propagate it into the destination FBO.

Consequently, `vo_gpu` sees an unknown output:

* Unknown target primaries become BT.709
* PQ/HLG output becomes gamma 2.2
* Unknown output peak becomes mpv reference white: 203 nits
* HDR is tone-mapped to an SDR rendering target
* Wide-gamut SDR is converted toward BT.709
* No HDR or wide-gamut metadata reaches Android’s compositor

The app also lacks `android:colorMode="wideColorGamut"` in [AndroidManifest.xml](sandbox:/workspace/scratch/6857edfc174a/mpvFlux-OSD/app/src/main/AndroidManifest.xml), and the underlying `SurfaceView` does not configure dataspace, HDR, preferred format, or frame rate.

So “leave PQ/HLG alone” currently means only “do not apply the custom SDR shader.” Native mpv still transforms HDR to untagged SDR.

This flaw exists in R07 and remains unfixed in current upstream mpv’s Android Vulkan context. Merely upgrading mpv or switching to `gpu-next` will not solve it.

---

### 3. Output is deliberately quantized as though it were 8-bit

Because Android reports no framebuffer depth, legacy `vo_gpu` receives `-1`.

With:

```text
dither-depth=auto
```

mpv explicitly assumes an 8-bit target whenever depth is unknown.

Therefore, even if the Pixel’s Vulkan driver selects a 10-bit swapchain, mpv performs its final quantization and dithering for 8-bit output first.

This is code-proven. The actual Pixel swapchain format still needs a device log, but any physical 10-bit surface would currently be underused.

---

### 4. The default decoder cannot guarantee 10-bit preservation

The default order is:

```text
mediacodec-copy,mediacodec,no
```

In the pinned FFmpeg MediaCodec implementation, copy-mode output maps only to:

* `AV_PIX_FMT_YUV420P`
* `AV_PIX_FMT_NV12`

There is no P010 or other 10-bit MediaCodec buffer mapping.

A 10-bit source can therefore:

* Be converted to 8-bit by the decoder
* Produce an unsupported output-format failure
* Fall back to another decoder

It cannot be assumed to remain 10-bit through `mediacodec-copy`.

Direct `mediacodec` output uses Android `AImageReader`, but the pinned mpv mapper explicitly requires OpenGL and exposes the result as `RGB0`. It cannot map into the app’s forced Vulkan renderer.

Until proper Vulkan `AHardwareBuffer`/YCbCr/P010 interop exists, fidelity-first behavior should select software decoding for 10-bit and HDR content.

---

### 5. Current R08 actions can apply the entire shader twice

The [Lua controller](sandbox:/workspace/scratch/6857edfc174a/mpvFlux-OSD/app/src/main/assets/mpvlab/source/scripts/pixel9-shader-lab.lua) still generates and appends runtime shader A/B files for:

* Built-in preset load
* User preset load
* Morph
* Reset all
* Revert to video start
* Load state
* Shader proof

Android then reads the changed Lua values and publishes them into the resident R08 shader.

However, [ShaderLabResidentGpuTransport.kt](sandbox:/workspace/scratch/6857edfc174a/mpvFlux-OSD/app/src/main/java/app/marlboroadvance/mpvex/repository/shaderlab/bridge/ShaderLabResidentGpuTransport.kt) skips its full reconciliation when:

* Source is already SDR
* Resident shader is already attached

That means it updates resident parameters without removing the Lua runtime shader.

Result:

```text
Resident V3.1 shader
        +
Generated runtime V3.1 shader
        =
V3.1 transformation applied twice
```

This can significantly exaggerate contrast, luminance expansion, saturation, clipping, and skin treatment after preset-related actions.

The existing unit test misses the defect because its fake transport records the `script-message` but does not actually execute Lua’s `change-list` commands.

## Major shader fidelity losses

### 6. V3.1 changes strength depending on scaling

The resident shader uses:

```glsl
//!HOOK LINEAR
```

Exact legacy `vo_gpu` only creates that hook stage when:

* Upscaling with sigmoid or linear upscaling
* Downscaling with linear downscaling
* Not HDR downscaling

At exact 1:1 sizing, the `LINEAR` hook is skipped.

Therefore, the same source can change appearance when:

* Surface resolution changes
* The phone rotates
* Zoom changes
* Aspect mode changes
* Content resolution changes
* Picture-in-picture or window size changes

A core perceptual transform must not disappear simply because the source happens to match the viewport.

---

### 7. V3.1 processes every SDR gamut as though it were BT.709

The [resident shader](sandbox:/workspace/scratch/6857edfc174a/mpvFlux-OSD/app/src/main/assets/mpvlab/source/shaders/pixel9-perceptual-expansion-resident-v3.1.glsl) hardcodes:

```glsl
vec3(0.2126, 0.7152, 0.0722)
```

and fixed linear-sRGB/BT.709 Oklab matrices.

But the shader runs before mpv performs output-primary conversion, and eligibility only checks whether gamma is PQ or HLG.

It therefore treats all of these as BT.709:

* BT.601 SDR
* BT.2020 SDR
* Display-P3
* Adobe RGB-like sources
* Untagged or incorrectly tagged SDR

That produces incorrect luminance, hue, skin detection, chroma scaling, and gamut decisions before mpv later converts the result.

---

### 8. The shader destroys valid extended-range information

The shader performs:

```glsl
vec3 rgb = max(src.rgb, vec3(0.0));
```

It also:

* Clamps luminance into `0…1`
* Caps the highest RGB component at `0.99995`
* Uses `RGB_LOW=0.00005`
* Pulls gamut-limited colors inward with `GAMUT_MARGIN=0.997`

Consequences:

* Negative RGB generated during valid color conversion is discarded
* Superwhite/headroom is discarded
* Saturated boundary colors are deliberately reduced
* Repeated nonlinear processing cannot recover the removed information

Sigmoid upscaling adds another `0…1` clamp before scaling.

`rgba16f` cannot rescue values already clipped by shader logic.

---

### 9. The default “Reference” is an active grade

The V3.1 defaults include nonzero:

* Luminance contrast expansion
* Highlight lift
* Base chroma expansion
* Midtone chroma expansion
* Bright-region chroma expansion
* Skin-dependent chroma behavior

That may be a desirable Pixel-Adaptive-style presentation, but it is not source-neutral.

For strict terminology:

* “V3.1 Reference” means the reference enhancement grade
* It does not mean reference/source-faithful reproduction

A separate neutral mode is required for objective comparison.

## Default processing problems

### 10. GPU debanding is enabled even when the preference says None

Startup always sets:

```text
deband=yes
deband-iterations=2
deband-threshold=24
deband-range=16
deband-grain=8
```

Yet `DecoderPreferences` defaults to:

```text
Debanding.None
iterations=1
threshold=48
range=16
grain=32
```

`postInitOptions()` does nothing for `None`, so it never disables the already-enabled GPU debander.

CPU mode adds `gradfun` without disabling GPU debanding, producing two debanding passes.

Effects include:

* Removal of legitimate texture
* Removal or alteration of film grain
* Smearing of subtle gradients
* Synthetic grain replacing actual grain

This is an active default-path fidelity robber.

---

### 11. Default `fast` profile leaves HDR peak analysis disabled

The app defaults to:

```text
profile=fast
```

It later overrides the fast scaler and dither selections, but it does not override:

```text
hdr-compute-peak=no
```

Therefore HDR→SDR tone mapping does not dynamically analyze scene/frame peaks. It relies on static signal metadata or inferred peaks, which can cause inferior highlight allocation across changing scenes.

---

### 12. Sharp EWA scaling lacks the high-quality profile’s antiring protection

The app forces:

```text
scale=ewa_lanczossharp
cscale=ewa_lanczos
dscale=ewa_lanczos
```

But because the default profile is `fast`, `scale-antiring` remains zero.

mpv’s built-in `high-quality` profile would use:

```text
scale-antiring=0.6
```

The current default is therefore more vulnerable to:

* Edge halos
* Overshoot
* Ringing around high-contrast detail
* Artificially sharpened texture

Sigmoid upscaling reduces some ringing but does so partly through clamping.

## Placebo and misleading controls

### 13. `sdr-intensity` is not implemented in the inspected engine

The exact pinned mpv source contains no `sdr-intensity` option.

Neither the committed binary nor the R07 Vulkan binary contains that option name.

The Lua controller attempts to set it, notices failure, and then marks it unsupported. The Android UI can still retain and display a value.

This conflicts with our earlier visual impression that changing it affected panel brightness. Static engine evidence says the property itself is not implemented, so that observation needs a controlled instrumented A/B test before we preserve the claim. Another simultaneous state change likely caused the visible difference.

---

### 14. `target-colorspace-hint` cannot affect the active renderer

That option belongs to `vo=gpu-next`.

The app forces legacy `vo=gpu`, and exact mpv documentation lists only Wayland, D3D11, and winvk as supported colorspace-hinting contexts.

The configured value is therefore inactive on Android.

---

### 15. `gamut-mapping-mode=perceptual` does not run

Legacy `vo_gpu` accepts only:

* `auto`
* `warn`
* `clip`
* `desaturate`

Although the global parser recognizes `perceptual`, the renderer rejects it, logs a warning, and changes it to `auto`.

The requested perceptual mapping never executes.

---

### 16. “HDR to SDR compression” cannot process HDR

`SDR_COMPRESS` is inside the SDR-only custom shader.

The resident shader is removed for PQ and HLG, so the control can never compress HDR.

Its actual math is:

```glsl
mix(tunedSDR, originalSDR, SDR_COMPRESS)
```

It simply reduces the strength of the SDR grade.

---

### 17. The GPU renderer preferences are ignored

`DecoderPreferences` exposes:

* `gpuNext`
* `useVulkan`

But the specialized player path always forces:

```text
vo=gpu
gpu-context=androidvk
gpu-api=vulkan
```

The preferences do not change runtime behavior and prevent easy testing of improved renderers.

## “Original” is not original

The native comparison path changes only the private `R08_BYPASS` shader parameter.

The following remain active:

* GPU debanding
* EWA scaling
* Sigmoid upscaling
* Linear downscaling
* Dithering
* mpv color conversion
* HDR tone mapping
* Gamut mapping
* Picture properties
* Decoder conversions

The UI’s “ORIGINAL” view is therefore only “custom V3.1 shader bypass.”

It should either be relabeled or expanded into a genuine neutral/reference mode.

## Additional shader defects

### 18. Negative chroma controls do not reduce chroma

The UI permits negative values for several chroma controls.

But:

```glsl
if (requestedScale <= 1.000001)
    return 1.0;
```

Any requested chroma reduction is changed back to unity.

The controls can cancel positive expansion, but they cannot desaturate below the input.

---

### 19. Gamut search can produce small saturation steps

The default gamut limiter uses seven binary-search iterations.

That gives approximately 1/128 resolution across the requested chroma-expansion interval. Near gamut boundaries, this can create small stepped saturation changes or contour-like transitions.

Twelve iterations are already permitted and would be safer, although an analytic or libplacebo-based gamut solution would be preferable.

---

### 20. Ordered control pairs can still become equal

Both Kotlin and Lua attempt to keep `smoothstep` edge pairs separated by `0.000001`.

At a shared upper limit, adding the gap is clamped back to the same maximum. Both values remain equal.

GLSL defines `smoothstep(edge, edge, x)` as undefined.

This can create discontinuities or device-dependent results after aggressive tuning or malformed preset/state restoration.

---

### 21. Source detection has a transition race

Classification watches only:

```text
video-params/gamma
```

A path change resets comparison state but does not remove the resident shader.

For `NOT_READY` and `UNKNOWN`, resident reconciliation does nothing.

Therefore, when switching from SDR to HDR, the old SDR resident shader can remain attached until the new gamma event arrives. Depending on mpv event order, the first HDR frames may be processed by the SDR shader.

This is timing-dependent and needs a device transition test.

## Optional but real losses

These are not active on a fresh installation unless selected:

* `vf=format=yuv420p`: destroys >8-bit precision and higher chroma formats
* Crop/panscan: removes image area
* Stretch/custom aspect: geometrically distorts the image
* Zoom: forces extra resampling and cropping
* Sharpen: creates artificial edge contrast
* Brightness/contrast/gamma/saturation/hue presets: deliberate grading
* CPU/GPU debanding: removes source information
* Audio normalization: changes dynamics
* Mono/stereo forcing: changes channel presentation
* Reverse stereo: swaps channels
* Speed changes: require temporal resampling/time stretching
* Volume above 100: can clip

## Audio fidelity findings

### 22. `Auto` and `Auto Safe` are reversed

[AudioPreferences.kt](sandbox:/workspace/scratch/6857edfc174a/mpvFlux-OSD/app/src/main/java/app/marlboroadvance/mpvex/preferences/AudioPreferences.kt) maps:

```text
UI Auto      → mpv auto-safe
UI Auto Safe → mpv auto
```

The default is UI `Auto Safe`, so the engine actually receives `auto`.

That can change multichannel routing and downmix behavior from what the user selected.

---

### 23. Default audio is not bit-perfect

The pinned engine tries Android AudioTrack first.

AudioTrack:

* Runs through Android’s shared audio path
* Uses the native output rate as a ceiling
* Resamples higher-rate audio
* Does not request exclusive output
* Can undergo further Android mixer processing

Ordinary 44.1/48 kHz material may be fine, but high-resolution or external-DAC playback is not bit-perfect.

## Temporal fidelity

### 24. No display frame-rate matching exists

The app and Android Vulkan context do not use:

* `Surface.setFrameRate`
* Preferred display mode
* Android refresh-rate selection
* A functional display-vsync callback
* Explicit display-resample configuration

Vulkan FIFO avoids tearing, but it does not guarantee correct cadence.

On the Pixel’s 60/120 Hz modes, 23.976, 24, 25, and 50 fps content can still exhibit repeated-frame cadence or timing drift depending on Android’s selected refresh mode.

## Non-playback imagery

Browser thumbnails are:

* Capped to 1024 pixels
* Stored as JPEG
* Written at quality 100

JPEG quality 100 remains lossy. This affects only browser/playlist thumbnails—not video playback.

Snapshots use PNG and do not add JPEG compression.

## Relevant beyond-R07 upstream improvement

R07’s mpv pin predates commit:

```text
702abfd58 — vo_gpu: use actual (not logical) texture dimension
```

It fixes polar-scaler glitches when hardware-decoded textures have padded dimensions such as 1088 pixels for a logical 1080-line frame.

This becomes especially relevant once Chrovelo has a real Vulkan zero-copy hardware decode path.

Current upstream mpv still lacks the Android HDR/colorspace/depth solution, so an upstream version bump alone is insufficient.

# Recommended improvement order

1. **Repair the native artifact contract.** Every APK must use one canonical Vulkan-capable AAR. CI should fail if `androidvk`, `libvulkan`, libplacebo Vulkan, Shaderc, or required symbols are absent.

2. **Implement real Pixel HDR/wide-gamut output.** Propagate swapchain colorspace and depth, choose and tag 10-bit surfaces, configure Android wide-color behavior, and send proper HDR metadata/dataspace.

3. **Make decoding bit-depth aware.** Use software decode for 10-bit/HDR until Vulkan AHardwareBuffer/P010/YCbCr interop is implemented.

4. **Remove the R08 double-shader path.** Presets, reset, morph, and state restoration must update resident uniforms only. Lua must never append runtime V3.1 shaders under R08.

5. **Make shader execution stage-stable and metadata-aware.** Do not depend on whether scaling occurs. Use the actual source primaries and preserve extended-range values.

6. **Create a genuine reference mode.** Disable enhancement shader, debanding, artificial sharpening, picture grading, and unnecessary transforms while retaining only essential color conversion and scaling.

7. **Make enhancement processing opt-in.** Debanding should honor `None`; saved values should actually be restored; default fidelity mode should be neutral.

8. **Fix HDR tone mapping.** Enable dynamic peak analysis and only tone-map when the actual output cannot accept HDR.

9. **Implement refresh-rate matching.**

10. **Correct audio channel labels and add a high-fidelity audio route.**
