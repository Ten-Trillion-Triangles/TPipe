package com.TTT

import com.TTT.Pipe.TruncationSettings
import kotlin.test.Test
import kotlin.test.assertTrue

class DynamicFillIntegrationTest
{
    @Test
    fun testDynamicFillWithRealContent()
    {
        val pipe = DynamicFillTestPipe()
        pipe.addContextWindow("priority", createContextWindowWithWords(220))
        pipe.addContextWindow("secondary", createContextWindowWithWords(180))
        pipe.addContextWindow("tertiary", createContextWindowWithWords(90))

        val totalBudget = 140
        val pageKeys = listOf("priority", "secondary", "tertiary")
        val truncationSettings = TruncationSettings()

        val priorityAlloc = pipe.invokeCalculatePriorityFill(totalBudget, pageKeys, truncationSettings)
        val priorityAllocFull = pageKeys.associateWith { priorityAlloc[it] ?: 0 }
        val dynamicAlloc = pipe.invokeCalculateDynamicFill(totalBudget, pageKeys, truncationSettings)
        val simulatedUsage = pipe.invokeSimulateTruncationUsage(priorityAllocFull, truncationSettings)

        assertTrue(dynamicAlloc.values.sum() <= totalBudget)
        assertTrue(dynamicAlloc.values.sum() >= simulatedUsage.values.sum())

        pageKeys.forEach { pageKey ->
            val dynamicValue = dynamicAlloc.getOrDefault(pageKey, 0)
            val simulatedValue = simulatedUsage.getOrDefault(pageKey, 0)
            assertTrue(dynamicValue >= simulatedValue)
        }

        assertTrue(pageKeys.any { pageKey ->
            val dynamicValue = dynamicAlloc.getOrDefault(pageKey, 0)
            val simulatedValue = simulatedUsage.getOrDefault(pageKey, 0)
            dynamicValue > simulatedValue
        })
    }
}
