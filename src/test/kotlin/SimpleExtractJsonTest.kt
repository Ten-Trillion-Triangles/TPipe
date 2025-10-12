package com.TTT

import com.TTT.Context.ContextWindow
import com.TTT.Context.LoreBook
import com.TTT.Util.extractJson
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

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
}
