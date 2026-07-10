package com.TTT.Pipeline

import com.TTT.Pipe.MultimodalContent
import com.TTT.Pipe.Pipe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The canonical three-layer pattern for handling a path that admits failure
 * ("I don't have enough information", hallucinated success, etc.) without
 * relying solely on the judge LLM to catch it.
 *
 * Layer 1 — [PumpStation.setPathValidationFunction] DITL hook rejects the
 *   path output before the judge votes; the harness retries until the path
 *   validates or maxRetries is hit.
 * Layer 2 — Path returns [MultimodalContent.terminatePipeline] = true on a
 *   hard failure; the harness exits with
 *   [PumpStationExitReason.TerminateSignal].
 * Layer 3 — Path returns [MultimodalContent.passPipeline] = false for a soft
 *   "no progress" signal; the harness continues to the next dispatch turn.
 *
 * Each test pins one layer against a representative scenario.
 */
class PumpStationPathFailureExitsProperlyTest
{
    /**
     * Layer 1: pathValidationFunction DITL hook rejects path outputs that
     * contain failure phrases before the judge votes. The harness re-runs the
     * path until validation passes or maxRetries is hit.
     */
    @Test
    fun pathValidationFunctionRejectsAdmissionOfFailure()
    {
        runBlocking {
            val station = PumpStation()
                .setJudgeAgent(pipelineReturning("""{"isComplete": false, "shouldTerminate": false}"""))
                .setDispatchAgent(pipelineReturning("""{"pathName": "research", "pathSchema": "{}"}"""))
                .setPathValidationFunction { content, _ ->
                    !content.text.contains("I don't have enough information")
                }
                .setMaxHarnessTurns(4)

            val path = PathObject().apply {
                pathName = "research"
                pathDescription = "research"
                riskLevel = PathRiskLevel.Low
                setExecutionFunction { content, _, _, _ ->
                    MultimodalContent(text = "I don't have enough information to answer.", context = content.context)
                }
            }
            station.addPath(path)

            var validationCalls = 0
            station.setEventObserver { event ->
                if (event is PathValidationCompleted) validationCalls++
            }
            station.executeLocal(MultimodalContent(text = "ask"))
            assertTrue(
                validationCalls >= 1,
                "Defect 14 layer 1: pathValidationFunction must be consulted at least once."
            )
            val state = station.getTaskState()
            assertFalse(
                state.exitReason == PumpStationExitReason.JudgeComplete && state.latestContent?.text?.contains("I don't have enough information") == true,
                "Defect 14 layer 1: harness must NOT exit via JudgeComplete when the path output reads as admission of failure."
            )
        }
    }

    /**
     * Layer 2: a path that returns terminatePipeline=true on failure exits
     * the harness with PumpStationExitReason.TerminateSignal, NOT JudgeComplete.
     */
    @Test
    fun pathReturningTerminatePipelineExitsWithTerminateSignal()
    {
        runBlocking {
            val station = PumpStation()
                .setJudgeAgent(pipelineReturning("""{"isComplete": false, "shouldTerminate": false}"""))
                .setDispatchAgent(pipelineReturning("""{"pathName": "abort", "pathSchema": "{}"}"""))
                .setMaxHarnessTurns(4)

            val path = PathObject().apply {
                pathName = "abort"
                pathDescription = "abort"
                riskLevel = PathRiskLevel.Low
                setExecutionFunction { content, _, _, _ ->
                    val result = MultimodalContent(text = "aborting", context = content.context)
                    result.terminatePipeline = true
                    result
                }
            }
            station.addPath(path)
            station.executeLocal(MultimodalContent(text = "ask"))
            val state = station.getTaskState()
            assertEquals(
                PumpStationExitReason.TerminateSignal,
                state.exitReason,
                "Defect 14 layer 2: terminatePipeline=true must exit via TerminateSignal."
            )
        }
    }

    /**
     * Layer 3: passPipeline=false on the path result continues the harness
     * loop (does NOT exit). This pins that the canonical pattern of
     * "admit failure → continue" stays a continue, not a silent terminate.
     */
    @Test
    fun pathReturningPassPipelineFalseContinuesTheHarnessLoop()
    {
        runBlocking {
            val station = PumpStation()
                .setJudgeAgent(pipelineReturning("""{"isComplete": true, "shouldTerminate": false}"""))
                .setDispatchAgent(pipelineReturning("""{"pathName": "noop", "pathSchema": "{}"}"""))
                .setMaxHarnessTurns(3)

            val path = PathObject().apply {
                pathName = "noop"
                pathDescription = "noop"
                riskLevel = PathRiskLevel.Low
                setExecutionFunction { content, _, _, _ ->
                    val result = MultimodalContent(text = "no progress", context = content.context)
                    result.passPipeline = false
                    result
                }
            }
            station.addPath(path)
            station.executeLocal(MultimodalContent(text = "ask"))
            assertEquals(
                PumpStationExitReason.JudgeComplete,
                station.getTaskState().exitReason,
                "Defect 14 layer 3: passPipeline=false must continue; the harness exits only when the judge votes isComplete."
            )
        }
    }

    private fun pipelineReturning(response: String): Pipeline
    {
        val pipe = object : Pipe()
        {
            init { pipeName = "defect14-scripted" }
            override suspend fun generateText(promptInjector: String): String = response
            override fun truncateModuleContext(): Pipe = this
        }
        return Pipeline().apply { add(pipe) }
    }
}