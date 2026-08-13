package com.TTT.Pipe

import com.TTT.Context.MetadataBank
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals

class MultimodalContentMetaPageKeysTests
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

    @Test
    fun testPullMergesFromBankIntoMetadata()
    {
        val mc = MultimodalContent(text = "hello")
        MetadataBank.setMeta("alpha", mapOf<Any, Any>("k1" to "v1"))
        MetadataBank.setMeta("beta", mapOf<Any, Any>("k2" to "v2"))

        mc.setMetaPageKeys("alpha, beta")
        mc.pullMetaPageKeysIntoMetaData()

        assertEquals("v1", mc.metadata["k1"])
        assertEquals("v2", mc.metadata["k2"])
    }

    @Test
    fun testPullSkipsMissingKeys()
    {
        val mc = MultimodalContent(text = "hello")
        MetadataBank.setMeta("real", mapOf<Any, Any>("x" to 7))

        mc.setMetaPageKeys("missing, real")
        mc.pullMetaPageKeysIntoMetaData()

        assertEquals(7, mc.metadata["x"])
    }

    @Test
    fun testPullIsNoOpWhenNoKeysSet()
    {
        val mc = MultimodalContent(text = "hello").apply {
            metadata["preexisting"] = "stays"
        }
        mc.setMetaPageKeys("")
        mc.pullMetaPageKeysIntoMetaData()

        assertEquals("stays", mc.metadata["preexisting"])
        assertEquals(1, mc.metadata.size)
    }

    @Test
    fun testLastWriteWinsOnCollision()
    {
        val mc = MultimodalContent(text = "hello")
        MetadataBank.setMeta("pageA", mapOf<Any, Any>("k" to "fromA"))
        MetadataBank.setMeta("pageB", mapOf<Any, Any>("k" to "fromB"))

        mc.setMetaPageKeys("pageA, pageB")
        mc.pullMetaPageKeysIntoMetaData()

        assertEquals("fromB", mc.metadata["k"])
    }
}
