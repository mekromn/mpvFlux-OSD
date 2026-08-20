# R02 Shader Lab v6.1.1 Migration Inventory

## Purpose

This inventory records the legacy Shader Lab implementation being normalized onto the latest-source refactor branch. R02 is source normalization only; these assets are **not wired into playback yet**.

## Legacy Android wrapper files

- `app/src/main/java/app/marlboroadvance/mpvex/ui/player/ShaderLabRuntime.kt` — reconstructed eight Base64 chunks into a ZIP, verified payload SHA-256, extracted to app-private storage, rewrote `/storage/emulated/0/mpv` paths to private storage, removed `script=`/`input-conf=` lines, and injected the native-state bridge into the Lua controller.
- `app/src/main/java/app/marlboroadvance/mpvex/ui/player/ShaderLabBridge.kt` — Android control catalog plus semantic-ish `script-message` commands, preset/morph commands, and native-state parsing.
- `app/src/main/java/app/marlboroadvance/mpvex/ui/player/ShaderLabStateBus.kt` — event-driven `StateFlow` copy of `user-data/p9lab/native-state`.
- `app/src/main/assets/mpvlab/payload/workstation.b64.00` … `.07` — opaque Base64 representation of the legacy workstation ZIP; payload SHA-256 `e498dfebbec204b264fb00bf5a39f9df70ecec6f87bc34fdc224cfc14653dcc6`.

## Normalization decision

- The canonical R02 source is the **original v6.1.1 workstation archive before `ShaderLabRuntime` private-runtime patching**.
- This intentionally keeps the workstation’s `/storage/emulated/0/mpv` assumptions because R03 will implement that path as the canonical Android workspace.
- The legacy Kotlin-injected native-state publisher/apply scheduler is documented behavior, not silently injected into these files. Its useful semantics will be reimplemented cleanly in the typed command/state architecture in later roadmap steps.
- The legacy branch remains unchanged and is the behavioral reference if an exact implementation detail needs to be recovered.

## Extracted normalized files

- `config/input.conf` — `4cb8d46deb3fafd1b9b4fae7ce0c89d224beceab3351e3abab8530499187e8e4`
- `config/mpv.conf` — `c889f787c341bc258eb55ebb349b1adec0f2382aa1fdd73806529292b337da01`
- `docs/README.legacy-v6.1.1.txt` — `9e8a98918b000292adfe34271b79e4a5e0017a51cf9e91d5b5a0bebd440a7b55`
- `misc/mpv-touch-diagnostic.conf` — `27a0c177b1c7ef22d4575b49c6fad1dc24c7065952bdf9bb825708733189dfec`
- `misc/state/README.txt` — `0108b08aa285ecdc55c7ff1aa77068be37c39b8807c9c1fbcaa4a639748d0559`
- `scripts/mpvflux-touch-diagnostic.lua` — `e5c1f26ce49b478d46d4bef736772bd25f405d385078577b4e702cf2318ae9a6`
- `scripts/pixel9-shader-lab.lua` — `9d83395fa948142f7274452245c20e701dc858bbc9ce6eadc6a7dffae3299542`
- `shaders/pixel9-perceptual-expansion-known-good-v3.1.glsl` — `1663f54be13678688f4babfd68b360c1797b1057101b3c460c663ea66694c988`
- `shaders/pixel9-perceptual-expansion-runtime-a.glsl` — `1663f54be13678688f4babfd68b360c1797b1057101b3c460c663ea66694c988`
- `shaders/pixel9-perceptual-expansion-runtime-b.glsl` — `1663f54be13678688f4babfd68b360c1797b1057101b3c460c663ea66694c988`
- `shaders/pixel9-perceptual-expansion-template.glsl.txt` — `bca434069957344e919fabe6a9f5033a3c9b8e9da5734f4405f585cccd0c5ed7`

## Legacy behavior that must survive later refactors

- Pixel 9 SDR/HDR guard and `vo=gpu` expanded-brightness workflow.
- Live shader and MPV-property tuning.
- Atomic/double-slot shader application and last-known-good behavior.
- Bypass and press-to-preview-original.
- Video-start revert, reset, save/load state.
- Ten user presets, built-in presets, and preset morphing.
- Gamut/luma clipping diagnostics and shader-reload proof mode.
- Fine/normal/coarse step modes and grouped controls.
- Event-driven native state (`user-data/p9lab/native-state`) rather than UI polling.

## Explicitly not carried forward as architecture

- Base64 workstation packaging.
- App-private mpvLab workspace as the canonical user source of truth.
- Runtime text surgery of the Lua controller.
- Monolithic Android bridge/control catalog.
- Fixed-interval UI polling or global-gesture ownership of Shader Lab controls.

## Integrity

`app/src/main/assets/mpvlab/source/engine-manifest.json` records every normalized source file SHA-256 plus the legacy payload provenance. Run `python3 tools/verify_mpvlab_manifest.py` to verify the tree.
