# R08 Android Runtime Dependency Repair

## Pixel symptom

The first telemetry-enabled R08 Pixel 9 Pro XL APK remained on the Android splash screen and never reached the application UI.

## Root cause

The custom R08 `libmpv.so` built from the pinned Secozzi Android harness declared the following additional ELF runtime dependency:

`DT_NEEDED: libxml2.so`

The preserved R07 `MPVLib` AAR did not package `libxml2.so`. Android therefore could not satisfy the custom `libmpv.so` dependency graph at runtime. Gradle compilation, unit tests, PARAM verification, and 16 KB LOAD-segment verification could all pass without detecting this missing runtime dependency.

The pinned harness intentionally uses libxml2 2.15.2 in its native dependency graph. FFmpeg enables libxml2 and fontconfig selects libxml2 as its XML backend. Removing that dependency would change the native feature stack, so R08 packages the exact matching arm64 `libxml2.so` instead.

## Repair

- Repair workflow: `.github/workflows/r08-native-runtime-dependency-repair.yml`.
- Workflow source commit: `2937c68e8c4f95a2e052bb481c21ebcb12985fee`.
- Guarded repaired-AAR commit: `5050fcf70c8ee99e3f82c0a9015f4a0663e10469`.
- The existing `MPVLib`/`MPVNode` Java API, `classes.jar`, `libplayer.so`, and R08 resident shader/bridge design remain unchanged.
- `libxml2.so` is built from the same pinned native harness and version used by the custom libmpv dependency graph.
- All packaged arm64 ELF files are audited for >= 16 KB LOAD alignment.
- The final AAR is audited for complete non-system `DT_NEEDED` dependency closure before Gradle testing/building.
- The repaired APK is checked to contain both `libmpv.so` and the required `libxml2.so` before signing/artifact upload.

## R08 status

This repair corrects the packaging/runtime-loader defect only. R08 remains `IN_PROGRESS` until the repaired APK passes the Pixel 9 Pro XL / Android 16 resident-parameter acceptance checks in `R08_RESIDENT_GPU_PARAMETER_ARCHITECTURE.md`.
