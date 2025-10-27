package com.TTT.Util

import com.TTT.P2P.AgentRequest
import kotlin.test.Test
import kotlin.test.assertTrue

class SchemaGeneratorTest {
    @Test
    fun `examplePromptFor adds enum legend for AgentRequest`() {
        val output = examplePromptFor(AgentRequest.serializer())
        assertTrue("Enum Legend" in output, "Expected enum legend section in output\n$output")
        assertTrue("promptSchema" in output, "Expected promptSchema enum entry in output\n$output")
        println("examplePromptFor(AgentRequest) output:\n\n$output")
    }
}
