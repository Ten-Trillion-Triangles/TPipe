# TPipe Memory System Phase 2 Remediation Summary

**Date:** 2026-03-13  
**Status:** Implemented  
**Related artifacts:**  
- `md/memory-system-thread-safety-audit.md`  
- `md/memory-system-thread-safety-remediation-summary-2026-03-13.md`  
- `md/memory-system-phase-2-audit-2026-03-13.md`  

---

## Purpose

This document records the phase-2 memory-system remediation work that followed the stage-1 `ContextBank` / `ContextLock` hardening pass.

Phase 2 focused on the remaining system-level risks that were still active after stage 1:

- token-budget runtime state drifting across repeated executions
- lorebook selection paths that did not honor remote lock state
- provider-specific truncation paths still using local-only lorebook selection
- container and execution docs overstating automatic memory-management behavior
- missing regression coverage for repeated internal reuse and remote-aware lorebook locking

This was intentionally **not** a full architectural rewrite. The intended ownership model remained unchanged:

- separate built instances may run concurrently
- a single `Pipe`, `Pipeline`, or container instance is still treated as a mutable single-owner execution object
- this phase documents that contract, but does not add hard runtime blocking or warning behavior

---

## Scope Completed

### 1. Remote-aware lorebook selection in `ContextWindow`

`ContextWindow` gained suspend-native selection helpers so internal execution paths can honor remote `ContextLock` state instead of relying on the older local-only sync helpers.

Implemented additions:

- `findMatchingLoreBookKeysSuspend(...)`
- `selectLoreBookContextSuspend(...)`
- `selectAndFillLoreBookContextSuspend(...)`
- `selectLoreBookContextWithSettingsSuspend(...)`
- `selectAndFillLoreBookContextWithSettingsSuspend(...)`
- `selectAndTruncateContextSuspend(...)`
- `combineAndTruncateAsStringWithSettingsSuspend(...)`
- `canSelectLoreBookKeySuspend(...)`

Key behavior changes:

- remote lock state is now consulted through `ContextLock.isKeyLockedSuspend(...)`
- passthrough functions still remain supported
- sync helpers remain available, but their documentation now explicitly states that they are local-only compatibility paths

This closes the earlier correctness gap where synchronous lorebook selection and truncation could include remotely locked keys.

Primary file:

- `src/main/kotlin/Context/ContextWindow.kt`

---

### 2. Token-budget runtime state restore in `Pipe`

The main sequential correctness bug in phase 2 was that token-budget execution could rewrite long-lived `Pipe` fields during a run and leave later executions with mutated state.

That issue affected:

- `maxTokens`
- `contextWindowSize`
- mutable nested `TokenBudgetSettings` members such as `userPromptSize`

Implemented fix:

- added a detached copy helper for `TokenBudgetSettings`
- changed `setTokenBudget(...)` to store a cloned internal budget object instead of retaining the caller’s original mutable object
- added a `TokenBudgetExecutionSnapshot` internal snapshot type
- added capture/restore helpers for execution-time budget state
- wrapped runtime budget truncation in `try/finally` so mutable execution fields are restored after each run

Important implementation choice:

- this phase used **snapshot-and-restore**
- it did **not** attempt a larger execution-local runtime-state refactor
- this preserved current in-flight behavior while fixing sequential drift

That choice reduced regression risk for:

- `Pipe.repeatPipe` loops
- repeated reuse of the same `Pipe` inside a `Pipeline`
- repeated manager / worker reuse inside `Manifold`

Primary file:

- `src/main/kotlin/Pipe/Pipe.kt`

---

### 3. Suspend-safe provider truncation hook

Phase 2 also found that provider-specific truncation implementations could still bypass the new remote-aware lorebook path because `truncateModuleContext()` was synchronous and called sync `ContextWindow` helpers.

Implemented change:

- added `truncateModuleContextSuspend()` to `Pipe`
- default implementation falls back to the legacy synchronous `truncateModuleContext()`
- execution-time auto-truncation now calls the suspend-safe hook

Provider updates:

- Bedrock now overrides `truncateModuleContextSuspend()`
- Ollama now overrides `truncateModuleContextSuspend()`

Those overrides now use the new suspend-aware `ContextWindow` truncation helpers so remote lock visibility remains consistent during provider-managed prompt assembly.

Primary files:

- `src/main/kotlin/Pipe/Pipe.kt`
- `TPipe-Bedrock/src/main/kotlin/bedrockPipe/BedrockPipe.kt`
- `TPipe-Ollama/src/main/kotlin/ollamaPipe/OllamaPipe.kt`

---

### 4. Ownership and behavior documentation

Phase 2 intentionally preserved the existing ownership model instead of adding hard runtime guards.

Documentation changes now state clearly that:

- `Pipe` instances are mutable execution objects
- `Pipeline` instances are mutable orchestration objects
- `Manifold` reuses its configured pipelines across loop iterations
- fresh instances should be built for concurrent top-level runs

This was added in:

- `src/main/kotlin/Pipe/Pipe.kt`
- `src/main/kotlin/Pipeline/Pipeline.kt`
- `src/main/kotlin/Pipeline/Manifold.kt`
- `docs/core-concepts/pipe-class.md`
- `docs/core-concepts/pipeline-class.md`
- `docs/containers/manifold.md`

No hard-fail guards or runtime warnings were added in this phase.

---

### 5. Docs narrowed to match actual container behavior

`Manifold` and `DistributionGrid` were called out in the audit because their docs implied broader automatic memory-management behavior than the code actually delivered.

Implemented documentation corrections:

- `Manifold` docs now describe its built-in truncation as working-converse-history truncation, not general cross-agent memory compression
- `DistributionGrid` class docs now explicitly state that token compression, summarization, and execution orchestration are not implemented yet

Primary files:

- `src/main/kotlin/Pipeline/Manifold.kt`
- `src/main/kotlin/Pipeline/DistributionGrid.kt`
- `docs/containers/manifold.md`

This keeps the docs truthful without expanding phase 2 into a new container-feature implementation project.

---

## Files Changed

### Kotlin runtime / behavior

- `src/main/kotlin/Context/ContextWindow.kt`
- `src/main/kotlin/Pipe/Pipe.kt`
- `src/main/kotlin/Pipeline/Pipeline.kt`
- `src/main/kotlin/Pipeline/Manifold.kt`
- `src/main/kotlin/Pipeline/DistributionGrid.kt`
- `TPipe-Bedrock/src/main/kotlin/bedrockPipe/BedrockPipe.kt`
- `TPipe-Ollama/src/main/kotlin/ollamaPipe/OllamaPipe.kt`

### Tests

- `src/test/kotlin/TokenBudgetRuntimeStateTest.kt`
- `src/test/kotlin/Context/ContextWindowRemoteLockTest.kt`

### Docs

- `docs/core-concepts/pipe-class.md`
- `docs/core-concepts/pipeline-class.md`
- `docs/containers/manifold.md`

---

## Testing Added

### `TokenBudgetRuntimeStateTest`

Added new regression coverage for:

- repeated execution of the same `Pipe` instance
- repeated execution of the same `Pipe` instance through a `Pipeline`
- verification that budget-related state is restored after each execution

Assertions cover:

- `maxTokens`
- `contextWindowSize`
- `TokenBudgetSettings` content

### `ContextWindowRemoteLockTest`

Added new regression coverage for:

- remote lock registration through the memory server path
- sync lorebook selection remaining local-only
- suspend-native lorebook selection excluding remotely locked keys

This test validates the specific phase-2 correctness goal that remote-aware selection now behaves differently and correctly where required.

---

## Validation Run

The following validation command was run successfully:

```bash
./gradlew :TPipe-Bedrock:compileKotlin :TPipe-Ollama:compileKotlin :test --tests com.TTT.TokenBudgetRuntimeStateTest --tests com.TTT.Context.ContextWindowRemoteLockTest --tests com.TTT.MultiPageTokenBudgetTest --tests com.TTT.LoreBookTest --tests com.TTT.ContextWindowTest
```

This confirmed:

- the new suspend-safe provider hooks compile in Bedrock and Ollama
- the new token-budget restore tests pass
- the new remote-lock lorebook test passes
- existing lorebook and token-budget tests exercised in this pass remain green

---

## Formatter / Style Compliance

All touched Kotlin files were updated under the TTT style-guide requirements:

- vertical bracing for constructs with parentheses
- same-line braces preserved where Kotlin DSL / scope-function exceptions apply
- no extra space before `(`
- type declaration spacing preserved
- KDoc added or updated on touched public APIs and behavior-sensitive helpers
- inline comments used only where the concurrency / budgeting behavior would otherwise be unclear

Style source of truth used during implementation:

- `/home/cage/.codex/skills/formatter/references/TTT_STYLE_GUIDE.md`

---

## Explicitly Deferred

The following items were intentionally **not** implemented in phase 2:

- hard-fail or warning-based self-concurrency enforcement
- execution-local runtime-state rewrite for `Pipe` / `Pipeline`
- provider/model-calibrated tokenizer enforcement changes
- safety-margin policy changes for `Dictionary`
- generalized worker-memory summarization in `Manifold`
- any real execution / summarization implementation for `DistributionGrid`

Those remain follow-up work if the broader architecture or product direction requires them.

---

## Net Result

Phase 2 materially improved the deeper memory system without changing TPipe’s intended instance-ownership model.

After this pass:

- token-budget execution no longer leaves behind mutated runtime state across later runs
- remote-aware lorebook lock handling is available and used in execution-time truncation paths
- provider-specific truncation paths no longer bypass remote lock visibility
- docs are more truthful about instance ownership and container memory-management behavior
- regression coverage exists for the specific phase-2 bugs that were fixed

This closes the main phase-2 issues identified in the audit while keeping the implementation conservative enough to avoid a large architectural regression surface.
