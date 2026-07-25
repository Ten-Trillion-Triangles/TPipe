package com.TTT.Debug

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.io.File

/**
 * Manual verification helper. Builds a realistic four-turn PumpStation trace covering every
 * visualizer feature (state ribbon KPIs, memory-pressure sparkline, path inventory, reserve
 * reveals, turn timeline with phase ribbon, background activity strip, outcome panel, loop-guard
 * trip, context blowout, stash, intervention) and writes the resulting HTML report to
 * `build/pumpstation-samples/pumpstation-sample-report.html` so a developer can open it in a
 * browser to inspect the rendered output.
 *
 * The test itself asserts the four structural invariants the visualizer must preserve:
 *  1. The dispatcher still routes a non-empty PUMP_STATION_*-only trace to the PumpStation
 *     renderer (proves we built a valid PumpStation trace, not a generic one).
 *  2. The report contains every layout primitive the visualizer is supposed to render.
 *  3. The file lands on disk at a known, predictable path.
 *  4. The file is non-trivially sized — a few KB of HTML, not a placeholder.
 *
 * Re-run with: `./gradlew :test --tests "com.TTT.Debug.PumpStationSampleReportTest"`
 * Then open the printed path in a browser to inspect.
 */
class PumpStationSampleReportTest
{
    private val visualizer = TraceVisualizer()

    @Test
    fun generateAndPersistRichSampleReport()
    {
        val trace = buildRichFourTurnTrace()
        val html = visualizer.generatePumpStationHtmlReport(trace)

        // Layout invariants — must hold for the visualizer to be considered correct.
        assertTrue(html.contains("PumpStation Trace"),
            "Report must use the PumpStation heading")
        assertTrue(html.contains("ps-ribbon"),
            "Report must render the state ribbon (KPI cards)")
        assertTrue(html.contains("ps-sparkline"),
            "Report must render the memory-pressure sparkline")
        assertTrue(html.contains("ps-paths-section"),
            "Report must render the path inventory")
        assertTrue(html.contains("ps-turns-section"),
            "Report must render the turn timeline")
        assertTrue(html.contains("ps-outcome"),
            "Report must render the outcome panel")
        // Content-extras invariants — the new visualizer primitives (collapsible content panels,
        // token-usage chips, nested P2P block) must appear in the sample so a developer opening
        // the rendered HTML can see the new features.
        assertTrue(html.contains("ps-event-extras"),
            "Report must render the per-event content extras blocks")
        assertTrue(html.contains("ps-token-chip"),
            "Report must render token-usage chips")
        assertTrue(html.contains("ps-token-summary"),
            "Report must render the per-turn token summary row")
        assertTrue(html.contains("ps-nested-p2p"),
            "Report must render the nested P2P block (sample includes at least one nested call)")
        assertTrue(html.contains("ps-event-text"),
            "Report must render the text content pre blocks")

        // Persist the report and surface the absolute path so the developer can open it.
        val outDir = File("build/pumpstation-samples").apply { mkdirs() }
        val outFile = File(outDir, "pumpstation-sample-report.html")
        outFile.writeText(html, Charsets.UTF_8)

        // File-lifecycle invariants — guarantees the artifact is actually usable.
        assertTrue(outFile.exists(), "Report file must be created on disk")
        assertTrue(outFile.length() > 8_000,
            "Report must be more than a placeholder (got ${outFile.length()} bytes)")
        assertTrue(outFile.canRead(), "Report must be readable by the developer")

        // Surface the path on stdout so it's easy to find in the test log.
        println("[PumpStationSampleReportTest] wrote ${outFile.absolutePath} " +
            "(${outFile.length()} bytes, ${trace.size} events across " +
            "${trace.map { it.metadata["turnIndex"] }.distinct().size} turns)")
    }

    /**
     * Build a deterministic four-turn trace for visual inspection. The scenario is a debugging
     * session where the agent initially loops on the "search" path (loop guard trips), a context
     * blowout occurs and triggers a stash, a reserve path "advanced-search" is revealed and
     * succeeds, and the goal validation passes on the final turn. Memory pressure climbs
     * monotonically through the first three turns (good sparkline content) and drops after the
     * stash/compaction in turn two.
     */
    private fun buildRichFourTurnTrace(): List<TraceEvent>
    {
        val events = mutableListOf<TraceEvent>()
        val runId = "ps-sample-rich-001"
        var now = System.currentTimeMillis()

        //---- Lifecycle --------------------------------------------------------------------
        events += event(0, TraceEventType.PUMP_STATION_STARTED, runId, now)
        events += event(0, TraceEventType.PUMP_STATION_RESUMED, runId, now + 5)
        now += 10

        //---- Turn 0: Health check, judge in progress, first dispatch ----------------------
        events += event(0, TraceEventType.PUMP_STATION_HEALTH_CHECK_STARTED, runId, now); now += 30
        events += event(0, TraceEventType.PUMP_STATION_HEALTH_CHECK_COMPLETED, runId, now,
            mapOf("status" to "OK", "warnings" to 0, "terminateHarness" to false)); now += 20

        events += event(0, TraceEventType.PUMP_STATION_JUDGE_STARTED, runId, now); now += 15
        events += event(0, TraceEventType.PUMP_STATION_JUDGE_COMPLETED, runId, now,
            mapOf(
                "isComplete" to false,
                "shouldTerminate" to false,
                "contentPreview" to "text=verdict: not yet complete, more paths needed",
                "contentLength" to 47,
                "modelReasoning" to "Reasoning: the user asked for TPipe PumpStation docs. The agent has not surfaced them yet. Continue loop.",
                "modelReasoningLen" to 109,
                "inputTokens" to 320,
                "outputTokens" to 48,
                "totalTokens" to 368
            )); now += 15

        events += event(0, TraceEventType.PUMP_STATION_DISPATCH_STARTED, runId, now); now += 10
        events += event(0, TraceEventType.PUMP_STATION_DISPATCH_COMPLETED, runId, now,
            mapOf(
                "selectedPathName" to "search",
                "pathRequest" to "find the TPipe PumpStation docs",
                "contentPreview" to "text=dispatcher chose: search (path=search, schema=...)",
                "contentLength" to 51,
                "inputTokens" to 410,
                "outputTokens" to 72,
                "totalTokens" to 482
            )); now += 10

        events += event(0, TraceEventType.PUMP_STATION_PATH_SELECTED, runId, now,
            mapOf("pathName" to "search", "riskLevel" to "LOW")); now += 10
        events += event(0, TraceEventType.PUMP_STATION_PATH_SAFETY_STARTED, runId, now,
            mapOf("pathName" to "search", "riskLevel" to "LOW")); now += 20
        events += event(0, TraceEventType.PUMP_STATION_PATH_SAFETY_COMPLETED, runId, now,
            mapOf("pathName" to "search", "riskLevel" to "LOW", "approved" to true, "reason" to "")); now += 10
        events += event(0, TraceEventType.PUMP_STATION_PATH_STARTED, runId, now,
            mapOf("pathName" to "search", "riskLevel" to "LOW")); now += 150
        events += event(0, TraceEventType.PUMP_STATION_PATH_COMPLETED, runId, now,
            mapOf(
                "pathName" to "search",
                "riskLevel" to "LOW",
                "tokensUsed" to 812,
                "contentPreview" to "text=found 3 PumpStation doc pages",
                "contentLength" to 30,
                "inputTokens" to 612,
                "outputTokens" to 200,
                "totalTokens" to 812
            )); now += 10
        events += event(0, TraceEventType.PUMP_STATION_PATH_VALIDATION_COMPLETED, runId, now,
            mapOf("pathName" to "search", "approved" to true, "reason" to "answered the prompt"))

        // Foreground agent (researcher) returns summary content
        events += event(0, TraceEventType.PUMP_STATION_FOREGROUND_AGENT_COMPLETED, runId, now + 5,
            mapOf(
                "agentName" to "researcher",
                "contentPreview" to "text=researcher summary: TPipe PumpStation is a turn-based harness runtime",
                "contentLength" to 73,
                "inputTokens" to 280,
                "outputTokens" to 95,
                "totalTokens" to 375
            ))

        // A nested P2P call fired from inside the path — surfaces the sub-entry under
        // the path's content panel.
        events += event(0, TraceEventType.PUMP_STATION_NESTED_P2P_COMPLETED, runId, now + 30,
            mapOf(
                "pathName" to "search",
                "agentName" to "(nested-p2p)",
                "contentPreview" to "text=nested retrieval: 12 raw results",
                "contentLength" to 32,
                "inputTokens" to 180,
                "outputTokens" to 64,
                "totalTokens" to 244
            ))

        events += event(0, TraceEventType.PUMP_STATION_MEMORY_UPDATE_STARTED, runId, now + 10,
            mapOf("memoryMode" to "Append"))
        events += event(0, TraceEventType.PUMP_STATION_MEMORY_UPDATE_COMPLETED, runId, now + 20,
            mapOf("memoryMode" to "Append", "compactionPercent" to 0.42,
                "loreBookActive" to false, "summaryActive" to false))
        now += 30

        //---- Turn 1: Judge completes, dispatch repeats the same path -> loop guard trips ---
        events += event(1, TraceEventType.PUMP_STATION_HEALTH_CHECK_COMPLETED, runId, now,
            mapOf("status" to "OK", "warnings" to 1, "terminateHarness" to false)); now += 20

        events += event(1, TraceEventType.PUMP_STATION_JUDGE_STARTED, runId, now); now += 15
        events += event(1, TraceEventType.PUMP_STATION_JUDGE_COMPLETED, runId, now,
            mapOf("isComplete" to true, "shouldTerminate" to false)); now += 10

        events += event(1, TraceEventType.PUMP_STATION_DISPATCH_STARTED, runId, now); now += 10
        events += event(1, TraceEventType.PUMP_STATION_DISPATCH_COMPLETED, runId, now,
            mapOf("selectedPathName" to "search", "pathRequest" to "follow up on the docs")); now += 10

        // The loop guard fires BEFORE the path executes, with a clear detail string.
        events += event(1, TraceEventType.PUMP_STATION_LOOP_GUARD_TRIPPED, runId, now,
            mapOf("guard" to "maxConsecutiveSamePath", "pathName" to "search",
                "detail" to "consecutive=2, limit=2")); now += 5
        events += event(1, TraceEventType.PUMP_STATION_PATH_FAILED, runId, now,
            mapOf("pathName" to "search", "riskLevel" to "LOW",
                "error" to "MaxConsecutiveSamePath", "errorMessage" to "Consecutive path limit reached")); now += 10
        events += event(1, TraceEventType.PUMP_STATION_PATH_HIDDEN, runId, now,
            mapOf("pathName" to "search", "reason" to "Loop guard tripped"))

        //---- Turn 2: LLM gets stuck dispatching a non-existent path; maxConsecutiveUnknownPaths trips ----
        // Three consecutive UnknownPath failures, then the guard fires.
        events += event(2, TraceEventType.PUMP_STATION_JUDGE_COMPLETED, runId, now,
            mapOf("isComplete" to false, "shouldTerminate" to false)); now += 10
        for (i in 1..3)
        {
            events += event(2, TraceEventType.PUMP_STATION_DISPATCH_STARTED, runId, now); now += 5
            events += event(2, TraceEventType.PUMP_STATION_DISPATCH_COMPLETED, runId, now,
                mapOf("selectedPathName" to "flarble",
                    "pathRequest" to """{"pathName":"flarble","inputData":{}}""")); now += 5
            events += event(2, TraceEventType.PUMP_STATION_PATH_FAILED, runId, now,
                mapOf("pathName" to "flarble", "riskLevel" to "LOW",
                    "error" to "UnknownPath",
                    "errorMessage" to "Path 'flarble' not found")); now += 5
        }
        // The third consecutive UnknownPath trips the new maxConsecutiveUnknownPaths guard.
        events += event(2, TraceEventType.PUMP_STATION_LOOP_GUARD_TRIPPED, runId, now,
            mapOf("guard" to "maxConsecutiveUnknownPaths", "pathName" to "flarble",
                "detail" to "consecutive=3, limit=3", "metric" to "consecutive",
                "observed" to 3, "limit" to 3)); now += 5
        events += event(2, TraceEventType.PUMP_STATION_PATH_FAILED, runId, now,
            mapOf("pathName" to "flarble", "riskLevel" to "LOW",
                "error" to "LoopGuardTriggered",
                "errorMessage" to "maxConsecutiveUnknownPaths exceeded for path 'flarble'")); now += 5
        events += event(2, TraceEventType.PUMP_STATION_FAILED, runId, now,
            mapOf("error" to "LoopGuardTriggered",
                "errorMessage" to "maxConsecutiveUnknownPaths exceeded for path 'flarble'",
                "exitReason" to "LoopGuardTripped"))

        // Context pressure climbs hard and trips the blowout detector. Stash catches the output.
        events += event(1, TraceEventType.PUMP_STATION_CONTEXT_BLOWOUT_DETECTED, runId, now,
            mapOf("fillRatio" to 0.94, "threshold" to 0.90, "afterPhase" to "PathExecution")); now += 10
        events += event(1, TraceEventType.PUMP_STATION_STASH_CREATED, runId, now,
            mapOf("stashId" to "stash-001", "sourcePath" to "search",
                "reason" to "ContextBlowout", "tokenEstimate" to 4200)); now += 10

        // Compaction runs after the blowout, history shrinks.
        events += event(1, TraceEventType.PUMP_STATION_COMPACTION_STARTED, runId, now,
            mapOf("strategy" to "SummarizeAndDrop", "memoryMode" to "Append")); now += 50
        events += event(1, TraceEventType.PUMP_STATION_COMPACTION_COMPLETED, runId, now,
            mapOf("strategy" to "SummarizeAndDrop", "memoryMode" to "Append",
                "previousHistorySize" to 18, "newHistorySize" to 6)); now += 10

        events += event(1, TraceEventType.PUMP_STATION_MEMORY_UPDATE_STARTED, runId, now,
            mapOf("memoryMode" to "Compaction"))
        events += event(1, TraceEventType.PUMP_STATION_MEMORY_UPDATE_COMPLETED, runId, now + 30,
            mapOf("memoryMode" to "Compaction", "compactionPercent" to 0.55,
                "loreBookActive" to true, "summaryActive" to true))
        now += 60

        //---- Turn 2: Intervention, reserve path revealed, succeeds ------------------------
        events += event(2, TraceEventType.PUMP_STATION_HEALTH_CHECK_COMPLETED, runId, now,
            mapOf("status" to "OK", "warnings" to 0, "terminateHarness" to false)); now += 15

        // Intervention fires because the previous turn failed.
        events += event(2, TraceEventType.PUMP_STATION_INTERVENTION_STARTED, runId, now,
            mapOf("pathName" to "search", "trigger" to "ConsecutiveFailure")); now += 30
        events += event(2, TraceEventType.PUMP_STATION_INTERVENTION_COMPLETED, runId, now,
            mapOf("nudges" to 1, "shouldContinue" to true)); now += 10

        // Judge and dispatch now reveal a reserve path.
        events += event(2, TraceEventType.PUMP_STATION_JUDGE_COMPLETED, runId, now,
            mapOf("isComplete" to true, "shouldTerminate" to false)); now += 10
        events += event(2, TraceEventType.PUMP_STATION_DISPATCH_COMPLETED, runId, now,
            mapOf("selectedPathName" to "advanced-search",
                "pathRequest" to "search with semantic expansion")); now += 5
        events += event(2, TraceEventType.PUMP_STATION_RESERVE_PATH_REVEALED, runId, now,
            mapOf("pathName" to "advanced-search",
                "reservePathNames" to listOf("advanced-search", "experimental-retrieval"))); now += 5

        // Path safety & validation: this one is high risk, requires explicit approval.
        events += event(2, TraceEventType.PUMP_STATION_PATH_SELECTED, runId, now,
            mapOf("pathName" to "advanced-search", "riskLevel" to "HIGH")); now += 10
        events += event(2, TraceEventType.PUMP_STATION_PATH_SAFETY_STARTED, runId, now,
            mapOf("pathName" to "advanced-search", "riskLevel" to "HIGH")); now += 20
        events += event(2, TraceEventType.PUMP_STATION_PATH_SAFETY_COMPLETED, runId, now,
            mapOf("pathName" to "advanced-search", "riskLevel" to "HIGH",
                "approved" to true, "reason" to "operator override")); now += 10
        events += event(2, TraceEventType.PUMP_STATION_PATH_STARTED, runId, now,
            mapOf("pathName" to "advanced-search", "riskLevel" to "HIGH")); now += 200
        events += event(2, TraceEventType.PUMP_STATION_PATH_COMPLETED, runId, now,
            mapOf("pathName" to "advanced-search", "riskLevel" to "HIGH", "tokensUsed" to 1640)); now += 10
        events += event(2, TraceEventType.PUMP_STATION_PATH_VALIDATION_COMPLETED, runId, now,
            mapOf("pathName" to "advanced-search", "approved" to true, "reason" to "found the missing piece"))

        // Background agent finishes a stale retrieval off-cycle.
        events += event(2, TraceEventType.PUMP_STATION_BACKGROUND_AGENT_QUEUED, runId, now + 5,
            mapOf("agentName" to "indexer"))

        events += event(2, TraceEventType.PUMP_STATION_MEMORY_UPDATE_STARTED, runId, now + 20,
            mapOf("memoryMode" to "Append"))
        events += event(2, TraceEventType.PUMP_STATION_MEMORY_UPDATE_COMPLETED, runId, now + 40,
            mapOf("memoryMode" to "Append", "compactionPercent" to 0.78,
                "loreBookActive" to true, "summaryActive" to true))
        now += 80

        //---- Turn 3: Goal validation passes, harness completes ----------------------------
        events += event(3, TraceEventType.PUMP_STATION_HEALTH_CHECK_COMPLETED, runId, now,
            mapOf("status" to "OK", "warnings" to 0, "terminateHarness" to false)); now += 15
        events += event(3, TraceEventType.PUMP_STATION_JUDGE_COMPLETED, runId, now,
            mapOf("isComplete" to true, "shouldTerminate" to true)); now += 10
        events += event(3, TraceEventType.PUMP_STATION_GOAL_VALIDATION_STARTED, runId, now); now += 25
        events += event(3, TraceEventType.PUMP_STATION_GOAL_VALIDATION_COMPLETED, runId, now,
            mapOf("passed" to true, "reason" to "All objectives met"))
        events += event(3, TraceEventType.PUMP_STATION_MEMORY_UPDATE_STARTED, runId, now + 5,
            mapOf("memoryMode" to "Append"))
        events += event(3, TraceEventType.PUMP_STATION_MEMORY_UPDATE_COMPLETED, runId, now + 20,
            mapOf("memoryMode" to "Append", "compactionPercent" to 0.51,
                "loreBookActive" to true, "summaryActive" to true))
        now += 40

        // Final lifecycle: completed.
        events += event(3, TraceEventType.PUMP_STATION_COMPLETED, runId, now)

        return events
    }

    /**
     * Local copy of the test helper from PumpStationTraceVisualizationTest. The shared helper
     * uses `System.currentTimeMillis() + turnIndex * 1000L` which would put every event in the
     * same turn at the same timestamp, hiding the timeline ordering. This copy accepts an
     * explicit timestamp so within-turn events can be chronologically ordered.
     */
    private fun event(
        turnIndex: Int,
        eventType: TraceEventType,
        runId: String,
        timestamp: Long,
        metadata: Map<String, Any> = emptyMap()
    ): TraceEvent
    {
        val baseMeta = mutableMapOf<String, Any>("turnIndex" to turnIndex, "runId" to runId)
        baseMeta.putAll(metadata)
        return TraceEvent(
            timestamp = timestamp,
            pipeId = runId,
            pipeName = "PumpStation",
            eventType = eventType,
            phase = TracePhase.EXECUTION,
            content = null,
            contextSnapshot = null,
            metadata = baseMeta
        )
    }
}
