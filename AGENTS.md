# AGENTS.md

## Shader Lab / mpvLab work

For any Shader Lab, mpvLab, touch-control, Android TV remote, shader-runtime, Pixel HDR/SDR expansion, or related player-control work in this repository:

1. Read `docs/SHADER_LAB_REFACTOR_ROADMAP.md` before changing code.
2. Read `docs/SHADER_LAB_REFACTOR_PROGRESS.md` when it exists; it records partial/blocked execution state and temporarily overrides the roadmap's step status/pointer when the two differ.
3. Treat the roadmap as the source of truth for architecture and constraints, and the progress file as the source of truth for the latest execution state.
4. `agent/upstream-refactor` is the clean working branch.
5. `agent/native-shader-lab` is a read-only behavioral reference. Do not wholesale cherry-pick/rebase its legacy player/control changes onto the clean branch.
6. When the user says **“Continue roadmap”**, execute exactly one roadmap step. If the progress file marks the current step `BLOCKED`, retry/resolve that step before advancing. Otherwise execute the first `TODO` step identified by `CURRENT_STEP`.
7. Do not start a second roadmap step in the same prompt unless the user explicitly asks to combine steps.
8. At the beginning of each implementation prompt, check whether `master` or immediate upstream `Muhammedahmed18/mpvFlux` advanced. Integrate source updates before feature work when necessary.
9. At the end of each completed step:
   - run the step's validation;
   - update the roadmap status and notes;
   - update/remove any temporary blocker in the progress file;
   - record relevant commit/workflow SHA or result;
   - advance `CURRENT_STEP` to the next `TODO` step;
   - commit the roadmap/progress update with the code.
10. Never commit reusable signing keys or private signing material.
11. Preserve the project requirements documented in the roadmap, especially Pixel 9 Pro XL / Android 16 behavior, `vo=gpu` expanded-brightness path, canonical `/storage/emulated/0/mpv` workspace, native touch lifecycle, immediate release cancellation, and Android TV/D-pad support.

If a requested change conflicts with the roadmap, follow the user's newest explicit instruction and update the roadmap so future work does not revert that decision.
