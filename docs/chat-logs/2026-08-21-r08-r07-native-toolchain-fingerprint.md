# R08 — Accepted R07 Native Toolchain Fingerprint

Date: 2026-08-21

This checkpoint records a native fingerprint recovered directly from the accepted `Chrovelo-Debug-R07-Pixel9ProXL.apk`. It supplements the existing R08 renderer-parity handoff and must be treated as part of the parity contract.

## Direct APK evidence

The accepted R07 `lib/arm64-v8a/libmpv.so` reports:

```text
ELF 64-bit LSB shared object, ARM aarch64
for Android 24
built by NDK r29 (14206865)
```

Its `.note.android.ident` contains:

```text
r29
14206865
```

The Android NDK revision corresponding to this compiler fingerprint is:

```text
r29
29.0.14206865
```

The same accepted `libmpv.so` has `DT_NEEDED` entries including `libvulkan.so`, and contains the expected shaderc/glslang symbols, further confirming that the accepted renderer was a Vulkan-capable build with shaderc available at runtime/build time.

## Why this changed the R08 parity harness

The pinned Secozzi harness commit `fd17fb02cfb8c8b5c12621c2c90769685d635d91` defaults to:

```text
v_ndk=r27c
v_ndk_n=27.2.12479018
```

That is not the toolchain which produced the accepted R07 native library. Even with exact mpv, FFmpeg, and libplacebo source revisions, an r27c build cannot truthfully be called an exact reproduction of the accepted R07 native renderer.

Commit `75d98030de8077f9ada1697f82e6b01d3a5cc6da` therefore updates `tools/r08_renderer_parity_rebuild.sh` so the harness is changed to the accepted R07 toolchain **before dependency download/build**:

```text
v_ndk=r29
v_ndk_n=29.0.14206865
```

The parity proof now hard-gates the finished `libmpv.so` on:

- Android API 24
- NDK tag `r29`
- NDK build `14206865`
- FFmpeg `N-122998-g5ba2525c7a`
- libplacebo `v7.360.0 (v7.360.0-3-gc93aa134)`
- mpv configured with Vulkan enabled
- `DT_NEEDED: libvulkan.so`
- `pl_vulkan_create` present
- 16 KB ELF LOAD alignment
- only the two required resident PARAM backports above the R07 mpv base
- `SHADER_MAX_PARAMS=64`
- no mined post-R07 renderer/correctness patches in the reference build

## Frozen parity stack after this finding

```text
mpv        d54bad5636924ab3f39cb6e397b94b6aa8a7c433
PARAM      91ceffce42534a45705617036b6b2a392a32fc57
           0d655fe66590009e1d77a17581257d677286531a
FFmpeg     5ba2525c7affc29cbd99e6266946b382d3fffe8b
libplacebo c93aa134ab62365ce1177efff99b8e1e66a818e7
NDK        r29 / 29.0.14206865 / build 14206865
Android    API 24
Vulkan     enabled
vo         gpu
PARAM max  64
miner pack NONE until Pixel parity is proven
```

R08 remains in progress. This fingerprint improves the definition of the reference build; it does not replace the required Pixel 9 Pro XL visual/behavioral acceptance test.
