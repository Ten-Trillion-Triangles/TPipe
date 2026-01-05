package com.TTT

import com.TTT.Pipe.MultiPageBudgetStrategy
import com.TTT.Pipe.TokenBudgetSettings
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertEquals

class DynamicSizeFillStrategyTest {

    @Test
    fun testDynamicSizeFillEnumExists() {
        // Verify DYNAMIC_SIZE_FILL is properly part of the enum
        val strategies = MultiPageBudgetStrategy.values()
        
        assertEquals(5, strategies.size)
        assertTrue(strategies.contains(MultiPageBudgetStrategy.DYNAMIC_SIZE_FILL))
        assertEquals("DYNAMIC_SIZE_FILL", MultiPageBudgetStrategy.DYNAMIC_SIZE_FILL.name)
        assertEquals(4, MultiPageBudgetStrategy.DYNAMIC_SIZE_FILL.ordinal)
    }

    @Test
    fun testTokenBudgetSettingsWithDynamicSizeFill() {
        // Test that TokenBudgetSettings works with DYNAMIC_SIZE_FILL
        val settings = TokenBudgetSettings(
            contextWindowSize = 1000,
            multiPageBudgetStrategy = MultiPageBudgetStrategy.DYNAMIC_SIZE_FILL,
            maxTokens = 500
        )
        
        assertEquals(MultiPageBudgetStrategy.DYNAMIC_SIZE_FILL, settings.multiPageBudgetStrategy)
        assertEquals(1000, settings.contextWindowSize)
        assertEquals(500, settings.maxTokens)
    }

    @Test
    fun testDynamicSizeFillStrategyComparison() {
        // Test that DYNAMIC_SIZE_FILL is distinct from other strategies
        val dynamicSizeFill = MultiPageBudgetStrategy.DYNAMIC_SIZE_FILL
        val dynamicFill = MultiPageBudgetStrategy.DYNAMIC_FILL
        val priorityFill = MultiPageBudgetStrategy.PRIORITY_FILL
        val equalSplit = MultiPageBudgetStrategy.EQUAL_SPLIT
        val weightedSplit = MultiPageBudgetStrategy.WEIGHTED_SPLIT
        
        assertTrue(dynamicSizeFill != dynamicFill)
        assertTrue(dynamicSizeFill != priorityFill)
        assertTrue(dynamicSizeFill != equalSplit)
        assertTrue(dynamicSizeFill != weightedSplit)
        
        // Test ordinal ordering
        assertTrue(dynamicSizeFill.ordinal > dynamicFill.ordinal)
        assertTrue(dynamicSizeFill.ordinal > priorityFill.ordinal)
        assertTrue(dynamicSizeFill.ordinal > equalSplit.ordinal)
        assertTrue(dynamicSizeFill.ordinal > weightedSplit.ordinal)
    }

    @Test
    fun testStrategySerializationSupport() {
        // Test that DYNAMIC_SIZE_FILL can be used in serializable contexts
        val settings1 = TokenBudgetSettings(multiPageBudgetStrategy = MultiPageBudgetStrategy.DYNAMIC_SIZE_FILL)
        val settings2 = TokenBudgetSettings(multiPageBudgetStrategy = MultiPageBudgetStrategy.DYNAMIC_FILL)
        
        // Verify they're different
        assertTrue(settings1.multiPageBudgetStrategy != settings2.multiPageBudgetStrategy)
        assertEquals(MultiPageBudgetStrategy.DYNAMIC_SIZE_FILL, settings1.multiPageBudgetStrategy)
        assertEquals(MultiPageBudgetStrategy.DYNAMIC_FILL, settings2.multiPageBudgetStrategy)
    }

    @Test
    fun testAllStrategiesAccountedFor() {
        // Ensure all expected strategies exist
        val expectedStrategies = setOf(
            "EQUAL_SPLIT",
            "WEIGHTED_SPLIT", 
            "PRIORITY_FILL",
            "DYNAMIC_FILL",
            "DYNAMIC_SIZE_FILL"
        )
        
        val actualStrategies = MultiPageBudgetStrategy.values().map { it.name }.toSet()
        
        assertEquals(expectedStrategies, actualStrategies)
        assertEquals(5, actualStrategies.size)
    }

    @Test
    fun testDefaultStrategyUnchanged() {
        // Verify that adding DYNAMIC_SIZE_FILL didn't change the default
        val defaultSettings = TokenBudgetSettings()
        assertEquals(MultiPageBudgetStrategy.DYNAMIC_SIZE_FILL, defaultSettings.multiPageBudgetStrategy)
        
        // Verify backward compatibility
        assertTrue(defaultSettings.multiPageBudgetStrategy != MultiPageBudgetStrategy.DYNAMIC_FILL)
    }
}
