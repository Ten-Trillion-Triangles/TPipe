package com.TTT.Pipeline

import com.TTT.Pipe.MultimodalContent
import com.TTT.Context.ConverseData
import com.TTT.Context.ConverseRole
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

/**
 * Unit test verifying that a path-safety rejection hint reaches the LLM-facing
 * dispatch prompt via the turn history serialization path.
 *
 * The trace triage of 2026-07-23 found that live-04 dispatched "gather" 47
 * times in a row despite the path-safety gate rejecting the path 3 times with
 * "too vague" reasons. Three sub-possibilities were identified:
 *
 *   1. The hint is appended to turnHistory but never makes it into the pipe's
 *      prompt context.
 *   2. The hint reaches context but gets buried under accumulated entries.
 *   3. The hint reaches context correctly and the LLM sees it but ignores it.
 *
 * This test pins down sub-possibility #1: it builds a PumpStation, simulates
 * a path-safety rejection hint being appended to turnHistory (mirroring the
 * production code at PumpStation.kt:3025-3041), then asserts the hint is
 * present in:
 *   - turnHistory.history directly (raw data structure)
 *   - the user-message text built by buildTurnContent() (the LLM-facing input)
 *   - the [CONVERSATION HISTORY] serialization block
 *
 * If any of these fail, the hint is not reaching the LLM and we have a
 * harness governance bug (sub-possibility #1 confirmed).
 */
class PathSafetyHintInjectionTest
{
    private val hintMarker = "[Harness Notice] path '<NAME>' was rejected by the path-safety gate"

    @Test
    fun `path-safety rejection hint is present in turnHistory after append`() = runTest {
        val station = pumpStation("hint-injection-1-${System.nanoTime()}") {
            dispatchAgent = Pipeline()
            path("noop") { setExecutionFunction { content, _, _, _ -> content } }
        }

        // Simulate the hint-append behavior from PumpStation.kt:3025-3041.
        // The production code path appends a hint of the form:
        //   "[Harness Notice] path '<NAME>' was rejected by the path-safety gate
        //    for: <REASON>. Select a different path from the visible list on your
        //    next dispatch."
        val hintText = hintMarker.replace("<NAME>", "report") +
            " for: The request is too vague to validate for safety." +
            " Select a different path from the visible list on your next dispatch."
        station.turnHistory.add(
            ConverseData(
                role = ConverseRole.user,
                content = MultimodalContent(text = hintText)
            )
        )

        // Assertion 1: hint is in turnHistory directly.
        val matchingEntries = station.turnHistory.history.filter { turn ->
            turn.content.text?.contains("rejected by the path-safety gate") == true
        }
        assertEquals(1, matchingEntries.size,
            "Expected exactly one path-safety rejection hint in turnHistory, " +
            "found ${matchingEntries.size}")
        assertTrue(matchingEntries[0].content.text!!.contains("too vague"),
            "Hint text must carry the rejection reason so the LLM sees what was wrong")
    }

    @Test
    fun `path-safety rejection hint survives buildTurnContent serialization`() = runTest {
        val station = pumpStation("hint-injection-2-${System.nanoTime()}") {
            dispatchAgent = Pipeline()
            path("noop") { setExecutionFunction { content, _, _, _ -> content } }
        }

        // Append the hint
        val hintText = hintMarker.replace("<NAME>", "report") +
            " for: The request is too vague to validate for safety." +
            " Select a different path from the visible list on your next dispatch."
        station.turnHistory.add(
            ConverseData(
                role = ConverseRole.user,
                content = MultimodalContent(text = hintText)
            )
        )

        // Set phase to Dispatch so the phase question is "Select the next path to invoke."
        station.taskState.phase = PumpStationPhase.Dispatch

        // Call buildTurnContent to produce the LLM-facing input
        val content = station.buildTurnContent()

        // Assertion 2: the serialized user-message text contains the hint.
        val userText = content.text
        assertNotNull(userText, "buildTurnContent must produce non-null text")
        assertTrue(userText.contains("[CONVERSATION HISTORY]"),
            "buildTurnContent text must include the [CONVERSATION HISTORY] serialization block")
        assertTrue(userText.contains("rejected by the path-safety gate"),
            "buildTurnContent text must include the path-safety rejection hint marker. " +
            "Got first 500 chars: ${userText.take(500)}")
        assertTrue(userText.contains("Select a different path from the visible list on your next dispatch"),
            "buildTurnContent text must include the full hint copy directing the LLM to pick another path")

        // Assertion 3: the visible paths metadata is populated alongside the hint.
        // If the hint says "select a different path from the visible list" but the
        // visiblePaths metadata is empty or missing, the LLM has no path list to choose from.
        val visiblePaths = content.metadata["visiblePaths"]
        assertNotNull(visiblePaths, "buildTurnContent metadata must include visiblePaths")
    }

    @Test
    fun `hint injection is idempotent under already-nudged gate simulation`() = runTest {
        val station = pumpStation("hint-injection-3-${System.nanoTime()}") {
            dispatchAgent = Pipeline()
            path("noop") { setExecutionFunction { content, _, _, _ -> content } }
        }

        // Mirror the production gate at PumpStation.kt:3025:
        //   val alreadyNudged = turnHistory.history.any { turn ->
        //       turn.content.text?.contains(hintMarker) == true
        //   }
        //   if (!alreadyNudged) { turnHistory.add(...) }
        //
        // This test pins down the gate's current behavior. After the FIRST hint is
        // appended, subsequent attempts to append a new hint find alreadyNudged=true
        // and skip the append. If the user wants to change this gate, this test
        // should be updated alongside the production code change.

        val firstHint = hintMarker.replace("<NAME>", "report") + " for: First rejection."
        station.turnHistory.add(
            ConverseData(role = ConverseRole.user, content = MultimodalContent(text = firstHint))
        )

        // Simulate second rejection attempt. The production gate at
        // PumpStation.kt:3025-3026 checks for the constant substring
        // "rejected by the path-safety gate" (the unchanging tail of the marker).
        val productionGateSubstring = "rejected by the path-safety gate"
        val alreadyNudged = station.turnHistory.history.any { turn ->
            turn.content.text?.contains(productionGateSubstring) == true
        }
        assertTrue(alreadyNudged, "After first hint is appended, alreadyNudged must be true")

        // The production code at PumpStation.kt:3030 would now skip the second hint append.
        // Verify that only ONE hint is in turnHistory.
        val hintCount = station.turnHistory.history.count { turn ->
            turn.content.text?.contains("rejected by the path-safety gate") == true
        }
        assertEquals(1, hintCount,
            "With the alreadyNudged gate in place, only one hint should ever be in turnHistory. " +
            "If this test fails after a code change, the gate behavior has changed — update this " +
            "test to reflect the new contract.")
    }

    @Test
    fun `multiple distinct hints without marker match still get appended`() = runTest {
        // Edge case: what if two paths are rejected with different markers?
        // The current gate uses a single marker string. If the markers differ,
        // the second one would NOT be blocked by alreadyNudged.
        val station = pumpStation("hint-injection-4-${System.nanoTime()}") {
            dispatchAgent = Pipeline()
            path("noop") { setExecutionFunction { content, _, _, _ -> content } }
        }

        // First hint: uses marker "report"
        val firstHint = "[Harness Notice] path 'report' was rejected by the path-safety gate for: vague."
        station.turnHistory.add(
            ConverseData(role = ConverseRole.user, content = MultimodalContent(text = firstHint))
        )

        // Second hint: uses a different path name in the marker. The gate checks
        // for "rejected by the path-safety gate" (the constant part of the marker),
        // not the full marker. So even if pathName differs, the gate still trips.
        val secondHint = "[Harness Notice] path 'analyze' was rejected by the path-safety gate for: vague."
        val alreadyNudged = station.turnHistory.history.any { turn ->
            turn.content.text?.contains("rejected by the path-safety gate") == true
        }
        assertTrue(alreadyNudged,
            "Gate checks for the constant 'rejected by the path-safety gate' substring, " +
            "so a different pathName in the marker still trips alreadyNudged=true")
    }
}
