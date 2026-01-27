package com.TTT.Util

import com.TTT.Context.ContextWindow
import com.TTT.Context.LoreBook
import com.TTT.Context.ConverseHistory
import com.TTT.Util.extractJson
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ContextWindowExtractJsonTest {

    @Test
    fun testExtractJsonWithContextWindow() {
        // Create a ContextWindow with test data
        val contextWindow = ContextWindow().apply {
            // Add some lorebook entries
            loreBookKeys["shadow_entity"] = LoreBook().apply {
                key = "shadow entity"
                value = "Reality-warping being manipulating temporal infrastructure"
                weight = 1  // Int, not Double
                linkedKeys = mutableListOf("safehouse", "Xilaron_pulsar_spike")
                aliasKeys = mutableListOf()
                requiredKeys = mutableListOf()
            }
            
            loreBookKeys["safehouse"] = LoreBook().apply {
                key = "safehouse"
                value = "Reality-anchored base with marrow-like structural properties"
                weight = 1  // Int, not Double
                linkedKeys = mutableListOf("Kesey", "Magpie")
                aliasKeys = mutableListOf()
                requiredKeys = mutableListOf()
            }
            
            // Add context elements
            contextElements.add("Holographic cobblestones bleeding into fusion corridors")
            contextElements.add("Event horizon silk fused with Victorian-era scarves")
            
            // Set context size
            //contextSize = 8000
        }
        
        // Serialize to JSON string
        val json = Json { 
            prettyPrint = true
            ignoreUnknownKeys = true
        }
        val jsonString = json.encodeToString(contextWindow)
        
        // Wrap in some text to simulate real-world usage
        val textWithJson = "Here is some context data: $jsonString And here's more text after."
        
        // Test extractJson function
        val extracted = extractJson<ContextWindow>(textWithJson)
        
        // Assertions
        assertNotNull(extracted, "extractJson should not return null")
        assertEquals(2, extracted!!.loreBookKeys.size, "Should extract 2 lorebook entries")
        assertEquals("shadow entity", extracted.loreBookKeys["shadow_entity"]?.key)
        assertEquals(1, extracted.loreBookKeys["shadow_entity"]?.weight)
        assertEquals(2, extracted.contextElements.size, "Should extract 2 context elements")
        //assertEquals(8000, extracted.contextSize, "Should extract correct context size")
        assertTrue(extracted.converseHistory.history.isEmpty(), "Conversation history should be empty")
        
        println("✅ COMPLEX TEST SUCCESS: extractJson works with full ContextWindow!")
        println("   - Extracted ${extracted.loreBookKeys.size} lorebook entries")
        println("   - Extracted ${extracted.contextElements.size} context elements")
        //println("   - Context size: ${extracted.contextSize}")
    }
}
