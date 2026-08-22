# R07 Pixel timeout diagnosis

## Device result

Pixel 9 Pro XL / Android 16 device smoke on the post-regression-repair R07 APK reached the Android bridge but did not receive a Lua native-state snapshot.

Initial observed proof file:

```text
status=WAITING
stage=timeout
detail=No native-state snapshot after bounded load-script handshake
```

After the pre-initialization controller-load repair, a fresh Pixel smoke on the correct APK still timed out:

```text
status=WAITING
stage=timeout
detail=No native-state snapshot after pre-init script option and bounded load-script fallback
```

The user also confirmed that pressing the restored Science/Labs OSD button opens the legacy-compatible Shader Lab workstation. That is significant device evidence: Lua scripting is alive and Android -> `script-message p9lab-toggle-ui` reaches a running Shader Lab controller. The remaining failure is therefore narrower than generic controller activation.

## Diagnosis

- The readable controller contains the expected `p9lab-native-state` registration and native-state publisher.
- A dedicated Lua 5.4 syntax + mocked-mpv startup smoke executed the exact controller source successfully, confirmed `p9lab-native-state` registration, and confirmed startup publication of `__ready=1` / `__version=6.1.1-r07-state-1`.
- Pre-init `script` loading plus bounded runtime `load-script` fallback did not make the native-state snapshot visible to Android on the Pixel.
- The working Labs-button message proves a Lua controller is active and the host -> Lua script-message transport works.

## R07 pre-init repair

Commit `ef2dc05b8c845509722304cfb26d7b501f891a44` changes controller activation to the pre-initialization libmpv path:

- R04 engine install/repair is performed from `MPVView.initOptions()` before mpv initialization.
- `/storage/emulated/0/mpv/scripts/pixel9-shader-lab.lua` is supplied through mpv's `script` option before initialization.
- engine preparation is idempotent across pre-init and later bridge attachment.
- runtime `load-script` remains only as a bounded fallback.
- full `:app:testStandardDebugUnitTest` passed in the guarded patched tree before the source commit was accepted.

## R07 state-2 transport discriminator

Commit `74371e19ca5efafefe1e85c202df429631b2080d` added a device-only discriminator without changing shader behavior:

- engine/backend state version advanced to `6.1.1-r07-state-2` / `6.1.1-source-r07-state-2`;
- Lua wrote the tiny value `R07_STATE_2` to `user-data/p9lab/lua-probe` and read it back;
- Lua wrote `/storage/emulated/0/mpv/logs/shaderlab-r07-lua-probe.txt` with script identity plus set/readback results;
- Android independently read the tiny Lua probe and the string-converted `user-data` root.

### Pixel state-2 result

The real Pixel probe produced:

```text
script=R07_STATE_2
set_ok=true
readback="R07_STATE_2"
```

At the same time, the Android bridge timed out on the direct native-state leaf but its diagnostic read of the `user-data` root contained:

- `lua-probe: R07_STATE_2`;
- `native-state` beginning with `__ready=1`;
- backend version `6.1.1-r07-state-2`;
- positive snapshot serial (`2` in the captured timeout);
- normal Shader Lab state fields.

This is decisive: the correct R07 Lua controller is executing, Lua -> mpv user-data writes work, and Android MPVLib can read the complete state through the string-converted top-level `user-data` map. The remaining defect is specific to this Android MPVLib wrapper's direct string access/observation of the multiline `user-data/p9lab/native-state` leaf.

## R07 state-3 root-map compatibility repair

Commit `344abbcb101a982ca3fceec10d7b562879369b99` implements the compatibility fix while preserving event-driven transport:

- keeps direct `user-data/p9lab/native-state` access as the preferred path;
- additionally observes the top-level `user-data` property;
- falls back to extracting and JSON-unescaping `p9lab.native-state` from the root map when direct leaf string access is unavailable;
- uses the same root fallback during bounded handshake readback, so no periodic UI polling is introduced;
- removes the temporary state-2 `lua-probe` property/file instrumentation;
- advances the managed controller/backend to `6.1.1-r07-state-3` / `6.1.1-source-r07-state-3`;
- adds unit coverage for initial root-map recovery and event-driven root-map snapshot updates.

The corrected guarded apply passed engine-manifest integrity, the full `:app:testStandardDebugUnitTest` suite, and `git diff --check` before committing the repair. The first attempted guarded patch was correctly prevented from committing because of a Kotlin escape error in a temporary regex implementation; the accepted repair replaced that with a small explicit JSON-string extractor.

R07 remains `BLOCKED` only until the state-3 signed APK is smoke-tested on the Pixel and `/storage/emulated/0/mpv/logs/shaderlab-r07-bridge-sync.txt` reports a healthy synchronized snapshot. R08 must not begin before that device gate passes.
