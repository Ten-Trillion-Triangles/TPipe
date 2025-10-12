package com.TTT

import com.TTT.Context.ContextWindow
import com.TTT.Util.JsonSchemaGenerator
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class ContextWindowSchemaTest {

    @Test
    fun `should generate valid JSON schema for ContextWindow`() {
        val generator = JsonSchemaGenerator()
        val schema = generator.generate(ContextWindow::class, "ContextWindowSchema")
        
        assertNotNull(schema)
        assertEquals("https://json-schema.org/draft/2020-12/schema", schema["\$schema"]?.toString()?.removeSurrounding("\""))
        assertEquals("ContextWindowSchema", schema["\$id"]?.toString()?.removeSurrounding("\""))
        assertNotNull(schema["\$defs"])
        assertNotNull(schema["\$ref"])
    }

    @Test
    fun `should include LoreBook definition in schema`() {
        val generator = JsonSchemaGenerator()
        val schema = generator.generate(ContextWindow::class)
        
        val defs = schema["\$defs"]
        assertNotNull(defs)
        
        val prettyJson = Json { prettyPrint = true }
        val schemaString = prettyJson.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), schema)
        
        assertTrue(schemaString.contains("com.TTT.Context.LoreBook"))
        assertTrue(schemaString.contains("com.TTT.Context.ContextWindow"))
    }
}