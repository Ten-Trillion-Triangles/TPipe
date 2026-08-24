package com.TTT.Pipe

import com.TTT.Context.MetadataBank
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class PipeMetaPageKeysTests
{
    @Before
    fun setup()
    {
        MetadataBank.clear()
    }

    @After
    fun tearDown()
    {
        MetadataBank.clear()
    }

    private fun stubPipe(): Pipe = TestStubPipe()

    @Test
    fun testPullMergesFromBankIntoPipeMetadata()
    {
        val pipe = stubPipe()
        MetadataBank.setMeta("p1", mapOf<Any, Any>("k1" to "fromP1"))
        MetadataBank.setMeta("p2", mapOf<Any, Any>("k2" to "fromP2"))

        pipe.setMetaPageKeys("p1, p2")
        pipe.pullMetaPageKeysIntoPipeMetadata()

        assertEquals("fromP1", pipe.pipeMetadata["k1"])
        assertEquals("fromP2", pipe.pipeMetadata["k2"])
    }

    @Test
    fun testPullSkipsMissingKeys()
    {
        val pipe = stubPipe()
        MetadataBank.setMeta("present", mapOf<Any, Any>("x" to 1))
        pipe.setMetaPageKeys("missing, present, also-missing")
        pipe.pullMetaPageKeysIntoPipeMetadata()
        assertEquals(1, pipe.pipeMetadata["x"])
    }

    @Test
    fun testPullIsNoOpWhenNoKeysSet()
    {
        val pipe = stubPipe()
        pipe.setMetaPageKeys("")
        pipe.pullMetaPageKeysIntoPipeMetadata()
        assertEquals(0, pipe.pipeMetadata.size)
    }

    @Test
    fun testSetMetaPageKeysReturnsSelfForChaining()
    {
        val pipe = stubPipe()
        val returned = pipe.setMetaPageKeys("a, b")
        assertSame(pipe, returned)
    }

    @Test
    fun testHasMetaPageKeysReflectsWhetherPullIsConfigured()
    {
        val pipe = stubPipe()
        assertEquals(false, pipe.hasMetaPageKeys())
        pipe.setMetaPageKeys("alpha, beta")
        assertEquals(true, pipe.hasMetaPageKeys())
        pipe.setMetaPageKeys("")
        assertEquals(false, pipe.hasMetaPageKeys())
    }
}

/**
 * Minimum-viable test double for [Pipe]. Mirrors the pattern used in
 * `ErrorPropagationTest.FailingPipe`. Overrides the three abstract
 * members Pipe forces callers to implement; the rest is no-op stub.
 */
private class TestStubPipe : Pipe()
{
    override suspend fun generateText(promptInjector: String): String = ""

    override suspend fun generateContent(content: MultimodalContent): MultimodalContent
    {
        return content
    }

    override fun truncateModuleContext(): Pipe = this
}
