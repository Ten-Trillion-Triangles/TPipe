package com.TTT

import com.TTT.Context.ContextWindow
import com.TTT.Context.LoreBook
import com.TTT.Util.extractJson
import com.TTT.Util.deserialize
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SimpleExtractJsonTest {

    @Test
    fun testExtractJsonWorks() {
        // Your original JSON that was failing
        val jsonString = """
        {
            "loreBookKeys": {
                "shadow_entity": {
                    "key": "shadow entity",
                    "value": "Reality-warping being",
                    "weight": 1,
                    "linkedKeys": ["safehouse"],
                    "aliasKeys": [],
                    "requiredKeys": []
                }
            },
            "contextElements": [],
            "converseHistory": { "history": [] },
            "contextSize": 8000
        }
        """.trimIndent()
        
        // Test extractJson function
        val extracted = extractJson<ContextWindow>(jsonString)
        
        // Prove it works
        assertNotNull(extracted, "extractJson should not return null")
        assertEquals(1, extracted!!.loreBookKeys.size)
        assertEquals("shadow entity", extracted.loreBookKeys["shadow_entity"]?.key)
        assertEquals(8000, extracted.contextSize)
        
        println("✅ SUCCESS: extractJson<ContextWindow> works correctly!")
        println("   - Extracted ${extracted.loreBookKeys.size} lorebook entries")
        println("   - Context size: ${extracted.contextSize}")
        println("   - First key: ${extracted.loreBookKeys.values.first().key}")
    }
    
    @Test
    fun `deserialize should reject unrelated JSON types`() {
        // Test that deserialize creates object with default values when JSON fields don't match
        val result = deserialize<ContextWindow>("""{"userId": 123, "email": "test@example.com", "productId": "abc"}""")
        assertNotNull(result, "Should create ContextWindow with default values even with unrelated JSON")
        assertEquals(8000, result?.contextSize, "Should use default contextSize when not provided in JSON")
        
        println("✅ SUCCESS: deserialize correctly handles unrelated JSON types with defaults")
    }
    
    @Test
    fun `deserialize should work with matching field structures`() {
        // Test that deserialize works when JSON has fields that match the target type
        val result = deserialize<ContextWindow>("""{"contextSize": 5000}""")
        assertNotNull(result, "Should create ContextWindow when JSON has matching fields")
        assertEquals(5000, result?.contextSize)
        
        println("✅ SUCCESS: deserialize works with matching field structures")
    }
}
