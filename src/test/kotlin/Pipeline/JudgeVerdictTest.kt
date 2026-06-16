package com.TTT.Pipeline

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JudgeVerdictTest
{
    @Test
    fun testEmptyVerdictDefaultsToNotComplete()
    {
        val verdict = JudgeVerdict.empty()
        assertFalse(verdict.isComplete)
        assertFalse(verdict.shouldTerminate)
        assertFalse(verdict.shouldHalt)
        assertEquals(null, verdict.reason)
    }

    @Test
    fun testCompleteVerdict()
    {
        val verdict = JudgeVerdict(isComplete = true, shouldTerminate = false, shouldHalt = false)
        assertTrue(verdict.isComplete)
    }
}
