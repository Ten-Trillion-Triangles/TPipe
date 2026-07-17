package com.TTT.Pipeline

import com.TTT.Pipe.MultimodalContent
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Regression test for Defect 19: maxConsecutiveSamePath loop guard logged a
 * LoopGuardTripped event but the harness kept running. The plan mandates the
 * guard now halts the harness with PumpStationExitReason.LoopGuardTripped.
 */
class PumpStationLoopGuardHaltTest
{
    @Test
    fun loopGuardHarnessExitsWithLoopGuardTrippedReason()
    {
        runBlocking {
            val station = PumpStation()
                .setMaxConsecutiveSamePath(2)
                .setPathSafetyFunction { _, _, _ -> true }
            val path = PathObject().apply {
                pathName = "loop"
                pathDescription = "loop path"
                setExecutionFunction { content, _, _, _ ->
                    MultimodalContent(text = "loop result", context = content.context)
                }
            }
            station.addPath(path)

            station.invokePathInternal(path, MultimodalContent(text = "call 1"))
            station.invokePathInternal(path, MultimodalContent(text = "call 2"))

            val state = station.getTaskState()
            assertEquals(
                PumpStationExitReason.LoopGuardTripped,
                state.exitReason,
                "Defect 19: harness must exit with PumpStationExitReason.LoopGuardTripped when the guard trips."
            )
            assertTrue(
                state.lastError == PumpStationError.LoopGuardTriggered,
                "Defect 19: taskState.lastError must be LoopGuardTriggered."
            )
            assertTrue(
                state.latestContent?.terminatePipeline == true,
                "Defect 19: latestContent.terminatePipeline must be true so the harness loop actually halts."
            )
        }
    }
}