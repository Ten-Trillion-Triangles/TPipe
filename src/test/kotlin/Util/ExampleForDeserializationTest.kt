package com.TTT.Util

import com.TTT.Context.ContextWindow
import com.TTT.Pipe.Pipe
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ExampleForDeserializationTest {

    @Test
    fun `exampleFor generated JSON can be deserialized back to original type`() {
        // Generate example JSON for ContextWindow
        val exampleJson = exampleFor(ContextWindow::class)
        val jsonString = Json.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), exampleJson)
        
        // Verify it can be deserialized back
        val deserialized = deserialize<ContextWindow>(jsonString)
        assertNotNull(deserialized, "Generated example should deserialize successfully")
    }

    @Test
    fun `exampleFor handles abstract classes by generating empty object`() {
        // Generate example JSON for abstract Pipe class
        val exampleJson = exampleFor(Pipe::class)
        val jsonString = Json.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), exampleJson)
        
        // Abstract classes generate empty objects which can't be deserialized
        // This is expected behavior - verify the empty object is generated
        assertTrue(jsonString == "{}", "Abstract classes should generate empty JSON object")
    }

    @Test
    fun `exampleFor output is valid JSON structure`() {
        val exampleJson = exampleFor(ContextWindow::class)
        
        // Verify it's a proper JsonObject with expected structure
        assertTrue(exampleJson.containsKey("loreBookKeys"), "Should contain loreBookKeys field")
        assertTrue(exampleJson.containsKey("contextElements"), "Should contain contextElements field")
        assertTrue(exampleJson.containsKey("contextSize"), "Should contain contextSize field")
    }
}
