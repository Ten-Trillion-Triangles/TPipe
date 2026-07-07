package com.TTT.Pipeline

import com.TTT.Pipe.MultimodalContent
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertTrue
import kotlin.test.assertEquals

/**
 * Bug 1 + Bug 2 TDD regression tests.
 *
 * Background (from 2026-07-07 trace analysis):
 *   - Live trace 01-always-on-judge: judge LLM on T4 returned
 *     `isComplete=true, reason: "...brief on 'How to Use AI to Write Your Next Book' has been fully generated..."`
 *     while the test topic was Kotlin coroutines vs Java virtual threads.
 *   - Live trace 02-flag-triggered-judge: dispatch LLM on T4 selected `gather` again
 *     saying "No prior work has been done" even though T1-T3 already ran gather/analyze/report.
 *
 * Root cause (per trace + code inspection):
 *   - [PumpStationHelpers.buildTurnContent] correctly puts turnHistory into
 *     `content.context.converseHistory` (verified by [BuildContentTest]).
 *   - But [com.TTT.Pipe.Pipe.generateContent]'s default implementation in Pipe.kt:5660-5664
 *     calls `generateText(content.text)` and drops `content.context.converseHistory`.
 *   - [GenericOpenAIPipe] (which the harness uses) inherits this default and
 *     never reads `content.context.converseHistory`.
 *   - Result: judge and dispatch LLMs receive only their system prompt + a 1-line
 *     "phase question" via `content.text`, with no conversation history.
 *
 * These tests pin the contract that the JUDGE and DISPATCH agents must see the
 * prior turn history in their LLM input. RED: prompt does NOT contain the canary
 * text from the previous path output. GREEN: prompt DOES contain it.
 */
class JudgeDispatchHistoryInjectionTest
{
    /**
     * Recording variant of [ScriptedTestPipe]. Captures every prompt the LLM
     * would receive (the `promptInjector` argument to generateText) into a
     * thread-safe list, then returns the configured [response] string.
     */
    private class RecordingPipe(
        private val name: String,
        var response: String
    ) : com.TTT.Pipe.Pipe()
    {
        init { pipeName = name }

        val capturedPrompts: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())

        override suspend fun generateText(promptInjector: String): String
        {
            capturedPrompts.add(promptInjector)
            return response
        }

        override fun truncateModuleContext(): com.TTT.Pipe.Pipe = this
    }

    private fun makeJudgePipeline(response: String): Pair<Pipeline, RecordingPipe>
    {
        val pipe = RecordingPipe("judge", response)
        return Pipeline().apply { add(pipe) } to pipe
    }

    private fun makeDispatchPipeline(response: String): Pair<Pipeline, RecordingPipe>
    {
        val pipe = RecordingPipe("dispatch", response)
        return Pipeline().apply { add(pipe) } to pipe
    }

    /**
     * Bug 1 RED test: judge LLM must receive the prior turn's path output
     * in its prompt. The judge is fed after a path runs, so its prompt
     * should include the path's output text.
     */
    @Test
    fun testJudgePromptIncludesPriorPathOutput()
    {
        runBlocking {
            val station = buildTestStation(maxHarnessTurns = 3)
            val (judgePipeline, judgeRecorder) = makeJudgePipeline(
                """{"isComplete": true, "shouldTerminate": false, "reason": "task complete"}"""
            )
            val (dispatchPipeline, dispatchRecorder) = makeDispatchPipeline(
                """{"pathName": "p1", "pathSchema": "{}"}"""
            )
            station.setJudgeAgent(judgePipeline).setDispatchAgent(dispatchPipeline)

            // Path returns a CANARY string. If the harness correctly threads
            // the turn history into the judge LLM prompt, the canary will
            // appear in the second capture (T1 judge, after path runs).
            val canary = "CANARY_PATH_OUTPUT_judge_injection_test"
            station.addPath(testPath("p1", returnText = canary))

            station.executeLocal(MultimodalContent(text = "task"))

            // The judge fires once at T1 (after dispatch picks p1 on T0, p1 runs).
            // The capture count depends on whether the path's executeFunction
            // output gets added to turnHistory (it should, per PumpStationLoop
            // contract) AND whether the converseHistory gets injected into the
            // judge prompt. We assert at least one judge capture contains the canary.
            val judgeCaptures = judgeRecorder.capturedPrompts
            assertTrue(
                judgeCaptures.any { it.contains(canary) },
                "Judge LLM prompt did NOT include prior path output (canary='$canary'). " +
                    "Captured prompts (n=${judgeCaptures.size}): " +
                    judgeCaptures.joinToString(" || ") { it.take(200) }
            )
        }
    }

    /**
     * Bug 2 RED test: dispatch LLM must receive the prior turn's path output
     * in its prompt. The dispatch on T2 should see T1's gather output when
     * selecting the next path — otherwise it loops on `gather` saying
     * "no prior work has been done" (the exact bug from 02-flag-triggered-judge).
     */
    @Test
    fun testDispatchPromptIncludesPriorPathOutput()
    {
        runBlocking {
            val station = buildTestStation(maxHarnessTurns = 3)
            // Judge: never complete (loop bounded by maxHarnessTurns=3)
            val (judgePipeline, _) = makeJudgePipeline(
                """{"isComplete": false, "shouldTerminate": false, "reason": ""}"""
            )
            val (dispatchPipeline, dispatchRecorder) = makeDispatchPipeline(
                """{"pathName": "p1", "pathSchema": "{}"}"""
            )
            station.setJudgeAgent(judgePipeline).setDispatchAgent(dispatchPipeline)

            // Path returns a CANARY string every call. If the harness threads
            // history into the dispatch prompt, the SECOND dispatch (T1, after
            // p1 ran on T0) will see the canary in its prompt.
            val canary = "CANARY_DISPATCH_HISTORY_TEST_xyz"
            station.addPath(testPath("p1", returnText = canary))

            station.executeLocal(MultimodalContent(text = "task"))

            // T0 dispatch picks p1 (first call, no history expected).
            // T1 dispatch picks p1 again (judge says not complete).
            // The T1 dispatch prompt MUST contain the canary from T0's p1 output.
            val dispatchCaptures = dispatchRecorder.capturedPrompts
            assertTrue(
                dispatchCaptures.size >= 2,
                "Expected at least 2 dispatch LLM calls (T0 and T1), got ${dispatchCaptures.size}"
            )
            val secondDispatchPrompt = dispatchCaptures[1]
            assertTrue(
                secondDispatchPrompt.contains(canary),
                "T1 dispatch LLM prompt did NOT include T0 path output (canary='$canary'). " +
                    "T1 prompt first 400 chars: ${secondDispatchPrompt.take(400)}"
            )
        }
    }

    /**
     * Pinning test for the fix: the system prompt the harness injects into the
     * judge/dispatch pipelines explicitly claims turn history will be present
     * in the conversation history. This test asserts that claim is consistent
     * with the actual prompt content (RED if either side drifts).
     *
     * Verifies [DEFAULT_JUDGE_FOOTER] + [DEFAULT_DISPATCH_FOOTER] both reference
     * "conversation history" so we can detect if either is dropped without
     * the fix noticing.
     */
    @Test
    fun testDefaultPromptsReferenceConversationHistory()
    {
        // Both footers should mention "conversation history" so a future
        // maintainer who sees the prompt text in a trace HTML understands
        // the harness contractually promises to inject it.
        assertTrue(
            DEFAULT_JUDGE_FOOTER.contains("conversation history", ignoreCase = true),
            "DEFAULT_JUDGE_FOOTER must reference 'conversation history' to set " +
                "LLM expectations about what the prompt contains"
        )
        assertTrue(
            DEFAULT_DISPATCH_FOOTER.contains("conversation history", ignoreCase = true),
            "DEFAULT_DISPATCH_FOOTER must reference 'conversation history'"
        )
    }

    /**
     * Belt-and-suspenders assertion: even on a fresh harness with no prior
     * work, the judge prompt should contain MORE than just the static system
     * prompt + phase question. There should be SOMETHING about the conversation
     * history shape (the footer text itself, which is appended per the harness
     * contract).
     *
     * This test passes today because the system prompt itself contains the
     * footer text. It guards against future refactors that move the footer
     * out of the system prompt and into a "we'll inject this for you" path
     * that might silently break.
     */
    @Test
    fun testJudgeSystemPromptContainsHistoryReference()
    {
        // Default system prompt text the harness injects into the judge.
        val judgeSystemPrompt = DEFAULT_JUDGE_PROMPT
        assertTrue(
            judgeSystemPrompt.contains("conversation history", ignoreCase = true) ||
                judgeSystemPrompt.contains("turn history", ignoreCase = true),
            "DEFAULT_JUDGE_PROMPT must contain a reference to turn/conversation " +
                "history so the LLM knows where to look for prior work"
        )
        // Same for dispatch.
        assertTrue(
            DEFAULT_DISPATCH_PROMPT.contains("conversation history", ignoreCase = true) ||
                DEFAULT_DISPATCH_PROMPT.contains("turn history", ignoreCase = true),
            "DEFAULT_DISPATCH_PROMPT must contain a reference to turn/conversation " +
                "history so the LLM knows where to look for prior work"
        )
    }
}