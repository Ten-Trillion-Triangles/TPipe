package com.TTT

import com.TTT.Context.ContextWindow
import com.TTT.Pipe.MultiPageBudgetStrategy
import com.TTT.Pipe.TokenBudgetSettings
import com.TTT.Pipe.TruncationSettings
import com.TTT.Pipe.toTokenBudgetSettings
import com.TTT.Pipe.toTruncationSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertNotNull

class IntegrationGapValidationTest {

    @Test
    fun testSelectAndFillLoreBookContextWithSettings() {
        val contextWindow = ContextWindow()
        
        // Setup lorebook entries
        contextWindow.addLoreBookEntry("dragon", "Ancient fire-breathing creature", 10)
        contextWindow.addLoreBookEntry("sword", "Sharp metal blade", 5)
        contextWindow.addLoreBookEntry("magic", "Mystical power", 3)
        contextWindow.addLoreBookEntry("shield", "Protective barrier", 8)
        
        val settings = TruncationSettings()
        val selectedKeys = contextWindow.selectAndFillLoreBookContextWithSettings(
            settings, 
            "dragon sword", 
            100
        )
        
        // Should include matching entries (dragon, sword) plus fill entries by weight
        assertTrue(selectedKeys.contains("dragon"))
        assertTrue(selectedKeys.contains("sword"))
        assertTrue(selectedKeys.size >= 2) // At least matching entries
    }

    @Test
    fun testTruncationSettingsMultiPageFields() {
        val settings = TruncationSettings(
            fillMode = true,
            multiPageBudgetStrategy = MultiPageBudgetStrategy.WEIGHTED_SPLIT,
            pageWeights = mapOf("pageA" to 2.0, "pageB" to 1.0)
        )
        
        assertEquals(true, settings.fillMode)
        assertEquals(MultiPageBudgetStrategy.WEIGHTED_SPLIT, settings.multiPageBudgetStrategy)
        assertEquals(2, settings.pageWeights?.size)
        assertEquals(2.0, settings.pageWeights?.get("pageA"))
    }

    @Test
    fun testConversionUtilities() {
        // Test TruncationSettings to TokenBudgetSettings
        val truncationSettings = TruncationSettings(
            fillMode = true,
            multiPageBudgetStrategy = MultiPageBudgetStrategy.PRIORITY_FILL,
            pageWeights = mapOf("test" to 1.0)
        )
        
        val budgetSettings = truncationSettings.toTokenBudgetSettings(
            contextWindowSize = 1000,
            maxTokens = 500
        )
        
        assertEquals(1000, budgetSettings.contextWindowSize)
        assertEquals(500, budgetSettings.maxTokens)
        assertEquals(MultiPageBudgetStrategy.PRIORITY_FILL, budgetSettings.multiPageBudgetStrategy)
        assertEquals(1, budgetSettings.pageWeights?.size)
        
        // Test TokenBudgetSettings to TruncationSettings
        val convertedBack = budgetSettings.toTruncationSettings()
        assertEquals(MultiPageBudgetStrategy.PRIORITY_FILL, convertedBack.multiPageBudgetStrategy)
        assertEquals(1, convertedBack.pageWeights?.size)
    }

    @Test
    fun testStringTruncationWithFillMode() {
        val contextWindow = ContextWindow()
        
        // Setup lorebook
        contextWindow.addLoreBookEntry("important", "Very important content", 10)
        contextWindow.addLoreBookEntry("normal", "Normal content", 5)
        contextWindow.addLoreBookEntry("minor", "Minor content", 2)
        
        val settings = TruncationSettings()
        
        // Test with fillMode enabled
        val result = contextWindow.combineAndTruncateAsStringWithSettings(
            text = "important",
            tokenBudget = 100,
            settings = settings,
            fillMode = true
        )
        
        assertNotNull(result)
        assertTrue(result.isNotEmpty())
    }

    @Test
    fun testTruncationSettingsIntegration() {
        // Test that TruncationSettings properly integrates with existing systems
        val settings = TruncationSettings(
            favorWholeWords = true,
            fillMode = true,
            multiPageBudgetStrategy = MultiPageBudgetStrategy.EQUAL_SPLIT
        )
        
        // Verify all fields are accessible
        assertEquals(true, settings.favorWholeWords)
        assertEquals(true, settings.fillMode)
        assertEquals(MultiPageBudgetStrategy.EQUAL_SPLIT, settings.multiPageBudgetStrategy)
        
        // Test conversion maintains all settings
        val budgetSettings = settings.toTokenBudgetSettings(contextWindowSize = 500)
        val convertedSettings = budgetSettings.toTruncationSettings()
        
        assertEquals(MultiPageBudgetStrategy.EQUAL_SPLIT, convertedSettings.multiPageBudgetStrategy)
    }
}
