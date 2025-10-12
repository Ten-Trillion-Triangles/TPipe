package bedrockPipe

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import kotlinx.serialization.json.*

class ConverseApiRolloutTest {

    @Test
    fun testJsonSerializationCorrectness() {
        // Test that buildJsonObject toString produces valid JSON - this validates our builders approach
        val testJson = buildJsonObject {
            put("temperature", JsonPrimitive(0.7))
            put("max_tokens", JsonPrimitive(1000))
            put("nested", buildJsonObject {
                put("scale", JsonPrimitive(0.0))
            })
        }
        
        val jsonString = testJson.toString()
        
        // Verify it's valid JSON by parsing it back
        val parsed = Json.parseToJsonElement(jsonString)
        assertTrue(parsed is JsonObject, "JSON should parse correctly")
        
        println("✅ JSON serialization test passed: $jsonString")
        
        // Verify the JSON contains expected values
        val obj = parsed.jsonObject
        assertEquals(0.7, obj["temperature"]?.jsonPrimitive?.double)
        assertEquals(1000, obj["max_tokens"]?.jsonPrimitive?.int)
        assertEquals(0.0, obj["nested"]?.jsonObject?.get("scale")?.jsonPrimitive?.double)
    }
}
