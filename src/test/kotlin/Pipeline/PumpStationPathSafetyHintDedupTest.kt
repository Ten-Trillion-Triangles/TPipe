package com.TTT.Pipeline

import com.TTT.Pipe.MultimodalContent
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * Repeated [Path Safety] rejections for the same pathName must dedup so the
 * harness doesn't append one hint per turn while the dispatch LLM is stuck.
 * After 3 consecutive rejections of "p1", turnHistory contains at most ONE
 * [Path Safety] hint for that pathName.
 */
class PumpStationPathSafetyHintDedupTest
{
    @Test
    fun pathSafetyHintDoesNotDuplicateForSamePathName()
    {
        runBlocking {
            val station = PumpStation()
                .setPathSafetyFunction { _, _, _ -> false }
            val path = PathObject().apply {
                pathName = "p1"
                pathDescription = "p1"
                riskLevel = PathRiskLevel.Medium
                setExecutionFunction { content, _, _, _ ->
                    MultimodalContent(text = "p1", context = content.context)
                }
            }
            station.addPath(path)

            repeat(3) { iteration ->
                station.invokePathInternal(path, MultimodalContent(text = "call #$iteration"))
            }

            val pathSafetyHints = station.turnHistory.history.mapNotNull { it.content.text }
                .filter { it.contains("[Path Safety]") && it.contains("'p1'") }
            assertEquals(
                1,
                pathSafetyHints.size,
                "Defect (F3 clone): repeated rejections must dedup the [Path Safety] hint per pathName."
            )
        }
    }
}