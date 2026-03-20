# Junction Memory Governance Parity

## Purpose

Junction should preserve TPipe's memory discipline when building outbound requests for participants, moderators, and workflow roles.

The harness keeps authoritative run state internally, then emits bounded role-specific envelopes for downstream containers. Optional summarization is allowed only as an additive support mechanism for older history tails.

## Current Status

- `JunctionMemoryPolicy` exists in `src/main/kotlin/Pipeline/JunctionMemoryModels.kt`.
- `Junction` now resolves outbound budgets from harness policy plus the target participant's advertised limits.
- Outbound requests for discussion and workflow roles use compact memory envelopes instead of raw full-state dumps.
- The prompt path preserves `ContextWindow` and `MiniBank` payloads for downstream P2P participants.
- Optional summarization is supported through `JunctionMemoryPolicy.summarizer`, but deterministic compaction remains the primary safety mechanism.
- Regression coverage now proves:
  - discussion prompts are compacted and can include a deterministic summary tail
  - workflow prompts are compacted and can include a deterministic summary tail
  - impossible outbound budgets fail fast before dispatch

## Requirements

- Preserve the full discussion/workflow state internally.
- Never forward unresolved overflow to child containers.
- Use deterministic compaction first.
- Allow optional summarization for older history only.
- Fail fast when the outbound request cannot be made safe.

## Open Follow-Ups

- Confirm the live Bedrock Junction integration still passes after memory-budget changes.
