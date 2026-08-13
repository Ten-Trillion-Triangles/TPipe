package com.TTT.Context

import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class ContextWindowMetaPageKeysTests
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
    fun testPullMergesFromBankIntoWindowMetaData()
    {
        val win = ContextWindow()
        MetadataBank.setMeta("a", mapOf<Any, Any>("k1" to 1))
        MetadataBank.setMeta("b", mapOf<Any, Any>("k2" to 2))

        win.setMetaPageKeys("a, b")
        win.pullMetaPageKeysIntoWindowMetaData()

        assertEquals(1, win.metaData["k1"])
        assertEquals(2, win.metaData["k2"])
    }

    @Test
    fun testPullIsNoOpWhenNoKeysSet()
    {
        val win = ContextWindow().apply { metaData["preset"] = "kept" }
        win.setMetaPageKeys("")
        win.pullMetaPageKeysIntoWindowMetaData()
        assertEquals("kept", win.metaData["preset"])
    }

    @Test
    fun testSetMetaPageKeysReturnsSelfForChaining()
    {
        val win = ContextWindow()
        val returned = win.setMetaPageKeys("a, b")
        assertSame(win, returned)
    }
}
