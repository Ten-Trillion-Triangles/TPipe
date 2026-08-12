package com.TTT.Context

import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class MetadataBankTests
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
    fun testSetMetaThenGetMetaReturnsSameMap()
    {
        val map = mapOf<Any, Any>("alpha" to 1, "beta" to "two")
        MetadataBank.setMeta("test-key", map)

        val retrieved = MetadataBank.getMeta("test-key")
        assertNotNull(retrieved)
        assertEquals(1, retrieved["alpha"])
        assertEquals("two", retrieved["beta"])
    }

    @Test
    fun testSuspendSetAndGetThroughRunBlockingMatchesBlockingShape()
    {
        kotlinx.coroutines.runBlocking {
            val v = mapOf<Any, Any>("k" to 42)
            MetadataBank.setMetaSuspend("sx", v)
            val out = MetadataBank.getMetaSuspend("sx")
            assertEquals(42, out?.get("k"))
        }
    }

    @Test
    fun testEmplaceOnMissingKeyCreatesPage()
    {
        MetadataBank.emplace("emerge", mapOf<Any, Any>("a" to 1))
        val out = MetadataBank.getMeta("emerge")
        assertEquals(1, out?.get("a"))
    }

    @Test
    fun testEmplaceOnExistingKeyMergesAndPreservesOriginals()
    {
        MetadataBank.setMeta("merge-source", mapOf<Any, Any>("a" to 1, "b" to 2))
        MetadataBank.emplace("merge-source", mapOf<Any, Any>("c" to 3, "b" to "overridden"))
        val out = MetadataBank.getMeta("merge-source")
        assertEquals(1, out?.get("a"))
        assertEquals("overridden", out?.get("b"))
        assertEquals(3, out?.get("c"))
    }
}
