package bedrockPipe

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class PromptVariablesBuilderTest
{
    @Test
    fun defaultIsNull()
    {
        val pipe = BedrockPipe()
        assertNull(pipe.getPromptVariables())
    }

    @Test
    fun setPersistsAndReturnsThis()
    {
        val pipe = BedrockPipe()
        val vars = mapOf("name" to "world", "topic" to "kotlin")
        val returned = pipe.setPromptVariables(vars)
        assertEquals(pipe, returned)
        assertEquals(vars, pipe.getPromptVariables())
    }

    @Test
    fun additiveMergeAccumulates()
    {
        val pipe = BedrockPipe()
        pipe.setPromptVariables(mapOf("a" to "1"))
        pipe.setPromptVariables(mapOf("b" to "2"))
        assertEquals(mapOf("a" to "1", "b" to "2"), pipe.getPromptVariables())
    }
}
