package com.TTT.Pipeline

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BuildContentTest
{
    @Test
    fun testBuildTurnContentIncludesHistoryAndContext()
    {
        val station = PumpStation()
        val content = station.buildTurnContent()
        assertNotNull(content.context)
        assertEquals(station.turnHistory, content.context.converseHistory)
        assertEquals(station.contextWindow.loreBookKeys, content.context.loreBookKeys)
    }

    @Test
    fun testBuildGoalContentIncludesRawTurnHistory()
    {
        val station = PumpStation()
        val content = station.buildGoalContent()
        // rawTurnHistory should be referenced in metadata
        assertTrue(content.metadata.containsKey("taskState"))
    }
}
