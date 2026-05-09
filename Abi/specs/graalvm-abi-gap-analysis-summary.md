# TPipe GraalVM ABI - Gap Analysis & Specification Sheet
## Complete Integration of All Gap Analysis Decisions

**Version:** 0.2.0-draft
**Created:** 2026-05-07
**Status:** Draft - Gap Analysis Complete

---

## Executive Summary

This document consolidates all gap analysis findings into a unified ABI specification sheet. 11 gap areas were analyzed against the TPipe codebase, resulting in concrete decisions on what belongs in the ABI spec vs what remains internal.

| Gap Area | Decision | Status |
|----------|----------|--------|
| GAP 1-3: Trace System | NOT ABI spec'd - internal only | ✅ RESOLVED |
| GAP 4: DistributionGrid | Partial - user-facing types spec'd, wire protocol internal | ✅ RESOLVED (pending user decision on Envelope) |
| GAP 5: Provider Config | Configuration classes + builder APIs spec'd; credentials internal | ✅ RESOLVED |
| GAP 6: DSL Builders | Full DSL surface spec'd; internal config classes not spec'd | ✅ RESOLVED |
| GAP 7: PCP/FunctionRegistry | Full public API spec'd | ✅ RESOLVED |
| GAP 8: Memory System | ContextWindow, LoreBook, ConverseHistory spec'd; ContextBank internal | ✅ RESOLVED |
| GAP 9: LoreBook | Full data class surface spec'd; internal combine logic not spec'd | ✅ RESOLVED |
| GAP 10: Spec Gaps | Phantom references identified; implementation not yet built | ⚠️ OPEN |
| GAP 11: P2P System | Core P2P types spec'd; hosted registry internal | ✅ RESOLVED |
| GAP 12: ServiceLoader / META-INF/services | resource-config.json entries required for native image; startup verification needed | ✅ RESOLVED |

---

## Part 1: Items NOT in ABI Spec (Internal Only)

### 1.1 Trace System (GAP 1-3) - COMPLETE

**Decision:** Trace system is 100% internal. No trace types appear in the ABI spec.

| Internal Type | Classification | Rationale |
|--------------|----------------|-----------|
| `TraceEventType` (83 values) | INTERNAL | Classification vocabulary for tracing; wrappers see only formatted strings from `getTraceReport()` |
| `TracePhase` | INTERNAL | Internal phase tagging; not exposed to wrappers |
| `TraceVisualizer` | INTERNAL | Developer tooling for trace analysis |
| `FailureAnalysis` | INTERNAL | Debug/diagnostic tool |
| `TracingBuilder` | INTERNAL | Trace configuration builder |
| `TraceStreamMerger` | INTERNAL | Multi-stream trace merging |

**Rationale:** Wrappers consume `getTraceReport()` which returns formatted strings. The raw trace type system is an internal implementation detail that could change without affecting the ABI surface.

---

### 1.2 ContextBank and Dictionary (GAP 8) - COMPLETE

**Decision:** These singleton managers are internal; they are not part of the public ABI.

| Internal Type | Classification | Rationale |
|--------------|----------------|-----------|
| `ContextBank` (object) | INTERNAL | Global singleton managing context windows; no user-facing constructor |
| `Dictionary` (object) | INTERNAL | Token counting utility; called internally by ContextWindow |

**Rationale:** Users interact with `ContextWindow` handles, not the bank manager. The internal singleton pattern is an implementation detail.

---

## Part 2: User-Facing ABI Surface

### 2.1 DistributionGrid (GAP 4) - PARTIAL (pending user decision)

**Decision:** User-facing types are ABI spec'd. Wire protocol types are internal.

#### ABI_SPEC: User-Facing Types

| Type | File | Notes |
|------|------|-------|
| `TaskOutcomeKind` (enum) | DistributionGridModels.kt | User consumes outcomes |
| `TaskStatusKind` (enum) | DistributionGridModels.kt | Task lifecycle status |
| `DirectiveKind` (enum) | DistributionGridModels.kt | 9 directive kinds (only 3 in current spec) |
| `FailureKind` (enum) | DistributionGridModels.kt | Failure classification |
| `NodeRoleKind` (enum) | DistributionGridModels.kt | Node roles |
| `DistributionGridOutcome` | DistributionGridModels.kt | Result of grid operations |
| `DistributionGridFailure` | DistributionGridModels.kt | Failure with context |
| `TaskProgress` | DistributionGridModels.kt | Progress tracking |
| `P2PAgentListing` | DistributionGridModels.kt | Agent discovery |
| `JoinResult` | DistributionGridModels.kt | Node join result |
| `P2PConcurrencyMode` | (policy struct) | Concurrency strategy |
| `RetryPolicy` | (policy struct) | Retry configuration |
| `TimeoutPolicy` | (policy struct) | Timeout configuration |

#### INTERNAL: Wire Protocol Types

| Type | Classification | Rationale |
|------|----------------|-----------|
| `ProtocolVersion` | INTERNAL | Wire protocol versioning; not user-facing |
| `SessionRef` | INTERNAL | Session identification for P2P communication |
| `RpcMessageType` | INTERNAL | RPC message types for internal messaging |
| `NodeMetadata` | INTERNAL | Node discovery metadata |
| `BindingKind` | INTERNAL | Transport binding type |
| `P2PInterface` | INTERNAL | P2P communication interface |

#### DECISION_NEEDED: DistributionGridEnvelope

The `DistributionGridEnvelope` design requires user input:

**Option A:** Full C struct exposure (ABI spec includes complete envelope structure)
**Option B:** Opaque handle with accessor functions (ABI-stable, implementation-hidden)
**Option C:** Treat as entirely internal (not exposed across ABI boundary)

**User must choose one of these approaches.**

---

### 2.2 Provider Configuration Layer (GAP 5) - COMPLETE

**Decision:** Provider configuration classes and builder APIs are ABI spec'd. Credentials are internal.

#### ABI_SPEC: BedrockConfiguration

```kotlin
data class BedrockConfiguration(
    var region: String,                    // AWS region
    var model: String,                     // Bedrock model identifier
    var pipeCount: Int = 2,                // Number of pipes in Manifold
    var inferenceProfile: String = "",      // Inference profile ARN
    var useConverseApi: Boolean = true     // Use Converse API vs Invoke API
    // Credentials fields: INTERNAL
    // var accessKey: String? = null
    // var secretKey: String? = null
    // var sessionToken: String? = null
    // var profileName: String? = null
)
```

#### ABI_SPEC: OllamaConfiguration

```kotlin
data class OllamaConfiguration(
    val model: String,                     // Ollama model name
    val pipeCount: Int = 2,                // Number of pipes in Manifold
    val host: String = "localhost",         // Ollama server host
    val port: Int = 11434,                 // Ollama server port
    val timeout: Long = 30000,             // Connection timeout (ms)
    val useHttps: Boolean = false          // HTTPS vs HTTP
)
```

#### ABI_SPEC: ManifoldDefaults (Factory Methods)

```kotlin
object ManifoldDefaults {
    fun withBedrock(configuration: BedrockConfiguration): Manifold
    fun withOllama(configuration: OllamaConfiguration): Manifold
    fun getAvailableProviders(): List<ProviderName>
}
```

#### ABI_SPEC: BedrockPipe Builder API

```kotlin
fun setRegion(region: String): BedrockPipe
fun setModel(model: String): BedrockPipe
fun setReadTimeout(timeoutSeconds: Long): BedrockPipe
fun enableStreaming(enabled: Boolean): BedrockPipe
fun setTools(tools: List<JsonObject>): BedrockPipe
fun setToolChoice(toolChoice: String): BedrockPipe
fun enableCaching(control: Boolean): BedrockPipe
fun setReasoning(tokens: Int): BedrockPipe
fun setReasoning(reasoning: String): BedrockPipe
fun setSystemPrompt(prompt: String): BedrockPipe
fun setJsonInput(schema: Any): BedrockPipe
```

#### ABI_SPEC: OllamaPipe Builder API

```kotlin
fun setModel(model: String): Pipe
fun setIP(ip: String): Pipe
fun setPort(port: Int): Pipe
fun setMinP(minP: Float): Pipe
fun setTypicalP(typicalP: Float): Pipe
fun setMirostat(mode: Int, eta: Float? = null, tau: Float? = null): Pipe
fun setRepeatLastN(n: Int): Pipe
fun setPresencePenalty(penalty: Float): Pipe
fun setFrequencyPenalty(penalty: Float): Pipe
fun setRepeatPenalty(penalty: Float): Pipe
fun setNumCtx(ctx: Int): Pipe
fun setNumBatch(batch: Int): Pipe
fun setNumThread(thread: Int): Pipe
fun setGpuSettings(numGpu: Int, mainGpu: Int): OllamaPipe
fun setLowVram(enabled: Boolean): Pipe
fun setUseMmap(enabled: Boolean): Pipe
fun setUseMlock(enabled: Boolean): Pipe
fun setJsonInput(schema: Any): Pipe
```

#### INTERNAL: validate() Methods

All `validate()` methods on configuration classes are implementation details and not part of the ABI surface.

---

### 2.3 DSL Builders (GAP 6) - COMPLETE

**Decision:** Full DSL public API is ABI spec'd. Internal configuration classes and state machine stages are not spec'd.

#### ABI_SPEC: ManifoldDsl Entry Points

```kotlin
fun manifold(block: ManifoldBuilder<ManifoldStage.Initial>.() -> Unit): Manifold
fun manifoldBuilder(): ManifoldBuilder<ManifoldStage.Initial>
fun ManifoldBuilder<ManifoldStage.Ready>.build(): Manifold
fun ManifoldBuilder<ManifoldStage.Ready>.buildSuspend(): Manifold
```

#### ABI_SPEC: ManifoldBuilder Public Methods

```kotlin
fun manager(block: ManagerDsl.() -> Unit): ManifoldBuilder<ManifoldStage.HasManager>
fun worker(agentName: String, block: WorkerDsl.() -> Unit): ManifoldBuilder<ManifoldStage.Ready>
fun history(block: HistoryDsl.() -> Unit): ManifoldBuilder<S>
fun validation(block: ValidationDsl.() -> Unit): ManifoldBuilder<S>
fun initFunction(block: InitFunctionDsl.() -> Unit): ManifoldBuilder<S>
fun concurrencyMode(mode: P2PConcurrencyMode): ManifoldBuilder<S>
fun killSwitch(inputTokenLimit: Int?, outputTokenLimit: Int?, onTripped: ...): ManifoldBuilder<S>
fun maxIterations(iterations: Int): ManifoldBuilder<S>
fun summaryPipeline(block: SummaryPipelineDsl.() -> Unit): ManifoldBuilder<S>
fun tracing(block: TraceConfig.() -> Unit): ManifoldBuilder<S>
```

#### ABI_SPEC: Helper DSL Classes

| DSL Class | Purpose |
|-----------|---------|
| `ManagerDsl` | Configure manager pipeline, descriptor, requirements, agent pipe names |
| `WorkerDsl` | Configure worker pipeline, descriptor, requirements, description, skills |
| `HistoryDsl` | Configure context window, truncation, token budget |
| `ValidationDsl` | Configure validator, failure handler, transformer callbacks |
| `InitFunctionDsl` | Configure manifold initialization function |
| `SummaryPipelineDsl` | Configure summary pipeline |

#### INTERNAL (Not ABI Spec'd)

- `ManifoldStage` sealed class (state machine stages: Initial, HasManager, HasWorkers, Ready)
- `ValidationConfiguration` data class
- `InitFunctionConfiguration` data class
- `HistoryConfiguration` data class
- `SummaryPipelineConfiguration` data class
- All internal builder state management

---

### 2.4 PCP/FunctionRegistry (GAP 7) - COMPLETE

**Decision:** Full PCP public API is ABI spec'd.

#### ABI_SPEC: FunctionRegistry

```kotlin
object FunctionRegistry {
    fun registerFunction(name: String, function: KFunction<*>): FunctionSignature
    fun <T> registerLambda(name: String, lambda: T, signature: FunctionSignature): FunctionSignature
    fun getFunction(name: String): NativeFunction?
    fun getFunctionNames(): Set<String>
    fun removeFunction(name: String): Boolean
    fun clear()
}
```

#### ABI_SPEC: PcpContext

```kotlin
data class PcpContext {
    var transport: Transport
    fun addStdioOption(option: StdioContextOptions)
    fun addTPipeOption(option: TPipeContextOptions)
    fun addHttpOption(option: HttpContextOptions)
    // Directory/file permission lists
}
```

#### ABI_SPEC: PcpExecutionDispatcher

```kotlin
class PcpExecutionDispatcher {
    suspend fun executeRequest(request: PcPRequest): PcpRequestResult
    suspend fun executeRequests(requests: List<PcPRequest>): PcpExecutionResult
}
```

#### ABI_SPEC: PcPRequest

```kotlin
data class PcPRequest(
    val stdioContextOptions: StdioContextOptions = StdioContextOptions(),
    val tPipeContextOptions: TPipeContextOptions = TPipeContextOptions(),
    val httpContextOptions: HttpContextOptions = HttpContextOptions(),
    val pythonContextOptions: PythonContext = PythonContext(),
    val argumentsOrFunctionParams: List<String> = mutableListOf()
)
```

#### ABI_SPEC: Context Option Classes

| Class | Fields |
|-------|--------|
| `StdioContextOptions` | command, args, permissions, description, executionMode, sessionId, bufferId, workingDirectory, environmentVariables, timeoutMs, keepSessionAlive, bufferPersistence, maxBufferSize |
| `TPipeContextOptions` | functionName, description, params (Map<String, Triple<ParamType, String, List<String>>>) |
| `HttpContextOptions` | baseUrl, endpoint, method, requestBody, allowedMethods, headers, authType, authCredentials, allowedHosts, followRedirects, timeoutMs, permissions, description |
| `PythonContext` | availablePackages, pythonVersion, pythonPath, workingDirectory, environmentVariables, timeoutMs, captureOutput, permissions |

#### ABI_SPEC: PCP Enums

| Enum | Values |
|------|--------|
| `Transport` | Auto, Stdio, Tpipe, Http, Python, Unknown |
| `Permissions` | Read, Write, Delete, Execute |
| `ParamType` | String, Int, Bool, Float, Enum, List, Map, Object, Any |
| `StdioExecutionMode` | ONE_SHOT, INTERACTIVE, CONNECT, BUFFER_REPLAY |

---

### 2.5 Memory System (GAP 8) - COMPLETE

**Decision:** User-facing memory types are ABI spec'd. Internal managers are not.

#### ABI_SPEC: ContextWindow

```kotlin
data class ContextWindow {
    var loreBookKeys: MutableMap<String, LoreBook>
    var contextElements: MutableList<String>
    var converseHistory: ConverseHistory
    var version: Long
    var metaData: MutableMap<Any, Any>

    // Public methods
    fun findMatchingLoreBookKeys(text: String): List<String>
    fun countAndSortKeyHits(text: String): List<Pair<String, Int>>
    fun selectLoreBookContext(text: String, maxTokens: Int): String
    fun addLoreBookEntry(key: String, loreBook: LoreBook)
    fun findLoreBookEntry(key: String): LoreBook?
    fun merge(other: ContextWindow): ContextWindow
    fun truncateContextElements(settings: TruncationSettings, maxTokens: Int)
    fun truncateConverseHistory(settings: TruncationSettings, maxTokens: Int)
    fun isEmpty(): Boolean
    fun clear()
    fun cleanLorebook()
}
```

#### ABI_SPEC: LoreBook

```kotlin
data class LoreBook(
    var key: String,
    var value: String,
    var weight: Float = 1.0f,
    var linkedKeys: MutableList<String> = mutableListOf(),
    var aliasKeys: MutableList<String> = mutableListOf(),
    var requiredKeys: MutableList<String> = mutableListOf()
) {
    fun combineValue(other: LoreBook): LoreBook
    fun toMap(): Map<String, Any>
}
```

#### ABI_SPEC: ConverseHistory

```kotlin
data class ConverseHistory {
    var converseDataList: MutableList<ConverseData>
    var converseMap: MutableMap<String, ConverseData>

    fun add(role: ConverseRole, content: String)
    fun add(converseData: ConverseData)
    fun getRoleContentPairs(): List<Pair<ConverseRole, String>>
    fun getMessages(): List<String>
    fun clear()
    fun size(): Int
}
```

#### ABI_SPEC: Supporting Types

| Type | ABI Status | Notes |
|------|-----------|-------|
| `ConverseData` | ABI_SPEC | Single conversation turn |
| `ConverseRole` (enum) | ABI_SPEC | developer, system, user, agent, assistant |
| `MiniBank` | ABI_SPEC | Container for multiple context windows |
| `TruncationSettings` | ABI_SPEC | Token counting configuration |
| `ContextWindowSettings` (enum) | ABI_SPEC | TruncateTop, TruncateBottom, TruncateMiddle |

#### INTERNAL

- `ContextBank` (object) - singleton manager
- `Dictionary` (object) - token counting utility

---

### 2.6 P2P System (GAP 11) - COMPLETE

**Decision:** Core P2P types are ABI spec'd. Hosted registry implementation is internal.

#### ABI_SPEC: P2PDescriptor

```kotlin
data class P2PDescriptor(
    var agentName: String,
    var agentDescription: String,
    var transport: P2PTransport,
    var requiresAuth: Boolean,
    var usesConverse: Boolean,
    var allowsAgentDuplication: Boolean,
    var allowsCustomContext: Boolean,
    var allowsCustomAgentJson: Boolean,
    var recordsInteractionContext: Boolean,
    var recordsPromptContent: Boolean,
    var allowsExternalContext: Boolean,
    var contextProtocol: ContextProtocol,
    var skills: P2PSkills,
    var requirements: P2PRequirements
)
```

#### ABI_SPEC: P2PTransport

```kotlin
data class P2PTransport(
    var transportMethod: Transport,
    var transportAddress: String,
    var transportAuthBody: String
)
```

#### ABI_SPEC: P2PSkills

```kotlin
data class P2PSkills(
    var skills: MutableList<P2PSkill>
)

data class P2PSkill(
    var skillName: String,
    var skillDescription: String
)
```

#### ABI_SPEC: P2PRequirements

```kotlin
data class P2PRequirements(
    var minMemory: Long,
    var maxMemory: Long,
    var requiresNetwork: Boolean,
    var requiresFileSystem: Boolean,
    var requiresGPU: Boolean,
    var customRequirements: MutableMap<String, String>
)
```

#### ABI_SPEC: AgentRequest

```kotlin
data class AgentRequest(
    var requestId: String,
    var requestingAgentName: String,
    var targetAgentName: String,
    var method: String,
    var arguments: List<String>,
    var authToken: String?,
    var timestamp: Long,
    var priority: Int
)
```

#### ABI_SPEC: P2PRegistry

```kotlin
object P2PRegistry {
    suspend fun register(descriptor: P2PDescriptor): RegistrationResult
    suspend fun unregister(agentName: String): Boolean
    suspend fun getAgent(agentName: String): P2PDescriptor?
    suspend fun listAgents(filter: P2PRequirements?): List<P2PAgentListing>
    suspend fun listAllAgents(): List<P2PAgentListing>
    suspend fun refreshAgent(agentName: String): Boolean
    fun getRegisteredAgents(): Set<String>
    fun isRegistered(agentName: String): Boolean
}
```

#### ABI_SPEC: KillSwitch

```kotlin
data class KillSwitch(
    var inputTokenLimit: Int?,
    var outputTokenLimit: Int?,
    var onTripped: (KillSwitchContext) -> Unit
)
```

#### ABI_SPEC: P2PConcurrencyMode (enum)

```kotlin
enum class P2PConcurrencyMode {
    SHARED,
    ISOLATED,
    AUTO
}
```

#### ABI_SPEC: P2PResponse Types

```kotlin
data class P2PResponse(
    var responseId: String,
    var requestId: String,
    var content: String?,
    var error: String?,
    var metadata: MutableMap<String, String>
)

data class P2PRejected(
    var rejectionId: String,
    var requestId: String,
    var reason: String,
    var agentName: String
)
```

#### INTERNAL

- `P2PHost` - Stdio host implementation
- `P2PHostedRegistry` - Large hosted registry implementation
- `P2PHostedRegistryModels` - Internal models
- `P2PHostedRegistryTools` - Internal tools
- `P2PInterface` - Communication interface (not constructible by users)

---

## Part 3: Open Implementation Gaps (GAP 10)

### 3.1 Phantom References - Items Spec'd But Not Implemented

The following items are defined in the ABI spec but have no corresponding Kotlin implementation:

| Spec Item | Status | Action Required |
|-----------|--------|-----------------|
| `TPipe_getCapabilities()` | NOT IMPLEMENTED | Must be implemented in native image layer |
| `TPipe_getVersion()` | NOT IMPLEMENTED | Must be implemented in native image layer |
| `TPipe_isInitialized()` | NOT IMPLEMENTED | Must be implemented in native image layer |
| `TPipe_ListHandle` type | NOT IMPLEMENTED | Must be implemented as handle abstraction |
| `TPipe_MapHandle` type | NOT IMPLEMENTED | Must be implemented as handle abstraction |
| `TPipe_Handle_addRef()` | NOT IMPLEMENTED | Must be implemented in native image layer |
| `TPipe_Handle_release()` | NOT IMPLEMENTED | Must be implemented in native image layer |
| `TPipe_Handle_getRefCount()` | NOT IMPLEMENTED | Must be implemented in native image layer |
| `TPipe_Handle_isValid()` | NOT IMPLEMENTED | Must be implemented in native image layer |

**Rationale:** The ABI spec was written ahead of implementation. These are planned native C entry points that will be generated via GraalVM Native Image compilation, but the Kotlin source does not currently contain these specific functions (they would be generated as part of the native image build process).

---

### 3.2 Missing from Spec - Items Implemented But Not Spec'd

The following items exist in the code but are not yet in the ABI spec:

| Code Item | Notes |
|-----------|-------|
| `DistributionGridEnvelope` | Pending user decision on approach (A/B/C) |
| `TPipe_DistributionGridDurableStore` interface | Interface exists in code but methods not spec'd |
| `TPipe_DistributionGridDurableState` struct | Struct exists in code but not spec'd as C interface |
| `DistributionGridEnvelopeHook` callback | Not defined in spec |
| `DistributionGridOutcomeHook` callback | Not defined in spec |

---

## Part 3.5: GAP 12 — ServiceLoader / META-INF/services (RESOLVED)

### Finding

When TPipe is compiled as a GraalVM native image, `ServiceLoader.load(SomeInterface.class)` returns an **empty iterator** if the `META-INF/services/*` provider files are not registered in `resource-config.json` — **no exception is thrown**. This is the most dangerous native image failure mode: the application silently proceeds with zero service providers loaded.

### Root Cause

GraalVM Native Image has no classpath at runtime. ServiceLoader relies on the JVM's `META-INF/services/` classpath scanning, which does not exist in a native binary. Without explicit registration, the native image cannot locate service provider files.

### Resolution

Two-part fix:

1. **resource-config.json entry** in `TPipe/src/main/resources/META-INF/native-image/resource-config.json`:
   ```json
   { "resources": [{ "pattern": "META-INF/services/*" }] }
   ```

2. **Startup verification** in `TPipeBootstrap.TPipe_init()`:
   ```kotlin
   val testLoad = ServiceLoader.load(com.TTT.Pipeline.Pipe::class.java).iterator()
   if (!testLoad.hasNext()) {
       throw IllegalStateException(
           "TPipe native image: no Pipe service providers found. " +
           "Ensure META-INF/services/* entries are registered in resource-config.json."
       )
   }
   ```

### Affected Service Interfaces

| Service Interface | Used For |
|---|---|
| `Pipe` | Pipeline implementations |
| `Connector` | Connector implementations |
| `Splitter` | Splitter implementations |
| `Manifold` | Manifold implementations |
| Agent implementations | P2P agent discovery |

### Spec References

- `graalvm-abi-reflection-config.md` §8–§9 — Full ServiceLoader configuration spec
- `graalvm-abi-bootstrap-plan.md` §10 — ServiceLoader startup verification implementation

---

---

## Part 4: Enums Status

### 4.1 Complete Enum Mapping (ABI Spec'd)

| Kotlin Enum | C Enum Name | Values |
|-------------|-------------|--------|
| `Transport` | `TPipe_Transport` | Auto, Stdio, Tpipe, Http, Python, Unknown |
| `Permissions` | `TPipe_Permissions` | Read, Write, Delete, Execute |
| `ParamType` | `TPipe_ParamType` | String, Int, Bool, Float, Enum, List, Map, Object, Any |
| `StdioExecutionMode` | `TPipe_StdioExecutionMode` | ONE_SHOT, INTERACTIVE, CONNECT, BUFFER_REPLAY |
| `ConverseRole` | `TPipe_ConverseRole` | DEVELOPER, SYSTEM, USER, AGENT, ASSISTANT |
| `ContextWindowSettings` | `TPipe_ContextWindowSettings` | TRUNCATE_TOP, TRUNCATE_BOTTOM, TRUNCATE_MIDDLE |
| `ProviderName` | `TPipe_ProviderName` | AWS, NAI, GEMINI, GPT, OLLAMA, OPENROUTER |
| `PromptMode` | `TPipe_PromptMode` | SINGLE, CHAT, INTERNAL_CONTEXT |
| `SummaryMode` | `TPipe_SummaryMode` | APPEND, REGENERATE |
| `P2PConcurrencyMode` | (part of Manifold DSL) | SHARED, ISOLATED, AUTO |
| `TaskOutcomeKind` | (DistributionGrid) | See DistributionGridModels.kt |
| `TaskStatusKind` | (DistributionGrid) | See DistributionGridModels.kt |
| `DirectiveKind` | (DistributionGrid) | See DistributionGridModels.kt (9 values) |
| `FailureKind` | (DistributionGrid) | See DistributionGridModels.kt |
| `NodeRoleKind` | (DistributionGrid) | See DistributionGridModels.kt |

---

## Part 5: Handle Types (From ABI Spec)

### 5.1 Complete Handle Type List

| Handle Type | Maps To | Status |
|-------------|---------|--------|
| `TPipe_Handle` | Base handle (uint64_t) | Spec'd |
| `TPipe_ContentHandle` | MultimodalContent | Impl exists |
| `TPipe_BinaryHandle` | BinaryContent | Impl exists |
| `TPipe_ContextHandle` | ContextWindow | Impl exists |
| `TPipe_MiniBankHandle` | MiniBank | Impl exists |
| `TPipe_ListHandle` | Generic list | NOT IMPLEMENTED |
| `TPipe_MapHandle` | Generic map | NOT IMPLEMENTED |
| `TPipe_PipeSettingsHandle` | PipeSettings | Impl exists |
| `TPipe_ConverseHistoryHandle` | ConverseHistory | Impl exists |
| `TPipe_TokenBudgetHandle` | TokenBudgetSettings | Impl exists |
| `TPipe_LoreBookHandle` | LoreBook | Impl exists |
| `TPipe_ErrorHandle` | PipeError | Impl exists |
| `TPipe_PCPHandle` | PCP module root | Impl exists |
| `TPipe_StdioContextHandle` | StdioContextOptions | Impl exists |
| `TPipe_HttpContextHandle` | HttpContextOptions | Impl exists |
| `TPipe_P2PTransportHandle` | P2PTransport | Impl exists |
| `TPipe_P2PDescriptorHandle` | P2PDescriptor | Impl exists |
| `TPipe_P2PRequirementsHandle` | P2PRequirements | Impl exists |
| `TPipe_TraceConfigHandle` | TraceConfig | Impl exists |
| `TPipe_PcpExecutionResultHandle` | PCP execution result | Impl exists |
| `TPipe_P2PInterfaceHandle` | P2PInterface (not constructible) | Impl exists |

---

## Appendix A: Key Files Reference

### ABI Spec Files
- `/home/cage/Desktop/Workspaces/TPipe/TPipe/Abi/specs/graalvm-abi-overview.md`
- `/home/cage/Desktop/Workspaces/TPipe/TPipe/Abi/specs/graalvm-abi-initialization.md`
- `/home/cage/Desktop/Workspaces/TPipe/TPipe/Abi/specs/graalvm-abi-core-types.md`
- `/home/cage/Desktop/Workspaces/TPipe/TPipe/Abi/specs/graalvm-abi-pipeline-api.md`
- `/home/cage/Desktop/Workspaces/TPipe/TPipe/Abi/specs/graalvm-abi-pipe-api.md`

### Key Source Files
- `DistributionGrid.kt` - Main DistributionGrid class
- `DistributionGridModels.kt` - Outcome, Directive, Failure types
- `DistributionGridDurabilityModels.kt` - DurableStore, DurableState
- `DistributionGridProtocolModels.kt` - Wire protocol types (INTERNAL)
- `ProviderConfiguration.kt` - BedrockConfiguration, OllamaConfiguration
- `ManifoldDefaults.kt` - Factory methods
- `ManifoldDsl.kt` - DSL builders
- `Pcp.kt` - PCP context and options
- `FunctionRegistry.kt` - Function binding
- `ContextWindow.kt` - Memory types
- `P2PDescriptor.kt` - P2P types
- `P2PRegistry.kt` - Registry

---

## Appendix B: Decisions Pending User Input

1. **DistributionGridEnvelope approach** - Choose A (full C struct), B (opaque handle), or C (internal only)
2. **Implementation of phantom references** - GAP 10 items need implementation planning

---

*Document status: Gap analysis complete. Some items require user decisions before finalization.*