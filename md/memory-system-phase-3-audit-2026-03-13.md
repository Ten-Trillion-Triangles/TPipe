# TPipe Memory System Phase 3 Audit

**Date:** 2026-03-13  
**Status:** Open - Phases 1 and 2 landed, Phase 3 still required  
**Scope:** `MemoryClient`, remote memory transport correctness, remote delete semantics, and filesystem-backed lorebook / todo persistence guarantees.

---

## Purpose

This audit exists to capture the remaining memory-system work after the stage-1 bank / lock hardening pass and the stage-2 execution-state remediation pass.

Phase 3 is narrower than the earlier audits. It is focused on the parts of the memory system that were adjacent to the earlier fixes but not fully closed by them:

- `MemoryClient` local state and transport behavior
- remote memory delete semantics
- real integration coverage for remote memory client/server behavior
- on-disk lorebook and todo persistence semantics beyond in-process mutex safety
- filesystem write durability / atomicity expectations

This is not a re-audit of `Pipe`, `Pipeline`, or token budgeting. Those were the primary focus of phase 2 and should stay out of scope unless they directly affect remote memory or persistence behavior.

---

## Inputs Reviewed

### Existing audit / remediation artifacts

- `md/memory-system-thread-safety-audit.md`
- `md/memory-system-thread-safety-remediation-summary-2026-03-13.md`
- `md/memory-system-phase-2-audit-2026-03-13.md`
- `md/memory-system-phase-2-remediation-summary-2026-03-13.md`

### Latest branch commit

- `2ffd6755` - `Fix extended memory concurrency issues`

### Additional repository evidence

- `src/main/kotlin/Context/MemoryClient.kt`
- `src/main/kotlin/Context/MemoryServer.kt`
- `src/main/kotlin/Context/ContextBank.kt`
- `src/main/kotlin/Util/Util.kt`
- `src/test/kotlin/Context/RemoteMemoryTest.kt`
- `src/test/kotlin/Context/ContextBankStorageModeTest.kt`
- `src/test/kotlin/Context/ContextBankConcurrencyTest.kt`

---

## Key Conclusion

**The shared in-process bank layer is substantially hardened, but the remote client and filesystem persistence layer are not fully closed.**

What appears materially handled already:

- suspend-safe remote server handlers through `ContextBank` in `src/main/kotlin/Context/MemoryServer.kt:51`, `src/main/kotlin/Context/MemoryServer.kt:60`, `src/main/kotlin/Context/MemoryServer.kt:90`, `src/main/kotlin/Context/MemoryServer.kt:155`, and `src/main/kotlin/Context/MemoryServer.kt:182`
- per-key in-process coordination for disk-backed page and todo loads / writes in `src/main/kotlin/Context/ContextBank.kt:178`, `src/main/kotlin/Context/ContextBank.kt:215`, `src/main/kotlin/Context/ContextBank.kt:906`, `src/main/kotlin/Context/ContextBank.kt:978`, `src/main/kotlin/Context/ContextBank.kt:1122`, and `src/main/kotlin/Context/ContextBank.kt:1223`
- storage-mode regression coverage for disk-backed pages and todos in `src/test/kotlin/Context/ContextBankStorageModeTest.kt:49`, `src/test/kotlin/Context/ContextBankStorageModeTest.kt:66`, `src/test/kotlin/Context/ContextBankStorageModeTest.kt:87`, and `src/test/kotlin/Context/ContextBankStorageModeTest.kt:171`

What is still open:

- `MemoryClient` still has unsynchronized mutable local caches
- the real `MemoryClient` transport path is not meaningfully covered by tests
- remote todo deletion is not implemented server-side even though the client exposes it
- remote context deletion removes the disk file but does not clearly remove the in-memory cached page
- filesystem persistence is still based on direct `writeText`, `readText`, and `delete`, with no atomic replace protocol or crash-safety guarantee

The net result is that phase 1 and phase 2 closed the coroutine and shared-memory substrate, but phase 3 is still needed if the goal is a defensible end-to-end story for remote memory and persisted memory correctness.

---

## Phase 1 / Phase 2 Boundary

### Closed or materially improved already

Stage 1 explicitly covered `ContextBank`, `ContextLock`, and `MemoryServer`, and the stage-1 remediation summary states that the server request handlers were migrated onto suspend-safe APIs:

- `md/memory-system-thread-safety-remediation-summary-2026-03-13.md:273`
- `md/memory-system-thread-safety-remediation-summary-2026-03-13.md:288`

Stage 2 focused on execution-state drift, remote-aware lorebook selection, and provider truncation behavior:

- `md/memory-system-phase-2-remediation-summary-2026-03-13.md:16`
- `md/memory-system-phase-2-remediation-summary-2026-03-13.md:34`
- `md/memory-system-phase-2-remediation-summary-2026-03-13.md:63`

Neither pass claims to have closed the `MemoryClient` internals or upgraded filesystem persistence semantics to atomic / durable writes.

### Not closed by the earlier passes

The latest phase-1 commit changed:

- `src/main/kotlin/Context/ContextBank.kt`
- `src/main/kotlin/Context/ContextLock.kt`
- `src/main/kotlin/Context/MemoryServer.kt`

It did **not** touch `src/main/kotlin/Context/MemoryClient.kt`.

The phase-2 commit also did not include `MemoryClient`, `MemoryServer`, `ContextBank`, or persistence utility rewrites.

That boundary matters because the remaining issues are no longer mainly coroutine-blocking issues. They are now about:

- mutable client-side cache correctness
- remote API completeness
- delete semantics
- filesystem atomicity and durability
- realistic integration coverage

---

## Subsystem Status

### 1. `MemoryClient.kt`

**Status:** Partially acceptable, but not complete.

### What appears acceptable

`MemoryClient` is already suspend-based throughout its public network surface:

- `getContextWindow(...)` at `src/main/kotlin/Context/MemoryClient.kt:61`
- `emplaceContextWindow(...)` at `src/main/kotlin/Context/MemoryClient.kt:72`
- `getTodoList(...)` at `src/main/kotlin/Context/MemoryClient.kt:134`
- `emplaceTodoList(...)` at `src/main/kotlin/Context/MemoryClient.kt:145`
- `isKeyLocked(...)` at `src/main/kotlin/Context/MemoryClient.kt:176`
- `isPageLocked(...)` at `src/main/kotlin/Context/MemoryClient.kt:195`

That means the client is not suffering from the original `runBlocking` problem described in the phase-1 audit.

### What is still open

#### A. Local lock caches are still plain mutable maps

`MemoryClient` still stores lock-state cache data in unsynchronized mutable maps:

- `src/main/kotlin/Context/MemoryClient.kt:44`
- `src/main/kotlin/Context/MemoryClient.kt:45`

Those caches are read and written from suspend functions that may run concurrently:

- reads at `src/main/kotlin/Context/MemoryClient.kt:179` and `src/main/kotlin/Context/MemoryClient.kt:198`
- writes at `src/main/kotlin/Context/MemoryClient.kt:188`, `src/main/kotlin/Context/MemoryClient.kt:207`, `src/main/kotlin/Context/MemoryClient.kt:222`, `src/main/kotlin/Context/MemoryClient.kt:223`, `src/main/kotlin/Context/MemoryClient.kt:238`, and `src/main/kotlin/Context/MemoryClient.kt:239`

This is the same class of issue that phase 1 removed from `ContextBank` and `ContextLock`, but it remains in the client-side cache layer.

This is not necessarily catastrophic because the cache is only an optimization, but it is still unresolved thread-safety debt.

#### B. Transport failures are collapsed into soft fallbacks

`withRetry(...)` returns `null` after retry exhaustion at `src/main/kotlin/Context/MemoryClient.kt:25`, `src/main/kotlin/Context/MemoryClient.kt:40`.

Many call sites then collapse that into a successful-looking fallback value such as:

- `emptyList()` at `src/main/kotlin/Context/MemoryClient.kt:54`, `src/main/kotlin/Context/MemoryClient.kt:95`, `src/main/kotlin/Context/MemoryClient.kt:106`, `src/main/kotlin/Context/MemoryClient.kt:127`, and `src/main/kotlin/Context/MemoryClient.kt:169`
- `null` at `src/main/kotlin/Context/MemoryClient.kt:64` and `src/main/kotlin/Context/MemoryClient.kt:137`
- `false` at `src/main/kotlin/Context/MemoryClient.kt:76`, `src/main/kotlin/Context/MemoryClient.kt:117`, `src/main/kotlin/Context/MemoryClient.kt:149`, `src/main/kotlin/Context/MemoryClient.kt:159`, `src/main/kotlin/Context/MemoryClient.kt:184`, and `src/main/kotlin/Context/MemoryClient.kt:203`

That behavior may be intentional, but it means the client does not currently distinguish:

- transport unavailable
- auth failure
- malformed server response
- actual empty remote state

This is a correctness / observability issue more than a thread-safety issue.

#### C. Remote todo delete is exposed by the client but not implemented by the server

The client exposes:

- `deleteTodoList(...)` at `src/main/kotlin/Context/MemoryClient.kt:156`

But `MemoryServer` only implements `GET /keys`, `GET /{key}`, and `POST /{key}` for `/context/todo` at `src/main/kotlin/Context/MemoryServer.kt:142` through `src/main/kotlin/Context/MemoryServer.kt:184`.

There is no matching `DELETE /context/todo/{key}` server route.

That means the client API advertises a capability that the server does not actually provide.

### Assessment

`MemoryClient` is **in progress**, not done.

The client is coroutine-friendly, but it is not fully hardened or fully aligned with the server contract.

---

### 2. Remote memory server behavior

**Status:** Improved, but not fully semantically closed.

### What appears handled

The active server handlers now use suspend-safe bank APIs instead of sync compatibility wrappers:

- page retrieval at `src/main/kotlin/Context/MemoryServer.kt:60`
- page write at `src/main/kotlin/Context/MemoryServer.kt:90`
- page delete at `src/main/kotlin/Context/MemoryServer.kt:137`
- todo retrieval at `src/main/kotlin/Context/MemoryServer.kt:155`
- todo write at `src/main/kotlin/Context/MemoryServer.kt:182`

This matches the stage-1 remediation summary and should be treated as an actual improvement over the original audit baseline.

### What is still open

#### A. Remote context delete appears to only delete the file

The server delete endpoint calls:

- `ContextBank.deletePersistingBankKeySuspend(...)` at `src/main/kotlin/Context/MemoryServer.kt:137`

That delete helper only deletes the disk file under the page mutex:

- `src/main/kotlin/Context/ContextBank.kt:598`
- `src/main/kotlin/Context/ContextBank.kt:606`
- `src/main/kotlin/Context/ContextBank.kt:607`

It does **not** remove the in-memory cached page from `bank`.

So if a page was previously cached in memory, the delete route may leave the page still readable from memory until some later eviction or overwrite path clears it.

That is a semantic correctness issue for remote delete behavior.

#### B. Remote todo delete is missing entirely

As noted above, the todo route set does not include a delete endpoint at `src/main/kotlin/Context/MemoryServer.kt:142` through `src/main/kotlin/Context/MemoryServer.kt:184`.

This means remote todo lifecycle support is incomplete.

### Assessment

`MemoryServer` is **mostly migrated for suspend correctness**, but **not done for lifecycle correctness**.

---

### 3. On-disk context and todo persistence

**Status:** Improved for in-process coordination, not complete for filesystem semantics.

### What appears handled

The current `ContextBank` design does coordinate disk-backed loads and writes through per-key mutexes.

For context pages:

- load helper at `src/main/kotlin/Context/ContextBank.kt:178`
- suspend retrieval path at `src/main/kotlin/Context/ContextBank.kt:906`
- atomic mutate-and-store path at `src/main/kotlin/Context/ContextBank.kt:978`

For todo lists:

- load helper at `src/main/kotlin/Context/ContextBank.kt:215`
- suspend retrieval path at `src/main/kotlin/Context/ContextBank.kt:1122`
- suspend emplace path at `src/main/kotlin/Context/ContextBank.kt:1223`

That means phase 1 did materially fix the original in-JVM race shape where unsynchronized reads, writes, and copies could overlap.

### What is still open

#### A. File writes are still direct `writeText(...)`

Persistent writes still funnel through:

- `writeStringToFile(...)` at `src/main/kotlin/Util/Util.kt:925`

That helper:

- creates parent directories if needed at `src/main/kotlin/Util/Util.kt:928` through `src/main/kotlin/Util/Util.kt:935`
- writes directly with `file.writeText(content)` at `src/main/kotlin/Util/Util.kt:936`

There is no temp-file write + atomic rename protocol.

So the current implementation still lacks guarantees for:

- crash-safe writes
- atomic replace semantics for external readers
- durability on sudden process termination
- cross-process coordination

#### B. File reads are still direct `readText(...)`

Persistent reads still funnel through:

- `readStringFromFile(...)` at `src/main/kotlin/Util/Util.kt:948`

That helper simply returns `File(filepath).readText()` if the file exists and is readable at `src/main/kotlin/Util/Util.kt:950` through `src/main/kotlin/Util/Util.kt:952`.

This is acceptable for simple local persistence, but it means reads have no protection against:

- partial files left by interrupted writes
- externally modified malformed files
- concurrent writers outside the current process

#### C. File deletes are still plain `delete()`

Deletion still funnels through:

- `deleteFile(...)` at `src/main/kotlin/Util/Util.kt:1336`

That helper calls `file.delete()` at `src/main/kotlin/Util/Util.kt:1349`.

No higher-level persistence contract exists around delete ordering, cache invalidation, or recovery.

### Assessment

The filesystem layer is **acceptable only if the intended contract is single-process best-effort persistence**.

It is **not** yet a strong persistence subsystem with atomic write guarantees.

---

### 4. Test coverage status

**Status:** Partial.

### What appears covered

Storage-mode and disk round-trip behavior are covered locally:

- context page disk tests at `src/test/kotlin/Context/ContextBankStorageModeTest.kt:49`, `src/test/kotlin/Context/ContextBankStorageModeTest.kt:66`, and `src/test/kotlin/Context/ContextBankStorageModeTest.kt:87`
- todo disk test at `src/test/kotlin/Context/ContextBankStorageModeTest.kt:171`

Concurrent in-process bank access is covered:

- `src/test/kotlin/Context/ContextBankConcurrencyTest.kt:44`
- `src/test/kotlin/Context/ContextBankConcurrencyTest.kt:75`

### What is still missing

#### A. The real `MemoryClient` path is not truly exercised

`RemoteMemoryTest` explicitly acknowledges that it is mainly testing server endpoints and not the real `MemoryClient` transport path:

- `src/test/kotlin/Context/RemoteMemoryTest.kt:103`
- `src/test/kotlin/Context/RemoteMemoryTest.kt:106`
- `src/test/kotlin/Context/RemoteMemoryTest.kt:107`
- `src/test/kotlin/Context/RemoteMemoryTest.kt:108`

So phase 1 and phase 2 do not provide strong evidence that the actual remote client/server pair behaves correctly end to end.

#### B. No direct tests for remote delete semantics

I did not find tests covering:

- remote context delete clearing both disk and memory state
- remote todo delete behavior
- `MemoryClient.deleteTodoList(...)` against a real server
- client-side lock cache concurrency behavior

#### C. No filesystem atomicity / crash-safety tests

I did not find tests covering:

- interrupted writes
- malformed partial files during load
- external concurrent readers / writers
- temp-file / rename based durability, because that protocol does not exist yet

### Assessment

Current tests are good enough to support the claim that the bank layer is better coordinated in-process.

They are **not** enough to claim that remote memory and on-disk persistence are fully closed.

---

## Findings Summary

### Finding 1: `MemoryClient` cache state is still unsynchronized

**Severity:** Medium  
**Status:** Open

Evidence:

- `src/main/kotlin/Context/MemoryClient.kt:44`
- `src/main/kotlin/Context/MemoryClient.kt:45`
- `src/main/kotlin/Context/MemoryClient.kt:179`
- `src/main/kotlin/Context/MemoryClient.kt:188`
- `src/main/kotlin/Context/MemoryClient.kt:198`
- `src/main/kotlin/Context/MemoryClient.kt:207`

Recommendation direction:

- either move these caches to `ConcurrentHashMap`-style storage or guard them with a dedicated mutex
- keep the cache optional and correctness-neutral
- add tests that hit repeated concurrent remote lock lookups and lock mutations

### Finding 2: Remote todo delete is not actually implemented

**Severity:** High  
**Status:** Open

Evidence:

- client delete API exists at `src/main/kotlin/Context/MemoryClient.kt:156`
- server todo routes stop at create/read/list in `src/main/kotlin/Context/MemoryServer.kt:142`
- server todo routes end at `src/main/kotlin/Context/MemoryServer.kt:184`

Recommendation direction:

- either implement `DELETE /context/todo/{key}` end to end or remove / narrow the client API until it is real
- add an integration test for the full todo lifecycle

### Finding 3: Remote context delete likely leaves cached memory state behind

**Severity:** High  
**Status:** Open

Evidence:

- server delete route uses `ContextBank.deletePersistingBankKeySuspend(...)` at `src/main/kotlin/Context/MemoryServer.kt:137`
- delete helper only deletes the disk file at `src/main/kotlin/Context/ContextBank.kt:598`, `src/main/kotlin/Context/ContextBank.kt:606`, and `src/main/kotlin/Context/ContextBank.kt:607`
- eviction of in-memory context is handled by separate APIs at `src/main/kotlin/Context/ContextBank.kt:625` and `src/main/kotlin/Context/ContextBank.kt:633`, not by the delete helper

Recommendation direction:

- clarify delete semantics first
- if delete is meant to mean full removal, it must clear disk, memory cache, and relevant metadata together under coordinated access
- add server/client tests for delete followed by immediate read

### Finding 4: Filesystem persistence still lacks atomic replace semantics

**Severity:** Medium to High  
**Status:** Open

Evidence:

- direct write at `src/main/kotlin/Util/Util.kt:936`
- direct read at `src/main/kotlin/Util/Util.kt:952`
- direct delete at `src/main/kotlin/Util/Util.kt:1349`

Recommendation direction:

- decide whether TPipe wants best-effort local persistence or stronger atomic persistence guarantees
- if stronger guarantees are desired, introduce a temp-file + fsync + atomic rename protocol for `.bank` and `.todo` files
- document the persistence contract explicitly either way

### Finding 5: Remote-memory integration coverage is still thin

**Severity:** Medium  
**Status:** Open

Evidence:

- `src/test/kotlin/Context/RemoteMemoryTest.kt:103`
- `src/test/kotlin/Context/RemoteMemoryTest.kt:106`
- `src/test/kotlin/Context/RemoteMemoryTest.kt:107`
- `src/test/kotlin/Context/RemoteMemoryTest.kt:108`

Recommendation direction:

- add a real `MemoryClient` integration suite against a live embedded memory server on a real port
- cover page CRUD, todo CRUD, lock CRUD, version conflicts, and delete-followed-by-read behavior
- add concurrent client lock-cache tests if the cache is kept

---

## Recommended Phase 3 Planning Questions

1. Is remote memory supposed to provide full CRUD parity for both context pages and todo lists, or only partial lifecycle support?
2. When a remote delete occurs, should it remove only the persisted file, or should it also invalidate any in-memory cached state immediately?
3. Is the intended persistence contract best-effort local storage, or do we want atomic on-disk replacement semantics?
4. Should `MemoryClient` network failures remain soft-fallback behavior, or should internal callers be able to distinguish transport failure from true empty remote state?
5. Do we want `MemoryClient` cache behavior to remain an internal optimization only, with correctness never depending on it?
6. Should phase 3 include only correctness fixes, or also observability improvements for remote-memory failure modes?

---

## Recommended Refactor Scope for the Next Plan

If phase 3 moves forward, the next implementation plan should likely include four workstreams:

### 1. `MemoryClient` hardening

- harden or remove unsynchronized local caches
- define transport-error semantics clearly
- ensure client/server route parity

### 2. Remote lifecycle completeness

- implement or remove remote todo delete
- fix remote context delete semantics
- add lifecycle tests for create/read/update/delete/re-read flows

### 3. Filesystem persistence contract

- decide whether to keep best-effort semantics or upgrade to atomic replace writes
- centralize lorebook and todo file persistence helpers around that contract
- ensure deletes and cache invalidation follow the same contract

### 4. Integration coverage

- add real client/server remote-memory tests
- add delete semantics tests
- add persistence corruption / malformed file tests if stronger persistence guarantees are introduced

---

## Final Status

**Phase 3 is required if the goal is to say that TPipe memory is fully handled beyond the in-process bank layer.**

Current status by area:

- `ContextBank` in-process shared access: materially improved
- `MemoryServer` suspend-path migration: materially improved
- local disk-backed storage modes: partially covered and partially tested
- `MemoryClient`: not fully handled
- remote delete semantics: not fully handled
- filesystem atomicity / durability semantics: not handled
- remote integration coverage: not fully handled

That makes the correct overall status:

**In progress - strong progress on the core bank layer, but remote client and persistence semantics still need a dedicated final pass.**
