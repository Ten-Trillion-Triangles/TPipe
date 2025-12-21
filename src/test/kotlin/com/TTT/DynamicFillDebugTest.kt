package com.TTT

import com.TTT.Pipe.TruncationSettings
import kotlin.test.Test

class DynamicFillDebugTest
{
    @Test
    fun debugDynamicFillWithRealContent()
    {
        val pipe = DynamicFillTestPipe()
        pipe.addContextWindow("priority", createContextWindowWithWords(220))
        pipe.addContextWindow("secondary", createContextWindowWithWords(180))
        pipe.addContextWindow("tertiary", createContextWindowWithWords(90))

        val totalBudget = 140
        val pageKeys = listOf("priority", "secondary", "tertiary")
        val truncationSettings = TruncationSettings()

        val priorityAlloc = pipe.invokeCalculatePriorityFill(totalBudget, pageKeys, truncationSettings)
        val dynamicAlloc = pipe.invokeCalculateDynamicFill(totalBudget, pageKeys, truncationSettings)
        val dynamicSimulatedUsage = pipe.invokeSimulateTruncationUsage(dynamicAlloc, truncationSettings)

        println("=== DYNAMIC FILL DEBUG ===")
        println("Total Budget: $totalBudget")
        println("Priority Allocation: $priorityAlloc (total: ${priorityAlloc.values.sum()})")
        println("Dynamic Allocation: $dynamicAlloc (total: ${dynamicAlloc.values.sum()})")
        println("Dynamic Simulated Usage: $dynamicSimulatedUsage (total: ${dynamicSimulatedUsage.values.sum()})")
        
        println("\nAssertion checks:")
        println("Dynamic total <= Budget: ${dynamicAlloc.values.sum()} <= $totalBudget = ${dynamicAlloc.values.sum() <= totalBudget}")
        println("Dynamic total >= Simulated: ${dynamicAlloc.values.sum()} >= ${dynamicSimulatedUsage.values.sum()} = ${dynamicAlloc.values.sum() >= dynamicSimulatedUsage.values.sum()}")
        
        // This assertion should always pass - allocation should be >= actual usage
        assert(dynamicAlloc.values.sum() >= dynamicSimulatedUsage.values.sum()) {
            "Dynamic allocation (${dynamicAlloc.values.sum()}) should be >= simulated usage (${dynamicSimulatedUsage.values.sum()})"
        }
    }
}
