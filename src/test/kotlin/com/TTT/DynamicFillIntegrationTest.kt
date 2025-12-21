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
        val truncationSettings = TruncationSettings(multiplyWindowSizeBy = 0)

        val priorityAlloc = pipe.invokeCalculatePriorityFill(totalBudget, pageKeys, truncationSettings)
        val dynamicAlloc = pipe.invokeCalculateDynamicFill(totalBudget, pageKeys, truncationSettings)

        // CRITICAL: Dynamic allocation must not exceed budget
        assertTrue(dynamicAlloc.values.sum() <= totalBudget, 
                  "Dynamic allocation (${dynamicAlloc.values.sum()}) exceeds budget ($totalBudget)")
        
        assertTrue(priorityAlloc.values.sum() <= totalBudget,
                  "Priority allocation (${priorityAlloc.values.sum()}) exceeds budget ($totalBudget)")

        // All allocations should be non-negative
        pageKeys.forEach { pageKey ->
            assertTrue(dynamicAlloc.getOrDefault(pageKey, 0) >= 0, 
                      "Negative dynamic allocation for $pageKey")
            assertTrue(priorityAlloc.getOrDefault(pageKey, 0) >= 0, 
                      "Negative priority allocation for $pageKey")
        }
    }
}
