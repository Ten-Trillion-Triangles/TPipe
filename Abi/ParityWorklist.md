# TPipe ABI Parity Worklist

**Date:** 2026-06-08
**Branch:** ABI
**Status:** 204 / 204 symbols at 100% on the 22 documented C ABI feature groups (Cycle 3).
**Goal:** Extend the C ABI surface from the 22 documented feature groups to cover the
remaining ~211 public JVM methods not yet exposed via the C ABI. (35 added in Cycle 3.)

---

## Current State

| Subsystem | JVM Methods | Native ABI | Parity |
|-----------|------------:|-----------:|-------:|
| Pipe.kt | 173 | 20 | 12% |
| Pipeline.kt | 44 | 9 | 20% |
| Manifold.kt | 60 | 8 | 13% |
| Junction.kt | 100 | 5 | 5% |
| Splitter.kt | 30 | 5 | 17% |
| Connector.kt | 20 | 5 | 25% |
| DistributionGrid.kt | 200 | 9 | 4.5% |
| ContextWindow.kt | 24 | 6 | 25% |
| LoreBook.kt | 2 | 16 | (over-covered) |
| ConverseData.kt | 5 | 0 | 0% |
| **Total (tracked)** | **~660** | **168** | **~25%** |

> The 22 documented C ABI feature groups report 100% on their declared symbols
> (169/169). The worklist below tracks the *expanded* coverage needed to expose
> the full JVM public surface.

---

## Cycle 3 - High-Impact Configuration Methods

Cycle 3 targets the simplest, most impactful configuration surface: integer
and string setters/getters on the container layer (Junction, Manifold,
Splitter, Connector). These avoid DSL suspend-lambda marshaling and
P2PInterface wrapping, both of which are out of scope for the C ABI.

### Junction Configuration (~15 functions)

| # | C Symbol | JVM Method | Signature | Status |
|---|----------|------------|-----------|--------|
| 1 | TPipe_Junction_setStrategy | setStrategy(strategy) | (handle, int) -> int | DONE |
| 2 | TPipe_Junction_getStrategy | getStrategy() | (handle, int*) -> int | DONE |
| 3 | TPipe_Junction_setRounds | setRounds(rounds) | (handle, int) -> int | DONE |
| 4 | TPipe_Junction_getRounds | getRounds() | (handle, int*) -> int | DONE |
| 5 | TPipe_Junction_setVotingThreshold | setVotingThreshold(threshold) | (handle, double) -> int | DONE |
| 6 | TPipe_Junction_getVotingThreshold | getVotingThreshold() | (handle, double*) -> int | DONE |
| 7 | TPipe_Junction_setMaxNestedDepth | setMaxNestedDepth(depth) | (handle, int) -> int | DONE |
| 8 | TPipe_Junction_getMaxNestedDepth | getMaxNestedDepth() | (handle, int*) -> int | DONE |
| 9 | TPipe_Junction_setWorkflowRecipe | setWorkflowRecipe(recipe) | (handle, int) -> int | DONE |
| 10 | TPipe_Junction_getWorkflowRecipe | getWorkflowRecipe() | (handle, int*) -> int | DONE |
| 11 | TPipe_Junction_getTraceId | getTraceId() | (handle, buf, size) -> int | DONE |
| 12 | TPipe_Junction_getFailureAnalysis | getFailureAnalysis() | (handle, buf, size) -> int | DONE |
| 13 | TPipe_Junction_getMemoryPolicy | getMemoryPolicy() | (handle, int*) -> int | DONE |
| 14 | TPipe_Junction_setMemoryPolicy | setMemoryPolicy(policy) | (handle, int, int) -> int | DONE |
| 15 | TPipe_Junction_enableTracing | enableTracing() | (handle) -> int | DONE |

### Manifold Configuration (~10 functions)

| # | C Symbol | JVM Method | Signature | Status |
|---|----------|------------|-----------|--------|
| 16 | TPipe_Manifold_setContextWindowSize | setContextWindowSize(size) | (handle, int) -> int | DONE |
| 17 | TPipe_Manifold_getContextWindowSize | getContextWindowSize() | (handle, int*) -> int | DONE |
| 18 | TPipe_Manifold_setTruncationMethod | setTruncationMethod(method) | (handle, int) -> int | DONE |
| 19 | TPipe_Manifold_getTruncationMethod | getTruncationMethod() | (handle, int*) -> int | DONE |
| 20 | TPipe_Manifold_setSummaryMode | setSummaryMode(mode) | (handle, int) -> int | DONE |
| 21 | TPipe_Manifold_getMaxLoopIterations | getMaxLoopIterations() | (handle, int*) -> int | DONE |
| 22 | TPipe_Manifold_hasLoopLimit | hasLoopLimit() | (handle, int*) -> int | DONE |
| 23 | TPipe_Manifold_getWorkerPipelines | getWorkerPipelines() | (handle, buf, size) -> int | DONE |
| 24 | TPipe_Manifold_setManagerTokenBudget | setManagerTokenBudget(budget) | (handle, int) -> int | DONE |
| 25 | TPipe_Manifold_getManagerTokenBudget | getManagerTokenBudget() | (handle, int*) -> int | DONE |

### Splitter Configuration (~3 functions)

| # | C Symbol | JVM Method | Signature | Status |
|---|----------|------------|-----------|--------|
| 26 | TPipe_Splitter_addPipeline | addPipeline(pipeline) | (handle, long) -> int | DONE |
| 27 | TPipe_Splitter_getAllChildPipelines | getAllChildPipelines() | (handle, int*) -> int | DONE |
| 28 | TPipe_Splitter_removePipeline | removePipeline(pipeline) | (handle, long) -> int | DONE |

### Connector Configuration (~2 functions)

| # | C Symbol | JVM Method | Signature | Status |
|---|----------|------------|-----------|--------|
| 29 | TPipe_Connector_add | add(pipeline) | (handle, long) -> int | DONE |
| 30 | TPipe_Connector_get | get(key) | (handle, char*, int*) -> long | DONE |

**Cycle 3 result:** 35 new C ABI functions delivered (target was 30, exceeded by 5). Status: COMPLETE.

| Subsystem | Functions Added | Notes |
|-----------|----------------:|-------|
| Junction | 17 | setStrategy, setRounds, setVotingThreshold, setMaxNestedDepth, setWorkflowRecipe, setMemoryPolicy, enable/disableTracing, getTraceId, getFailureAnalysis (+ getters and PolicyEx variant) |
| Manifold | 12 | setContextWindowSize, setTruncationMethod, setSummaryMode, setManagerTokenBudget (+ getters) |
| Splitter | 4 | addPipeline, removePipeline, getAllChildPipelines, getChildCount |
| Connector | 2 | add, get |
| **Total** | **35** | All completion tests pass.

---

## Future Cycles

### Cycle 4 - Pipe Configuration (~50 functions)
The next-largest gap is Pipe.kt. Target the simple setters/getters that map
to integer/float/string fields:
- setModel, setSystemPrompt, setUserPrompt, setMiddlePrompt,
  setFooterPrompt, setTopP, setTopK, setMaxTokens, setSeed,
  setStopSequences, setMultimodalInput, setContextWindowSize,
  enableTracing, disableTracing, etc.
- For setters with complex object types (KClass<*>, suspend lambdas),
  expose as C ABI stubs that return TPIPE_ERR_UNSUPPORTED and document
  the limitation.

### Cycle 5 - DistributionGrid Configuration (~50 functions)
Largest absolute gap. Target simple configuration first:
- setWorker, setRouter, setRoutingPolicy, setMaxHops,
  setMaxSessionDuration, setRpcTimeout, setMemoryPolicy, etc.

### Cycle 6 - Pipeline Configuration (~10 functions)
- setPipelineName, getPipelineName, getTokenUsage, getPipes,
  getErrorMessage, hasError, enablePausing, pause, resume,
  getTraceId.

### Cycle 7 - ContextWindow Configuration (~10 functions)
- addLoreBookEntry, merge, clear, isEmpty, isContextLocked,
  getLockedKeys, truncateConverseHistory, countConverseHistoryTokens.

### Out of Scope
- DSL suspend-lambda configuration methods (e.g. setExceptionFunction)
  - require FFI thunks for lambda marshaling, deferred to a later phase.
- JVM-internal accessors (e.g. setP, getContainerObject) - internal
  pattern, not user-callable.
- P2P-interface binding methods on Junction (setModerator, setPlanner,
  etc.) - require P2PInterface handle wrapping, deferred to a later phase.

---

## How to Use This Worklist

1. Pick a row. The row order is priority order within each cycle.
2. Follow the per-function TDD loop:
   1. Append a red @Test to the relevant *HandleTest.kt.
   2. Run the test; confirm RED.
   3. Add the method to the relevant *Handle.kt class. The
      *HandleCompletionTest will then look for TPipe_<Family>_<verb>
      in the header and fail.
   4. Add the @JvmStatic wrapper in NativeBridge.kt.
   5. Add the @CEntryPoint shim in TPipeBootstrap.java.
   6. Add the declaration to tpipe-abi.h.
   7. Re-run the test; confirm GREEN.
3. Update this worklist: flip the row's status from TODO to DONE.
4. Update Abi/PARITY-CYCLE<N>-<date>.md at the end of the cycle
   with the scorecard and any new feature groups added.
