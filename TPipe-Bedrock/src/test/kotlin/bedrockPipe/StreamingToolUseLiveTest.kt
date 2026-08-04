package bedrockPipe

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StreamingToolUseLiveTest
{
    @BeforeAll
    fun checkCredentials()
    {
        val allowTest = System.getenv("AllowTest")
        assumeTrue(allowTest == "true", "AllowTest flag not enabled — skipping live test")
    }

    @Test
    fun liveStreamingClaudeToolCallPopulatesMetadata()
    {
        val pipe = BedrockPipe()
        pipe.setRegion("us-east-1")
        pipe.setModel("anthropic.claude-3-5-sonnet-20241022-v2:0")
        pipe.useConverseApi()
        pipe.enableStreaming()
        // Define a tool the model can call
        pipe.setTools(listOf(
            kotlinx.serialization.json.JsonObject(mapOf(
                "name" to kotlinx.serialization.json.JsonPrimitive("get_weather"),
                "description" to kotlinx.serialization.json.JsonPrimitive("Get the current weather for a location"),
                "input_schema" to kotlinx.serialization.json.JsonObject(mapOf(
                    "type" to kotlinx.serialization.json.JsonPrimitive("object"),
                    "properties" to kotlinx.serialization.json.JsonObject(mapOf(
                        "location" to kotlinx.serialization.json.JsonObject(mapOf(
                            "type" to kotlinx.serialization.json.JsonPrimitive("string")
                        ))
                    )),
                    "required" to kotlinx.serialization.json.JsonArray(listOf(
                        kotlinx.serialization.json.JsonPrimitive("location")
                    ))
                ))
            ))
        ))
        kotlinx.coroutines.runBlocking { pipe.init() }

        val prompt = "What's the weather in San Francisco? Use the get_weather tool."
        val result = kotlinx.coroutines.runBlocking {
            pipe.generateText(prompt)
        }

        // Assert: streaming call completed and metadata captured
        val metadata = pipe.getLastCallMetadata()
        assertNotNull(metadata, "Streaming call should populate BedrockCallMetadata")
        metadata?.let { meta ->
            // Tool call was made — toolUse should have 1 entry
            assertTrue(meta.toolUse.isNotEmpty(), "Should have at least one tool use entry")
            meta.toolUse.forEach { tool ->
                assertTrue(tool.toolUseId.isNotEmpty(), "Tool use ID should be populated")
            }
        }
    }
}
