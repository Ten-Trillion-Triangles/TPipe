package com.TTT

import com.TTT.Pipe.TruncationSettings
import kotlin.test.Test

class DynamicFillDebugTest
{
    @Test
    fun debugDynamicFillValues()
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

        println("Total Budget: $totalBudget")
        println("Priority Allocation: $priorityAllocFull")
        println("Dynamic Allocation: $dynamicAlloc")
        println("Simulated Usage: $simulatedUsage")
        println("Dynamic Sum: ${dynamicAlloc.values.sum()}")
        println("Simulated Sum: ${simulatedUsage.values.sum()}")
        
        pageKeys.forEach { pageKey ->
            val dynamicValue = dynamicAlloc.getOrDefault(pageKey, 0)
            val simulatedValue = simulatedUsage.getOrDefault(pageKey, 0)
            println("$pageKey: dynamic=$dynamicValue, simulated=$simulatedValue, dynamic>simulated=${dynamicValue > simulatedValue}")
        }
        
        val hasAnyGreater = pageKeys.any { pageKey ->
            val dynamicValue = dynamicAlloc.getOrDefault(pageKey, 0)
            val simulatedValue = simulatedUsage.getOrDefault(pageKey, 0)
            dynamicValue > simulatedValue
        }
        println("Any dynamic > simulated: $hasAnyGreater")
    }
}
