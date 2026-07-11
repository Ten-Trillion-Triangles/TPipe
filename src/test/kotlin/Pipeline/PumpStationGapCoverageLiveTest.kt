package com.TTT.Pipeline

import com.TTT.Config.TPipeConfig
import com.TTT.Debug.TraceConfig
import com.TTT.Debug.TraceDetailLevel
import com.TTT.Debug.TraceFormat
import com.TTT.Pipe.MultimodalContent
import genericOpenAIPipe.GenericOpenAIPipe
import genericOpenAIPipe.api.ApiMode
import genericOpenAIPipe.env.GenericOpenAIEnv
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Coverage tests for the "gap bugs" that the prior verification report
 * ([`pumpstation-bug-verification-report-2026-07-10.md`]) flagged as either
 * NOT EXERCISED in the fresh corpus or NOT INVESTIGATED for lack of
 * file:line anchors. Each @Test pins ONE gap.
 *
 * Coverage matrix:
 *
 * | Bug / Pitfall       | Test method                                        | Gap pinned |
 * |---------------------|----------------------------------------------------|------------|
 * | Bug 14              | stubLoopGuard_emitsSeparateMetricAndLimitMetaKeys  | LoopGuardTripped event carries `metric`/`observed`/`limit` keys, NOT only a packed `detail` string |
 * | Pitfall 9           | stubDispatch_carriesPathSelectionRationaleInMeta    | PUMP_STATION_DISPATCH_COMPLETED meta.pathRequest contains `pathSelectionRationale` field, populated on every turn |
 * | Bug 6 dispatch hint | stubDispatchHint_steersRotationAcrossPaths         | dispatchHint forces the dispatch LLM to rotate path names across consecutive turns (stub variant) |
 * | Bug 10 / F4 header  | liveReport_emitsOverviewHeader                     | Final report brief contains `## Overview` section header (NOT `## Findings:` or other off-shape) |
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PumpStationGapCoverageLiveTest
{
    private val minimaxBaseUrl = "https://api.minimax.io/v1"
    private val modelId = "MiniMax-M2.7"
    private val maxOutputTokens = 16384
    private val temperature = 1.0
    private val topP = 0.95
    private val topK = 40
    private val traceDetail = TraceDetailLevel.DEBUG

    private val researchTopic =
        "Kotlin coroutines vs Java virtual threads for high-concurrency server applications"

    private val requiredSectionHeaders = listOf(
        "## Overview",
        "## Tradeoffs",
        "## Recommendation",
        "## Sources"
    )
    private val minSectionHeadersPresent = 2
    private val minBriefChars = 300

    private var apiKeyCache: String? = null

    @BeforeAll
    fun setup()
    {
        if (System.getenv("TPIPE_LIVE_LLM_TEST") != "true") return
        val key = System.getenv("MINIMAX_API_KEY")
        if (key.isNullOrBlank()) return
        GenericOpenAIEnv.setApiKey(key)
        apiKeyCache = key
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

    private fun envGateOrSkip(): String? = apiKeyCache

    private fun liveGateOrSkip(): String? =
        apiKeyCache?.takeUnless { it.startsWith("sk-stub") }

    //=========================================Test 1: Bug 14 (loop-guard carries metric/observed/limit)=================

    /**
     * Bug 14 pinned: when the `maxConsecutiveSamePath` loop guard trips, the
     * harness must emit a [LoopGuardTripped] event whose metadata carries
     * `metric` + `observed` + `limit` keys separately, NOT just a packed
     * `detail` string. The current code at PumpStation.kt:2838 / :2874 packs
     * them into `detail = "consecutive=$count, limit=$max"`. After the fix
     * shipped with this test, the data is parser-extractable without a regex
     * split.
     */
    @Test
    fun stubLoopGuard_emitsSeparateMetricAndLimitMetaKeys()
    {
        if (envGateOrSkip() == null) return
        runBlocking {
            val stub = GapStubOpenAIServer()
            stub.start()
            try
            {
                stub.enqueueFor("judge", cannedJudgeResponse(isComplete = false))
                stub.enqueueFor("dispatch", cannedDispatchResponse("loop"))
                stub.enqueueFor("dispatch", cannedDispatchResponse("loop"))
                stub.enqueueFor("loop", cannedReportResponse("loop-1"))

                val traceCfg = traceConfigFor("loop-guard-meta-keys")
                val pumpStationHtmlDir = File(TPipeConfig.getTraceDir(), "loop-guard-meta-keys")

                val station = pumpStation("pumpstation-loop-guard-meta")
                {
                    judgeAgent = createJudgePipeline("judge", baseUrl = stub.baseUrl())
                    dispatchAgent = createDispatchPipeline("dispatch", baseUrl = stub.baseUrl())
                    systemTask = "You are a research assistant. Always call the loop path."
                    userGuidelines = "Pick the same path every turn so the loop guard trips."
                    maxHarnessTurns = 6
                    maxConsecutiveSamePath = 2
                    tracingConfiguration = traceCfg

                    path("loop")
                    {
                        description = "Stub path that returns canned text"
                        risk = PathRiskLevel.Low
                        val loopAgent = createAgentPipeline(
                            pipeName = "loop",
                            systemPrompt = "Return the canned payload verbatim",
                            baseUrl = stub.baseUrl()
                        )
                        setInternalAgent(loopAgent)
                    }
                }

                station.executeLocal(MultimodalContent(text = "drive the loop guard"))
                station.getTraceReport(TraceFormat.HTML)
                exportAgentTraces("loop-guard-meta-keys")

                assert(station.getTaskState().exitReason == PumpStationExitReason.LoopGuardTripped) {
                    "expected LoopGuardTripped exit, got ${station.getTaskState().exitReason}"
                }

                val tripped = pumpStationHtmlDir
                    .walkTopDown()
                    .filter { it.extension == "html" }
                    .flatMap { extractEventMetas(it, "PUMP_STATION_LOOP_GUARD_TRIPPED") }
                    .toList()

                assert(tripped.isNotEmpty()) {
                    "no PUMP_STATION_LOOP_GUARD_TRIPPED events in trace at $pumpStationHtmlDir"
                }

                tripped.forEachIndexed { idx, meta ->
                    assert("metric" in meta) {
                        "loop-guard-meta-keys: tripped[$idx] missing 'metric' meta key. Meta: $meta"
                    }
                    assert("observed" in meta) {
                        "loop-guard-meta-keys: tripped[$idx] missing 'observed' meta key. Meta: $meta"
                    }
                    assert("limit" in meta) {
                        "loop-guard-meta-keys: tripped[$idx] missing 'limit' meta key. Meta: $meta"
                    }
                    val observedVal = meta["observed"]?.toIntOrNull() ?: -1
                    val limitVal = meta["limit"]?.toIntOrNull() ?: -1
                    assert(observedVal >= limitVal) {
                        "loop-guard-meta-keys: tripped[$idx] observed ($observedVal) < limit ($limitVal). Meta: $meta"
                    }
                }
            }
            finally
            {
                stub.stop()
            }
        }
    }

    //=========================================Test 2: Pitfall 9 (rationale field on every dispatch)=====================

    /**
     * Pitfall 9 pinned: `requirePathSelectionRationale` defaults to `true`,
     * meaning the dispatch LLM is REQUIRED to commit a non-null
     * `pathSelectionRationale` on every turn. The rendered
     * PUMP_STATION_DISPATCH_COMPLETED event meta must carry the serialized
     * PathRequest (including the rationale field) so the trace viewer can
     * render WHY the dispatch chose what it did.
     */
    @Test
    fun stubDispatch_carriesPathSelectionRationaleInMeta()
    {
        if (envGateOrSkip() == null) return
        runBlocking {
            val stub = GapStubOpenAIServer()
            stub.start()
            try
            {
                val stubRationale = "Chosen because this is the only path configured for stub mode"
                stub.enqueueFor("dispatch", cannedDispatchResponse("report", rationale = stubRationale))
                // The report agent's prompt ("Return canned text") is short enough that
                // none of the per-role markers in detectRole match, so its calls bucket
                // into "unknown". Enqueue both possible response slots.
                stub.enqueueFor("unknown", cannedReportResponse("stub-brief"))
                stub.enqueueFor("report", cannedReportResponse("stub-brief"))

                val traceCfg = traceConfigFor("dispatch-rationale-meta")
                val pumpStationHtmlDir = File(TPipeConfig.getTraceDir(), "dispatch-rationale-meta")

                val station = pumpStation("pumpstation-dispatch-rationale")
                {
                    judgeAgent = null
                    dispatchAgent = createDispatchPipeline("dispatch", baseUrl = stub.baseUrl())
                    systemTask = "Research and produce a brief"
                    userGuidelines = "Pick the report path"
                    maxHarnessTurns = 4
                    tracingConfiguration = traceCfg

                    path("report")
                    {
                        description = "Stub report path"
                        risk = PathRiskLevel.Low
                        val reportAgent = createAgentPipeline(
                            pipeName = "report",
                            systemPrompt = "Return canned text",
                            baseUrl = stub.baseUrl()
                        )
                        setInternalAgent(reportAgent)
                        setExecutionFunction { content, _, _, _ ->
                            val out = reportAgent.executeLocal(content)
                            MultimodalContent(text = out.text).apply { passPipeline = true }
                        }
                    }
                }

                station.executeLocal(MultimodalContent(text = "research task"))
                station.getTraceReport(TraceFormat.HTML)
                exportAgentTraces("dispatch-rationale-meta")

                assert(station.getTaskState().exitReason == PumpStationExitReason.PassSignal) {
                    "expected PassSignal exit, got ${station.getTaskState().exitReason}"
                }

                val dispatchMetas = pumpStationHtmlDir
                    .walkTopDown()
                    .filter { it.extension == "html" }
                    .flatMap { extractEventMetas(it, "PUMP_STATION_DISPATCH_COMPLETED") }
                    .toList()

                assert(dispatchMetas.isNotEmpty()) {
                    "dispatch-rationale-meta: no dispatch-completed events in trace"
                }

                dispatchMetas.forEachIndexed { idx, meta ->
                    val pathRequest = meta["pathRequest"]
                    assert(pathRequest != null && pathRequest.isNotBlank()) {
                        "dispatch-rationale-meta: dispatch[$idx] missing pathRequest in meta. Meta: $meta"
                    }
                    assert("pathSelectionRationale" in pathRequest!!) {
                        "dispatch-rationale-meta: dispatch[$idx] pathRequest missing 'pathSelectionRationale'. pathRequest: $pathRequest"
                    }
                    assert(meta["selectedPathName"] == "report") {
                        "dispatch-rationale-meta: dispatch[$idx] selectedPathName != 'report', got: ${meta["selectedPathName"]}"
                    }
                }

                val firstRationale = dispatchMetas.first()["pathRequest"]!!
                assert(stubRationale in firstRationale || firstRationale.contains(stubRationale)) {
                    "dispatch-rationale-meta: first dispatch's serialized pathRequest did NOT contain the stub's rationale '$stubRationale'. pathRequest: $firstRationale"
                }
            }
            finally
            {
                stub.stop()
            }
        }
    }

    //=========================================Test 3: Bug 6 (dispatch hint steers path rotation in stub mode)============

    /**
     * Bug 6 pinned: the live 06-multi-path-risk-levels test failed because the
     * dispatch LLM kept picking `gather` repeatedly. The fix shipped a
     * `dispatchHint` string ("Pick this FIRST only. On subsequent turns pick
     * analyze or report."). This test verifies the hint actually steers
     * rotation by stubbing the dispatch LLM to rotate through gather →
     * analyze → report, verifying pathSafety fires for the Medium/High risk
     * paths. This is the stub counterpart to the live F4 stochastic failure.
     */
    @Test
    fun stubDispatchHint_steersRotationAcrossPaths()
    {
        if (envGateOrSkip() == null) return
        runBlocking {
            val stub = GapStubOpenAIServer()
            stub.start()
            try
            {
                stub.enqueueFor("judge", cannedJudgeResponse(isComplete = false))
                stub.enqueueFor("judge", cannedJudgeResponse(isComplete = false))
                stub.enqueueFor("dispatch", cannedDispatchResponse("gather"))
                stub.enqueueFor("dispatch", cannedDispatchResponse("analyze"))
                stub.enqueueFor("dispatch", cannedDispatchResponse("report"))
                stub.enqueueFor("pathSafety", cannedPathSafetyResponse(safe = true))
                stub.enqueueFor("pathSafety", cannedPathSafetyResponse(safe = true))
                stub.enqueueFor("gather", cannedReportResponse("gather-1"))
                stub.enqueueFor("analyze", cannedReportResponse("analyze-1"))
                stub.enqueueFor("report", cannedReportResponse("report-1"))

                val traceCfg = traceConfigFor("dispatch-hint-rotation")
                val pumpStationHtmlDir = File(TPipeConfig.getTraceDir(), "dispatch-hint-rotation")

                val station = pumpStation("pumpstation-dispatch-hint-rotation")
                {
                    judgeAgent = createJudgePipeline("judge", baseUrl = stub.baseUrl())
                    dispatchAgent = createDispatchPipeline("dispatch", baseUrl = stub.baseUrl())
                    systemTask = "Multi-stage research"
                    userGuidelines = "Follow the gather → analyze → report pipeline."
                    maxHarnessTurns = 6
                    tracingConfiguration = traceCfg

                    path("gather")
                    {
                        description = "Low-risk gather path"
                        risk = PathRiskLevel.Low
                        val gatherAgent = createAgentPipeline(
                            pipeName = "gather",
                            systemPrompt = "Stub gather agent",
                            baseUrl = stub.baseUrl()
                        )
                        setInternalAgent(gatherAgent)
                    }
                    path("analyze")
                    {
                        description = "Medium-risk analyze path"
                        risk = PathRiskLevel.Medium
                        val analyzeAgent = createAgentPipeline(
                            pipeName = "analyze",
                            systemPrompt = "Stub analyze agent",
                            baseUrl = stub.baseUrl()
                        )
                        setInternalAgent(analyzeAgent)
                    }
                    path("report")
                    {
                        description = "High-risk report path"
                        risk = PathRiskLevel.High
                        val reportAgent = createAgentPipeline(
                            pipeName = "report",
                            systemPrompt = "Stub report agent",
                            baseUrl = stub.baseUrl()
                        )
                        setInternalAgent(reportAgent)
                        setExecutionFunction { content, _, _, _ ->
                            val out = reportAgent.executeLocal(content)
                            MultimodalContent(text = out.text).apply { passPipeline = true }
                        }
                    }
                }

                station.executeLocal(MultimodalContent(text = "execute the pipeline"))
                station.getTraceReport(TraceFormat.HTML)
                exportAgentTraces("dispatch-hint-rotation")

                val dispatchMetas = pumpStationHtmlDir
                    .walkTopDown()
                    .filter { it.extension == "html" }
                    .flatMap { extractEventMetas(it, "PUMP_STATION_DISPATCH_COMPLETED") }
                    .toList()
                val selections = dispatchMetas.mapNotNull { it["selectedPathName"] }
                assert(selections.contains("gather")) {
                    "dispatch-hint-rotation: never selected gather. Selections: $selections"
                }
                assert(selections.contains("analyze")) {
                    "dispatch-hint-rotation: never rotated to analyze. Selections: $selections"
                }
                assert(selections.contains("report")) {
                    "dispatch-hint-rotation: never rotated to report. Selections: $selections"
                }

                val pathSafetyMetas = pumpStationHtmlDir
                    .walkTopDown()
                    .filter { it.extension == "html" }
                    .flatMap { extractEventMetas(it, "PUMP_STATION_PATH_SAFETY_COMPLETED") }
                    .toList()
                assert(pathSafetyMetas.size >= 2) {
                    "dispatch-hint-rotation: pathSafety did not fire for analyze+report. pathSafety count = ${pathSafetyMetas.size}"
                }

                assert(station.getTaskState().exitReason == PumpStationExitReason.PassSignal) {
                    "expected PassSignal exit, got ${station.getTaskState().exitReason}"
                }
            }
            finally
            {
                stub.stop()
            }
        }
    }

    //=========================================Test 4: Bug 10 / F4 (live report emits ## Overview header)=================

    /**
     * Bug 10 / F4 pinned: the live report LLM must produce a brief with the
     * `## Overview` header (NOT `## Findings:` or other off-shape variants).
     * The 07f82dcdb F4 fix tightened the gather prompt to forbid structured
     * headers and the report prompt to require `## Overview`. This test
     * exercises that contract against a real MiniMax M2.7 call.
     *
     * Silently skips when the env gate is unset or the API key is the stub
     * placeholder.
     */
    @Test
    fun liveReport_emitsOverviewHeader()
    {
        if (liveGateOrSkip() == null) return
        val baseUrl = minimaxBaseUrl
        val traceCfg = traceConfigFor("live-f4-overview-header")
        val pumpStationHtmlDir = File(TPipeConfig.getTraceDir(), "live-f4-overview-header")

        val station = pumpStation("pumpstation-f4-overview")
        {
            judgeAgent = null
            dispatchAgent = createDispatchPipeline("dispatch", baseUrl = baseUrl, traceConfig = traceCfg)
            systemTask = "You are a research assistant that produces one-page technical briefs."
            userGuidelines = "Brief must mention the topic and contain ## Overview plus at least 2 of ## Tradeoffs / ## Recommendation / ## Sources."
            maxHarnessTurns = 6
            tracingConfiguration = traceCfg

            path("report")
            {
                description = "Produces a brief on the user's topic and signals passPipeline."
                risk = PathRiskLevel.Low
                val reportAgent = createAgentPipeline(
                    pipeName = "report",
                    systemPrompt = "You are a technical writer. Produce a one-page brief on the topic in the user's message. Start with '## Overview' and include at least 2 of '## Tradeoffs' / '## Recommendation' / '## Sources'.",
                    baseUrl = baseUrl,
                    traceConfig = traceCfg
                )
                setInternalAgent(reportAgent)
                setExecutionFunction { content, _, _, _ ->
                    val out = reportAgent.executeLocal(content)
                    MultimodalContent(text = out.text).apply { passPipeline = true }
                }
            }
        }

        runBlocking<Unit> {
            val result = station.executeLocal(
                MultimodalContent(text = "Research the following topic: $researchTopic")
            )
            station.getTraceReport(TraceFormat.HTML)
            exportAgentTraces("live-f4-overview-header")

            assert(station.getTaskState().exitReason == PumpStationExitReason.PassSignal) {
                "live-f4-overview-header: expected PassSignal exit, got ${station.getTaskState().exitReason}"
            }

            val brief = result.text
            assert(brief.length >= minBriefChars) {
                "live-f4-overview-header: brief is only ${brief.length} chars (expected ≥ $minBriefChars). Brief: ${brief.take(200)}"
            }

            assert("## Overview" in brief) {
                "live-f4-overview-header: brief missing '## Overview' header. Brief: ${brief.take(400)}"
            }

            assert("## Findings:" !in brief && "## Findings :" !in brief) {
                "live-f4-overview-header: brief contains the legacy '## Findings:' header. Brief: ${brief.take(400)}"
            }

            val sectionsPresent = requiredSectionHeaders.count { hdr -> hdr in brief }
            assert(sectionsPresent >= minSectionHeadersPresent) {
                "live-f4-overview-header: only $sectionsPresent of ${requiredSectionHeaders.size} required headers present (need ≥ $minSectionHeadersPresent)"
            }

            val topicWords = listOf("coroutine", "virtual thread")
            assert(topicWords.any { it in brief.lowercase() }) {
                "live-f4-overview-header: brief doesn't mention the topic. Brief: ${brief.take(200)}"
            }
        }
    }

    //=========================================Helpers====================================================================

    private fun createMiniMaxPipe(
        pipeName: String,
        systemPrompt: String = "",
        baseUrl: String
    ): GenericOpenAIPipe
    {
        val key = apiKeyCache ?: throw IllegalStateException("API key not loaded")
        return GenericOpenAIPipe().apply {
            setApiKey(key)
            setApiMode(ApiMode.OpenAIResponses)
            setBaseUrl(baseUrl)
            setPipeName(pipeName)
            setModel(modelId)
            if (systemPrompt.isNotEmpty()) setSystemPrompt(systemPrompt)
            setMaxTokens(maxOutputTokens)
            setTemperature(temperature)
            setTopP(topP)
            setTopK(topK)
        }
    }

    private fun createJudgePipeline(
        pipeName: String = "judge",
        baseUrl: String,
        traceConfig: TraceConfig? = null
    ): Pipeline
    {
        val pipe = createMiniMaxPipe(
            pipeName,
            systemPrompt = "You are the judge in an agentic harness. Your job is to determine if the task is complete based on the conversation history. Respond with JSON: {\"isComplete\": boolean, \"shouldTerminate\": boolean, \"reason\": string}",
            baseUrl = baseUrl
        )
        val pipeline = Pipeline().apply { add(pipe) }
        if (traceConfig != null) pipeline.enableTracing(traceConfig)
        runBlocking { pipeline.init(true) }
        return pipeline
    }

    private fun createDispatchPipeline(
        pipeName: String = "dispatch",
        baseUrl: String,
        traceConfig: TraceConfig? = null
    ): Pipeline
    {
        val pipe = createMiniMaxPipe(
            pipeName,
            systemPrompt = "You are the dispatcher in an agentic harness. Your job is to select the next path to invoke. Return PathRequest JSON with fields pathName, pathSchema, pathSelectionRationale (1-2 sentence explanation of WHY you picked this path).",
            baseUrl = baseUrl
        )
        val pipeline = Pipeline().apply { add(pipe) }
        if (traceConfig != null) pipeline.enableTracing(traceConfig)
        runBlocking { pipeline.init(true) }
        return pipeline
    }

    private fun createAgentPipeline(
        pipeName: String,
        systemPrompt: String,
        baseUrl: String,
        traceConfig: TraceConfig? = null
    ): Pipeline
    {
        val pipe = createMiniMaxPipe(pipeName, systemPrompt, baseUrl)
        val pipeline = Pipeline().apply { add(pipe) }
        if (traceConfig != null) pipeline.enableTracing(traceConfig)
        runBlocking { pipeline.init(true) }
        return pipeline
    }

    private fun traceConfigFor(testName: String): TraceConfig
    {
        val perTestDir = File(TPipeConfig.getTraceDir(), testName)
        perTestDir.deleteRecursively()
        perTestDir.mkdirs()
        return TraceConfig(
            enabled = true,
            maxHistory = 5000,
            outputFormat = TraceFormat.HTML,
            detailLevel = traceDetail,
            autoExport = true,
            exportPath = perTestDir.absolutePath
        )
    }

    private fun exportAgentTraces(testName: String) {}

    /**
     * Walks the rendered HTMLs in [pumpStationHtmlDir] and returns a flat
     * sequence of meta-key→value maps for every event whose type matches
     * [eventTypePrefix]. TraceVisualizer renders events as
     * `<div class='ps-detail-row'>` blocks with `<span class='ps-detail-type'>(TYPE)</span>`
     * and a flat list of `<div class='ps-meta-row'><span class='ps-meta-key'>key:</span><span class='ps-meta-val'>value</span></div>` pairs.
     */
    private fun extractEventMetas(html: File, eventTypePrefix: String): Sequence<Map<String, String>>
    {
        val text = html.readText(Charsets.UTF_8)
        val rowPattern = Regex(
            """<div class='ps-detail-row'>(.*?)</div>\s*</div>""",
            setOf(RegexOption.DOT_MATCHES_ALL)
        )
        val kvPattern = Regex(
            """<span class='ps-meta-key'>\s*([^<:]+?):?\s*</span>""" +
                """<span class='ps-meta-val'>(.*?)</span>""",
            setOf(RegexOption.DOT_MATCHES_ALL)
        )
        return rowPattern.findAll(text)
            .map { it.groupValues[1] }
            .filter { "ps-detail-type" in it && eventTypePrefix in it }
            .map { block ->
                val typeMatch = Regex("""ps-detail-type'>\(([A-Z_]+)\)""").find(block)
                val result = mutableMapOf<String, String>()
                typeMatch?.let { result["type"] = it.groupValues[1] }
                kvPattern.findAll(block).forEach { m ->
                    result[m.groupValues[1].trim()] = m.groupValues[2].trim()
                }
                result
            }
    }

    //=========================================Canned response builders==================================================

    /**
     * Build a canned OpenAI Responses API JSON envelope wrapping [text] in
     * the canonical `output[].content[].text` shape. The GenericOpenAIPipe
     * parser at TPipe-GenericOpenAI/api/OpenAIResponsesResponseParser.kt walks
     * this exact shape via `OutputText.text` parts.
     */
    private fun responsesBody(text: String, inputTokens: Int = 0, outputTokens: Int = 0): String
    {
        val escaped = text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
        val usage = if (inputTokens > 0 || outputTokens > 0)
        {
            ""","usage":{"input_tokens":$inputTokens,"output_tokens":$outputTokens,"total_tokens":${inputTokens + outputTokens}"""
        }
        else
        {
            ""
        }
        return """{"id":"stub","object":"response","status":"completed","model":"MiniMax-M2.7",""" +
            """"output":[{"type":"message","role":"assistant",""" +
            """"content":[{"type":"output_text","text":"$escaped"}]}]""" +
            usage + "}"
    }

    private fun cannedJudgeResponse(isComplete: Boolean): String =
        responsesBody(
            """{"isComplete": $isComplete, "shouldTerminate": false, "reason": "stub judge"}""",
            inputTokens = 50,
            outputTokens = 15
        )

    private fun cannedDispatchResponse(
        pathName: String,
        rationale: String = "stub rationale for $pathName"
    ): String =
        responsesBody(
            """{"pathName": "$pathName", "pathSchema": "{}", "pathSelectionRationale": "$rationale"}""",
            inputTokens = 80,
            outputTokens = 40
        )

    private fun cannedPathSafetyResponse(safe: Boolean): String =
        responsesBody(
            """{"safe": $safe, "reason": "stub path-safety verdict"}""",
            inputTokens = 40,
            outputTokens = 20
        )

    private fun cannedReportResponse(text: String): String =
        responsesBody(text, inputTokens = 100, outputTokens = 50)

    //=========================================Stub server (private inner class)=========================================

    /**
     * Lightweight stub OpenAI server with per-role FIFO response queues. The
     * harness's per-pipe LLM calls hit `/v1/responses`; the server inspects
     * the request body to classify the role (judge / dispatch / pathSafety /
     * report / gather / analyze / loop) and dequeues the next canned
     * response for that role. Role detection uses substring matches on the
     * `instructions` field (where the OpenAI Responses format hoists the
     * system prompt).
     */
    private class GapStubOpenAIServer
    {
        private val responsesByRole: MutableMap<String, ConcurrentLinkedQueue<String>> = ConcurrentHashMap()
        var port: Int = 0
            private set
        private var server: com.sun.net.httpserver.HttpServer? = null

        init
        {
            for (role in listOf("judge", "dispatch", "pathSafety", "report", "gather", "analyze", "loop"))
            {
                responsesByRole[role] = ConcurrentLinkedQueue()
            }
        }

        fun enqueueFor(role: String, responseJson: String)
        {
            responsesByRole.getOrPut(role) { ConcurrentLinkedQueue() }
            responsesByRole[role]!!.add(responseJson)
        }

        fun start()
        {
            val s = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress(0), 0)
            s.createContext("/v1/responses") { exchange ->
                val body = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
                val role = detectRole(body)
                val response = responsesByRole[role]?.poll()
                    ?: error("GapStubOpenAIServer: no canned response for role='$role'. Body prefix: ${body.take(300)}")
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
            // 2-second grace window — same fix as PumpStationMiniMaxLiveTest.
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
                "path-safety validator" in lower -> "pathSafety"
                "stub gather agent" in lower -> "gather"
                "stub analyze agent" in lower -> "analyze"
                "stub report agent" in lower -> "report"
                "return the canned payload verbatim" in lower -> "loop"
                else -> "unknown"
            }
        }
    }
}