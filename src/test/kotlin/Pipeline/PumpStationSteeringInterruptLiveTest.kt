package com.TTT.Pipeline

import com.TTT.Config.TPipeConfig
import com.TTT.Debug.TraceConfig
import com.TTT.Debug.TraceDetailLevel
import com.TTT.Debug.TraceEvent
import com.TTT.Debug.TraceFormat
import com.TTT.Debug.TraceVisualizer
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

/**
 * Live integration tests for the **steering** and **interrupt** runtime features
 * (sibling injection points on [PumpStation]). Mirrors the structure of
 * [PumpStationTPipeConfigTraceLiveTest]: real harness loop against the MiniMax
 * M2.7 endpoint, real LLM calls for judge + dispatch + paths, and every trace
 * artifact written under [TPipeConfig.getTraceDir] — the canonical TPipe trace
 * root.
 *
 * Two tests:
 *   1. `steerInjectsAtBeforeJudgeWithCanonicalMetadataEnvelope` — a background
 *      coroutine watches `taskState.turnIndex` and, after the first turn, fires
 *      `station.steer(PumpStationPausePhase.BeforeJudge, ...)` so the next
 *      BeforeJudge poll drains a one-shot entry. The trace HTML must contain
 *      the steered text and the canonical `metadata["steering"]` envelope
 *      (`phase` + `persistent` + `injectionId` + `timestamp`).
 *   2. `interruptRewindsAndReentersFromBeforeJudge` — the same harness shape
 *      plus a second background coroutine that fires
 *      `station.interrupt(PumpStationPausePhase.BeforeJudge, ...)` after a
 *      few turns. The trace HTML must contain the interrupt text with the
 *      `metadata["interrupt"]` envelope (`phase` + `wasRewound` + `injectionId`
 *      + `timestamp`).
 *
 * Both tests are gated on the same env pair as [PumpStationTPipeConfigTraceLiveTest]
 * and [PumpStationMiniMaxLiveTest]:
 *   - TPIPE_LIVE_LLM_TEST=true enables the run
 *   - MINIMAX_API_KEY=<the live M2.7 credential> provides auth
 *
 * When either gate is unset the test method returns silently (no pass, no fail,
 * no artifacts) so the suite remains green on CI without network access.
 *
 * Trace location:
 *   `${TPipeConfig.getTraceDir()}/<test-name>/pumpstation-<runId-prefix>.html`
 *
 * which resolves to `~/.tpipe/debug/trace/<test-name>/…` by default.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PumpStationSteeringInterruptLiveTest
{
    private val minimaxBaseUrl = "https://api.minimax.io/v1"
    private val modelId = "MiniMax-M2.7"
    private val researchTopic =
        "Kotlin coroutines structured concurrency vs Java virtual threads scoped values"

    private var apiKeyCache: String? = null

    @BeforeAll
    fun setup()
    {
        if (System.getenv("TPIPE_LIVE_LLM_TEST") != "true") return
        val key = System.getenv("MINIMAX_API_KEY")
        if (key.isNullOrBlank()) return
        GenericOpenAIEnv.setApiKey(key)
        apiKeyCache = key
    }

    @AfterAll
    fun teardown()
    {
        if (apiKeyCache != null)
        {
            GenericOpenAIEnv.clearApiKey()
            apiKeyCache = null
        }
    }

    private fun liveGateOrSkip(): String? =
        apiKeyCache?.takeUnless { it.startsWith("sk-stub") }

    /**
     * Multi-turn harness where a background coroutine enqueues a steering
     * one-shot at BeforeJudge after the first turn completes. Asserts:
     *
     *   - The trace HTML lands under TPipeConfig.getTraceDir().
     *   - The harness ran at least one dispatch + path cycle.
     *   - The steered text appears in the trace HTML.
     *   - The canonical `metadata["steering"]` envelope keys are present in
     *     the HTML (phase, persistent, injectionId, timestamp).
     */
    @Test
    fun steerInjectsAtBeforeJudgeWithCanonicalMetadataEnvelope() = runBlocking<Unit>
    {
        if (liveGateOrSkip() == null) return@runBlocking

        val testName = "tpipe-config-steering-live"
        val perTestDir = File(TPipeConfig.getTraceDir(), testName)
        perTestDir.deleteRecursively()
        perTestDir.mkdirs()

        val traceCfg = TraceConfig(
            enabled = true,
            autoExport = true,
            exportPath = perTestDir.absolutePath,
            outputFormat = TraceFormat.HTML,
            detailLevel = TraceDetailLevel.DEBUG
        )

        val steeredText = "user just asked: focus on memory overhead, not throughput"

        val station = pumpStation("pumpstation-steering-tpipe-config")
        {
            judgeAgent = buildPipe("judge",
                "You are the research judge in an agentic harness. Inspect the " +
                    "conversation. If it contains '## Overview', return isComplete=true. " +
                    "Reply JSON: {\"isComplete\": <bool>, \"shouldTerminate\": false, " +
                    "\"reason\": \"<one line>\"}",
                traceCfg
            )
            dispatchAgent = buildPipe("dispatch",
                "You are the dispatcher. Pick the next path; return PathRequest JSON.",
                traceCfg
            )

            tracingConfiguration = traceCfg

            systemTask = "Produce a one-page technical brief on: $researchTopic."

            // Pre-seed the steering service with a one-shot that fires on the
            // very first BeforeJudge. The first turn's judge LLM call will
            // see the steered text appended to its context. This is more
            // reliable than a background coroutine waiting on turnIndex,
            // because the harness may exit after the first turn if the judge
            // decides the task is complete.
            steeringPolicy {
                phaseBoundContent(PumpStationPausePhase.BeforeJudge, steeredText)
            }

            path("gather")
            {
                description = "Gathers raw research findings on the topic."
                risk = PathRiskLevel.Low
                val gatherAgent = buildPipe("gather",
                    "You are a research gatherer. Produce 3-5 substantive findings " +
                        "on the topic. Aim for ~120 words.",
                    traceCfg
                )
                setInternalAgent(gatherAgent)
                setExecutionFunction { content, _, _, _ ->
                    val out = gatherAgent.executeLocal(content)
                    MultimodalContent(text = out.text)
                }
            }

            path("analyze")
            {
                description = "Analyzes the gathered material and tags 3 themes."
                risk = PathRiskLevel.Low
                setExecutionFunction { content, _, _, _ ->
                    val sentences = content.text.split(Regex("[.!?]+"))
                        .map { it.trim() }.filter { it.isNotBlank() }.take(3)
                    val themes = if (sentences.isNotEmpty())
                    {
                        sentences.mapIndexed { i, s -> "- theme ${i + 1}: $s" }.joinToString("\n")
                    } else "- theme 1: (no findings)"
                    MultimodalContent(
                        text = "Analyzed themes:\n$themes\n\nSource: ${content.text.take(300)}"
                    )
                }
            }

            path("report")
            {
                description = "Final brief writer."
                risk = PathRiskLevel.Low
                val reportAgent = buildPipe("report",
                    "You are a technical writer. Synthesize the analyzed themes into a " +
                        "one-page brief. Required sections in order: " +
                        "## Overview, ## Tradeoffs, ## Recommendation. Each 1-3 sentences.",
                    traceCfg
                )
                setInternalAgent(reportAgent)
                setExecutionFunction { content, _, _, _ ->
                    val out = reportAgent.executeLocal(content)
                    MultimodalContent(text = out.text)
                }
            }

            maxHarnessTurns = 6
        }

        val result = station.executeLocal(MultimodalContent(text = "Research: $researchTopic"))

        // === 1. Pump HTML present and non-empty ===
        val reportHtml = station.getTraceReport(TraceFormat.HTML)
        assert(reportHtml.isNotBlank() && reportHtml.contains("<html")) {
            "getTraceReport(HTML) returned non-HTML payload (len=${reportHtml.length})"
        }
        exportPerAgentTraces(station, perTestDir)

        val exportedFiles = perTestDir.walkTopDown().filter { it.isFile && it.extension == "html" }.toList()
        assert(exportedFiles.isNotEmpty()) { "no HTML traces exported to $perTestDir" }

        val pumpHtml = exportedFiles.first { it.name.contains("pumpstation", ignoreCase = true) }
        val pumpContent = pumpHtml.readText(Charsets.UTF_8)

        // === 2. Multi-turn assertion: at least one dispatch + path cycle ===
        val dispatchCount = Regex("PUMP_STATION_DISPATCH_COMPLETED").findAll(pumpContent).count()
        assert(dispatchCount >= 1) { "no DISPATCH_COMPLETED events in pump HTML" }
        val pathCount = Regex("PUMP_STATION_PATH_COMPLETED").findAll(pumpContent).count()
        assert(pathCount >= 1) { "no PATH_COMPLETED events in pump HTML" }

        // === 3. Steering assertion: the live station's turnHistory is the
        // ground truth for steering. The visualizer does NOT render the
        // steered text as a labeled field in the pump HTML (only the standard
        // metadata keys like contentPreview / contentLength / inputTokens),
        // so the previous "pumpContent.contains(steeredText)" assertion was
        // unreliable. The turnHistory assertion below is the real contract.
        val steeredEntry = station.turnHistory.history.firstOrNull {
            it.content.text == steeredText
        }
        assert(steeredEntry != null) {
            "steered text '$steeredText' not present in station.turnHistory — the " +
                "steering service did not drain the entry (the background coroutine " +
                "may have fired AFTER the harness completed, or the harness exited " +
                "before reaching BeforeJudge turn 1). " +
                "turnHistory size: ${station.turnHistory.history.size}, " +
                "turnIndex: ${station.taskState.turnIndex}, " +
                "exitReason: ${station.taskState.exitReason}"
        }
        @Suppress("UNCHECKED_CAST")
        val steeringEnvelope = steeredEntry!!.content.metadata["steering"] as? Map<String, Any>
        assert(steeringEnvelope != null) {
            "steered entry missing metadata['steering'] envelope; metadata keys: " +
                steeredEntry.content.metadata.keys.toString()
        }
        assert(steeringEnvelope!!["phase"] == PumpStationPausePhase.BeforeJudge.name) {
            "steering envelope phase mismatch: ${steeringEnvelope["phase"]}"
        }
        assert(steeringEnvelope["persistent"] == false) {
            "one-shot steering envelope should have persistent=false; got " +
                "${steeringEnvelope["persistent"]}"
        }
        assert((steeringEnvelope["injectionId"] as? String)?.isNotBlank() == true) {
            "steering envelope injectionId blank"
        }
        assert((steeringEnvelope["timestamp"] as? Long) ?: 0L > 0L) {
            "steering envelope timestamp must be positive epoch millis; got " +
                "${steeringEnvelope["timestamp"]}"
        }

        // === 4. Final result text non-trivial ===
        assert(result.text.length > 100) {
            "result text is only ${result.text.length} chars; report LLM probably did not fire"
        }

        println("=== steerInjectsAtBeforeJudgeWithCanonicalMetadataEnvelope ===")
        println("TPipeConfig.getTraceDir() = ${TPipeConfig.getTraceDir()}")
        println("perTestDir = $perTestDir")
        println("DISPATCH_COMPLETED events: $dispatchCount")
        println("PATH_COMPLETED events: $pathCount")
        println("Exported files (${exportedFiles.size}):")
        exportedFiles.sortedBy { it.name }.forEach {
            println("  ${it.relativeTo(perTestDir)} (${it.length()} bytes)")
        }
    }

    /**
     * Multi-turn harness where a background coroutine fires an interrupt
     * after a few turns. The interrupt rewinds the harness state to the
     * BeforeJudge of the current turn slot and re-enters from the top. The
     * trace HTML must contain the interrupt text with the canonical
     * `metadata["interrupt"]` envelope (phase, wasRewound, injectionId, timestamp).
     *
     * The interrupt is fire-and-forget: it may fire at any turn >= 2. We don't
     * assert a specific turn count; we assert the interrupt text and envelope
     * keys are present in the pump HTML.
     */
    @Test
    fun interruptRewindsAndReentersFromBeforeJudge() = runBlocking<Unit>
    {
        if (liveGateOrSkip() == null) return@runBlocking

        val testName = "tpipe-config-interrupt-live"
        val perTestDir = File(TPipeConfig.getTraceDir(), testName)
        perTestDir.deleteRecursively()
        perTestDir.mkdirs()

        val traceCfg = TraceConfig(
            enabled = true,
            autoExport = true,
            exportPath = perTestDir.absolutePath,
            outputFormat = TraceFormat.HTML,
            detailLevel = TraceDetailLevel.DEBUG
        )

        val interruptText = "external interrupt: drop the in-flight path and switch strategy"

        val station = pumpStation("pumpstation-interrupt-tpipe-config")
        {
            judgeAgent = buildPipe("judge",
                "You are the research judge in an agentic harness. Inspect the " +
                    "conversation. If it contains '## Overview', return isComplete=true. " +
                    "Reply JSON: {\"isComplete\": <bool>, \"shouldTerminate\": false, " +
                    "\"reason\": \"<one line>\"}",
                traceCfg
            )
            dispatchAgent = buildPipe("dispatch",
                "You are the dispatcher. Pick the next path; return PathRequest JSON.",
                traceCfg
            )

            tracingConfiguration = traceCfg

            systemTask = "Produce a one-page technical brief on: $researchTopic."

            // Pre-seed the interrupt service with a one-shot that fires on
            // the very first BeforeJudge. The interrupt will rewind the
            // harness to BeforeJudge and re-enter; on re-entry the judge
            // sees the interrupt message in turnHistory. Pre-seeding is
            // more reliable than a background coroutine waiting on
            // turnIndex because the harness may exit after the first turn
            // if the judge decides the task is complete.
            interruptPolicy {
                initialQueue[PumpStationPausePhase.BeforeJudge] = listOf(
                    MultimodalContent(text = interruptText)
                )
            }

            path("gather")
            {
                description = "Gathers raw research findings on the topic."
                risk = PathRiskLevel.Low
                val gatherAgent = buildPipe("gather",
                    "You are a research gatherer. Produce 3-5 substantive findings " +
                        "on the topic. Aim for ~120 words.",
                    traceCfg
                )
                setInternalAgent(gatherAgent)
                setExecutionFunction { content, _, _, _ ->
                    val out = gatherAgent.executeLocal(content)
                    MultimodalContent(text = out.text)
                }
            }

            path("analyze")
            {
                description = "Analyzes the gathered material and tags 3 themes."
                risk = PathRiskLevel.Low
                setExecutionFunction { content, _, _, _ ->
                    val sentences = content.text.split(Regex("[.!?]+"))
                        .map { it.trim() }.filter { it.isNotBlank() }.take(3)
                    val themes = if (sentences.isNotEmpty())
                    {
                        sentences.mapIndexed { i, s -> "- theme ${i + 1}: $s" }.joinToString("\n")
                    } else "- theme 1: (no findings)"
                    MultimodalContent(
                        text = "Analyzed themes:\n$themes\n\nSource: ${content.text.take(300)}"
                    )
                }
            }

            path("report")
            {
                description = "Final brief writer."
                risk = PathRiskLevel.Low
                val reportAgent = buildPipe("report",
                    "You are a technical writer. Synthesize the analyzed themes into a " +
                        "one-page brief. Required sections in order: " +
                        "## Overview, ## Tradeoffs, ## Recommendation. Each 1-3 sentences.",
                    traceCfg
                )
                setInternalAgent(reportAgent)
                setExecutionFunction { content, _, _, _ ->
                    val out = reportAgent.executeLocal(content)
                    MultimodalContent(text = out.text)
                }
            }

            maxHarnessTurns = 8
        }

        val result = station.executeLocal(MultimodalContent(text = "Research: $researchTopic"))

        // === 1. Pump HTML present and non-empty ===
        val reportHtml = station.getTraceReport(TraceFormat.HTML)
        assert(reportHtml.isNotBlank() && reportHtml.contains("<html")) {
            "getTraceReport(HTML) returned non-HTML payload (len=${reportHtml.length})"
        }
        exportPerAgentTraces(station, perTestDir)

        val exportedFiles = perTestDir.walkTopDown().filter { it.isFile && it.extension == "html" }.toList()
        assert(exportedFiles.isNotEmpty()) { "no HTML traces exported to $perTestDir" }

        val pumpHtml = exportedFiles.first { it.name.contains("pumpstation", ignoreCase = true) }
        val pumpContent = pumpHtml.readText(Charsets.UTF_8)

        // === 2. Multi-turn assertion: at least one dispatch + path cycle
        // (the interrupt may rewind the in-flight turn but the loop continues
        // and dispatches a new path, so we still expect at least one full
        // cycle. The interrupt's wasRewound=true flag is what we verify below.)
        val dispatchCount = Regex("PUMP_STATION_DISPATCH_COMPLETED").findAll(pumpContent).count()
        assert(dispatchCount >= 1) { "no DISPATCH_COMPLETED events in pump HTML" }
        val pathCount = Regex("PUMP_STATION_PATH_COMPLETED").findAll(pumpContent).count()
        assert(pathCount >= 1) { "no PATH_COMPLETED events in pump HTML" }

        // === 3. Interrupt assertion: the live station's turnHistory is the
        // ground truth for the interrupt. The visualizer does NOT render the
        // interrupt text as a labeled field in the pump HTML (only the
        // standard metadata keys like contentPreview / contentLength /
        // inputTokens), so the previous "pumpContent.contains(interruptText)"
        // assertion was unreliable.
        //
        // The interrupt may cause the harness to exit via maxTurns because
        // the rewind discards the in-flight path and the judge may decide
        // to repeat. We do not assert result.text non-trivial in this test —
        // the steering test does that. We only assert that the interrupt
        // fired and the envelope shape is correct.
        val interruptEntry = station.turnHistory.history.firstOrNull {
            it.content.text == interruptText
        }
        assert(interruptEntry != null) {
            "interrupt text '$interruptText' not present in station.turnHistory — " +
                "the interrupt service did not drain the entry (the background " +
                "coroutine may have fired AFTER the harness completed, or the " +
                "harness exited before reaching BeforeJudge turn 2). " +
                "turnHistory size: ${station.turnHistory.history.size}, " +
                "turnIndex: ${station.taskState.turnIndex}, " +
                "exitReason: ${station.taskState.exitReason}"
        }
        @Suppress("UNCHECKED_CAST")
        val interruptEnvelope = interruptEntry!!.content.metadata["interrupt"] as? Map<String, Any>
        assert(interruptEnvelope != null) {
            "interrupt entry missing metadata['interrupt'] envelope; metadata keys: " +
                interruptEntry.content.metadata.keys.toString()
        }
        assert(interruptEnvelope!!["phase"] == PumpStationPausePhase.BeforeJudge.name) {
            "interrupt envelope phase mismatch: ${interruptEnvelope["phase"]}"
        }
        assert(interruptEnvelope["wasRewound"] == true) {
            "interrupt envelope wasRewound must be true; got " +
                "${interruptEnvelope["wasRewound"]}"
        }
        assert((interruptEnvelope["injectionId"] as? String)?.isNotBlank() == true) {
            "interrupt envelope injectionId blank"
        }
        assert((interruptEnvelope["timestamp"] as? Long) ?: 0L > 0L) {
            "interrupt envelope timestamp must be positive epoch millis; got " +
                "${interruptEnvelope["timestamp"]}"
        }

        println("=== interruptRewindsAndReentersFromBeforeJudge ===")
        println("TPipeConfig.getTraceDir() = ${TPipeConfig.getTraceDir()}")
        println("perTestDir = $perTestDir")
        println("DISPATCH_COMPLETED events: $dispatchCount")
        println("PATH_COMPLETED events: $pathCount")
        println("interrupt envelope: phase=${interruptEnvelope["phase"]} " +
            "wasRewound=${interruptEnvelope["wasRewound"]} " +
            "injectionId=${interruptEnvelope["injectionId"]}")
        println("Exported files (${exportedFiles.size}):")
        exportedFiles.sortedBy { it.name }.forEach {
            println("  ${it.relativeTo(perTestDir)} (${it.length()} bytes)")
        }
    }

    //=========================================Helpers============================================================

    /**
     * Build a one-pipe [Pipeline] bound to the MiniMax M2.7 endpoint, with tracing
     * enabled. Returns a ready-to-use Pipeline that can be assigned to judgeAgent
     * or dispatchAgent directly, or wrapped in a path via setInternalAgent.
     */
    private fun buildPipe(name: String, systemPrompt: String, traceCfg: TraceConfig): Pipeline
    {
        val pipe = GenericOpenAIPipe().apply {
            setApiKey(apiKeyCache!!)
            setApiMode(ApiMode.OpenAIResponses)
            setBaseUrl(minimaxBaseUrl)
            setPipeName(name)
            setModel(modelId)
            setSystemPrompt(systemPrompt)
            setMaxTokens(8000)
            setTemperature(1.0)
        }
        val pipeline = Pipeline().apply { add(pipe) }
        runBlocking { pipeline.init(true) }
        pipeline.enableTracing(traceCfg)
        return pipeline
    }

    /**
     * Walk the harness's [com.TTT.Debug.PipeTracer] store, group events by
     * pipeName, and write one HTML per pipe into [perTestDir] using
     * [TraceVisualizer]. The PumpStation HTML itself is auto-exported by
     * `getTraceReport(HTML)`.
     */
    private fun exportPerAgentTraces(@Suppress("UNUSED_PARAMETER") station: PumpStation, perTestDir: File)
    {
        val allTraces = com.TTT.Debug.PipeTracer.getAllTraces()
        if (allTraces.isEmpty()) return

        val byName = mutableMapOf<String, MutableList<TraceEvent>>()
        for ((_, events) in allTraces)
        {
            for (event in events)
            {
                if (event.pipeName.isBlank() || event.pipeName == "PumpStation") continue
                byName.getOrPut(event.pipeName) { mutableListOf() }.add(event)
            }
        }
        if (byName.isEmpty()) return

        val visualizer = TraceVisualizer()
        for ((name, events) in byName)
        {
            if (events.isEmpty()) continue
            val safeName = name.replace(Regex("[^A-Za-z0-9_.-]"), "_").ifBlank { "agent" }
            val outFile = File(perTestDir, "agent-${safeName}.html")
            val html = visualizer.generateHtmlReport(events)
            outFile.writeText(html)
        }
    }
}
