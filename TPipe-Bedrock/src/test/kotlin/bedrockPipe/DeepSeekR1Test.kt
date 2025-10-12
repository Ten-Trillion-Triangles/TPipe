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

class DeepSeekR1Test {
    
    private lateinit var deepseekPipe: BedrockPipe
    
    @BeforeEach
    fun setup() {
        TestCredentialUtils.requireAwsCredentials()
        
        val pipe = BedrockPipe()
            .setProvider(ProviderName.Aws)
            .setModel("deepseek.deepseek-r1")
        (pipe as BedrockPipe).setRegion("us-east-2")
        deepseekPipe = pipe as BedrockPipe
    }
    
    @Test
    fun testDeepSeekRequestFormat() {
        deepseekPipe
            .setSystemPrompt("You are an expert reasoning assistant.")
            .setTemperature(0.7)
            .setMaxTokens(1000)
            .setTopP(0.9)
            .setStopSequences(listOf("END"))
        
        // Test request building by accessing private method via reflection
        val buildMethod = BedrockPipe::class.java.getDeclaredMethod("buildDeepSeekRequest", String::class.java)
        buildMethod.isAccessible = true
        val requestJson = buildMethod.invoke(deepseekPipe, "Test prompt") as String
        
        val json = Json.parseToJsonElement(requestJson).jsonObject
        
        assertEquals(1000, json["max_tokens"]?.jsonPrimitive?.content?.toInt())
        assertEquals(0.7, json["temperature"]?.jsonPrimitive?.content?.toDouble() ?: 0.0, 0.01)
        assertEquals(0.9, json["top_p"]?.jsonPrimitive?.content?.toDouble() ?: 0.0, 0.01)
        assertTrue(json.containsKey("prompt"))
        assertTrue(json.containsKey("stop"))
    }
    
    @Test
    fun testDeepSeekResponseExtraction() {
        val mockResponse = """
        {
            "choices": [
                {
                    "text": "15% of 240 is 36. Here's the calculation: 240 × 0.15 = 36"
                }
            ]
        }
        """.trimIndent()
        
        // Test response extraction via reflection
        val extractMethod = BedrockPipe::class.java.getDeclaredMethod("extractTextFromResponse", String::class.java, String::class.java)
        extractMethod.isAccessible = true
        val result = extractMethod.invoke(deepseekPipe, mockResponse, "deepseek.deepseek-r1") as String
        
        assertEquals("15% of 240 is 36. Here's the calculation: 240 × 0.15 = 36", result)
    }
    
    @Test
    fun testDeepSeekWithSystemPrompt() {
        deepseekPipe.setSystemPrompt("You are a math tutor.")
        
        val buildMethod = BedrockPipe::class.java.getDeclaredMethod("buildDeepSeekRequest", String::class.java)
        buildMethod.isAccessible = true
        val requestJson = buildMethod.invoke(deepseekPipe, "What is 2+2?") as String
        
        val json = Json.parseToJsonElement(requestJson).jsonObject
        val prompt = json["prompt"]?.jsonPrimitive?.content
        
        assertNotNull(prompt)
        assertTrue(requestJson.contains("math tutor"))
        assertTrue(requestJson.contains("What is 2+2?"))
    }
    
    @Test
    fun testDeepSeekEmptyResponse() {
        val emptyResponse = "{}"
        
        val extractMethod = BedrockPipe::class.java.getDeclaredMethod("extractTextFromResponse", String::class.java, String::class.java)
        extractMethod.isAccessible = true
        val result = extractMethod.invoke(deepseekPipe, emptyResponse, "deepseek.deepseek-r1") as String
        
        assertEquals("", result)
    }
    
    @Test
    fun testDeepSeekMalformedResponse() {
        val malformedResponse = "invalid json"
        
        val extractMethod = BedrockPipe::class.java.getDeclaredMethod("extractTextFromResponse", String::class.java, String::class.java)
        extractMethod.isAccessible = true
        val result = extractMethod.invoke(deepseekPipe, malformedResponse, "deepseek.deepseek-r1") as String
        
        assertEquals("", result)
    }
    
    @Test
    fun testDeepSeekParameterMapping() {
        deepseekPipe
            .setTemperature(0.5)
            .setMaxTokens(500)
            .setTopP(0.8)
            .setStopSequences(listOf("STOP", "END"))
        
        val buildMethod = BedrockPipe::class.java.getDeclaredMethod("buildDeepSeekRequest", String::class.java)
        buildMethod.isAccessible = true
        val requestJson = buildMethod.invoke(deepseekPipe, "Test") as String
        
        val json = Json.parseToJsonElement(requestJson).jsonObject
        
        assertEquals(500, json["max_tokens"]?.jsonPrimitive?.content?.toInt())
        assertEquals(0.5, json["temperature"]?.jsonPrimitive?.content?.toDouble() ?: 0.0, 0.01)
        assertEquals(0.8, json["top_p"]?.jsonPrimitive?.content?.toDouble() ?: 0.0, 0.01)
        assertTrue(requestJson.contains("STOP"))
        assertTrue(requestJson.contains("END"))
    }
}