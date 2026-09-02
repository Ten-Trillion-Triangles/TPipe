package com.TTT.AgentCore.Runtime.AgUi

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AgentCoreAgUiTest
{
    @Test
    fun mapsLatestUserMessageAndKeepsPriorConversationInRequestContext()
    {
        val input = RunAgentInput(
            threadId = "thread-1",
            runId = "run-1",
            messages = listOf(
                RunAgentMessage("user", "Earlier question"),
                RunAgentMessage("assistant", "Earlier answer"),
                RunAgentMessage("user", "Latest question")
            ),
            toolDefinitions = listOf("client-tool")
        )

        val mapped = AgentCoreAgUiInputMapper().map(input)

        assertEquals("thread-1", mapped.sessionId)
        assertEquals("Latest question", mapped.request.prompt.text)
        assertEquals(
            listOf("user: Earlier question", "assistant: Earlier answer"),
            mapped.request.context?.contextElements?.toList()
        )
        assertFalse(mapped.request.pcpRequest != null)
    }

    @Test
    fun emitsCanonicalSseFramesAndStandardRunSequence()
    {
        val input = RunAgentInput("thread-1", "run-1", listOf(RunAgentMessage("user", "Hi")))
        val events = AgentCoreAgUiEventMapper.started(input) +
            AgentCoreAgUiEventMapper.content(input, "Hello") +
            AgentCoreAgUiEventMapper.finished(input)

        assertEquals(
            listOf("RUN_STARTED", "TEXT_MESSAGE_START", "TEXT_MESSAGE_CONTENT", "TEXT_MESSAGE_END", "RUN_FINISHED"),
            events.map { it.type }
        )
        val encoded = AgentCoreAgUiEventEncoder.encodeSse(events[2])
        assertTrue(encoded.startsWith("data: {"))
        assertTrue(encoded.endsWith("}\n\n"))
        assertTrue(encoded.contains("\"delta\":\"Hello\""))

        val start = AgentCoreAgUiEventEncoder.encodeJson(events[1])
        assertTrue(start.contains("\"role\":\"assistant\""))

        val error = AgentCoreAgUiEventEncoder.encodeJson(
            AgentCoreAgUiEventMapper.failed(input, "boom")
        )
        assertTrue(error.contains("\"message\":\"boom\""))
    }
}
