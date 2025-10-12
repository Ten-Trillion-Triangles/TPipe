# TPipe Developer Guide

- [Overview](#overview)
- [Repository Layout](#repository-layout)
- [Core Concepts: Pipe & Pipeline](#core-concepts-pipe--pipeline)
- [Context System](#context-system)
- [Pipe Context Protocol (PCP)](#pipe-context-protocol-pcp)
- [Pipe-to-Pipe Network](#pipe-to-pipe-network)
- [Container Components](#container-components)
- [Modules & Provider Setup](#modules--provider-setup)
- [Debugging & Trace Workflows](#debugging--trace-workflows)
- [Advanced Feature Catalog](#advanced-feature-catalog)
- [API Quick Reference](#api-quick-reference)

## Overview
TPipe is a Kotlin framework that provides complete, unabstracted access to LLM provider APIs while adding capabilities to models that lack native features. Unlike frameworks that hide complexity behind simplified wrappers, TPipe exposes every parameter, streaming callback, and provider-specific feature (like Bedrock's reasoning tokens and caching) through native method binding. Pipes wrap individual LLM calls with full control, Pipelines connect them in multi-directional networks where execution can flow forward, backward, parallel, or conditionally via `jumpToPipe` and `repeatPipe`, and Containers orchestrate complex multi-agent workflows through Manifold (manager/worker), Splitter (parallel), and Connector (routing) patterns. The framework adds JSON schema enforcement, reasoning workflows, and tool calling to any provider, manages sophisticated context through weighted lorebooks and multi-page windows, enables secure tool execution across multiple transports via PCP (Pipe Context Protocol), and provides comprehensive tracing, streaming, automatic JSON repair, and failure analysis for production deployment. TPipeWriter demonstrates real-world usage with dynamic context selection, chapter management, and interactive shell interfaces for creative writing workflows.
- Pipe Context Protocol for safe tool execution across multiple transports
- Stdio, HTTP, Python, and in-memory tool execution with configurable sandboxing
- Permission-based validation for external system access
- Integration with Model Context Protocol (MCP) for tool discovery

**Multi-Agent Orchestration**
- Manifold: Manager/worker patterns with task progress tracking and P2P dispatch
- Splitter: Parallel pipeline execution with result aggregation
- Connector: Routing and conditional execution based on content analysis
- Peer-to-peer agent networks with service discovery and remote execution

**Production Features**
- Comprehensive tracing with HTML/JSON exports and failure analysis
- Streaming responses with real-time callbacks and progress monitoring
- JSON schema generation and automatic repair for malformed LLM outputs
- Caching, retry mechanisms, and graceful error handling

### Development Workflow
1. **Configure Pipes** - Access full provider APIs with complete parameter control
2. **Build Pipelines** - Create dynamic execution networks with multi-directional flow
3. **Add Context** - Implement sophisticated memory management for your domain
4. **Integrate Tools** - Define PCP tools for secure external system interactions
5. **Orchestrate Agents** - Use containers for complex multi-agent behaviors
6. **Enable Observability** - Add comprehensive tracing and monitoring for production

### Real-World Usage
The companion `TPipeWriter` application demonstrates TPipe's capabilities in a production creative writing assistant, featuring dynamic context selection, chapter management, multi-agent collaboration, and interactive shell interfaces. TPipe's architecture supports use cases from simple chatbots to sophisticated multi-agent systems requiring advanced memory management and complete provider API control.

## Repository Layout
- `src/main/kotlin` – Core engine packages: `Pipe`, `Pipeline`, `Context`, `PipeContextProtocol`, `P2P`, `Debug`, `Util`, and supporting enums/structs.
- `src/test/kotlin` – Unit and integration suites covering context selection, PCP transports (HTTP, Python, stdio), tracing, and JSON repair helpers.
- `TPipe-Bedrock` – AWS Bedrock pipes (`BedrockPipe`, `BedrockMultimodalPipe`, Nova variants) plus CLI helpers and environment bindings.
- `TPipe-Ollama` – Local server integration with request/response models and sampling controls tailored for Ollama.
- `TPipe-MCP` – Bridges Model Context Protocol assets into PCP-compatible tool catalogs.
- `TPipe-Defaults` – Factory utilities that assemble opinionated manifolds and worker pipelines for Bedrock and Ollama deployments.
- `build.gradle.kts` & `settings.gradle.kts` – Kotlin/JVM 24 multi-module build with Ktor server/client, kotlinx serialization, and logging dependencies.


## Core Concepts: Pipe & Pipeline
TPipe standardizes provider interactions through the `Pipe` base class and orchestrates them with `Pipeline`.

### Building a Pipe
```kotlin
import bedrockPipe.BedrockMultimodalPipe
import com.TTT.Pipe.MultimodalContent
import com.TTT.Context.ContextWindow
import kotlinx.serialization.Serializable

@Serializable
data class SummaryOutput(
    val keyFindings: List<String> = emptyList(),
    val citations: List<String> = emptyList()
)

val researchPipe = BedrockMultimodalPipe()
    .setPipeName("Research summary")
    .setModel("anthropic.claude-3-5-sonnet-20240620-v1:0")
    .setSystemPrompt("""
        You are a senior analyst. Return JSON with fields `keyFindings` and `citations`.
    """.trimIndent())
    .setJsonOutput(SummaryOutput())
    .autoInjectContext()
    .setMaxTokens(3000)
    .setTemperature(0.4)
    .applySystemPrompt()

researchPipe.preValidationFunction = { context: ContextWindow, content ->
    // keep only the latest 8 turns from shared history
    context.converseHistory.history.takeLast(8).let { sliced ->
        context.converseHistory.history.clear()
        context.converseHistory.history.addAll(sliced)
    }
    context
}
```
Key pipe capabilities:
- **Prompt management** – `setSystemPrompt`, `setMiddlePrompt`, and `setFooterPrompt` coordinate prompt scaffolding; `copySystemPromptToUserPrompt()` handles models that ignore system slots.
- **Schema enforcement** – `setJsonInput(...)`, `setJsonOutput(...)`, and `requireJsonPromptInjection()` steer models without native JSON guarantees.
- **Context handling** – `pullPipelineContext()`, `useGlobalContext()`, `setPageKey(...)`, and `autoTruncateContext()` orchestrate access to shared context windows and lorebooks.
- **Execution hooks** – assign `preInitFunction`, `preValidationFunction`, `validationFunction`, `transformationFunction`, and `failureFunction` for human-in-loop controls.
- **Multimodal support** – `generateContent` works with `MultimodalContent` containing text plus `BinaryContent` variants (bytes, base64, cloud references).

### Assembling a Pipeline
```kotlin
import com.TTT.Pipeline.Pipeline
import com.TTT.Pipe.MultimodalContent

val pipeline = Pipeline()
    .add(researchPipe)
    .add(
        BedrockMultimodalPipe()
            .setPipeName("Redactor")
            .setModel("meta.llama3-1-8b-instruct-v1:0")
            .setSystemPrompt("Mask personally identifiable information and rephrase for clarity.")
            .setJsonOutput("""{"approved": false, "body": ""}""")
            .requireJsonPromptInjection()
            .applySystemPrompt()
    )
    .enableTracing()

val result = pipeline.execute(
    MultimodalContent(text = "Summarize the attached report", binaryContent = mutableListOf(/* attachments */))
)
```
Pipelines track `inputTokensSpent`/`outputTokensSpent`, expose `jumpToPipe` and `repeatPipe` controls via `MultimodalContent`, and coordinate context propagation. Setting `pipeline.useGlobalContext()` writes merged context back to `ContextBank` so other pipelines or agents can reuse it. Calling `pipeline.getTraceReport(TraceFormat.HTML)` exports the captured execution timeline.

## Context System
TPipe’s memory model combines per-pipe `ContextWindow`s, a global `ContextBank`, and lightweight `MiniBank`s for multi-page sharing.

```kotlin
import com.TTT.Context.ContextWindow
import com.TTT.Context.ContextBank
import com.TTT.Context.LoreBook
import com.TTT.Context.MiniBank
import com.TTT.Enums.ContextWindowSettings
import com.TTT.Pipe.MultimodalContent
import kotlinx.coroutines.runBlocking

val lore = LoreBook().apply {
    key = "npc:captain"
    value = "Captain Reyes commands the orbital platform Aegis."
    weight = 8
    aliasKeys.add("Commander Reyes")
    linkedKeys.add("station:aegis")
}

val sessionContext = ContextWindow().apply {
    contextSize = 32000
    contextWindowTruncation = ContextWindowSettings.TruncateTop
    loreBookKeys[lore.key] = lore
    contextElements.add("Previous duty log: ...")
}

runBlocking {
    ContextBank.emplaceWithMutex("campaign", sessionContext)
}

val branchBank = MiniBank(mutableMapOf("campaign" to sessionContext))

val content = MultimodalContent().apply {
    context = sessionContext
    workspaceContext = branchBank
}
```

Essentials:
- **Lorebook weighting** – `ContextWindow.selectLoreBookContext` scans prompts, applies dependency checks, and ranks entries by hits × weight. Use `requiredKeys` and `linkedKeys` to control activation cascades.
- **Global sharing** – `ContextBank.swapBankWithMutex(key)` pages different memory sets into the active window; `ContextBank.getContextFromBank(key)` returns deep copies for concurrency safety.
- **Mini banks** – `MiniBank.merge(other)` lets retrieval-augmented pipelines combine multiple context pages without polluting the main window. `preValidationMiniBankFunction` hooks inside `Pipe` manipulate these before inference.
- **Token budgeting** – `Dictionary.countTokens` leverages `TruncationSettings` to approximate provider tokenizers, enabling adaptive truncation via `autoTruncateContext()`.
- **Multimodal snapshots** – setting `MultimodalContent.useSnapshot = true` stores a clone in `metadata["snapshot"]`, letting failure handlers restore prior state when a model refuses or derails.

## Pipe-to-Pipe Network
The P2P layer lets agents advertise capabilities and call each other over in-memory, stdio, or remote transports.

```kotlin
import com.TTT.P2P.*
import com.TTT.Pipe.MultimodalContent
import com.TTT.PipeContextProtocol.PcpContext
import com.TTT.PipeContextProtocol.PcPRequest
import com.TTT.PipeContextProtocol.Transport

val agentPipeline = pipeline // reuse pipeline from earlier example

val descriptor = P2PDescriptor(
    agentName = "bedrock.researcher",
    agentDescription = "Summarises documents and returns structured findings.",
    transport = P2PTransport(transportMethod = Transport.Tpipe, transportAddress = "researcher"),
    requiresAuth = false,
    usesConverse = true,
    allowsAgentDuplication = true,
    allowsCustomContext = true,
    allowsCustomAgentJson = true,
    recordsInteractionContext = true,
    recordsPromptContent = false,
    allowsExternalContext = true,
    contextProtocol = ContextProtocol.pcp,
    pcpDescriptor = PcpContext()
)

val requirements = P2PRequirements().apply {
    allowExternalConnections = true
    allowAgentDuplication = true
    allowCustomContext = true
}

descriptor.requestTemplate = P2PRequest(prompt = MultimodalContent())

agentPipeline.setP2pDescription(descriptor)
agentPipeline.setP2pTransport(descriptor.transport)
agentPipeline.setP2pRequirements(requirements)

P2PRegistry.register(agentPipeline)

val response = agentPipeline.executeP2PRequest(
    P2PRequest(
        transport = descriptor.transport,
        prompt = MultimodalContent(text = "Summarise the attached briefing."),
        pcpRequest = PcPRequest()
    )
)
```

What to know:
- **Descriptors vs requirements** – `P2PDescriptor` is the public contract an LLM can read; `P2PRequirements` enforce runtime constraints (token limits, converse schema, auth). `P2PRegistry.listGlobalAgents()` returns descriptors marked for external calls.
- **Custom schemas** – Requests may inject `customContextDescriptions`, `inputSchema`, and `outputSchema`—if duplication is allowed the pipeline clones itself, reapplies system prompts, and re-initializes before execution.
- **AgentRequest helper** – `AgentRequest.buildP2PRequest(template)` converts compact LLM-generated requests into full transports using registry templates.
- **Return routing** – `P2PRequest.returnAddress` and `P2PTransport.transportAuthBody` let you wire multi-hop flows or remote transports (HTTP or stdio runners).
- **Container awareness** – Containers (`Manifold`, `DistributionGrid`, `Connector`) call `getPipelinesFromInterface()` to enumerate sub agents; `P2PRegistry.listLocalAgents(container)` scopes discovery to a particular orchestration domain.
## Container Components
TPipe ships higher-order orchestrators to combine multiple pipelines.

### Connector & MultiConnector
```kotlin
import com.TTT.Pipeline.Connector
import com.TTT.Pipeline.MultiConnector
import com.TTT.Pipe.MultimodalContent

val connector = Connector()
    .add("analysis", pipeline)
    .add("redaction", agentPipeline)
    .setDefaultPath("analysis")
    .enableTracing()

val multi = MultiConnector()
    .add(connector)
    .setMode(MultiConnector.ExecutionMode.SEQUENTIAL)

val routed = connector.execute("analysis", MultimodalContent(text = "Review doc"))
val parallelResults = multi.execute(listOf("analysis", "redaction"), listOf(routed))
```
Connector picks a single downstream pipeline based on a key; MultiConnector chains or balances several connectors with sequential, parallel, or fallback semantics.

### Splitter
```kotlin
import com.TTT.Pipeline.Splitter
import com.TTT.Pipe.MultimodalContent
import com.TTT.Debug.TraceConfig
import kotlinx.coroutines.runBlocking

val splitter = Splitter()
    .addContent("draft", MultimodalContent(text = "Create marketing copy"))
    .addPipeline("draft", pipeline)
    .addPipeline("draft", agentPipeline)
    .enableTracing()
    .setOnPipelineFinish { _, finishedPipeline, content ->
        println("${finishedPipeline.pipelineName} produced ${content.text.take(80)}")
    }

runBlocking {
    splitter.init(TraceConfig(enabled = true))
    splitter.executePipelines().forEach { it.await() }
    println(splitter.results.contents.keys)
}
```
Splitter clones input content, fan-outs execution, and aggregates results with optional callbacks.

### Manifold
`Manifold` coordinates a manager pipeline plus a roster of worker pipelines with progress tracking (`TaskProgress`) and memory policies (auto truncation, context sharing). Key calls:
- `setManagerPipeline(pipeline)` – validates that the manager can emit `AgentRequest` JSON and registers P2P metadata.
- `addWorkerPipeline(worker)` – auto-registers each worker for P2P dispatch, optionally using custom descriptors.
- `setAgentPipeNames(list)` – names the manager pipe(s) that issue worker calls.
- `setContextTruncationFunction { }`, `setWorkerValidationFunction { }`, `setFailureFunction { }` – inject human-in-loop logic around task loops.

Use `Defaults.ManifoldDefaults.withBedrock(...)` or `.withOllama(...)` to bootstrap a fully wired manager + workers whose agents are already registered.

### DistributionGrid & Junction
- **DistributionGrid** (WIP) enables decentralized swarms where each agent decides the next hop via `DistributionGridTask`. Core methods (`setEntryPipeline`, `setJudgePipeline`, `addWorkerPipeline`) already enforce JSON schema compatibility; remaining orchestration loops are under construction.
- **Junction** documents democratic debate flows (moderator + specialists) but is not yet implemented—treat as design notes.

## Modules & Provider Setup

### TPipe-Bedrock
- **Credentials** – configure standard AWS auth (`aws configure`, environment variables, or IAM role). Ensure the runtime has permission for `bedrock:InvokeModel` / `bedrock:Converse`. 
- **Inference profiles** – call `env.bedrockEnv.loadInferenceConfig()` to create `~/.aws/inference.txt`; fill profile ARNs for `us.*` models or bind dynamically via `bedrockEnv.bindInferenceProfile(modelId, profileArn)`.
- **Pipe setup**
  ```kotlin
  import bedrockPipe.BedrockMultimodalPipe
  import env.bedrockEnv

  bedrockEnv.loadInferenceConfig()

  val pipe = BedrockMultimodalPipe()
      .setPipeName("Policy summariser")
      .setRegion("us-west-2")
      .useConverseApi()
      .setModel("anthropic.claude-3-7-sonnet-20250219-v1:0")
      .setMaxTokens(4000)
      .enableStreaming()
      .setStreamingCallback { chunk -> print(chunk) }
      .setTools(listOf(cloudwatchQueryToolJson))
      .setToolChoice("auto")
      .setReasoning(6000)
      .applySystemPrompt()
  ```
- **Features** –
  - Switch between legacy `InvokeModel` and `Converse` with `useConverseApi()`, and stream responses via `enableStreaming()` plus either overload of `setStreamingCallback`.
  - Tune reasoning behaviour with `setReasoning()`, `setReasoning(tokens)`, or `setReasoning(customString)`; inspect `MultimodalContent.modelReasoning` to capture the provider’s thought trace.
  - Register Claude tool schemas using `setTools(List<JsonObject>)` and direct selection with `setToolChoice("auto"/"any"/specific-name`).
  - Enable caching (`enableCaching(control)`), adjust timeouts with `setReadTimeout`, and reuse Nova presets via the `NovaPipe` and `NovaCanvasPipe` helpers.

### TPipe-Ollama
- **Server** – start `ollama serve` locally or point to a LAN host; pull models in advance (`ollama pull llama3.1`).
- **Pipe setup**
  ```kotlin
  import ollamaPipe.OllamaPipe

  val writer = OllamaPipe()
      .setModel("llama3.1:70b")
      .setIP("192.168.0.42")
      .setPort(11434)
      .setNumPredict(800)
      .setRepeatPenalty(1.1f)
      .setMirostat(mode = 2, eta = 0.5f, tau = 0.8f)
      .setGpuSettings(numGpu = 2)
      .setNumThread(12)
      .setJsonOutput("""{"headline": "", "body": ""}""")
      .applySystemPrompt()
  ```
- **Knobs** – adjust context window (`setNumCtx`), sampling (`setMinP`, `setTypicalP`, `setPresencePenalty`, `setFrequencyPenalty`), batching (`setBatchSize`), reproducibility (`setSeed`), and memory trade-offs (`setLowVram`, `setUseMmap`, `setUseMlock`). Files added to `MultimodalContent.binaryContent` travel through to Ollama’s `/generate` endpoint as base64 payloads.

### TPipe-Defaults
- `Defaults.ManifoldDefaults.withBedrock(config)` / `.withOllama(config)` return a pre-wired `Manifold` with opinionated manager + worker prompts, tracing, and context policies. `BedrockConfiguration.make(region, model)` looks up inference profiles automatically; `OllamaConfiguration` captures host, TLS, and timeout settings.
- `assignManagerPipelineDefaults(pipeline)` rewrites the first two pipes to emit `TaskProgress` and `AgentRequest` payloads and toggles context/truncation settings expected by the default manifold supervisor.
- Provider factories `BedrockDefaults.createWorkerPipe` / `OllamaDefaults.createWorkerPipe` clone extra agents with consistent settings.

### TPipe-MCP
- Use `McpToPcpConverter` to translate MCP tool catalogs into PCP function + stdio options, and `PcpToMcpConverter` to advertise PCP-defined capabilities back to MCP hosts.
- `PcpBuilder` offers a fluent API for assembling `PcpContext` entries programmatically, while `PcpAccessor` (`PipeExtensions.kt`) exposes convenience methods to pipe instances.

## Debugging & Trace Workflows
### Enable tracing early
Call `pipeline.enableTracing(TraceConfig(enabled = true, detailLevel = TraceDetailLevel.DEBUG))` while iterating so every pipe emits lifecycle events. When you only need to observe a single component, toggle tracing per pipe with `pipe.enableTracing(config)`.

### Explore the trace
Export runs with `PipeTracer.exportTrace(pipelineId, TraceFormat.HTML/JSON/MARKDOWN)` or feed events into `TraceVisualizer.generateHtmlReport(...)`. Sample dashboards (`standard-pipeline-trace.html`, `manifold-trace.html`) show the expected layout. For a terminal summary, `TraceVisualizer.generateFlowChart` and `generateTimeline` show the execution path and timing.

### Investigate failures
`PipeTracer.getFailureAnalysis(pipelineId)` reports the last successful pipe, the failure event, and any captured context snapshot, then suggests remediation steps. When you need reasoning tokens, guardrail output, or P2P hop detail, export JSON and run `java -jar GenerateTraceHtml.jar trace.json trace.html` to generate a standalone report.

### Automate and regress
The `trace(...)` helper records custom metadata, and containers such as Splitter, MultiConnector, and Manifold already log routing decisions through it. Keep behaviour stable with `./gradlew test`; the suite exercises tracer behaviour (`Debug/PipeTracerTest.kt`, `Debug/TraceVerbosityTest.kt`, `Debug/ManifoldTracingTest.kt`) and PCP security tests. After injecting credentials, `run-comprehensive-test.sh` rehearses cross-module flows end-to-end.

## Advanced Feature Catalog
**Multimodal control.** `BinaryContent` covers raw bytes, base64 payloads, text documents, and cloud references. Combine that with `MultimodalContent` flags—`repeatPipe`, `jumpToPipe("name")`, `terminate()`, `passPipeline`—to loop, branch, or exit early while keeping tool calls in `tools: PcPRequest`.

**Reusable templates.** Call `pipe.toPipeSettings()` and persist the result with `serialize`/`deserialize` to store tuned configurations without revisiting every setter.

**Resilient parsing.** `repairJsonString`, `repairAndDeserialize`, and `extractAllJsonObjects` clean up inconsistent LLM output before deserialisation. Use `exampleFor(MySchema::class)` when you need canonical JSON for prompts or custom P2P schemas.

**HTTP tooling.** `httpRequest`, `httpGet`, `httpPost`, and `httpPut` wrap Ktor clients with timeout/auth handling so the same path serves human calls and PCP HTTP tools.

**Token budgeting.** `Dictionary.countTokens` approximates provider tokenisers. Inspect `pipe.getTruncationSettings()` to adjust factors such as `multiplyWindowSizeBy` or `favorWholeWords` before enabling `autoTruncateContext()` on long-lived agents.

**See it in practice.** The neighbouring `TPipeWriter` project shows these mechanisms in a production-style app—multi-agent orchestration, PCP tooling, and trace analysis included.

## API Quick Reference
| Component | Defined In | Role | Handy Calls |
| --- | --- | --- | --- |
| `Pipe` | `src/main/kotlin/Pipe/Pipe.kt` | Provider abstraction with prompts, context, PCP, and reasoning controls | `setModel`, `setSystemPrompt`, `setJsonOutput`, `setReasoning(...)`, `enableTracing`, `execute(MultimodalContent)` |
| `Pipeline` | `src/main/kotlin/Pipeline/Pipeline.kt` | Ordered execution engine with context propagation and tracing | `add`, `useGlobalContext`, `enableTracing`, `execute(...)`, `getTraceReport`, `init()` |
| `MultimodalContent` | `src/main/kotlin/Pipe/BinaryContent.kt` | Shared payload for text, binaries, metadata, and tool calls | `addBinary`, `addText`, `terminate`, `jumpToPipe`, `clearJumpToPipe`, `copyMultimodal` |
| `ContextWindow` | `src/main/kotlin/Context/ContextWindow.kt` | Stores lorebooks, raw context, and conversation history | `merge`, `selectLoreBookContext`, `findMatchingLoreBookKeys`, `countAndSortKeyHits` |
| `PcpContext` | `src/main/kotlin/PipeContextProtocol/Pcp.kt` | Declares available tools across transports | `addStdioOption`, `addTPipeOption`, `addHttpOption` |
| `PcPRequest` | `src/main/kotlin/PipeContextProtocol/Pcp.kt` | Represents a single tool invocation emitted by an LLM | pass to `PcpExecutionDispatcher.executeRequest(s)` |
| `P2PDescriptor` | `src/main/kotlin/P2P/P2PDescriptor.kt` | Advertises agent capabilities and formatting requirements | configure then `P2PRegistry.register`; summarise with `AgentDescriptor.buildFromDescriptor` |
| `Connector` | `src/main/kotlin/Pipeline/Connector.kt` | Routes content to one of several pipelines by key | `add`, `setDefaultPath`, `execute`, `enableTracing`, `getTrace` |
| `Splitter` | `src/main/kotlin/Pipeline/Splitter.kt` | Executes multiple pipelines in parallel and aggregates results | `addContent`, `addPipeline`, `init`, `executePipelines`, `setOnPipelineFinish`, `setOnSplitterFinish` |
| `Manifold` | `src/main/kotlin/Pipeline/Manifold.kt` | Manages manager/worker agents with P2P dispatch | `setManagerPipeline`, `addWorkerPipeline`, `setAgentPipeNames`, `setContextTruncationFunction`, `execute` |
| `McpToPcpConverter` | `TPipe-MCP/src/main/.../McpToPcpConverter.kt` | Converts MCP manifests into PCP tool/context definitions | `convert(mcpRequest)` |
