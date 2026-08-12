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
}
