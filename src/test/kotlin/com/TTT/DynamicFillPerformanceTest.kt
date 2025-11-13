package com.TTT

import com.TTT.Pipe.TruncationSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DynamicFillPerformanceTest
{
    @Test
    fun testDynamicFillEfficiency()
    {
        val pipe = DynamicFillTestPipe()
        pipe.addContextWindow("critical", createContextWindowWithWords(900))
        pipe.addContextWindow("supporting", createContextWindowWithWords(720))
        pipe.addContextWindow("background", createContextWindowWithWords(580))

        val initialAllocations = mapOf(
            "critical" to 60,
            "supporting" to 40,
            "background" to 30
        )

        val simulatedUsage = mapOf(
            "critical" to 35,
            "supporting" to 30,
            "background" to 20
        )

        val totalBudget = 150
        val optimized = pipe.invokeRedistributeBudgetDynamically(
            initialAllocations,
            simulatedUsage,
            totalBudget,
            TruncationSettings()
        )

        assertEquals(totalBudget, optimized.values.sum())
        assertTrue(optimized["critical"]!! > simulatedUsage["critical"]!!)
        assertTrue(optimized["critical"]!! >= initialAllocations["critical"]!!)
    }
}
