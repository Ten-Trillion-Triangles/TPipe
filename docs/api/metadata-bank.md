# MetadataBank and Meta-Page-Keys Pull — API Reference

Process-singleton, page-keyed, in-memory-only `Map<Any, Any>` scratchpad for TPipe, with a runtime auto-pull surface on `Pipe` and `PumpStation` that mirrors the existing `Pipe.readFromGlobalContext` execution-time pattern.

## Table of Contents

- [Overview](#overview)
- [Core Concept: Pages and the Bulk-Pull Pattern](#core-concept-pages-and-the-bulk-pull-pattern)
- [Glued Page-Key String](#glued-page-key-string)
- [MetadataBank API](#metadatabank-api)
  - [Object Lifecycle](#object-lifecycle)
  - [Single-Page Read and Write](#single-page-read-and-write)
  - [Merge-into-Page](#merge-into-page)
  - [Delete, Exists, Clear](#delete-exists-clear)
  - [Active-Page Pointer](#active-page-pointer)
  - [Bulk Pull](#bulk-pull)
  - [Inspection](#inspection)
- [Meta-Page-Keys Surface on `Pipe`](#meta-page-keys-surface-on-pipe)
- [Meta-Page-Keys Surface on `PumpStation`](#meta-page-keys-surface-on-pumpstation)
- [Runtime Auto-Pull Lifecycle](#runtime-auto-pull-lifecycle)
- [Concurrency Contract](#concurrency-contract)
- [What This Surface Does Not Cover](#what-this-surface-does-not-cover)
- [Examples](#examples)

## Overview

`MetadataBank` is a TPipe-internal scratchpad. It provides globally addressable storage for any `Map<Any, Any>` keyed by `String`, with bulk-pull semantics that let a single glued-string selector merge multiple pages into a target map in one call.

Two execution-class components expose a `setMetaPageKeys(...)` setter paired with a runtime auto-pull at their natural lifecycle points:

- `Pipe` [see Meta-Page-Keys Surface on Pipe](#meta-page-keys-surface-on-pipe)
- `PumpStation` [see Meta-Page-Keys Surface on PumpStation](#meta-page-keys-surface-on-pumpstation)

`MultimodalContent` and `ContextWindow` are data carriers. They do not expose the meta-page-keys surface; their `metadata` and `metaData` fields are populated by other system controls and are not pulled from `MetadataBank` at runtime.

## Core Concept: Pages and the Bulk-Pull Pattern

A **page** is a `Map<Any, Any>` value stored in the bank under a `String` key. Pages are independent and replaceable; pages are not schema-validated and have no value-type contract beyond `Map<Any, Any>`.

A **bulk pull** takes a glued page-key string, parses it to a list of keys, and `putAll`-merges every page under those keys into a target map. The bank does not copy the target; the caller owns it. The bank provides the source pages; the caller provides the destination.

The bulk-pull pattern lets a developer address multiple pages with one short string parameter. A single `setMetaPageKeys("apex.flow_state, workflow.global_state")` call arms a pipe to populate its metadata from two pages, with the bank primitive reading both at runtime.

## Glued Page-Key String

The glued-string format is `"key1, key2, key3"`. Parsing rules:

- The string is split on `","`.
- Each resulting fragment is `trim()`-ed.
- Empty fragments are dropped.

Examples of valid inputs:

```
"alpha, beta, gamma"           // parses to ["alpha", "beta", "gamma"]
"alpha,beta,gamma"             // parses to ["alpha", "beta", "gamma"]  (whitespace tolerance)
"   alpha   ,   ,  beta  "     // parses to ["alpha", "beta"]          (whitespace and empties skipped)
""                            // parses to empty list — pull is a no-op
"single"                       // parses to ["single"]
```

Whitespace is tolerated around commas. Empty fragments between consecutive commas are skipped. A blank string parses to an empty key list, and any operation that takes a glued string as input is a no-op when the parsed list is empty.

## MetadataBank API

`MetadataBank` is a Kotlin `object` (process-singleton). Every public method ships as a blocking-and-`suspend` pair: the blocking variant uses `runBlocking { suspend }` and is the one non-coroutine code paths call; the `suspend` variant is the canonical path for any code already in a `suspend` context.

```kotlin
import com.TTT.Context.MetadataBank
```

### Object Lifecycle

`MetadataBank` lives for the lifetime of the JVM. There is no constructor and no `close()`. The bank is unbounded in memory by design; pages live until `clearSuspend` is invoked or the JVM exits.

The bank is process-singleton; one instance is shared by all callers in the JVM. Pages are not isolated by class loader or thread group.

### Single-Page Read and Write

```kotlin
MetadataBank.setMeta(key: String, value: Map<Any, Any>)
suspend fun MetadataBank.setMetaSuspend(key: String, value: Map<Any, Any>)
```

Replace the page at `key` with `value`. Reference-assign under the per-page mutex so a concurrent merge into the same key cannot be lost. The caller owns the map instance; subsequent mutations to `value` are visible to the bank because the substrate holds the same reference.

`value: Map<Any, Any>` — the page contents. Ownership transfers to the caller; the bank does not deep-copy.

```kotlin
MetadataBank.getMeta(key: String): Map<Any, Any>?
suspend fun MetadataBank.getMetaSuspend(key: String): Map<Any, Any>?
```

Return the page at `key`, or `null` if no page exists. The returned map is the substrate's reference; mutating it mutates the bank's page. Treat the returned map as read-only unless intentional.

### Merge-into-Page

```kotlin
MetadataBank.emplace(key: String, value: Map<Any, Any>)
suspend fun MetadataBank.emplaceSuspend(key: String, value: Map<Any, Any>)
```

Merge `value` into the page at `key`. If `key` has no page yet, the operation is equivalent to `setMeta(key, value)`. If a page exists, every entry in `value` is merged into the existing page: keys present in both pages are overwritten by the incoming value; keys present only in the existing page are preserved. The full merge is atomic per-page.

The merge semantics mirror Kotlin's `MutableMap.putAll`: last-write-wins on key collision.

### Delete, Exists, Clear

```kotlin
MetadataBank.delete(key: String): Boolean
suspend fun MetadataBank.deleteSuspend(key: String): Boolean
```

Remove the page at `key`. Returns `true` if a page was removed, `false` if no page existed at `key`. The operation is idempotent for missing keys. Holds the per-page mutex so a concurrent `emplaceSuspend` cannot resurrect the key mid-delete.

```kotlin
MetadataBank.exists(key: String): Boolean
suspend fun MetadataBank.existsSuspend(key: String): Boolean
```

Return `true` if `key` currently maps to a page in the bank. Cheap structural check; no page copy.

```kotlin
MetadataBank.clear()
suspend fun MetadataBank.clearSuspend()
```

Empty the bank and reset the active-page pointer to `null`. Holds the bank-wide mutex for the full structural mutation. Pair with `emplaceSuspend` if a concurrent writer might be active at call time.

### Active-Page Pointer

```kotlin
MetadataBank.swapMeta(key: String)
suspend fun MetadataBank.swapMetaSuspend(key: String)
```

Promote the page at `key` to the active-page pointer. If `key` has no page, the active-page pointer is set to `null`. The page itself is not moved; the bank's `@Volatile` reference is reassigned to point at the existing page object.

```kotlin
MetadataBank.getActiveMeta(): Map<Any, Any>?
suspend fun MetadataBank.getActiveMetaSuspend(): Map<Any, Any>?
```

Read the active-page pointer. Returns `null` until the first `swapMetaSuspend` call promotes a key. The pointer can go stale: if the active page is later `deleteSuspend`d, the pointer still references the deleted object. Treat the returned map as read-only.

### Bulk Pull

```kotlin
MetadataBank.pullMetaPageKeysInto(target: MutableMap<Any, Any>, pageKeysGlued: String)
suspend fun MetadataBank.pullMetaPageKeysIntoSuspend(
    target: MutableMap<Any, Any>,
    pageKeysGlued: String
)
```

Read every page under the keys parsed from `pageKeysGlued` and merge each into `target` via `putAll`. The bulk pull is the canonical entry point for the meta-page-keys contract on TPipe execution classes [see Runtime Auto-Pull Lifecycle](#runtime-auto-pull-lifecycle).

Parameters:

- `target: MutableMap<Any, Any>` — the destination map. The caller owns it. Entries in `target` before the call are preserved unless a pulled page overrides them.
- `pageKeysGlued: String` — the glued page-key string [see Glued Page-Key String](#glued-page-key-string).

Returns: `Unit`. The merge is in-place on `target`.

Missing keys in the bank are skipped silently. Conflicting keys (same key present in two pages, or a key already in `target`) resolve last-write-wins, matching `MutableMap.putAll` semantics.

### Inspection

```kotlin
MetadataBank.keys(): Set<String>
suspend fun MetadataBank.keysSuspend(): Set<String>
```

Return a snapshot of every page key currently in the bank. The returned set is a stable copy; concurrent writes do not affect it. `ConcurrentHashMap.keys` iteration is weakly consistent under concurrent structural modification, but `.toSet()` materializes a point-in-time snapshot here.

```kotlin
MetadataBank.debugSnapshot(): Map<String, String>
suspend fun MetadataBank.debugSnapshotSuspend(): Map<String, String>
```

Return a stringified view of every page. For each entry, the page's `Map<Any, Any>.toString()` is captured as the snapshot value. This is a dev-only lossy view: nested maps coerce via Kotlin stdlib `toString`, and no serializer is invoked. Holds the bank-wide mutex to bind the iteration with the structural read.

## Meta-Page-Keys Surface on `Pipe`

```kotlin
import com.TTT.Pipe.Pipe
```

`Pipe` exposes the meta-page-keys contract on its `pipeMetadata: MutableMap<Any, Any>` bag. The bank primitive populates the bag at runtime; the dev does not call the pull method directly under normal flow.

```kotlin
fun Pipe.setMetaPageKeys(keys: String): Pipe
```

Record the glued page-key string for runtime auto-pull. Returns the pipe for chaining.

`keys: String` — the glued page-key string [see Glued Page-Key String](#glued-page-key-string).

```kotlin
fun Pipe.pullMetaPageKeysIntoPipeMetadata()
```

Pull metadata from every key in `pipe.metaPageKeys` into `pipe.pipeMetadata`. Last-write-wins on collision; missing keys silently skipped; no-op when `pipe.metaPageKeys` is blank. Runtime-invoked from `Pipe.execute*()`; the dev does not need to call this directly under normal flow.

```kotlin
fun Pipe.hasMetaPageKeys(): Boolean
```

Return `true` iff `pipe.metaPageKeys` is non-blank. Mirrors the boolean-flag pattern used by `readFromGlobalContext` and related `Pipe` flags: a bool the runtime checks, then pulls if true.

Example:

```kotlin
val pipe = BedrockPipe().setMetaPageKeys("apex.flow_state, workflow.global_state")
// Runtime auto-pulls at the readFromGlobalContext block of Pipe.execute*().
// No explicit pullMetaPageKeysIntoPipeMetadata() call required.
val result = pipe.execute(content)
```

## Meta-Page-Keys Surface on `PumpStation`

```kotlin
import com.TTT.Pipeline.PumpStation
```

`PumpStation` exposes the meta-page-keys contract on its `metadata: MutableMap<Any?, Any?>` bag. The bag accepts `Any?`-keyed entries, so the bank primitive's `Any`-keyed result is bridged through a transient `MutableMap<Any, Any>` view before being written back into the `Any?` bag.

```kotlin
fun PumpStation.setMetaPageKeys(keys: String): PumpStation
```

Record the glued page-key string for runtime auto-pull. Returns the station for chaining.

`keys: String` — the glued page-key string.

```kotlin
fun PumpStation.pullMetaPageKeysIntoPumpStationMetadata()
```

Pull metadata from every key in `pumpStation.metaPageKeys` into `pumpStation.metadata`. Last-write-wins on collision; missing keys silently skipped; no-op when `pumpStation.metaPageKeys` is blank. Runtime-invoked from `Pipe.execute*()`'s `readFromPumpStationContext` block; the dev does not need to call this directly under normal flow.

The bridge between the bank's `MutableMap<Any, Any>` and `PumpStation.metadata: MutableMap<Any?, Any?>` means keys with `null` payloads are preserved; null keys in the bank are not representable through this surface.

```kotlin
fun PumpStation.hasMetaPageKeys(): Boolean
```

Return `true` iff `pumpStation.metaPageKeys` is non-blank.

Example:

```kotlin
val station = PumpStation().setDispatchAgent(Pipeline())
    .setMetaPageKeys("apex.flow_state, workflow.global_state")
// Runtime auto-pulls at the readFromPumpStationContext block when Pipe
// addresses this station during execute.
```

## Runtime Auto-Pull Lifecycle

The meta-page-keys surface on `Pipe` and `PumpStation` is auto-pulled by `Pipe.execute*()` at two specific lifecycle points. The dev sets the glued string; the runtime fires the pull before any metadata access.

For `Pipe`:

1. Inside `Pipe.execute*()`, the runtime evaluates `readFromGlobalContext`.
2. After the existing context-pull block completes, the runtime checks `pipe.hasMetaPageKeys()`.
3. If true, the runtime invokes `pipe.pullMetaPageKeysIntoPipeMetadata()` to populate `pipe.pipeMetadata`.

For `PumpStation`:

1. Inside `Pipe.execute*()`, the runtime evaluates `readFromPumpStationContext`.
2. After the existing context-window and mini-bank pulls, the runtime checks `pumpStation.hasMetaPageKeys()` (via the parent `pumpStationParent` lookup at line 6592).
3. If true, the runtime invokes `pumpStationParent.pullMetaPageKeysIntoPumpStationMetadata()` to populate `pumpStation.metadata`.

The pull is idempotent within a single execution. The pull fires on every `Pipe.execute*()` call when the boolean flag is true. Repeated pulls within the same execution re-merge from the bank into the destination; entries already in the destination are preserved unless a pulled page overrides them.

For the `Pipe`-of-the-`Pipe` case (a pipe referencing another pipe's metadata via `parentPipeRef.pipeMetadata`), the runtime auto-pull only fires for the executing pipe and for the nearest pump station parent. Intermediate pipes do not auto-pull unless their `metaPageKeys` is set explicitly.

## Concurrency Contract

`MetadataBank` is safe for concurrent use under the following primitives:

| Operation | Concurrency primitive |
|---|---|
| `getMetaSuspend` | `ConcurrentHashMap` structural read. No lock held. |
| `setMetaSuspend` | Per-page `Mutex` (allocated lazily via `computeIfAbsent`). |
| `emplaceSuspend` | Per-page `Mutex` (full R-M-W window held). |
| `deleteSuspend` | Per-page `Mutex`. |
| `swapMetaSuspend` | `swapMutex` (active-page pointer reassignment). |
| `getActiveMetaSuspend` | No lock. `@Volatile` read. |
| `clearSuspend` | `bankMutex` (full structural mutation). |
| `pullMetaPageKeysIntoSuspend` | Per-page read for each parsed key; no lock held across the loop. |
| `keysSuspend` | `ConcurrentHashMap.keys.toSet()` (weakly consistent iteration materialized to snapshot). |
| `debugSnapshotSuspend` | `bankMutex`. |

The per-page mutex is an advisory concurrency primitive. It is not a semantic content gate; a thread that does not take the mutex can still mutate the page. The mutex serializes the `emplaceSuspend` R-M-W window against another `emplaceSuspend` or `setMetaSuspend` or `deleteSuspend` on the same key.

For the `setMetaSuspend` / `emplaceSuspend` / `deleteSuspend` family, the per-page mutex guarantees atomicity of the bank's internal view. For two callers both reading via `getMetaSuspend` and then merging outside the bank (a pattern the surface discourages), no atomicity is provided — callers that need atomic R-M-W should use `emplaceSuspend`.

For `pullMetaPageKeysIntoSuspend`, the loop reads each page independently. A `setMetaSuspend` that fires mid-loop can change one of the pages already iterated, and the change will not appear in `target`. This is consistent with `ConcurrentHashMap` weakly-consistent iteration semantics.

## What This Surface Does Not Cover

The current surface is in scope as follows:

| Capability | Status |
|---|---|
| In-memory page-keyed scratchpad | Covered |
| Bulk pull from glued-string page-key list | Covered |
| Runtime auto-pull on `Pipe.execute*()` | Covered |
| Runtime auto-pull on `PumpStation` via nearest-parent walk | Covered |
| `PumpStation.metadata: MutableMap<Any?, Any?>` bridge | Covered |
| Per-page atomic R-M-W | Covered (`setMetaSuspend`, `emplaceSuspend`, `deleteSuspend`) |

The following are explicitly out of scope for the current surface:

| Capability | Status |
|---|---|
| Disk persistence | Out of scope. The bank lives in memory only and evaporates with the JVM. |
| Remote sharing | Out of scope. No `MemoryClient` / `MemoryServer` integration. |
| Cache eviction | Out of scope. Pages live until `clearSuspend`. |
| Lorebook-style page-key semantic locks | Out of scope. The per-page `Mutex` is an advisory concurrency primitive only. |
| Value-type schema validation | Out of scope. Pages are untyped `Map<Any, Any>`. |
| `MultimodalContent` runtime auto-pull | Out of scope. `MultimodalContent` is a data carrier; its `metadata` field is set by other system controls. |
| `ContextWindow` runtime auto-pull | Out of scope. `ContextWindow` is a data carrier; its `metaData` field is set by other system controls. |
| `Manifold`, `Junction`, `Splitter`, `DistributionGrid`, `Pipeline`, `PathObject` runtime auto-pull | Out of scope. These execution classes do not have a per-instance `metadata` field at the current version. |

## Examples

### Example 1: Two-Tool Pipe Sharing State via the Bank

Apex writes its flow state to the bank under `apex.flow_state`; the worker pipe reads it into its own metadata at runtime.

```kotlin
import com.TTT.Context.MetadataBank
import com.TTT.Pipe.Pipe

// Apex-side, before invoking the worker:
MetadataBank.setMeta("apex.flow_state", mapOf<Any, Any>(
    "lastTool" to "search",
    "step" to 3
))

// Worker-side configuration:
val worker = MyToolPipe().setMetaPageKeys("apex.flow_state")

// Inside worker execution, no manual pull required — runtime auto-fills worker.pipeMetadata
// with the bank contents before any metadata access.
val lastTool = worker.pipeMetadata["lastTool"]  // "search"
```

Expected behavior: at execution time, `worker.pipeMetadata` contains `lastTool = "search"` and `step = 3` without the developer calling `pullMetaPageKeysIntoPipeMetadata()` explicitly.

### Example 2: PumpStation Carrying Workflow Global State

```kotlin
import com.TTT.Context.MetadataBank
import com.TTT.Pipeline.PumpStation

MetadataBank.emplace("workflow.global_state", mapOf<Any, Any>(
    "ticketId" to "TICKET-1234",
    "priority" to "high"
))

val station = PumpStation()
    .setDispatchAgent(workflowPipeline)
    .setMetaPageKeys("workflow.global_state")

// When the dispatch agent's pipe executes with readFromPumpStationContext = true,
// the runtime auto-pulls workflow.global_state into station.metadata before
// any metadata access.
```

Expected behavior: at dispatch time, `station.metadata["ticketId"]` is `"TICKET-1234"` and `station.metadata["priority"]` is `"high"`. The dispatch agent's pipe can read these without an explicit pull call.

### Example 3: Bulk Pull from Multiple Pages

```kotlin
import com.TTT.Context.MetadataBank

MetadataBank.setMeta("pageA", mapOf<Any, Any>("x" to 1, "shared" to "fromA"))
MetadataBank.setMeta("pageB", mapOf<Any, Any>("y" to 2, "shared" to "fromB"))

val target = mutableMapOf<Any, Any>("preset" to "kept")
MetadataBank.pullMetaPageKeysInto(target, "pageA, pageB")

// target now contains:
//   "preset" -> "kept"   (preserved from before the pull)
//   "x"      -> 1        (from pageA)
//   "y"      -> 2        (from pageB)
//   "shared" -> "fromB"  (last-write-wins: pageB overrides pageA)
```

### Example 4: Active-Page Pointer Round-Trip

```kotlin
import com.TTT.Context.MetadataBank

MetadataBank.setMeta("active", mapOf<Any, Any>("hot" to true))
MetadataBank.swapMeta("active")
val current: Map<Any, Any>? = MetadataBank.getActiveMeta()
// current == {"hot" to true}

MetadataBank.delete("active")
val stale: Map<Any, Any>? = MetadataBank.getActiveMeta()
// stale still references the deleted object: {"hot" to true}
```

Expected behavior: the active-page pointer is not invalidated by `deleteSuspend`. Treat the returned map as read-only; treat stale pointers as an expected state.

### Example 5: Inspecting the Bank

```kotlin
import com.TTT.Context.MetadataBank

val allKeys: Set<String> = MetadataBank.keys()
val snapshot: Map<String, String> = MetadataBank.debugSnapshot()

for (entry in snapshot) {
    println("${entry.key} = ${entry.value}")
}
```

Expected output:

```
pageA = {x=1, shared=fromA}
pageB = {y=2, shared=fromB}
```

`debugSnapshot()` is a dev-only lossy view. Nested maps coerce via Kotlin stdlib `toString`. Do not use `debugSnapshot()` for serialization; use the page references from `getMetaSuspend` directly.

### Example 6: Pipe Auto-Pull Fires Only on the Executing Pipe

```kotlin
import com.TTT.Pipe.Pipe
import com.TTT.Context.MetadataBank

MetadataBank.setMeta("shared", mapOf<Any, Any>("value" to 42))

val pipeA = MyPipe().setMetaPageKeys("shared")
val pipeB = MyPipe()  // no setMetaPageKeys — hasMetaPageKeys() returns false

// When pipeA executes, its pipeMetadata is auto-populated with {"value" -> 42}.
// When pipeB executes, no pull fires; pipeB.pipeMetadata is empty.
```

Expected behavior: only pipes with `setMetaPageKeys(...)` set participate in the auto-pull at execute-time. Pipes without the surface do not see bank contents.
