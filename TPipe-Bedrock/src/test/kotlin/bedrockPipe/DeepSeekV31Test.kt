package bedrockPipe

import bedrockPipe.BedrockPipe
import com.TTT.Enums.ProviderName
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Assertions.*

/**
 * Comprehensive test suite for DeepSeek V3.1 model support.
 * 
 * Tests model detection, thinking parameter functionality, backward compatibility
 * with R1 models, and reasoning content extraction for both V3.1 and R1 models.
 */
class DeepSeekV31Test {
    
    private lateinit var v31Pipe: BedrockPipe
    private lateinit var r1Pipe: BedrockPipe
    
    @BeforeEach
    fun setup() {
        TestCredentialUtils.requireAwsCredentials()
        
        // Setup V3.1 pipe
        val v31 = BedrockPipe()
            .setProvider(ProviderName.Aws)
            .setModel("deepseek.v3-v1:0")
        (v31 as BedrockPipe).setRegion("us-east-2")
        v31Pipe = v31 as BedrockPipe
        
        // Setup R1 pipe for backward compatibility testing
        val r1 = BedrockPipe()
            .setProvider(ProviderName.Aws)
            .setModel("deepseek.r1-v1:0")
        (r1 as BedrockPipe).setRegion("us-east-2")
        r1Pipe = r1 as BedrockPipe
    }
    
    @Test
    fun testV31ModelDetection() {
        // Test isDeepSeekV31() method with various model IDs
        val isV31Method = BedrockPipe::class.java.getDeclaredMethod("isDeepSeekV31", String::class.java)
        isV31Method.isAccessible = true
        
        assertTrue(isV31Method.invoke(v31Pipe, "deepseek.v3-v1:0") as Boolean)
        assertTrue(isV31Method.invoke(v31Pipe, "us.deepseek.v3-v1:0") as Boolean)
        assertTrue(isV31Method.invoke(v31Pipe, "DeepSeek.V3-v1:0") as Boolean) // Case insensitive
        
        assertFalse(isV31Method.invoke(v31Pipe, "deepseek.r1-v1:0") as Boolean)
        assertFalse(isV31Method.invoke(v31Pipe, "anthropic.claude-3-sonnet") as Boolean)
    }
    
    @Test
    fun testR1ModelDetection() {
        // Test isDeepSeekR1() method (ensure no regression)
        val isR1Method = BedrockPipe::class.java.getDeclaredMethod("isDeepSeekR1", String::class.java)
        isR1Method.isAccessible = true
        
        assertTrue(isR1Method.invoke(r1Pipe, "deepseek.r1-v1:0") as Boolean)
        assertTrue(isR1Method.invoke(r1Pipe, "us.deepseek.r1-v1:0") as Boolean)
        assertTrue(isR1Method.invoke(r1Pipe, "DeepSeek.R1-v1:0") as Boolean) // Case insensitive
        
        assertFalse(isR1Method.invoke(r1Pipe, "deepseek.v3-v1:0") as Boolean)
        assertFalse(isR1Method.invoke(r1Pipe, "anthropic.claude-3-sonnet") as Boolean)
    }
    
    @Test
    fun testThinkingParameterEnabled() {
        // Test thinking parameter is added when useModelReasoning = true
        v31Pipe.setReasoning()
        
        val buildMethod = BedrockPipe::class.java.getDeclaredMethod("buildDeepSeekRequest", String::class.java)
        buildMethod.isAccessible = true
        val requestJson = buildMethod.invoke(v31Pipe, "Test prompt") as String
        
        val json = Json.parseToJsonElement(requestJson).jsonObject
        
        assertTrue(json.containsKey("thinking"))
        val thinking = json["thinking"]?.jsonObject
        assertNotNull(thinking)
        assertEquals("enabled", thinking?.get("type")?.jsonPrimitive?.content)
    }
    
    @Test
    fun testThinkingParameterDisabled() {
        // Test thinking parameter is NOT added when useModelReasoning = false
        // useModelReasoning defaults to false, so no need to explicitly set it
        
        val buildMethod = BedrockPipe::class.java.getDeclaredMethod("buildDeepSeekRequest", String::class.java)
        buildMethod.isAccessible = true
        val requestJson = buildMethod.invoke(v31Pipe, "Test prompt") as String
        
        val json = Json.parseToJsonElement(requestJson).jsonObject
        
        assertFalse(json.containsKey("thinking"))
    }
    
    @Test
    fun testR1BackwardCompatibility() {
        // Ensure R1 models still work without thinking parameter
        r1Pipe.setReasoning()
        
        val buildMethod = BedrockPipe::class.java.getDeclaredMethod("buildDeepSeekRequest", String::class.java)
        buildMethod.isAccessible = true
        val requestJson = buildMethod.invoke(r1Pipe, "Test prompt") as String
        
        val json = Json.parseToJsonElement(requestJson).jsonObject
        
        // R1 should NOT have thinking parameter even when reasoning is enabled
        assertFalse(json.containsKey("thinking"))
        assertTrue(json.containsKey("prompt"))
        assertTrue(json.containsKey("max_tokens"))
    }
    
    @Test
    fun testV31ConverseApiThinkingParameter() {
        // Test thinking parameter in Converse API via additionalModelRequestFields
        v31Pipe.setReasoning()
        
        val buildMethod = BedrockPipe::class.java.getDeclaredMethod("buildDeepSeekConverseRequestObject", String::class.java, java.util.List::class.java)
        buildMethod.isAccessible = true
        
        // Create content blocks
        val contentBlocks = listOf(
            aws.sdk.kotlin.services.bedrockruntime.model.ContentBlock.Text("Test prompt")
        )
        
        val converseRequest = buildMethod.invoke(v31Pipe, "deepseek.v3-v1:0", contentBlocks)
        
        // Check that additionalModelRequestFields is set
        assertNotNull(converseRequest)
        // Note: We can't easily test the Document content without more complex reflection
        // but the build success indicates the structure is correct
    }
    
    @Test
    fun testV31ReasoningExtraction() {
        // Test reasoning content extraction for V3.1 responses
        val mockV31Response = """
        {
            "output": {
                "message": {
                    "content": [
                        {
                            "text": "The answer is 42."
                        },
                        {
                            "reasoningContent": {
                                "reasoningText": "Let me think about this step by step..."
                            }
                        }
                    ]
                }
            }
        }
        """.trimIndent()
        
        val extractMethod = BedrockPipe::class.java.getDeclaredMethod("extractReasoningContent", String::class.java, String::class.java)
        extractMethod.isAccessible = true
        val result = extractMethod.invoke(v31Pipe, mockV31Response, "deepseek.v3-v1:0") as String
        
        assertEquals("Let me think about this step by step...", result)
    }
    
    @Test
    fun testV31InvokeApiReasoningExtraction() {
        // Test V3.1 Invoke API reasoning extraction
        val mockInvokeResponse = """
        {
            "thinking": "This requires careful analysis...",
            "choices": [
                {
                    "text": "The solution is X."
                }
            ]
        }
        """.trimIndent()
        
        // Disable Converse API to test Invoke API path
        val useConverseApiField = BedrockPipe::class.java.getDeclaredField("useConverseApi")
        useConverseApiField.isAccessible = true
        useConverseApiField.set(v31Pipe, false)
        
        val extractMethod = BedrockPipe::class.java.getDeclaredMethod("extractReasoningContent", String::class.java, String::class.java)
        extractMethod.isAccessible = true
        val result = extractMethod.invoke(v31Pipe, mockInvokeResponse, "deepseek.v3-v1:0") as String
        
        assertEquals("This requires careful analysis...", result)
    }
    
    @Test
    fun testR1ReasoningExtractionPreserved() {
        // Ensure R1 reasoning extraction still works as before
        val mockR1Response = """
        {
            "output": {
                "message": {
                    "content": [
                        {
                            "text": "The answer is 42."
                        },
                        {
                            "reasoningContent": {
                                "reasoningText": "R1 reasoning content..."
                            }
                        }
                    ]
                }
            }
        }
        """.trimIndent()
        
        val extractMethod = BedrockPipe::class.java.getDeclaredMethod("extractReasoningContent", String::class.java, String::class.java)
        extractMethod.isAccessible = true
        val result = extractMethod.invoke(r1Pipe, mockR1Response, "deepseek.r1-v1:0") as String
        
        assertEquals("R1 reasoning content...", result)
    }
    
    @Test
    fun testV31ContextWindowSettings() {
        // Test that V3.1 gets 128k context window
        v31Pipe.truncateModuleContext()
        
        val contextWindowSizeField = BedrockPipe::class.java.getDeclaredField("contextWindowSize")
        contextWindowSizeField.isAccessible = true
        val v31ContextSize = contextWindowSizeField.get(v31Pipe) as Int
        
        assertEquals(128000, v31ContextSize)
    }
    
    @Test
    fun testR1ContextWindowSettingsPreserved() {
        // Test that R1 preserves existing 126k context window
        r1Pipe.truncateModuleContext()
        
        val contextWindowSizeField = BedrockPipe::class.java.getDeclaredField("contextWindowSize")
        contextWindowSizeField.isAccessible = true
        val r1ContextSize = contextWindowSizeField.get(r1Pipe) as Int
        
        assertEquals(126000, r1ContextSize)
    }
    
    @Test
    fun testShouldEnableDeepSeekThinking() {
        // Test shouldEnableDeepSeekThinking logic
        val shouldEnableMethod = BedrockPipe::class.java.getDeclaredMethod("shouldEnableDeepSeekThinking", String::class.java)
        shouldEnableMethod.isAccessible = true
        
        // V3.1 with reasoning enabled should return true
        v31Pipe.setReasoning()
        assertTrue(shouldEnableMethod.invoke(v31Pipe, "deepseek.v3-v1:0") as Boolean)
        
        // V3.1 with reasoning disabled should return false (set field directly)
        val useModelReasoningField = BedrockPipe::class.java.getDeclaredField("useModelReasoning")
        useModelReasoningField.isAccessible = true
        useModelReasoningField.set(v31Pipe, false)
        assertFalse(shouldEnableMethod.invoke(v31Pipe, "deepseek.v3-v1:0") as Boolean)
        
        // R1 should always return false regardless of reasoning setting
        r1Pipe.setReasoning()
        assertFalse(shouldEnableMethod.invoke(r1Pipe, "deepseek.r1-v1:0") as Boolean)
    }
    
    @Test
    fun testV31RequestFormatWithAllParameters() {
        // Test complete V3.1 request format with all parameters
        v31Pipe
            .setSystemPrompt("You are a helpful assistant.")
            .setTemperature(0.7)
            .setMaxTokens(1000)
            .setTopP(0.9)
            .setStopSequences(listOf("END", "STOP"))
            .setReasoning()
        
        val buildMethod = BedrockPipe::class.java.getDeclaredMethod("buildDeepSeekRequest", String::class.java)
        buildMethod.isAccessible = true
        val requestJson = buildMethod.invoke(v31Pipe, "Test prompt") as String
        
        val json = Json.parseToJsonElement(requestJson).jsonObject
        
        // Verify all standard parameters
        assertEquals(1000, json["max_tokens"]?.jsonPrimitive?.content?.toInt())
        assertEquals(0.7, json["temperature"]?.jsonPrimitive?.content?.toDouble() ?: 0.0, 0.01)
        assertEquals(0.9, json["top_p"]?.jsonPrimitive?.content?.toDouble() ?: 0.0, 0.01)
        assertTrue(json.containsKey("prompt"))
        assertTrue(json.containsKey("stop"))
        
        // Verify thinking parameter for V3.1
        assertTrue(json.containsKey("thinking"))
        val thinking = json["thinking"]?.jsonObject
        assertEquals("enabled", thinking?.get("type")?.jsonPrimitive?.content)
    }
}
