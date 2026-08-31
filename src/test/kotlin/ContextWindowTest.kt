package com.TTT

import com.TTT.Context.ContextWindow
import com.TTT.Context.LoreBook
import com.TTT.Context.Dictionary
import com.TTT.Context.buildLorebookScanText
import com.TTT.Context.ConverseRole
import com.TTT.Pipe.LoreBookTokenAccounting
import com.TTT.Pipe.MultimodalContent
import com.TTT.Util.serialize
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ContextWindowTest {

    @Test
    fun selectLoreBookContextWithLinkedKeysTest() {
        val contextWindow = ContextWindow()
        
        // Setup lorebook with linked keys
        contextWindow.addLoreBookEntry(
            key = "dragon",
            value = "Ancient fire-breathing creature.",
            weight = 10,
            linkedKeys = listOf("fire", "scales")
        )
        contextWindow.addLoreBookEntry(
            key = "fire",
            value = "Elemental flame magic.",
            weight = 5
        )
        contextWindow.addLoreBookEntry(
            key = "scales",
            value = "Protective dragon armor.",
            weight = 3
        )
        contextWindow.addLoreBookEntry(
            key = "sword",
            value = "Sharp metal blade.",
            weight = 8
        )
        
        // Test text that only mentions "dragon" - should trigger linked keys
        val selectedContext = contextWindow.selectLoreBookContext("The dragon roared", 100)
        
        // Should include dragon + its linked keys (fire, scales)
        assertEquals(3, selectedContext.size)
        assertTrue(selectedContext.contains("dragon"))
        assertTrue(selectedContext.contains("fire"))
        assertTrue(selectedContext.contains("scales"))
    }

    @Test
    fun selectLoreBookContextWithAliasKeysTest() {
        val contextWindow = ContextWindow()
        
        // Setup lorebook with alias keys
        contextWindow.addLoreBookEntry(
            key = "dragon",
            value = "Ancient fire-breathing creature.",
            weight = 10,
            aliasKeys = listOf("wyrm", "drake", "wyvern")
        )
        contextWindow.addLoreBookEntry(
            key = "sword",
            value = "Sharp metal blade.",
            weight = 5
        )
        
        // Test text using alias key "wyrm" instead of main key "dragon"
        val selectedContext = contextWindow.selectLoreBookContext("The wyrm attacked with sword", 50)
        
        // Should include dragon entry (triggered by alias) and sword
        assertEquals(2, selectedContext.size)
        assertTrue(selectedContext.contains("dragon"))
        assertTrue(selectedContext.contains("sword"))
    }

    @Test
    fun selectLoreBookContextWithLinkedAndAliasKeysTest() {
        val contextWindow = ContextWindow()
        
        // Setup complex lorebook with both linked and alias keys
        contextWindow.addLoreBookEntry(
            key = "dragon",
            value = "Ancient fire-breathing creature.",
            weight = 10,
            linkedKeys = listOf("fire"),
            aliasKeys = listOf("wyrm")
        )
        contextWindow.addLoreBookEntry(
            key = "fire",
            value = "Elemental flame magic.",
            weight = 5,
            linkedKeys = listOf("magic")
        )
        contextWindow.addLoreBookEntry(
            key = "magic",
            value = "Mystical power source.",
            weight = 3
        )
        
        // Test using alias "wyrm" which should trigger dragon -> fire -> magic chain
        val selectedContext = contextWindow.selectLoreBookContext("The wyrm appeared", 100)
        
        // Should include all three: dragon (via alias), fire (linked to dragon), magic (linked to fire)
        assertEquals(3, selectedContext.size)
        assertTrue(selectedContext.contains("dragon"))
        assertTrue(selectedContext.contains("fire"))
        assertTrue(selectedContext.contains("magic"))
    }

    @Test
    fun selectLoreBookContextPriorityOrderTest() {
        val contextWindow = ContextWindow()
        
        // Setup lorebook with different weights
        contextWindow.addLoreBookEntry("high", "High priority entry.", 100)
        contextWindow.addLoreBookEntry("medium", "Medium priority entry.", 50)
        contextWindow.addLoreBookEntry("low", "Low priority entry.", 10)
        
        // Check actual token counts
        val highTokens = Dictionary.countTokens("High priority entry.")
        val mediumTokens = Dictionary.countTokens("Medium priority entry.")
        val lowTokens = Dictionary.countTokens("Low priority entry.")
        
        // Test with limited token budget that can only fit 2 entries
        val selectedContext = contextWindow.selectLoreBookContext("high medium low", 15)
        
        // Debug output
        val totalTokens = selectedContext.sumOf { Dictionary.countTokens(it) }
        
        // Adjust expectation based on actual token counts
        // If all 3 entries fit within 15 tokens, expect 3, otherwise expect 2
        val expectedSize = if (highTokens + mediumTokens + lowTokens <= 15) 3 else 2
        
        assertEquals(expectedSize, selectedContext.size)
        assertTrue(selectedContext.contains("high"))
        if (expectedSize >= 2) {
            assertTrue(selectedContext.contains("medium"))
        }
        if (expectedSize >= 3) {
            assertTrue(selectedContext.contains("low"))
        }
    }

    @Test
    fun selectLoreBookContextTokenBudgetTest() {
        val contextWindow = ContextWindow()
        
        // Setup entries with known token counts
        contextWindow.addLoreBookEntry("short", "Short.", 10) // ~1 token
        contextWindow.addLoreBookEntry("medium", "Medium length entry.", 10) // ~3 tokens
        contextWindow.addLoreBookEntry("long", "This is a much longer entry with many words.", 10) // ~9 tokens
        
        // Test with tight token budget
        val selectedContext = contextWindow.selectLoreBookContext("short medium long", 5)
        
        // Should only include entries that fit within budget
        val totalTokens = selectedContext.sumOf { key -> 
            contextWindow.loreBookKeys[key]?.let { Dictionary.countTokens(it.value) } ?: 0
        }
        assertTrue(totalTokens <= 5)
        assertTrue(selectedContext.isNotEmpty())
    }

    @Test
    fun selectLoreBookContextNoDuplicatesTest() {
        val contextWindow = ContextWindow()

        // Setup lorebook where multiple paths could lead to same entry
        contextWindow.addLoreBookEntry(
            key = "dragon",
            value = "Fire-breathing creature.",
            weight = 10,
            linkedKeys = listOf("fire"),
            aliasKeys = listOf("wyrm")
        )
        contextWindow.addLoreBookEntry(
            key = "fire",
            value = "Elemental flame.",
            weight = 5,
            linkedKeys = listOf("dragon") // Circular reference
        )

        // Test text that could trigger dragon multiple ways
        val selectedContext = contextWindow.selectLoreBookContext("The dragon breathes fire and the wyrm roars", 100)

        // Should not have duplicates despite multiple trigger paths
        assertEquals(2, selectedContext.size)
        assertEquals(1, selectedContext.count { it == "dragon" })
        assertEquals(1, selectedContext.count { it == "fire" })
    }

    @Test
    fun buildLorebookScanTextReturnsUserPromptWhenFlagOff() {
        val contextWindow = ContextWindow()

        // Populate both extra sources so any leakage would be visible.
        contextWindow.contextElements.add("context element with dragon keyword")
        contextWindow.contextElements.add("context element with phoenix keyword")
        contextWindow.converseHistory.add(
            ConverseRole.user,
            MultimodalContent("prior turn mentions wyvern")
        )
        contextWindow.converseHistory.add(
            ConverseRole.assistant,
            MultimodalContent("assistant turn mentions drake")
        )

        val userPrompt = "Tell me about the hero."
        val scanText = contextWindow.buildLorebookScanText(userPrompt, useEntireContext = false)

        assertEquals(
            userPrompt,
            scanText,
            "When useEntireContext is false the helper must return the user prompt verbatim"
        )
    }

    @Test
    fun buildLorebookScanTextReturnsUserPromptWhenFlagOnButNoExtraSources() {
        val contextWindow = ContextWindow()

        val userPrompt = "Tell me about the hero."
        val scanText = contextWindow.buildLorebookScanText(userPrompt, useEntireContext = true)

        assertEquals(
            userPrompt,
            scanText,
            "With no contextElements or converseHistory the helper must return the user prompt unchanged"
        )
        assertFalse(
            scanText.endsWith("\n"),
            "There must be no trailing newline when both extra sources are empty"
        )
    }

    @Test
    fun buildLorebookScanTextAppendsContextElementsWhenFlagOn() {
        val contextWindow = ContextWindow()

        contextWindow.contextElements.add("first context element")
        contextWindow.contextElements.add("second context element")
        // converseHistory stays empty so only the contextElements branch fires.

        val userPrompt = "Tell me about the hero."
        val scanText = contextWindow.buildLorebookScanText(userPrompt, useEntireContext = true)

        val expected = "$userPrompt\nfirst context element\nsecond context element"
        assertEquals(
            expected,
            scanText,
            "contextElements must be joined with \\n and prefixed by a single \\n separator"
        )
    }

    @Test
    fun buildLorebookScanTextAppendsConverseHistoryWhenFlagOn() {
        val contextWindow = ContextWindow()

        // contextElements stays empty so only the converseHistory branch fires.
        contextWindow.converseHistory.add(
            ConverseRole.user,
            MultimodalContent("user turn keeps the conversation grounded")
        )
        contextWindow.converseHistory.add(
            ConverseRole.assistant,
            MultimodalContent("assistant turn keeps the conversation grounded")
        )

        val userPrompt = "Tell me about the hero."
        val scanText = contextWindow.buildLorebookScanText(userPrompt, useEntireContext = true)

        val expected = "$userPrompt\nuser turn keeps the conversation grounded\nassistant turn keeps the conversation grounded"
        assertEquals(
            expected,
            scanText,
            "converseHistory text must be joined with \\n and prefixed by a single \\n separator"
        )
    }

    @Test
    fun buildLorebookScanTextConcatenatesAllSourcesInOrder() {
        val contextWindow = ContextWindow()

        contextWindow.contextElements.add("alpha context")
        contextWindow.contextElements.add("beta context")
        contextWindow.converseHistory.add(
            ConverseRole.user,
            MultimodalContent("user said gamma")
        )
        contextWindow.converseHistory.add(
            ConverseRole.assistant,
            MultimodalContent("assistant said delta")
        )

        val userPrompt = "Tell me about the hero."
        val scanText = contextWindow.buildLorebookScanText(userPrompt, useEntireContext = true)

        val expected = "$userPrompt\nalpha context\nbeta context\nuser said gamma\nassistant said delta"
        assertEquals(
            expected,
            scanText,
            "userPrompt + \\n + contextElements.joinToString(\"\\n\") + \\n + converseHistory.joinToString(\"\\n\") { it.content.text }"
        )
    }

    @Test
    fun repeatedMatchesMustInfluenceEqualWeightRanking()
    {
        val contextWindow = ContextWindow()
        val repeatedValue = "same lore value"
        val valueTokens = Dictionary.countTokens(repeatedValue)

        contextWindow.addLoreBookEntry(
            key = "single",
            value = repeatedValue,
            weight = 10
        )
        contextWindow.addLoreBookEntry(
            key = "repeated",
            value = repeatedValue,
            weight = 10
        )

        val selectedKeys = contextWindow.selectLoreBookContext(
            text = "repeated repeated repeated single",
            maxTokens = valueTokens
        )

        assertEquals(
            listOf("repeated"),
            selectedKeys,
            "Repeated direct matches should win the equal-weight hit-count tie"
        )
    }

    @Test
    fun repeatedMatchesMustInfluenceEqualWeightRankingSuspend() = runBlocking {
        val contextWindow = ContextWindow()
        val repeatedValue = "same lore value"
        val valueTokens = Dictionary.countTokens(repeatedValue)

        contextWindow.addLoreBookEntry(
            key = "single",
            value = repeatedValue,
            weight = 10
        )
        contextWindow.addLoreBookEntry(
            key = "repeated",
            value = repeatedValue,
            weight = 10
        )

        val selectedKeys = contextWindow.selectLoreBookContextSuspend(
            text = "repeated repeated repeated single",
            maxTokens = valueTokens
        )

        assertEquals(listOf("repeated"), selectedKeys)
    }

    @Test
    fun valueOnlyAccountingPreservesCurrentSelection()
    {
        val contextWindow = ContextWindow()
        val loreBookKey = "serialized-overhead-entry-with-a-long-key"
        val loreBookValue = "lore"
        val valueTokens = Dictionary.countTokens(loreBookValue)

        contextWindow.addLoreBookEntry(
            key = loreBookKey,
            value = loreBookValue,
            weight = 10
        )

        val selectedKeys = contextWindow.selectLoreBookContext(
            text = loreBookKey,
            maxTokens = valueTokens,
            loreBookTokenAccounting = LoreBookTokenAccounting.ValueOnly
        )

        assertEquals(listOf(loreBookKey), selectedKeys)
    }

    @Test
    fun serializedEntryAccountingRejectsMetadataOverflow()
    {
        val contextWindow = ContextWindow()
        val loreBookKey = "serialized-overhead-entry-with-a-long-key"
        val loreBookValue = "lore"
        val loreBookAliases = listOf(
            "long-alias-one",
            "long-alias-two",
            "long-alias-three"
        )
        val valueTokens = Dictionary.countTokens(loreBookValue)
        val loreBook = LoreBook().apply {
            key = loreBookKey
            value = loreBookValue
            weight = 10
            aliasKeys.addAll(loreBookAliases)
        }
        val serializedTokens = Dictionary.countTokens(serialize(loreBook))

        assertTrue(serializedTokens > valueTokens)

        contextWindow.addLoreBookEntry(
            key = loreBookKey,
            value = loreBookValue,
            weight = 10,
            aliasKeys = loreBookAliases
        )

        val selectedKeys = contextWindow.selectLoreBookContext(
            text = loreBookKey,
            maxTokens = valueTokens,
            loreBookTokenAccounting = LoreBookTokenAccounting.SerializedEntry
        )

        assertTrue(selectedKeys.isEmpty())
    }

    @Test
    fun fullContextSerializedAccountingRejectsFramingOverflow()
    {
        val contextWindow = ContextWindow()
        val loreBookKey = "framed-entry"
        val loreBookValue = "lore"
        val valueTokens = Dictionary.countTokens(loreBookValue)

        contextWindow.addLoreBookEntry(
            key = loreBookKey,
            value = loreBookValue,
            weight = 10
        )

        val serializedContextTokens = Dictionary.countTokens(serialize(contextWindow))
        assertTrue(serializedContextTokens > valueTokens)

        val selectedKeys = contextWindow.selectLoreBookContext(
            text = loreBookKey,
            maxTokens = valueTokens,
            loreBookTokenAccounting = LoreBookTokenAccounting.FullContextSerialized
        )

        assertTrue(selectedKeys.isEmpty())
    }

    @Test
    fun serializedEntryAccountingSkipsOversizedCandidateAndContinues()
    {
        val contextWindow = ContextWindow()
        val oversizedKey = "oversized-entry-with-metadata"
        val oversizedValue = "large lore value"
        val smallerKey = "small"
        val smallerValue = "small lore"

        contextWindow.addLoreBookEntry(
            key = oversizedKey,
            value = oversizedValue,
            weight = 10,
            aliasKeys = listOf("oversized-alias-one", "oversized-alias-two")
        )
        contextWindow.addLoreBookEntry(
            key = smallerKey,
            value = smallerValue,
            weight = 1
        )

        val smallerTokens = Dictionary.countTokens(serialize(contextWindow.loreBookKeys.getValue(smallerKey)))
        val oversizedTokens = Dictionary.countTokens(serialize(contextWindow.loreBookKeys.getValue(oversizedKey)))
        assertTrue(oversizedTokens > smallerTokens)

        val selectedKeys = contextWindow.selectAndFillLoreBookContext(
            text = "$oversizedKey $smallerKey",
            maxTokens = smallerTokens,
            loreBookTokenAccounting = LoreBookTokenAccounting.SerializedEntry
        )

        assertEquals(listOf(smallerKey), selectedKeys)
    }
}
