package com.TTT.Pipeline

import com.TTT.Context.MetadataBank
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class PumpStationMetaPageKeysTests
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

    private fun station(): PumpStation = PumpStation()

    @Test
    fun testPullMergesFromBankIntoPumpStationMetadata()
    {
        val ps = station()
        MetadataBank.setMeta("p1", mapOf<Any, Any>("k1" to "v1"))
        MetadataBank.setMeta("p2", mapOf<Any, Any>("k2" to 42))

        ps.setMetaPageKeys("p1, p2")
        ps.pullMetaPageKeysIntoPumpStationMetadata()

        assertEquals("v1", ps.metadata["k1"])
        assertEquals(42, ps.metadata["k2"])
    }

    @Test
    fun testPullIsNoOpWhenNoKeysSet()
    {
        val ps = station().apply { metadata["preset"] = "kept" }
        ps.setMetaPageKeys("")
        ps.pullMetaPageKeysIntoPumpStationMetadata()
        assertEquals("kept", ps.metadata["preset"])
    }

    @Test
    fun testSetMetaPageKeysReturnsSelfForChaining()
    {
        val ps = station()
        val returned = ps.setMetaPageKeys("a, b")
        assertSame(ps, returned)
    }
}
