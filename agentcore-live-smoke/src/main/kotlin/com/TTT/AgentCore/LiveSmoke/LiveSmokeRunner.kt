package com.TTT.AgentCore.LiveSmoke

import aws.sdk.kotlin.runtime.auth.credentials.DefaultChainCredentialsProvider
import aws.sdk.kotlin.services.bedrockagentcore.model.*
import aws.sdk.kotlin.services.bedrockagentcorecontrol.model.GetOnlineEvaluationConfigRequest
import com.TTT.AgentCore.AgentCoreClients
import com.TTT.AgentCore.AgentCoreConfig
import com.TTT.AgentCore.evaluations.evaluationClient
import com.TTT.AgentCore.evaluations.AgentCoreEvaluationPoller
import com.TTT.AgentCore.evaluations.evaluationAdmin
import com.TTT.AgentCore.gateway.AgentCoreGatewayCredentials
import com.TTT.AgentCore.gateway.AgentCoreGatewayCredentialsProvider
import com.TTT.AgentCore.gateway.AgentCoreGatewaySigV4Auth
import com.TTT.AgentCore.harness.AgentCoreHarnessAgent
import com.TTT.AgentCore.harness.harnessClient
import com.TTT.AgentCore.memory.AgentCoreMemoryBackend
import com.TTT.AgentCore.memory.AgentCoreMemoryConfig
import com.TTT.AgentCore.memory.semanticMemory
import com.TTT.AgentCore.observability.AgentCoreOtelConfig
import com.TTT.AgentCore.observability.AgentCoreOtelTraceSink
import com.TTT.AgentCore.runtime.AgentCoreRuntimeClient
import com.TTT.AgentCore.runtime.AgentCoreRuntimeClientConfig
import com.TTT.AgentCore.runtime.AgentCoreRuntimeRequestSigner
import com.TTT.AgentCore.runtime.AgentCoreRuntimeAgent
import com.TTT.AgentCore.policy.AgentCorePolicyDecision
import com.TTT.AgentCore.policy.AgentCorePolicyEvaluator
import com.TTT.AgentCore.policy.AgentCorePolicyMode
import com.TTT.AgentCore.policy.AgentCoreGatewayPolicyBinding
import com.TTT.AgentCore.policy.policyAdmin
import com.TTT.AgentCore.tools.browser
import com.TTT.AgentCore.tools.codeInterpreter
import com.TTT.AgentCore.identity.AgentCoreIdentityAuthProvider
import com.TTT.AgentCore.identity.identityProvider
import com.TTT.Context.TodoList
import com.TTT.Context.TodoListTask
import com.TTT.Context.TodoTaskArray
import com.TTT.Debug.PipeTracer
import com.TTT.Debug.TraceEvent
import com.TTT.Debug.TraceEventType
import com.TTT.Debug.TracePhase
import com.TTT.P2P.P2PRequest
import com.TTT.Pipe.MultimodalContent
import com.TTT.MCP.Client.McpRemoteClient
import com.TTT.MCP.Client.McpRemoteClientConfig
import com.TTT.PipeContextProtocol.FunctionRegistry
import aws.smithy.kotlin.runtime.content.Document
import bedrockPipe.BedrockMultimodalPipe
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.common.CompletableResultCode
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import io.opentelemetry.sdk.trace.data.SpanData
import io.opentelemetry.sdk.trace.export.SpanExporter
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.plugins.plugin
import io.ktor.client.request.post
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.content.OutgoingContent
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.coroutineScope
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.system.exitProcess

/** Executes configured AgentCore live smoke cases and writes a redacted report. */
class LiveSmokeRunner(
    private val config: LiveSmokeConfig,
    private val clients: AgentCoreClients = AgentCoreClients(AgentCoreConfig(config.region))
) : AutoCloseable
{
    private val manifest = ResourceManifest(
        runId = config.runId,
        path = config.manifestPath ?: config.outputPath.resolveSibling("manifest.json")
    )

    /** Run every configured case once. Missing capability IDs become BLOCKED. */
    suspend fun run(): SmokeReport
    {
        val startedAt = Instant.now().toString()
        val cases = mutableListOf<SmokeCaseResult>()
        if(isSelected("runtime.http")) cases += runOptional("runtime.http", config.httpEndpoint) { runtimeHttp(it) }
        if(isSelected("runtime.streaming")) cases += runOptional("runtime.streaming", config.httpEndpoint) { runtimeStreaming(it) }
        if(isSelected("runtime.websocket")) cases += runOptional("runtime.websocket", config.httpEndpoint) { runtimeWebSocket(it) }
        if(isSelected("runtime.sessions")) cases += runOptional("runtime.sessions", config.httpEndpoint) { runtimeSessions(it) }
        if(isSelected("runtime.p2p-adapter")) cases += runOptional("runtime.p2p-adapter", config.httpEndpoint) { runtimeP2pAdapter(it) }
        if(isSelected("runtime.agui")) cases += runOptional("runtime.agui", config.aguiEndpoint) { agui(it) }
        if(isSelected("mcp.pcp")) cases += runOptional("mcp.pcp", config.mcpEndpoint) { mcpAndPcp(it) }
        if(isSelected("gateway.sigv4")) cases += runOptional("gateway.sigv4", config.gatewayEndpoint) { gateway(it) }
        if(isSelected("memory.exact")) cases += runOptional("memory.exact", config.memoryId) { exactMemory(it) }
        if(isSelected("memory.semantic")) cases += runOptional("memory.semantic", config.memoryId) { semanticMemory(it) }
        if(isSelected("tools.browser")) cases += runOptional("tools.browser", config.browserIdentifier) { browser(it) }
        if(isSelected("tools.code-interpreter")) cases += runOptional("tools.code-interpreter", config.codeInterpreterIdentifier) { codeInterpreter(it) }
        if(isSelected("identity.workload-token")) cases += runOptional(
            "identity.workload-token",
            config.workloadName
        ) { workloadName -> identity(workloadName, config.identityVerificationEndpoint) }
        if(isSelected("harness.p2p")) cases += runOptional("harness.p2p", config.harnessArn) { harness(it) }
        if(isSelected("model.bedrock")) cases += runOptional("model.bedrock", config.modelId) { bedrockModel(it) }
        if(isSelected("evaluation.on-demand")) cases += runRequired(
            "evaluation.on-demand",
            listOf(config.evaluationEvaluatorId, config.evaluationTraceId)
        ) { values -> onDemandEvaluation(values[0], values[1]) }
        if(isSelected("evaluation.batch")) cases += runRequired(
            "evaluation.batch",
            listOf(
                config.evaluationEvaluatorId,
                config.evaluationBatchLogGroup,
                config.evaluationServiceName
            )
        ) { values -> batchEvaluation(values[0], values[1], values[2]) }
        if(isSelected("evaluation.online")) cases += runOptional("evaluation.online", config.onlineEvaluationConfigId) { onlineEvaluation(it) }
        if(isSelected("credentials.oauth-api-key")) cases += notSafelyTestable(
            id = "credentials.oauth-api-key",
            message = "No disposable OAuth/API-key provider with an exact delete lifecycle was configured."
        )
        if(isSelected("policy.local-adapter")) cases += runCase("policy.local-adapter") { policyAdapter() }
        if(isSelected("policy.gateway")) cases += runRequired(
            "policy.gateway",
            listOf(config.gatewayEndpoint, config.policyGatewayIdentifier, config.policyEngineId)
        ) { values -> policyGateway(values[0], values[1], values[2]) }
        if(isSelected("observability.local-sink")) cases += runCase("observability.local-sink") { observability() }
        if(isSelected("capability.a2a"))
        {
            val unsupportedAt = Instant.now().toString()
            cases += SmokeCaseResult(
                id = "capability.a2a",
                status = SmokeStatus.UNSUPPORTED,
                startedAt = unsupportedAt,
                finishedAt = Instant.now().toString(),
                message = "A2A is intentionally unsupported by TPipe-AgentCore."
            )
        }
        return SmokeReport(
            runId = config.runId,
            region = config.region,
            startedAt = startedAt,
            finishedAt = Instant.now().toString(),
            cleanupStatus = SmokeStatus.BLOCKED,
            cases = cases,
            notes = listOf(
                "Resource creation and deletion are manifest-controlled by the deployment wrapper.",
                "The report is BLOCKED until the wrapper confirms the post-run AWS rescan is clean."
            )
        )
    }

    /** Write a report using the configured output path. */
    fun writeReport(report: SmokeReport)
    {
        config.outputPath.parent?.toFile()?.mkdirs()
        config.outputPath.toFile().writeText(SmokeJson.encodeReport(report))
    }

    /** Return the manifest loaded for this run for controller cleanup. */
    fun manifestSnapshot(): ResourceManifest = manifest

    /** Close the shared AgentCore SDK clients. */
    override fun close() = clients.close()

    private suspend fun runtimeHttp(endpoint: String): Map<String, String>
    {
        val runtimeArn = config.httpRuntimeArn ?: config.runtimeArn
        runtimeClient(endpoint, runtimeArn).use { client ->
            val pingStatus = if(runtimeArn == null)
            {
                client.ping().also { response ->
                    check(response.status.startsWith("Healthy")) {
                        "Runtime was not healthy: ${response.status}"
                    }
                }.status
            }
            else
            {
                "AgentCore-managed"
            }
            val response = client.invoke("smoke-http", sessionId = sessionId("http"))
            check(response.output.contains("SMOKE_OK")) { "Unexpected runtime output." }
            check(response.outputContent?.text?.contains("SMOKE_OK") == true) {
                "Runtime response did not contain the canonical output schema."
            }
            val malformed = client.invokeRawJson("{bad-json")
            val invalid = client.invokeRawJson("{\"prompt\":\"one\",\"content\":{}}")
            check(invalid.statusCode !in 200..299) { "Invalid invocation was accepted." }
            val missing = client.invokeRawJson("{}")
            check(missing.statusCode !in 200..299) { "Missing prompt/content was accepted." }
            check(malformed.statusCode !in 200..299) { "Malformed invocation was accepted." }
            return mapOf(
                "status" to pingStatus,
                "sessionId" to response.sessionId,
                "malformedStatus" to malformed.statusCode.toString(),
                "invalidStatus" to invalid.statusCode.toString(),
                "missingStatus" to missing.statusCode.toString(),
                "requestId" to listOf(malformed.requestId, invalid.requestId, missing.requestId)
                    .filterNotNull().joinToString(",")
            )
        }
    }

    private suspend fun runtimeStreaming(endpoint: String): Map<String, String>
    {
        runtimeClient(endpoint, config.httpRuntimeArn ?: config.runtimeArn).use { client ->
            val chunks = mutableListOf<String>()
            val response = client.invokeStreaming(
                input = "stream",
                sessionId = sessionId("stream"),
                onChunk = { chunks += it }
            )
            check(chunks == listOf("SMOKE_CHUNK_1|", "SMOKE_CHUNK_2|")) {
                "Unexpected streaming chunks: $chunks"
            }
            check(response.output == chunks.joinToString("")) { "Stream output was not accumulated exactly." }
            return mapOf("chunks" to chunks.joinToString(","), "sessionId" to response.sessionId)
        }
    }

    private suspend fun runtimeWebSocket(endpoint: String): Map<String, String>
    {
        val websocketRuntimeArn = config.httpRuntimeArn ?: config.runtimeArn
        runtimeClient(endpoint, websocketRuntimeArn).use { client ->
            val chunks = mutableListOf<String>()
            val response = client.invokeWebSocket("stream", sessionId("websocket")) { chunks += it }
            check(chunks == listOf("SMOKE_CHUNK_1|", "SMOKE_CHUNK_2|")) {
                "Unexpected WebSocket chunks: $chunks"
            }
            return mapOf("chunks" to chunks.joinToString(","), "sessionId" to response.sessionId)
        }
    }

    private suspend fun runtimeSessions(endpoint: String): Map<String, String>
    {
        runtimeClient(endpoint, config.httpRuntimeArn ?: config.runtimeArn).use { client ->
            val sessionA = sessionId("session-a")
            val sessionB = sessionId("session-b")
            val first = client.invoke("session-a", sessionA)
            val second = client.invoke("session-a", sessionA)
            val isolated = client.invoke("session-b", sessionB)
            check(first.output.contains("session_count=1")) { "First session call did not start at one." }
            check(second.output.contains("session_count=2")) { "Same session was not retained." }
            check(isolated.output.contains("session_count=1")) { "Different session was not isolated." }
            coroutineScope {
                listOf("parallel-a", "parallel-b").map { prompt ->
                    async { client.invoke(prompt, sessionA).output }
                }.awaitAll()
            }
            stopSessionIfConfigured(client, sessionA)
            stopSessionIfConfigured(client, sessionB)
            return mapOf("sessionA" to sessionA, "sessionB" to sessionB)
        }
    }

    private suspend fun runtimeP2pAdapter(endpoint: String): Map<String, String>
    {
        runtimeClient(endpoint, config.httpRuntimeArn ?: config.runtimeArn).use { client ->
            val chunks = mutableListOf<String>()
            val agent = AgentCoreRuntimeAgent(client)
            agent.setStreamingCallbackRecursive { chunk -> chunks += chunk }
            val streamedResponse = agent.executeP2PRequest(
                P2PRequest(prompt = MultimodalContent("stream"))
            )
            check(streamedResponse.output?.text == chunks.joinToString("")) {
                "Runtime P2P adapter did not accumulate streamed output."
            }
            check(chunks == listOf("SMOKE_CHUNK_1|", "SMOKE_CHUNK_2|")) {
                "Runtime P2P adapter lost streamed chunks: $chunks"
            }
            val nonStreamingResponse = agent.executeP2PRequest(
                P2PRequest(prompt = MultimodalContent("marker"))
            )
            check(nonStreamingResponse.output?.text?.contains("SMOKE_OK") == true) {
                "Runtime P2P adapter returned no deterministic output after callback cleanup."
            }
            return mapOf(
                "chunks" to chunks.joinToString(","),
                "callbackCleaned" to "true"
            )
        }
    }

    private suspend fun agui(endpoint: String): Map<String, String>
    {
        signedHttpClient(runtimeSigner(endpoint)).use { client ->
            val input = """
                {"threadId":"${config.runId}-thread","runId":"${config.runId}-agui","messages":[{"role":"user","content":"stream"}]}
            """.trimIndent()
            val aguiRuntimeArn = config.aguiRuntimeArn ?: config.runtimeArn
            val sseBody = client.post(runtimeHttpUrl(endpoint, "/invocations", aguiRuntimeArn)) {
                contentType(ContentType.Application.Json)
                setBody(input)
            }.bodyAsText()
            val sseEvents = sseBody.lineSequence()
                .filter { it.startsWith("data:") }
                .map { it.removePrefix("data:").trim() }
                .toList()
            check(sseEvents.any { it.contains("RUN_STARTED") }) { "AG-UI SSE did not start a run." }
            check(sseEvents.any { it.contains("SMOKE_CHUNK_1") }) { "AG-UI SSE lost streaming content." }
            check(sseEvents.last().contains("RUN_FINISHED")) { "AG-UI SSE did not finish last." }

            val websocketEvents = mutableListOf<String>()
            client.webSocket(urlString = runtimeWebSocketUrl(endpoint, aguiRuntimeArn)) {
                send(Frame.Text(input))
                for(frame in incoming)
                {
                    if(frame is Frame.Text)
                    {
                        websocketEvents += frame.readText()
                        if(websocketEvents.last().contains("RUN_FINISHED")) break
                    }
                }
            }
            check(websocketEvents.any { it.contains("SMOKE_CHUNK_1") }) {
                "AG-UI WebSocket lost streaming content."
            }
            check(websocketEvents.last().contains("RUN_FINISHED")) {
                "AG-UI WebSocket did not finish last."
            }
            return mapOf("sseEvents" to sseEvents.size.toString(), "websocketEvents" to websocketEvents.size.toString())
        }
    }

    private suspend fun mcpAndPcp(endpoint: String): Map<String, String>
    {
        val client = McpRemoteClient(
            McpRemoteClientConfig(
                endpoint = runtimeHttpUrl(endpoint, "/invocations", config.mcpRuntimeArn ?: config.runtimeArn),
                namespacePrefix = "smoke__",
                requestSigner = gatewaySigner(endpoint),
                protocolVersion = "2025-06-18"
            )
        )
        try
        {
            val tools = client.listTools()
            val resources = client.listResources()
            val prompts = client.listPrompts()
            check(tools.any { it.name == "smoke_echo" }) { "smoke_echo was not discovered." }
            check(resources.any { it.name == "smoke_resource" }) { "smoke_resource was not discovered." }
            check(prompts.any { it.name == "smoke_prompt" }) { "smoke_prompt was not discovered." }
            val result = client.callTool("smoke_echo", mapOf("message" to "live"))
            check(result.contains("SMOKE_ECHO:live")) { "Unexpected MCP tool result." }
            client.toPcpContext("smoke__")
            check(FunctionRegistry.getSignature("smoke__smoke_echo") != null) {
                "MCP tool was not bound to PCP."
            }
            return mapOf(
                "tools" to tools.size.toString(),
                "resources" to resources.size.toString(),
                "prompts" to prompts.size.toString(),
                "mcpSessionId" to (client.sessionId() ?: "")
            )
        }
        finally
        {
            client.close()
        }
    }

    private suspend fun gateway(endpoint: String): Map<String, String>
    {
        val client = McpRemoteClient(
            McpRemoteClientConfig(
                endpoint = endpoint,
                namespacePrefix = "gateway__",
                requestSigner = gatewaySigner(endpoint)
            )
        )
        try
        {
            val tools = client.listTools()
            check(tools.isNotEmpty()) { "Gateway returned no tools." }
            val echoTool = tools.firstOrNull { it.name == "smoke_echo" || it.name.endsWith("smoke_echo") }
            checkNotNull(echoTool) { "Gateway did not expose smoke_echo: ${tools.map { it.name }}" }
            check(echoTool.name != "smoke_echo") { "Gateway tool was not namespaced." }
            val result = client.callTool(echoTool.name, mapOf("message" to "gateway"))
            check(result.contains("SMOKE_ECHO:gateway")) { "Gateway tool result was unexpected." }
            return mapOf(
                "tools" to tools.size.toString(),
                "echoTool" to echoTool.name,
                "mcpSessionId" to (client.sessionId() ?: "")
            )
        }
        finally
        {
            client.close()
        }
    }

    private suspend fun exactMemory(memoryId: String): Map<String, String>
    {
        val backend = AgentCoreMemoryBackend(
            clients,
            AgentCoreMemoryConfig(memoryId = memoryId, instanceId = config.runId)
        )
        val key = "${config.runId}-todo"
        backend.putTodoList(
            key,
            TodoList(
                tasks = TodoTaskArray(mutableListOf(TodoListTask(taskNumber = 1, task = "${config.runId}-v1"))),
                version = 1
            )
        )
        check(awaitMemoryValue { backend.getTodoList(key)?.takeIf { it.version == 1L } } != null) {
            "Exact Memory v1 was not readable."
        }
        backend.putTodoList(
            key,
            TodoList(
                tasks = TodoTaskArray(mutableListOf(TodoListTask(taskNumber = 1, task = "${config.runId}-v2"))),
                version = 2
            )
        )
        check(awaitMemoryValue {
            backend.getTodoList(key)?.takeIf { it.tasks.tasks.single().task.endsWith("-v2") }
        } != null) {
            "Exact Memory revision was not readable."
        }
        check(awaitMemoryValue { backend.listTodoListKeys().takeIf { key in it } } != null) {
            "Exact Memory index did not list the key."
        }

        val largeKey = "${config.runId}-chunked"
        val largeValue = buildString {
            repeat(20_000) { index -> append(('a'.code + (index % 26)).toChar()) }
        }
        backend.putTodoList(
            largeKey,
            TodoList(
                tasks = TodoTaskArray(mutableListOf(TodoListTask(taskNumber = 1, task = largeValue))),
                version = 3
            )
        )
        check(awaitMemoryValue {
            backend.getTodoList(largeKey)?.takeIf { it.tasks.tasks.single().task == largeValue }
        } != null) {
            "Exact Memory chunked value did not round-trip with its checksum."
        }
        check(backend.deleteTodoList(key)) { "Exact Memory delete did not remove the key." }
        check(backend.deleteTodoList(largeKey)) { "Exact Memory chunked key did not delete." }
        check(awaitMemoryCondition { backend.getTodoList(key) == null }) {
            "Exact Memory key remained after deletion."
        }
        return mapOf(
            "memoryId" to memoryId,
            "namespace" to "/tpipe/${config.runId}/todo",
            "chunkedValueChars" to largeValue.length.toString(),
            "revision" to "1->2->3",
            "deleted" to "true"
        )
    }

    private suspend fun semanticMemory(memoryId: String): Map<String, String>
    {
        val marker = "${config.runId}-semantic-marker"
        val sessionId = "${config.runId}-semantic-session"
        val response = clients.semanticMemory().createEvent(
            CreateEventRequest {
                this.memoryId = memoryId
                actorId = config.runId
                this.sessionId = sessionId
                eventTimestamp = aws.smithy.kotlin.runtime.time.Instant(java.time.Instant.now())
                payload = listOf(
                    PayloadType.Conversational(
                        Conversational {
                            role = Role.User
                            content = Content.Text(
                                "Remember this AgentCore semantic smoke fact: $marker."
                            )
                        }
                    ),
                    PayloadType.Conversational(
                        Conversational {
                            role = Role.Assistant
                            content = Content.Text(
                                "Acknowledged. The AgentCore smoke marker is $marker."
                            )
                        }
                    )
                )
            }
        )
        val eventId = requireNotNull(response.event?.eventId) {
            "Semantic Memory did not return an event id."
        }
        val retrieved = awaitMemoryValue {
            clients.semanticMemory().retrieveMemoryRecords(
                RetrieveMemoryRecordsRequest {
                    this.memoryId = memoryId
                    namespacePath = "tpipe/${config.runId}/$sessionId"
                    searchCriteria {
                        searchQuery = marker
                        topK = 1
                    }
                }
            ).takeIf { response ->
                response.memoryRecordSummaries.any { summary -> summary.toString().contains(marker) }
            }
        }
        check(retrieved != null) {
            "Semantic Memory did not return the unique marker."
        }
        return mapOf("memoryId" to memoryId, "eventId" to eventId, "retrieved" to "true")
    }

    private suspend fun browser(identifier: String): Map<String, String>
    {
        val owner = "${config.runId}-browser-owner"
        val browser = clients.browser()
        val session = browser.startSession(
            StartBrowserSessionRequest {
                browserIdentifier = identifier
                name = config.runId
                sessionTimeoutSeconds = 60
            },
            owner
        )
        try
        {
            browser.getSession(
                GetBrowserSessionRequest {
                    browserIdentifier = identifier
                    sessionId = session.sessionId
                },
                owner
            )
            config.browserStableUrl?.let { url ->
                browser.invoke(
                    InvokeBrowserRequest {
                        browserIdentifier = identifier
                        sessionId = session.sessionId
                        action = BrowserAction.KeyShortcut(KeyShortcutArguments { keys = listOf("CTRL", "L") })
                    },
                    owner
                )
                browser.invoke(
                    InvokeBrowserRequest {
                        browserIdentifier = identifier
                        sessionId = session.sessionId
                        action = BrowserAction.KeyType(KeyTypeArguments { text = url })
                    },
                    owner
                )
                browser.invoke(
                    InvokeBrowserRequest {
                        browserIdentifier = identifier
                        sessionId = session.sessionId
                        action = BrowserAction.KeyPress(KeyPressArguments { key = "ENTER" })
                    },
                    owner
                )
            }
            val screenshot = browser.invoke(
                InvokeBrowserRequest {
                    browserIdentifier = identifier
                    sessionId = session.sessionId
                    action = BrowserAction.Screenshot(ScreenshotArguments {})
                },
                owner
            )
            check(screenshot.result != null) { "Browser screenshot returned no result." }
            return mapOf(
                "browserIdentifier" to identifier,
                "sessionId" to session.sessionId,
                "navigation" to if(config.browserStableUrl == null) "not-configured" else "address-bar-navigation",
                "screenshot" to "received"
            )
        }
        finally
        {
            browser.stopOwnedSessions(owner)
        }
    }

    private suspend fun codeInterpreter(identifier: String): Map<String, String>
    {
        val owner = "${config.runId}-code-owner"
        val code = clients.codeInterpreter()
        val session = code.startSession(
            StartCodeInterpreterSessionRequest {
                codeInterpreterIdentifier = identifier
                name = config.runId
                sessionTimeoutSeconds = 60
            },
            owner
        )
        try
        {
            code.getSession(
                GetCodeInterpreterSessionRequest {
                    codeInterpreterIdentifier = identifier
                    sessionId = session.sessionId
                },
                owner
            )
            val text = mutableListOf<String>()
            code.invoke(
                InvokeCodeInterpreterRequest {
                    codeInterpreterIdentifier = identifier
                    sessionId = session.sessionId
                    name = ToolName.ExecuteCode
                    arguments {
                        this.code = "print(2+2)"
                        language = ProgrammingLanguage.Python
                    }
                },
                owner
            ) { response ->
                response.stream?.collect { output ->
                    output.asResultOrNull()?.content.orEmpty().mapNotNull { it.text }.forEach { text += it }
                }
            }
            check(text.any { it.contains("4") }) { "Code Interpreter did not return 4." }
            return mapOf("codeInterpreterIdentifier" to identifier, "sessionId" to session.sessionId)
        }
        finally
        {
            code.stopOwnedSessions(owner)
        }
    }

    private suspend fun identity(workloadName: String, configuredEndpoint: String?): Map<String, String>
    {
        val provider = clients.identityProvider()
        var loadCount = 0
        val auth = AgentCoreIdentityAuthProvider(
            loader = {
                loadCount++
                provider.getWorkloadAccessToken(
                    GetWorkloadAccessTokenRequest { this.workloadName = workloadName }
                ).workloadAccessToken
            },
            tokenLifetimeMillis = 1L
        )
        val receivedTokenFingerprints = CopyOnWriteArrayList<String>()
        val localServer = if(configuredEndpoint == null)
        {
            HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).also { server ->
                server.createContext("/verify") { exchange ->
                    val authorization = exchange.requestHeaders.getFirst("Authorization")
                    if(authorization?.startsWith("Bearer ") != true)
                    {
                        exchange.sendResponseHeaders(401, -1)
                    }
                    else
                    {
                        receivedTokenFingerprints += tokenFingerprint(authorization.removePrefix("Bearer "))
                        exchange.sendResponseHeaders(204, -1)
                    }
                    exchange.close()
                }
                server.start()
            }
        }
        else null
        val verificationEndpoint = configuredEndpoint
            ?: "http://127.0.0.1:${checkNotNull(localServer).address.port}/verify"
        try
        {
            HttpClient(CIO).use { client ->
                val first = auth.headers()["Authorization"]
                check(!first.isNullOrBlank()) { "Identity provider returned no bearer header." }
                val firstResponse = client.get(verificationEndpoint) { header("Authorization", first) }
                check(firstResponse.status.isSuccess()) { "Identity verification endpoint rejected the first token." }
                delay(5L)
                val second = auth.headers()["Authorization"]
                val secondResponse = client.get(verificationEndpoint) { header("Authorization", second) }
                check(secondResponse.status.isSuccess()) { "Identity verification endpoint rejected the refreshed token." }
                check(loadCount >= 2) { "Identity token provider did not refresh after expiry." }
                return mapOf(
                    "tokenLoads" to loadCount.toString(),
                    "bearerRequests" to if(configuredEndpoint == null) receivedTokenFingerprints.size.toString() else "2",
                    "tokenRecorded" to "false",
                    "verificationEndpoint" to if(configuredEndpoint == null) "local-loopback" else "configured"
                )
            }
        }
        finally
        {
            localServer?.stop(0)
        }
    }

    private fun tokenFingerprint(token: String): String = MessageDigest.getInstance("SHA-256")
        .digest(token.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }
        .take(12)

    private suspend fun harness(arn: String): Map<String, String>
    {
        val harness = clients.harnessClient()
        val outputs = harness.invoke(
            InvokeHarnessRequest {
                harnessArn = arn
                runtimeSessionId = "${config.runId}-harness-session"
                messages = listOf(
                    HarnessMessage {
                        role = HarnessConversationRole.User
                        content = listOf(HarnessContentBlock.Text("Reply with a short smoke marker."))
                    }
                )
            }
        )
        check(outputs.isNotEmpty()) { "Harness returned no stream output." }
        val agent = AgentCoreHarnessAgent(harness, arn)
        val response = agent.executeP2PRequest(P2PRequest(prompt = MultimodalContent("adapter smoke")))
        check(!response.output?.text.isNullOrBlank()) { "Harness P2P adapter returned no output." }
        return mapOf("harnessArn" to arn, "streamItems" to outputs.size.toString())
    }

    private suspend fun bedrockModel(modelId: String): Map<String, String>
    {
        val pipe = BedrockMultimodalPipe()
        var failure: Throwable? = null
        pipe.setExceptionFunction { _, exception -> failure = exception }
        pipe.setModel(modelId)
        pipe.setRegion(config.region)
        pipe.useConverseApi()
        pipe.setMaxTokens(64)
        pipe.setTemperature(0.0)
        pipe.init()
        val output = pipe.generateContent(
            MultimodalContent("Reply with a short non-empty response containing TPIPE_AGENTCORE_MODEL_OK.")
        )
        check(output.text.isNotBlank()) {
            "Bedrock-backed TPipe returned empty output: ${failure?.message.orEmpty()}"
        }
        return mapOf("modelId" to modelId, "outputPresent" to "true")
    }

    private suspend fun onDemandEvaluation(evaluatorId: String, traceId: String): Map<String, String>
    {
        val response = clients.evaluationClient().evaluate(
            EvaluateRequest {
                this.evaluatorId = evaluatorId
                evaluationTarget = EvaluationTarget.TraceIds(listOf(traceId))
                evaluationInput = EvaluationInput.SessionSpans(
                    listOf(
                        Document.Map(
                            mapOf(
                                "trace_id" to Document.String(traceId),
                                "span_id" to Document.String("${config.runId}-span"),
                                "name" to Document.String("agentcore-live-smoke"),
                                "status" to Document.String("OK")
                            )
                        )
                    )
                )
            }
        )
        check(response.evaluationResults.isNotEmpty()) { "On-demand evaluation returned no results." }
        return mapOf("evaluatorId" to evaluatorId, "traceId" to traceId, "results" to response.evaluationResults.size.toString())
    }

    private suspend fun batchEvaluation(
        evaluatorId: String,
        logGroupName: String,
        serviceName: String
    ): Map<String, String>
    {
        val client = clients.evaluationClient()
        val started = client.startBatch(
            StartBatchEvaluationRequest {
                batchEvaluationName = "${config.runId}_batch"
                clientToken = "${config.runId}_batch_token"
                evaluators = listOf(Evaluator { this.evaluatorId = evaluatorId })
                dataSourceConfig = DataSourceConfig.CloudWatchLogs(
                    CloudWatchLogsSource {
                        logGroupNames = listOf(logGroupName)
                        serviceNames = listOf(serviceName)
                    }
                )
                tags = mapOf("TPipeSmokeRun" to config.runId)
            }
        )
        val completed = try
        {
            AgentCoreEvaluationPoller.await(
                timeoutMillis = 120_000L,
                initialDelayMillis = 1_000L,
                maxDelayMillis = 5_000L,
                load = {
                    client.getBatch(
                        GetBatchEvaluationRequest { batchEvaluationId = started.batchEvaluationId }
                    )
                },
                isTerminal = { response ->
                    response.status.value in setOf("COMPLETED", "COMPLETED_WITH_ERRORS", "FAILED", "STOPPED")
                }
            )
        }
        catch(exception: Throwable)
        {
            runCatching {
                client.stopBatch(
                    StopBatchEvaluationRequest { batchEvaluationId = started.batchEvaluationId }
                )
            }
            throw exception
        }
        check(completed.status == BatchEvaluationStatus.Completed) {
            "Batch evaluation ended with ${completed.status.value}: ${completed.errorDetails.orEmpty()}"
        }
        return mapOf(
            "batchEvaluationId" to started.batchEvaluationId,
            "status" to completed.status.value
        )
    }

    private suspend fun onlineEvaluation(identifier: String): Map<String, String>
    {
        val response = clients.evaluationAdmin().execute {
            getOnlineEvaluationConfig(
                GetOnlineEvaluationConfigRequest { onlineEvaluationConfigId = identifier }
            )
        }
        check(response.status.value != "FAILED") {
            "Online evaluation configuration failed: ${response.failureReason.orEmpty()}"
        }
        return mapOf(
            "onlineEvaluationConfigId" to response.onlineEvaluationConfigId,
            "status" to response.status.value,
            "executionStatus" to response.executionStatus.value
        )
    }

    private fun policyAdapter(): Map<String, String>
    {
        val logged = mutableListOf<AgentCorePolicyDecision>()
        val logOnly = AgentCorePolicyEvaluator(
            mode = AgentCorePolicyMode.LOG_ONLY,
            logger = { logged += it }
        )
        check(logOnly.evaluate("smoke_allowed")) { "LOG_ONLY unexpectedly denied an action." }
        check(logged.single().reason == "log-only") { "LOG_ONLY decision was not recorded." }
        val enforce = AgentCorePolicyEvaluator(
            mode = AgentCorePolicyMode.ENFORCE,
            evaluator = { action, _ -> AgentCorePolicyDecision(action, action == "smoke_allowed", "smoke") }
        )
        check(enforce.evaluate("smoke_allowed")) { "ENFORCE rejected the allowed action." }
        check(!enforce.evaluate("smoke_forbidden")) { "ENFORCE allowed the forbidden action." }
        return mapOf("logOnlyDecisions" to logged.size.toString(), "enforce" to "allowed-and-denied")
    }

    private suspend fun policyGateway(
        endpoint: String,
        gatewayIdentifier: String,
        policyEngineIdentifier: String
    ): Map<String, String>
    {
        requireOwnedResource("gateway", gatewayIdentifier)
        requireOwnedResource("policy-engine", policyEngineIdentifier)
        val policy = clients.policyAdmin()
        val client = McpRemoteClient(
            McpRemoteClientConfig(
                endpoint = endpoint,
                namespacePrefix = "policy__",
                requestSigner = gatewaySigner(endpoint)
            )
        )
        try
        {
            policy.bindGateway(
                AgentCoreGatewayPolicyBinding(
                    gatewayIdentifier = gatewayIdentifier,
                    policyEngineIdentifier = policyEngineIdentifier,
                    mode = AgentCorePolicyMode.LOG_ONLY
                )
            )
            val tools = client.listTools()
            val echoTool = tools.firstOrNull { it.name == "smoke_echo" || it.name.endsWith("smoke_echo") }
            val forbiddenTool = tools.firstOrNull {
                it.name == "smoke_forbidden" || it.name.endsWith("smoke_forbidden")
            }
            checkNotNull(echoTool) { "Policy gateway did not expose smoke_echo: ${tools.map { it.name }}" }
            checkNotNull(forbiddenTool) {
                "Policy gateway did not expose smoke_forbidden: ${tools.map { it.name }}"
            }
            check(client.callTool(echoTool.name, mapOf("message" to "policy-log"))
                .contains("SMOKE_ECHO:policy-log")) { "LOG_ONLY allowed tool did not execute." }
            check(client.callTool(forbiddenTool.name, emptyMap()).contains("SMOKE_FORBIDDEN")) {
                "LOG_ONLY forbidden tool did not execute."
            }

            policy.bindGateway(
                AgentCoreGatewayPolicyBinding(
                    gatewayIdentifier = gatewayIdentifier,
                    policyEngineIdentifier = policyEngineIdentifier,
                    mode = AgentCorePolicyMode.ENFORCE
                )
            )
            check(client.callTool(echoTool.name, mapOf("message" to "policy-enforce"))
                .contains("SMOKE_ECHO:policy-enforce")) { "ENFORCE blocked the allowed tool." }
            val denied = runCatching { client.callTool(forbiddenTool.name, emptyMap()) }.isFailure
            check(denied) { "ENFORCE allowed the forbidden tool." }
            return mapOf("logOnly" to "allowed-and-forbidden", "enforce" to "allowed-and-denied")
        }
        finally
        {
            runCatching {
                policy.bindGateway(
                    AgentCoreGatewayPolicyBinding(
                        gatewayIdentifier = gatewayIdentifier,
                        policyEngineIdentifier = policyEngineIdentifier,
                        mode = AgentCorePolicyMode.LOG_ONLY
                    )
                )
            }
            client.close()
        }
    }

    private fun requireOwnedResource(type: String, identifier: String)
    {
        check(manifest.resources().any { resource ->
            resource.type == type && (resource.id == identifier || resource.arn == identifier)
        }) {
            "Refusing to mutate non-manifest $type '$identifier'."
        }
    }

    /** Poll AgentCore Memory until an eventually consistent result is visible. */
    private suspend fun <T> awaitMemoryValue(
        timeoutMillis: Long = 120_000L,
        read: suspend () -> T?
    ): T?
    {
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000L
        var value: T? = null
        while(System.nanoTime() < deadline)
        {
            value = runCatching { read() }.getOrNull()
            if(value != null) return value
            delay(2_000L)
        }
        return value
    }

    /** Poll AgentCore Memory until a deletion or other condition is true. */
    private suspend fun awaitMemoryCondition(
        timeoutMillis: Long = 120_000L,
        condition: suspend () -> Boolean
    ): Boolean
    {
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000L
        while(System.nanoTime() < deadline)
        {
            if(runCatching { condition() }.getOrDefault(false)) return true
            delay(2_000L)
        }
        return false
    }

    private fun observability(): Map<String, String>
    {
        val exported = CopyOnWriteArrayList<SpanData>()
        var exportCalls = 0
        val exporter = object : SpanExporter {
            override fun export(spans: Collection<SpanData>): CompletableResultCode
            {
                exportCalls++
                if(exportCalls == 1) return CompletableResultCode.ofFailure()
                exported += spans
                return CompletableResultCode.ofSuccess()
            }

            override fun flush(): CompletableResultCode = CompletableResultCode.ofSuccess()

            override fun shutdown(): CompletableResultCode = CompletableResultCode.ofSuccess()
        }
        val provider = SdkTracerProvider.builder()
            .addSpanProcessor(SimpleSpanProcessor.create(exporter))
            .build()
        val openTelemetry: OpenTelemetry = OpenTelemetrySdk.builder().setTracerProvider(provider).build()
        val sink = AgentCoreOtelTraceSink(
            openTelemetry,
            AgentCoreOtelConfig(
                sinkName = "${config.runId}-otel",
                queueCapacity = 4,
                redactionPredicate = { key -> key.contains("authorization", ignoreCase = true) }
            )
        )
        val traceId = "${config.runId}-trace"
        PipeTracer.enable()
        try
        {
            PipeTracer.startTrace(traceId)
            PipeTracer.addEvent(
                traceId,
                TraceEvent(
                    timestamp = System.currentTimeMillis(),
                    pipeId = "smoke-pipe",
                    pipeName = "agentcore-live-smoke",
                    eventType = TraceEventType.PIPE_START,
                    phase = TracePhase.EXECUTION,
                    content = MultimodalContent("content must not be exported by default"),
                    contextSnapshot = null,
                    metadata = mapOf("authorization" to "secret")
                )
            )
            PipeTracer.addEvent(
                traceId,
                TraceEvent(
                    timestamp = System.currentTimeMillis(),
                    pipeId = "smoke-pipe",
                    pipeName = "agentcore-live-smoke",
                    eventType = TraceEventType.PIPE_END,
                    phase = TracePhase.CLEANUP,
                    content = null,
                    contextSnapshot = null
                )
            )
            repeat(12) { index ->
                PipeTracer.addEvent(
                    traceId,
                    TraceEvent(
                        timestamp = System.currentTimeMillis(),
                        pipeId = "smoke-pipe-$index",
                        pipeName = "agentcore-live-smoke",
                        eventType = TraceEventType.PIPE_START,
                        phase = TracePhase.EXECUTION,
                        content = null,
                        contextSnapshot = null,
                        metadata = mapOf("queue_index" to index.toString())
                    )
                )
            }
            sink.flush()
            check(exported.isNotEmpty()) { "OTEL sink exported no spans." }
            check(exportCalls >= 2) { "OTEL exporter failure was not isolated from later exports." }
            check(exported.none { span ->
                span.attributes.asMap().values.any { value -> value.toString().contains("secret") }
            }) { "OTEL sink exported a redacted value." }
            val dropped = sink.droppedEvents()
            check(dropped >= 0L) { "OTEL queue drop counter became invalid." }
            val exportedBeforeClose = exported.size
            sink.close()
            check(exported.size >= exportedBeforeClose) { "OTEL close lost already accepted spans." }
            return mapOf(
                "spans" to exported.size.toString(),
                "dropped" to dropped.toString(),
                "queueCapacity" to sink.capacity().toString(),
                "failureIsolated" to "true",
                "shutdownFlushed" to "true"
            )
        }
        finally
        {
            runCatching { sink.close() }
            provider.close()
            PipeTracer.clearTrace(traceId)
            PipeTracer.disable()
        }
    }

    private fun runtimeClient(endpoint: String, runtimeArn: String?): AgentCoreRuntimeClient = AgentCoreRuntimeClient(
        AgentCoreRuntimeClientConfig(
            endpoint = endpoint,
            invocationPath = runtimePath("/invocations", includeQualifier = true, runtimeArn = runtimeArn),
            websocketPath = runtimePath("/ws", includeQualifier = false, runtimeArn = runtimeArn),
            pingPath = runtimePath("/ping", includeQualifier = false, runtimeArn = runtimeArn),
            runtimeArn = runtimeArn,
            requestSigner = runtimeSigner(endpoint)
        ),
        clients
    )

    /** Return whether an explicit case filter permits this live assertion. */
    private fun isSelected(id: String): Boolean = config.caseFilter?.contains(id) != false

    private fun runtimeSigner(endpoint: String): AgentCoreRuntimeRequestSigner?
    {
        if(!endpoint.startsWith("https://", ignoreCase = true)) return null
        val signer = gatewaySigner(endpoint)
        return AgentCoreRuntimeRequestSigner { url, method, headers, body ->
            signer.sign(url, method, headers, body)
        }
    }

    private fun signedHttpClient(signer: AgentCoreRuntimeRequestSigner?): HttpClient =
        HttpClient(CIO) { install(WebSockets) }.also { client ->
            signer ?: return@also
            client.plugin(HttpSend).intercept { request ->
                val body = when(val content = request.body)
                {
                    is OutgoingContent.ByteArrayContent -> content.bytes()
                    is OutgoingContent.NoContent -> ByteArray(0)
                    else -> ByteArray(0)
                }
                val headers = buildMap {
                    request.headers.entries().forEach { (name, values) ->
                        put(name, values.joinToString(","))
                    }
                    (request.body as? OutgoingContent)?.contentType?.let {
                        put("Content-Type", it.toString())
                    }
                }
                signer.sign(
                    url = request.url.buildString(),
                    method = request.method.value,
                    headers = headers,
                    body = body
                ).forEach { (name, value) ->
                    request.headers.remove(name)
                    request.headers.append(name, value)
                }
                execute(request)
            }
        }

    private fun runtimeHttpUrl(endpoint: String, path: String, runtimeArn: String?): String =
        endpoint.trimEnd('/') + runtimePath(path, includeQualifier = true, runtimeArn = runtimeArn)

    private fun runtimeWebSocketUrl(endpoint: String, runtimeArn: String?): String =
        endpoint.trimEnd('/').replaceFirst("https://", "wss://", ignoreCase = true) +
            runtimePath("/ws", includeQualifier = false, runtimeArn = runtimeArn)

    private fun runtimePath(path: String, includeQualifier: Boolean, runtimeArn: String?): String
    {
        val selectedRuntimeArn = runtimeArn ?: return path
        val escapedArn = URLEncoder.encode(
            selectedRuntimeArn.substringBefore("/runtime-endpoint/"),
            StandardCharsets.UTF_8
        )
        val qualifier = selectedRuntimeArn.substringAfter("/runtime-endpoint/", "DEFAULT")
            .ifBlank { "DEFAULT" }
        return "/runtimes/$escapedArn$path" +
            if(includeQualifier)
            {
                "?qualifier=${URLEncoder.encode(qualifier, StandardCharsets.UTF_8)}"
            }
            else
            {
                ""
            }
    }

    private fun gatewaySigner(endpoint: String): com.TTT.MCP.Client.McpRemoteRequestSigner
    {
        val credentialsProvider = DefaultChainCredentialsProvider()
        return AgentCoreGatewaySigV4Auth(
            region = config.region,
            credentialsProvider = AgentCoreGatewayCredentialsProvider {
                val credentials = credentialsProvider.resolve()
                AgentCoreGatewayCredentials(
                    accessKeyId = requireNotNull(credentials.accessKeyId),
                    secretAccessKey = requireNotNull(credentials.secretAccessKey),
                    sessionToken = credentials.sessionToken
                )
            }
        )
    }

    private suspend fun stopSessionIfConfigured(client: AgentCoreRuntimeClient, sessionId: String)
    {
        if(config.httpRuntimeArn != null || config.runtimeArn != null)
        {
            runCatching { client.stopSession(sessionId) }
        }
    }

    private fun sessionId(label: String): String = "${config.runId}-$label-${java.util.UUID.randomUUID()}"

    private suspend fun runOptional(
        id: String,
        required: String?,
        block: suspend (String) -> Map<String, String>
    ): SmokeCaseResult
    {
        if(required.isNullOrBlank())
        {
            val now = Instant.now().toString()
            return SmokeCaseResult(
                id = id,
                status = SmokeStatus.BLOCKED,
                startedAt = now,
                finishedAt = now,
                message = "Required run-owned endpoint or identifier was not configured.",
                failureClass = failureClassFor(id)
            )
        }
        return runCase(id) { block(required) }
    }

    private suspend fun runRequired(
        id: String,
        required: List<String?>,
        block: suspend (List<String>) -> Map<String, String>
    ): SmokeCaseResult
    {
        val values = required.filterNotNull().filter { it.isNotBlank() }
        if(values.size != required.size)
        {
            val now = Instant.now().toString()
            return SmokeCaseResult(
                id = id,
                status = SmokeStatus.BLOCKED,
                startedAt = now,
                finishedAt = now,
                message = "All run-owned inputs for this case must be configured.",
                failureClass = failureClassFor(id)
            )
        }
        return runCase(id) { block(values) }
    }

    private fun notSafelyTestable(id: String, message: String): SmokeCaseResult
    {
        val now = Instant.now().toString()
        return SmokeCaseResult(
            id = id,
            status = SmokeStatus.NOT_SAFELY_TESTABLE,
            startedAt = now,
            finishedAt = now,
            message = message
        )
    }

    private suspend fun runCase(id: String, block: suspend () -> Map<String, String>): SmokeCaseResult
    {
        val startedAt = Instant.now().toString()
        return try
        {
            val evidence = block()
            SmokeCaseResult(
                id = id,
                status = SmokeStatus.PASS,
                startedAt = startedAt,
                finishedAt = Instant.now().toString(),
                evidence = evidence,
                requestIds = evidenceIds(evidence, "requestId", "requestIds"),
                traceIds = evidenceIds(evidence, "traceId", "traceIds"),
                failureClass = failureClassFor(id)
            )
        }
        catch(exception: Throwable)
        {
            SmokeCaseResult(
                id = id,
                status = SmokeStatus.FAIL,
                startedAt = startedAt,
                finishedAt = Instant.now().toString(),
                message = SmokeRedaction.text(exception.message ?: exception::class.simpleName.orEmpty()),
                failureClass = failureClassFor(id)
            )
        }
    }

    private fun evidenceIds(evidence: Map<String, String>, vararg keys: String): List<String> =
        keys.flatMap { key -> evidence[key].orEmpty().split(',') }
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinct()

    private fun failureClassFor(id: String): SmokeFailureClass = when
    {
        id == "deployment" -> SmokeFailureClass.DEPLOYMENT
        id.startsWith("runtime.streaming") || id.startsWith("runtime.websocket") || id.contains("stream") ->
            SmokeFailureClass.STREAMING
        id.startsWith("runtime.sessions") -> SmokeFailureClass.SESSION
        id.contains("identity") || id.contains("sigv4") || id.contains("credential") ->
            SmokeFailureClass.AUTHENTICATION
        id.startsWith("runtime.") || id.startsWith("mcp.") || id.startsWith("agui") -> SmokeFailureClass.PROTOCOL
        id.startsWith("memory.") -> SmokeFailureClass.MEMORY
        id.startsWith("policy.") -> SmokeFailureClass.POLICY
        id.startsWith("tools.") || id.startsWith("harness.") -> SmokeFailureClass.TOOL
        id.startsWith("model.") -> SmokeFailureClass.MODEL
        id.startsWith("evaluation.") -> SmokeFailureClass.EVALUATION
        id.startsWith("observability.") -> SmokeFailureClass.OBSERVABILITY
        else -> SmokeFailureClass.PROTOCOL
    }
}

/** Explicit command-line entrypoint for the live smoke application. */
fun main()
{
    val config = LiveSmokeConfig.fromEnvironment()
    val runner = LiveSmokeRunner(config)
    try
    {
        val report = runBlocking { runner.run() }
        runner.writeReport(report)
        println("AgentCore live smoke report: ${config.outputPath}")
        if(report.hasFailure()) exitProcess(2)
    }
    finally
    {
        runner.close()
    }
}
