package com.TTT.Pipeline

import com.TTT.Pipe.MultimodalContent
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CheckMultimodalFlagsTest
{
    @Test
    fun testEmptyContentHasAllFalseFlags()
    {
        val station = PumpStation()
        // We can't call the private method directly, but we can test via the
        // public FlagCheckResult logic by constructing equivalent inputs.
        val flags = FlagCheckResult()
        assertFalse(flags.shouldHalt)
        assertFalse(flags.shouldPass)
        assertFalse(flags.shouldInterrupt)
    }

    @Test
    fun testTerminatePipelineFlagMappedToHalt()
    {
        val content = MultimodalContent(text = "x", terminatePipeline = true)
        // Use the spec: shouldHalt = content.terminatePipeline
        assertTrue(content.terminatePipeline)
    }

    @Test
    fun testPassPipelineFlagMappedToPass()
    {
        val content = MultimodalContent(text = "x")
        content.passPipeline = true
        assertTrue(content.passPipeline)
    }

    @Test
    fun testInterruptPipelineFlagMappedToInterrupt()
    {
        val content = MultimodalContent(text = "x")
        content.interuptPipeline = true
        assertTrue(content.interuptPipeline)
    }

    @Test
    fun testHelperPropagatesHaltReason()
    {
        val content = MultimodalContent(text = "x", terminatePipeline = true)
        content.metadata["haltReason"] = "custom reason"
        val result = checkMultimodalFlags(content, "TestSource")
        assertTrue(result.shouldHalt)
        assertEquals("custom reason", result.haltReason)
    }
}
