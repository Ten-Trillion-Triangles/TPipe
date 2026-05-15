# TPipe GraalVM ABI — Full-Scale Review: Work vs Spec

**Review date:** 2026-05-15
**Worktree:** `/home/cage/Desktop/Workspaces/tpipe-abi-work` (branch `feature/graalvm-abi`, commit `19ea5934`)
**Spec source:** `Abi/specs/` (12 documents, ~8262 lines)
**Source implementation:** `src/main/kotlin/com/TTT/Native/` (5 files, ~1074 lines)

---

## Executive Summary

The ABI spec suite is **comprehensive and well-structured** — 12 spec documents covering every major surface area of the GraalVM Native ABI. The implementation skeleton in `com.TTT.Native` is a minimal scaffolding layer with stub C entry points that return hardcoded values.

**Verdict: INCOMPLETE.** The spec defines everything. The implementation stubs only the bootstrap layer. The gap is enormous — approximately 90% of the defined ABI surface has no corresponding Kotlin implementation.

---

## Part I: Spec Coverage Analysis

### Spec Suite Overview

| Spec File | Lines | Status | Coverage |
|-----------|-------|--------|----------|
| `graalvm-abi-overview.md` | 362 | ✅ COMPLETE | Library design, versioning, init/shutdown contract |
| `graalvm-abi-initialization.md` | 335 | ✅ COMPLETE | State machine, memory ownership, thread safety |
| `graalvm-abi-core-types.md` | 1,122 | ✅ COMPLETE | Handle system (20 types), all enum mappings |
| `graalvm-abi-core-infrastructure.md` | 281 | ✅ COMPLETE | Result codes, state machine, handle lifecycle |
| `graalvm-abi-pipe-api.md` | 1,249 | ✅ COMPLETE | ~100+ Pipe functions, async, streaming, callbacks |
| `graalvm-abi-pipeline-api.md` | 1,678 | ✅ COMPLETE | 7 container types, all lifecycle/execution/tracing |
| `graalvm-abi-handle-types.md` | 564 | ✅ COMPLETE (all TODO) | All handle type bindings — no implementation |
| `graalvm-abi-collection-handles.md` | 417 | ✅ COMPLETE (all TODO) | ListHandle, MapHandle operations — no impl |
| `graalvm-abi-reflection-config.md` | 440 | ✅ COMPLETE | reflect-config.json spec, ServiceLoader gaps |
| `graalvm-abi-distribution-grid-envelope.md` | 457 | ✅ COMPLETE | DG envelope C structs, all 9 directive kinds |
| `graalvm-abi-gap-analysis-summary.md` | 705 | ✅ COMPLETE | 12 gap areas, all resolved with decisions |
| `graalvm-abi-bootstrap-plan.md` | 471 | ✅ COMPLETE (all TODO) | Bootstrap architecture, phantom refs |
| `graalvm-abi-capability-version.md` | 181 | ✅ COMPLETE | Capability/version reporting |

**Spec quality: HIGH.** The spec suite is authoritative, well-organized, and internally consistent. Cross-references between specs are complete. Every decision is documented with rationale. The gap analysis is particularly thorough.

---

## Part II: Implementation Inventory

### 2.1 Native Layer Files (source)

```
src/main/kotlin/com/TTT/Native/
├── LibraryState.kt          25 lines  — 5-state enum (LOADED→INITIALIZING→READY→SHUTTING_DOWN→SHUTDOWN)
├── TPipeResult.kt           19 lines  — Result code constants (TPIPE_OK through TPIPE_ERR_INTERNAL=99)
├── TpipeRuntime.kt         139 lines  — Runtime singleton with state machine, executor, registry ref
├── TPipeBootstrap.kt.bak    49 lines  — Stub @CEntryPoint methods (8 functions, all return 0/-1)
└── TPipeBridge.kt          891 lines  — Context/History/LoreBook/Pipeline operations via reflection
```

**Total: 5 files, ~1,074 lines.**

### 2.2 Native Layer: What Exists vs What's Stubbed

**EXISTS (actual implementation):**
- `LibraryState.kt` — complete 5-state enum matching spec
- `TPipeResult.kt` — complete result codes matching spec
- `TpipeRuntime.kt` — state machine with CAS-based initialization, executor, registry reference
- `TPipeBridge.kt` — context/lorebook/history/pipeline ops via Java reflection, native PCP registry

**STUBBED (hardcoded return values):**
- `TPipeBootstrap.kt.bak` — 8 @CEntryPoint functions that return 0/-1 without performing any real work:
  - `TPipe_init()` → returns 0 (TPIPE_OK) without initializing anything
  - `TPipe_shutdown()` → returns 0
  - `TPipe_getState()` → returns 0 (LOADED)
  - `TPipe_isInitialized()` → returns 0
  - `TPipe_Handle_addRef()` → returns 0
  - `TPipe_Handle_release()` → returns 0
  - `TPipe_Handle_getRefCount()` → returns -1
  - `TPipe_Handle_isValid()` → returns 0

**MISSING (not present at all):**
- `TPipeHandleRegistry.kt` — referenced in `TpipeRuntime.kt` but not implemented anywhere
- All handle type binding implementations (ContentHandle, ContextHandle, MiniBankHandle, etc.)
- All container implementations (Pipeline, Manifold, Junction, Splitter, Connector, MultiConnector, DistributionGrid)
- All config structs for container creation
- Gradle native image plugin configuration
- `resource-config.json` with META-INF/services entries
- At-exit hook implementation

---

## Part III: Detailed Gap Analysis

### Category A: Bootstrap Layer (Partially Implemented)

| Spec Function | Implementation | Status |
|---|---|---|
| `TPipe_init()` | `TPipeBootstrap.kt` stub returning 0 | ❌ NOT IMPLEMENTED — no actual initialization |
| `TPipe_shutdown()` | Stub returning 0 | ❌ NOT IMPLEMENTED — no cleanup |
| `TPipe_getState()` | Stub returning 0 | ❌ NOT IMPLEMENTED — always returns LOADED |
| `TPipe_isInitialized()` | Stub returning 0 | ❌ NOT IMPLEMENTED |
| `TPipe_Handle_addRef()` | Stub returning 0 | ❌ NOT IMPLEMENTED — no ref counting |
| `TPipe_Handle_release()` | Stub returning 0 | ❌ NOT IMPLEMENTED |
| `TPipe_Handle_getRefCount()` | Stub returning -1 | ❌ NOT IMPLEMENTED |
| `TPipe_Handle_isValid()` | Stub returning 0 | ❌ NOT IMPLEMENTED |
| `TPipe_getCapabilities()` | Not present | ❌ NOT IMPLEMENTED |
| `TPipe_getVersion()` | Not present | ❌ NOT IMPLEMENTED |

**Gap severity: CRITICAL.** The entire bootstrap layer is non-functional stubs. The spec defines the behavior precisely (state machine, idempotent init, thread-safe shutdown) but the Kotlin implementation does none of it.

### Category B: Handle Table (Missing)

`TpipeRuntime.kt` line 38 references `TPipeHandleRegistry.getInstance()` but no such class exists in the source tree. The spec (`graalvm-abi-core-infrastructure.md` §5.4) defines the handle table as:

```kotlin
object HandleTable {
    private val handles = ConcurrentHashMap<u64, RefCountedObject>()
    fun allocate(data: Any): u64
    fun get(id: u64): RefCountedObject?
    fun addRef(id: u64): Int
    fun release(id: u64): Boolean
}
```

**Gap severity: CRITICAL.** Without the handle table, no handle-based API can function. The stub's addRef/release/isValid/getRefCount all call a non-existent registry.

### Category C: Handle Type Bindings (100% Missing)

`graalvm-abi-handle-types.md` defines 18 handle types. All are marked ☐ TODO.

| Handle | Spec Section | Implementation |
|--------|-------------|----------------|
| `TPipe_ContentHandle` (MultimodalContent) | §2 | ❌ Missing |
| `TPipe_BinaryHandle` (BinaryContent) | §3 | ❌ Missing |
| `TPipe_ContextHandle` (ContextWindow) | §4 | ❌ Missing |
| `TPipe_MiniBankHandle` (MiniBank) | §5 | ❌ Missing |
| `TPipe_ListHandle` | collection spec | ❌ Missing |
| `TPipe_MapHandle` | collection spec | ❌ Missing |
| `TPipe_PipeSettingsHandle` (PipeSettings) | §6 | ❌ Missing |
| `TPipe_ConverseHistoryHandle` (ConverseHistory) | §7 | ❌ Missing |
| `TPipe_TokenBudgetHandle` (TokenBudgetSettings) | §8 | ❌ Missing |
| `TPipe_LoreBookHandle` (LoreBook) | §9 | ❌ Missing |
| `TPipe_TraceConfigHandle` (TraceConfig) | §10 | ❌ Missing |
| `TPipe_ErrorHandle` (PipeError) | §11 | ❌ Missing |
| `TPipe_PCPHandle` (PCP module) | §12 | ❌ Missing |
| `TPipe_StdioContextHandle` | §13 | ❌ Missing |
| `TPipe_HttpContextHandle` | §14 | ❌ Missing |
| `TPipe_P2PTransportHandle` (P2PTransport) | §15 | ❌ Missing |
| `TPipe_P2PDescriptorHandle` (P2PDescriptor) | §16 | ❌ Missing |
| `TPipe_P2PRequirementsHandle` (P2PRequirements) | §17 | ❌ Missing |

**`TPipeBridge.kt` context operations:** `TPipeBridge` has context/lorebook/history operations via reflection that could serve as the foundation for `ContextWindow`/`LoreBook`/`ConverseHistory` handle bindings — but they are standalone static methods, not wrapped as handle operations.

**Gap severity: CRITICAL.** The handle system is the foundation of the entire ABI. All operations require handles.

### Category D: Pipe API (0% Implemented)

`graalvm-abi-pipe-api.md` defines ~100+ functions. None are implemented.

Required functions include:
- `TPipe_Pipe_create()` / `release()` / `addRef()`
- `TPipe_Pipe_init()` / `isInitialized()` / `abort()`
- `TPipe_Pipe_setProvider()` / `setModel()` / `setSystemPrompt()` / `setUserPrompt()`
- `TPipe_Pipe_setTemperature()` / `setTopP()` / `setTopK()` / `setMaxTokens()`
- `TPipe_Pipe_setRepetitionPenalty()` / `setPresencePenalty()` / `setSeed()`
- `TPipe_Pipe_setReasoning()` / `disableReasoning()`
- `TPipe_Pipe_execute()` / `executeContent()`
- `TPipe_Pipe_executeContentAsync()` + `TPipe_Async_*` functions
- `TPipe_Pipe_getTokenUsage()` / `getErrorMessage()`
- Streaming callbacks, logit bias, stop sequences, and more

**Gap severity: CRITICAL.** The Pipe API is the primary LLM interaction surface.

### Category E: Pipeline API (0% Implemented)

`graalvm-abi-pipeline-api.md` defines the full API for all 7 container types (1,678-line spec). None are implemented.

| Container | Config Struct | Lifecycle | Execution | P2PInterface |
|-----------|---------------|----------|-----------|--------------|
| `Pipeline` | ✅ spec'd | ❌ | ❌ | ❌ |
| `Manifold` | ✅ spec'd | ❌ | ❌ | ❌ |
| `Junction` | ✅ spec'd | ❌ | ❌ | ❌ |
| `Splitter` | ✅ spec'd | ❌ | ❌ | ❌ |
| `Connector` | ✅ spec'd | ❌ | ❌ | ❌ |
| `MultiConnector` | ✅ spec'd | ❌ | ❌ | ❌ |
| `DistributionGrid` | ✅ spec'd | ❌ | ❌ | ❌ |

**Gap severity: CRITICAL.** All 7 containers are defined in the spec but have zero Kotlin wrapper implementations.

### Category F: reflection-config.json (Partially Implemented)

The existing `reflection-config.json` at `src/main/resources/META-INF/native-image/reflection-config.json` has 10 entries:

| Class | Fields | Status |
|-------|--------|--------|
| `com.TTT.Context.ContextWindow` | contextElements, tokenBudget, isInitialized | ⚠️ PARTIAL — missing loreBookKeys, converseHistory, version, metaData |
| `com.TTT.Context.LoreBook` | key, value, linkedKeys, aliasKeys, requiredKeys | ✅ Complete |
| `com.TTT.Context.ConverseHistory` | history, add() method | ⚠️ PARTIAL — field is "history" but spec references ".history" property, naming mismatch |
| `com.TTT.Context.ConverseRole` | allDeclaredFields, allDeclaredMethods | ✅ Complete |
| `com.TTT.Pipeline.Pipeline` | pipes, name | ⚠️ PARTIAL — missing traceConfig, errorMessage, onTerminate, terminationState |
| `com.TTT.Native.TPipeHandleRegistry` | handleTable | ⚠️ Class doesn't exist — reference is dead |
| `com.TTT.Pipe.Pipe` | allDeclaredFields | ⚠️ PARTIAL — no methods registered, no specific fields |
| `com.TTT.Pipe.MultimodalContent` | text, binaryData, modelReasoning | ⚠️ PARTIAL — missing binaryContentType, jumpTo, terminate, etc. |
| `com.TTT.PipeContextProtocol.FunctionRegistry` | allDeclaredFields | ⚠️ PARTIAL — no methods |
| `com.TTT.PipeContextProtocol.PcPRequest` | allDeclaredFields, allDeclaredMethods | ⚠️ PARTIAL |
| `com.TTT.PipeContextProtocol.PcpContext` | allDeclaredFields | ⚠️ PARTIAL |

**Missing critical entries per spec:**
- `com.TTT.P2P.P2PDescriptor` — P2P user-facing type per gap analysis §2.6
- `com.TTT.P2P.P2PTransport` — transport config per spec
- `com.TTT.P2P.P2PSkills` / `P2PSkill` — skills model per spec
- `com.TTT.P2P.P2PRequirements` — requirements per spec
- `com.TTT.PipeContextProtocol.StdioContextOptions` — PCP stdio config
- `com.TTT.PipeContextProtocol.HttpContextOptions` — PCP HTTP config
- `com.TTT.PipeContextProtocol.PythonContext` — PCP Python config
- `com.TTT.PipeContextProtocol.TPipeContextOptions` — PCP TPipe config
- `com.TTT.Pipeline.Manifold` — Manifold DSL builder
- `com.TTT.Pipeline.DistributionGrid` — DistributionGrid
- `com.TTT.Pipeline.Splitter` — Splitter
- `com.TTT.Pipeline.Connector` — Connector
- `com.TTT.Pipeline.MultiConnector` — MultiConnector
- `com.TTT.Pipeline.Junction` — Junction

**ServiceLoader:** No `resource-config.json` entry for `META-INF/services/*`. Spec's bootstrap plan §10 requires this entry. Without it, ServiceLoader returns empty iterator silently in native image — the most dangerous native image failure mode per the spec itself.

**Gap severity: HIGH.** The reflection config covers only a fraction of required entries. TPipe's actual implementation classes (Manifold, Junction, DistributionGrid, all P2P types) are absent.

### Category G: Bootstrap Plan Items (100% Missing)

`graalvm-abi-bootstrap-plan.md` §11 implementation checklist:

| Item | Status |
|------|--------|
| Create `TPipeBootstrap.kt` (real @CEntryPoint) | ❌ Not done (only `.bak` stub) |
| Create `TPipeHandleBridge.kt` (@CEntryPoint for handles) | ❌ Not done |
| Create `TPipeCapabilitiesBridge.kt` (@CEntryPoint for capabilities) | ❌ Not done |
| Configure native image Gradle plugin | ❌ Not done |
| Symbol name sanitization (`@CEntryPoint(name = "...")`) | ❌ Not done |
| Verify symbols in built binary (`nm -D`) | ❌ Not done |
| Annotation Retention Audit (§9) | ❌ Not done |
| `resource-config.json` META-INF/services entry (§10.2) | ❌ Not done |
| ServiceLoader startup verification (§10.3) | ❌ Not done |

**Gap severity: HIGH.** The bootstrap plan is the execution roadmap and every item is TODO.

---

## Part IV: What Was Done Correctly

1. **Spec suite is excellent.** 12 comprehensive spec documents covering every surface of the ABI. The spec is the strongest part of this work.

2. **`LibraryState.kt` and `TPipeResult.kt`** are complete, correct implementations matching the spec exactly. These are the only fully-implemented Kotlin types.

3. **`TpipeRuntime.kt`** has a correct CAS-based state machine, executor, and registry reference pattern. The structural skeleton is sound — it just references a non-existent `TPipeHandleRegistry`.

4. **`TPipeBridge.kt`** has useful context/history/lorebook operations via reflection. While not handle-based, these operations demonstrate how to interact with TPipe objects from Java/native code. They could form the foundation for handle bindings.

5. **Gap analysis** is thorough and actionable. The 12-gap analysis in `graalvm-abi-gap-analysis-summary.md` documents every decision with rationale, including items explicitly marked NOT IN ABI SPEC (Trace system, ContextBank).

6. **DistributionGrid models** (`DistributionGridModels.kt`, `DistributionGridDurabilityModels.kt`) exist in the main TPipe source tree and correctly implement the spec's user-facing types — TaskOutcomeKind, TaskStatusKind, DirectiveKind, FailureKind, NodeRoleKind, DistributionGridOutcome, TaskProgress, P2PAgentListing. The spec's C struct definitions for the DG envelope are fully matched by the Kotlin data classes.

7. **`@Retention(RUNTIME)` fixes** were applied to all three DSL markers (`@DistributionGridDslMarker`, `@ManifoldDslMarker`, `@JunctionDslMarker`) — confirmed in the audit results.

---

## Part V: Completeness Assessment

### By Spec Section

| Spec Area | Spec Lines | Impl Lines | Completeness |
|-----------|-----------|------------|--------------|
| Core infrastructure (state, result, handles) | ~1,600 | ~200 | ~12% |
| Pipe API | ~1,250 | 0 | 0% |
| Pipeline API (all 7 containers) | ~1,680 | 0 | 0% |
| Handle type bindings | ~1,000 | 0 | 0% |
| Collection handles | ~420 | 0 | 0% |
| PCP / FunctionRegistry | ~500 | partial (bridge) | ~20% |
| P2P types | ~300 | partial (spec) | ~0% |
| Reflection config | ~440 | ~100 (incomplete) | ~23% |
| Bootstrap plan | ~470 | 0 | 0% |
| **TOTAL** | **~8,260** | **~1,074** | **~13%** |

### By Function Category

| Category | Spec'd Functions | Implemented | Correct |
|----------|-----------------|-------------|---------|
| Bootstrap C entry points | 10 | 8 stubs | 0 correct |
| Handle lifecycle (addRef/release/etc) | 4 | 4 stubs | 0 correct |
| Handle type operations | ~200 | 0 | 0% |
| Pipe API | ~100 | 0 | 0% |
| Pipeline/Container API | ~300 | 0 | 0% |
| PCP API | ~50 | partial (bridge) | ~20% |
| Context/Memory API | ~80 | partial (bridge) | ~25% |

---

## Part VI: Key Findings

### Finding 1: Bootstrap is entirely stubbed
All 8 @CEntryPoint functions in `TPipeBootstrap.kt.bak` return hardcoded values (0 or -1) without performing any real operations. `TPipe_init()` returns TPIPE_OK without initializing the runtime. `TPipe_Handle_getRefCount()` returns -1 (invalid) for every handle.

### Finding 2: Handle table referenced but non-existent
`TpipeRuntime.kt` references `TPipeHandleRegistry.getInstance()` which has no source file. The entire handle lifecycle system (addRef, release, getRefCount, isValid) is built around a registry that doesn't exist.

### Finding 3: reflect-config.json incomplete
Only 10 classes registered. The spec requires P2PDescriptor, P2PTransport, P2PSkills, all PCP option classes, all container types, and more. The ServiceLoader resource-config entry is missing entirely.

### Finding 4: No Gradle native image configuration
The bootstrap plan specifies a `nativeImage { }` Gradle block with `mainClass = "TPipe.Native.TPipeBootstrap"`, `--enable-https`, and `metadataRepository { enabled = true }`. No such configuration exists in any `build.gradle.kts`.

### Finding 5: `TPipeBootstrap.kt.bak` — the .bak suffix
The bootstrap file is named with `.bak` extension, suggesting it's a backup. The real bootstrap (`TPipeBootstrap.kt`) doesn't exist. This is the entry point that GraalVM needs — the `.bak` file would not be compiled.

### Finding 6: TPipeBridge methods are static helpers, not handle wrappers
`TPipeBridge` operations (`createContextWindow()`, `addContentToContext()`, etc.) are static methods accepting Kotlin objects as parameters. They're not wrapped as handle operations. They cannot be called from C entry points because the C side doesn't have Kotlin object references.

### Finding 7: ConverseHistory field name mismatch
`reflection-config.json` registers `history` field on `ConverseHistory`, but `TPipeBridge.kt` accesses `.history` via reflection. Need to verify the actual field name in `ConverseHistory.kt` — if it's `converseDataList` or similar, the reflection config entry is wrong.

---

## Part VII: Recommendations

### Priority 1 (Critical — Required for Native Image to Boot)
1. **Create `TPipeHandleRegistry.kt`** — the handle table is the foundation of everything
2. **Create working `TPipeBootstrap.kt`** (rename from `.bak`) with real `@CEntryPoint` implementations that call `TpipeRuntime.initializeBlocking()` / `shutdownBlocking()`
3. **Add `resource-config.json`** with `{ "pattern": "META-INF/services/*" }` entry
4. **Add ServiceLoader verification** in `TPipe_init()` per bootstrap plan §10.3

### Priority 2 (High — Required for Basic Pipe Operations)
5. **Implement `TPipe_ContentHandle`** binding (MultimodalContent) — the most fundamental handle type
6. **Implement `TPipe_ContextHandle`** binding (ContextWindow) — leverages existing `TPipeBridge` reflection code
7. **Implement core Pipe API** — create, setProvider, setModel, execute, getTokenUsage

### Priority 3 (Medium — Container Layer)
8. **Implement container handle wrappers** — Pipeline first, then all 6 remaining containers
9. **Complete `reflection-config.json`** — add P2P types, PCP option classes, all container types

### Priority 4 (Lower — Spec Refinement)
10. **Add `graalvm-abi-pcp-api.md`** — the pipe-api.md spec has a section for PCP but no dedicated spec file
11. **Add `graalvm-abi-p2p-api.md`** — overview mentions it but it doesn't exist as a separate spec document
12. **Create `graalvm-abi-handle-types-impl.md`** — implementation guide matching handle types to actual Kotlin source files

---

## Summary

The ABI specification suite is **professionally crafted and comprehensive**. Every design decision is documented, every function is specified, every gap is identified and resolved.

The implementation is a **minimal scaffolding layer** — stub @CEntryPoint methods, a state machine referencing a non-existent handle registry, a bridge class with reflection-based context operations, and a partial reflection config.

The work as it stands cannot produce a working native image. The spec defines everything; the code implements perhaps 10-15% of the required functionality, and most of that is structural scaffolding rather than working code.

**Recommendation:** The implementation should be driven by the spec's implementation checklist in `graalvm-abi-bootstrap-plan.md` §11, working top-down from the bootstrap layer through handles to pipes to containers. The existing `TPipeBridge.kt` context operations can serve as a reference implementation for how to interact with TPipe objects from the native boundary.