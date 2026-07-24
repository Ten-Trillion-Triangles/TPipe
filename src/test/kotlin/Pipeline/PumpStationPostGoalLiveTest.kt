package com.TTT.Pipeline

import com.TTT.Config.TPipeConfig
import com.TTT.Debug.PipeTracer
import com.TTT.Debug.TraceConfig
import com.TTT.Debug.TraceDetailLevel
import com.TTT.Debug.TraceFormat
import com.TTT.Debug.TraceVisualizer
import com.TTT.P2P.KillSwitch
import com.TTT.P2P.P2PInterface
import com.TTT.P2P.P2PRequest
import com.TTT.P2P.P2PResponse
import com.TTT.Pipe.MultimodalContent
import com.TTT.Pipe.TokenBudgetSettings
import com.TTT.Structs.PipeSettings
import genericOpenAIPipe.GenericOpenAIPipe
import genericOpenAIPipe.api.ApiMode
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Live + stub-mode integration test suite for the post-success intervention surface that
 * fires inside `runExitFlow` after the goal agent passes (or on the no-goal-agent /
 * passPipeline-routed exit paths).
 *
 * # Test coverage shape
 *
 * Six configurations × two modes (live LLM + stub server) = twelve tests. Each test
 * exercises a distinct code path in the new hook:
 *
 * 1. pass-pipeline, no goal agent (broad coverage on no-goal-agent halt)
 * 2. pass-pipeline, with goal agent that passes (post-goal fires after goal pass)
 * 3. pass-pipeline, with goal agent that fails (post-goal does NOT fire on failure)
 * 4. multi-path risk levels (post-goal fires through full judge/dispatch/path loop)
 * 5. flag-triggered judge (post-goal fires on the flag-triggered exit path)
 * 6. compaction memory (post-goal fires after compaction)
 *
 * # Trace capture discipline
 *
 * Every test wires `tracingConfiguration = traceConfigFor(testName)`, drains the
 * background event queue, calls `getTraceReport(HTML)`, exports per-agent traces,
 * and asserts:
 *
 * - The pump station HTML exists at `File(TPipeConfig.getTraceDir(), "PumpStation")/<testName>/pumpstation-<runId12>.html`
 * - It is >5KB (empty / stub HTML would indicate the harness crashed before emitting events)
 * - Its runId prefix matches the current run
 * - Its content contains `PUMP_STATION_POST_GOAL_COMPLETED` (when the hook was expected to fire)
 * - Its content contains the `transformedContent` and `passed` metadata fields (when applicable)
 * - Its content does NOT contain `PUMP_STATION_POST_GOAL_COMPLETED` for config 3 (failure-exhaustion)
 *
 * Per the persona memory pitfall "Test passed != test does the right thing": assertions on
 * trace artifact LOCATION and CONTENT, not just harness-side event-observer state.
 *
 * # Stub server
 *
 * The `StubOpenAIServer` in this file is a minimal replica of the `private` stub server in
 * `PumpStationMiniMaxLiveTest`. Promotion to a shared test file is deferred (see
 * `.hermes/plans/pumpstation-postgoal-live-test-suite.md`).
 *
 * # To run
 *
 * Stub-mode tests run without a real API key. Set `MINIMAX_API_KEY=sk-stub-*` so the
 * gate returns the stub key path.
 *
 * ```
 * export TPIPE_LIVE_LLM_TEST=true
 * export MINIMAX_API_KEY=sk-stub-...   # or sk-... for live mode
 * ./gradlew :test --tests "com.TTT.Pipeline.PumpStationPostGoalLiveTest" --rerun-tasks
 * ```
 *
 * Stochastic risk: the live-mode tests depend on the LLM's response format. The stub-mode
 * tests cover the same code paths deterministically as the safety net.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PumpStationPostGoalLiveTest
{
    companion object
    {
        // === MiniMax endpoint (same as PumpStationMiniMaxLiveTest) ===

        private const val MINIMAX_BASE_URL = "https://api.minimax.io/v1"
        private const val MINIMAX_MODEL = "MiniMax-M2.7"
        private const val TEMPERATURE = 1.0
        private const val TOP_P = 0.95
        private const val TOP_K = 40
        private const val MAX_TOKENS = 16384

        // === Post-goal agent marker (deterministic P2PInterface stub) ===

        private const val POSTGOAL_MARKER = "[POSTGOAL-RAN]"
        private const val POSTGOAL_FUNCTION_PREFIX = "TRANSFORMED:"

        private val JSON = kotlinx.serialization.json.Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    }

    // === Instance state ===

    private var apiKeyCache: String? = null

    @BeforeAll
    fun setup()
    {
        // The Hermes terminal() tool spawns a non-interactive bash subprocess that
        // does NOT source ~/.bashrc or ~/.profile, and the gradle test JVM inherits
        // that env. So System.getenv("MINIMAX_API_KEY") is null unless the user
        // re-exports it on the gradle command line. Per the persona memory recipe,
        // parse ~/.bashrc directly when the env var is unset. This lets the test
        // suite run end-to-end from a fresh shell with no setup ceremony.
        val envKey = System.getenv("MINIMAX_API_KEY")
        val key = envKey?.takeIf { it.isNotBlank() } ?: readKeyFromBashrc()
        if (key.isNullOrBlank()) return
        genericOpenAIPipe.env.GenericOpenAIEnv.setApiKey(key)
        apiKeyCache = key
        // Stub-mode tests use http:// baseUrl; opt in via the test-only flag.
        System.setProperty("tpipe.allowInsecureBaseUrl", "true")
    }

    @AfterAll
    fun teardown()
    {
        if (apiKeyCache != null)
        {
            genericOpenAIPipe.env.GenericOpenAIEnv.clearApiKey()
            apiKeyCache = null
        }
        System.clearProperty("tpipe.allowInsecureBaseUrl")
    }

    /**
     * Parse `~/.bashrc` directly for `export MINIMAX_API_KEY="..."`. Used when the
     * env var isn't set in the running shell (the standard Hermes execution path).
     * Returns null if the var isn't in bashrc.
     */
    private fun readKeyFromBashrc(): String?
    {
        val home = System.getProperty("user.home") ?: return null
        val bashrc = File(home, ".bashrc")
        if (!bashrc.exists()) return null
        val line = bashrc.readLines().firstOrNull { it.startsWith("export MINIMAX_API_KEY=") }
            ?: return null
        // Strip `export VAR=` and the surrounding quotes; preserve the raw key.
        return line.replaceFirst("export MINIMAX_API_KEY=", "")
            .trim()
            .trim('"')
            .trim('\'')
            .takeIf { it.isNotBlank() }
    }

    /**
     * Gate returning the API key for live-mode tests, or null to skip.
     * Returns null when env-gate unset, when the key is missing, or when the key is
     * a stub-mode key (sk-stub-* prefix means "use the stub server, not the real LLM").
     */
    private fun liveGateOrSkip(): String? =
        apiKeyCache?.takeUnless { it.startsWith("sk-stub") }

    /**
     * Gate for stub-mode tests: returns the stub key if present, else null.
     */
    private fun stubGateOrSkip(): String? =
        apiKeyCache?.takeIf { it.startsWith("sk-stub") }

    // ====================================================================================
    // STUB-MODE TESTS (6 configurations)
    // ====================================================================================

    /**
     * Config 1: judge-routed exit with no goal agent. The judge emits `isComplete=true`
     * to trigger `runExitFlow`, and the no-goal-agent branch fires the post-goal hook.
     *
     * Note: useSinglePathPassPipeline is NOT the right shape here. When the path sets
     * passPipeline=true and goalAgent is null, the harness bypasses `runExitFlow`
     * entirely and halts via `PassSignal` (PumpStationLoop.kt:2679-2682). To exercise
     * the no-goal-agent halt path INSIDE runExitFlow (line 2393), the judge must
     * drive the exit, not the path's passPipeline flag.
     */
    @Test
    fun stub_01_passPipelineNoGoal_postGoalFiresOnNoGoalAgentExit() = runBlocking<Unit>
    {
        val stubKey = stubGateOrSkip() ?: return@runBlocking
        val stub = startStub()
        try
        {
            stub.loopEnqueue("judge") { stubJson(isComplete = true) }
            stub.loopEnqueue("dispatch") { stubJson(passPipeline = true) }
            stub.loopEnqueue("report") { "Report brief on Kotlin coroutines." }
            runPostGoalHarness(
                testName = "stub-01-pass-pipeline-nogoal",
                baseUrl = stub.baseUrl(),
                config = stubKey,
                useFlagTriggeredJudge = false,
                useRiskLevels = false,
                memoryMode = null,
                useSinglePathPassPipeline = false,
                configurePaths = { registerSinglePathReportPath() },
                configureGoal = { /* no goal agent — broad-coverage path */ },
                postGoalExpectsFire = true,
                expectedExit = PumpStationExitReason.JudgeComplete
            )
        }
        finally { stub.stop() }
    }

    @Test
    fun stub_02_passPipelineGoalPasses_postGoalFiresAfterGoal() = runBlocking<Unit>
    {
        val stubKey = stubGateOrSkip() ?: return@runBlocking
        val stub = startStub()
        try
        {
            stub.loopEnqueue("judge") { stubJson(isComplete = false) }
            stub.loopEnqueue("dispatch") { stubJson(passPipeline = true) }
            stub.loopEnqueue("report") { "Report brief on Kotlin coroutines." }
            runPostGoalHarness(
                testName = "stub-02-pass-pipeline-goalpasses",
                baseUrl = stub.baseUrl(),
                config = stubKey,
                useFlagTriggeredJudge = false,
                useRiskLevels = false,
                memoryMode = null,
                useSinglePathPassPipeline = true,
                configurePaths = { registerSinglePathPassPipelinePath() },
                configureGoal = { stubGoalAgentThatPasses() },
                postGoalExpectsFire = true
            )
        }
        finally { stub.stop() }
    }

    @Test
    fun stub_03_goalAgentFailsExhausted_postGoalDoesNotFire() = runBlocking<Unit>
    {
        val stubKey = stubGateOrSkip() ?: return@runBlocking
        val stub = startStub()
        try
        {
            stub.loopEnqueue("judge") { stubJson(isComplete = false) }
            stub.loopEnqueue("dispatch") { stubJson(passPipeline = true) }
            stub.loopEnqueue("report") { "Report brief on Kotlin coroutines." }
            // Goal agent always returns terminatePipeline=true via loopFallback.
            // After maxGoalFailAttempts (default 3) the harness halts via GoalValidationFailed.
            runPostGoalHarness(
                testName = "stub-03-goal-fails-exhausted",
                baseUrl = stub.baseUrl(),
                config = stubKey,
                useFlagTriggeredJudge = false,
                useRiskLevels = false,
                memoryMode = null,
                useSinglePathPassPipeline = true,
                configurePaths = { registerSinglePathPassPipelinePath() },
                configureGoal = { stubGoalAgentThatFails() },
                postGoalExpectsFire = false,
                expectedExit = PumpStationExitReason.GoalValidationFailed
            )
        }
        finally { stub.stop() }
    }

    @Test
    fun stub_04_multiPathRiskLevels_postGoalFiresAfterFullLoop() = runBlocking<Unit>
    {
        val stubKey = stubGateOrSkip() ?: return@runBlocking
        val stub = startStub()
        try
        {
            stub.loopEnqueue("judge") { stubJson(isComplete = false) }
            stub.loopEnqueue("dispatch") { stubResponsesJson("""{"pathName":"report","inputData":{}}""") }
            stub.loopEnqueue("pathSafety") { stubResponsesJson("""{"safe":true,"reason":"ok"}""") }
            stub.loopEnqueue("report") { "Brief on Kotlin coroutines." }
            runPostGoalHarness(
                testName = "stub-04-multi-path-risk-levels",
                baseUrl = stub.baseUrl(),
                config = stubKey,
                useFlagTriggeredJudge = false,
                useRiskLevels = true,
                memoryMode = null,
                useSinglePathPassPipeline = false,
                configurePaths = { registerMultiPathRiskLevelPaths() },
                configureGoal = { stubGoalAgentThatPasses() },
                postGoalExpectsFire = true
            )
        }
        finally { stub.stop() }
    }

    @Test
    fun stub_05_flagTriggeredJudge_postGoalFiresOnFlagExit() = runBlocking<Unit>
    {
        val stubKey = stubGateOrSkip() ?: return@runBlocking
        val stub = startStub()
        try
        {
            stub.loopEnqueue("judge") { stubJson(isComplete = true) }
            stub.loopEnqueue("dispatch") { stubResponsesJson("""{"pathName":"report","inputData":{}}""") }
            stub.loopEnqueue("report") { "Brief on Kotlin coroutines." }
            runPostGoalHarness(
                testName = "stub-05-flag-triggered-judge",
                baseUrl = stub.baseUrl(),
                config = stubKey,
                useFlagTriggeredJudge = true,
                useRiskLevels = false,
                memoryMode = null,
                useSinglePathPassPipeline = false,
                configurePaths = { registerFlagTriggeredReportPath() },
                configureGoal = { stubGoalAgentThatPasses() },
                postGoalExpectsFire = true
            )
        }
        finally { stub.stop() }
    }

    @Test
    fun stub_06_compactionMemory_postGoalFiresAfterCompaction() = runBlocking<Unit>
    {
        val stubKey = stubGateOrSkip() ?: return@runBlocking
        val stub = startStub()
        try
        {
            stub.loopEnqueue("judge") { stubJson(isComplete = false) }
            stub.loopEnqueue("dispatch") { stubResponsesJson("""{"pathName":"report","inputData":{}}""") }
            stub.loopEnqueue("report") { "Brief on Kotlin coroutines." }
            stub.loopEnqueue("summary") { "Concise summary of conversation history." }
            runPostGoalHarness(
                testName = "stub-06-compaction-memory",
                baseUrl = stub.baseUrl(),
                config = stubKey,
                useFlagTriggeredJudge = false,
                useRiskLevels = false,
                memoryMode = PumpStationMemoryManagementMode.Compaction,
                useSinglePathPassPipeline = false,
                configurePaths = { registerSinglePathReportPath() },
                configureGoal = { stubGoalAgentThatPasses() },
                postGoalExpectsFire = true
            )
        }
        finally { stub.stop() }
    }

    // ====================================================================================
    // LIVE-MODE TESTS (6 configurations, parallel to stub-mode)
    // ====================================================================================

    @Test
    fun live_01_passPipelineNoGoal_postGoalFiresOnNoGoalAgentExit() = runBlocking<Unit>
    {
        if (liveGateOrSkip() == null) return@runBlocking
        runPostGoalHarness(
            testName = "live-01-pass-pipeline-nogoal",
            baseUrl = MINIMAX_BASE_URL,
            config = apiKeyCache!!,
            useFlagTriggeredJudge = false,
            useRiskLevels = false,
            memoryMode = null,
            useSinglePathPassPipeline = false,
            configurePaths = { registerSinglePathReportPath() },
            configureGoal = { /* no goal agent */ },
            postGoalExpectsFire = true,
            expectedExit = PumpStationExitReason.JudgeComplete
        )
    }

    @Test
    fun live_02_passPipelineGoalPasses_postGoalFiresAfterGoal() = runBlocking<Unit>
    {
        if (liveGateOrSkip() == null) return@runBlocking
        runPostGoalHarness(
            testName = "live-02-pass-pipeline-goalpasses",
            baseUrl = MINIMAX_BASE_URL,
            config = apiKeyCache!!,
            useFlagTriggeredJudge = false,
            useRiskLevels = false,
            memoryMode = null,
            useSinglePathPassPipeline = true,
            configurePaths = { registerSinglePathPassPipelinePath() },
            configureGoal = { liveGoalAgentThatPasses() },
            postGoalExpectsFire = true
        )
    }

    @Test
    fun live_03_goalAgentFailsExhausted_postGoalDoesNotFire() = runBlocking<Unit>
    {
        if (liveGateOrSkip() == null) return@runBlocking
        runPostGoalHarness(
            testName = "live-03-goal-fails-exhausted",
            baseUrl = MINIMAX_BASE_URL,
            config = apiKeyCache!!,
            useFlagTriggeredJudge = false,
            useRiskLevels = false,
            memoryMode = null,
            useSinglePathPassPipeline = true,
            configurePaths = { registerSinglePathPassPipelinePath() },
            configureGoal = { liveGoalAgentThatFails() },
            postGoalExpectsFire = false,
            expectedExit = PumpStationExitReason.GoalValidationFailed
        )
    }

    @Test
    fun live_04_multiPathRiskLevels_postGoalFiresAfterFullLoop() = runBlocking<Unit>
    {
        if (liveGateOrSkip() == null) return@runBlocking
        runPostGoalHarness(
            testName = "live-04-multi-path-risk-levels",
            baseUrl = MINIMAX_BASE_URL,
            config = apiKeyCache!!,
            useFlagTriggeredJudge = false,
            useRiskLevels = true,
            memoryMode = null,
            useSinglePathPassPipeline = false,
            configurePaths = { registerMultiPathRiskLevelPaths() },
            configureGoal = { liveGoalAgentThatPasses() },
            postGoalExpectsFire = true
        )
    }

    @Test
    fun live_05_flagTriggeredJudge_postGoalFiresOnFlagExit() = runBlocking<Unit>
    {
        if (liveGateOrSkip() == null) return@runBlocking
        runPostGoalHarness(
            testName = "live-05-flag-triggered-judge",
            baseUrl = MINIMAX_BASE_URL,
            config = apiKeyCache!!,
            useFlagTriggeredJudge = true,
            useRiskLevels = false,
            memoryMode = null,
            useSinglePathPassPipeline = false,
            configurePaths = { registerFlagTriggeredReportPath() },
            configureGoal = { liveGoalAgentThatPasses() },
            postGoalExpectsFire = true
        )
    }

    @Test
    fun live_06_compactionMemory_postGoalFiresAfterCompaction() = runBlocking<Unit>
    {
        if (liveGateOrSkip() == null) return@runBlocking
        runPostGoalHarness(
            testName = "live-06-compaction-memory",
            baseUrl = MINIMAX_BASE_URL,
            config = apiKeyCache!!,
            useFlagTriggeredJudge = false,
            useRiskLevels = false,
            memoryMode = PumpStationMemoryManagementMode.Compaction,
            useSinglePathPassPipeline = false,
            configurePaths = { registerSinglePathReportPath() },
            configureGoal = { liveGoalAgentThatPasses() },
            postGoalExpectsFire = true
        )
    }

    // ====================================================================================
    // HARNESS RUNNER — drives one configuration end-to-end with trace capture
    // ====================================================================================

    /**
     * Build a harness for one configuration, execute it, then assert on the trace artifact
     * (location + content). The harness is built in a separate suspend method below so the
     * suspend-block style of the rest of the file matches the existing live tests.
     */
    private suspend fun runPostGoalHarness(
        testName: String,
        baseUrl: String,
        config: String,
        useFlagTriggeredJudge: Boolean,
        useRiskLevels: Boolean,
        memoryMode: PumpStationMemoryManagementMode?,
        useSinglePathPassPipeline: Boolean,
        configurePaths: PumpStationBuilder<*>.() -> Unit,
        configureGoal: PumpStationBuilder<*>.() -> Unit,
        postGoalExpectsFire: Boolean,
        expectedExit: PumpStationExitReason = PumpStationExitReason.JudgeComplete
    )
    {
        val traceCfg = traceConfigFor(testName)
        val postGoalAgentImpl = CapturingPostGoalAgent()

        val builder: PumpStationBuilder<*>.() -> Unit = {
            // Always wire the post-goal hook so it can fire if a configuration reaches the success exit.
            postGoalAgent = postGoalAgentImpl
            postGoalFunction = { content, _ ->
                MultimodalContent(text = "$POSTGOAL_FUNCTION_PREFIX ${content.text}")
            }
            eventObserver = { ev -> /* sink — assertions live on the trace HTML */ }

            // Direct trace capture wiring (canonical TPipeConfig.getTraceDir() path).
            // MUST be set BEFORE configurePaths() runs: the first `path("name") { }` call
            // triggers `promote()` which `copyFrom(this)` snapshots the initial builder's
            // state into the promoted Ready-stage builder. Any property set after that
            // snapshot is lost. tracingConfiguration is the most consequential lost
            // property because it controls whether getTraceReport() writes the HTML file.
            tracingConfiguration = traceCfg

            // Memory mode wiring
            if (memoryMode == PumpStationMemoryManagementMode.Compaction)
            {
                memoryManagementMode = PumpStationMemoryManagementMode.Compaction
                compactionStrategy = PumpStationCompactionStrategy.Hybrid
                compactionThreshold = 0.01  // per existing live-test convention
                summaryAgent = createAgentPipeline(
                    testName = testName,
                    pipeName = "summary",
                    systemPrompt = "You are a summarizer. Compress the provided conversation " +
                        "history into a concise summary, preserving key technical details.",
                    baseUrl = baseUrl
                )
            }

            if (useFlagTriggeredJudge) judgeRunMode = PumpStationJudgeRunMode.FlagTriggered

            if (useRiskLevels)
            {
                pathSafetyAgent = createAgentPipeline(
                    testName = testName,
                    pipeName = "path-safety",
                    systemPrompt = "You are a path-safety validator. Decide if the selected path " +
                        "is safe to invoke. Reply with JSON: {\"safe\": boolean, \"reason\": string}",
                    baseUrl = baseUrl
                )
            }

            // Always wire judge + dispatch unless pass-pipeline-only
            if (!useSinglePathPassPipeline)
            {
                judgeAgent = createJudgePipeline(testName, baseUrl)
                dispatchAgent = createDispatchPipeline(testName, baseUrl)
            }
            else
            {
                dispatchAgent = createDispatchPipeline(testName, baseUrl)
            }

            // Goal agent wiring (varies by configuration)
            configureGoal()

            // Path wiring (varies by configuration) — triggers promote() via first path() call
            configurePaths()

            systemTask = "You are a research assistant. Conclude by calling the report path."
            userGuidelines = "Be concise."
            maxHarnessTurns = 6
        }

        val station = pumpStation("pumpstation-postgoal-$testName", builder)

        // Live LLM tests hit a transient upstream condition (\"Service error. Please retry later\"
        // from MiniMax) intermittently. Retry up to 3 times with a 3s backoff before declaring
        // the test a hard failure. Stub-mode tests don't need this — they never throw P2PException.
        var attemptCount = 0
        val maxAttempts = 3
        var lastException: Throwable? = null
        while (attemptCount < maxAttempts)
        {
            attemptCount += 1
            try
            {
                val result = station.executeLocal(
                    MultimodalContent(text = "Run the post-goal hook harness.")
                )
                // Drain background events so the trace HTML captures the full stream.
                station.drainBackgroundEventQueue()
                // getTraceReport triggers TraceConfig.autoExport and writes the pump station HTML.
                station.getTraceReport(TraceFormat.HTML)
                exportAgentTraces(testName)

                // Trace artifact assertions (LOCATION + CONTENT).
                assertRunProducedTracesWithPostGoal(
                    station = station,
                    testName = testName,
                    expectedExit = expectedExit,
                    postGoalExpectsFire = postGoalExpectsFire
                )

                // Live-result sanity check (only on pass-pipeline configs that don't gate on MaxTurnsHit).
                assertNotNull(result.text, "$testName: executeLocal returned null text")
                return@runPostGoalHarness
            }
            catch (e: com.TTT.P2P.P2PException)
            {
                lastException = e
                val isTransient = e.message?.contains("Service error", ignoreCase = true) == true
                if (!isTransient || attemptCount >= maxAttempts) throw e
                System.err.println("[RETRY] $testName attempt $attemptCount/$maxAttempts failed: ${e.message?.take(120)}; sleeping 3s")
                kotlinx.coroutines.delay(3000)
            }
        }
        // Should never reach here — either return or throw above.
        throw lastException ?: IllegalStateException("$testName: retry loop exited without result")
    }

    // ====================================================================================
    // PIPELINE BUILDERS — MiniMax direct pipes (live + stub-mode both use these)
    // ====================================================================================

    private fun createMiniMaxPipe(
        pipeName: String,
        systemPrompt: String = "",
        baseUrl: String = MINIMAX_BASE_URL
    ): GenericOpenAIPipe
    {
        val key = apiKeyCache ?: throw IllegalStateException("API key not loaded")
        val pipe = GenericOpenAIPipe()
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
        return pipe
    }

    private fun createJudgePipeline(testName: String, baseUrl: String = MINIMAX_BASE_URL): Pipeline
    {
        val pipe = createMiniMaxPipe("judge", systemPrompt = DEFAULT_JUDGE_PROMPT, baseUrl = baseUrl)
        val pipeline = Pipeline().apply { add(pipe) }
        runBlocking { pipeline.init(true) }
        return pipeline
    }

    private fun createDispatchPipeline(testName: String, baseUrl: String = MINIMAX_BASE_URL): Pipeline
    {
        val pipe = createMiniMaxPipe("dispatch", systemPrompt = DEFAULT_DISPATCH_PROMPT, baseUrl = baseUrl)
        val pipeline = Pipeline().apply { add(pipe) }
        runBlocking { pipeline.init(true) }
        return pipeline
    }

    private fun createAgentPipeline(
        testName: String,
        pipeName: String,
        systemPrompt: String,
        baseUrl: String = MINIMAX_BASE_URL
    ): Pipeline
    {
        val pipe = createMiniMaxPipe(pipeName, systemPrompt = systemPrompt, baseUrl = baseUrl)
        val pipeline = Pipeline().apply { add(pipe) }
        runBlocking { pipeline.init(true) }
        return pipeline
    }

    // ====================================================================================
    // GOAL AGENT CONFIGURATIONS — 4 variants (live-passes, live-fails, stub-passes, stub-fails)
    // ====================================================================================

    /**
     * Stub-mode goal agent: returns terminatePipeline=false (pass). The stub server returns
     * the JSON and we wire a P2PInterface wrapper around it for the goal slot. Since
     * goalAgent expects a P2PInterface (not a Pipeline), we wrap a single-pipe pipeline.
     */
    private fun PumpStationBuilder<*>.stubGoalAgentThatPasses()
    {
        val passGoal = wrapPipelineAsPassingGoal()
        goalAgent = passGoal
    }

    /**
     * Stub-mode goal agent: returns terminatePipeline=true (fail). Drives the harness into
     * GoalValidationFailed exhaustion after maxGoalFailAttempts retries.
     */
    private fun PumpStationBuilder<*>.stubGoalAgentThatFails()
    {
        val failGoal = wrapPipelineAsFailingGoal()
        goalAgent = failGoal
    }

    /**
     * Live-mode goal agent: real MiniMax LLM call. Pass-prompt instructs the LLM to always
     * return a passing response. Fail-prompt instructs it to always signal failure.
     */
    // The harness's goal-validation path checks `result.terminatePipeline` (line 2408 of
    // PumpStationLoop.kt: `val passed = !result.terminatePipeline`), not the textual
    // content of the goal-agent response. Text-only prompts like "say GOALFAILED" or
    // "say GOALCONFIRMED" do NOT flip the flag, so the harness treats every response
    // as `passed=true`. The wrappers below use a real LLM inside but force the
    // flag at the wrapper boundary, matching the stub-mode pattern.
    private fun PumpStationBuilder<*>.liveGoalAgentThatPasses()
    {
        goalAgent = wrapPipelineAsLivePassingGoal()
    }

    private fun PumpStationBuilder<*>.liveGoalAgentThatFails()
    {
        goalAgent = wrapPipelineAsLiveFailingGoal()
    }

    /**
     * Live-LLM-backed passing goal agent: invokes a real LLM pipe (so the call is
     * recorded in the live trace) and always returns a result with
     * `terminatePipeline = false`. Drives a real goal-validation cycle that
     * always passes; the trace HTML will show the GOAL_VALIDATION events.
     */
    private fun wrapPipelineAsLivePassingGoal(): P2PInterface
    {
        val pipe = createMiniMaxPipe(
            "goal-pass-live",
            systemPrompt = "You are a goal-verification agent. Inspect the conversation " +
                "and respond with one short sentence confirming the work is done. " +
                "End your response with the literal token 'GOALCONFIRMED'."
        )
        val pipeline = Pipeline().apply { add(pipe) }
        runBlocking { pipeline.init(true) }
        return object : P2PInterface
        {
            override suspend fun executeLocal(content: MultimodalContent): MultimodalContent
            {
                val out = pipeline.executeLocal(content)
                return MultimodalContent(text = out.text).apply { terminatePipeline = false }
            }
            override suspend fun executeP2PRequest(request: P2PRequest): P2PResponse? = null
            override fun setParentInterface(parent: P2PInterface) {}
            override fun getParentP2PInterface(): P2PInterface? = null
            override fun getPaths(): String = ""
            override fun setTokenBudgetRecursive(budget: TokenBudgetSettings) {}
            override fun getTokenBudgetSettings(): TokenBudgetSettings? = null
            override fun setPipeSettingsRecursively(settings: PipeSettings) {}
            override suspend fun P2PInit() {}
            override var killSwitch: KillSwitch? = null
        }
    }

    /**
     * Live-LLM-backed failing goal agent: invokes a real LLM pipe (so the call is
     * recorded in the live trace) and always returns a result with
     * `terminatePipeline = true`. Drives a real goal-validation cycle that always
     * fails; the harness will count the failure toward `maxGoalFailAttempts` and
     * eventually exit with `PumpStationExitReason.GoalValidationFailed`.
     */
    private fun wrapPipelineAsLiveFailingGoal(): P2PInterface
    {
        val pipe = createMiniMaxPipe(
            "goal-fail-live",
            systemPrompt = "You are a goal-verification agent. Inspect the conversation " +
                "and ALWAYS respond with 'GOALFAILED: not done'."
        )
        val pipeline = Pipeline().apply { add(pipe) }
        runBlocking { pipeline.init(true) }
        return object : P2PInterface
        {
            override suspend fun executeLocal(content: MultimodalContent): MultimodalContent
            {
                val out = pipeline.executeLocal(content)
                return MultimodalContent(text = out.text).apply { terminatePipeline = true }
            }
            override suspend fun executeP2PRequest(request: P2PRequest): P2PResponse? = null
            override fun setParentInterface(parent: P2PInterface) {}
            override fun getParentP2PInterface(): P2PInterface? = null
            override fun getPaths(): String = ""
            override fun setTokenBudgetRecursive(budget: TokenBudgetSettings) {}
            override fun getTokenBudgetSettings(): TokenBudgetSettings? = null
            override fun setPipeSettingsRecursively(settings: PipeSettings) {}
            override suspend fun P2PInit() {}
            override var killSwitch: KillSwitch? = null
        }
    }

    /**
     * Wrap a single-pipe Pipeline in a P2PInterface that flips the result into
     * terminatePipeline=true (goal-fail signal). Real LLM-pipeline execution + post-result
     * mutate of `terminatePipeline`. This lets us drive stub-mode goal-agent behavior
     * without writing a separate P2PInterface stub for each.
     */
    private fun wrapPipelineAsFailingGoal(): P2PInterface
    {
        val pipe = createMiniMaxPipe(
            "goal-fail",
            systemPrompt = "Return one short sentence."
        )
        val pipeline = Pipeline().apply { add(pipe) }
        runBlocking { pipeline.init(true) }
        return object : P2PInterface
        {
            override suspend fun executeLocal(content: MultimodalContent): MultimodalContent
            {
                val out = pipeline.executeLocal(content)
                return MultimodalContent(text = out.text).apply { terminatePipeline = true }
            }
            override suspend fun executeP2PRequest(request: P2PRequest): P2PResponse? = null
            override fun setParentInterface(parent: P2PInterface) {}
            override fun getParentP2PInterface(): P2PInterface? = null
            override fun getPaths(): String = ""
            override fun setTokenBudgetRecursive(budget: TokenBudgetSettings) {}
            override fun getTokenBudgetSettings(): TokenBudgetSettings? = null
            override fun setPipeSettingsRecursively(settings: PipeSettings) {}
            override suspend fun P2PInit() {}
            override var killSwitch: KillSwitch? = null
        }
    }

    /**
     * Wrap a single-pipe Pipeline in a P2PInterface that flips the result into
     * terminatePipeline=false (goal-pass signal). Used for stub-mode tests where we want
     * to control goal-pass deterministically.
     */
    private fun wrapPipelineAsPassingGoal(): P2PInterface
    {
        val pipe = createMiniMaxPipe(
            "goal-pass",
            systemPrompt = "Return 'GOALPASSED'."
        )
        val pipeline = Pipeline().apply { add(pipe) }
        runBlocking { pipeline.init(true) }
        return object : P2PInterface
        {
            override suspend fun executeLocal(content: MultimodalContent): MultimodalContent
            {
                val out = pipeline.executeLocal(content)
                return MultimodalContent(text = out.text).apply { terminatePipeline = false }
            }
            override suspend fun executeP2PRequest(request: P2PRequest): P2PResponse? = null
            override fun setParentInterface(parent: P2PInterface) {}
            override fun getParentP2PInterface(): P2PInterface? = null
            override fun getPaths(): String = ""
            override fun setTokenBudgetRecursive(budget: TokenBudgetSettings) {}
            override fun getTokenBudgetSettings(): TokenBudgetSettings? = null
            override fun setPipeSettingsRecursively(settings: PipeSettings) {}
            override suspend fun P2PInit() {}
            override var killSwitch: KillSwitch? = null
        }
    }

    // ====================================================================================
    // PATH REGISTRATIONS — 4 variants
    // ====================================================================================

    private fun PumpStationBuilder<*>.registerSinglePathPassPipelinePath()
    {
        path("report")
        {
            description = "Single-path brief that signals pass-pipeline."
            risk = PathRiskLevel.Low
            setExecutionFunction { content, _, _, _ ->
                MultimodalContent(text = "Brief on: ${content.text}\n\n## Done.")
                    .apply { passPipeline = true }
            }
        }
    }

    private fun PumpStationBuilder<*>.registerSinglePathReportPath()
    {
        path("report")
        {
            description = "Single-path report path (no pass-pipeline signal — judge decides)."
            risk = PathRiskLevel.Low
            setExecutionFunction { content, _, _, _ ->
                MultimodalContent(text = "Brief on: ${content.text}\n\n## Done.")
            }
        }
    }

    private fun PumpStationBuilder<*>.registerMultiPathRiskLevelPaths()
    {
        path("gather")
        {
            description = "Gathers research findings. Low risk."
            risk = PathRiskLevel.Low
            setExecutionFunction { content, _, _, _ ->
                MultimodalContent(text = "Findings on ${content.text}: 3 substantive points.")
            }
        }
        path("analyze")
        {
            description = "Analyzes gathered findings. Medium risk."
            risk = PathRiskLevel.Medium
            setExecutionFunction { content, _, _, _ ->
                MultimodalContent(text = "Analyzed: ${content.text}")
            }
        }
        path("report")
        {
            description = "Produces the final brief. High risk."
            risk = PathRiskLevel.High
            setExecutionFunction { content, _, _, _ ->
                MultimodalContent(text = "Brief on: ${content.text}\n\n## Done.")
                    .apply { passPipeline = true }
            }
        }
        // Hypothesis-test escape valve (added 2026-07-23): if the LLM cannot make
        // progress on the work paths (e.g. the user's input is too vague for any
        // work path to act on), it can pick giveUp to terminate the harness with
        // a clear "I cannot complete this task" signal. Without this path, the
        // dispatch contract forces a selection from {gather, analyze, report} and
        // the harness can cycle indefinitely.
        //
        // The passPipeline + terminatePipeline flags are Transient var fields on
        // MultimodalContent (BinaryContent.kt:121, 153), not constructor params,
        // so they must be set after construction via .also { } (the established
        // pattern at PumpStation.kt:3081 and BinaryContent.kt examples).
        path("giveUp")
        {
            description = "Use this path when the task cannot be completed with the " +
                "available information. Sets passPipeline=true and terminatePipeline=true " +
                "so the judge accepts the abandonment as task-complete AND the harness " +
                "exits the loop. The result text will start with GIVEUP: so the test " +
                "harness can detect the escape."
            risk = PathRiskLevel.Low
            setExecutionFunction { content, _, _, _ ->
                MultimodalContent(text = "GIVEUP: I cannot complete this task. ${content.text}")
                    .also {
                        it.passPipeline = true
                        it.terminatePipeline = true
                    }
            }
        }
    }

    private fun PumpStationBuilder<*>.registerFlagTriggeredReportPath()
    {
        path("report")
        {
            description = "Single-path report that signals requestJudgeNextTurn."
            risk = PathRiskLevel.Low
            setExecutionFunction { content, station, _, _ ->
                station.requestJudgeNextTurn()
                MultimodalContent(text = "Brief on: ${content.text}\n\n## Done.")
            }
        }
    }

    // ====================================================================================
    // POST-GOAL AGENT — deterministic P2PInterface stub
    // ====================================================================================

    /**
     * Captures the input from executeLocal, prefixes it with a marker, and returns the
     * marker-prefixed content with terminatePipeline=false. The test asserts the marker
     * appears in `result.text` and that the input starts with the post-goal-function
     * transformation prefix.
     */
    private class CapturingPostGoalAgent : P2PInterface
    {
        var invocationCount: Int = 0
            private set
        var lastInputText: String? = null
            private set

        override suspend fun executeLocal(content: MultimodalContent): MultimodalContent
        {
            invocationCount += 1
            lastInputText = content.text
            return MultimodalContent(text = "$POSTGOAL_MARKER ${content.text}")
        }

        override suspend fun executeP2PRequest(request: P2PRequest): P2PResponse? = null
        override fun setParentInterface(parent: P2PInterface) {}
        override fun getParentP2PInterface(): P2PInterface? = null
        override fun getPaths(): String = ""
        override fun setTokenBudgetRecursive(budget: TokenBudgetSettings) {}
        override fun getTokenBudgetSettings(): TokenBudgetSettings? = null
        override fun setPipeSettingsRecursively(settings: PipeSettings) {}
        override suspend fun P2PInit() {}
        override var killSwitch: KillSwitch? = null
    }

    // ====================================================================================
    // TRACE CAPTURE + ASSERTION HELPERS
    // ====================================================================================

    /**
     * Resolves the canonical TPipe trace root to an absolute path and creates the directory.
     * Path: `TPipeConfig.getTraceDir()` / `PumpStation` — the canonical post-fix path.
     */
    private fun traceDir(): File
    {
        val dir = File(TPipeConfig.getTraceDir(), "PumpStation")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun traceSubdir(testName: String): File
    {
        val sub = File(traceDir(), testName)
        if (!sub.exists()) sub.mkdirs()
        return sub
    }

    /**
     * Build a TraceConfig that auto-exports the pump station HTML to the canonical path.
     * Stale pumpstation-*.html files from prior runs are cleaned out (the per-agent files
     * are keyed by stable pipeName so the latest is always the most recent).
     */
    private fun traceConfigFor(testName: String): TraceConfig
    {
        val subdir = traceSubdir(testName)
        subdir.listFiles { f -> f.name.startsWith("pumpstation-") && f.name.endsWith(".html") }
            ?.forEach { it.delete() }
        return TraceConfig(
            enabled = true,
            maxHistory = 5000,
            outputFormat = TraceFormat.HTML,
            detailLevel = TraceDetailLevel.DEBUG,
            autoExport = true,
            exportPath = subdir.absolutePath,
            includeContext = true,
            includeMetadata = true
        )
    }

    /**
     * Walks PipeTracer.getAllTraces and groups events by pipeName. Writes one HTML per
     * non-pumpstation pipe into the per-test subfolder. The pump station's own stream is
     * keyed by runId and is exported via `getTraceReport` instead.
     */
    private fun exportAgentTraces(testName: String)
    {
        val subdir = traceSubdir(testName)
        val visualizer = TraceVisualizer()
        val allTraces = PipeTracer.getAllTraces()
        val byName = mutableMapOf<String, MutableList<com.TTT.Debug.TraceEvent>>()
        for ((_, events) in allTraces)
        {
            for (event in events)
            {
                if (event.pipeName == "PumpStation") continue
                byName.getOrPut(event.pipeName) { mutableListOf() }.add(event)
            }
        }
        for ((name, events) in byName)
        {
            if (events.isEmpty()) continue
            val safeName = name.replace(Regex("[^A-Za-z0-9_.-]"), "_").ifBlank { "agent" }
            val html = visualizer.generateHtmlReport(events)
            File(subdir, "agent-${safeName}.html").writeText(html)
        }
    }

    /**
     * Asserts that the run produced the expected trace artifacts (LOCATION) AND that the
     * pump station HTML contains the expected content for the post-goal hook. Three checks:
     *
     * 1. trace HTML file exists at `File(traceDir(), testName)/pumpstation-<runId12>.html`
     *    with size >5KB (matches the existing live-test convention)
     * 2. trace HTML content contains `PUMP_STATION_POST_GOAL_COMPLETED` when the hook was
     *    expected to fire (configurations 1, 2, 4, 5, 6), OR does NOT contain it when the
     *    hook was expected NOT to fire (configuration 3 — goal-failure-exhaustion)
     * 3. when the hook fired, the HTML contains the `transformedContent` metadata key
     *    (proving the postGoalFunction ran and the event was emitted with that flag)
     */
    private fun assertRunProducedTracesWithPostGoal(
        station: PumpStation,
        testName: String,
        expectedExit: PumpStationExitReason,
        postGoalExpectsFire: Boolean
    )
    {
        // === LOCATION check ===

        val runId = station.getTraceId()
        assert(!runId.isNullOrBlank()) {
            "$testName: getTraceId() returned blank after executeLocal"
        }

        val report = station.getTraceReport(TraceFormat.HTML)
        assert(report.isNotBlank() && report.contains("<html")) {
            "$testName: getTraceReport(HTML) returned non-HTML content (len=${report.length})"
        }

        val state = station.getTaskState()
        val acceptedExits = when
        {
            expectedExit == PumpStationExitReason.JudgeComplete ->
                setOf(PumpStationExitReason.JudgeComplete, PumpStationExitReason.MaxTurnsHit)
            expectedExit == PumpStationExitReason.GoalValidationFailed ->
                setOf(PumpStationExitReason.GoalValidationFailed, PumpStationExitReason.MaxTurnsHit)
            else -> setOf(expectedExit)
        }
        assert(state.exitReason in acceptedExits) {
            "$testName: expected exit reason in $acceptedExits, got ${state.exitReason}"
        }

        val subdir = traceSubdir(testName)
        val expectedRunIdPrefix = runId!!.take(12)
        val pumpHtmls = subdir.listFiles { f ->
            f.name.startsWith("pumpstation-") &&
                f.name.endsWith(".html") &&
                f.name.contains("-$expectedRunIdPrefix.")
        } ?: emptyArray()
        assert(pumpHtmls.isNotEmpty() && pumpHtmls.all { it.length() > 5000 }) {
            "$testName: pump station HTML trace not found for runId=$expectedRunIdPrefix in $subdir " +
                "(looked for pumpstation-*$expectedRunIdPrefix*.html with size > 5KB); " +
                "got: ${pumpHtmls.map { it.name }} with sizes ${pumpHtmls.map { it.length() }}"
        }

        val pumpHtml = pumpHtmls.first().readText()

        // === CONTENT check — post-goal hook fire/no-fire ===

        if (postGoalExpectsFire)
        {
            assertTrue(
                pumpHtml.contains("PUMP_STATION_POST_GOAL_COMPLETED"),
                "$testName: pump station HTML must contain PUMP_STATION_POST_GOAL_COMPLETED " +
                    "since the post-goal hook was expected to fire; HTML length: ${pumpHtml.length}"
            )
            assertTrue(
                pumpHtml.contains("transformedContent"),
                "$testName: pump station HTML must surface 'transformedContent' " +
                    "metadata key for the post-goal hook event"
            )
            assertTrue(
                pumpHtml.contains("$POSTGOAL_MARKER"),
                "$testName: pump station HTML must carry the post-goal agent's marker " +
                    "in the rendered output"
            )
        }
        else
        {
            assertFalse(
                pumpHtml.contains("PUMP_STATION_POST_GOAL_COMPLETED"),
                "$testName: pump station HTML must NOT contain PUMP_STATION_POST_GOAL_COMPLETED " +
                    "since the post-goal hook was expected NOT to fire (failure-exhaustion); " +
                    "HTML length: ${pumpHtml.length}"
            )
        }
    }

    // ====================================================================================
    // STUB SERVER — minimal replica of PumpStationMiniMaxLiveTest.StubOpenAIServer
    // ====================================================================================

    private fun startStub(): StubOpenAIServer
    {
        val stub = StubOpenAIServer()
        stub.start()
        return stub
    }

    /**
     * Build a stub OpenAI Responses JSON body in the format GenericOpenAIPipe expects.
     * Default `passPipeline=true` (single-path exit), with optional `isComplete` for judge.
     */
    private fun stubJson(
        isComplete: Boolean = false,
        passPipeline: Boolean = false
    ): String
    {
        return stubResponsesJson("{\"isComplete\":$isComplete,\"shouldTerminate\":$passPipeline}")
    }

    /**
     * Wrap arbitrary text content in a valid OpenAI Responses API envelope.
     *
     * The stub server returns raw strings, but the harness pipe runs in
     * [ApiMode.OpenAIResponses] mode and routes every response body through
     * [genericOpenAIPipe.api.OpenAIResponsesResponseParser], which is strict
     * about the [OpenAIResponsesResponse] wire shape (requires `id`, `model`,
     * and a polymorphic `output` list of typed items). A raw snippet like
     * `{"path":"report"}` deserializes to `null` and trips
     * `P2PException: Failed to deserialize OpenAI Responses body`.
     *
     * This helper produces a minimal but valid envelope that the parser
     * accepts; the `text` is what the dispatch / pathSafety / summary agent
     * would have returned in the live-mode test.
     */
    private fun stubResponsesJson(text: String): String
    {
        val escaped = text.replace("\\", "\\\\").replace("\"", "\\\"")
        return """{"id":"stub-resp","object":"response","created_at":0,"status":"completed","model":"stub","output":[{
            "type":"message",
            "role":"assistant",
            "content":[{"type":"output_text","text":"$escaped"}]
        }]}"""
    }

    // ====================================================================================
    // STUB OPENAI SERVER (private replica — see plan "Deferred items")
    // ====================================================================================

    private class StubOpenAIServer
    {
        private val responsesByRole: MutableMap<String, java.util.concurrent.ConcurrentLinkedQueue<String>> =
            java.util.concurrent.ConcurrentHashMap()
        private val loopFallbacks: MutableMap<String, () -> String> = java.util.concurrent.ConcurrentHashMap()
        private var server: com.sun.net.httpserver.HttpServer? = null
        var port: Int = 0
            private set

        init
        {
            for (role in listOf("judge", "dispatch", "gather", "analyze", "report", "pathSafety", "summary", "goal"))
            {
                responsesByRole[role] = java.util.concurrent.ConcurrentLinkedQueue()
            }
        }

        fun loopEnqueue(role: String, provider: () -> String)
        {
            val queue = responsesByRole[role]
                ?: error("Unknown role: $role. Known: ${responsesByRole.keys}")
            loopFallbacks[role] = provider
            queue.size
        }

        fun enqueueFor(role: String, responseJson: String)
        {
            val queue = responsesByRole[role] ?: error("Unknown role: $role")
            queue.add(responseJson)
        }

        fun start()
        {
            val s = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress(0), 0)
            s.createContext("/v1/responses") { exchange ->
                val body = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
                val role = detectRole(body)
                val queue = responsesByRole[role] ?: responsesByRole.getValue("report")
                val response = queue.poll()
                    ?: loopFallbacks[role]?.invoke()
                    ?: throw IllegalStateException(
                        "StubOpenAIServer: no canned response for role='$role'. " +
                            "Body prefix: ${body.take(200)}"
                    )
                val bytes = response.toByteArray(Charsets.UTF_8)
                exchange.responseHeaders.set("Content-Type", "application/json; charset=utf-8")
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            s.executor = null
            s.start()
            server = s
            port = s.address.port
        }

        fun stop()
        {
            // 2s grace window lets in-flight handlers drain before close (per StubServerLifecycleTest).
            server?.stop(2)
            server = null
        }

        fun baseUrl(): String = "http://localhost:$port/v1"

        private fun detectRole(body: String): String
        {
            val lower = body.lowercase()
            return when
            {
                "the judge in an agentic harness" in lower -> "judge"
                "the dispatcher in an agentic harness" in lower -> "dispatch"
                "research gatherer" in lower -> "gather"
                "you are a summarizer" in lower -> "summary"
                "path-safety validator" in lower -> "pathSafety"
                "technical writer" in lower -> "report"
                "you are a goal-verification agent" in lower -> "goal"
                else -> "report"
            }
        }
    }
}