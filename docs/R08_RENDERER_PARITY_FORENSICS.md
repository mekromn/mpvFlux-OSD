# R08 Renderer Parity Forensics

This file is the detailed execution record for the exact-R07 renderer-parity rebuild used by Shader Lab R08. It exists to keep build evidence, rejected hypotheses, fixes, and the next breakpoint recoverable across chat/session boundaries.

## Frozen parity target

R08 remains `IN_PROGRESS`. Do not advance to R09 until the parity APK has passed both automated native fingerprint proof and real Pixel 9 Pro XL / Android 16 visual validation.

The renderer stack is frozen to:

- mpv base: `d54bad5636924ab3f39cb6e397b94b6aa8a7c433`
- FFmpeg: `5ba2525c7affc29cbd99e6266946b382d3fffe8b`
- expected FFmpeg describe: `N-122998-g5ba2525c7a`
- libplacebo: `c93aa134ab62365ce1177efff99b8e1e66a818e7`
- expected libplacebo describe: `v7.360.0-3-gc93aa134`
- expected embedded libplacebo string: `v7.360.0 (v7.360.0-3-gc93aa134)`
- NDK: `r29`, revision `29.0.14206865`, build `14206865`
- Android API: `24`
- Vulkan: enabled
- required runtime dependency: `libvulkan.so`
- `vo=gpu`; `gpu-next` remains out of scope for the Pixel brightness-parity target
- R08-only mpv changes: PARAM prerequisite `91ceffce42534a45705617036b6b2a392a32fc57`, PARAM support `0d655fe66590009e1d77a17581257d677286531a`, and `SHADER_MAX_PARAMS=64`
- no post-R07 mined renderer patches are allowed before Pixel parity

## 2026-08-21/22 parity forensic pass

### Stale failure recovery

The previously tracked failed native job was `96881103884`. Its step summary established failure inside the native parity step, but the retained job-log endpoint did not return usable log text. It therefore was not sufficient evidence for changing shaderc, Vulkan, NDK, libplacebo, or any renderer dependency.

The branch already contained a newer phase-tagged parity harness with `/tmp/r08-renderer-parity-native-build.log` and automatic failure-artifact capture. Rather than patch from the stale failure, a fresh exact-head proof was triggered.

### Fresh proof trigger

Trigger commit:

- branch: `agent/upstream-refactor`
- commit: `d69429e482d5a382631714cf7f348933861c7977`
- message: `R08 retrigger exact renderer parity diagnostics`

Fresh workflow:

- workflow: `R08 Renderer Parity Proof`
- run number: `24`
- run ID: `32558275237`
- native job ID: `96995796033`
- branch head SHA reported by GitHub: `d69429e482d5a382631714cf7f348933861c7977`
- failure artifact ID: `9472167298`
- failure artifact name: `Chrovelo-R08-renderer-parity-failure-053cdd7`
- failure artifact digest: `sha256:245f6728c08e807286cb5ac9695ddb245d0e18a233ae5486fd44e65c0de7cf06`

An immediately preceding run using the same instrumented native script also supplied independent evidence:

- run number: `22`
- run ID: `32557710494`
- native job ID: `96994430564`
- branch head: `c6afce0f303db7a249f78dce3a01373d6ecc7d29`
- failure artifact ID: `9472015588`
- artifact name: `Chrovelo-R08-renderer-parity-failure-b93c765`
- artifact digest: `sha256:391bc8c0ea001ba0a64d54adc500f955d47200a5701ccfe93822a1cdc86203b1`

### First proven blocker

Both run #22 and fresh run #24 completed the native build and failed at the same verification command:

`R08_FAIL phase=renderer_fingerprint_gates rc=1 line=346 command=grep -F 'for Android 24'`

This is not a native compiler/linker failure. The log immediately before the gate proves:

- mpv compiled all `245/245` objects and linked `libmpv.so` successfully;
- FFmpeg identity was `N-122998-g5ba2525c7a`;
- libplacebo identity was `v7.360.0 (v7.360.0-3-gc93aa134)`;
- embedded mpv configuration included `-Dvulkan=enabled` and the frozen accepted option ordering;
- `pl_vulkan_create` and `pl_vulkan_create_swapchain` were present;
- `libmpv.so` had `DT_NEEDED libvulkan.so`;
- the embedded FFmpeg configuration used `--cc=aarch64-linux-android24-clang`;
- FFmpeg remained on the accepted mediacodec/JNI topology with FFmpeg Vulkan disabled;
- libxml2 remained absent from the accepted topology;
- `force_mpegts` remained absent.

The failing gate depended on human-readable host `file(1)` prose:

`file "$so" | grep -F 'for Android 24'`

That string is not the authoritative binary representation of Android API/NDK identity. The accepted R07 ELF contains the canonical `.note.android.ident` note whose descriptor encodes:

- API integer `24`;
- NDK tag `r29`;
- NDK build `14206865`.

### Verification-only repair

The parity script now validates `.note.android.ident` directly instead of treating `file(1)` wording as the proof source.

For every rebuilt renderer/FFmpeg ELF it:

1. logs `file(1)` output for diagnostics only;
2. obtains `.note.android.ident` with `readelf -x`;
3. parses the ELF note header and Android descriptor;
4. asserts note owner `Android` and note type `1`;
5. asserts API exactly `24`;
6. asserts NDK tag exactly `r29`;
7. asserts NDK build exactly `14206865`;
8. retains the existing per-ELF `PT_LOAD` minimum alignment assertion of `0x4000` (16 KB).

The parser was independently tested against the accepted R07 Pixel APK's `libmpv.so` and decoded exactly:

`Android API 24, NDK r29 (14206865)`

This repair changes only the parity verifier. It does not change mpv, FFmpeg, libplacebo, shaderc, NDK, API level, Vulkan topology, linker flags, PARAM code, or Shader Lab runtime behavior.

If a rebuilt ELF genuinely lacks or disagrees with `.note.android.ident`, the new gate will still fail. That would become the next proven blocker; the verifier must not be weakened to hide it.

## Rejected / corrected hypotheses

### NDK r29 Vulkan registry path

Earlier suspicion that the current build was depending on a removed `$NDK/sources/third_party/vulkan/...` registry was incorrect for the current exact-R07 script.

Exact libplacebo `c93aa134...` carries and prefers its own pinned `3rdparty/Vulkan-Headers` submodule. That submodule is pinned to `450bd2232225d6c7728a4108055ac2e37cef6475` and supplies `registry/vk.xml`. No parity patch should be made based on the removed NDK Vulkan-Headers path.

### shaderc source availability

The prior hypothesis that NDK r29 necessarily removed the shaderc source tree was not proven and is contradicted by the current successful native build. The exact run compiled through libplacebo/mpv and linked successfully, so shaderc is not the first blocker.

### synthetic Vulkan pkg-config ordering

A possible ordering concern was identified because the synthetic Android `vulkan.pc` is generated in the mpv recipe after libplacebo's build step. The successful run disproves it as the current first blocker: exact libplacebo/mpv Vulkan compilation and final `DT_NEEDED libvulkan.so` succeeded. Do not change this topology absent new failure evidence.

## Next breakpoint

1. Run `R08 Renderer Parity Proof` from the commit containing the `.note.android.ident` verifier repair.
2. If the note gate fails, capture the exact decoded/missing note as the new first proven blocker and fix only that issue.
3. If the native gate passes, continue through parity AAR assembly, Shader Lab tests, persistent signing, Pixel APK assembly, final R07 native fingerprint checks, and proof-artifact upload.
4. Record the resulting run/job/artifact IDs and hashes here and in the R08 progress documentation.
5. Only after automated parity proof passes, install/test the parity APK on the real Pixel 9 Pro XL / Android 16 and compare the expanded-brightness/high-fidelity `vo=gpu` output to accepted R07.
6. Do not apply mined upstream renderer improvements until that real-device parity checkpoint passes.
