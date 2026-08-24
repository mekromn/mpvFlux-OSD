# R07 Observable MPV / Lua Bridge Inventory

## Scope

R07 implements the concrete observable runtime bridge for the R06 semantic Shader Lab command API. It intentionally stops before R08 shader generation coalescing/rollback work.

## Android transport

`MpvShaderLabBridge` is a `ShaderLabCommandBackend` implementation that:

- owns a typed `StateFlow<ShaderLabBackendState>`;
- owns a typed bridge event stream for attachment, snapshots, and backend errors;
- registers a native `MPVLib.EventObserver` instead of a UI polling loop;
- observes `user-data/p9lab/native-state` as the complete Lua state envelope;
- observes `video-params/gamma` directly for prompt SDR/PQ/HLG classification;
- observes all six live mpv property controls directly (`sdr-intensity`, `brightness`, `contrast`, `gamma`, `saturation`, `hue`);
- serializes semantic command entry through one backend lock;
- maps R06 commands to `p9lab-native-*` script messages;
- decodes and clamps incoming control values through the R05 authoritative catalog;
- surfaces transport/backend failures as typed state/events rather than hiding them.

There is no periodic timer or fixed 200 ms polling loop in the Android bridge.

## Lua state publisher

The readable bundled `pixel9-shader-lab.lua` now publishes complete snapshots through:

`user-data/p9lab/native-state`

Snapshot metadata includes:

- ready/version/serial;
- active bank;
- bypass state;
- original-preview state;
- source gamma + SDR eligibility;
- active shader slot + swap count;
- apply-busy state;
- last backend error;
- all non-action control values;
- occupancy for all 10 user preset slots.

Publication is event-driven:

- script startup;
- file load;
- explicit Android handshake/refresh;
- before/after native operations that report apply-busy state;
- after native semantic mutations.

No periodic native-state timer was added.

## Runtime integration

- `ShaderLabModule` registers one `MpvShaderLabBridge` singleton, binds it as `ShaderLabCommandBackend`, and registers `ShaderLabCommandApi` against that backend.
- `MPVView.observeProperties()` attaches/re-attaches the bridge whenever libmpv establishes property observation.
- The R04 manifest version is bumped to `6.1.1-source-r07-state-1`, so the installer repairs/updates the readable Lua engine on the next app start.
- R07 does not assume the R04 reference `config/mpv.conf` is the user's active mpv configuration. On bridge attachment it first reconciles the R04 engine, then checks for an existing `user-data/p9lab/native-state` publisher. If none is active, it explicitly loads `/storage/emulated/0/mpv/scripts/pixel9-shader-lab.lua` and requests the native-state handshake. If a publisher already exists, it reuses it and does not load a duplicate controller.
- Engine preparation failures are surfaced through the same observable backend error state before MPV transport attachment.

## Device synchronization proof

For R07 device acceptance, a decoded ready snapshot writes:

`/storage/emulated/0/mpv/logs/shaderlab-r07-bridge-sync.txt`

The file is diagnostics-only and is never read as runtime state. A valid smoke-test file must include at least:

- `status=PASS`
- `backend_version=6.1.1-r07-state-1`
- a positive `snapshot_serial`
- source classification fields
- `control_count=53`
- backend error text (empty on a healthy run)

This proves the round trip: installed Lua engine -> mpv user-data property -> Android MPV observer -> typed decoder -> canonical workspace diagnostic file.

## Validation completed before final device smoke

- Isolated bridge/fake-transport unit compile gate: PASS.
- Guarded Lua/transport wiring workflow: PASS.
- Engine manifest integrity on the patched tree: PASS.
- Full `:app:testStandardDebugUnitTest` on the wired tree: PASS.
- Event-driven state transport commit: `67cf5a49fb03d206879bba75572969c0086d147a`.
- Initial signed wired build on head `7787fe64896b87c1fe39f2559acbfc2007c68114`: PASS after rerun; normal CI #46: PASS.
- Activation-gap audit found that R04's reference `config/mpv.conf` is not guaranteed to become the player's active root config.
- First guarded activation attempt correctly failed compile before commit because Koin type inference was ambiguous; no broken activation code landed.
- Corrected guarded activation attempt: PASS, including full Shader Lab unit tests.
- Canonical-controller activation commit: `ce39417e45e01ca5d198a84b8bd4cc0470f88272`.

A documentation-only commit after the activation code is used to trigger final signed `Refactor Dev APK` and normal multi-ABI CI against the exact landed R07 implementation before the Pixel synchronization smoke test.
