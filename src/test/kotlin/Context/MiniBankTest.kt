package com.TTT.Context

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MiniBankTest
{
    @Test
    fun testMergeEmptyBank()
    {
        val target = MiniBank()
        val source = MiniBank()

        target.merge(source)
        assertTrue(target.isEmpty())
        assertTrue(target.contextMap.isEmpty())
    }

    @Test
    fun testMergeDisjointKeys()
    {
        val target = MiniBank()
        val source = MiniBank()

        val cw1 = ContextWindow()
        cw1.addLoreBookEntry("key1", "value1")
        target.contextMap["window1"] = cw1

        val cw2 = ContextWindow()
        cw2.addLoreBookEntry("key2", "value2")
        source.contextMap["window2"] = cw2

        target.merge(source)

        assertEquals(2, target.contextMap.size)
        assertTrue(target.contextMap.containsKey("window1"))
        assertTrue(target.contextMap.containsKey("window2"))
        assertEquals("value1", target.contextMap["window1"]?.loreBookKeys?.get("key1")?.value)
        assertEquals("value2", target.contextMap["window2"]?.loreBookKeys?.get("key2")?.value)
    }

    @Test
    fun testMergeOverlappingKeysWithAppend()
    {
        val target = MiniBank()
        val source = MiniBank()

        val cw1 = ContextWindow()
        cw1.addLoreBookEntry("lore1", "target_value")
        target.contextMap["window1"] = cw1

        val cw2 = ContextWindow()
        cw2.addLoreBookEntry("lore1", "source_value")
        source.contextMap["window1"] = cw2

        // Merge using appendKeys = true
        target.merge(source, emplaceLorebookKeys = false, appendKeys = true)

        assertEquals(1, target.contextMap.size)
        assertTrue(target.contextMap.containsKey("window1"))
        val mergedWindow = target.contextMap["window1"]

        // appendKeys logic in ContextWindow.merge adds a space then the value:
        assertEquals("target_value source_value", mergedWindow?.loreBookKeys?.get("lore1")?.value)
    }

    @Test
    fun testMergeOverlappingKeysWithEmplace()
    {
        val target = MiniBank()
        val source = MiniBank()

        val cw1 = ContextWindow()
        cw1.addLoreBookEntry("lore1", "target_value")
        target.contextMap["window1"] = cw1

        val cw2 = ContextWindow()
        cw2.addLoreBookEntry("lore1", "source_value")
        source.contextMap["window1"] = cw2

        // Merge using emplaceLorebookKeys = true
        target.merge(source, emplaceLorebookKeys = true, appendKeys = false)

        assertEquals(1, target.contextMap.size)
        assertTrue(target.contextMap.containsKey("window1"))
        val mergedWindow = target.contextMap["window1"]

        // emplaceLorebookKeys replaces the value
        assertEquals("source_value", mergedWindow?.loreBookKeys?.get("lore1")?.value)
    }

    @Test
    fun testMergeConverseHistoryEmplaceIfNull()
    {
        val target = MiniBank()
        val source = MiniBank()

        val cw1 = ContextWindow()
        cw1.converseHistory.add(ConverseRole.user, com.TTT.Pipe.MultimodalContent(text="Hello"))
        target.contextMap["window1"] = cw1

        val cw2 = ContextWindow()
        cw2.converseHistory.add(ConverseRole.assistant, com.TTT.Pipe.MultimodalContent(text="Hi there"))
        source.contextMap["window1"] = cw2
        source.contextMap["window2"] = ContextWindow().apply {
            converseHistory.add(ConverseRole.assistant, com.TTT.Pipe.MultimodalContent(text="New window history"))
        }

        // Merge using emplaceConverseHistory = true, onlyEmplaceIfNull = true
        target.merge(source, emplaceConverseHistory = true, onlyEmplaceIfNull = true)

        // window1 target was not empty, so it should keep its history
        val mergedWindow1 = target.contextMap["window1"]
        assertEquals(1, mergedWindow1?.converseHistory?.history?.size)
        assertEquals("Hello", mergedWindow1?.converseHistory?.history?.first()?.content?.text)

        // window2 target was empty (didn't exist), so it should get source history
        val mergedWindow2 = target.contextMap["window2"]
        assertEquals(1, mergedWindow2?.converseHistory?.history?.size)
        assertEquals("New window history", mergedWindow2?.converseHistory?.history?.first()?.content?.text)
    }

    @Test
    fun testMergeConverseHistoryReplace()
    {
        val target = MiniBank()
        val source = MiniBank()

        val cw1 = ContextWindow()
        cw1.converseHistory.add(ConverseRole.user, com.TTT.Pipe.MultimodalContent(text="Old target history"))
        target.contextMap["window1"] = cw1

        val cw2 = ContextWindow()
        cw2.converseHistory.add(ConverseRole.assistant, com.TTT.Pipe.MultimodalContent(text="New source history"))
        source.contextMap["window1"] = cw2

        // Merge using emplaceConverseHistory = true, onlyEmplaceIfNull = false
        target.merge(source, emplaceConverseHistory = true, onlyEmplaceIfNull = false)

        // window1 target history should be replaced
        val mergedWindow1 = target.contextMap["window1"]
        assertEquals(1, mergedWindow1?.converseHistory?.history?.size)
        assertEquals("New source history", mergedWindow1?.converseHistory?.history?.first()?.content?.text)
    }

    @Test
    fun testClearAndIsEmpty()
    {
        val bank = MiniBank()
        assertTrue(bank.isEmpty())

        bank.contextMap["window"] = ContextWindow()
        assertFalse(bank.isEmpty())

        bank.clear()
        assertTrue(bank.isEmpty())
    }
}
