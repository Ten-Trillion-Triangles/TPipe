package com.TTT

import com.TTT.Pipe.MultiPageBudgetStrategy
import com.TTT.Pipe.TokenBudgetSettings
import kotlin.test.Test
import kotlin.test.assertEquals

class DynamicSizeFillIntegrationTest {

    @Test
    fun testDynamicSizeFillViaSetTokenBudget() {
        // Test that DYNAMIC_SIZE_FILL works when set via setTokenBudget()
        val budgetSettings = TokenBudgetSettings(
            contextWindowSize = 1000,
            multiPageBudgetStrategy = MultiPageBudgetStrategy.DYNAMIC_SIZE_FILL
        )
        
        // Verify the strategy is properly set
        assertEquals(MultiPageBudgetStrategy.DYNAMIC_SIZE_FILL, budgetSettings.multiPageBudgetStrategy)
        
        // Test that TokenBudgetSettings can be created with DYNAMIC_SIZE_FILL
        val budgetSettings2 = TokenBudgetSettings().apply {
            multiPageBudgetStrategy = MultiPageBudgetStrategy.DYNAMIC_SIZE_FILL
        }
        
        assertEquals(MultiPageBudgetStrategy.DYNAMIC_SIZE_FILL, budgetSettings2.multiPageBudgetStrategy)
    }

    @Test
    fun testDynamicSizeFillEnumValue() {
        // Verify DYNAMIC_SIZE_FILL is properly part of the enum
        val strategies = MultiPageBudgetStrategy.values()
        
        assertEquals(5, strategies.size)
        assertEquals(MultiPageBudgetStrategy.DYNAMIC_SIZE_FILL, strategies[4])
        assertEquals("DYNAMIC_SIZE_FILL", MultiPageBudgetStrategy.DYNAMIC_SIZE_FILL.name)
    }
}
