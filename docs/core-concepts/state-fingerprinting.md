# State Fingerprinting

> 💡 **Tip:** A state fingerprint is TPipe's isDirty() check for whole objects. Snapshot a pipe's semantic state once, then detect later whether anything inside it changed — without the developer manually reporting the change.


## Table of Contents
- [What It Is](#what-it-is)
- [When To Use It](#when-to-use-it)
- [Tutorial](#tutorial)
- [Use Cases](#use-cases)
- [Behavioral Contract](#behavioral-contract)
- [What Is Included, What Is Excluded](#what-is-included-what-is-excluded)
- [Limitations](#limitations)

TPipe ships a deterministic state-fingerprint mechanism in the Util package (`StateHasher.kt`, package `com.TTT.Util`). It walks an object's semantic state with `kotlin-reflect`, encodes the reachable graph into a canonical byte form, and digests it with SHA-256. The result is a 64-character hex string that changes — and only changes — when the object's semantic state changes.

## What It Is

- `stateHash(root, options)` — hashes the state of any object graph.
- `isDirty(root, lastHash, options)` — compares a fresh hash against a stored one.
- `Pipe.stateSnapshot()` / `Pipe.isDirtySince(lastSnapshot)` — pipe-typed convenience extensions.

No `sun.misc.Unsafe`, no native code, no FFM heap addressing: the fingerprint is a pure-JVM reflection canonicalization, so it is stable across JDK upgrades without rework.

## When To Use It

- A pipe's configuration can be mutated at execution time (DITL `preExecuteFunction` hooks and friends are stateful by design — the KDoc on `Pipe.preExecuteFunction` warns the modified state persists past the run). Snapshot before the run, compare after, and you know if the pipe drifted.
- You want to invalidate caches keyed on a pipe's structure.
- You want a regression guard that pins a pipe's shape after arbitrary initialization code.

## Tutorial

```kotlin
import com.TTT.Pipe.DummyPipe
import com.TTT.Util.stateSnapshot
import com.TTT.Util.isDirtySince

val pipeline = DummyPipe()
    .setPipeName("report-pipeline")
    .setConcurrency(2)

val baselineHash = pipeline.stateSnapshot()

// Later, after some DITL hook or operator code may have reconfigured the pipeline:
if(pipeline.isDirtySince(baselineHash))
{
    println("Pipeline config drifted since snapshot — rebuild before re-running")
}
```

For generic (non-pipe) objects, use the top-level functions directly:

```kotlin
import com.TTT.Util.stateHash
import com.TTT.Util.isDirty

val config = MySettings()
val beforeHash = stateHash(config)

config.reload() // may or may not change anything

if(isDirty(config, beforeHash))
{
    // some included field moved — act on it
}
```

## Use Cases

1. **Drift detection after stateful DITL mutations.** Snapshot at pipe build time; after any execution path that can reconfigure the pipe, `isDirtySince` tells you the pipe is no longer the pipe you built.
2. **Cache invalidation.** Key memoized results on `stateSnapshot()`; a structural change recomputes, a cosmetic re-run does not.
3. **Regression guards in tests.** Assert that an initializer's side effects left the pipe structurally intact by comparing against a baseline snapshot.

## Behavioral Contract

- **Determinism:** same semantic state, same options, same JVM → same digest. Map/Set insertion order never changes the hash (entries/elements are canonically sorted); List order does.
- **Cycle safety:** reference cycles terminate via an identity guard and emit a stable cycle marker.
- **Caps:** the walk stops descending at `maxDepth` (default 64) or after `maxObjects` encodings (default 10000), emitting a stable truncated marker — capped graphs hash deterministically, just differently from uncapped ones.
- **Throwing getters** degrade to a stable exception-class marker instead of aborting the whole fingerprint.
- **Function values** encode a marker by default (two fresh pipes with equivalent lambda defaults hash equal); pass `setEncodeFunctionIdentity(true)` to make distinct lambda instances hash differently.
- **NaN payloads** hash by raw bits — two differently-encoded NaNs differ.
- **No Unsafe:** the implementation uses neither `sun.misc.Unsafe` nor native code, so the fingerprint stays valid as the JDK marches forward.

## What Is Included, What Is Excluded

**Included:** the full declared and inherited property surface of every reachable object — including private properties (the `memberProperties` seam that `cloneInstance` persists, `Util.kt:804-812`; in this build the seam empirically exposes private members, so they are hashed by default), transitive objects, List contents, Map/Set entries, arrays, strings, enums, and primitives. A caller who wants the narrower boundary can opt out per property with `StateHashOptions().exclude { property -> ... }`.

**Excluded by default:** properties annotated `@RuntimeState` (per-execution state: counters, pause flags, session caches, generated identities) or annotated with any annotation whose qualified name contains `Transient` (covers `@kotlinx.serialization.Transient`). Override the whole boundary with `StateHashOptions().exclude { property -> ... }`.

**Note on generated identifiers:** `Pipe.pipeId` (`Pipe.kt:2069`) is a per-instance generated UUID and *is* hashed by design — it is part of the clone-persisted boundary. Two independently built pipes therefore hash equal only when their generated identifiers match. Pin them explicitly (or exclude `pipeId` via `exclude {}`) when you want pure structural parity.

## Limitations

- **Bare Java fields without accessors are outside the surface** — the walk is property-based, so a Java class field that exposes no getter/setter pair is invisible to it. Kotlin private properties *are* inside the surface (the seam includes them); narrow the boundary with `StateHashOptions().exclude { ... }` when needed.
- **In-JVM guarantee:** digests are guaranteed stable within one JVM run-to-run; comparing digests across JVM restarts or processes is not promised.
- **Capped graphs** produce a truncated-but-stable digest; raising the caps after the fact changes the hash.
