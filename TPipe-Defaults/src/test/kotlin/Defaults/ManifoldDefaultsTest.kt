package Defaults

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertFalse

/**
 * Basic tests for ManifoldDefaults functionality
 */
class ManifoldDefaultsTest 
{
    @Test
    fun `getAvailableProviders returns list`() 
    {
        val providers = ManifoldDefaults.getAvailableProviders()
        assertTrue(providers is List<String>)
    }
    
    @Test
    fun `isProviderAvailable handles unknown provider`() 
    {
        val result = ManifoldDefaults.isProviderAvailable("unknown")
        assertFalse(result)
    }
    
    @Test
    fun `BedrockConfiguration validates correctly`() 
    {
        val validConfig = BedrockConfiguration(
            region = "us-east-1",
            model = "claude-3-sonnet"
        )
        assertTrue(validConfig.validate())
        
        val invalidConfig = BedrockConfiguration(
            region = "",
            model = "claude-3-sonnet"
        )
        assertFalse(invalidConfig.validate())
    }
    
    @Test
    fun `OllamaConfiguration validates correctly`() 
    {
        val validConfig = OllamaConfiguration(
            model = "llama3.1:8b"
        )
        assertTrue(validConfig.validate())
        
        val invalidConfig = OllamaConfiguration(
            model = ""
        )
        assertFalse(invalidConfig.validate())
    }
}
