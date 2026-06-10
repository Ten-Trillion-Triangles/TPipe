package com.TTT.Pipeline

import com.TTT.Pipe.MultimodalContent
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MockAgentTest
{
    @Test
    fun testMockP2PAgentReturnsScriptedContent() = runBlocking {
        val script = listOf(
            MultimodalContent(text = "first"),
            MultimodalContent().apply { text = "second"; passPipeline = true }
        )
        val agent = MockP2PAgent(script = script)
        val first = agent.executeLocal(MultimodalContent(text = "input"))
        val second = agent.executeLocal(MultimodalContent(text = "input"))
        assertEquals("first", first.text)
        assertTrue(second.passPipeline)
        assertEquals(2, agent.callLog.size)
    }
}
