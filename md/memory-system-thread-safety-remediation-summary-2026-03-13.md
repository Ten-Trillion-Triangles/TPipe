# Memory System Thread-Safety Remediation Summary

**Date:** 2026-03-13  
**Repository:** `TPipe`  
**Purpose:** Preserve a detailed implementation record for the memory-system thread-safety remediation before context compaction.

---

## Executive Summary

This change set implements the approved thread-safety remediation plan for TPipe's memory subsystem. The work focused on eliminating internal blocking behavior in coroutine-heavy code, replacing unsafe shared mutable structures in the memory path, introducing suspend-safe APIs for internal use, preserving synchronous compatibility for user-facing APIs, and extending coverage with targeted concurrency tests.

The main architectural direction was:

1. Keep synchronous public APIs available for compatibility.
2. Add explicit suspend-safe `...Suspend` APIs for internal coroutine/server/container usage.
3. Ensure TPipe's internal memory flows no longer rely on `runBlocking` inside active coroutine/multithreaded memory paths.
4. Move shared memory structures onto thread-safe primitives and per-key coordination.
5. Bring Todo memory and lock coordination up to parity with context-page handling.
6. Make banked-context reads safe by default for internal code.

---

## Files Changed

### Production code

- `src/main/kotlin/Context/ContextBank.kt`
- `src/main/kotlin/Context/ContextLock.kt`
- `src/main/kotlin/Context/MemoryIntrospection.kt`
- `src/main/kotlin/Context/MemoryIntrospectionTools.kt`
- `src/main/kotlin/Context/MemoryServer.kt`
- `src/main/kotlin/Pipe/Pipe.kt`

### Tests

- `src/test/kotlin/Context/RetrievalFunctionTest.kt`
- `src/test/kotlin/Context/WriteBackFunctionTest.kt`
- `src/test/kotlin/Context/ContextBankConcurrencyTest.kt` (new)

---

## Detailed Change Log

## 1. `ContextBank` refactor

### 1.1 Shared storage primitives upgraded

`ContextBank` was the primary source of thread-safety risk. The following internal maps were migrated from plain mutable maps to `ConcurrentHashMap`:

- page context bank
- todo-list bank
- storage metadata
- pre-existing retrieval/write-back function maps remained concurrent

This removes the unsafe pattern where `@Volatile` was being used around mutable map references without protecting internal map state.

### 1.2 New lock strategy introduced

To avoid relying entirely on coarse bank-wide locking, the implementation now uses:

- per-page mutexes for context window operations
- per-todo mutexes for todo-list operations
- a cache mutex for cache policy / eviction coordination
- the existing banked-context swap mutex for banked-context safety

This allows unrelated keys to proceed in parallel while still making same-key read/copy/write sequences atomic.

### 1.3 New suspend-safe API layer added

A new explicit internal suspend-safe API layer was introduced. This gives internal TPipe code a non-blocking path while preserving old sync compatibility methods.

Key new methods added include:

- `getBankedContextWindowSuspend(...)`
- `updateBankedContextSuspend(...)`
- `swapBankSuspend(...)`
- `emplaceSuspend(...)`
- `deletePersistingBankKeySuspend(...)`
- `configureCachePolicySuspend(...)`
- `clearCacheSuspend()`
- `getContextFromBankSuspend(...)`
- `getPageKeysSuspend(...)`
- `getTodoListKeysSuspend(...)`
- `getPagedTodoListSuspend(...)`
- `emplaceTodoListSuspend(...)`
- `withContextWindowReferenceSuspend(...)`
- `mutateContextWindowSuspend(...)`

### 1.4 Sync compatibility wrappers preserved

The original sync-style methods were kept in place where compatibility mattered, but they now act as blocking wrappers or compatibility entrypoints rather than the preferred internal implementation path.

Examples:

- `getContextFromBank(...)`
- `getPageKeys(...)`
- `getPagedTodoList(...)`
- `getBankedContextWindow()`
- `emplace(...)`

These remain callable for legacy and user-facing sync flows, but internal code was moved away from them wherever possible.

### 1.5 Banked context made safe by default for internal usage

The banked-context flow was updated so internal coroutine code uses snapshot-based suspend-safe access instead of directly sharing the stored object reference.

Changes include:

- `getBankedContextWindowSuspend(copy = true)` now returns a snapshot by default
- `updateBankedContextSuspend(...)` stores a deep copy
- `getBankedContextWindowReference()` was added as the explicit unsafe escape hatch for direct shared-reference access
- `copyBankedContextWindow()` now routes through the safe default behavior

This aligns banked context with the safety expectations previously intended for the rest of the memory system.

### 1.6 Read-path races removed

The old check-then-act memory access pattern was removed from page/todo retrieval.

Before, the code used patterns equivalent to:

- `containsKey(...)`
- followed by forced lookup / `!!`

Now retrieval is performed under key-level coordination with direct lookup and snapshotting. This avoids:

- stale reads between `containsKey` and actual lookup
- null assertion crashes during concurrent mutation
- copying while the same key is being mutated

### 1.7 Atomic snapshot behavior added

Deep-copy use remains part of the design, but now it happens under key-specific coordination for the in-memory banked objects exposed through the new suspend paths.

This materially reduces the risk of:

- copying partially mutated state
- returning inconsistent mutable collection contents
- exposing in-progress updates to callers that expect snapshots

### 1.8 Cache population and eviction behavior hardened

The old read path could write into cache during retrieval without coordinated synchronization. That behavior was replaced with key-aware loading helpers and cache-aware insertion.

New helper behavior includes:

- `loadContextWindowForKeyLocked(...)`
- `loadTodoListForKeyLocked(...)`
- `putIfAbsent(...)` usage for cache population
- `enforceEvictionPolicy()` and `enforceTodoListEvictionPolicy()` moving through suspend-safe coordination
- global cache policy changes now coordinated through `cacheMutex`

This reduces concurrent cache corruption risk and centralizes eviction control.

### 1.9 Atomic mutation helper added for page contexts

`mutateContextWindowSuspend(...)` was introduced so internal code can perform read-modify-write updates safely for a single key while preserving storage semantics.

This helper:

- loads the current page under the page mutex
- deep-copies to a working value
- applies the mutation block
- persists according to storage mode
- updates metadata
- triggers cache enforcement if needed

This gave internal components a safe alternative to manually fetching mutable references, mutating them, then writing them back with race windows in between.

### 1.10 Metadata updates made atomic

`storageMetadata` updates were changed to use atomic concurrent-map operations such as:

- `compute(...)`
- `computeIfPresent(...)`

This reduces lost-update behavior around access counters and access timestamps.

### 1.11 Todo system brought to parity

The todo memory path now follows the same concurrency model as context pages:

- concurrent map storage
- per-key mutexes
- suspend-safe retrieval and emplace APIs
- safer cache population
- suspend-safe eviction enforcement
- same compatibility strategy for sync methods

This was a deliberate expansion of scope so all memory systems moved together rather than only page contexts.

---

## 2. `ContextLock` refactor

### 2.1 Lock storage upgraded

`ContextLock` moved from plain `mutableMapOf` storage to `ConcurrentHashMap` for lock bundles.

### 2.2 New suspend-safe lock APIs added

A parallel suspend-safe API layer was introduced for lock operations:

- `addLockSuspend(...)`
- `removeLockSuspend(...)`
- `lockKeyBundleSuspend(...)`
- `unlockKeyBundleSuspend(...)`
- `isKeyLockedSuspend(...)`
- `isPageLockedSuspend(...)`
- `getLockKeysSuspend(...)`

### 2.3 `WithMutex` wrappers preserved and re-pointed

The existing mutex-based wrappers were retained and now call suspend-safe implementations instead of duplicating logic.

This keeps naming continuity while clarifying the layering:

- sync compatibility APIs
- suspend-safe internal APIs
- mutex-backed compound-operation APIs

### 2.4 Internal remote-lock blocking removed from active coroutine paths

The previous design used `runBlocking` inside lock operations when remote memory was enabled. Internal lock paths now use suspend-safe remote calls instead.

### 2.5 Lock-to-context metadata application updated

When a lock affects page context metadata, `ContextLock` now updates the affected context pages via `ContextBank.withContextWindowReferenceSuspend(...)` under coordinated page-level access.

This preserves the existing `metaData["isLocked"]` model while removing race-prone direct access patterns.

### 2.6 Sync lock-state checks kept locally safe

Synchronous selection helpers inside `ContextWindow` still call sync `ContextLock` checks. To avoid injecting blocking network behavior into those synchronous selection helpers, the sync lock-state methods now behave as local lock-state lookups, while remote-aware behavior is handled by the new suspend-safe methods.

This was an intentional compatibility/safety split.

---

## 3. `MemoryIntrospection` and tooling migration

### 3.1 Suspend-safe write-permission check added

`MemoryIntrospection` gained `canWriteSuspend(...)` so coroutine-driven tooling can verify page creation / write eligibility without calling sync page-key lookups.

### 3.2 `MemoryIntrospectionTools` moved onto suspend-safe memory APIs

The following tool flows were migrated from sync APIs to suspend-safe ones:

- listing page keys
- reading lorebook entries
- reading full lorebooks
- simulated lorebook triggering
- memory search
- lorebook entry update/delete flows
- todo list read/update flows

### 3.3 Lorebook mutation flow hardened

Instead of:

1. loading a mutable page copy
2. mutating it externally
3. writing it back later

`MemoryIntrospectionTools` now uses `ContextBank.mutateContextWindowSuspend(...)` for lorebook update/delete behavior.

This closes a read-modify-write race window in those update paths.

---

## 4. `MemoryServer` migration

The remote memory server already operates in suspend-capable Ktor handlers, so it was updated to call the new suspend-safe APIs directly.

Updated areas include:

- bank key listing
- bank page retrieval
- version-aware remote write flow
- delete flow
- todo key listing
- todo retrieval and write flow
- lock key listing
- lock state checks

This removes blocking compatibility paths from the active server request handlers.

---

## 5. `Pipe` migration

The global-context loading path in `Pipe` was one of the key internal coroutine flows called out by the audit.

Changes made:

- banked-context loading now uses `getBankedContextWindowSuspend()`
- page-key loads now use `getContextFromBankSuspend(...)`
- the multi-page global-context loop no longer routes through blocking ContextBank reads

The existing todo-list injection call at `Pipe` setup still uses the sync getter because that specific code path is not currently a suspend function. It remains compatibility-safe, but the higher-impact internal coroutine memory paths were migrated first.

---

## 6. Tests updated and added

### 6.1 Existing tests stabilized for environment isolation

`RetrievalFunctionTest` and `WriteBackFunctionTest` were updated so each test explicitly disables remote-memory flags during setup. This prevents cross-test contamination from remote-memory state and restores deterministic expectations for compatibility-path behavior.

### 6.2 New concurrency regression suite added

A new test file was added:

- `src/test/kotlin/Context/ContextBankConcurrencyTest.kt`

It includes coverage for:

- concurrent suspend-safe page read/write traffic
- concurrent suspend-safe todo read/write traffic
- banked-context snapshot safety by default
- mixed stress operations across multiple keys

These tests are intended to catch regressions in the new suspend-safe memory coordination model.

---

## Compatibility Notes

### Preserved behavior

- sync `ContextBank` APIs still exist
- sync `ContextLock` APIs still exist
- write-back functions still trigger through mutex-backed/suspend-safe internal writes
- sync `emplace(...)` still bypasses write-back hooks, preserving test-verified behavior
- retrieval-function priority behavior remains intact

### Changed internal expectations

- TPipe internal coroutine/server flows should prefer `...Suspend` APIs
- banked-context internal reads are now snapshot-based by default
- remote-aware lock checks should use suspend methods instead of sync methods

---

## Validation Performed

### Targeted memory-related test run

Executed:

```bash
./gradlew :test --tests '*ContextBankStorageModeTest' --tests '*ContextBankConcurrencyTest' --tests '*RetrievalFunctionTest' --tests '*WriteBackFunctionTest' --tests '*RemoteMemoryTest'
```

Result:

- passed after fixing compatibility regressions uncovered during migration

### Full root test run

Executed:

```bash
./gradlew :test
```

Result:

- passed successfully

---

## Notable Debugging / Regression Fixes During Implementation

Several regressions surfaced during the refactor and were corrected before final validation:

1. A `Pipe` todo-list call site was temporarily changed to a suspend method in a non-suspend function and had to be reverted to the sync compatibility getter.
2. Sync `ContextBank.emplace(...)` initially started honoring write-back hooks due to shared implementation reuse; this broke an existing compatibility guarantee and was fixed so sync `emplace(...)` preserves legacy behavior.
3. Reusing the suspend-safe emplace implementation also changed object-reference behavior in a way that broke a retrieval-function compatibility test; sync `emplace(...)` was restored to preserve the legacy semantics expected by current tests.
4. Full test runs exposed remote-memory flag leakage across tests, so the affected tests now explicitly reset remote-memory configuration during setup/cleanup.

---

## Formatting / Style Notes

All touched Kotlin code was adjusted with the active TTT formatter expectations in mind:

- vertical bracing for normal functions/control flow
- no extra space before `(`
- KDoc added or expanded on public/suspend APIs where touched
- concurrency-specific comments kept focused on non-obvious behavior

The style guide source used for this work was:

- `/home/cage/.codex/skills/formatter/references/TTT_STYLE_GUIDE.md`

This was used because `md/TTT_STYLE_GUIDE.md` was not present in the repo.

---

## Remaining Considerations / Follow-up Ideas

These were not blockers for this implementation, but may be worth future follow-up:

1. `Pipe` todo-list injection still uses a sync compatibility getter because that code path is not currently suspend.
2. Some sync compatibility wrappers in `ContextBank` and `ContextLock` still use `runBlocking` by design for external compatibility. Internal TPipe code should continue migrating away from them wherever additional call sites are found.
3. `ContextWindow` and `TodoList` remain mutable models; the current solution hardens access via coordination rather than converting the models to fully immutable snapshots.
4. `MemoryServer` still has an unrelated Ktor deprecation warning around route interception that was not part of this thread-safety pass.

---

## Final Outcome

This remediation materially improves the safety and scalability of TPipe's memory subsystem by:

- removing blocking internal memory access from key coroutine/server flows
- replacing unsafe shared mutable map usage in memory-critical areas
- making page/todo snapshotting and mutation coordinated per key
- bringing lock coordination into the same suspend-safe model
- preserving user-facing sync compatibility where needed
- adding concurrency-focused regression coverage

This report is intended to serve as the durable implementation record for the 2026-03-13 thread-safety remediation pass.
