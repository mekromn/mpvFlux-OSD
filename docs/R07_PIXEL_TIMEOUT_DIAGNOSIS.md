# R07 Pixel timeout diagnosis

## Device result

Pixel 9 Pro XL / Android 16 device smoke on the post-regression-repair R07 APK reached the Android bridge but did not receive a Lua native-state snapshot.

Observed proof file:

```text
status=WAITING
stage=timeout
detail=No native-state snapshot after bounded load-script handshake
```

This proves the Android bridge attached and the staged diagnostic writer ran, while the Lua -> `user-data/p9lab/native-state` publication was not visible to Android.

## Diagnosis

- The readable controller contains the expected `p9lab-native-state` registration and native-state publisher.
- A dedicated Lua 5.4 syntax + mocked-mpv startup smoke executed the exact controller source successfully, confirmed `p9lab-native-state` registration, and confirmed startup publication of `__ready=1` / `__version=6.1.1-r07-state-1`.
- Therefore the failure was narrowed to runtime controller activation in the embedded Android/libmpv lifecycle rather than Lua syntax/registration or the Android property decoder.

## R07 repair

Commit `ef2dc05b8c845509722304cfb26d7b501f891a44` changes controller activation to the deterministic pre-initialization libmpv path:

- R04 engine install/repair is performed from `MPVView.initOptions()` before mpv initialization.
- `/storage/emulated/0/mpv/scripts/pixel9-shader-lab.lua` is supplied through mpv's `script` option before initialization.
- engine preparation is idempotent across pre-init and later bridge attachment.
- runtime `load-script` remains only as a bounded fallback.
- full `:app:testStandardDebugUnitTest` passed in the guarded patched tree before the source commit was accepted.

R07 remains `BLOCKED` until a new Pixel smoke produces `status=PASS` from `/storage/emulated/0/mpv/logs/shaderlab-r07-bridge-sync.txt`.
