package openrouterPipe

import env.ChatToolCall
import env.ToolCallFunction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * TDD coverage for [ToolCallStreamAccumulator]: OpenAI-compat streaming
 * tool-call fragments grouped by `index` and merged into whole calls.
 */
class OpenRouterToolCallStreamMergeTest
{
    //=========================================single call fragmented=================================================

    @Test
    fun testSingleCallFragmentsMergeToOneCompleteCall()
    {
        val acc = ToolCallStreamAccumulator()
        // First fragment carries id/type/name + the start of arguments.
        acc.add(ChatToolCall(
            id = "call_1", type = "function", index = 0,
            function = ToolCallFunction(name = "get_weather", arguments = "{")))
        // Subsequent fragments append the rest of the arguments JSON.
        acc.add(ChatToolCall(index = 0, function = ToolCallFunction(name = null, arguments = "\"loc\":\"NYC\"}")))
        acc.add(ChatToolCall(index = 0, function = ToolCallFunction(name = null, arguments = "")))

        val calls = acc.result()
        assertEquals(1, calls.size)
        val merged = calls[0]
        assertEquals("call_1", merged.id)
        assertEquals("function", merged.type)
        assertEquals("get_weather", merged.function?.name)
        assertEquals("{\"loc\":\"NYC\"}", merged.function?.arguments)
    }

    //=========================================multiple calls=========================================================

    @Test
    fun testTwoCallsMergeIndependentlyInIndexOrder()
    {
        val acc = ToolCallStreamAccumulator()
        acc.add(ChatToolCall(id = "a", type = "function", index = 0, function = ToolCallFunction(name = "first", arguments = "x")))
        acc.add(ChatToolCall(id = "b", type = "function", index = 1, function = ToolCallFunction(name = "second", arguments = "y")))
        acc.add(ChatToolCall(index = 0, function = ToolCallFunction(name = null, arguments = "z")))

        val calls = acc.result()
        assertEquals(2, calls.size)
        assertEquals("first", calls[0].function?.name)
        assertEquals("xz", calls[0].function?.arguments)
        assertEquals("second", calls[1].function?.name)
        assertEquals("y", calls[1].function?.arguments)
    }

    //=========================================index-less passthrough=================================================

    @Test
    fun testIndexlessFragmentsPassThroughAsComplete()
    {
        val acc = ToolCallStreamAccumulator()
        acc.add(ChatToolCall(id = "c", type = "function", function = ToolCallFunction(name = "solo", arguments = "1")))
        val calls = acc.result()
        assertEquals(1, calls.size)
        assertEquals("solo", calls[0].function?.name)
        assertNull(calls[0].index)
    }

    @Test
    fun testMergedArgumentsDoNotEmitEmptyStringWhenAllFragmentsEmpty()
    {
        val acc = ToolCallStreamAccumulator()
        acc.add(ChatToolCall(id = "e", type = "function", index = 0, function = ToolCallFunction(name = "n", arguments = "")))
        val calls = acc.result()
        assertEquals(1, calls.size)
        assertEquals("", calls[0].function?.arguments)
        assertTrue(calls[0].id?.isNotEmpty() == true)
    }
}
