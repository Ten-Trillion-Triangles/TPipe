package com.TTT.Pipeline

import com.TTT.Pipe.MultimodalContent
import com.TTT.Pipe.Pipe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Regression test for Defect 16: malformed path-safety JSON falls through to
 * the legacy flag fallback and approves Medium/High risk paths.
 *
 * Contract: when the JSON contract is enabled and the path-safety agent returns
 * malformed / unparsable text, the fallback is risk-aware:
 * - Low risk approves.
 * - Medium risk denies.
 * - High risk denies.
 *
 * This test drives checkPathSafety directly so it isolates the fallback policy
 * from invokePath's surrounding event / loop-guard behavior.
 */
class PumpStationPathSafetyFallbackTest
{
    @Test
    fun malformedPathSafetyJsonUsesRiskAwareFallback()
    {
        runBlocking {
            val station = PumpStation().setPathSafetyAgent(pipelineReturning("this is not json"))

            val low = path("low", PathRiskLevel.Low)
            val medium = path("medium", PathRiskLevel.Medium)
            val high = path("high", PathRiskLevel.High)

            assertTrue(
                station.checkPathSafety(low, MultimodalContent(text = "low input")),
                "Low risk paths should approve when path-safety JSON cannot be parsed."
            )
            assertFalse(
                station.checkPathSafety(medium, MultimodalContent(text = "medium input")),
                "Defect 16: Medium risk paths must deny when path-safety JSON cannot be parsed."
            )
            assertFalse(
                station.checkPathSafety(high, MultimodalContent(text = "high input")),
                "Defect 16: High risk paths must deny when path-safety JSON cannot be parsed."
            )
        }
    }

    @Test
    fun parsedPathSafetyVerdictStillWinsOverFallback()
    {
        runBlocking {
            val station = PumpStation().setPathSafetyAgent(
                pipelineReturning("""{"safe": true, "reason": "explicit approve"}""")
            )
            val high = path("high", PathRiskLevel.High)

            assertTrue(
                station.checkPathSafety(high, MultimodalContent(text = "high input")),
                "Parsed JSON verdicts must win over the risk-aware fallback."
            )
        }
    }

    private fun path(name: String, risk: PathRiskLevel): PathObject = PathObject().apply {
        pathName = name
        pathDescription = "$name path"
        riskLevel = risk
        setExecutionFunction { content, _, _, _ -> MultimodalContent(text = "$name result", context = content.context) }
    }

    private fun pipelineReturning(response: String): Pipeline
    {
        val pipe = object : Pipe()
        {
            init { pipeName = "path-safety-fallback-scripted" }
            override suspend fun generateText(promptInjector: String): String = response
            override fun truncateModuleContext(): Pipe = this
        }
        return Pipeline().apply { add(pipe) }
    }
}
