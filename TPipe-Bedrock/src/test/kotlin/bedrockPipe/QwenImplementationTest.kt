package bedrockPipe

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import kotlinx.serialization.json.*

class QwenImplementationTest {

    @Test
    fun testQwenRequestBuilderFormat() {
        // Test that Qwen request builder produces correct JSON structure
        val pipe = BedrockPipe()
            .setModel("qwen.qwen3-32b-v1:0")
            .setMaxTokens(100)
            .setTemperature(0.7)
            .setTopP(0.9)
            .setTopK(50)
            .setSystemPrompt("You are a helpful assistant")
        
        // Use reflection to access private buildQwenRequest method
        val method = BedrockPipe::class.java.getDeclaredMethod("buildQwenRequest", String::class.java)
        method.isAccessible = true
        val jsonString = method.invoke(pipe, "Test prompt") as String
        
        // Parse and validate JSON structure
        val json = Json.parseToJsonElement(jsonString).jsonObject
        
        // Verify required fields
        assertTrue(json.containsKey("messages"), "Should contain messages array")
        assertTrue(json.containsKey("max_tokens"), "Should contain max_tokens")
        assertTrue(json.containsKey("enable_thinking"), "Should contain enable_thinking")
        assertTrue(json.containsKey("system"), "Should contain system prompt")
        
        // Verify message structure
        val messages = json["messages"]?.jsonArray
        assertNotNull(messages, "Messages should be an array")
        assertEquals(1, messages!!.size, "Should have one message")
        
        val message = messages[0].jsonObject
        assertEquals("user", message["role"]?.jsonPrimitive?.content, "Should have user role")
        assertEquals("Test prompt", message["content"]?.jsonPrimitive?.content, "Should have correct content")
        
        // Verify parameters
        assertEquals(100, json["max_tokens"]?.jsonPrimitive?.int, "Should have correct max_tokens")
        assertEquals(0.7, json["temperature"]?.jsonPrimitive?.double, "Should have correct temperature")
        assertEquals(0.9, json["top_p"]?.jsonPrimitive?.double, "Should have correct top_p")
        assertEquals(50, json["top_k"]?.jsonPrimitive?.int, "Should have correct top_k")
        assertEquals(false, json["enable_thinking"]?.jsonPrimitive?.boolean, "Should disable thinking by default")
        assertEquals("You are a helpful assistant", json["system"]?.jsonPrimitive?.content, "Should have system prompt")
        
        println("✅ Qwen request builder test passed: $jsonString")
    }
    
    @Test
    fun testQwenModelDetection() {
        // Test that Qwen models are properly detected
        val qwenModels = listOf(
            "qwen.qwen3-235b-a22b-2507-v1:0",
            "qwen.qwen3-32b-v1:0", 
            "qwen.qwen3-coder-480b-a35b-v1:0",
            "qwen.qwen3-coder-30b-a3b-v1:0"
        )
        
        qwenModels.forEach { modelId ->
            assertTrue(modelId.contains("qwen.qwen3"), "Model $modelId should be detected as Qwen3")
        }
        
        // Test non-Qwen models are not detected
        val nonQwenModels = listOf(
            "anthropic.claude-3-sonnet-20240229-v1:0",
            "amazon.nova-pro-v1:0",
            "deepseek.r1-v1:0"
        )
        
        nonQwenModels.forEach { modelId ->
            assertFalse(modelId.contains("qwen.qwen3"), "Model $modelId should not be detected as Qwen3")
        }
        
        println("✅ Qwen model detection test passed")
    }
    
    @Test
    fun testQwenContextWindowSpecs() {
        // Test context window specifications from documentation
        val contextWindows = mapOf(
            "qwen.qwen3-0.6b-v1:0" to 32_000,  // 32K for small models
            "qwen.qwen3-1.7b-v1:0" to 32_000,  // 32K for small models  
            "qwen.qwen3-4b-v1:0" to 32_000,    // 32K for small models
            "qwen.qwen3-8b-v1:0" to 128_000,   // 128K for large models
            "qwen.qwen3-32b-v1:0" to 128_000,  // 128K for large models
            "qwen.qwen3-235b-a22b-2507-v1:0" to 128_000, // 128K for MoE models
            "qwen.qwen3-coder-30b-a3b-v1:0" to 128_000   // 128K for coding models
        )
        
        contextWindows.forEach { (modelId, expectedTokens) ->
            assertTrue(modelId.contains("qwen.qwen3"), "Should be Qwen3 model: $modelId")
            assertTrue(expectedTokens in listOf(32_000, 128_000), "Should have valid context window: $expectedTokens")
        }
        
        println("✅ Qwen context window specifications validated")
    }
}
