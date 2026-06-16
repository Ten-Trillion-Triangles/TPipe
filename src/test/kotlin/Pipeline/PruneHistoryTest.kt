package com.TTT.Pipeline

import com.TTT.Context.ConverseData
import com.TTT.Context.ConverseRole
import com.TTT.Pipe.MultimodalContent
import com.TTT.Pipe.Pipe
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PruneHistoryTest
{
    @Test
    fun testPruneRemovesOldestEntriesOverLimit()
    {
        val station = buildTestStation()
        station.setMaxTurnHistorySize(3)
        // Add 5 entries
        repeat(5) { i ->
            station.turnHistory.add(ConverseData(
                role = ConverseRole.assistant,
                content = MultimodalContent(text = "entry $i")
            ))
        }
        runBlocking { station.pruneTurnHistory() }
        // Should be at or below 3 (after summarizing into 1 entry)
        assertTrue(station.turnHistory.history.size <= 3, "Turn history should be pruned to <= 3 entries")
    }

    @Test
    fun testSummarizePoppedEntriesReturnsSingleEntry()
    {
        val station = buildTestStation()
        val popped = listOf(
            ConverseData(role = ConverseRole.assistant, content = MultimodalContent(text = "a")),
            ConverseData(role = ConverseRole.assistant, content = MultimodalContent(text = "b"))
        )
        val summary = station.summarizePoppedEntries(popped)
        assertTrue(summary.content.text.contains("a"))
        assertTrue(summary.content.text.contains("b"))
    }
}
