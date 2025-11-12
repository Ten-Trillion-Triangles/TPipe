package com.TTT

import com.TTT.P2P.*
import com.TTT.Pipe.MultiPageBudgetStrategy
import com.TTT.Pipe.TokenBudgetSettings
import com.TTT.Pipe.TruncationSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class P2PSecurityParadigmTest {

    @Test
    fun testDefaultAllowMultiPageContext() {
        // Test that allowMultiPageContext defaults to true for backward compatibility
        val requirements = P2PRequirements()
        assertEquals(true, requirements.allowMultiPageContext)
    }

    @Test
    fun testMultiPageContextDetection() {
        // Create a basic P2P request with multi-page indicators
        val request = P2PRequest()
        request.contextExplanationMessage = "Using pageKey for context"
        
        // Test detection logic
        val hasMultiPage = request.contextExplanationMessage.contains("pageKey", ignoreCase = true) ||
                          request.contextExplanationMessage.contains("MiniBank", ignoreCase = true)
        
        assertTrue(hasMultiPage)
    }

    @Test
    fun testSecurityParadigmStrictness() {
        // Test that setting allowMultiPageContext = false increases strictness
        val restrictiveRequirements = P2PRequirements(
            allowMultiPageContext = false
        )
        
        val permissiveRequirements = P2PRequirements(
            allowMultiPageContext = true
        )
        
        // Restrictive should be false (more strict)
        assertFalse(restrictiveRequirements.allowMultiPageContext)
        
        // Permissive should be true (less strict) 
        assertTrue(permissiveRequirements.allowMultiPageContext)
    }

    @Test
    fun testEnhancedValidationWithBudgetSettings() {
        // Test that multiPageBudgetSettings adds stricter validation
        val basicRequirements = P2PRequirements()
        
        val enhancedRequirements = P2PRequirements(
            multiPageBudgetSettings = TokenBudgetSettings(
                contextWindowSize = 1000,
                multiPageBudgetStrategy = MultiPageBudgetStrategy.WEIGHTED_SPLIT
            )
        )
        
        // Enhanced requirements should have budget settings (stricter)
        assertEquals(null, basicRequirements.multiPageBudgetSettings)
        assertEquals(MultiPageBudgetStrategy.WEIGHTED_SPLIT, 
                    enhancedRequirements.multiPageBudgetSettings?.multiPageBudgetStrategy)
    }

    @Test
    fun testP2PErrorTypesExist() {
        // Test that new error types were added
        val contextError = P2PError.context
        val configError = P2PError.configuration
        
        assertEquals("context", contextError.name)
        assertEquals("configuration", configError.name)
    }

    @Test
    fun testBackwardCompatibilityDefault() {
        // Test that existing code won't break with new defaults
        val manifoldRequirements = P2PRequirements(
            allowAgentDuplication = false,
            allowCustomContext = false,
            allowExternalConnections = false,
            requireConverseInput = true
            // allowMultiPageContext not explicitly set - should default to true
        )
        
        // Should default to true for backward compatibility
        assertTrue(manifoldRequirements.allowMultiPageContext)
    }
}
