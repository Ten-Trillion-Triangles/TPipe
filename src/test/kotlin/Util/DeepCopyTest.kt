package com.TTT.Util

import com.TTT.Context.ContextWindow
import com.TTT.Context.LoreBook
import com.TTT.Context.MiniBank
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

class DeepCopyTest
{
    @Test
    fun testContextWindowDeepCopy()
    {
        // Create original ContextWindow with data
        val original = ContextWindow()
        val loreBook1 = LoreBook()
        loreBook1.key = "key1"
        loreBook1.value = "value1"
        original.loreBookKeys["key1"] = loreBook1
        
        val loreBook2 = LoreBook()
        loreBook2.key = "key2"
        loreBook2.value = "value2"
        original.loreBookKeys["key2"] = loreBook2
        
        original.contextElements.add("element1")
        original.contextElements.add("element2")
        //original.contextSize = 200000
        
        // Deep copy
        val copy = original.deepCopy()
        
        // Verify it's a different instance
        assertNotSame(original, copy, "Copy should be a different instance")
        
        // Verify loreBookKeys are copied
        assertEquals(2, copy.loreBookKeys.size, "loreBookKeys size should match")
        assertTrue(copy.loreBookKeys.containsKey("key1"), "Should contain key1")
        assertTrue(copy.loreBookKeys.containsKey("key2"), "Should contain key2")
        assertNotSame(original.loreBookKeys, copy.loreBookKeys, "loreBookKeys should be different map instance")
        
        // Verify contextElements are copied
        assertEquals(2, copy.contextElements.size, "contextElements size should match")
        assertEquals("element1", copy.contextElements[0], "First element should match")
        assertEquals("element2", copy.contextElements[1], "Second element should match")
        assertNotSame(original.contextElements, copy.contextElements, "contextElements should be different list instance")
        
        // Verify contextSize is copied
        //assertEquals(200000, copy.contextSize, "contextSize should be copied")
        
        // Verify modifications to copy don't affect original
        val loreBook3 = LoreBook()
        loreBook3.key = "key3"
        loreBook3.value = "value3"
        copy.loreBookKeys["key3"] = loreBook3
        copy.contextElements.add("element3")
        //copy.contextSize = 300000
        
        assertEquals(2, original.loreBookKeys.size, "Original loreBookKeys should be unchanged")
        assertEquals(2, original.contextElements.size, "Original contextElements should be unchanged")
        //assertEquals(200000, original.contextSize, "Original contextSize should be unchanged")
    }
    
    @Test
    fun testMiniBankDeepCopy()
    {
        // Create original MiniBank with ContextWindows
        val original = MiniBank()
        
        val context1 = ContextWindow()
        val loreBook1 = LoreBook()
        loreBook1.key = "key1"
        loreBook1.value = "value1"
        context1.loreBookKeys["key1"] = loreBook1
        context1.contextElements.add("element1")
        //context1.contextSize = 100000
        
        val context2 = ContextWindow()
        val loreBook2 = LoreBook()
        loreBook2.key = "key2"
        loreBook2.value = "value2"
        context2.loreBookKeys["key2"] = loreBook2
        context2.contextElements.add("element2")
        //context2.contextSize = 150000
        
        original.contextMap["page1"] = context1
        original.contextMap["page2"] = context2
        
        // Deep copy
        val copy = original.deepCopy()
        
        // Verify it's a different instance
        assertNotSame(original, copy, "Copy should be a different instance")
        assertNotSame(original.contextMap, copy.contextMap, "contextMap should be different instance")
        
        // Verify contextMap is copied
        assertEquals(2, copy.contextMap.size, "contextMap size should match")
        assertTrue(copy.contextMap.containsKey("page1"), "Should contain page1")
        assertTrue(copy.contextMap.containsKey("page2"), "Should contain page2")
        
        // Verify nested ContextWindows are deep copied
        val copiedContext1 = copy.contextMap["page1"]!!
        assertNotSame(context1, copiedContext1, "Nested ContextWindow should be different instance")
        assertEquals(1, copiedContext1.loreBookKeys.size, "Nested loreBookKeys should be copied")
        assertEquals(1, copiedContext1.contextElements.size, "Nested contextElements should be copied")
        assertEquals("element1", copiedContext1.contextElements[0], "Nested element should match")
        //assertEquals(100000, copiedContext1.contextSize, "Nested contextSize should be copied")
        
        // Verify modifications to copy don't affect original
        copy.contextMap["page3"] = ContextWindow()
        copy.contextMap["page1"]!!.contextElements.add("new element")
        
        assertEquals(2, original.contextMap.size, "Original contextMap should be unchanged")
        assertEquals(1, original.contextMap["page1"]!!.contextElements.size, "Original nested contextElements should be unchanged")
    }
}
