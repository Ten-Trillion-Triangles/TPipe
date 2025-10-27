package Defaults

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertEquals
import com.TTT.Pipeline.Pipeline
import bedrockPipe.BedrockMultimodalPipe

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

    @Test
    fun `assignManagerPipelineDefaults returns configured pipeline without replacing it`()
    {
        val pipeline = Pipeline().apply {
            add(BedrockMultimodalPipe())
            add(BedrockMultimodalPipe())
        }

        val configured = ManifoldDefaults.assignManagerPipelineDefaults(pipeline)

        // Should return the same instance we passed in and keep both pipes
        assertSame(pipeline, configured)
        assertEquals(2, configured.getPipes().size)
        assertEquals("entry pipe", configured.getPipes()[0].pipeName)
        assertEquals("Agent caller pipe", configured.getPipes()[1].pipeName)
    }
}
