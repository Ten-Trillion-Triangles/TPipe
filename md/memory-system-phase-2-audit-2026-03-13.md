# TPipe Memory System Phase 2 Audit

**Date:** 2026-03-13  
**Status:** Open - Phase 1 landed, Phase 2 still required  
**Scope:** Execution ownership, token budgeting, automatic memory management, lorebook/context interaction, and deeper memory-system behavior beyond the ContextBank / ContextLock stage-1 remediation.

---

## Purpose

This audit exists to capture the remaining work after the stage-1 memory hardening pass so the next discussion can happen in plan mode against a stable artifact.

The goal of phase 2 is not to re-audit the already-fixed `ContextBank` and `ContextLock` storage primitives. The goal is to identify the remaining places where TPipe still relies on mutable execution state, heuristic token accounting, incomplete automatic memory management, or implicit single-owner assumptions that are not enforced by code.

---

## Inputs Reviewed

### Existing audit / remediation artifacts

- `md/memory-system-thread-safety-audit.md`
- `md/memory-system-thread-safety-remediation-summary-2026-03-13.md`

### Latest branch commit

- `2f2a61f` - `stage 1. Fix main memory systems.`

### Repository rules / context consulted

- `README.md`
- `.amazonq/rules/TPipe-Formatting.md`
- `.amazonq/rules/Test.md`
- `AGENTS.md` instructions provided for this repo in the current session

---

## Key Conclusion

**Phase 1 fixed the bank/lock substrate, but it did not finish the broader memory system.**

The latest commit hardened the shared memory repository and some internal callers, but the wider execution stack still contains unresolved risks in:

- `Pipe` execution ownership and mutable runtime state
- `Pipeline` execution ownership and shared context publication
- token-budget state mutation across executions
- synchronous lorebook selection that does not honor remote lock state
- incomplete automatic history compression / summarization systems
- heuristic token counting used as a hard safety boundary

The latest commit did **not** touch these deeper files:

- `src/main/kotlin/Context/ContextWindow.kt`
- `src/main/kotlin/Context/LoreBook.kt`
- `src/main/kotlin/Context/MiniBank.kt`
- `src/main/kotlin/Pipeline/Pipeline.kt`
- `src/main/kotlin/Pipeline/Manifold.kt`
- `src/main/kotlin/Pipeline/DistributionGrid.kt`
- `src/main/kotlin/Context/Dict.kt`

That absence matters because most of the remaining risks live there or in how those files interact with the now-hardened bank layer.

---

## Phase 1 Boundary

### Closed or materially improved in phase 1

The following areas were meaningfully improved by the recent remediation and should be treated as the stable foundation for phase 2:

- thread-safe shared storage inside `ContextBank`
- suspend-safe page / todo access
- keyed snapshotting and mutation via `ContextBank`
- suspend-safe `ContextLock` behavior
- removal of blocking internal memory flows from the main bank / lock paths
- initial concurrency coverage for bank-level operations

Strong evidence:

- `src/main/kotlin/Context/ContextBank.kt:906`
- `src/main/kotlin/Context/ContextBank.kt:978`
- `src/main/kotlin/Context/ContextLock.kt:403`

### Not closed by phase 1

Phase 1 did **not** convert the wider execution system into an immutable or re-entrant model. `Pipe`, `Pipeline`, `Manifold`, `DistributionGrid`, `Dictionary`, and the mutable context model types remain partially or fully unchanged.

---

## Subsystem Status

### 1. `ContextWindow.kt`

**Status:** Partially acceptable as a single-owner model, but not fully closed.

### What appears acceptable

`ContextWindow` is still a mutable model with:

- `loreBookKeys` at `src/main/kotlin/Context/ContextWindow.kt:23`
- `contextElements` at `src/main/kotlin/Context/ContextWindow.kt:29`
- `converseHistory` at `src/main/kotlin/Context/ContextWindow.kt:36`
- `metaData` at `src/main/kotlin/Context/ContextWindow.kt:53`

That is not automatically a bug. Under the new `ContextBank` regime, banked pages are now returned as snapshots or mutated under a page mutex:

- `src/main/kotlin/Context/ContextBank.kt:906`
- `src/main/kotlin/Context/ContextBank.kt:978`

So `ContextWindow` does **not** currently need to become internally synchronized just to support global page storage.

### What is still open

#### A. Remote lock state is still bypassed during synchronous lorebook selection

`ContextWindow.canSelectLoreBookKey(...)` calls the synchronous lock API:

- `src/main/kotlin/Context/ContextWindow.kt:1432`
- `src/main/kotlin/Context/ContextWindow.kt:1452`

But the synchronous `ContextLock.isKeyLocked(...)` intentionally checks **local state only**:

- `src/main/kotlin/Context/ContextLock.kt:391`

Remote-aware lock checks only exist on the suspend path:

- `src/main/kotlin/Context/ContextLock.kt:403`

This means synchronous lorebook selection / truncation paths can still include lorebook keys that are remotely locked.

That is a real correctness gap, not just documentation debt.

#### B. The class is still heavily mutation-oriented

In-place mutation remains core behavior:

- merge logic mutates this instance at `src/main/kotlin/Context/ContextWindow.kt:499`
- context truncation mutates this instance at `src/main/kotlin/Context/ContextWindow.kt:576`
- lorebook cleanup mutates and rebuilds entries at `src/main/kotlin/Context/ContextWindow.kt:1398`

That is acceptable only if caller ownership remains exclusive. Today that ownership is not enforced by the higher-level execution classes.

### Phase 2 judgment

Phase 2 should **not** start by adding locks inside `ContextWindow`. It should instead:

- fix remote-aware lorebook lock selection
- document and enforce single-owner semantics
- reduce direct shared-reference mutation paths in `Pipe` and `Pipeline`

---

### 2. `LoreBook.kt`

**Status:** No direct standalone concurrency bug found; inherits safety from `ContextWindow` ownership.

`LoreBook` is a mutable payload object:

- `src/main/kotlin/Context/LoreBook.kt:17`
- `src/main/kotlin/Context/LoreBook.kt:25`
- `src/main/kotlin/Context/LoreBook.kt:34`
- `src/main/kotlin/Context/LoreBook.kt:50`
- `src/main/kotlin/Context/LoreBook.kt:58`
- `src/main/kotlin/Context/LoreBook.kt:66`

I did **not** find evidence that bare `LoreBook` instances are independently shared through a concurrent global repository outside their enclosing `ContextWindow`.

### Phase 2 judgment

No dedicated `LoreBook` synchronization pass is warranted right now. If `ContextWindow` ownership is enforced properly, `LoreBook` can remain a plain mutable model.

Recommended phase-2 work here is limited to:

- better documentation of ownership expectations
- ensuring lorebook selection paths use correct lock visibility
- verifying no direct reference escapes bypass the `ContextWindow` ownership model

---

### 3. `MiniBank.kt`

**Status:** File-level logic is simple, but the system-level ownership story is still open.

`MiniBank` is a mutable multi-page container:

- `src/main/kotlin/Context/MiniBank.kt:9`
- `src/main/kotlin/Context/MiniBank.kt:30`

Its merge logic mutates `contextMap` and nested `ContextWindow` instances in place:

- `src/main/kotlin/Context/MiniBank.kt:36`
- `src/main/kotlin/Context/MiniBank.kt:39`
- `src/main/kotlin/Context/MiniBank.kt:43`

That is acceptable only if a `MiniBank` has a single execution owner.

### Why this is not fully closed

`Pipe` directly mutates its owned `miniContextBank` during execution:

- global page pulls append to it at `src/main/kotlin/Pipe/Pipe.kt:4940`
- banked context may also be inserted at `src/main/kotlin/Pipe/Pipe.kt:4947`
- pre-validation can replace it at `src/main/kotlin/Pipe/Pipe.kt:5042`
- token budgeting writes back truncated copies at `src/main/kotlin/Pipe/Pipe.kt:5138`

This means `MiniBank` remains safe only if the containing `Pipe` is not reused concurrently.

### Phase 2 judgment

`MiniBank` does not need internal locking as a first move. The real phase-2 work is:

- enforce exclusive ownership per executing `Pipe`
- decide whether `MiniBank` should become an immutable execution snapshot instead of a long-lived mutable field
- add tests for repeated / concurrent execution against the same pipe instance

---

## Critical Open Work Outside the Model Files

### 4. `Pipe.kt` execution ownership is still unsafe

**Status:** Open and high priority.

`Pipe` still stores execution state in mutable fields:

- `contextWindow` at `src/main/kotlin/Pipe/Pipe.kt:765`
- `miniContextBank` at `src/main/kotlin/Pipe/Pipe.kt:778`
- `tokenBudgetSettings` at `src/main/kotlin/Pipe/Pipe.kt:801`

During `executeMultimodal(...)`, these fields are mutated directly:

- global context load at `src/main/kotlin/Pipe/Pipe.kt:4928`
- page pulls into minibank at `src/main/kotlin/Pipe/Pipe.kt:4940`
- pipeline context merge at `src/main/kotlin/Pipe/Pipe.kt:4989`
- parent-pipe context merge at `src/main/kotlin/Pipe/Pipe.kt:4995`
- pre-validation replacement at `src/main/kotlin/Pipe/Pipe.kt:5019`
- minibank replacement at `src/main/kotlin/Pipe/Pipe.kt:5042`
- budget-truncated context writeback at `src/main/kotlin/Pipe/Pipe.kt:5137`
- budget-truncated minibank writeback at `src/main/kotlin/Pipe/Pipe.kt:5138`

There is no execution mutex or explicit re-entrancy guard on `Pipe.execute(...)`.

### Why this matters

The bank layer is now safer, but `Pipe` can still corrupt or cross-contaminate execution-local memory if the same instance is reused concurrently.

This is the most important phase-2 ownership issue.

### Required phase-2 decision

Plan mode needs to choose one of these approaches explicitly:

1. `Pipe` is **single-execution-owner only**, and code should enforce or loudly reject concurrent execution.
2. `Pipe` must become **re-entrant**, and execution-local state must move out of the instance into a dedicated runtime state object.

Without that decision, the memory hardening remains incomplete.

---

### 5. `Pipeline.kt` still carries mutable shared execution state

**Status:** Open and high priority.

`Pipeline.executeMultimodal(...)` mutates shared pipeline state during execution:

- pre-validation operates on shared `context` / `miniBank` at `src/main/kotlin/Pipeline/Pipeline.kt:1124`
- `currentPipeIndex` reset at `src/main/kotlin/Pipeline/Pipeline.kt:1147`
- `pipelineTokenUsage` reset at `src/main/kotlin/Pipeline/Pipeline.kt:1150`
- pipeline context replaced from generated output at `src/main/kotlin/Pipeline/Pipeline.kt:1337`
- global publication follows at `src/main/kotlin/Pipeline/Pipeline.kt:1342`
- pipeline content snapshot stored back on the instance at `src/main/kotlin/Pipeline/Pipeline.kt:1376`

### Why this matters

Even if individual `Pipe` objects are fixed, `Pipeline` still behaves like a mutable runtime container instead of a re-entrant execution engine.

### Required phase-2 decision

Same as `Pipe`: either

- enforce single-execution ownership for a pipeline instance, or
- split execution-local state out of the object.

---

### 6. Token budgeting mutates configuration and runtime state

**Status:** Open and high priority.

This is one of the clearest phase-2 defects.

`setTokenBudgetInternal(...)` mutates long-lived pipe fields:

- context window changes at `src/main/kotlin/Pipe/Pipe.kt:2407`
- max token changes at `src/main/kotlin/Pipe/Pipe.kt:2439`
- derived context window overwrite at `src/main/kotlin/Pipe/Pipe.kt:2470`

`truncateToFitTokenBudget(...)` then mutates budget settings during execution:

- dynamic user prompt assignment at `src/main/kotlin/Pipe/Pipe.kt:4395`
- dynamic prompt shrink at `src/main/kotlin/Pipe/Pipe.kt:4408`
- binary-overflow shrink at `src/main/kotlin/Pipe/Pipe.kt:4452`
- dynamic-only reset at `src/main/kotlin/Pipe/Pipe.kt:4646`
- context window reset only at `src/main/kotlin/Pipe/Pipe.kt:4648`

### Concrete problem

`contextWindowSize` is restored, but `maxTokens` is **not** visibly restored after `src/main/kotlin/Pipe/Pipe.kt:2439`.

That means token budgeting is not purely execution-local today.

Also, `tokenBudgetSettings.userPromptSize` is mutated in place during execution. It is only nulled back out when the prompt budget started as dynamic. If the prompt budget is explicitly configured and then adjusted during overflow handling, that configuration can drift across runs.

### Required phase-2 work

Token budgeting needs to become execution-local and side-effect-free against the long-lived pipe configuration.

At minimum, the plan should cover:

- immutable or copied budget settings per execution
- restoring `maxTokens` and all other derived state after execution
- removing in-place mutation of shared budget configuration
- tests for repeated executions against the same pipe instance

---

### 7. Automatic memory management is incomplete in `Manifold.kt`

**Status:** Open and medium-high priority.

`Manifold` correctly identifies the smallest supported context window across manager and worker pipelines:

- `src/main/kotlin/Pipeline/Manifold.kt:696`
- `src/main/kotlin/Pipeline/Manifold.kt:717`

But the automatic truncation path only operates on the shared manager-side converse history:

- `src/main/kotlin/Pipeline/Manifold.kt:819`
- `src/main/kotlin/Pipeline/Manifold.kt:847`

### Why this matters

The code comments imply a stronger guarantee than what is actually implemented:

- `src/main/kotlin/Pipeline/Manifold.kt:154`
- `src/main/kotlin/Pipeline/Manifold.kt:165`

In practice, this logic truncates the manager task history, but it does not establish a comprehensive worker-side memory-control regime.

### Required phase-2 work

Plan mode should decide whether `Manifold` must:

- only manage manager conversation history, with documentation narrowed accordingly, or
- actively manage worker context, prompt, and memory budgets as well.

Right now the implementation and the stated behavior are not aligned strongly enough.

---

### 8. `DistributionGrid.kt` documents auto-summarization that does not exist

**Status:** Open and medium priority.

The class documentation states:

- token usage will be counted over time
- actions will be auto summarized
- history will be compressed under the lowest context window

Evidence:

- `src/main/kotlin/Pipeline/DistributionGrid.kt:53`

But the file currently contains no implementation of that token-compression / summarization behavior.

### Why this matters

This is not just documentation drift. It creates a false expectation that one of TPipe's multi-agent containers already has automatic history control when it does not.

### Required phase-2 work

Choose one:

- implement the promised summarization and budgeting behavior, or
- remove / narrow the claim in docs until that work exists.

---

### 9. `Dictionary` remains a heuristic counter used as a hard budget boundary

**Status:** Open and medium priority.

All token budgeting and truncation still depends on `Dictionary`:

- counter entrypoint at `src/main/kotlin/Context/Dict.kt:90`
- main counter implementation at `src/main/kotlin/Context/Dict.kt:126`
- string truncation at `src/main/kotlin/Context/Dict.kt:300`
- list truncation at `src/main/kotlin/Context/Dict.kt:485`

### Why this matters

The dictionary implementation splits primarily on spaces and heuristic subword rules:

- `src/main/kotlin/Context/Dict.kt:145`
- `src/main/kotlin/Context/Dict.kt:334`

That is good enough for rough estimation, but phase 2 needs to decide whether it is acceptable to keep using it as a hard safety boundary for:

- context window overflow prevention
- binary/content budgeting
- multi-page allocation
- automatic history management

This is especially risky for:

- JSON-heavy prompts
- script-heavy languages
- provider-specific tokenizer differences
- large multimodal payloads

### Required phase-2 work

Possible paths:

- keep the heuristic counter, but add explicit safety margins and documentation
- add provider/model-specific calibration or adapters
- separate "estimate" paths from "must-fit" enforcement paths

---

## Testing Gaps

**Status:** Open.

The stage-1 tests added concurrency coverage for the bank layer, which is good. But I did not find equivalent coverage for the deeper execution and budget systems.

The biggest missing test areas are:

- concurrent `Pipe.execute(...)` on the same instance
- concurrent `Pipeline.execute(...)` on the same instance
- repeated token-budgeted executions verifying no state drift in `maxTokens`
- repeated token-budgeted executions verifying no drift in `userPromptSize`
- remote lock behavior during lorebook selection and truncation
- `Manifold` memory-pressure tests for long-running agent histories
- `DistributionGrid` tests for the currently undocumented / unimplemented summarization claim

---

## Recommended Phase 2 Workstreams

The following workstreams should be discussed in plan mode.

### Workstream A - Execution ownership model

Define and enforce the ownership model for:

- `Pipe`
- `Pipeline`
- any child / branch / nested pipe execution paths

This is the top decision because it determines whether we add:

- execution mutexes / re-entrancy guards, or
- true execution-local state objects and immutable runtime snapshots

### Workstream B - Token-budget runtime refactor

Make token budgeting pure for a single execution:

- do not mutate long-lived config during execution
- do not leave derived state behind after execution
- isolate runtime budget calculations from object configuration
- add regression tests around repeated runs

### Workstream C - Lorebook lock semantics

Make lorebook selection compatible with remote-aware lock state.

This likely requires one of:

- suspend-aware selection APIs
- injecting a lock snapshot into sync selection
- precomputing eligible keys before sync selection begins

### Workstream D - Automatic memory management truthfulness

Decide whether `Manifold` and `DistributionGrid` should:

- fully implement budget-aware compression / summarization, or
- narrow docs and guarantees to match current behavior

### Workstream E - Tokenizer reliability boundary

Define whether `Dictionary` remains:

- a soft estimate with safety headroom, or
- something that needs provider-calibrated enforcement support

### Workstream F - Documentation of ownership and footguns

At minimum, the next pass should document:

- `ContextWindow` is mutable and not thread-safe by itself
- `MiniBank` is mutable and not thread-safe by itself
- `Pipe` / `Pipeline` execution ownership expectations
- limitations of heuristic token counting
- actual scope of automatic memory management in containers

---

## Open Questions For Plan Mode

These should be resolved explicitly before implementation starts.

1. Is `Pipe` intended to be safely executable concurrently on the same instance, or should that be forbidden?
2. Is `Pipeline` intended to be safely executable concurrently on the same instance, or should that be forbidden?
3. Should token budgeting be treated as a pure runtime transform, with zero persistent mutation of pipe configuration?
4. Do we want remote lock state to affect every lorebook-selection path, even when the selection helper is currently synchronous?
5. Is `DistributionGrid` auto-summarization a real roadmap item, or stale documentation that should be removed?
6. Should `Manifold` manage only manager history, or all worker-facing memory pressure as well?
7. Do we accept `Dictionary` as heuristic-only with safety margins, or do we want harder tokenizer alignment?

---

## Priority Order Recommendation

### P0

- execution ownership and re-entrancy decision for `Pipe` / `Pipeline`
- token-budget state mutation cleanup

### P1

- remote-aware lorebook lock selection
- `Manifold` memory-control contract alignment
- tests for repeated and concurrent execution

### P2

- `DistributionGrid` summarization implementation or doc correction
- `Dictionary` calibration / safety-margin strategy
- documentation hardening for mutable model ownership

---

## Strongest Evidence Summary

If phase 2 needs to be justified quickly, these are the strongest anchors:

- Phase 1 was explicitly scoped as stage 1 in commit `2f2a61f`.
- `Pipe` still mutates shared execution state in `src/main/kotlin/Pipe/Pipe.kt:4928` and `src/main/kotlin/Pipe/Pipe.kt:5137`.
- token budgeting still mutates long-lived state in `src/main/kotlin/Pipe/Pipe.kt:2439` and `src/main/kotlin/Pipe/Pipe.kt:4452`.
- `Pipeline` still mutates shared execution state in `src/main/kotlin/Pipeline/Pipeline.kt:1147` and `src/main/kotlin/Pipeline/Pipeline.kt:1337`.
- `ContextWindow` lorebook lock selection still uses sync local-only locking at `src/main/kotlin/Context/ContextWindow.kt:1452` and `src/main/kotlin/Context/ContextLock.kt:391`.
- `DistributionGrid` still claims auto summarization without implementation at `src/main/kotlin/Pipeline/DistributionGrid.kt:53`.

---

## Final Assessment

**Phase 2 is required.**

The main repository-level memory store is in much better shape now. The unresolved work has shifted upward into the execution layer and sideways into the token-budget / automatic-memory-management layer.

The next planning pass should treat this as a broader "execution-state and memory-control refactor," not just a continuation of `ContextBank` hardening.
