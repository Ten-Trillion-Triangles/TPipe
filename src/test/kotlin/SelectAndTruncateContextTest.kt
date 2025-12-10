package com.TTT

import com.TTT.Context.ContextWindow
import com.TTT.Context.Dictionary
import com.TTT.Enums.ContextWindowSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SelectAndTruncateContextTest {

    @Test
    fun selectAndTruncateContextTest() {
        val contextWindow = ContextWindow()
        
        // Setup lorebook entries
        contextWindow.addLoreBookEntry("dragon", "Ancient fire-breathing creature.", 10)
        contextWindow.addLoreBookEntry("sword", "Sharp metal blade.", 5)
        contextWindow.addLoreBookEntry("magic", "Mystical power source.", 3)
        
        // Setup context elements
        contextWindow.contextElements.addAll(listOf(
            "First context element.",
            "Second context element.",
            "Third context element."
        ))
        
        // Store original counts
        val originalLorebookSize = contextWindow.loreBookKeys.size
        val originalContextSize = contextWindow.contextElements.size
        
        // Test with limited budget
        contextWindow.selectAndTruncateContext(
            text = "The dragon wielded a sword",
            totalTokenBudget = 20,
            multiplyWindowSizeBy = 1000,
            truncateSettings = com.TTT.Enums.ContextWindowSettings.TruncateBottom
        )
        
        // Verify truncation occurred
        assertTrue(contextWindow.loreBookKeys.size <= originalLorebookSize)
        assertTrue(contextWindow.contextElements.size <= originalContextSize)
        
        // Verify only matching lorebook keys remain
        assertTrue(contextWindow.loreBookKeys.containsKey("dragon"))
        assertTrue(contextWindow.loreBookKeys.containsKey("sword"))
        
        // Verify total token usage is within budget
        val lorebookTokens = contextWindow.loreBookKeys.values.sumOf { 
            Dictionary.countTokens(it.value) 
        }
        val contextTokens = contextWindow.contextElements.sumOf { 
            Dictionary.countTokens(it) 
        }
        assertTrue(lorebookTokens + contextTokens <= 20)
    }

    @Test
    fun preserveMatchingContextElements() {
        val contextWindow = ContextWindow()
        contextWindow.contextElements.addAll(listOf("alpha", "beta", "dragon"))

        contextWindow.truncateContextElements(
            maxTokens = 1,
            multiplyWindowSizeBy = 0,
            truncateSettings = ContextWindowSettings.TruncateBottom,
            inputText = "dragon",
            preserveTextMatches = true
        )

        assertEquals(listOf("dragon"), contextWindow.contextElements)
    }
}
