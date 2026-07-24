package com.TTT.Pipeline

import com.TTT.Pipe.MultimodalContent
import org.junit.jupiter.api.Test
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PumpStationPathCaseInsensitiveTest
{
    @Test
    fun `addPath with mixed case is reachable via getPath with any case`() {
        val station = pumpStation("case-insensitive-${System.nanoTime()}") {
            dispatchAgent = Pipeline()
            path("giveUp") {
                risk = PathRiskLevel.Low
                setExecutionFunction { content, _, _, _ -> content }
            }
        }

        assertNotNull(station.getPath("giveUp"), "exact-case lookup must find the path")
        assertNotNull(station.getPath("giveup"), "lowercase lookup must find the path")
        assertNotNull(station.getPath("GIVEUP"), "uppercase lookup must find the path")
        assertNotNull(station.getPath("GiveUp"), "title-case lookup must find the path")
        assertSame(station.getPath("giveUp"), station.getPath("GIVEUP"),
            "all casings must resolve to the same PathObject instance")
    }

    @Test
    fun `getVisiblePathNames preserves original casing`() {
        val station = pumpStation("visible-casing-${System.nanoTime()}") {
            dispatchAgent = Pipeline()
            path("giveUp") {
                risk = PathRiskLevel.Low
                setExecutionFunction { content, _, _, _ -> content }
            }
        }
        assertEquals(listOf("giveUp"), station.getVisiblePathNames(),
            "the LLM-facing visible-paths list must show the original casing")
    }

    @Test
    fun `removePath with any case removes the path`() {
        val station = pumpStation("remove-case-${System.nanoTime()}") {
            dispatchAgent = Pipeline()
            path("giveUp") {
                risk = PathRiskLevel.Low
                setExecutionFunction { content, _, _, _ -> content }
            }
        }
        station.removePath("GIVEUP")
        assertNull(station.getPath("giveUp"),
            "case-insensitive remove must drop the path")
    }

    @Test
    fun `resolvePath returns the same instance for any case`() {
        val station = pumpStation("resolve-case-${System.nanoTime()}") {
            dispatchAgent = Pipeline()
            path("giveUp") {
                risk = PathRiskLevel.Low
                setExecutionFunction { content, _, _, _ -> content }
            }
        }
        val a = station.getPath("giveUp")
        val b = station.getPath("GIVEUP")
        assertSame(a, b)
    }

    @Test
    fun `dispatching a mixed-case path name reaches the registered path`() {
        val station = pumpStation("e2e-case-${System.nanoTime()}") {
            dispatchAgent = Pipeline()
            path("giveUp") {
                description = "Gives up on the task and terminates the harness."
                risk = PathRiskLevel.Low
                setExecutionFunction { content, _, _, _ ->
                    MultimodalContent(text = "GIVEUP: ${content.text}")
                        .also { it.terminatePipeline = true }
                }
            }
        }
        val dispatched = pathRequest("giveUp")
        val resolved = station.getPath(dispatched.pathName)
        assertNotNull(resolved,
            "end-to-end: dispatching 'giveUp' must resolve the path registered as 'giveUp'")
        assertEquals("giveUp", resolved.pathName,
            "the resolved path's pathName preserves the original casing for display")
    }

    private fun pathRequest(name: String): PathRequest =
        PathRequest(pathName = name)
}
