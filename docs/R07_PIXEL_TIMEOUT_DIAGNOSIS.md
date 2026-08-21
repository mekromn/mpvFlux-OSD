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
- R07 is now narrowed to one of these remaining cases: the active Lua instance is not the newly managed R07 controller, the Lua `user-data` write/read path is failing, Android MPVLib cannot read/observe that user-data subtree correctly, or the full native-state payload is the problem while a small user-data value still works.

## R07 pre-init repair

Commit `ef2dc05b8c845509722304cfb26d7b501f891a44` changes controller activation to the pre-initialization libmpv path:

- R04 engine install/repair is performed from `MPVView.initOptions()` before mpv initialization.
- `/storage/emulated/0/mpv/scripts/pixel9-shader-lab.lua` is supplied through mpv's `script` option before initialization.
- engine preparation is idempotent across pre-init and later bridge attachment.
- runtime `load-script` remains only as a bounded fallback.
- full `:app:testStandardDebugUnitTest` passed in the guarded patched tree before the source commit was accepted.

## R07 state-2 transport discriminator

Commit `74371e19ca5efafefe1e85c202df429631b2080d` adds a device-only discriminator without changing shader behavior:

- engine/backend state version advances to `6.1.1-r07-state-2` / `6.1.1-source-r07-state-2`;
- Lua writes the tiny value `R07_STATE_2` to `user-data/p9lab/lua-probe`;
- Lua immediately reads that value back;
- Lua writes `/storage/emulated/0/mpv/logs/shaderlab-r07-lua-probe.txt` with script identity plus set/readback results;
- Android observes and reads the tiny Lua probe independently of the full native-state snapshot;
- if native-state still times out, the bridge proof includes `lua_probe=...` and a bounded read of the `user-data` root.

The guarded state-2 apply workflow verified engine-manifest size/hash integrity, full `:app:testStandardDebugUnitTest`, and `git diff --check` before committing the probe.

R07 remains `BLOCKED` until real Pixel evidence identifies/fixes the state transport and produces a healthy synchronized snapshot. R08 must not begin before that device gate passes.
