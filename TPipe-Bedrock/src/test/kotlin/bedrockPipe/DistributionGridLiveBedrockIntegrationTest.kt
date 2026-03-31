package bedrockPipe

import com.TTT.Config.TPipeConfig
import com.TTT.Debug.PipeTracer
import com.TTT.Debug.TraceConfig
import com.TTT.Debug.TraceDetailLevel
import com.TTT.Debug.TraceEventType
import com.TTT.Debug.TraceFormat
import com.TTT.Enums.ProviderName
import com.TTT.P2P.ContextProtocol
import com.TTT.P2P.P2PDescriptor
import com.TTT.P2P.P2PInterface
import com.TTT.P2P.P2PRegistry
import com.TTT.P2P.P2PRequest
import com.TTT.P2P.P2PRequirements
import com.TTT.P2P.P2PResponse
import com.TTT.P2P.P2PTransport
import com.TTT.P2P.SupportedContentTypes
import com.TTT.Pipe.MultimodalContent
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
import com.TTT.Pipeline.distributionGrid
import com.TTT.Pipeline.Pipeline
import com.TTT.PipeContextProtocol.Transport
import com.TTT.Util.deepCopy
import com.TTT.Util.deserialize
import com.TTT.Util.serialize
import com.TTT.Util.writeStringToFile
import env.bedrockEnv
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

private const val GRID_DIRECTIVE_METADATA_KEY = "distributionGridDirective"
private const val QWEN_30B_MODEL_ID = "qwen.qwen3-coder-30b-a3b-v1:0"
private const val QWEN_30B_REGION = "us-west-2"
private const val TRACE_SUBDIRECTORY = "Library/distribution-grid-live-bedrock/manual-core-qwen-two-node"
private const val DSL_TRACE_SUBDIRECTORY = "Library/distribution-grid-dsl-live-bedrock/manual-core-qwen-two-node"

@Serializable
private enum class LiveGridExecutionMode
{
    LOCAL_ONLY,
    REMOTE_SPECIALIST
}

@Serializable
private data class LiveGridTaskRequest(
    val caseId: String,
    val executionMode: LiveGridExecutionMode,
    val targetPeerId: String = "",
    val task: String
)

@Serializable
private data class LiveGridWorkerResult(
    val executingNode: String,
    val executionMode: String,
    val specialty: String,
    val answer: String,
    val confidenceBand: String
)

@Serializable
private data class LiveGridDirectiveOutput(
    val directive: DistributionGridDirective
)

@Serializable
private data class LiveGridWorkerOutput(
    val result: LiveGridWorkerResult
)

private data class LiveGridExecutionResults(
    val localResult: MultimodalContent,
    val remoteResult: MultimodalContent,
    val localPayload: LiveGridWorkerResult,
    val remotePayload: LiveGridWorkerResult
)

private data class LiveGridRuntime(
    val scenarioName: String,
    val senderGrid: DistributionGrid,
    val remoteGrid: DistributionGrid,
    val senderRouter: LiveBedrockRoleContainer,
    val senderWorker: LiveBedrockRoleContainer,
    val remoteRouter: LiveBedrockRoleContainer,
    val remoteWorker: LiveBedrockRoleContainer,
    val remotePeerId: String
)

private data class LiveGridDslRuntime(
    val scenarioName: String,
    val senderGrid: DistributionGrid,
    val remoteGrid: DistributionGrid,
    val senderRouter: LiveBedrockRoleContainer,
    val senderWorker: LiveBedrockRoleContainer,
    val remoteRouter: LiveBedrockRoleContainer,
    val remoteWorker: LiveBedrockRoleContainer,
    val remotePeerId: String
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

private sealed class StrictRoleAdapter
{
    abstract fun adapt(output: MultimodalContent, original: MultimodalContent): MultimodalContent

    data class SenderRouter(
        val expectedRemotePeerId: String
    ) : StrictRoleAdapter()
    {
        override fun adapt(output: MultimodalContent, original: MultimodalContent): MultimodalContent
        {
            val taskRequest = deserializeStrict<LiveGridTaskRequest>(original.text, "sender router task request")
            val directive = deserializeStrict<LiveGridDirectiveOutput>(output.text, "sender router output").directive

            when(taskRequest.executionMode)
            {
                LiveGridExecutionMode.LOCAL_ONLY ->
                {
                    require(directive.kind == DistributionGridDirectiveKind.RUN_LOCAL_WORKER) {
                        "Sender router must choose RUN_LOCAL_WORKER for LOCAL_ONLY tasks."
                    }
                }

                LiveGridExecutionMode.REMOTE_SPECIALIST ->
                {
                    require(directive.kind == DistributionGridDirectiveKind.HAND_OFF_TO_PEER) {
                        "Sender router must choose HAND_OFF_TO_PEER for REMOTE_SPECIALIST tasks."
                    }
                    require(directive.targetPeerId == expectedRemotePeerId) {
                        "Sender router must return the exact remote peer id '$expectedRemotePeerId'."
                    }
                }
            }

            return original.deepCopy().apply {
                metadata[GRID_DIRECTIVE_METADATA_KEY] = directive.deepCopy()
            }
        }
    }

    object RemoteRouter : StrictRoleAdapter()
    {
        override fun adapt(output: MultimodalContent, original: MultimodalContent): MultimodalContent
        {
            val directive = deserializeStrict<LiveGridDirectiveOutput>(output.text, "remote router output").directive
            require(directive.kind == DistributionGridDirectiveKind.RUN_LOCAL_WORKER) {
                "Remote router must choose RUN_LOCAL_WORKER in the live handoff test."
            }
            return original.deepCopy().apply {
                metadata[GRID_DIRECTIVE_METADATA_KEY] = directive.deepCopy()
            }
        }
    }

    data class Worker(
        val expectedNodeId: String,
        val expectedExecutionMode: LiveGridExecutionMode,
        val expectedSpecialty: String
    ) : StrictRoleAdapter()
    {
        override fun adapt(output: MultimodalContent, original: MultimodalContent): MultimodalContent
        {
            val taskRequest = deserializeStrict<LiveGridTaskRequest>(original.text, "worker task request")
            val parsed = deserializeStrict<LiveGridWorkerOutput>(output.text, "worker output").result

            require(parsed.executingNode == expectedNodeId) {
                "Worker should identify executingNode as '$expectedNodeId'."
            }
            require(parsed.executionMode == expectedExecutionMode.name) {
                "Worker should identify executionMode as '${expectedExecutionMode.name}'."
            }
            require(parsed.specialty == expectedSpecialty) {
                "Worker should identify specialty as '$expectedSpecialty'."
            }
            require(parsed.answer.isNotBlank()) {
                "Worker answer should not be blank for task ${taskRequest.caseId}."
            }

            return MultimodalContent(text = serialize(parsed)).apply {
                modelReasoning = output.text.take(256)
            }
        }
    }
}

private class LiveBedrockRoleContainer(
    private val roleName: String,
    val pipeline: Pipeline,
    private val adapter: StrictRoleAdapter
) : P2PInterface
{
    private var descriptor: P2PDescriptor? = null
    private var requirements: P2PRequirements? = null
    private var transport: P2PTransport? = null
    private var containerRef: Any? = null

    private val _requestCount = AtomicInteger(0)
    val requestCount: Int get() = _requestCount.get()

    override fun setP2pDescription(description: P2PDescriptor)
    {
        descriptor = description
        pipeline.setP2pDescription(description)
    }

    override fun getP2pDescription(): P2PDescriptor?
    {
        return descriptor ?: pipeline.getP2pDescription()
    }

    override fun setP2pTransport(transport: P2PTransport)
    {
        this.transport = transport
        pipeline.setP2pTransport(transport)
    }

    override fun getP2pTransport(): P2PTransport?
    {
        return transport ?: pipeline.getP2pTransport()
    }

    override fun setP2pRequirements(requirements: P2PRequirements)
    {
        this.requirements = requirements
        pipeline.setP2pRequirements(requirements)
    }

    override fun getP2pRequirements(): P2PRequirements?
    {
        return requirements ?: pipeline.getP2pRequirements()
    }

    override fun getContainerObject(): Any?
    {
        return containerRef ?: pipeline.getContainerObject()
    }

    override fun setContainerObject(container: Any)
    {
        containerRef = container
        pipeline.setContainerObject(container)
    }

    override fun getPipelinesFromInterface(): List<Pipeline>
    {
        return listOf(pipeline)
    }

    override suspend fun executeLocal(content: MultimodalContent): MultimodalContent
    {
        _requestCount.incrementAndGet()
        val original = content.deepCopy()
        val output = pipeline.execute(content)
        return adapter.adapt(output, original)
    }

    override suspend fun executeP2PRequest(request: P2PRequest): P2PResponse?
    {
        _requestCount.incrementAndGet()
        val response = pipeline.executeP2PRequest(request) ?: return null
        val output = response.output ?: return response
        val normalized = adapter.adapt(output, request.prompt.deepCopy())
        return P2PResponse(output = normalized)
    }
}

private class FixedDirectiveRoleContainer(
    private val directive: DistributionGridDirective
) : P2PInterface
{
    private var descriptor: P2PDescriptor? = null
    private var requirements: P2PRequirements? = null
    private var transport: P2PTransport? = null
    private var containerRef: Any? = null

    override fun setP2pDescription(description: P2PDescriptor)
    {
        descriptor = description
    }

    override fun getP2pDescription(): P2PDescriptor?
    {
        return descriptor
    }

    override fun setP2pTransport(transport: P2PTransport)
    {
        this.transport = transport
    }

    override fun getP2pTransport(): P2PTransport?
    {
        return transport
    }

    override fun setP2pRequirements(requirements: P2PRequirements)
    {
        this.requirements = requirements
    }

    override fun getP2pRequirements(): P2PRequirements?
    {
        return requirements
    }

    override fun getContainerObject(): Any?
    {
        return containerRef
    }

    override fun setContainerObject(container: Any)
    {
        containerRef = container
    }

    override fun getPipelinesFromInterface(): List<Pipeline>
    {
        return emptyList()
    }

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

/**
 * Live DistributionGrid regression coverage backed by real Qwen Bedrock pipelines.
 *
 * The live test uses TPipe's official structured-output flow end to end: request models are serialized with
 * `serialize(...)`, responses are parsed with `deserialize(...)`, and invalid JSON fails the test immediately.
 */
class DistributionGridLiveBedrockIntegrationTest
{
    @Test
    fun liveBedrockDistributionGridExecutesLocallyThenHandsOffRemotelyWithStrictTypedJson()
    {
        TestCredentialUtils.requireAwsCredentials()

        runBlocking(Dispatchers.IO) {
            bedrockEnv.loadInferenceConfig()
            PipeTracer.enable()

            val traceConfig = buildDebugTraceConfig()
            val resolvedModelId = resolveLiveModelId()
            var runtime: LiveGridRuntime? = null

            try
            {
                runtime = createRuntime(
                    scenarioName = "strict-execution",
                    traceConfig = traceConfig,
                    modelId = resolvedModelId,
                    senderTracePolicy = buildVisibleDebugTracePolicy(),
                    remoteTracePolicy = buildVisibleDebugTracePolicy()
                )

                startRuntime(runtime)
                val results = executeStrictScenario(runtime, "distribution-grid-live-bedrock-strict")

                assertTrue(results.localResult.passPipeline)
                assertTrue(results.remoteResult.passPipeline)
                saveTraceArtifacts(runtime, results)
            }
            finally
            {
                runtime?.let {
                    runCatching { saveTraceArtifacts(it) }
                    shutdownRuntime(it)
                }
                PipeTracer.disable()
            }
        }
    }

    @Test
    fun liveBedrockDistributionGridTracePolicyShowsVisibleOutputAndRejectsNonCompliantPeer()
    {
        TestCredentialUtils.requireAwsCredentials()

        runBlocking(Dispatchers.IO) {
            bedrockEnv.loadInferenceConfig()
            PipeTracer.enable()

            val traceConfig = buildDebugTraceConfig()
            val resolvedModelId = resolveLiveModelId()
            var visibleRuntime: LiveGridRuntime? = null
            var rejectionRuntime: LiveGridRuntime? = null

            try
            {
                visibleRuntime = createRuntime(
                    scenarioName = "trace-policy-visible",
                    traceConfig = traceConfig,
                    modelId = resolvedModelId,
                    senderTracePolicy = buildVisibleDebugTracePolicy(),
                    remoteTracePolicy = buildVisibleDebugTracePolicy()
                )
                startRuntime(visibleRuntime)
                val visibleResults = executeStrictScenario(visibleRuntime, "distribution-grid-live-bedrock-visible")
                val visibleArtifacts = saveTraceArtifacts(visibleRuntime, visibleResults)

                assertTrue(
                    visibleArtifacts.senderGridHtml.contains(visibleResults.localPayload.answer),
                    "Sender grid trace should show the sender worker answer when debug tracing is visible."
                )
                assertTrue(
                    visibleArtifacts.senderGridHtml.contains(visibleResults.remotePayload.answer),
                    "Sender grid trace should show the remote final answer when debug tracing is visible."
                )
                assertTrue(
                    visibleArtifacts.remoteGridHtml.contains(visibleResults.remotePayload.answer),
                    "Remote grid trace should show the remote worker answer when debug tracing is visible."
                )
                assertFalse(
                    visibleArtifacts.senderGridHtml.contains("REDACTED BY DISTRIBUTION GRID TRACE POLICY"),
                    "Non-redacted debug tracing should not redact sender grid output."
                )
                assertFalse(
                    visibleArtifacts.remoteGridHtml.contains("REDACTED BY DISTRIBUTION GRID TRACE POLICY"),
                    "Non-redacted debug tracing should not redact remote grid output."
                )

                shutdownRuntime(visibleRuntime)
                visibleRuntime = null

                rejectionRuntime = createRuntime(
                    scenarioName = "trace-policy-rejection",
                    traceConfig = traceConfig,
                    modelId = resolvedModelId,
                    senderTracePolicy = buildVisibleDebugTracePolicy(),
                    remoteTracePolicy = buildVisibleDebugTracePolicy(),
                    remoteRecordsPromptContent = true
                )
                rejectionRuntime.senderGrid.setRouter(
                    FixedDirectiveRoleContainer(
                        DistributionGridDirective(
                            kind = DistributionGridDirectiveKind.HAND_OFF_TO_PEER,
                            targetPeerId = rejectionRuntime.remotePeerId,
                            notes = "Force the rejection scenario through remote handoff."
                        )
                    )
                )
                rejectionRuntime.senderGrid.setBeforePeerDispatchHook { envelope ->
                    envelope.tracePolicy.allowTracing = false
                    envelope.tracePolicy.allowTracePersistence = false
                    envelope.tracePolicy.requireRedaction = true
                    envelope.tracePolicy.rejectNonCompliantNodes = true
                    envelope
                }
                startRuntime(rejectionRuntime)

                val failureResult = rejectionRuntime.senderGrid.execute(
                    MultimodalContent(
                        text = serialize(
                            LiveGridTaskRequest(
                                caseId = "distribution-grid-live-bedrock-rejection",
                                executionMode = LiveGridExecutionMode.REMOTE_SPECIALIST,
                                targetPeerId = rejectionRuntime.remotePeerId,
                                task = "Attempt a remote specialist handoff that should be rejected by trace policy."
                            )
                        )
                    )
                )

                val failure = failureResult.metadata["distributionGridFailure"] as? DistributionGridFailure
                    ?: error("Policy rejection scenario should expose a DistributionGridFailure.")
                assertTrue(failureResult.terminatePipeline)
                assertEquals(DistributionGridFailureKind.POLICY_REJECTED, failure.kind)

                val rejectionArtifacts = saveTraceArtifacts(rejectionRuntime)
                assertTrue(
                    rejectionArtifacts.senderGridHtml.contains("POLICY_REJECTED") &&
                        rejectionArtifacts.senderGridHtml.contains("conflicts with the current trace or privacy policy"),
                    "Sender grid trace should show the policy rejection reason."
                )
                assertTrue(
                    rejectionArtifacts.senderGridHtml.contains("tracePolicyRequireRedaction"),
                    "Sender grid trace should expose the effective trace policy metadata."
                )
            }
            finally
            {
                rejectionRuntime?.let {
                    runCatching { saveTraceArtifacts(it) }
                    shutdownRuntime(it)
                }
                visibleRuntime?.let {
                    runCatching { saveTraceArtifacts(it) }
                    shutdownRuntime(it)
                }
                PipeTracer.disable()
            }
        }
    }

    private suspend fun createRuntime(
        scenarioName: String,
        traceConfig: TraceConfig,
        modelId: String,
        senderTracePolicy: DistributionGridTracePolicy,
        remoteTracePolicy: DistributionGridTracePolicy,
        remoteRecordsPromptContent: Boolean = false,
        remoteRecordsInteractionContext: Boolean = false
    ): LiveGridRuntime
    {
        val remotePeerId = "${Transport.Tpipe}::live-remote-grid-address"

        val senderRouter = createLiveRoleContainer(
            roleName = "sender-router",
            traceConfig = traceConfig,
            pipeline = createSenderRouterPipeline(traceConfig, modelId),
            adapter = StrictRoleAdapter.SenderRouter(expectedRemotePeerId = remotePeerId)
        )
        val senderWorker = createLiveRoleContainer(
            roleName = "sender-worker",
            traceConfig = traceConfig,
            pipeline = createWorkerPipeline(
                roleName = "sender-worker",
                traceConfig = traceConfig,
                modelId = modelId,
                executingNode = "live-sender-grid",
                executionMode = LiveGridExecutionMode.LOCAL_ONLY,
                specialty = "sender-generalist"
            ),
            adapter = StrictRoleAdapter.Worker(
                expectedNodeId = "live-sender-grid",
                expectedExecutionMode = LiveGridExecutionMode.LOCAL_ONLY,
                expectedSpecialty = "sender-generalist"
            )
        )
        val remoteRouter = createLiveRoleContainer(
            roleName = "remote-router",
            traceConfig = traceConfig,
            pipeline = createRemoteRouterPipeline(traceConfig, modelId),
            adapter = StrictRoleAdapter.RemoteRouter
        )
        val remoteWorker = createLiveRoleContainer(
            roleName = "remote-worker",
            traceConfig = traceConfig,
            pipeline = createWorkerPipeline(
                roleName = "remote-worker",
                traceConfig = traceConfig,
                modelId = modelId,
                executingNode = "live-remote-grid",
                executionMode = LiveGridExecutionMode.REMOTE_SPECIALIST,
                specialty = "remote-specialist"
            ),
            adapter = StrictRoleAdapter.Worker(
                expectedNodeId = "live-remote-grid",
                expectedExecutionMode = LiveGridExecutionMode.REMOTE_SPECIALIST,
                expectedSpecialty = "remote-specialist"
            )
        )

        val senderGrid = createGrid(
            nodeId = "live-sender-grid",
            transportAddress = "live-sender-grid-address",
            router = senderRouter,
            worker = senderWorker,
            traceConfig = traceConfig,
            defaultTracePolicy = senderTracePolicy
        )
        val remoteGrid = createGrid(
            nodeId = "live-remote-grid",
            transportAddress = "live-remote-grid-address",
            router = remoteRouter,
            worker = remoteWorker,
            traceConfig = traceConfig,
            defaultTracePolicy = remoteTracePolicy,
            recordsPromptContent = remoteRecordsPromptContent,
            recordsInteractionContext = remoteRecordsInteractionContext
        )

        return LiveGridRuntime(
            scenarioName = scenarioName,
            senderGrid = senderGrid,
            remoteGrid = remoteGrid,
            senderRouter = senderRouter,
            senderWorker = senderWorker,
            remoteRouter = remoteRouter,
            remoteWorker = remoteWorker,
            remotePeerId = remotePeerId
        )
    }

    private suspend fun startRuntime(runtime: LiveGridRuntime)
    {
        runtime.remoteGrid.init()
        P2PRegistry.register(runtime.remoteGrid)
        runtime.senderGrid.addPeerDescriptor(runtime.remoteGrid.getP2pDescription()!!.deepCopy())
        runtime.senderGrid.init()
    }

    private fun shutdownRuntime(runtime: LiveGridRuntime)
    {
        P2PRegistry.remove(runtime.remoteGrid)
    }

    private suspend fun executeStrictScenario(
        runtime: LiveGridRuntime,
        caseIdPrefix: String
    ): LiveGridExecutionResults
    {
        val localTask = LiveGridTaskRequest(
            caseId = "$caseIdPrefix-local",
            executionMode = LiveGridExecutionMode.LOCAL_ONLY,
            task = "Handle this request locally and return only strict JSON for the sender-generalist role."
        )
        val localResult = runtime.senderGrid.execute(MultimodalContent(text = serialize(localTask)))
        assertGridExecutionSucceeded(localResult, "local sender execution")
        val localPayload = deserializeStrict<LiveGridWorkerResult>(localResult.text, "sender worker result")

        assertEquals("live-sender-grid", localPayload.executingNode)
        assertEquals(LiveGridExecutionMode.LOCAL_ONLY.name, localPayload.executionMode)
        assertEquals("sender-generalist", localPayload.specialty)
        assertEquals(1, runtime.senderRouter.requestCount)
        assertEquals(1, runtime.senderWorker.requestCount)
        assertEquals(0, runtime.remoteRouter.requestCount)
        assertEquals(0, runtime.remoteWorker.requestCount)

        val remoteTask = LiveGridTaskRequest(
            caseId = "$caseIdPrefix-remote",
            executionMode = LiveGridExecutionMode.REMOTE_SPECIALIST,
            targetPeerId = runtime.remotePeerId,
            task = "Delegate this specialist request to the remote node and return only strict JSON for the remote-specialist role."
        )
        val remoteResult = runtime.senderGrid.execute(MultimodalContent(text = serialize(remoteTask)))
        assertGridExecutionSucceeded(remoteResult, "remote specialist execution")
        val remotePayload = deserializeStrict<LiveGridWorkerResult>(remoteResult.text, "remote worker result")

        assertEquals("live-remote-grid", remotePayload.executingNode)
        assertEquals(LiveGridExecutionMode.REMOTE_SPECIALIST.name, remotePayload.executionMode)
        assertEquals("remote-specialist", remotePayload.specialty)

        val envelope = remoteResult.metadata["distributionGridEnvelope"] as? DistributionGridEnvelope
            ?: error("Remote result should contain a DistributionGridEnvelope.")
        val outcome = remoteResult.metadata["distributionGridOutcome"] as? DistributionGridOutcome
            ?: error("Remote result should contain a DistributionGridOutcome.")
        assertEquals("live-remote-grid", outcome.finalNodeId)
        assertTrue(outcome.hopCount >= 2, "Remote handoff should record at least sender and remote hops.")
        assertEquals(DistributionGridDirectiveKind.HAND_OFF_TO_PEER, envelope.hopHistory.first().routerAction)

        assertEquals(2, runtime.senderRouter.requestCount)
        assertEquals(1, runtime.senderWorker.requestCount)
        assertEquals(1, runtime.remoteRouter.requestCount)
        assertEquals(1, runtime.remoteWorker.requestCount)

        return LiveGridExecutionResults(
            localResult = localResult,
            remoteResult = remoteResult,
            localPayload = localPayload,
            remotePayload = remotePayload
        )
    }

    private fun assertGridExecutionSucceeded(
        result: MultimodalContent,
        label: String
    )
    {
        val failure = result.metadata["distributionGridFailure"] as? DistributionGridFailure
        assertTrue(
            result.passPipeline,
            "$label should complete successfully. Failure: ${failure?.kind}: ${failure?.reason}. Raw result: ${result.text}"
        )
        assertFalse(result.terminatePipeline, "$label should not terminate with a failure envelope.")
    }

    private suspend fun createGrid(
        nodeId: String,
        transportAddress: String,
        router: LiveBedrockRoleContainer,
        worker: LiveBedrockRoleContainer,
        traceConfig: TraceConfig,
        defaultTracePolicy: DistributionGridTracePolicy,
        recordsPromptContent: Boolean = false,
        recordsInteractionContext: Boolean = false
    ): DistributionGrid
    {
        val grid = DistributionGrid()
        grid.setP2pTransport(
            P2PTransport(
                transportMethod = Transport.Tpipe,
                transportAddress = transportAddress
            )
        )
        grid.setP2pDescription(
            buildGridDescriptor(
                nodeId = nodeId,
                transportAddress = transportAddress,
                defaultTracePolicy = defaultTracePolicy,
                recordsPromptContent = recordsPromptContent,
                recordsInteractionContext = recordsInteractionContext
            )
        )
        grid.setP2pRequirements(
            P2PRequirements(
                allowExternalConnections = true,
                acceptedContent = mutableListOf(SupportedContentTypes.text)
            )
        )
        grid.setRouter(router)
        grid.setWorker(worker)
        grid.enableTracing(traceConfig)
        return grid
    }

    private suspend fun createLiveRoleContainer(
        roleName: String,
        traceConfig: TraceConfig,
        pipeline: Pipeline,
        adapter: StrictRoleAdapter
    ): LiveBedrockRoleContainer
    {
        pipeline.setPipelineName(roleName)
        pipeline.enableTracing(traceConfig)
        pipeline.init(initPipes = true)
        return LiveBedrockRoleContainer(
            roleName = roleName,
            pipeline = pipeline,
            adapter = adapter
        )
    }

    private fun createSenderRouterPipeline(
        traceConfig: TraceConfig,
        modelId: String
    ): Pipeline
    {
        val localExample = LiveGridDirectiveOutput(
            directive = DistributionGridDirective(
                kind = DistributionGridDirectiveKind.RUN_LOCAL_WORKER,
                notes = "Execute locally."
            )
        )
        val remoteExample = LiveGridDirectiveOutput(
            directive = DistributionGridDirective(
                kind = DistributionGridDirectiveKind.HAND_OFF_TO_PEER,
                targetPeerId = "${Transport.Tpipe}::live-remote-grid-address",
                notes = "Delegate to the remote specialist."
            )
        )
        val pipe = createBaseBedrockPipe(
            pipeName = "distribution-grid-live-sender-router",
            modelId = modelId,
            traceConfig = traceConfig,
            maxTokens = 256
        )
        pipe.setSystemPrompt(
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
        pipe.setJsonOutput(remoteExample)
        pipe.requireJsonPromptInjection(true)

        return Pipeline().apply {
            add(pipe)
        }
    }

    private fun createRemoteRouterPipeline(
        traceConfig: TraceConfig,
        modelId: String
    ): Pipeline
    {
        val remoteRouterExample = LiveGridDirectiveOutput(
            directive = DistributionGridDirective(
                kind = DistributionGridDirectiveKind.RUN_LOCAL_WORKER,
                notes = "Remote node should run its local worker."
            )
        )
        val pipe = createBaseBedrockPipe(
            pipeName = "distribution-grid-live-remote-router",
            modelId = modelId,
            traceConfig = traceConfig,
            maxTokens = 256
        )
        pipe.setSystemPrompt(
            """
            You are the remote router for a live TPipe DistributionGrid integration test.
            The user prompt is a serialized LiveGridTaskRequest JSON object.
            Follow the configured JSON schema exactly.
            For every task in this test, choose the local worker path on the remote node.
            Never hand off again.
            Keep notes short and operational.
            """.trimIndent()
        )
        pipe.setJsonOutput(remoteRouterExample)
        pipe.requireJsonPromptInjection(true)

        return Pipeline().apply {
            add(pipe)
        }
    }

    private fun createWorkerPipeline(
        roleName: String,
        traceConfig: TraceConfig,
        modelId: String,
        executingNode: String,
        executionMode: LiveGridExecutionMode,
        specialty: String
    ): Pipeline
    {
        val pipe = createBaseBedrockPipe(
            pipeName = "distribution-grid-live-$roleName",
            modelId = modelId,
            traceConfig = traceConfig,
            maxTokens = 512
        )
        val workerExample = LiveGridWorkerOutput(
            result = LiveGridWorkerResult(
                executingNode = executingNode,
                executionMode = executionMode.name,
                specialty = specialty,
                answer = "Short task-specific answer.",
                confidenceBand = "HIGH"
            )
        )
        pipe.setSystemPrompt(
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
        pipe.setJsonOutput(workerExample)
        pipe.requireJsonPromptInjection(true)

        return Pipeline().apply {
            add(pipe)
        }
    }

    private fun createBaseBedrockPipe(
        pipeName: String,
        modelId: String,
        traceConfig: TraceConfig,
        maxTokens: Int
    ): BedrockMultimodalPipe
    {
        return BedrockMultimodalPipe().apply {
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

    private fun saveTraceArtifacts(
        runtime: LiveGridRuntime,
        results: LiveGridExecutionResults? = null
    ): TraceArtifacts
    {
        val traceDir = File(TPipeConfig.getTraceDir(), "$TRACE_SUBDIRECTORY/${runtime.scenarioName}")
        traceDir.mkdirs()

        val senderGridTrace = runtime.senderGrid.getTraceReport(TraceFormat.HTML)
        val remoteGridTrace = runtime.remoteGrid.getTraceReport(TraceFormat.HTML)
        val senderRouterTrace = runtime.senderRouter.pipeline.getTraceReport(TraceFormat.HTML)
        val senderWorkerTrace = runtime.senderWorker.pipeline.getTraceReport(TraceFormat.HTML)
        val remoteRouterTrace = runtime.remoteRouter.pipeline.getTraceReport(TraceFormat.HTML)
        val remoteWorkerTrace = runtime.remoteWorker.pipeline.getTraceReport(TraceFormat.HTML)

        writeTraceFile(File(traceDir, "sender-grid.html"), senderGridTrace, "sender grid")
        writeTraceFile(File(traceDir, "remote-grid.html"), remoteGridTrace, "remote grid")
        writeTraceFile(File(traceDir, "sender-router-pipeline.html"), senderRouterTrace, "sender router pipeline")
        writeTraceFile(File(traceDir, "sender-worker-pipeline.html"), senderWorkerTrace, "sender worker pipeline")
        writeTraceFile(File(traceDir, "remote-router-pipeline.html"), remoteRouterTrace, "remote router pipeline")
        writeTraceFile(File(traceDir, "remote-worker-pipeline.html"), remoteWorkerTrace, "remote worker pipeline")

        assertGridTrace(senderGridTrace, "sender grid", results)
        assertGridTrace(remoteGridTrace, "remote grid", results)
        if(results != null)
        {
            assertPipelineTrace(runtime.senderRouter, "sender router")
            assertPipelineTrace(runtime.senderWorker, "sender worker")
            assertPipelineTrace(runtime.remoteRouter, "remote router")
            assertPipelineTrace(runtime.remoteWorker, "remote worker")
        }

        return TraceArtifacts(
            senderGridHtml = senderGridTrace,
            remoteGridHtml = remoteGridTrace
        )
    }

    private fun writeTraceFile(
        file: File,
        content: String,
        label: String
    )
    {
        writeStringToFile(file.absolutePath, content)
        assertTrue(file.exists(), "$label trace file should exist at ${file.absolutePath}")
        assertTrue(file.length() > 0, "$label trace file should not be empty at ${file.absolutePath}")
        assertTrue(content.isNotBlank(), "$label trace report should not be blank.")
    }

    private fun assertGridTrace(
        html: String,
        label: String,
        results: LiveGridExecutionResults?
    )
    {
        assertTrue(html.contains("TPipe DistributionGrid Execution Analysis"), "$label trace should use the DistributionGrid visualizer heading.")
        assertTrue(html.contains("Grid State"), "$label trace should include the Grid State section.")
        assertTrue(html.contains("Grid Orchestration Flow"), "$label trace should include the orchestration section.")
        if(results != null)
        {
            assertTrue(
                html.contains(results.localPayload.executingNode) || html.contains(results.remotePayload.executingNode),
                "$label trace should identify the node that executed work."
            )
        }
    }

    private fun assertPipelineTrace(
        role: LiveBedrockRoleContainer,
        label: String
    )
    {
        val events = PipeTracer.getTrace(role.pipeline.getTraceId()).map { it.eventType }
        assertTrue(events.isNotEmpty(), "$label pipeline trace should record live events.")
        assertTrue(
            events.contains(TraceEventType.API_CALL_START) || events.contains(TraceEventType.API_CALL_SUCCESS),
            "$label pipeline trace should contain Bedrock API execution events."
        )
    }

    private fun resolveLiveModelId(): String
    {
        val inferenceProfile = bedrockEnv.getInferenceProfileId(QWEN_30B_MODEL_ID)
        return if(inferenceProfile.isNullOrBlank()) QWEN_30B_MODEL_ID else inferenceProfile
    }

    private fun buildGridDescriptor(
        nodeId: String,
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
                transportMethod = Transport.Tpipe,
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
                supportedTransports = mutableListOf(Transport.Tpipe),
                requiresHandshake = true,
                defaultTracePolicy = defaultTracePolicy,
                defaultRoutingPolicy = DistributionGridRoutingPolicy(),
                actsAsRegistry = false
            )
        )
    }

    private fun buildDebugTraceConfig(): TraceConfig
    {
        return TraceConfig(
            enabled = true,
            outputFormat = TraceFormat.HTML,
            detailLevel = TraceDetailLevel.DEBUG,
            includeContext = true,
            includeMetadata = true
        )
    }

    private fun buildVisibleDebugTracePolicy(): DistributionGridTracePolicy
    {
        return DistributionGridTracePolicy(
            allowTracing = true,
            allowTracePersistence = true,
            requireRedaction = false,
            rejectNonCompliantNodes = true
        )
    }

    private data class TraceArtifacts(
        val senderGridHtml: String,
        val remoteGridHtml: String
    )
}

/**
 * Live DSL DistributionGrid regression coverage backed by the same real Qwen Bedrock pipelines as the raw grid test.
 *
 * This class keeps the exact same scenario matrix as [DistributionGridLiveBedrockIntegrationTest], but constructs
 * the sender and remote grid shells through the DistributionGrid DSL so we prove the DSL path is production-ready.
 */
class DistributionGridDslLiveBedrockIntegrationTest
{
    @Test
    fun liveDslDistributionGridExecutesLocallyThenHandsOffRemotelyWithStrictTypedJson()
    {
        TestCredentialUtils.requireAwsCredentials()

        runBlocking(Dispatchers.IO) {
            bedrockEnv.loadInferenceConfig()
            PipeTracer.enable()

            val traceConfig = buildDebugTraceConfig()
            val resolvedModelId = resolveLiveModelId()
            var runtime: LiveGridDslRuntime? = null

            try
            {
                runtime = createRuntime(
                    scenarioName = "strict-execution",
                    traceConfig = traceConfig,
                    modelId = resolvedModelId,
                    senderTracePolicy = buildVisibleDebugTracePolicy(),
                    remoteTracePolicy = buildVisibleDebugTracePolicy()
                )

                startRuntime(runtime)
                val results = executeStrictScenario(runtime, "distribution-grid-dsl-live-bedrock-strict")

                assertTrue(results.localResult.passPipeline)
                assertTrue(results.remoteResult.passPipeline)
                saveTraceArtifacts(runtime, results)
            }
            finally
            {
                runtime?.let {
                    runCatching { saveTraceArtifacts(it) }
                    shutdownRuntime(it)
                }
                PipeTracer.disable()
            }
        }
    }

    @Test
    fun liveDslDistributionGridTracePolicyShowsVisibleOutputAndRejectsNonCompliantPeer()
    {
        TestCredentialUtils.requireAwsCredentials()

        runBlocking(Dispatchers.IO) {
            bedrockEnv.loadInferenceConfig()
            PipeTracer.enable()

            val traceConfig = buildDebugTraceConfig()
            val resolvedModelId = resolveLiveModelId()
            var visibleRuntime: LiveGridDslRuntime? = null
            var rejectionRuntime: LiveGridDslRuntime? = null

            try
            {
                visibleRuntime = createRuntime(
                    scenarioName = "trace-policy-visible",
                    traceConfig = traceConfig,
                    modelId = resolvedModelId,
                    senderTracePolicy = buildVisibleDebugTracePolicy(),
                    remoteTracePolicy = buildVisibleDebugTracePolicy()
                )
                startRuntime(visibleRuntime)
                val visibleResults = executeStrictScenario(visibleRuntime, "distribution-grid-dsl-live-bedrock-visible")
                val visibleArtifacts = saveTraceArtifacts(visibleRuntime, visibleResults)

                assertTrue(
                    visibleArtifacts.senderGridHtml.contains(visibleResults.localPayload.answer),
                    "Sender grid trace should show the sender worker answer when debug tracing is visible."
                )
                assertTrue(
                    visibleArtifacts.senderGridHtml.contains(visibleResults.remotePayload.answer),
                    "Sender grid trace should show the remote final answer when debug tracing is visible."
                )
                assertTrue(
                    visibleArtifacts.remoteGridHtml.contains(visibleResults.remotePayload.answer),
                    "Remote grid trace should show the remote worker answer when debug tracing is visible."
                )
                assertFalse(
                    visibleArtifacts.senderGridHtml.contains("REDACTED BY DISTRIBUTION GRID TRACE POLICY"),
                    "Non-redacted debug tracing should not redact sender grid output."
                )
                assertFalse(
                    visibleArtifacts.remoteGridHtml.contains("REDACTED BY DISTRIBUTION GRID TRACE POLICY"),
                    "Non-redacted debug tracing should not redact remote grid output."
                )

                shutdownRuntime(visibleRuntime)
                visibleRuntime = null

                rejectionRuntime = createRuntime(
                    scenarioName = "trace-policy-rejection",
                    traceConfig = traceConfig,
                    modelId = resolvedModelId,
                    senderTracePolicy = buildVisibleDebugTracePolicy(),
                    remoteTracePolicy = buildVisibleDebugTracePolicy(),
                    remoteRecordsPromptContent = true
                )
                rejectionRuntime.senderGrid.setRouter(
                    FixedDirectiveRoleContainer(
                        DistributionGridDirective(
                            kind = DistributionGridDirectiveKind.HAND_OFF_TO_PEER,
                            targetPeerId = rejectionRuntime.remotePeerId,
                            notes = "Force the rejection scenario through remote handoff."
                        )
                    )
                )
                rejectionRuntime.senderGrid.setBeforePeerDispatchHook { envelope ->
                    envelope.tracePolicy.allowTracing = false
                    envelope.tracePolicy.allowTracePersistence = false
                    envelope.tracePolicy.requireRedaction = true
                    envelope.tracePolicy.rejectNonCompliantNodes = true
                    envelope
                }
                startRuntime(rejectionRuntime)

                val failureResult = rejectionRuntime.senderGrid.execute(
                    MultimodalContent(
                        text = serialize(
                            LiveGridTaskRequest(
                                caseId = "distribution-grid-dsl-live-bedrock-rejection",
                                executionMode = LiveGridExecutionMode.REMOTE_SPECIALIST,
                                targetPeerId = rejectionRuntime.remotePeerId,
                                task = "Attempt a remote specialist handoff that should be rejected by trace policy."
                            )
                        )
                    )
                )

                val failure = failureResult.metadata["distributionGridFailure"] as? DistributionGridFailure
                    ?: error("Policy rejection scenario should expose a DistributionGridFailure.")
                assertTrue(failureResult.terminatePipeline)
                assertEquals(DistributionGridFailureKind.POLICY_REJECTED, failure.kind)

                val rejectionArtifacts = saveTraceArtifacts(rejectionRuntime)
                assertTrue(
                    rejectionArtifacts.senderGridHtml.contains("POLICY_REJECTED") &&
                        rejectionArtifacts.senderGridHtml.contains("conflicts with the current trace or privacy policy"),
                    "Sender grid trace should show the policy rejection reason."
                )
                assertTrue(
                    rejectionArtifacts.senderGridHtml.contains("tracePolicyRequireRedaction"),
                    "Sender grid trace should expose the effective trace policy metadata."
                )
            }
            finally
            {
                rejectionRuntime?.let {
                    runCatching { saveTraceArtifacts(it) }
                    shutdownRuntime(it)
                }
                visibleRuntime?.let {
                    runCatching { saveTraceArtifacts(it) }
                    shutdownRuntime(it)
                }
                PipeTracer.disable()
            }
        }
    }

    private suspend fun createRuntime(
        scenarioName: String,
        traceConfig: TraceConfig,
        modelId: String,
        senderTracePolicy: DistributionGridTracePolicy,
        remoteTracePolicy: DistributionGridTracePolicy,
        remoteRecordsPromptContent: Boolean = false,
        remoteRecordsInteractionContext: Boolean = false
    ): LiveGridDslRuntime
    {
        val remotePeerId = "${Transport.Tpipe}::live-remote-grid-address"

        val senderRouter = createLiveRoleContainer(
            roleName = "sender-router",
            traceConfig = traceConfig,
            pipeline = createSenderRouterPipeline(traceConfig, modelId),
            adapter = StrictRoleAdapter.SenderRouter(expectedRemotePeerId = remotePeerId)
        )
        val senderWorker = createLiveRoleContainer(
            roleName = "sender-worker",
            traceConfig = traceConfig,
            pipeline = createWorkerPipeline(
                roleName = "sender-worker",
                traceConfig = traceConfig,
                modelId = modelId,
                executingNode = "live-sender-grid",
                executionMode = LiveGridExecutionMode.LOCAL_ONLY,
                specialty = "sender-generalist"
            ),
            adapter = StrictRoleAdapter.Worker(
                expectedNodeId = "live-sender-grid",
                expectedExecutionMode = LiveGridExecutionMode.LOCAL_ONLY,
                expectedSpecialty = "sender-generalist"
            )
        )
        val remoteRouter = createLiveRoleContainer(
            roleName = "remote-router",
            traceConfig = traceConfig,
            pipeline = createRemoteRouterPipeline(traceConfig, modelId),
            adapter = StrictRoleAdapter.RemoteRouter
        )
        val remoteWorker = createLiveRoleContainer(
            roleName = "remote-worker",
            traceConfig = traceConfig,
            pipeline = createWorkerPipeline(
                roleName = "remote-worker",
                traceConfig = traceConfig,
                modelId = modelId,
                executingNode = "live-remote-grid",
                executionMode = LiveGridExecutionMode.REMOTE_SPECIALIST,
                specialty = "remote-specialist"
            ),
            adapter = StrictRoleAdapter.Worker(
                expectedNodeId = "live-remote-grid",
                expectedExecutionMode = LiveGridExecutionMode.REMOTE_SPECIALIST,
                expectedSpecialty = "remote-specialist"
            )
        )

        val senderGrid = createDslGrid(
            nodeId = "live-sender-grid",
            transportAddress = "live-sender-grid-address",
            router = senderRouter,
            worker = senderWorker,
            traceConfig = traceConfig,
            defaultTracePolicy = senderTracePolicy
        )
        val remoteGrid = createDslGrid(
            nodeId = "live-remote-grid",
            transportAddress = "live-remote-grid-address",
            router = remoteRouter,
            worker = remoteWorker,
            traceConfig = traceConfig,
            defaultTracePolicy = remoteTracePolicy,
            recordsPromptContent = remoteRecordsPromptContent,
            recordsInteractionContext = remoteRecordsInteractionContext
        )

        return LiveGridDslRuntime(
            scenarioName = scenarioName,
            senderGrid = senderGrid,
            remoteGrid = remoteGrid,
            senderRouter = senderRouter,
            senderWorker = senderWorker,
            remoteRouter = remoteRouter,
            remoteWorker = remoteWorker,
            remotePeerId = remotePeerId
        )
    }

    private suspend fun startRuntime(runtime: LiveGridDslRuntime)
    {
        P2PRegistry.register(runtime.remoteGrid)
        runtime.senderGrid.addPeerDescriptor(runtime.remoteGrid.getP2pDescription()!!.deepCopy())
        runtime.senderGrid.init()
    }

    private fun shutdownRuntime(runtime: LiveGridDslRuntime)
    {
        P2PRegistry.remove(runtime.remoteGrid)
    }

    private suspend fun executeStrictScenario(
        runtime: LiveGridDslRuntime,
        caseIdPrefix: String
    ): LiveGridExecutionResults
    {
        val localTask = LiveGridTaskRequest(
            caseId = "$caseIdPrefix-local",
            executionMode = LiveGridExecutionMode.LOCAL_ONLY,
            task = "Handle this request locally and return only strict JSON for the sender-generalist role."
        )
        val localResult = runtime.senderGrid.execute(MultimodalContent(text = serialize(localTask)))
        assertGridExecutionSucceeded(localResult, "local sender execution")
        val localPayload = deserializeStrict<LiveGridWorkerResult>(localResult.text, "sender worker result")

        assertEquals("live-sender-grid", localPayload.executingNode)
        assertEquals(LiveGridExecutionMode.LOCAL_ONLY.name, localPayload.executionMode)
        assertEquals("sender-generalist", localPayload.specialty)
        assertEquals(1, runtime.senderRouter.requestCount)
        assertEquals(1, runtime.senderWorker.requestCount)
        assertEquals(0, runtime.remoteRouter.requestCount)
        assertEquals(0, runtime.remoteWorker.requestCount)

        val remoteTask = LiveGridTaskRequest(
            caseId = "$caseIdPrefix-remote",
            executionMode = LiveGridExecutionMode.REMOTE_SPECIALIST,
            targetPeerId = runtime.remotePeerId,
            task = "Delegate this specialist request to the remote node and return only strict JSON for the remote-specialist role."
        )
        val remoteResult = runtime.senderGrid.execute(MultimodalContent(text = serialize(remoteTask)))
        assertGridExecutionSucceeded(remoteResult, "remote specialist execution")
        val remotePayload = deserializeStrict<LiveGridWorkerResult>(remoteResult.text, "remote worker result")

        assertEquals("live-remote-grid", remotePayload.executingNode)
        assertEquals(LiveGridExecutionMode.REMOTE_SPECIALIST.name, remotePayload.executionMode)
        assertEquals("remote-specialist", remotePayload.specialty)

        val envelope = remoteResult.metadata["distributionGridEnvelope"] as? DistributionGridEnvelope
            ?: error("Remote result should contain a DistributionGridEnvelope.")
        val outcome = remoteResult.metadata["distributionGridOutcome"] as? DistributionGridOutcome
            ?: error("Remote result should contain a DistributionGridOutcome.")
        assertEquals("live-remote-grid", outcome.finalNodeId)
        assertTrue(outcome.hopCount >= 2, "Remote handoff should record at least sender and remote hops.")
        assertEquals(DistributionGridDirectiveKind.HAND_OFF_TO_PEER, envelope.hopHistory.first().routerAction)

        assertEquals(2, runtime.senderRouter.requestCount)
        assertEquals(1, runtime.senderWorker.requestCount)
        assertEquals(1, runtime.remoteRouter.requestCount)
        assertEquals(1, runtime.remoteWorker.requestCount)

        return LiveGridExecutionResults(
            localResult = localResult,
            remoteResult = remoteResult,
            localPayload = localPayload,
            remotePayload = remotePayload
        )
    }

    private fun assertGridExecutionSucceeded(
        result: MultimodalContent,
        label: String
    )
    {
        val failure = result.metadata["distributionGridFailure"] as? DistributionGridFailure
        assertTrue(
            result.passPipeline,
            "$label should complete successfully. Failure: ${failure?.kind}: ${failure?.reason}. Raw result: ${result.text}"
        )
        assertFalse(result.terminatePipeline, "$label should not terminate with a failure envelope.")
    }

    private fun createDslGrid(
        nodeId: String,
        transportAddress: String,
        router: LiveBedrockRoleContainer,
        worker: LiveBedrockRoleContainer,
        traceConfig: TraceConfig,
        defaultTracePolicy: DistributionGridTracePolicy,
        recordsPromptContent: Boolean = false,
        recordsInteractionContext: Boolean = false
    ): DistributionGrid
    {
        val metadata = buildGridMetadata(
            nodeId = nodeId,
            defaultTracePolicy = defaultTracePolicy
        )

        return distributionGrid {
            p2p {
                agentName(nodeId)
                description("Live DistributionGrid Bedrock test node")
                transportMethod(Transport.Tpipe)
                transportAddress(transportAddress)
                supportedContent(SupportedContentTypes.text)
                acceptedContent(SupportedContentTypes.text)
                maxTokens(2048)
                distributionGridMetadata(metadata)
            }

            security {
                requiresAuth(false)
                recordsPromptContent(recordsPromptContent)
                recordsInteractionContext(recordsInteractionContext)
                allowsExternalContext(false)
                allowExternalConnections(true)
            }

            router(router)
            worker(worker)

            tracing {
                enabled(traceConfig)
            }
        }
    }

    private fun createSenderRouterPipeline(
        traceConfig: TraceConfig,
        modelId: String
    ): Pipeline
    {
        val remoteExample = LiveGridDirectiveOutput(
            directive = DistributionGridDirective(
                kind = DistributionGridDirectiveKind.HAND_OFF_TO_PEER,
                targetPeerId = "${Transport.Tpipe}::live-remote-grid-address",
                notes = "Delegate to the remote specialist."
            )
        )
        val pipe = createBaseBedrockPipe(
            pipeName = "distribution-grid-dsl-live-sender-router",
            modelId = modelId,
            traceConfig = traceConfig,
            maxTokens = 256
        )
        pipe.setSystemPrompt(
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
        pipe.setJsonOutput(remoteExample)
        pipe.requireJsonPromptInjection(true)

        return Pipeline().apply {
            add(pipe)
        }
    }

    private fun createRemoteRouterPipeline(
        traceConfig: TraceConfig,
        modelId: String
    ): Pipeline
    {
        val remoteRouterExample = LiveGridDirectiveOutput(
            directive = DistributionGridDirective(
                kind = DistributionGridDirectiveKind.RUN_LOCAL_WORKER,
                notes = "Remote node should run its local worker."
            )
        )
        val pipe = createBaseBedrockPipe(
            pipeName = "distribution-grid-dsl-live-remote-router",
            modelId = modelId,
            traceConfig = traceConfig,
            maxTokens = 256
        )
        pipe.setSystemPrompt(
            """
            You are the remote router for a live TPipe DistributionGrid integration test.
            The user prompt is a serialized LiveGridTaskRequest JSON object.
            Follow the configured JSON schema exactly.
            For every task in this test, choose the local worker path on the remote node.
            Never hand off again.
            Keep notes short and operational.
            """.trimIndent()
        )
        pipe.setJsonOutput(remoteRouterExample)
        pipe.requireJsonPromptInjection(true)

        return Pipeline().apply {
            add(pipe)
        }
    }

    private fun createWorkerPipeline(
        roleName: String,
        traceConfig: TraceConfig,
        modelId: String,
        executingNode: String,
        executionMode: LiveGridExecutionMode,
        specialty: String
    ): Pipeline
    {
        val pipe = createBaseBedrockPipe(
            pipeName = "distribution-grid-dsl-live-$roleName",
            modelId = modelId,
            traceConfig = traceConfig,
            maxTokens = 512
        )
        val workerExample = LiveGridWorkerOutput(
            result = LiveGridWorkerResult(
                executingNode = executingNode,
                executionMode = executionMode.name,
                specialty = specialty,
                answer = "Short task-specific answer.",
                confidenceBand = "HIGH"
            )
        )
        pipe.setSystemPrompt(
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
        pipe.setJsonOutput(workerExample)
        pipe.requireJsonPromptInjection(true)

        return Pipeline().apply {
            add(pipe)
        }
    }

    private fun createBaseBedrockPipe(
        pipeName: String,
        modelId: String,
        traceConfig: TraceConfig,
        maxTokens: Int
    ): BedrockMultimodalPipe
    {
        return BedrockMultimodalPipe().apply {
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

    private suspend fun createLiveRoleContainer(
        roleName: String,
        traceConfig: TraceConfig,
        pipeline: Pipeline,
        adapter: StrictRoleAdapter
    ): LiveBedrockRoleContainer
    {
        pipeline.setPipelineName(roleName)
        pipeline.enableTracing(traceConfig)
        pipeline.init(initPipes = true)
        return LiveBedrockRoleContainer(
            roleName = roleName,
            pipeline = pipeline,
            adapter = adapter
        )
    }

    private fun saveTraceArtifacts(
        runtime: LiveGridDslRuntime,
        results: LiveGridExecutionResults? = null
    ): TraceArtifacts
    {
        val traceDir = File(TPipeConfig.getTraceDir(), "$DSL_TRACE_SUBDIRECTORY/${runtime.scenarioName}")
        traceDir.mkdirs()

        val senderGridTrace = runtime.senderGrid.getTraceReport(TraceFormat.HTML)
        val remoteGridTrace = runtime.remoteGrid.getTraceReport(TraceFormat.HTML)
        val senderRouterTrace = runtime.senderRouter.pipeline.getTraceReport(TraceFormat.HTML)
        val senderWorkerTrace = runtime.senderWorker.pipeline.getTraceReport(TraceFormat.HTML)
        val remoteRouterTrace = runtime.remoteRouter.pipeline.getTraceReport(TraceFormat.HTML)
        val remoteWorkerTrace = runtime.remoteWorker.pipeline.getTraceReport(TraceFormat.HTML)

        writeTraceFile(File(traceDir, "sender-grid.html"), senderGridTrace, "sender grid")
        writeTraceFile(File(traceDir, "remote-grid.html"), remoteGridTrace, "remote grid")
        writeTraceFile(File(traceDir, "sender-router-pipeline.html"), senderRouterTrace, "sender router pipeline")
        writeTraceFile(File(traceDir, "sender-worker-pipeline.html"), senderWorkerTrace, "sender worker pipeline")
        writeTraceFile(File(traceDir, "remote-router-pipeline.html"), remoteRouterTrace, "remote router pipeline")
        writeTraceFile(File(traceDir, "remote-worker-pipeline.html"), remoteWorkerTrace, "remote worker pipeline")

        assertGridTrace(senderGridTrace, "sender grid", results)
        assertGridTrace(remoteGridTrace, "remote grid", results)
        if(results != null)
        {
            assertPipelineTrace(runtime.senderRouter, "sender router")
            assertPipelineTrace(runtime.senderWorker, "sender worker")
            assertPipelineTrace(runtime.remoteRouter, "remote router")
            assertPipelineTrace(runtime.remoteWorker, "remote worker")
        }

        return TraceArtifacts(
            senderGridHtml = senderGridTrace,
            remoteGridHtml = remoteGridTrace
        )
    }

    private fun writeTraceFile(
        file: File,
        content: String,
        label: String
    )
    {
        writeStringToFile(file.absolutePath, content)
        assertTrue(file.exists(), "$label trace file should exist at ${file.absolutePath}")
        assertTrue(file.length() > 0, "$label trace file should not be empty at ${file.absolutePath}")
        assertTrue(content.isNotBlank(), "$label trace report should not be blank.")
    }

    private fun assertGridTrace(
        html: String,
        label: String,
        results: LiveGridExecutionResults?
    )
    {
        assertTrue(html.contains("TPipe DistributionGrid Execution Analysis"), "$label trace should use the DistributionGrid visualizer heading.")
        assertTrue(html.contains("Grid State"), "$label trace should include the Grid State section.")
        assertTrue(html.contains("Grid Orchestration Flow"), "$label trace should include the orchestration section.")
        if(results != null)
        {
            assertTrue(
                html.contains(results.localPayload.executingNode) || html.contains(results.remotePayload.executingNode),
                "$label trace should identify the node that executed work."
            )
        }
    }

    private fun assertPipelineTrace(
        role: LiveBedrockRoleContainer,
        label: String
    )
    {
        val events = PipeTracer.getTrace(role.pipeline.getTraceId()).map { it.eventType }
        assertTrue(events.isNotEmpty(), "$label pipeline trace should record live events.")
        assertTrue(
            events.contains(TraceEventType.API_CALL_START) || events.contains(TraceEventType.API_CALL_SUCCESS),
            "$label pipeline trace should contain Bedrock API execution events."
        )
    }

    private fun resolveLiveModelId(): String
    {
        val inferenceProfile = bedrockEnv.getInferenceProfileId(QWEN_30B_MODEL_ID)
        return if(inferenceProfile.isNullOrBlank()) QWEN_30B_MODEL_ID else inferenceProfile
    }

    private fun buildGridMetadata(
        nodeId: String,
        defaultTracePolicy: DistributionGridTracePolicy
    ): DistributionGridNodeMetadata
    {
        return DistributionGridNodeMetadata(
            nodeId = nodeId,
            supportedProtocolVersions = mutableListOf(DistributionGridProtocolVersion()),
            roleCapabilities = mutableListOf("Router", "Worker"),
            supportedTransports = mutableListOf(Transport.Tpipe),
            requiresHandshake = true,
            defaultTracePolicy = defaultTracePolicy,
            defaultRoutingPolicy = DistributionGridRoutingPolicy(),
            actsAsRegistry = false
        )
    }

    private fun buildDebugTraceConfig(): TraceConfig
    {
        return TraceConfig(
            enabled = true,
            outputFormat = TraceFormat.HTML,
            detailLevel = TraceDetailLevel.DEBUG,
            includeContext = true,
            includeMetadata = true
        )
    }

    private fun buildVisibleDebugTracePolicy(): DistributionGridTracePolicy
    {
        return DistributionGridTracePolicy(
            allowTracing = true,
            allowTracePersistence = true,
            requireRedaction = false,
            rejectNonCompliantNodes = true
        )
    }

    private data class TraceArtifacts(
        val senderGridHtml: String,
        val remoteGridHtml: String
    )
}
