package com.TTT.Pipeline

import com.TTT.Context.ConverseData
import com.TTT.Context.ConverseRole
import com.TTT.Pipe.MultimodalContent
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for the hardened [DEFAULT_DISPATCH_PROMPT] and the empty-pathName error behavior.
 *
 * Background: the previous soft prompt told the dispatch LLM to "Return a PathRequest JSON
 * object as specified" without inlining the schema, leaving room for chat-mode LLMs to
 * drift into conversational prose when the user input was empty or ambiguous. The
 * hardened prompt now (a) inlines the PathRequest schema, (b) lists anti-prose rules,
 * (c) forbids empty pathName as a sentinel, (d) requires the dispatch to always pick a
 * path from the visible list.
 *
 * Background: the harness previously treated `pathRequest.pathName.isBlank()` as a
 * legitimate "I'm done, no path to call" sentinel, returning `TurnResult.Continue` to
 * spin the loop. That made it easy for chat-mode LLMs to silently drain the loop
 * budget. The harness now treats empty pathName as an error: it emits [PathFailed],
 * appends a hint to the conversation history so the next turn's dispatch LLM sees
 * the constraint, and lets the loop continue (bounded by [maxTurns] as a safety net).
 */
class PumpStationDispatchDefaultsTest
{
    // ---- Helpers ----

    private fun dispatchEmitsRawJson(rawJson: String): Pipeline
    {
        val pipe = ScriptedTestPipe(response = rawJson)
        return Pipeline().apply { add(pipe) }
    }

    private fun notDoneJudge(): Pipeline
    {
        val pipe = ScriptedTestPipe(response = """{"isComplete": false, "shouldTerminate": false}""")
        return Pipeline().apply { add(pipe) }
    }

    private fun dispatchAlwaysPicks(pathName: String): Pipeline
    {
        val pipe = ScriptedTestPipe(response = """{"pathName": "$pathName", "pathSchema": "{}"}""")
        return Pipeline().apply { add(pipe) }
    }

    private fun eventRecorder(): Pair<MutableList<PumpStationEvent>, (PumpStationEvent) -> Unit>
    {
        val events = mutableListOf<PumpStationEvent>()
        val observer: (PumpStationEvent) -> Unit = { ev -> synchronized(events) { events.add(ev) } }
        return events to observer
    }

    private fun <T : PumpStationEvent> uniqueBy(events: List<T>): List<T>
    {
        val seen = HashSet<Pair<Int, Long>>()
        val out = mutableListOf<T>()
        for (e in events)
        {
            val key = e.turnIndex to e.timestamp
            if (seen.add(key)) out.add(e)
        }
        return out
    }

    // ---- Part A: prompt hardening ----

    @Test
    fun testDefaultDispatchPromptContainsInlineSchema()
    {
        // The hardened prompt must include the PathRequest schema inline so the dispatch
        // LLM sees it in the soft prompt (not just in the bottom-of-prompt harness-mode
        // injection). This anchors a chat-mode LLM against drifting into prose.
        assertTrue(DEFAULT_DISPATCH_PROMPT.contains("\"pathName\""),
            "DEFAULT_DISPATCH_PROMPT must include the inline PathRequest schema with pathName field")
        assertTrue(DEFAULT_DISPATCH_PROMPT.contains("\"inputData\""),
            "DEFAULT_DISPATCH_PROMPT must include the inline PathRequest schema with inputData field")
    }

    @Test
    fun testDefaultDispatchPromptForbidsProseAndEmptyPathName()
    {
        // The hardened prompt must contain explicit anti-prose rules and an explicit
        // statement that empty pathName is not a valid signal.
        assertTrue(DEFAULT_DISPATCH_PROMPT.contains("ONLY the JSON object", ignoreCase = true) ||
            DEFAULT_DISPATCH_PROMPT.contains("MUST be a JSON object", ignoreCase = true),
            "DEFAULT_DISPATCH_PROMPT must explicitly forbid prose responses")
        assertTrue(DEFAULT_DISPATCH_PROMPT.contains("Empty pathName is", ignoreCase = true),
            "DEFAULT_DISPATCH_PROMPT must explicitly state empty pathName is not a valid signal")
        assertTrue(DEFAULT_DISPATCH_PROMPT.contains("MUST") || DEFAULT_DISPATCH_PROMPT.contains("must"),
            "DEFAULT_DISPATCH_PROMPT must contain MUST/must enforcement language")
    }

    @Test
    fun testDefaultDispatchPromptMentionsPassPipelineForCompletion()
    {
        // The new prompt directs LLMs to use a path with passPipeline=true to signal
        // completion, not empty pathName. Verify the pointer is present.
        assertTrue(DEFAULT_DISPATCH_PROMPT.contains("passPipeline", ignoreCase = true),
            "DEFAULT_DISPATCH_PROMPT must direct LLMs to use passPipeline=true for completion signals")
    }

    // ---- Part B: empty-pathName is now treated as an error, not a sentinel ----

    @Test
    fun testEmptyPathNameTriggersPathFailedEventAndHint()
    {
        runBlocking {
            val (events, observer) = eventRecorder()
            val station = buildTestStation(maxHarnessTurns = 3)
                .setJudgeAgent(notDoneJudge())
                // Dispatch emits valid JSON with pathName="" — old behavior: sentinel, loop
                // continues silently. New behavior: harness error with hint.
                .setDispatchAgent(dispatchEmitsRawJson("""{"pathName": "", "inputSchema": "{}"}"""))
                .setEventObserver(observer)
            station.addPath(testPath("p1", returnText = "did something"))

            val initialHistorySize = station.turnHistory.history.size

            station.executeLocal(MultimodalContent(text = "task"))

            val pathFailed = uniqueBy(synchronized(events) { events.filterIsInstance<PathFailed>() })
            assertEquals(3, pathFailed.size,
                "PathFailed must fire once per turn (default maxHarnessTurns=3 → 3 turns → 3 PathFailed); " +
                    "got ${pathFailed.size}")
            // Each PathFailed must carry the empty-pathName marker so consumers can distinguish
            // empty-pathName failures from generic DispatchJsonRepairFailed failures.
            pathFailed.forEach { pf ->
                assertEquals("(empty)", pf.pathName,
                    "Every empty-pathName PathFailed must carry pathName=\"(empty)\"")
                assertTrue(pf.errorMessage?.contains("empty pathName", ignoreCase = true) == true,
                    "Every empty-pathName PathFailed errorMessage must explain the failure; got: ${pf.errorMessage}")
            }

            // Conversation history must have grown: the hint should be appended on the
            // turn where empty pathName was rejected, so the next turn's dispatch LLM sees it.
            assertTrue(station.turnHistory.history.size > initialHistorySize,
                "turnHistory must grow after empty-pathName failure (hint appended)")
            val hintText = station.turnHistory.history.last().content.text
            assertTrue(hintText.contains("empty pathName", ignoreCase = true) ||
                hintText.contains("[Harness Notice]", ignoreCase = true),
                "Last turnHistory entry must contain the empty-pathName hint; got: $hintText")
        }
    }

    @Test
    fun testEmptyPathNameDoesNotHaltHarnessButDoesBurnTurns()
    {
        runBlocking {
            val (events, observer) = eventRecorder()
            val station = buildTestStation(maxHarnessTurns = 2)
                .setJudgeAgent(notDoneJudge())
                .setDispatchAgent(dispatchEmitsRawJson("""{"pathName": "", "inputSchema": "{}"}"""))
                .setEventObserver(observer)
            station.addPath(testPath("p1", returnText = "did something"))

            station.executeLocal(MultimodalContent(text = "task"))

            // Each turn should produce one PathFailed event. We expect 2 turns of retries
            // before maxTurns kicks in (judge never returns isComplete=true on its own).
            val pathFailed = uniqueBy(synchronized(events) { events.filterIsInstance<PathFailed>() })
            assertEquals(2, pathFailed.size,
                "Empty pathName must produce one PathFailed per turn; got ${pathFailed.size} for maxHarnessTurns=2")

            // exitReason should be MaxTurnsHit (the safety net), not JudgeComplete or PassSignal.
            // The empty pathName failure path does NOT halt the harness — it just burns turns.
            val exitReason = station.getTaskState().exitReason
            assertEquals(PumpStationExitReason.MaxTurnsHit, exitReason,
                "Empty-pathName retries should be bounded by maxTurns, not by an early exit. " +
                    "Got exitReason=$exitReason")
        }
    }

    @Test
    fun testNonEmptyPathNameStillWorksNormally()
    {
        // Regression guard: valid pathName still flows through to path execution.
        runBlocking {
            val (events, observer) = eventRecorder()
            val pathCallCount = IntArray(1)
            val station = buildTestStation(maxHarnessTurns = 3)
                .setJudgeAgent(notDoneJudge())
                .setDispatchAgent(dispatchAlwaysPicks("p1"))
                .setEventObserver(observer)
            station.addPath(testPath("p1", returnText = "did something", callCount = pathCallCount))

            station.executeLocal(MultimodalContent(text = "task"))

            assertTrue(pathCallCount[0] >= 1, "Path 'p1' must be invoked when dispatch returns non-empty pathName")
            val pathFailed = synchronized(events) { events.filterIsInstance<PathFailed>() }
            assertTrue(pathFailed.isEmpty(),
                "No PathFailed events should fire when dispatch returns a valid pathName; got ${pathFailed.size}")
        }
    }

    @Test
    fun testUnknownPathNameStillTriggersExistingPathFailed()
    {
        // Regression guard: unknown pathName continues to trigger the existing
        // PathFailed path (via runPathFlow) — only the empty-pathName shortcut
        // was changed.
        runBlocking {
            val (events, observer) = eventRecorder()
            val station = buildTestStation(maxHarnessTurns = 2)
                .setJudgeAgent(notDoneJudge())
                // Dispatch asks for a path that doesn't exist
                .setDispatchAgent(dispatchAlwaysPicks("nonexistent"))
                .setEventObserver(observer)
            station.addPath(testPath("p1", returnText = "did something"))

            station.executeLocal(MultimodalContent(text = "task"))

            // Unknown path is handled by runPathFlow's existing error path, not by our
            // new empty-pathName branch. The pathName in the failure event will be the
            // requested pathName ("nonexistent"), NOT "(empty)".
            val pathFailed = uniqueBy(synchronized(events) { events.filterIsInstance<PathFailed>() })
            assertTrue(pathFailed.any { it.pathName == "nonexistent" },
                "Unknown pathName must still trigger PathFailed via runPathFlow's existing error path; " +
                    "got: ${pathFailed.map { it.pathName }}")
            assertTrue(pathFailed.none { it.pathName == "(empty)" },
                "Unknown pathName must NOT trigger the empty-pathName branch; got: ${pathFailed.map { it.pathName }}")
        }
    }
}