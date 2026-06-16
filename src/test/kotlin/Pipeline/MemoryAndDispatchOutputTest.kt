package com.TTT.Pipeline

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class MemoryAndDispatchOutputTest
{
    @Test
    fun testMemorySnapshotCarriesAgentState()
    {
        val snap = MemorySnapshot(
            lorebookKeysSnapshot = mapOf("foo" to "bar"),
            summarySnapshot = "compressed summary",
            snapshotAt = 42
        )
        assertEquals("compressed summary", snap.summarySnapshot)
        assertEquals(42, snap.snapshotAt)
    }

    @Test
    fun testDispatchOutputWithParseError()
    {
        val out = DispatchOutput(pathRequest = null, repairAttempts = 2, parseError = "expected { got garbage")
        assertNull(out.pathRequest)
        assertEquals(2, out.repairAttempts)
        assertNotNull(out.parseError)
    }
}
