package com.TTT.Pipeline

import com.TTT.Pipe.MultimodalContent
import com.TTT.Pipe.Pipe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertEquals

/**
 * Regression test for Defect 12: the PumpStation DSL silently enables
 * maxConsecutiveSamePath=3 by default.
 *
 * The PumpStation class default is null (guard disabled). The DSL must preserve
 * that default unless the builder author explicitly opts in. Otherwise a station
 * built through the DSL has hidden loop-guard behavior that a direct PumpStation
 * does not have.
 */
class PumpStationDslDefaultTest
{
    @Test
    fun dslDoesNotEnableMaxConsecutiveSamePathByDefault()
    {
        runBlocking {
            val station = pumpStation("dsl-default-loop-guard") {
                dispatchAgent = pipelineReturning("""{"pathName":"same","pathSchema":"{}"}""")
                judgeAgent = pipelineReturning("""{"isComplete":false,"shouldTerminate":false}""")
                maxHarnessTurns = 4
                path("same") {
                    description = "same path"
                    schema = "{}"
                    setExecutionFunction { content, _, _, _ ->
                        MultimodalContent(text = "same result", context = content.context)
                    }
                }
            }
            val loopGuardEvents = mutableListOf<LoopGuardTripped>()
            station.setEventObserver { event ->
                if (event is LoopGuardTripped && event.guard == "maxConsecutiveSamePath") {
                    loopGuardEvents.add(event)
                }
            }

            station.executeLocal(MultimodalContent(text = "keep selecting same"))

            assertFalse(
                loopGuardEvents.isNotEmpty(),
                "Defect 12: DSL default must not enable maxConsecutiveSamePath. " +
                    "Saw hidden loop-guard events: ${loopGuardEvents.map { it.detail }}"
            )
            assertEquals(
                0,
                station.consecutivePathCountInternal,
                "When maxConsecutiveSamePath is null, the guard counter should stay inactive."
            )
        }
    }

    private fun pipelineReturning(response: String): Pipeline
    {
        val pipe = object : Pipe()
        {
            init { pipeName = "dsl-default-scripted" }
            override suspend fun generateText(promptInjector: String): String = response
            override fun truncateModuleContext(): Pipe = this
        }
        return Pipeline().apply { add(pipe) }
    }
}
