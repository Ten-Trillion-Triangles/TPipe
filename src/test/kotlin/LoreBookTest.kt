package com.TTT

import com.TTT.Context.ContextWindow
import com.TTT.Context.LoreBook
import com.TTT.Context.Dictionary
import kotlin.test.Test

class LoreBookTest {

    @Test
    fun loreBookContextSelection() {
        val contextWindow = ContextWindow()
        
        // Setup test lorebook with different weights
        contextWindow.loreBookKeys["dragon"] = LoreBook().apply {
            key = "dragon"
            value = "Ancient fire-breathing creature with scales and wings."
            weight = 10
        }
        contextWindow.loreBookKeys["sword"] = LoreBook().apply {
            key = "sword"
            value = "Sharp metal blade weapon used for combat."
            weight = 5
        }
        contextWindow.loreBookKeys["magic"] = LoreBook().apply {
            key = "magic"
            value = "Mystical power that defies natural laws."
            weight = 8
        }
        contextWindow.loreBookKeys["castle"] = LoreBook().apply {
            key = "castle"
            value = "Large fortified stone structure."
            weight = 3
        }
        contextWindow.loreBookKeys["knight"] = LoreBook().apply {
            key = "knight"
            value = "Armored warrior on horseback."
            weight = 5
        }
        
        val testScenarios = listOf(
            "The dragon breathed fire at the knight with his sword" to 50,
            "Magic filled the castle as the dragon roared" to 30,
            "The knight knight knight used his sword sword to fight the dragon" to 40,
            "In the magic castle, the dragon and knight battled with sword and magic" to 25
        )
        
        println("=== LOREBOOK CONTEXT SELECTION ===")
        
        for ((text, maxTokens) in testScenarios) {
            println("\nText: \"$text\"")
            println("Max tokens: $maxTokens")
            
            val matchingKeys = contextWindow.findMatchingLoreBookKeys(text)
            val keyHits = contextWindow.countAndSortKeyHits(matchingKeys)
            val selectedContext = contextWindow.selectLoreBookContext(text, maxTokens)
            
            println("Matching keys: $matchingKeys")
            println("Key hits: $keyHits")
            println("Selected context (${selectedContext.size} entries):")
            selectedContext.forEachIndexed { i, context ->
                val tokens = Dictionary.countTokens(context)
                println("  ${i+1}. [$tokens tokens] $context")
            }
            println("Total tokens used: ${selectedContext.sumOf { Dictionary.countTokens(it) }}")
        }
    }

    @Test
    fun loreBookLargeBudgetTest() {
        val contextWindow = ContextWindow()
        
        // Setup expanded lorebook with varied content lengths
        contextWindow.loreBookKeys["dragon"] = LoreBook().apply {
            key = "dragon"
            value = "Ancient fire-breathing creature with massive scales covering its body, powerful wings that can create devastating windstorms, razor-sharp claws capable of tearing through the strongest armor, and breath that can melt stone and metal alike. These legendary beasts are known for their immense intelligence, cunning nature, and their tendency to hoard vast treasures in their mountain lairs. They are feared across all kingdoms and respected by even the most powerful wizards and warriors."
            weight = 10
        }
        contextWindow.loreBookKeys["magic"] = LoreBook().apply {
            key = "magic"
            value = "Mystical energy that flows through all living things, allowing practitioners to bend reality to their will through complex incantations, precise gestures, and focused mental discipline. Magic comes in many forms including elemental manipulation, healing arts, divination, enchantment, and necromancy. The most skilled mages can alter time itself, teleport across vast distances, and even bring the dead back to life."
            weight = 8
        }
        contextWindow.loreBookKeys["sword"] = LoreBook().apply {
            key = "sword"
            value = "Forged steel blade weapon designed for combat, featuring a sharp double-edged blade, sturdy crossguard, and wrapped leather grip. Master swordsmiths spend years perfecting their craft to create weapons of legendary quality."
            weight = 5
        }
        contextWindow.loreBookKeys["castle"] = LoreBook().apply {
            key = "castle"
            value = "Massive stone fortress with towering walls, defensive battlements, and multiple courtyards designed to withstand prolonged sieges."
            weight = 3
        }
        contextWindow.loreBookKeys["knight"] = LoreBook().apply {
            key = "knight"
            value = "Noble warrior bound by codes of chivalry and honor."
            weight = 5
        }
        
        val testText = "The dragon used magic to defend its castle while the knight wielded his sword"
        val selectedContext = contextWindow.selectLoreBookContext(testText, 4000)
        
        println("=== 4K TOKEN BUDGET TEST ===")
        println("Text: \"$testText\"")
        println("Max tokens: 4000")
        println("Selected context (${selectedContext.size} entries):")
        
        selectedContext.forEachIndexed { i, context ->
            val tokens = Dictionary.countTokens(context)
            println("  ${i+1}. [$tokens tokens] ${context.take(100)}...")
        }
        
        val totalTokens = selectedContext.sumOf { Dictionary.countTokens(it) }
        println("Total tokens used: $totalTokens / 4000")
        println("Budget utilization: ${(totalTokens * 100.0 / 4000).toInt()}%")
    }

    @Test
    fun contextElementsTruncationTest() {
        val contextWindow = ContextWindow()
        
        // Setup context elements with varied lengths
        contextWindow.contextElements.addAll(listOf(
            "First context element with some basic information about the world.",
            "Second element containing more detailed background story and character development.",
            "Third element with extensive lore about the magical system and how it works in this universe.",
            "Fourth element describing the political landscape and various kingdoms that exist.",
            "Fifth element with combat mechanics and weapon systems used by different factions."
        ))
        
        val originalSize = contextWindow.contextElements.size
        val originalTokens = contextWindow.contextElements.sumOf { Dictionary.countTokens(it) }
        
        println("=== CONTEXT ELEMENTS TRUNCATION TEST ===")
        println("Original elements: $originalSize")
        println("Original tokens: $originalTokens")
        
        // Test TruncateBottom (keep beginning)
        val testWindow1 = ContextWindow().apply { contextElements.addAll(contextWindow.contextElements) }
        testWindow1.truncateContextElements(25, 1000, com.TTT.Enums.ContextWindowSettings.TruncateBottom)
        val bottomTokens = testWindow1.contextElements.sumOf { Dictionary.countTokens(it) }
        println("\nTruncateBottom (25 tokens):")
        println("  Elements kept: ${testWindow1.contextElements.size}")
        println("  Tokens used: $bottomTokens")
        testWindow1.contextElements.forEachIndexed { i, element ->
            println("  ${i+1}. ${element.take(60)}...")
        }
        
        // Test TruncateTop (keep end)
        val testWindow2 = ContextWindow().apply { contextElements.addAll(contextWindow.contextElements) }
        testWindow2.truncateContextElements(25, 1000, com.TTT.Enums.ContextWindowSettings.TruncateTop)
        val topTokens = testWindow2.contextElements.sumOf { Dictionary.countTokens(it) }
        println("\nTruncateTop (25 tokens):")
        println("  Elements kept: ${testWindow2.contextElements.size}")
        println("  Tokens used: $topTokens")
        testWindow2.contextElements.forEachIndexed { i, element ->
            println("  ${i+1}. ${element.take(60)}...")
        }
        
        // Test TruncateMiddle (keep beginning and end)
        val testWindow3 = ContextWindow().apply { contextElements.addAll(contextWindow.contextElements) }
        testWindow3.truncateContextElements(25, 1000, com.TTT.Enums.ContextWindowSettings.TruncateMiddle)
        val middleTokens = testWindow3.contextElements.sumOf { Dictionary.countTokens(it) }
        println("\nTruncateMiddle (25 tokens):")
        println("  Elements kept: ${testWindow3.contextElements.size}")
        println("  Tokens used: $middleTokens")
        testWindow3.contextElements.forEachIndexed { i, element ->
            println("  ${i+1}. ${element.take(60)}...")
        }
    }

    @Test
    fun testRequiredKeysDependency()
    {
        val contextWindow = ContextWindow()
        
        // Setup lorebook entries with dependencies
        contextWindow.addLoreBookEntry("dragon", "Ancient fire-breathing creature.", 10)
        contextWindow.addLoreBookEntry("sword", "Sharp metal blade weapon.", 5)
        contextWindow.addLoreBookEntry("dragonslayer", "Legendary warrior who hunts dragons.", 15, requiredKeys = listOf("dragon", "sword"))
        contextWindow.addLoreBookEntry("magic", "Mystical power.", 8)
        contextWindow.addLoreBookEntry("enchanted_sword", "Magical blade with special powers.", 12, requiredKeys = listOf("sword", "magic"))
        
        println("=== REQUIRED KEYS DEPENDENCY TEST ===")
        
        // Test 1: Text contains dragon and sword - dragonslayer should be included
        val text1 = "The dragon breathed fire while the knight drew his sword"
        val selected1 = contextWindow.selectLoreBookContext(text1, 1000)
        println("\nTest 1 - Text: \"$text1\"")
        println("Selected keys: $selected1")
        println("Should include: dragon, sword, dragonslayer")
        
        // Test 2: Text contains only dragon - dragonslayer should NOT be included
        val text2 = "The dragon roared loudly in its cave"
        val selected2 = contextWindow.selectLoreBookContext(text2, 1000)
        println("\nTest 2 - Text: \"$text2\"")
        println("Selected keys: $selected2")
        println("Should include: dragon only (no dragonslayer)")
        
        // Test 3: Text contains sword and magic - enchanted_sword should be included
        val text3 = "The wizard cast magic on the sword"
        val selected3 = contextWindow.selectLoreBookContext(text3, 1000)
        println("\nTest 3 - Text: \"$text3\"")
        println("Selected keys: $selected3")
        println("Should include: sword, magic, enchanted_sword")
        
        // Test 4: Complex scenario with all keys present
        val text4 = "The dragon attacked while the knight used magic on his sword"
        val selected4 = contextWindow.selectLoreBookContext(text4, 1000)
        println("\nTest 4 - Text: \"$text4\"")
        println("Selected keys: $selected4")
        println("Should include: all keys (dragon, sword, magic, dragonslayer, enchanted_sword)")
    }

    @Test
    fun testRequiredKeysWithAliases()
    {
        val contextWindow = ContextWindow()
        
        // Setup lorebook with aliases and dependencies
        contextWindow.addLoreBookEntry("dragon", "Fire-breathing beast.", 10, aliasKeys = listOf("wyrm", "drake"))
        contextWindow.addLoreBookEntry("sword", "Metal blade.", 5, aliasKeys = listOf("blade", "weapon"))
        contextWindow.addLoreBookEntry("dragonslayer", "Dragon hunter.", 15, requiredKeys = listOf("dragon", "sword"))
        
        println("=== REQUIRED KEYS WITH ALIASES TEST ===")
        
        // Test 1: Using alias keys to satisfy dependencies
        val text1 = "The wyrm attacked while the hero raised his blade"
        val selected1 = contextWindow.selectLoreBookContext(text1, 1000)
        println("\nTest 1 - Text: \"$text1\"")
        println("Selected keys: $selected1")
        println("Should include: dragon (via wyrm), sword (via blade), dragonslayer")
        
        // Test 2: Mixed direct and alias matches
        val text2 = "The dragon was defeated by a sharp weapon"
        val selected2 = contextWindow.selectLoreBookContext(text2, 1000)
        println("\nTest 2 - Text: \"$text2\"")
        println("Selected keys: $selected2")
        println("Should include: dragon (direct), sword (via weapon), dragonslayer")
    }

    @Test
    fun testRequiredKeysBackwardCompatibility()
    {
        val contextWindow = ContextWindow()
        
        // Setup mix of old-style entries (no dependencies) and new-style entries
        contextWindow.loreBookKeys["dragon"] = LoreBook().apply {
            key = "dragon"
            value = "Ancient creature."
            weight = 10
            // No requiredKeys set - should default to empty list
        }
        
        contextWindow.addLoreBookEntry("sword", "Sharp blade.", 5)
        contextWindow.addLoreBookEntry("dragonslayer", "Dragon hunter.", 15, requiredKeys = listOf("dragon"))
        
        println("=== BACKWARD COMPATIBILITY TEST ===")
        
        val text = "The dragon was slain by a sword-wielding warrior"
        val selected = contextWindow.selectLoreBookContext(text, 1000)
        println("\nText: \"$text\"")
        println("Selected keys: $selected")
        println("Should include: dragon (no deps), sword (no deps), dragonslayer (deps satisfied)")
    }

    @Test
    fun testRequiredKeysInMerge()
    {
        val contextWindow1 = ContextWindow()
        val contextWindow2 = ContextWindow()
        
        // Setup first context with basic entries
        contextWindow1.addLoreBookEntry("dragon", "Fire beast.", 10, requiredKeys = listOf("fire"))
        contextWindow1.addLoreBookEntry("sword", "Metal blade.", 5)
        
        // Setup second context with overlapping and new entries
        contextWindow2.addLoreBookEntry("dragon", "Ancient wyrm.", 12, requiredKeys = listOf("ancient"))
        contextWindow2.addLoreBookEntry("magic", "Mystical power.", 8)
        
        println("=== REQUIRED KEYS MERGE TEST ===")
        
        println("Before merge:")
        println("Context1 dragon requiredKeys: ${contextWindow1.loreBookKeys["dragon"]?.requiredKeys}")
        
        // Merge contexts
        contextWindow1.merge(contextWindow2)
        
        println("After merge:")
        println("Context1 dragon requiredKeys: ${contextWindow1.loreBookKeys["dragon"]?.requiredKeys}")
        println("Should contain both: fire, ancient")
        println("All keys: ${contextWindow1.loreBookKeys.keys}")
    }

    @Test
    fun testRequiredKeysChaining()
    {
        val contextWindow = ContextWindow()
        
        // Setup dependency chain: A requires B, B requires C
        contextWindow.addLoreBookEntry("fire", "Burning element.", 5)
        contextWindow.addLoreBookEntry("dragon", "Fire creature.", 10, requiredKeys = listOf("fire"))
        contextWindow.addLoreBookEntry("dragonlord", "Master of dragons.", 20, requiredKeys = listOf("dragon"))
        contextWindow.addLoreBookEntry("sword", "Metal blade.", 5)
        
        println("=== DEPENDENCY CHAINING TEST ===")
        
        // Test 1: Only fire present - should get fire only
        val text1 = "The fire burned brightly"
        val selected1 = contextWindow.selectLoreBookContext(text1, 1000)
        println("\nTest 1 - Text: \"$text1\"")
        println("Selected keys: $selected1")
        println("Should include: fire only")
        
        // Test 2: Fire and dragon present - should get fire and dragon
        val text2 = "The fire dragon soared overhead"
        val selected2 = contextWindow.selectLoreBookContext(text2, 1000)
        println("\nTest 2 - Text: \"$text2\"")
        println("Selected keys: $selected2")
        println("Should include: fire, dragon")
        
        // Test 3: All elements present - should get all
        val text3 = "The fire dragon served the dragonlord who wielded a sword"
        val selected3 = contextWindow.selectLoreBookContext(text3, 1000)
        println("\nTest 3 - Text: \"$text3\"")
        println("Selected keys: $selected3")
        println("Should include: fire, dragon, dragonlord, sword")
    }

    @Test
    fun testLinkedKeysWithDependencies()
    {
        val contextWindow = ContextWindow()
        
        // Setup lorebook with linked keys and dependencies
        contextWindow.addLoreBookEntry("dragon", "Fire creature.", 10, linkedKeys = listOf("fire", "treasure"))
        contextWindow.addLoreBookEntry("fire", "Burning element.", 5)
        contextWindow.addLoreBookEntry("treasure", "Gold and gems.", 3)
        contextWindow.addLoreBookEntry("sword", "Metal blade.", 5)
        contextWindow.addLoreBookEntry("dragonslayer", "Dragon hunter.", 15, requiredKeys = listOf("dragon", "sword"))
        
        println("=== LINKED KEYS WITH DEPENDENCIES TEST ===")
        
        // Test: Text contains dragon and sword - should get dragon, linked keys (fire, treasure), sword, and dragonslayer
        val text = "The dragon breathed fire while the knight drew his sword"
        val selected = contextWindow.selectLoreBookContext(text, 1000)
        println("\nText: \"$text\"")
        println("Selected keys: $selected")
        println("Should include: dragon, fire (linked), treasure (linked), sword, dragonslayer (deps satisfied)")
        
        // Verify all expected keys are present
        val expectedKeys = setOf("dragon", "fire", "treasure", "sword", "dragonslayer")
        val selectedSet = selected.toSet()
        val missing = expectedKeys - selectedSet
        val extra = selectedSet - expectedKeys
        
        if (missing.isNotEmpty()) {
            println("❌ Missing keys: $missing")
        }
        if (extra.isNotEmpty()) {
            println("ℹ️ Extra keys: $extra")
        }
        if (missing.isEmpty() && extra.isEmpty()) {
            println("✅ All expected keys present")
        }
    }
}