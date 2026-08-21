# Chat Continuity Archive

This directory is a source-controlled continuity record for the Chrovelo / mpv Shader Lab project.

## Purpose

Chat sessions can be interrupted, compacted, or forced into a new thread. The files here preserve the project decisions, test results, active blockers, build fingerprints, branch/commit pointers, and next actions needed to continue work without reconstructing context from memory.

## What belongs here

- Project-relevant conversation summaries and checkpoints.
- Decisions that affect architecture or rendering fidelity.
- Device test results and exact acceptance/failure observations.
- Relevant commit SHAs, workflow run IDs, artifact IDs, binary fingerprints, and branch pointers.
- Open blockers and the next concrete action.
- User requirements that must survive across chats.

## What does NOT belong here

- Secrets, passwords, signing private keys, tokens, account credentials, or private personal data.
- Large binary artifacts already handled by GitHub Actions/releases or local build outputs.
- Irrelevant casual conversation.

## Imported/recovered records

- `2026-08-21-r08-continuity.md` — concise active R08 checkpoint and current renderer-parity blocker.
- `2026-08-21-r08-recovered-chat-exports.md` — reconstructed source-controlled handoff from the two user-supplied August 21 ChatGPT exports, including the R08 implementation milestones, exact R07 renderer fingerprint, renderer-parity decision, upstream miner results, and job `96881103884` next action.

## Important limitation

ChatGPT cannot automatically export every historical ChatGPT transcript from the account. This archive therefore contains the conversation/project context actually available to the active project/session plus future checkpoints created during development. If a complete historical ChatGPT export is supplied later, it can be imported into this directory as additional source-controlled records.

## Maintenance rule

For Shader Lab work, update the active continuity checkpoint whenever a meaningful build/test/debug milestone changes the state of the project, especially before ending a long session or advancing a roadmap step.

The architectural source of truth remains `docs/SHADER_LAB_REFACTOR_ROADMAP.md`. The execution-state source of truth remains `docs/SHADER_LAB_REFACTOR_PROGRESS.md`. These chat records supplement those documents rather than replacing them.
