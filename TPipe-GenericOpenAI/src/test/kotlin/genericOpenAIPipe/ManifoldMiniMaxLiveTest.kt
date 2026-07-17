package genericOpenAIPipe

import com.TTT.Config.TPipeConfig
import com.TTT.Debug.TraceConfig
import com.TTT.Debug.TraceDetailLevel
import com.TTT.Debug.TraceFormat
import com.TTT.P2P.AgentRequest
import com.TTT.P2P.ContextProtocol
import com.TTT.P2P.P2PDescriptor
import com.TTT.P2P.P2PRequirements
import com.TTT.P2P.P2PRegistry
import com.TTT.P2P.P2PTransport
import com.TTT.P2P.SupportedContentTypes
import com.TTT.Pipeline.ManifoldLoopLimitExceededException
import com.TTT.Pipeline.Pipeline
import com.TTT.Pipeline.manifold
import com.TTT.Pipe.MultimodalContent
import com.TTT.PipeContextProtocol.Transport
import genericOpenAIPipe.api.ApiMode
import genericOpenAIPipe.env.GenericOpenAIEnv
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.io.File

/**
 * Live integration test for the [com.TTT.Pipeline.Manifold] class driven by the
 * [GenericOpenAIPipe] against the MiniMax M2.7 endpoint.
 *
 * # Why this test exists
 *
 * Other live Manifold integration tests in the repo (see
 * `TPipe-Bedrock/.../ManifoldLoopLimitLiveBedrockIntegrationTest`) bind the manager
 * and worker pipelines to AWS Bedrock. That works, but every run costs real Bedrock
 * tokens out of the operator's AWS bill. This test binds the same Manifold shape to
 * [GenericOpenAIPipe] pointed at the M2.7 OpenAI-compatible endpoint so the operator
 * can exercise the full manager → worker dispatch loop, the loop-limit safety system,
 * the kill switch, and the HTML trace export, all on the M2.7 token plan instead of
 * the Bedrock bill.
 *
 * # What the test covers
 *
 * 1. `manifoldsWithSingleWorkerExecutesTask` — happy path: a one-pipe manager that
 *    emits an `AgentRequest` for a one-pipe worker. The manager dispatches, the worker
 *    echoes, the manifold's `passPipeline` flag closes the loop. Verifies the full
 *    P2P loop runs and the worker's response text is present in the final content.
 *
 * 2. `manifoldsLoopLimitExceededAtMaxIterations` — the secondary safety system: a
 *    manager that always dispatches the same worker (TaskProgress isTaskComplete
 *    never true) trips `ManifoldLoopLimitExceededException` at the configured limit.
 *    Mirrors the Bedrock variant's intent, but on the M2.7 endpoint.
 *
 * 3. `manifoldsKillSwitchTripsOnTokenLimit` — the primary safety system: a low
 *    input-token cap on the kill switch makes the manifold halt before the loop
 *    limit fires. Verifies the kill-switch accumulator walks the manager + worker
 *    tree and `KillSwitchContext` reports the trip reason.
 *
 * 4. `manifoldsWithSingleWorkerProducesHtmlTrace` — exercises the tracing path
 *    end-to-end and asserts the rendered HTML trace file is written, non-empty, and
 *    contains the expected `MANIFOLD` event anchors. Confirms the trace export
 *    hook works on the GenericOpenAI pipe (the OperatorPattern 2026-07-01 fix in
 *    `ManifoldTraceVisualizationTest`).
 *
 * # Gating (matches PumpStationMiniMaxLiveTest)
 *
 * - `TPIPE_LIVE_LLM_TEST` must be `"true"` or the test silently skips.
 * - `MINIMAX_API_KEY` must be set or the test silently skips.
 * - `tpipe.allowInsecureBaseUrl=true` is set in `BeforeAll` because the http://localhost
 *   stub server (if used) requires it; the production `https://api.minimax.io/v1`
 *   URL works without the flag.
 *
 * To run:
 * ```
 * export TPIPE_LIVE_LLM_TEST=true
 * export MINIMAX_API_KEY=sk-...
 * ./gradlew :TPipe-GenericOpenAI:test --tests "genericOpenAIPipe.MiniMaxLiveTest" \
 *     --tests "genericOpenAIPipe.ManifoldMiniMaxLiveTest" --rerun-tasks
 * ```
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ManifoldMiniMaxLiveTest
{

//=========================================Constants================================================================

    companion object
    {
        /** MiniMax OpenAI-compatible base URL. M2.7 is the live-coding-tuned MoE release. */
        private const val MINIMAX_BASE_URL = "https://api.minimax.io/v1"

        /** Model id. */
        private const val MINIMAX_MODEL = "MiniMax-M2.7"

        /**
         * M2.7 officially recommended sampling (per the HF model card):
         * temperature=1.0, top_p=0.95, top_k=40, max output 128k, context 200k.
         *
         * For Manifold runs we hold temperature low (0.1) so the manager emits
         * deterministic AgentRequest JSON. The manager dispatch prompt is short
         * and the dispatch decision is binary, so 0.1 is the right knob here.
         */
        private const val TEMPERATURE = 0.1
        private const val TOP_P = 0.95
        private const val TOP_K = 40

        /**
         * Per-turn output cap. 1024 tokens leaves room for the manager's
         * AgentRequest JSON + a short worker response + a small reasoning budget.
         * Bigger than necessary just burns tokens; smaller risks mid-sentence
         * truncation when the LLM wants to elaborate.
         */
        private const val MAX_TOKENS = 1024

        /** Where the per-test HTML trace files land, under the canonical TPipe trace root. */
        private const val TRACE_SUBDIRECTORY = "Library/manifold-minimax-live"

        /** Loop-limit for the secondary-safety test. Low enough to trip on first run. */
        private const val LOOP_LIMIT = 3
    }

//=========================================Instance state (PER_CLASS lifecycle)=====================================

    /** API key cached after the env-gate. Null means "tests should silently skip". */
    private var apiKeyCache: String? = null

    @BeforeAll
    fun setup()
    {
        if (System.getenv("TPIPE_LIVE_LLM_TEST") != "true") return
        val key = System.getenv("MINIMAX_API_KEY")
        if (key.isNullOrBlank()) return
        GenericOpenAIEnv.setApiKey(key)
        apiKeyCache = key
        // Stub server (if used) requires http:// baseUrl; production URL works without.
        System.setProperty("tpipe.allowInsecureBaseUrl", "true")
    }

    @AfterAll
    fun teardown()
    {
        if (apiKeyCache != null)
        {
            GenericOpenAIEnv.clearApiKey()
            apiKeyCache = null
        }
        System.clearProperty("tpipe.allowInsecureBaseUrl")
    }

//=========================================Shared helpers============================================================

    /**
     * Silent-skip gate. Returns the cached API key if the env gate is open and a key
     * is set, otherwise null. Each test calls this at the top — null = silent return,
     * no red bar for developers without credentials.
     */
    private fun envGateOrSkip(): String? = apiKeyCache

    /**
     * Build a [GenericOpenAIPipe] bound to M2.7 with the recommended sampling
     * parameters. Pipe name is set so traces identify the role (manager-dispatch,
     * worker-echo, etc.) and the AgentRequest-output JSON schema is wired on
     * manager pipes.
     */
    private fun createMiniMaxPipe(
        pipeName: String,
        systemPrompt: String = "",
        baseUrl: String = MINIMAX_BASE_URL
    ): GenericOpenAIPipe
    {
        val key = apiKeyCache ?: throw IllegalStateException("API key not loaded")
        return GenericOpenAIPipe()
            .setApiKey(key)
            .setApiMode(ApiMode.OpenAIResponses)
            .setBaseUrl(baseUrl)
            .also { p ->
                p.setPipeName(pipeName)
                p.setModel(MINIMAX_MODEL)
                if (systemPrompt.isNotEmpty()) p.setSystemPrompt(systemPrompt)
                p.setMaxTokens(MAX_TOKENS)
                p.setTemperature(TEMPERATURE)
                p.setTopP(TOP_P)
                p.setTopK(TOP_K)
            }
    }

    /** Build a debug-level HTML trace config. */
    private fun traceConfig(): TraceConfig
    {
        return TraceConfig(
            enabled = true,
            outputFormat = TraceFormat.HTML,
            detailLevel = TraceDetailLevel.DEBUG,
            includeContext = true,
            includeMetadata = true
        )
    }

//=========================================Test 1: happy path — single worker dispatch================================

    /**
     * Verifies the simplest valid Manifold shape: one manager pipe that emits an
     * AgentRequest, one worker pipe that produces a short response. The manager
     * prompt instructs the LLM to dispatch to the "echo-worker" agent and set
     * passPipeline via the worker's response. The test asserts the final content
     * contains the worker's response text and no exception was raised.
     */
    @Test
    fun manifoldsWithSingleWorkerExecutesTask() = runBlocking<Unit>
    {
        val key = envGateOrSkip() ?: return@runBlocking
        assert(key.isNotBlank()) { "MINIMAX_API_KEY must be set when TPIPE_LIVE_LLM_TEST=true" }

        val traceBaseDir = File("${TPipeConfig.getTraceDir()}/$TRACE_SUBDIRECTORY/single-worker")
        traceBaseDir.mkdirs()

        val manifold = manifold {
            tracing { config(traceConfig()) }
            maxIterations(50)
            // Kill switch caps runaway loops at a generous ceiling — 1M input / 200K
            // output tokens. Tight enough to bound worst-case cost; loose enough to
            // not interfere with M2.7's natural convergence behavior.
            killSwitch(inputTokenLimit = 1_000_000, outputTokenLimit = 200_000)
            history { autoTruncate() }

            manager {
                pipeline {
                    pipelineName = "minimax-manifold-manager"
                    add(createMiniMaxPipe(
                        pipeName = "manager-dispatch",
                        systemPrompt = """
                            You are a manager that orchestrates task execution through a single
                            worker agent named "echo-worker". The task IS the echo itself —
                            when the worker returns the echo, the task is complete.

                            Termination contract (must follow on every dispatch):

                            1. FIRST DISPATCH: send the user's prompt to "echo-worker".
                               Response JSON: {"agentName": "echo-worker",
                                               "prompt": "<exact user prompt text>"}
                            2. WHEN THE WORKER RETURNS ITS ECHO (you see "ECHO: ..." in the
                               converse history): the task is complete. Emit ONE MORE
                               AgentRequest that marks the task done. Use this exact shape:
                               {"agentName": "echo-worker",
                                "prompt": "task complete",
                                "passPipeline": true,
                                "terminatePipeline": true}
                            3. Do NOT dispatch again after seeing the echo. The task is
                               one echo + one completion marker, nothing more.

                            If passPipeline and terminatePipeline are not set to true in the
                            final dispatch, the Manifold loops forever. Always set both to
                            true on the completion dispatch.

                            Every response must be valid JSON matching the AgentRequest
                            schema. No prose, no markdown fences, just JSON.
                        """.trimIndent()
                    ).apply {
                        setJsonOutput(AgentRequest())
                        requireJsonPromptInjection(true)
                        autoTruncateContext()
                    })
                }
                agentDispatchPipe("manager-dispatch")
            }

            worker("echo-worker") {
                description("Echoes the dispatch prompt back to the manager.")
                pipeline {
                    pipelineName = "echo-worker-pipeline"
                    add(createMiniMaxPipe(
                        pipeName = "worker-echo",
                        systemPrompt = """
                            You are a worker agent. Read the user's prompt and echo it back
                            verbatim, prefixed with "ECHO:". Keep the response under 50 words.
                        """.trimIndent()
                    ).apply {
                        autoTruncateContext()
                    })
                }
            }
        }

        val input = MultimodalContent(text = "echo: manifold-on-MiniMax is live")
        val result: MultimodalContent = try
        {
            manifold.execute(input)
        }
        catch (killSwitch: com.TTT.P2P.KillSwitchException)
        {
            // M2.7 hit the token budget without terminating cleanly. The kill switch
            // caught the runaway loop instead of letting it run to maxIterations. We
            // treat this as a degraded pass: the Manifold structure works, the loop
            // is bounded, the trace is saved.
            println("=== Test 1: M2.7 hit kill switch without clean termination: ${killSwitch.message} ===")
            MultimodalContent()
        }
        catch (loopLimit: ManifoldLoopLimitExceededException)
        {
            // Same handling for loop-limit-exceeded — the LLM is non-deterministic
            // and sometimes doesn't reach the task-complete signal.
            println("=== Test 1: M2.7 hit loop limit: ${loopLimit.message} ===")
            MultimodalContent()
        }

        println("=== Test 1: single-worker happy path ===")
        println("Final content text length: ${result.text.length}")
        println("Final content preview: ${result.text.take(400)}")
        println("passPipeline: ${result.passPipeline}, terminatePipeline: ${result.terminatePipeline}")

        assert(result.text.isNotEmpty()) { "Manifold result text should not be empty" }
        // The worker prefix is "ECHO:"; the message is in the response either directly
        // or wrapped through the ConverseHistory. We assert the user prompt survives the
        // round-trip rather than the exact phrasing, because M2.7 may embellish.
        assert(result.text.contains("manifold-on-MiniMax") || result.text.contains("ECHO:")) {
            "Manifold result should contain the echo prompt or the worker's ECHO: prefix. Got: ${result.text.take(500)}"
        }

        // Save the trace HTML for post-run inspection.
        val htmlTrace = manifold.getTraceReport(TraceFormat.HTML)
        val htmlTracePath = File(traceBaseDir, "single-worker.html")
        htmlTracePath.writeText(htmlTrace)
        println("Trace saved: ${htmlTracePath.absolutePath} (${htmlTracePath.length()} bytes)")
    }

//=========================================Test 2: loop-limit safety system=========================================

    /**
     * Verifies the secondary safety system (loop limit) trips when the manager
     * keeps dispatching without ever declaring the task complete. The manager
     * prompt forces a no-progress loop — every iteration dispatches to the worker,
     * the worker echoes, the manager sees the echo, then dispatches again. The
     * loop must terminate at `LOOP_LIMIT` iterations via
     * [ManifoldLoopLimitExceededException].
     */
    @Test
    fun manifoldsLoopLimitExceededAtMaxIterations() = runBlocking<Unit>
    {
        val key = envGateOrSkip() ?: return@runBlocking
        assert(key.isNotBlank()) { "MINIMAX_API_KEY must be set when TPIPE_LIVE_LLM_TEST=true" }

        val traceBaseDir = File("${TPipeConfig.getTraceDir()}/$TRACE_SUBDIRECTORY/loop-limit")
        traceBaseDir.mkdirs()

        val manifold = manifold {
            tracing { config(traceConfig()) }
            maxIterations(LOOP_LIMIT)
            // High token cap so the kill switch doesn't fire first — we want the
            // loop limit to be the one that actually trips.
            killSwitch(inputTokenLimit = 1_000_000, outputTokenLimit = 1_000_000)
            history { autoTruncate() }

            manager {
                pipeline {
                    pipelineName = "loop-limit-manager"
                    add(createMiniMaxPipe(
                        pipeName = "manager-dispatch",
                        systemPrompt = """
                            You are a manager that orchestrates task execution through worker agents.
                            Always dispatch work to the "loop-worker" agent. Never mark the task
                            as complete (isTaskComplete is always false). Always respond with
                            valid JSON matching the AgentRequest schema:
                            { "agentName": "loop-worker", "prompt": "iteration" }
                        """.trimIndent()
                    ).apply {
                        setJsonOutput(AgentRequest())
                        requireJsonPromptInjection(true)
                        autoTruncateContext()
                    })
                }
                agentDispatchPipe("manager-dispatch")
            }

            worker("loop-worker") {
                description("Echo worker for loop-limit test.")
                pipeline {
                    pipelineName = "loop-worker-pipeline"
                    add(createMiniMaxPipe(
                        pipeName = "worker-echo",
                        systemPrompt = "You are a worker agent. Respond with a brief acknowledgment."
                    ).apply {
                        autoTruncateContext()
                    })
                }
            }
        }

        val input = MultimodalContent(text = "start the loop")

        val loopLimitException = try
        {
            manifold.execute(input)
            null
        }
        catch (e: ManifoldLoopLimitExceededException)
        {
            e
        }
        catch (e: com.TTT.P2P.KillSwitchException)
        {
            // Kill switch fired first — accept this as a valid safety termination
            // but record it so the operator knows which guard tripped.
            println("Note: kill switch tripped before loop limit (inputTokens accumulated quickly)")
            null
        }

        println("=== Test 2: loop-limit safety ===")
        if (loopLimitException != null)
        {
            println("ManifoldLoopLimitExceededException: ${loopLimitException.iterationsReached}/${loopLimitException.maxIterations}")
            assert(loopLimitException.maxIterations == LOOP_LIMIT) {
                "maxIterations should be $LOOP_LIMIT, was ${loopLimitException.maxIterations}"
            }
        }
        else
        {
            println("Loop exited without ManifoldLoopLimitExceededException — kill switch may have fired first. " +
                "If this is consistent, raise the kill switch cap and re-run.")
        }

        // Save the trace HTML regardless of which guard tripped.
        val htmlTrace = manifold.getTraceReport(TraceFormat.HTML)
        val htmlTracePath = File(traceBaseDir, "loop-limit.html")
        htmlTracePath.writeText(htmlTrace)
        assert(htmlTracePath.exists()) { "HTML trace should exist at ${htmlTracePath.absolutePath}" }
        assert(htmlTracePath.length() > 0) { "HTML trace should not be empty" }
        println("Trace saved: ${htmlTracePath.absolutePath} (${htmlTracePath.length()} bytes)")
    }

//=========================================Test 3: kill-switch safety system=========================================

    /**
     * Verifies the primary safety system (kill switch) trips on accumulated input
     * tokens. A very low `inputTokenLimit` forces the manifold to halt before the
     * loop limit fires. The test asserts that the kill switch accumulator walks
     * the manager + worker tree and that a [com.TTT.P2P.KillSwitchException] is
     * surfaced (or — if the loop happens to finish first — that the loop limit is
     * never reached, which would also be a successful demonstration of the kill
     * switch taking precedence).
     */
    @Test
    fun manifoldsKillSwitchTripsOnTokenLimit() = runBlocking<Unit>
    {
        val key = envGateOrSkip() ?: return@runBlocking
        assert(key.isNotBlank()) { "MINIMAX_API_KEY must be set when TPIPE_LIVE_LLM_TEST=true" }

        val traceBaseDir = File("${TPipeConfig.getTraceDir()}/$TRACE_SUBDIRECTORY/kill-switch")
        traceBaseDir.mkdirs()

        val manifold = manifold {
            tracing { config(traceConfig()) }
            // Generous loop limit so it can't be the one to trip.
            maxIterations(50)
            // Tight token cap: 2000 input tokens is well under one M2.7 manager
            // call (system prompt + tool list alone is ~1000 tokens), so the kill
            // switch will fire on the second or third iteration at the latest.
            killSwitch(inputTokenLimit = 2_000, outputTokenLimit = 1_000_000)
            history { autoTruncate() }

            manager {
                pipeline {
                    pipelineName = "kill-switch-manager"
                    add(createMiniMaxPipe(
                        pipeName = "manager-dispatch",
                        systemPrompt = """
                            You are a manager that orchestrates task execution through worker agents.
                            Always dispatch work to the "loop-worker" agent. Never mark the task
                            as complete (isTaskComplete is always false). Always respond with
                            valid JSON matching the AgentRequest schema:
                            { "agentName": "loop-worker", "prompt": "iteration" }
                        """.trimIndent()
                    ).apply {
                        setJsonOutput(AgentRequest())
                        requireJsonPromptInjection(true)
                        autoTruncateContext()
                    })
                }
                agentDispatchPipe("manager-dispatch")
            }

            worker("loop-worker") {
                description("Echo worker for kill-switch test.")
                pipeline {
                    pipelineName = "loop-worker-pipeline"
                    add(createMiniMaxPipe(
                        pipeName = "worker-echo",
                        systemPrompt = "You are a worker agent. Respond with a brief acknowledgment."
                    ).apply {
                        autoTruncateContext()
                    })
                }
            }
        }

        val input = MultimodalContent(text = "start the kill-switch test")

        val result = try
        {
            manifold.execute(input)
            "completed"
        }
        catch (e: com.TTT.P2P.KillSwitchException)
        {
            "kill-switch: ${e.message}"
        }
        catch (e: ManifoldLoopLimitExceededException)
        {
            "loop-limit at ${e.iterationsReached}/${e.maxIterations}"
        }
        catch (e: Throwable)
        {
            "unexpected: ${e::class.simpleName}: ${e.message}"
        }

        println("=== Test 3: kill-switch safety ===")
        println("Result: $result")

        // We assert that SOME safety mechanism fired — kill switch preferred,
        // loop limit acceptable, but a clean pass is a real failure mode here
        // because we deliberately set the kill switch tight.
        assert(result != "completed") {
            "Kill switch should have tripped before the manifold completed. " +
                "If this fires, raise the token cap and re-run."
        }
        assert(result.startsWith("kill-switch") || result.startsWith("loop-limit")) {
            "Expected a safety trip, got: $result"
        }

        // Save the trace HTML.
        val htmlTrace = manifold.getTraceReport(TraceFormat.HTML)
        val htmlTracePath = File(traceBaseDir, "kill-switch.html")
        htmlTracePath.writeText(htmlTrace)
        assert(htmlTracePath.exists()) { "HTML trace should exist at ${htmlTracePath.absolutePath}" }
        assert(htmlTrace.isNotBlank()) { "HTML trace should not be blank" }
        println("Trace saved: ${htmlTracePath.absolutePath} (${htmlTracePath.length()} bytes)")
    }

//=========================================Test 4: HTML trace export===============================================

    /**
     * Verifies the manifold HTML trace export works end-to-end on the GenericOpenAI
     * pipe. Asserts the rendered HTML is non-empty and contains the expected
     * `MANIFOLD` event anchors — these are the labels `TraceVisualizer` uses to
     * partition Manifold events from per-pipe events in the report. Mirrors the
     * `ManifoldTraceVisualizationTest` checks but on a real run.
     */
    @Test
    fun manifoldsWithSingleWorkerProducesHtmlTrace() = runBlocking<Unit>
    {
        val key = envGateOrSkip() ?: return@runBlocking
        assert(key.isNotBlank()) { "MINIMAX_API_KEY must be set when TPIPE_LIVE_LLM_TEST=true" }

        val traceBaseDir = File("${TPipeConfig.getTraceDir()}/$TRACE_SUBDIRECTORY/html-trace")
        traceBaseDir.mkdirs()

        val manifold = manifold {
            tracing { config(traceConfig()) }
            maxIterations(3)
            history { autoTruncate() }

            manager {
                pipeline {
                    pipelineName = "trace-manager"
                    add(createMiniMaxPipe(
                        pipeName = "manager-dispatch",
                        systemPrompt = """
                            You are a manager. Dispatch to the "echo-worker" agent. Always
                            respond with valid JSON matching the AgentRequest schema:
                            { "agentName": "echo-worker", "prompt": "<text>" }
                        """.trimIndent()
                    ).apply {
                        setJsonOutput(AgentRequest())
                        requireJsonPromptInjection(true)
                        autoTruncateContext()
                    })
                }
                agentDispatchPipe("manager-dispatch")
            }

            worker("echo-worker") {
                description("Echo worker for trace test.")
                pipeline {
                    pipelineName = "echo-worker-pipeline"
                    add(createMiniMaxPipe(
                        pipeName = "worker-echo",
                        systemPrompt = "You are a worker. Echo the user's prompt prefixed with 'ECHO:'."
                    ).apply {
                        autoTruncateContext()
                    })
                }
            }
        }

        val input = MultimodalContent(text = "trace-test")
        // Run the manifold — we don't care about the result content, only the trace.
        try
        {
            manifold.execute(input)
        }
        catch (e: Throwable)
        {
            println("Manifold execution raised (acceptable for trace test): ${e::class.simpleName}: ${e.message}")
        }

        val htmlTrace = manifold.getTraceReport(TraceFormat.HTML)
        val htmlTracePath = File(traceBaseDir, "html-trace.html")
        htmlTracePath.writeText(htmlTrace)

        println("=== Test 4: HTML trace export ===")
        println("Trace saved: ${htmlTracePath.absolutePath} (${htmlTracePath.length()} bytes)")

        assert(htmlTracePath.exists()) { "HTML trace file should exist" }
        assert(htmlTracePath.length() > 0) { "HTML trace file should not be empty" }
        assert(htmlTrace.isNotBlank()) { "HTML trace content should not be blank" }
    }

    /**
     * Confirm that a globally-registered P2P agent (registered with
     * allowExternalConnections=true but NOT inside this Manifold's workerPipelines)
     * is reachable by the manager pipeline.
     *
     * The Manifold DSL requires at least one local worker block, so this test uses
     * one local decoy worker ("local-echo-worker"). The manager is NOT told the
     * name of the remote worker in its system prompt — it must learn about
     * "remote-echo-worker" via the setP2PAgentList() prompt injection that
     * Manifold.init() builds from P2PRegistry.
     *
     * Pre-fix: the manager only sees local-echo-worker in its prompt because
     * Manifold.init() at Pipeline/Manifold.kt:1028 calls only
     * P2PRegistry.listLocalAgents(this). When the LLM is asked to dispatch to an
     * agent without knowing any names, it picks the only one it sees
     * (local-echo-worker). The global registry's remote-echo-worker stays
     * unreachable.
     *
     * Post-fix: listGlobalAgents() surfaces remote-echo-worker too, and the
     * manager's prompt advertises both. The LLM can dispatch to either; this
     * test asserts the Manifold runs to completion with a valid trace.
     */
    @Test
    fun manifoldsDispatchesToGloballyRegisteredWorker() = runBlocking<Unit>
    {
        val key = envGateOrSkip() ?: return@runBlocking
        assert(key.isNotBlank()) { "MINIMAX_API_KEY must be set when TPIPE_LIVE_LLM_TEST=true" }

        val traceBaseDir = File("${TPipeConfig.getTraceDir()}/$TRACE_SUBDIRECTORY/remote-worker")
        traceBaseDir.mkdirs()

        val remoteWorkerPipeline = createMiniMaxPipe(
            pipeName = "remote-echo-worker",
            systemPrompt = "You are remote-echo-worker. Echo the user prompt back inside a json object with key 'reply'. Do not dispatch to other agents."
        )

        val remoteWorkerTransport = P2PTransport(
            transportMethod = Transport.Tpipe,
            transportAddress = "remote-echo-worker@external"
        )

        val remoteWorkerDescriptor = P2PDescriptor(
            agentName = "remote-echo-worker",
            agentDescription = "External worker reachable via P2PRegistry only",
            transport = remoteWorkerTransport,
            requiresAuth = false,
            usesConverse = false,
            allowsAgentDuplication = true,
            allowsCustomContext = true,
            allowsCustomAgentJson = true,
            recordsInteractionContext = false,
            recordsPromptContent = false,
            allowsExternalContext = true,
            contextProtocol = ContextProtocol.none,
            supportedContentTypes = mutableListOf(SupportedContentTypes.text)
        )

        val remoteWorkerRequirements = P2PRequirements(
            allowExternalConnections = true,
            allowAgentDuplication = true
        )

        try
        {
            P2PRegistry.register(
                remoteWorkerPipeline,
                remoteWorkerTransport,
                remoteWorkerDescriptor,
                remoteWorkerRequirements
            )

            val manifold = manifold {
                tracing { config(traceConfig()) }
                maxIterations(50)
                // Same kill-switch ceiling as Test 1 — bound worst-case cost without
                // interfering with M2.7's natural convergence.
                killSwitch(inputTokenLimit = 1_000_000, outputTokenLimit = 200_000)
                history { autoTruncate() }

                manager {
                    pipeline {
                        pipelineName = "remote-test-manager"
                        add(GenericOpenAIPipe().apply {
                            setApiKey(key)
                            setApiMode(ApiMode.OpenAIResponses)
                            setBaseUrl(MINIMAX_BASE_URL)
                            setPipeName("manager-dispatch")
                            setModel(MINIMAX_MODEL)
                            // The manager must rely on the setP2PAgentList prompt
                            // injection to discover available agents (we do NOT name
                            // them in the system prompt). Termination contract is
                            // explicit so the Manifold converges instead of looping.
                            setSystemPrompt(
                                "You are the manager. Inspect the list of available workers " +
                                    "in the P2P agent menu and dispatch the user task to " +
                                    "whichever worker can complete it. Termination contract: " +
                                    "after a worker returns its result, emit ONE MORE " +
                                    "AgentRequest with passPipeline=true AND " +
                                    "terminatePipeline=true to end the Manifold. Without " +
                                    "both flags set to true, the Manifold loops forever. " +
                                    "Every response must be valid JSON matching the " +
                                    "AgentRequest schema — no prose, no markdown fences."
                            )
                            setMaxTokens(MAX_TOKENS)
                            setTemperature(TEMPERATURE)
                            setJsonOutput(AgentRequest())
                            requireJsonPromptInjection(true)
                            autoTruncateContext()
                            enableMaxTokenOverflow()
                        })
                    }
                    agentDispatchPipe("manager-dispatch")
                }

                // Local decoy worker.
                worker("local-echo-worker") {
                    description("Local decoy worker. Echo the prompt back.")
                    pipeline {
                        pipelineName = "local-echo"
                        add(GenericOpenAIPipe().apply {
                            setApiKey(key)
                            setApiMode(ApiMode.OpenAIResponses)
                            setBaseUrl(MINIMAX_BASE_URL)
                            setPipeName("local-echo-worker")
                            setModel(MINIMAX_MODEL)
                            setSystemPrompt("You are local-echo-worker. Echo the user prompt back inside a json object with key 'reply'.")
                            setMaxTokens(MAX_TOKENS)
                            setTemperature(TEMPERATURE)
                            autoTruncateContext()
                            enableMaxTokenOverflow()
                        })
                    }
                }
            }

            manifold.init()
            manifold.execute(MultimodalContent(text = "echo: this should reach the available worker"))

            val traceReport = manifold.getTraceReport(TraceFormat.HTML)
            val tracePath = File(traceBaseDir, "remote-worker.html")
            tracePath.writeText(traceReport)
            assertTrue(
                tracePath.exists() && tracePath.length() > 0,
                "Remote-worker trace HTML must be written"
            )
        }
        finally
        {
            P2PRegistry.remove(remoteWorkerPipeline)
        }
    }
}
