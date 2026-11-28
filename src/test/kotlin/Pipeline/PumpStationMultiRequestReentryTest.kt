package com.TTT.Pipeline

import com.TTT.Context.ConverseRole
import com.TTT.Context.LoreBook
import com.TTT.Pipe.MultimodalContent
import com.TTT.Pipe.Pipe
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * TDD tests for multi-request [PumpStation] re-entry on a retained [PumpStationSession].
 */
class PumpStationMultiRequestReentryTest
{
    private class SequenceScriptedPipe(
        private val responses: MutableList<String>,
        val recordedInputs: MutableList<String> = mutableListOf()
    ) : Pipe()
    {
        override suspend fun generateText(promptInjector: String): String
        {
            recordedInputs.add(promptInjector)
            return if(responses.isNotEmpty()) responses.removeAt(0) else "{}"
        }

        override fun truncateModuleContext(): Pipe = this
    }

    @Test
    fun `retained PumpStationSession executes follow-up request preserving history lorebook stash and prefix`() = runBlocking {
        val dispatchResponses = mutableListOf(
            // Request 1, turn 0: parallel MultiPath dispatch (gatherA + gatherB)
            """
            {
              "paths": [
                {"pathName": "gatherA", "pathSchema": "{\"val\":\"a1\"}", "pathSelectionRationale": "read A"},
                {"pathName": "gatherB", "pathSchema": "{\"val\":\"b1\"}", "pathSelectionRationale": "read B"}
              ],
              "batchRationale": "Parallel reads on request 1"
            }
            """.trimIndent(),
            // Request 1, turn 1: respond
            """
            {
              "paths": [
                {"pathName": "respond", "pathSchema": "{\"message\":\"Done first\"}", "pathSelectionRationale": "finish 1"}
              ],
              "batchRationale": "Respond on request 1"
            }
            """.trimIndent(),
            // Request 2, turn 0: parallel MultiPath dispatch again (proves asyncScope & pendingAsyncResults reopened)
            """
            {
              "paths": [
                {"pathName": "gatherA", "pathSchema": "{\"val\":\"a2\"}", "pathSelectionRationale": "read A2"},
                {"pathName": "gatherB", "pathSchema": "{\"val\":\"b2\"}", "pathSelectionRationale": "read B2"}
              ],
              "batchRationale": "Parallel reads on request 2"
            }
            """.trimIndent(),
            // Request 2, turn 1: respond
            """
            {
              "paths": [
                {"pathName": "respond", "pathSchema": "{\"message\":\"Done second\"}", "pathSelectionRationale": "finish 2"}
              ],
              "batchRationale": "Respond on request 2"
            }
            """.trimIndent()
        )
        val dispatchPipe = SequenceScriptedPipe(dispatchResponses)
        val judgePipe = SequenceScriptedPipe(
            mutableListOf(
                """{"isComplete": true, "shouldTerminate": false, "reason": "r1 complete"}""",
                """{"isComplete": true, "shouldTerminate": false, "reason": "r2 complete"}"""
            )
        )

        var asyncActiveDuringRequest2 = false
        lateinit var stationRef: PumpStation
        val station = pumpStation("reentry-test-${System.nanoTime()}") {
            pathExecutionShape = PathExecutionShape.MultiPath
            waitPolicy {
                joinAllPathsOnMultiDispatch = true
            }

            judgeRunMode = PumpStationJudgeRunMode.FlagTriggered
            skipJudgeOnFirstTurn = true
            personality = "Calm generalist."
            systemTask = "Execute paths and answer clearly."
            entryUserPrompt = "Initial session anchor"
            maxHarnessTurns = 10

            dispatchAgent = Pipeline().apply { add(dispatchPipe) }
            judgeAgent = Pipeline().apply { add(judgePipe) }

            path("gatherA") {
                description = "Gather A"
                runsInBackground = true
                setExecutionFunction { content, st, _, _ ->
                    if(content.text.contains("a2"))
                    {
                        asyncActiveDuringRequest2 = st.isAsyncScopeActive()
                    }
                    MultimodalContent(text = "A:${content.text}")
                }
            }

            path("gatherB") {
                description = "Gather B"
                runsInBackground = true
                setExecutionFunction { content, _, _, _ ->
                    MultimodalContent(text = "B:${content.text}")
                }
            }

            path("respond") {
                description = "Respond to user"
                setExecutionFunction { content, _, _, _ ->
                    stationRef.requestJudgeNextTurn()
                    MultimodalContent(text = content.text ?: "ok")
                }
            }
        }
        stationRef = station

        // Seed lorebook during request 1 lifecycle
        station.contextWindow.loreBookKeys["pinned_fact"] = LoreBook().apply {
            this.key = "pinned_fact"
            this.value = "Project uses Kotlin 2.1"
        }

        val session = station.openSession()
        val observedUpdates = mutableListOf<PumpStationSessionUpdate>()
        val collectorJob = launch {
            for(update in session.updates)
            {
                observedUpdates.add(update)
            }
        }

        try
        {
            val firstResult = session.execute(MultimodalContent(text = "First user request"))
            assertTrue((firstResult.text ?: "").contains("Done first"))
            assertEquals(PumpStationExitReason.JudgeComplete, station.getTaskState().exitReason)
            assertNull(station.getTaskState().lastError)

            val historyCountAfterFirst = station.turnHistory.history.size
            assertTrue(historyCountAfterFirst > 0)

            // Execute follow-up request on the SAME station & session
            val secondResult = session.execute(MultimodalContent(text = "Second user request"))
            assertTrue((secondResult.text ?: "").contains("Done second"))
            assertEquals(PumpStationExitReason.JudgeComplete, station.getTaskState().exitReason)
            assertNull(station.getTaskState().lastError)

            // Verify asyncScope was active during request 2 and history/lorebook survived
            assertTrue(asyncActiveDuringRequest2)
            assertTrue(station.turnHistory.history.size > historyCountAfterFirst)
            assertTrue(
                station.turnHistory.history.any {
                    it.role == ConverseRole.user && (it.content.text ?: "").contains("Second user request")
                }
            )
            assertEquals("Project uses Kotlin 2.1", station.contextWindow.loreBookKeys["pinned_fact"]?.value)

            // Verify entryUserPrompt stayed stable (no system prompt drift)
            assertEquals("Initial session anchor", station.entryUserPrompt)
            assertFalse(observedUpdates.isEmpty())
            assertTrue(observedUpdates.count { it is PumpStationSessionEventUpdate && it.event is HarnessStarted } >= 2)
        }

        finally
        {
            session.close()
            collectorJob.cancel()
        }
    }
}
