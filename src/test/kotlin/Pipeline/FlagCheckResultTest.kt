package com.TTT.Pipeline

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FlagCheckResultTest
{
    @Test
    fun testDefaultFlagsAreAllFalse()
    {
        val r = FlagCheckResult()
        assertFalse(r.shouldHalt)
        assertFalse(r.shouldPass)
        assertFalse(r.shouldInterrupt)
        assertEquals(null, r.haltReason)
    }

    @Test
    fun testHaltFlag()
    {
        val r = FlagCheckResult(shouldHalt = true, haltReason = "test reason")
        assertTrue(r.shouldHalt)
        assertEquals("test reason", r.haltReason)
    }
}
