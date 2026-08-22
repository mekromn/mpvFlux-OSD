# Chrovelo / Shader Lab Conversation Archive

This directory is the repository-side continuity record for the Chrovelo / mpvFlux-OSD Pixel Shader Lab project.

## Purpose

Chat sessions can be split, compacted, or forced into a new conversation. Important engineering decisions must therefore not exist only in chat history. This archive duplicates the project context that is available to the active assistant into source control so a future session can reconstruct why the current branch looks the way it does.

This archive complements, but does not replace:

- `docs/SHADER_LAB_REFACTOR_ROADMAP.md` — architecture, invariants, acceptance criteria, and ordered roadmap.
- `docs/SHADER_LAB_REFACTOR_PROGRESS.md` — current execution state and blockers.
- Git history and GitHub Actions — exact source changes and build/test evidence.

## Privacy / security rule

Never archive passwords, access tokens, reusable signing keys, private signing material, credentials, account identifiers, or other secrets. Reference the existence of a secret-backed workflow without copying secret values.

## Archive format

Historical material that is available to the active session is recorded as dated project-continuity snapshots. These are normalized engineering records rather than guaranteed byte-for-byte ChatGPT exports when the original transcript is no longer directly available.

For ongoing work, append a dated checkpoint containing:

1. The user's request or correction that materially changed the project.
2. Decisions made in response.
3. Branch / commit / workflow / artifact identifiers that matter for continuation.
4. Device test observations and pass/fail state.
5. Known blockers and the exact next action.
6. Anything explicitly deferred for a later roadmap step.

Do not use this archive as permission to skip the roadmap/progress files. A future agent should read, in order:

1. `AGENTS.md`
2. `docs/SHADER_LAB_REFACTOR_ROADMAP.md`
3. `docs/SHADER_LAB_REFACTOR_PROGRESS.md`
4. this directory's latest dated continuity snapshot

## Current snapshots

- `2026-08-21-project-continuity.md` — consolidated project history through the R08 renderer-parity investigation.
