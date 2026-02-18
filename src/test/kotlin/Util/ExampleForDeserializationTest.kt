package com.TTT.Util

import com.TTT.Context.ContextWindow
import com.TTT.Pipe.Pipe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ExampleForDeserializationTest {

    @Test
    fun `examplePromptFor generated JSON can be deserialized back to original type`() {
        // Generate example JSON for ContextWindow
        val examplePrompt = examplePromptFor(ContextWindow::class)
        val jsonString = extractJsonSection(examplePrompt)
        
        // Verify it can be deserialized back
        val deserialized = deserialize<ContextWindow>(jsonString)
        assertNotNull(deserialized, "Generated example should deserialize successfully")
    }

    @Test
    fun `examplePromptFor handles abstract classes by generating empty object`() {
        // Generate example JSON for abstract Pipe class
        val examplePrompt = examplePromptFor(Pipe::class)
        val jsonString = extractJsonSection(examplePrompt)
        
        // Abstract classes generate empty objects which can't be deserialized
        // This is expected behavior - verify the empty object is generated
        assertTrue(jsonString == "{}", "Abstract classes should generate empty JSON object")
    }

    @Test
    fun `examplePromptFor output is valid JSON structure`() {
        val examplePrompt = examplePromptFor(ContextWindow::class)
        val exampleJson = Json.decodeFromString(JsonObject.serializer(), extractJsonSection(examplePrompt))
        
        // Verify it's a proper JsonObject with expected structure
        assertTrue(exampleJson.containsKey("loreBookKeys"), "Should contain loreBookKeys field")
        assertTrue(exampleJson.containsKey("contextElements"), "Should contain contextElements field")
        assertTrue(exampleJson.containsKey("converseHistory"), "Should contain converseHistory field")
    }

    private fun extractJsonSection(examplePrompt: String): String {
        val delimiter = "\n\nEnum Legend:"
        val jsonSection = examplePrompt.substringBefore(delimiter, missingDelimiterValue = examplePrompt)
        return jsonSection.trim()
    }
}
