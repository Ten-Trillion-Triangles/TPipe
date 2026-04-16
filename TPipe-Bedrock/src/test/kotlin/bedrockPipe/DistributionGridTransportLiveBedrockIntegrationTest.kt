package bedrockPipe

import com.TTT.Config.TPipeConfig
import com.TTT.Debug.PipeTracer
import com.TTT.Debug.TraceConfig
import com.TTT.Debug.TraceDetailLevel
import com.TTT.Debug.TraceEventType
import com.TTT.Debug.TraceFormat
import com.TTT.Enums.ProviderName
import com.TTT.P2P.ContextProtocol
import com.TTT.P2P.KillSwitch
import com.TTT.P2P.P2PDescriptor
import com.TTT.P2P.P2PInterface
import com.TTT.P2P.P2PRegistry
import com.TTT.P2P.P2PRequirements
import com.TTT.P2P.P2PRequest
import com.TTT.P2P.P2PResponse
import com.TTT.P2P.P2PTransport
import com.TTT.P2P.SupportedContentTypes
import com.TTT.Pipe.MultimodalContent
import com.TTT.PipeContextProtocol.PcPRequest
import com.TTT.PipeContextProtocol.StdioContextOptions
import com.TTT.PipeContextProtocol.StdioExecutionMode
import com.TTT.PipeContextProtocol.StdioSessionManager
import com.TTT.Pipeline.DistributionGrid
import com.TTT.Pipeline.DistributionGridDirective
import com.TTT.Pipeline.DistributionGridDirectiveKind
import com.TTT.Pipeline.DistributionGridEnvelope
import com.TTT.Pipeline.DistributionGridFailure
import com.TTT.Pipeline.DistributionGridFailureKind
import com.TTT.Pipeline.DistributionGridNodeMetadata
import com.TTT.Pipeline.DistributionGridOutcome
import com.TTT.Pipeline.DistributionGridProtocolVersion
import com.TTT.Pipeline.DistributionGridRoutingPolicy
import com.TTT.Pipeline.DistributionGridTracePolicy
import com.TTT.Pipeline.Pipeline
import com.TTT.Pipeline.distributionGrid
import com.TTT.PipeContextProtocol.Transport
import com.TTT.Util.deepCopy
import com.TTT.Util.deserialize
import com.TTT.Util.serialize
import com.TTT.Util.writeStringToFile
import com.TTT.module
import env.bedrockEnv
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.PrintStream
import java.net.ServerSocket
import java.util.Scanner
import java.util.concurrent.atomic.AtomicInteger

private const val QWEN_30B_MODEL_ID = "qwen.qwen3-coder-30b-a3b-v1:0"
private const val QWEN_30B_REGION = "us-west-2"

object DistributionGridTransportLiveBedrockFixtures
{
    private const val GRID_DIRECTIVE_METADATA_KEY = "distributionGridDirective"
    val compactJson = Json {
        prettyPrint = false
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
        explicitNulls = false
        coerceInputValues = true
        allowSpecialFloatingPointValues = true
        allowStructuredMapKeys = true
        allowComments = true
        useArrayPolymorphism = true
        decodeEnumsCaseInsensitive = true
        useAlternativeNames = true
    }

    const val HTTP_TRACE_ROOT = "Library/distribution-grid-live-bedrock-http/manual-core-qwen-two-node"
    const val STDIO_TRACE_ROOT = "Library/distribution-grid-live-bedrock-stdio/manual-core-qwen-two-node"

    @Serializable
    enum class LiveGridExecutionMode
    {
        LOCAL_ONLY,
        REMOTE_SPECIALIST
    }

    @Serializable
    data class LiveGridTaskRequest(
        val caseId: String,
        val executionMode: LiveGridExecutionMode,
        val targetPeerId: String = "",
        val task: String
    )

    @Serializable
    data class LiveGridWorkerResult(
        val executingNode: String,
        val executionMode: String,
        val specialty: String,
        val answer: String,
        val confidenceBand: String
    )

    @Serializable
    data class LiveGridDirectiveOutput(
        val directive: DistributionGridDirective
    )

    @Serializable
    data class LiveGridWorkerOutput(
        val result: LiveGridWorkerResult
    )

    @Serializable
    data class LiveGridRuntimeSummary(
        val scenarioName: String,
        val sideLabel: String,
        val routerRequestCount: Int,
        val workerRequestCount: Int,
        val finalNodeId: String,
        val hopCount: Int,
        val answer: String,
        val failureKind: String = "",
        val failureReason: String = ""
    )

    data class LiveGridExecutionResults(
        val localResult: MultimodalContent,
        val remoteResult: MultimodalContent,
        val localPayload: LiveGridWorkerResult,
        val remotePayload: LiveGridWorkerResult
    )

    data class LiveGridSideRuntime(
        val scenarioName: String,
        val sideLabel: String,
        val grid: DistributionGrid,
        val router: LiveBedrockRoleContainer,
        val worker: LiveBedrockRoleContainer
    )

    data class LiveGridSideArtifacts(
        val gridHtml: String,
        val routerHtml: String,
        val workerHtml: String,
        val summary: LiveGridRuntimeSummary
    )

    private inline fun <reified T> deserializeStrict(
        json: String,
        label: String
    ): T
    {
        val trimmed = json.trim()
        return when
        {
            trimmed.startsWith("{") ->
            {
                deserialize<T>(json, useRepair = false)
                    ?: error("$label should deserialize with TPipe's official JSON parser without repair. Raw output: $json")
            }

            trimmed.startsWith("[") ->
            {
                val items = deserialize<List<T>>(json, useRepair = false)
                    ?: error("$label should deserialize as a single-item JSON array with TPipe's official JSON parser. Raw output: $json")
                require(items.size == 1) {
                    "$label must contain exactly one structured response item when emitted as a JSON array. Raw output: $json"
                }
                items.first()
            }

            else ->
            {
                error("$label must be encoded as a JSON object or a single-item JSON array. Raw output: $json")
            }
        }
    }

    sealed class RoleAdapter
    {
        abstract fun adapt(output: MultimodalContent, original: MultimodalContent): MultimodalContent

        data class SenderRouter(
            val expectedRemotePeerId: String
        ) : RoleAdapter()
        {
            override fun adapt(output: MultimodalContent, original: MultimodalContent): MultimodalContent
            {
                val request = deserializeStrict<LiveGridTaskRequest>(original.text, "sender router task request")
                val directive = deserializeStrict<LiveGridDirectiveOutput>(output.text, "sender router output").directive

                when(request.executionMode)
                {
                    LiveGridExecutionMode.LOCAL_ONLY ->
                    {
                        require(directive.kind == DistributionGridDirectiveKind.RUN_LOCAL_WORKER)
                    }

                    LiveGridExecutionMode.REMOTE_SPECIALIST ->
                    {
                        require(directive.kind == DistributionGridDirectiveKind.HAND_OFF_TO_PEER)
                        require(directive.targetPeerId == expectedRemotePeerId)
                    }
                }

                return original.deepCopy().apply {
                    metadata[GRID_DIRECTIVE_METADATA_KEY] = directive.deepCopy()
                }
            }
        }

        object RemoteRouter : RoleAdapter()
        {
            override fun adapt(output: MultimodalContent, original: MultimodalContent): MultimodalContent
            {
                val directive = deserializeStrict<LiveGridDirectiveOutput>(output.text, "remote router output").directive
                require(directive.kind == DistributionGridDirectiveKind.RUN_LOCAL_WORKER)
                return original.deepCopy().apply {
                    metadata[GRID_DIRECTIVE_METADATA_KEY] = directive.deepCopy()
                }
            }
        }

        data class Worker(
            val expectedNodeId: String,
            val expectedExecutionMode: LiveGridExecutionMode,
            val expectedSpecialty: String
        ) : RoleAdapter()
        {
            override fun adapt(output: MultimodalContent, original: MultimodalContent): MultimodalContent
            {
                val request = deserializeStrict<LiveGridTaskRequest>(original.text, "worker task request")
                val parsed = deserializeStrict<LiveGridWorkerOutput>(output.text, "worker output").result

                require(parsed.executingNode == expectedNodeId)
                require(parsed.executionMode == expectedExecutionMode.name)
                require(parsed.specialty == expectedSpecialty)
                require(parsed.answer.isNotBlank())

                return MultimodalContent(text = serialize(parsed)).apply {
                    modelReasoning = output.text.take(256)
                    metadata["caseId"] = request.caseId
                }
            }
        }
    }

    class LiveBedrockRoleContainer(
        private val roleName: String,
        val pipeline: Pipeline,
        private val adapter: RoleAdapter
    ) : P2PInterface
    {
        private var descriptor: P2PDescriptor? = null
        private var requirements: P2PRequirements? = null
        private var transport: P2PTransport? = null
        private var containerRef: Any? = null
        override var killSwitch: KillSwitch? = null

        private val _requestCount = AtomicInteger(0)
        val requestCount: Int get() = _requestCount.get()

        override fun setP2pDescription(description: P2PDescriptor)
        {
            descriptor = description
            pipeline.setP2pDescription(description)
        }

        override fun getP2pDescription(): P2PDescriptor? = descriptor ?: pipeline.getP2pDescription()

        override fun setP2pTransport(transport: P2PTransport)
        {
            this.transport = transport
            pipeline.setP2pTransport(transport)
        }

        override fun getP2pTransport(): P2PTransport? = transport ?: pipeline.getP2pTransport()

        override fun setP2pRequirements(requirements: P2PRequirements)
        {
            this.requirements = requirements
            pipeline.setP2pRequirements(requirements)
        }

        override fun getP2pRequirements(): P2PRequirements? = requirements ?: pipeline.getP2pRequirements()

        override fun getContainerObject(): Any? = containerRef ?: pipeline.getContainerObject()

        override fun setContainerObject(container: Any)
        {
            containerRef = container
            pipeline.setContainerObject(container)
        }

        override fun getPipelinesFromInterface(): List<Pipeline> = listOf(pipeline)

        override suspend fun executeLocal(content: MultimodalContent): MultimodalContent
        {
            _requestCount.incrementAndGet()
            val original = content.deepCopy()
            return adapter.adapt(pipeline.execute(content), original)
        }

        override suspend fun executeP2PRequest(request: P2PRequest): P2PResponse?
        {
            _requestCount.incrementAndGet()
            val response = pipeline.executeP2PRequest(request) ?: return null
            val output = response.output ?: return response
            val normalized = adapter.adapt(output, request.prompt.deepCopy())
            return P2PResponse(output = normalized)
        }

        fun traceId(): String = pipeline.getTraceId()
    }

    class FixedDirectiveRoleContainer(
        private val directive: DistributionGridDirective
    ) : P2PInterface
    {
        private var descriptor: P2PDescriptor? = null
        private var requirements: P2PRequirements? = null
        private var transport: P2PTransport? = null
        private var containerRef: Any? = null
        override var killSwitch: KillSwitch? = null

        override fun setP2pDescription(description: P2PDescriptor) { descriptor = description }
        override fun getP2pDescription(): P2PDescriptor? = descriptor
        override fun setP2pTransport(transport: P2PTransport) { this.transport = transport }
        override fun getP2pTransport(): P2PTransport? = transport
        override fun setP2pRequirements(requirements: P2PRequirements) { this.requirements = requirements }
        override fun getP2pRequirements(): P2PRequirements? = requirements
        override fun getContainerObject(): Any? = containerRef
        override fun setContainerObject(container: Any) { containerRef = container }
        override fun getPipelinesFromInterface(): List<Pipeline> = emptyList()

        override suspend fun executeLocal(content: MultimodalContent): MultimodalContent
        {
            return content.deepCopy().apply {
                metadata[GRID_DIRECTIVE_METADATA_KEY] = directive.deepCopy()
            }
        }

        override suspend fun executeP2PRequest(request: P2PRequest): P2PResponse?
        {
            return P2PResponse(
                output = request.prompt.deepCopy().apply {
                    metadata[GRID_DIRECTIVE_METADATA_KEY] = directive.deepCopy()
                }
            )
        }
    }

    fun buildDebugTraceConfig(): TraceConfig =
        TraceConfig(enabled = true, outputFormat = TraceFormat.HTML, detailLevel = TraceDetailLevel.DEBUG, includeContext = true, includeMetadata = true)

    fun buildVisibleDebugTracePolicy(): DistributionGridTracePolicy =
        DistributionGridTracePolicy(allowTracing = true, allowTracePersistence = true, requireRedaction = false, rejectNonCompliantNodes = true)

    fun resolveLiveModelId(): String
    {
        val inferenceProfile = bedrockEnv.getInferenceProfileId(QWEN_30B_MODEL_ID)
        return if(inferenceProfile.isNullOrBlank()) QWEN_30B_MODEL_ID else inferenceProfile
    }

    fun createBaseBedrockPipe(
        pipeName: String,
        modelId: String,
        traceConfig: TraceConfig,
        maxTokens: Int
    ): BedrockPipe
    {
        return BedrockPipe().apply {
            setPipeName(pipeName)
            setProvider(ProviderName.Aws)
            setModel(modelId)
            setRegion(QWEN_30B_REGION)
            useConverseApi()
            setReadTimeout(600)
            setTemperature(0.1)
            setTopP(0.2)
            setMaxTokens(maxTokens)
            enableTracing(traceConfig)
        }
    }

    fun createSenderRouterPipeline(traceConfig: TraceConfig, modelId: String, expectedRemotePeerId: String, pipeName: String): Pipeline
    {
        val pipe = createBaseBedrockPipe(pipeName, modelId, traceConfig, 256).apply {
            setSystemPrompt(
                """
                You are the sender router for a live TPipe DistributionGrid integration test.
                The user prompt is a serialized LiveGridTaskRequest JSON object.
                Follow the configured JSON schema exactly.
                If executionMode is LOCAL_ONLY, choose the local worker path.
                If executionMode is REMOTE_SPECIALIST, choose the remote handoff path and preserve the input target peer id.
                Never use any other routing behavior in this test.
                Keep notes short and operational.
                """.trimIndent()
            )
            setJsonOutput(
                LiveGridDirectiveOutput(
                    directive = DistributionGridDirective(
                        kind = DistributionGridDirectiveKind.HAND_OFF_TO_PEER,
                        targetPeerId = expectedRemotePeerId,
                        notes = "Delegate to the remote specialist."
                    )
                )
            )
            requireJsonPromptInjection(true)
        }
        return Pipeline().apply { add(pipe) }
    }

    fun createRemoteRouterPipeline(traceConfig: TraceConfig, modelId: String, pipeName: String): Pipeline
    {
        val pipe = createBaseBedrockPipe(pipeName, modelId, traceConfig, 256).apply {
            setSystemPrompt(
                """
                You are the remote router for a live TPipe DistributionGrid integration test.
                The user prompt is a serialized LiveGridTaskRequest JSON object.
                Follow the configured JSON schema exactly.
                For every task in this test, choose the local worker path on the remote node.
                Never hand off again.
                Keep notes short and operational.
                """.trimIndent()
            )
            setJsonOutput(
                LiveGridDirectiveOutput(
                    directive = DistributionGridDirective(
                        kind = DistributionGridDirectiveKind.RUN_LOCAL_WORKER,
                        notes = "Remote node should run its local worker."
                    )
                )
            )
            requireJsonPromptInjection(true)
        }
        return Pipeline().apply { add(pipe) }
    }

    fun createWorkerPipeline(
        roleName: String,
        traceConfig: TraceConfig,
        modelId: String,
        executingNode: String,
        executionMode: LiveGridExecutionMode,
        specialty: String
    ): Pipeline
    {
        val pipe = createBaseBedrockPipe("distribution-grid-live-$roleName", modelId, traceConfig, 512).apply {
            setSystemPrompt(
                """
                You are the $specialty worker for a live TPipe DistributionGrid integration test.
                The user prompt is a serialized LiveGridTaskRequest JSON object.
                Follow the configured JSON schema exactly.
                Use these exact fixed values:
                - executing node: "$executingNode"
                - execution mode: "${executionMode.name}"
                - specialty: "$specialty"
                Set confidence to LOW, MEDIUM, or HIGH.
                Keep answer concise, helpful, and specific to the task.
                """.trimIndent()
            )
            setJsonOutput(
                LiveGridWorkerOutput(
                    result = LiveGridWorkerResult(
                        executingNode = executingNode,
                        executionMode = executionMode.name,
                        specialty = specialty,
                        answer = "Short task-specific answer.",
                        confidenceBand = "HIGH"
                    )
                )
            )
            requireJsonPromptInjection(true)
        }
        return Pipeline().apply { add(pipe) }
    }

    fun createLiveRoleContainer(
        roleName: String,
        traceConfig: TraceConfig,
        pipeline: Pipeline,
        adapter: RoleAdapter
    ): LiveBedrockRoleContainer
    {
        pipeline.setPipelineName(roleName)
        pipeline.enableTracing(traceConfig)
        runBlocking(Dispatchers.IO) { pipeline.init(initPipes = true) }
        return LiveBedrockRoleContainer(roleName = roleName, pipeline = pipeline, adapter = adapter)
    }

    fun buildGridDescriptor(
        nodeId: String,
        transportMethod: Transport,
        transportAddress: String,
        defaultTracePolicy: DistributionGridTracePolicy,
        recordsPromptContent: Boolean = false,
        recordsInteractionContext: Boolean = false
    ): P2PDescriptor
    {
        return P2PDescriptor(
            agentName = nodeId,
            agentDescription = "Live DistributionGrid Bedrock test node",
            transport = P2PTransport(
                transportMethod = transportMethod,
                transportAddress = transportAddress
            ),
            requiresAuth = false,
            usesConverse = false,
            allowsAgentDuplication = false,
            allowsCustomContext = false,
            allowsCustomAgentJson = false,
            recordsInteractionContext = recordsInteractionContext,
            recordsPromptContent = recordsPromptContent,
            allowsExternalContext = false,
            contextProtocol = ContextProtocol.none,
            supportedContentTypes = mutableListOf(SupportedContentTypes.text),
            distributionGridMetadata = DistributionGridNodeMetadata(
                nodeId = nodeId,
                supportedProtocolVersions = mutableListOf(DistributionGridProtocolVersion()),
                roleCapabilities = mutableListOf("Router", "Worker"),
                supportedTransports = mutableListOf(transportMethod),
                requiresHandshake = true,
                defaultTracePolicy = defaultTracePolicy,
                defaultRoutingPolicy = DistributionGridRoutingPolicy(),
                actsAsRegistry = false
            )
        )
    }

    fun buildPersistentStdioGridDescriptor(
        nodeId: String,
        transportAddress: String,
        defaultTracePolicy: DistributionGridTracePolicy,
        sessionId: String,
        recordsPromptContent: Boolean = false,
        recordsInteractionContext: Boolean = false
    ): P2PDescriptor
    {
        return buildGridDescriptor(
            nodeId = nodeId,
            transportMethod = Transport.Stdio,
            transportAddress = transportAddress,
            defaultTracePolicy = defaultTracePolicy,
            recordsPromptContent = recordsPromptContent,
            recordsInteractionContext = recordsInteractionContext
        ).apply {
            requestTemplate = P2PRequest(
                transport = P2PTransport(
                    transportMethod = Transport.Stdio,
                    transportAddress = transportAddress
                ),
                pcpRequest = PcPRequest(
                    stdioContextOptions = StdioContextOptions().apply {
                        executionMode = StdioExecutionMode.CONNECT
                        this.sessionId = sessionId
                        command = transportAddress
                    }
                )
            )
        }
    }

    fun createGrid(
        nodeId: String,
        transportMethod: Transport,
        transportAddress: String,
        router: LiveBedrockRoleContainer,
        worker: LiveBedrockRoleContainer,
        traceConfig: TraceConfig,
        defaultTracePolicy: DistributionGridTracePolicy,
        recordsPromptContent: Boolean = false,
        recordsInteractionContext: Boolean = false
    ): DistributionGrid
    {
        return DistributionGrid().apply {
            setP2pTransport(P2PTransport(transportMethod = transportMethod, transportAddress = transportAddress))
            setP2pDescription(
                buildGridDescriptor(
                    nodeId = nodeId,
                    transportMethod = transportMethod,
                    transportAddress = transportAddress,
                    defaultTracePolicy = defaultTracePolicy,
                    recordsPromptContent = recordsPromptContent,
                    recordsInteractionContext = recordsInteractionContext
                )
            )
            setP2pRequirements(P2PRequirements(allowExternalConnections = true, acceptedContent = mutableListOf(SupportedContentTypes.text)))
            setRouter(router)
            setWorker(worker)
            enableTracing(traceConfig)
        }
    }

    fun transportTraceDir(transportLabel: String, scenarioName: String, sideLabel: String): File
    {
        return File(TPipeConfig.getTraceDir(), "Library/distribution-grid-live-bedrock-$transportLabel/manual-core-qwen-two-node/$scenarioName/$sideLabel")
    }

    fun saveSideTraceArtifacts(
        transportLabel: String,
        sideRuntime: LiveGridSideRuntime,
        finalResult: MultimodalContent,
        summaryFileName: String = "summary.json"
    ): LiveGridSideArtifacts
    {
        val traceDir = transportTraceDir(transportLabel, sideRuntime.scenarioName, sideRuntime.sideLabel)
        traceDir.mkdirs()

        val gridHtml = sideRuntime.grid.getTraceReport(TraceFormat.HTML)
        val routerHtml = sideRuntime.router.pipeline.getTraceReport(TraceFormat.HTML)
        val workerHtml = sideRuntime.worker.pipeline.getTraceReport(TraceFormat.HTML)

        writeTraceFile(File(traceDir, "grid.html"), gridHtml, "${sideRuntime.sideLabel} grid")
        writeTraceFile(File(traceDir, "router-pipeline.html"), routerHtml, "${sideRuntime.sideLabel} router pipeline")
        writeTraceFile(File(traceDir, "worker-pipeline.html"), workerHtml, "${sideRuntime.sideLabel} worker pipeline")

        val outcome = finalResult.metadata["distributionGridOutcome"] as? DistributionGridOutcome
        val failure = finalResult.metadata["distributionGridFailure"] as? DistributionGridFailure
        val summary = LiveGridRuntimeSummary(
            scenarioName = sideRuntime.scenarioName,
            sideLabel = sideRuntime.sideLabel,
            routerRequestCount = sideRuntime.router.requestCount,
            workerRequestCount = sideRuntime.worker.requestCount,
            finalNodeId = outcome?.finalNodeId.orEmpty(),
            hopCount = outcome?.hopCount ?: 0,
            answer = finalResult.text,
            failureKind = failure?.kind?.name.orEmpty(),
            failureReason = failure?.reason.orEmpty()
        )
        writeStringToFile(File(traceDir, summaryFileName).absolutePath, serialize(summary))

        return LiveGridSideArtifacts(gridHtml = gridHtml, routerHtml = routerHtml, workerHtml = workerHtml, summary = summary)
    }

    fun writeTraceFile(file: File, content: String, label: String)
    {
        writeStringToFile(file.absolutePath, content)
        assertTrue(file.exists(), "$label trace file should exist at ${file.absolutePath}")
        assertTrue(file.length() > 0, "$label trace file should not be empty at ${file.absolutePath}")
        assertTrue(content.isNotBlank(), "$label trace report should not be blank.")
    }

    fun assertGridExecutionSucceeded(result: MultimodalContent, label: String)
    {
        val failure = result.metadata["distributionGridFailure"] as? DistributionGridFailure
        assertTrue(result.passPipeline, "$label should complete successfully. Failure: ${failure?.kind}: ${failure?.reason}. Raw result: ${result.text}")
        assertFalse(result.terminatePipeline, "$label should not terminate with a failure envelope.")
    }

    fun assertGridTrace(html: String, label: String, result: MultimodalContent? = null)
    {
        assertTrue(html.contains("TPipe DistributionGrid Execution Analysis"), "$label trace should use the DistributionGrid visualizer heading.")
        assertTrue(html.contains("Grid State"), "$label trace should include the Grid State section.")
        assertTrue(html.contains("Grid Orchestration Flow"), "$label trace should include the orchestration section.")
        result?.let {
            val outcome = it.metadata["distributionGridOutcome"] as? DistributionGridOutcome
            if(outcome != null && outcome.finalNodeId.isNotBlank()) assertTrue(html.contains(outcome.finalNodeId), "$label trace should identify the node that executed work.")
            val failure = it.metadata["distributionGridFailure"] as? DistributionGridFailure
            if(failure != null) assertTrue(html.contains(failure.kind.name) || html.contains(failure.reason), "$label trace should surface the failure kind or reason.")
        }
    }

    fun assertPipelineTrace(role: LiveBedrockRoleContainer, label: String)
    {
        val events = PipeTracer.getTrace(role.traceId()).map { it.eventType }
        assertTrue(events.isNotEmpty(), "$label pipeline trace should record live events.")
        assertTrue(events.contains(TraceEventType.API_CALL_START) || events.contains(TraceEventType.API_CALL_SUCCESS), "$label pipeline trace should contain Bedrock API execution events.")
    }

    fun createSenderRuntime(
        scenarioName: String,
        transportLabel: String,
        traceConfig: TraceConfig,
        modelId: String,
        senderTracePolicy: DistributionGridTracePolicy,
        remotePeerId: String
    ): LiveGridSideRuntime
    {
        val senderRouter = createLiveRoleContainer(
            roleName = "$transportLabel-sender-router",
            traceConfig = traceConfig,
            pipeline = createSenderRouterPipeline(traceConfig, modelId, remotePeerId, "distribution-grid-$transportLabel-live-sender-router"),
            adapter = RoleAdapter.SenderRouter(expectedRemotePeerId = remotePeerId)
        )
        val senderWorker = createLiveRoleContainer(
            roleName = "$transportLabel-sender-worker",
            traceConfig = traceConfig,
            pipeline = createWorkerPipeline("$transportLabel-sender-worker", traceConfig, modelId, "live-sender-grid", LiveGridExecutionMode.LOCAL_ONLY, "sender-generalist"),
            adapter = RoleAdapter.Worker("live-sender-grid", LiveGridExecutionMode.LOCAL_ONLY, "sender-generalist")
        )
        val senderGrid = createGrid("live-sender-grid", Transport.Tpipe, "live-sender-grid-address", senderRouter, senderWorker, traceConfig, senderTracePolicy)
        return LiveGridSideRuntime(scenarioName, "sender", senderGrid, senderRouter, senderWorker)
    }

    fun createRemoteRuntime(
        scenarioName: String,
        transportLabel: String,
        traceConfig: TraceConfig,
        modelId: String,
        remoteTracePolicy: DistributionGridTracePolicy,
        transportMethod: Transport,
        transportAddress: String,
        recordsPromptContent: Boolean = false,
        recordsInteractionContext: Boolean = false
    ): LiveGridSideRuntime
    {
        val remoteRouter = createLiveRoleContainer(
            roleName = "$transportLabel-remote-router",
            traceConfig = traceConfig,
            pipeline = createRemoteRouterPipeline(traceConfig, modelId, "distribution-grid-$transportLabel-live-remote-router"),
            adapter = RoleAdapter.RemoteRouter
        )
        val remoteWorker = createLiveRoleContainer(
            roleName = "$transportLabel-remote-worker",
            traceConfig = traceConfig,
            pipeline = createWorkerPipeline("$transportLabel-remote-worker", traceConfig, modelId, "live-remote-grid", LiveGridExecutionMode.REMOTE_SPECIALIST, "remote-specialist"),
            adapter = RoleAdapter.Worker("live-remote-grid", LiveGridExecutionMode.REMOTE_SPECIALIST, "remote-specialist")
        )
        val remoteGrid = createGrid("live-remote-grid", transportMethod, transportAddress, remoteRouter, remoteWorker, traceConfig, remoteTracePolicy, recordsPromptContent, recordsInteractionContext)
        return LiveGridSideRuntime(scenarioName, "remote", remoteGrid, remoteRouter, remoteWorker)
    }

    suspend fun executeStrictScenario(senderRuntime: LiveGridSideRuntime, remotePeerId: String, caseIdPrefix: String): LiveGridExecutionResults
    {
        val localTask = LiveGridTaskRequest(
            caseId = "$caseIdPrefix-local",
            executionMode = LiveGridExecutionMode.LOCAL_ONLY,
            task = "Handle this request locally and return only strict JSON for the sender-generalist role."
        )
        val localResult = senderRuntime.grid.execute(MultimodalContent(text = serialize(localTask)))
        assertGridExecutionSucceeded(localResult, "local sender execution")
        val localPayload = deserializeStrict<LiveGridWorkerResult>(localResult.text, "sender worker result")

        assertEquals("live-sender-grid", localPayload.executingNode)
        assertEquals(LiveGridExecutionMode.LOCAL_ONLY.name, localPayload.executionMode)
        assertEquals("sender-generalist", localPayload.specialty)
        assertEquals(1, senderRuntime.router.requestCount)
        assertEquals(1, senderRuntime.worker.requestCount)

        val remoteTask = LiveGridTaskRequest(
            caseId = "$caseIdPrefix-remote",
            executionMode = LiveGridExecutionMode.REMOTE_SPECIALIST,
            targetPeerId = remotePeerId,
            task = "Delegate this specialist request to the remote node and return only strict JSON for the remote-specialist role."
        )
        val remoteResult = senderRuntime.grid.execute(MultimodalContent(text = serialize(remoteTask)))
        assertGridExecutionSucceeded(remoteResult, "remote specialist execution")
        val remotePayload = deserializeStrict<LiveGridWorkerResult>(remoteResult.text, "remote worker result")

        assertEquals("live-remote-grid", remotePayload.executingNode)
        assertEquals(LiveGridExecutionMode.REMOTE_SPECIALIST.name, remotePayload.executionMode)
        assertEquals("remote-specialist", remotePayload.specialty)

        val envelope = remoteResult.metadata["distributionGridEnvelope"] as? DistributionGridEnvelope ?: error("Remote result should contain a DistributionGridEnvelope.")
        val outcome = remoteResult.metadata["distributionGridOutcome"] as? DistributionGridOutcome ?: error("Remote result should contain a DistributionGridOutcome.")
        assertEquals("live-remote-grid", outcome.finalNodeId)
        assertTrue(outcome.hopCount >= 2)
        assertEquals(DistributionGridDirectiveKind.HAND_OFF_TO_PEER, envelope.hopHistory.first().routerAction)

        return LiveGridExecutionResults(localResult, remoteResult, localPayload, remotePayload)
    }

    fun buildStdioLauncherScript(scenarioName: String, traceRoot: String): File
    {
        val script = File.createTempFile("distribution-grid-stdio-$scenarioName", ".sh")
        val classpath = shellQuote(System.getProperty("java.class.path"))
        val traceRootArg = shellQuote(traceRoot)
        val scenarioArg = shellQuote(scenarioName)
        script.writeText(
            """
            #!/bin/bash
            set -euo pipefail
            exec java -cp $classpath bedrockPipe.DistributionGridTransportStdioLauncher $traceRootArg $scenarioArg "$0"
            """.trimIndent()
        )
        script.setExecutable(true)
        script.deleteOnExit()
        return script
    }

    fun shellQuote(value: String): String = "'" + value.replace("'", "'\"'\"'") + "'"

    inline fun <reified T> serializeCompact(obj: T): String = compactJson.encodeToString(obj)
}

class DistributionGridHttpLiveBedrockIntegrationTest
{
    @Test
    fun liveHttpDistributionGridExecutesLocallyThenHandsOffRemotelyWithStrictTypedJson()
    {
        TestCredentialUtils.requireAwsCredentials()

        runBlocking(Dispatchers.IO) {
            bedrockEnv.loadInferenceConfig()
            PipeTracer.enable()

            val traceConfig = DistributionGridTransportLiveBedrockFixtures.buildDebugTraceConfig()
            val resolvedModelId = DistributionGridTransportLiveBedrockFixtures.resolveLiveModelId()
            val port = ServerSocket(0).use { it.localPort }
            val baseUrl = "http://127.0.0.1:$port"
            val remoteTransportAddress = "$baseUrl/p2p"
            val remotePeerId = "${Transport.Http}::$remoteTransportAddress"
            val server = embeddedServer(Netty, port = port, host = "127.0.0.1", module = module()).start(wait = false)

            val senderRuntime = DistributionGridTransportLiveBedrockFixtures.createSenderRuntime("strict-execution", "http", traceConfig, resolvedModelId, DistributionGridTransportLiveBedrockFixtures.buildVisibleDebugTracePolicy(), remotePeerId)
            val remoteRuntime = DistributionGridTransportLiveBedrockFixtures.createRemoteRuntime("strict-execution", "http", traceConfig, resolvedModelId, DistributionGridTransportLiveBedrockFixtures.buildVisibleDebugTracePolicy(), Transport.Http, remoteTransportAddress)

            try
            {
                remoteRuntime.grid.init()
                P2PRegistry.register(remoteRuntime.grid)
                senderRuntime.grid.addPeerDescriptor(remoteRuntime.grid.getP2pDescription()!!.deepCopy())
                senderRuntime.grid.init()

                val results = DistributionGridTransportLiveBedrockFixtures.executeStrictScenario(senderRuntime, remotePeerId, "distribution-grid-http-live-bedrock-strict")
                assertTrue(results.localResult.passPipeline)
                assertTrue(results.remoteResult.passPipeline)

                val senderArtifacts = DistributionGridTransportLiveBedrockFixtures.saveSideTraceArtifacts("http", senderRuntime, results.remoteResult)
                val remoteArtifacts = DistributionGridTransportLiveBedrockFixtures.saveSideTraceArtifacts("http", remoteRuntime, results.remoteResult)

                assertTrue(senderArtifacts.gridHtml.contains(results.localPayload.answer))
                assertTrue(remoteArtifacts.gridHtml.contains(results.remotePayload.answer))
                assertEquals("live-remote-grid", senderArtifacts.summary.finalNodeId)
                assertEquals("live-remote-grid", remoteArtifacts.summary.finalNodeId)
                assertEquals(2, senderRuntime.router.requestCount)
                assertEquals(1, senderRuntime.worker.requestCount)
                assertEquals(1, remoteRuntime.router.requestCount)
                assertEquals(1, remoteRuntime.worker.requestCount)
            }
            finally
            {
                P2PRegistry.remove(remoteRuntime.grid)
                server.stop(0, 0)
                PipeTracer.disable()
            }
        }
    }

    @Test
    fun liveHttpDistributionGridTracePolicyShowsVisibleOutputAndRejectsNonCompliantPeer()
    {
        TestCredentialUtils.requireAwsCredentials()

        runBlocking(Dispatchers.IO) {
            bedrockEnv.loadInferenceConfig()
            PipeTracer.enable()

            val traceConfig = DistributionGridTransportLiveBedrockFixtures.buildDebugTraceConfig()
            val resolvedModelId = DistributionGridTransportLiveBedrockFixtures.resolveLiveModelId()
            val port = ServerSocket(0).use { it.localPort }
            val baseUrl = "http://127.0.0.1:$port"
            val remoteTransportAddress = "$baseUrl/p2p"
            val remotePeerId = "${Transport.Http}::$remoteTransportAddress"
            val server = embeddedServer(Netty, port = port, host = "127.0.0.1", module = module()).start(wait = false)

            val visibleSenderRuntime = DistributionGridTransportLiveBedrockFixtures.createSenderRuntime("trace-policy-visible", "http", traceConfig, resolvedModelId, DistributionGridTransportLiveBedrockFixtures.buildVisibleDebugTracePolicy(), remotePeerId)
            val visibleRemoteRuntime = DistributionGridTransportLiveBedrockFixtures.createRemoteRuntime("trace-policy-visible", "http", traceConfig, resolvedModelId, DistributionGridTransportLiveBedrockFixtures.buildVisibleDebugTracePolicy(), Transport.Http, remoteTransportAddress)

            try
            {
                visibleRemoteRuntime.grid.init()
                P2PRegistry.register(visibleRemoteRuntime.grid)
                visibleSenderRuntime.grid.addPeerDescriptor(visibleRemoteRuntime.grid.getP2pDescription()!!.deepCopy())
                visibleSenderRuntime.grid.init()

                val visibleResults = DistributionGridTransportLiveBedrockFixtures.executeStrictScenario(visibleSenderRuntime, remotePeerId, "distribution-grid-http-live-bedrock-visible")
                val visibleSenderArtifacts = DistributionGridTransportLiveBedrockFixtures.saveSideTraceArtifacts("http", visibleSenderRuntime, visibleResults.remoteResult)
                val visibleRemoteArtifacts = DistributionGridTransportLiveBedrockFixtures.saveSideTraceArtifacts("http", visibleRemoteRuntime, visibleResults.remoteResult)

                assertTrue(visibleSenderArtifacts.gridHtml.contains(visibleResults.localPayload.answer))
                assertTrue(visibleSenderArtifacts.gridHtml.contains(visibleResults.remotePayload.answer))
                assertTrue(visibleRemoteArtifacts.gridHtml.contains(visibleResults.remotePayload.answer))
                assertFalse(visibleSenderArtifacts.gridHtml.contains("REDACTED BY DISTRIBUTION GRID TRACE POLICY"))
                assertFalse(visibleRemoteArtifacts.gridHtml.contains("REDACTED BY DISTRIBUTION GRID TRACE POLICY"))

                P2PRegistry.remove(visibleRemoteRuntime.grid)
                val rejectionRemoteRuntime = DistributionGridTransportLiveBedrockFixtures.createRemoteRuntime("trace-policy-rejection", "http", traceConfig, resolvedModelId, DistributionGridTransportLiveBedrockFixtures.buildVisibleDebugTracePolicy(), Transport.Http, remoteTransportAddress, recordsPromptContent = true)
                val rejectionSenderRuntime = DistributionGridTransportLiveBedrockFixtures.createSenderRuntime("trace-policy-rejection", "http", traceConfig, resolvedModelId, DistributionGridTransportLiveBedrockFixtures.buildVisibleDebugTracePolicy(), remotePeerId)
                rejectionSenderRuntime.grid.setRouter(
                    DistributionGridTransportLiveBedrockFixtures.FixedDirectiveRoleContainer(
                        DistributionGridDirective(
                            kind = DistributionGridDirectiveKind.HAND_OFF_TO_PEER,
                            targetPeerId = remotePeerId,
                            notes = "Force the rejection scenario through remote handoff."
                        )
                    )
                )
                rejectionSenderRuntime.grid.setBeforePeerDispatchHook { envelope ->
                    envelope.tracePolicy.allowTracing = false
                    envelope.tracePolicy.allowTracePersistence = false
                    envelope.tracePolicy.requireRedaction = true
                    envelope.tracePolicy.rejectNonCompliantNodes = true
                    envelope
                }

                rejectionRemoteRuntime.grid.init()
                P2PRegistry.register(rejectionRemoteRuntime.grid)
                rejectionSenderRuntime.grid.addPeerDescriptor(rejectionRemoteRuntime.grid.getP2pDescription()!!.deepCopy())
                rejectionSenderRuntime.grid.init()

                val failureResult = rejectionSenderRuntime.grid.execute(MultimodalContent(text = serialize(
                    DistributionGridTransportLiveBedrockFixtures.LiveGridTaskRequest(
                        caseId = "distribution-grid-http-live-bedrock-rejection",
                        executionMode = DistributionGridTransportLiveBedrockFixtures.LiveGridExecutionMode.REMOTE_SPECIALIST,
                        targetPeerId = remotePeerId,
                        task = "Attempt a remote specialist handoff that should be rejected by trace policy."
                    )
                )))

                val failure = failureResult.metadata["distributionGridFailure"] as? DistributionGridFailure ?: error("Policy rejection scenario should expose a DistributionGridFailure.")
                assertTrue(failureResult.terminatePipeline)
                assertTrue(
                    failure.kind == DistributionGridFailureKind.POLICY_REJECTED ||
                        failure.kind == DistributionGridFailureKind.ROUTING_FAILURE,
                    "Trace-policy rejection should surface as a policy or routing failure. Actual: ${failure.kind}"
                )
                assertTrue(
                    failure.reason.contains("trace", ignoreCase = true) ||
                        failure.reason.contains("policy", ignoreCase = true) ||
                        failure.reason.contains("privacy", ignoreCase = true),
                    "Trace-policy rejection should include a policy-related reason. Actual: ${failure.reason}"
                )

                val rejectionSenderArtifacts = DistributionGridTransportLiveBedrockFixtures.saveSideTraceArtifacts("http", rejectionSenderRuntime, failureResult)
                val rejectionRemoteArtifacts = DistributionGridTransportLiveBedrockFixtures.saveSideTraceArtifacts("http", rejectionRemoteRuntime, failureResult)

                assertTrue(rejectionSenderArtifacts.gridHtml.contains("POLICY_REJECTED") && rejectionSenderArtifacts.gridHtml.contains("conflicts with the current trace or privacy policy"))
                assertTrue(rejectionSenderArtifacts.gridHtml.contains("tracePolicyRequireRedaction"))
                assertTrue(rejectionRemoteArtifacts.gridHtml.contains("Grid State"))
            }
            finally
            {
                P2PRegistry.remove(visibleRemoteRuntime.grid)
                server.stop(0, 0)
                PipeTracer.disable()
            }
        }
    }
}

class DistributionGridStdioLiveBedrockIntegrationTest
{
    @Test
    fun liveStdioDistributionGridExecutesLocallyThenHandsOffRemotelyWithStrictTypedJson()
    {
        TestCredentialUtils.requireAwsCredentials()

        runBlocking(Dispatchers.IO) {
            bedrockEnv.loadInferenceConfig()
            PipeTracer.enable()

            val traceConfig = DistributionGridTransportLiveBedrockFixtures.buildDebugTraceConfig()
            val resolvedModelId = DistributionGridTransportLiveBedrockFixtures.resolveLiveModelId()
            val traceRoot = DistributionGridTransportLiveBedrockFixtures.STDIO_TRACE_ROOT
            val scenarioName = "strict-execution"
            val launcherScript = DistributionGridTransportLiveBedrockFixtures.buildStdioLauncherScript(scenarioName, traceRoot)
            val remoteSession = StdioSessionManager.createSession(
                command = launcherScript.absolutePath,
                args = emptyList(),
                ownerId = "p2p_caller"
            )
            val remotePeerId = "${Transport.Stdio}::${launcherScript.absolutePath}"
            val senderRuntime = DistributionGridTransportLiveBedrockFixtures.createSenderRuntime(scenarioName, "stdio", traceConfig, resolvedModelId, DistributionGridTransportLiveBedrockFixtures.buildVisibleDebugTracePolicy(), remotePeerId)

            try
            {
                senderRuntime.grid.addPeerDescriptor(
                    DistributionGridTransportLiveBedrockFixtures.buildPersistentStdioGridDescriptor(
                        nodeId = "live-remote-grid",
                        transportAddress = launcherScript.absolutePath,
                        defaultTracePolicy = DistributionGridTransportLiveBedrockFixtures.buildVisibleDebugTracePolicy(),
                        sessionId = remoteSession.sessionId
                    )
                )
                senderRuntime.grid.init()

                val results = DistributionGridTransportLiveBedrockFixtures.executeStrictScenario(senderRuntime, remotePeerId, "distribution-grid-stdio-live-bedrock-strict")
                assertTrue(results.localResult.passPipeline)
                assertTrue(results.remoteResult.passPipeline)

                val senderArtifacts = DistributionGridTransportLiveBedrockFixtures.saveSideTraceArtifacts("stdio", senderRuntime, results.remoteResult)
                val remoteGridHtml = File(TPipeConfig.getTraceDir(), "Library/distribution-grid-live-bedrock-stdio/manual-core-qwen-two-node/$scenarioName/remote/grid.html").readText()

                assertTrue(senderArtifacts.gridHtml.contains(results.localPayload.answer))
                assertTrue(senderArtifacts.gridHtml.contains(results.remotePayload.answer))
                assertEquals("live-remote-grid", senderArtifacts.summary.finalNodeId)
                assertTrue(remoteGridHtml.contains("live-remote-grid"))
                assertTrue(remoteGridHtml.contains("Grid State"))
                assertEquals(2, senderRuntime.router.requestCount)
                assertEquals(1, senderRuntime.worker.requestCount)
            }
            finally
            {
                StdioSessionManager.closeSession(remoteSession.sessionId)
                launcherScript.delete()
                PipeTracer.disable()
            }
        }
    }

    @Test
    fun liveStdioDistributionGridTracePolicyShowsVisibleOutputAndRejectsNonCompliantPeer()
    {
        TestCredentialUtils.requireAwsCredentials()

        runBlocking(Dispatchers.IO) {
            bedrockEnv.loadInferenceConfig()
            PipeTracer.enable()

            val traceConfig = DistributionGridTransportLiveBedrockFixtures.buildDebugTraceConfig()
            val resolvedModelId = DistributionGridTransportLiveBedrockFixtures.resolveLiveModelId()
            val scenarioName = "trace-policy-visible"
            val traceRoot = DistributionGridTransportLiveBedrockFixtures.STDIO_TRACE_ROOT
            val launcherScript = DistributionGridTransportLiveBedrockFixtures.buildStdioLauncherScript(scenarioName, traceRoot)
            val remoteSession = StdioSessionManager.createSession(
                command = launcherScript.absolutePath,
                args = emptyList(),
                ownerId = "p2p_caller"
            )
            val remotePeerId = "${Transport.Stdio}::${launcherScript.absolutePath}"
            val senderRuntime = DistributionGridTransportLiveBedrockFixtures.createSenderRuntime(scenarioName, "stdio", traceConfig, resolvedModelId, DistributionGridTransportLiveBedrockFixtures.buildVisibleDebugTracePolicy(), remotePeerId)

            try
            {
                senderRuntime.grid.addPeerDescriptor(
                    DistributionGridTransportLiveBedrockFixtures.buildPersistentStdioGridDescriptor(
                        nodeId = "live-remote-grid",
                        transportAddress = launcherScript.absolutePath,
                        defaultTracePolicy = DistributionGridTransportLiveBedrockFixtures.buildVisibleDebugTracePolicy(),
                        sessionId = remoteSession.sessionId
                    )
                )
                senderRuntime.grid.init()

                val visibleResults = DistributionGridTransportLiveBedrockFixtures.executeStrictScenario(senderRuntime, remotePeerId, "distribution-grid-stdio-live-bedrock-visible")
                val visibleArtifacts = DistributionGridTransportLiveBedrockFixtures.saveSideTraceArtifacts("stdio", senderRuntime, visibleResults.remoteResult)
                val visibleRemoteHtml = File(TPipeConfig.getTraceDir(), "Library/distribution-grid-live-bedrock-stdio/manual-core-qwen-two-node/$scenarioName/remote/grid.html").readText()
                assertTrue(visibleArtifacts.gridHtml.contains(visibleResults.localPayload.answer))
                assertTrue(visibleArtifacts.gridHtml.contains(visibleResults.remotePayload.answer))
                assertFalse(visibleArtifacts.gridHtml.contains("REDACTED BY DISTRIBUTION GRID TRACE POLICY"))
                assertTrue(visibleRemoteHtml.contains("live-remote-grid"))

                val rejectionLauncherScript = DistributionGridTransportLiveBedrockFixtures.buildStdioLauncherScript("trace-policy-rejection", traceRoot)
                val rejectionRemoteSession = StdioSessionManager.createSession(
                    command = rejectionLauncherScript.absolutePath,
                    args = emptyList(),
                    ownerId = "p2p_caller"
                )
                val rejectionRemotePeerId = "${Transport.Stdio}::${rejectionLauncherScript.absolutePath}"
                val rejectionSenderRuntime = DistributionGridTransportLiveBedrockFixtures.createSenderRuntime("trace-policy-rejection", "stdio", traceConfig, resolvedModelId, DistributionGridTransportLiveBedrockFixtures.buildVisibleDebugTracePolicy(), rejectionRemotePeerId)
                rejectionSenderRuntime.grid.setRouter(
                    DistributionGridTransportLiveBedrockFixtures.FixedDirectiveRoleContainer(
                        DistributionGridDirective(
                            kind = DistributionGridDirectiveKind.HAND_OFF_TO_PEER,
                            targetPeerId = rejectionRemotePeerId,
                            notes = "Force the rejection scenario through remote handoff."
                        )
                    )
                )
                rejectionSenderRuntime.grid.setBeforePeerDispatchHook { envelope ->
                    envelope.tracePolicy.allowTracing = false
                    envelope.tracePolicy.allowTracePersistence = false
                    envelope.tracePolicy.requireRedaction = true
                    envelope.tracePolicy.rejectNonCompliantNodes = true
                    envelope
                }
                rejectionSenderRuntime.grid.addPeerDescriptor(
                    DistributionGridTransportLiveBedrockFixtures.buildPersistentStdioGridDescriptor(
                        nodeId = "live-remote-grid",
                        transportAddress = rejectionLauncherScript.absolutePath,
                        defaultTracePolicy = DistributionGridTransportLiveBedrockFixtures.buildVisibleDebugTracePolicy(),
                        sessionId = rejectionRemoteSession.sessionId,
                        recordsPromptContent = true
                    )
                )
                rejectionSenderRuntime.grid.init()

                val failureResult = rejectionSenderRuntime.grid.execute(MultimodalContent(text = serialize(
                    DistributionGridTransportLiveBedrockFixtures.LiveGridTaskRequest(
                        caseId = "distribution-grid-stdio-live-bedrock-rejection",
                        executionMode = DistributionGridTransportLiveBedrockFixtures.LiveGridExecutionMode.REMOTE_SPECIALIST,
                        targetPeerId = rejectionRemotePeerId,
                        task = "Attempt a remote specialist handoff that should be rejected by trace policy."
                    )
                )))

                val failure = failureResult.metadata["distributionGridFailure"] as? DistributionGridFailure ?: error("Policy rejection scenario should expose a DistributionGridFailure.")
                assertTrue(failureResult.terminatePipeline)
                assertEquals(DistributionGridFailureKind.POLICY_REJECTED, failure.kind)

                val rejectionArtifacts = DistributionGridTransportLiveBedrockFixtures.saveSideTraceArtifacts("stdio", rejectionSenderRuntime, failureResult)
                assertTrue(rejectionArtifacts.gridHtml.contains("POLICY_REJECTED") && rejectionArtifacts.gridHtml.contains("conflicts with the current trace or privacy policy"))
                assertTrue(rejectionArtifacts.gridHtml.contains("tracePolicyRequireRedaction"))
                StdioSessionManager.closeSession(rejectionRemoteSession.sessionId)
                rejectionLauncherScript.delete()
            }
            finally
            {
                StdioSessionManager.closeSession(remoteSession.sessionId)
                launcherScript.delete()
                PipeTracer.disable()
            }
        }
    }
}

object DistributionGridTransportStdioLauncher
{
    @JvmStatic
    fun main(args: Array<String>)
    {
        runBlocking(Dispatchers.IO) {
            val traceRoot = args.getOrNull(0)?.takeIf { it.isNotBlank() } ?: error("Trace root argument is required.")
            val scenarioName = args.getOrNull(1)?.takeIf { it.isNotBlank() } ?: error("Scenario name argument is required.")
            val transportAddress = args.getOrNull(2)?.takeIf { it.isNotBlank() } ?: error("Transport address argument is required.")

            bedrockEnv.loadInferenceConfig()
            PipeTracer.enable()

            val traceConfig = DistributionGridTransportLiveBedrockFixtures.buildDebugTraceConfig()
            val resolvedModelId = DistributionGridTransportLiveBedrockFixtures.resolveLiveModelId()
            val remoteRuntime = DistributionGridTransportLiveBedrockFixtures.createRemoteRuntime(
                scenarioName = scenarioName,
                transportLabel = "stdio",
                traceConfig = traceConfig,
                modelId = resolvedModelId,
                remoteTracePolicy = DistributionGridTransportLiveBedrockFixtures.buildVisibleDebugTracePolicy(),
                transportMethod = Transport.Stdio,
                transportAddress = transportAddress,
                recordsPromptContent = true
            )

            try
            {
                val responseOut = System.out
                System.setOut(PrintStream(FileOutputStream(FileDescriptor.err), true))
                remoteRuntime.grid.init()
                P2PRegistry.register(remoteRuntime.grid)
                val scanner = Scanner(System.`in`)
                while(true)
                {
                    val input = readNextRequestJson(scanner) ?: break
                    val request = deserialize<P2PRequest>(input, useRepair = false)
                    val response = if(request != null)
                    {
                        P2PRegistry.executeP2pRequest(request)
                    }
                    else
                    {
                        P2PResponse().apply {
                            rejection = com.TTT.P2P.P2PRejection(com.TTT.P2P.P2PError.transport, "Failed to deserialize P2PRequest from stdin.")
                        }
                    }
                    responseOut.println(DistributionGridTransportLiveBedrockFixtures.serializeCompact(response))
                    responseOut.flush()
                }
            }
            finally
            {
                val traceDir = File(TPipeConfig.getTraceDir(), "$traceRoot/$scenarioName/remote")
                traceDir.mkdirs()
                val summary = DistributionGridTransportLiveBedrockFixtures.LiveGridRuntimeSummary(
                    scenarioName = scenarioName,
                    sideLabel = "remote",
                    routerRequestCount = remoteRuntime.router.requestCount,
                    workerRequestCount = remoteRuntime.worker.requestCount,
                    finalNodeId = "",
                    hopCount = 0,
                    answer = "",
                    failureKind = "",
                    failureReason = ""
                )
                DistributionGridTransportLiveBedrockFixtures.writeTraceFile(File(traceDir, "grid.html"), remoteRuntime.grid.getTraceReport(TraceFormat.HTML), "stdio remote grid")
                DistributionGridTransportLiveBedrockFixtures.writeTraceFile(File(traceDir, "router-pipeline.html"), remoteRuntime.router.pipeline.getTraceReport(TraceFormat.HTML), "stdio remote router pipeline")
                DistributionGridTransportLiveBedrockFixtures.writeTraceFile(File(traceDir, "worker-pipeline.html"), remoteRuntime.worker.pipeline.getTraceReport(TraceFormat.HTML), "stdio remote worker pipeline")
                writeStringToFile(File(traceDir, "summary.json").absolutePath, serialize(summary))
                P2PRegistry.remove(remoteRuntime.grid)
                PipeTracer.disable()
            }
        }
    }

    private fun readNextRequestJson(scanner: Scanner): String?
    {
        if(!scanner.hasNextLine())
        {
            return null
        }

        val inputBuilder = StringBuilder()
        var braceBalance = 0
        var seenStart = false
        while(scanner.hasNextLine())
        {
            val line = scanner.nextLine()
            inputBuilder.appendLine(line)
            seenStart = true
            braceBalance += line.count { it == '{' }
            braceBalance -= line.count { it == '}' }
            if(seenStart && braceBalance <= 0)
            {
                break
            }
        }

        return inputBuilder.toString().trim().takeIf { it.isNotBlank() }
    }
}
