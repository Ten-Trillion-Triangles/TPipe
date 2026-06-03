package com.TTT.Native

import com.TTT.Context.LoreBook
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * TDD tests for LoreBookHandle — the C ABI wrapper around TPipe's LoreBook.
 *
 * LoreBook stores keyed context in a NovelAI-like system. Each entry has a key,
 * value, weight, and optional linking/aliasing/required keys.
 *
 * RED PHASE: These tests define expected behavior. Some may fail until the full
 * ABI implementation is complete. Following TDD: RED first, then GREEN.
 */
class LoreBookHandleTest {

    //==========================================================================
    // Creation and Type Discriminator
    //==========================================================================

    @Test
    fun testCreateWithName() {
        val loreBook = LoreBook()
        loreBook.key = "test-key"
        val handle = LoreBookHandle(loreBook)
        assertNotNull(handle, "LoreBookHandle constructor should return a non-null handle")
        assertEquals("test-key", handle.getKey(), "handle should wrap the provided LoreBook")
    }

    @Test
    fun testTypeDiscriminator() {
        // LORE_BOOK discriminator must match HandleTypes.LOREBOOK (=7)
        assertEquals(7, HandleTypes.LOREBOOK, "HandleTypes.LOREBOOK should be 7")
    }

    //==========================================================================
    // Key and Value Getters/Setters
    //==========================================================================

    @Test
    fun testGetSetKey() {
        val loreBook = LoreBook()
        val handle = LoreBookHandle(loreBook)
        handle.setKey("new-key")
        assertEquals("new-key", handle.getKey(), "setKey should mutate the wrapped LoreBook key")
    }

    @Test
    fun testGetSetValue() {
        val loreBook = LoreBook()
        val handle = LoreBookHandle(loreBook)
        handle.setValue("some context value")
        assertEquals("some context value", handle.getValue(),
            "setValue should mutate the wrapped LoreBook value")
    }

    @Test
    fun testGetSetWeight() {
        val loreBook = LoreBook()
        val handle = LoreBookHandle(loreBook)
        handle.setWeight(75)
        assertEquals(75, handle.getWeight(), "setWeight should mutate the wrapped LoreBook weight")
    }

    //==========================================================================
    // Linked / Alias / Required Key Management
    //==========================================================================

    @Test
    fun testGetSetLinkedKeys() {
        val loreBook = LoreBook()
        val handle = LoreBookHandle(loreBook)
        assertEquals(emptyList(), handle.getLinkedKeys(),
            "fresh handle should have empty linked keys")

        handle.addLinkedKey("linked-a")
        handle.addLinkedKey("linked-b")
        val linked = handle.getLinkedKeys()
        assertEquals(2, linked.size, "should have 2 linked keys after adds")
        assertTrue("linked-a" in linked, "linked keys should contain linked-a")
        assertTrue("linked-b" in linked, "linked keys should contain linked-b")
    }

    @Test
    fun testGetSetAliasKeys() {
        val loreBook = LoreBook()
        val handle = LoreBookHandle(loreBook)
        assertEquals(emptyList(), handle.getAliasKeys(),
            "fresh handle should have empty alias keys")

        handle.addAliasKey("alias-a")
        handle.addAliasKey("alias-b")
        val aliases = handle.getAliasKeys()
        assertEquals(2, aliases.size, "should have 2 alias keys after adds")
        assertTrue("alias-a" in aliases, "alias keys should contain alias-a")
        assertTrue("alias-b" in aliases, "alias keys should contain alias-b")
    }

    @Test
    fun testGetSetRequiredKeys() {
        val loreBook = LoreBook()
        val handle = LoreBookHandle(loreBook)
        assertEquals(emptyList(), handle.getRequiredKeys(),
            "fresh handle should have empty required keys")

        handle.addRequiredKey("req-a")
        handle.addRequiredKey("req-b")
        val required = handle.getRequiredKeys()
        assertEquals(2, required.size, "should have 2 required keys after adds")
        assertTrue("req-a" in required, "required keys should contain req-a")
        assertTrue("req-b" in required, "required keys should contain req-b")
    }

    @Test
    fun testAddLinkedKeyIdempotent() {
        val loreBook = LoreBook()
        val handle = LoreBookHandle(loreBook)
        handle.addLinkedKey("dup")
        handle.addLinkedKey("dup")
        assertEquals(1, handle.getLinkedKeys().size,
            "adding the same linked key twice should be idempotent")
    }

    //==========================================================================
    // Combine Two LoreBooks
    //==========================================================================

    @Test
    fun testCombine() {
        val loreBookA = LoreBook().apply { key = "a"; value = "value-a" }
        val loreBookB = LoreBook().apply { key = "b"; value = "value-b" }
        val handleA = LoreBookHandle(loreBookA)
        val handleB = LoreBookHandle(loreBookB)
        handleA.combine(handleB)
        val combinedValue = handleA.getValue()
        assertTrue(combinedValue.contains("value-a"),
            "combined value should retain original value-a, got: $combinedValue")
        assertTrue(combinedValue.contains("value-b"),
            "combined value should include value-b, got: $combinedValue")
    }

    //==========================================================================
    // JSON Serialization
    //==========================================================================

    @Test
    fun testToJson() {
        val loreBook = LoreBook().apply {
            key = "mykey"
            value = "myvalue"
            weight = 50
        }
        val handle = LoreBookHandle(loreBook)
        val json = handle.toJson()
        assertTrue(json.contains("\"key\""), "toJson should contain key field, got: $json")
        assertTrue(json.contains("mykey"), "toJson should contain the key value, got: $json")
        assertTrue(json.contains("myvalue"), "toJson should contain the value, got: $json")
        assertTrue(json.contains("50"), "toJson should contain the weight, got: $json")
    }

    //==========================================================================
    // HandleRegistry Integration
    //==========================================================================

    @Test
    fun testRefCounting() {
        val loreBook = LoreBook().apply { key = "ref-key" }
        val handle = LoreBookHandle(loreBook)
        val handleId = HandleRegistry.allocate(HandleTypes.LOREBOOK, handle)
        assertTrue(handleId >= 0, "allocate() should return non-negative handle, got: $handleId")
        assertEquals(1, HandleRegistry.getRefCount(handleId),
            "newly allocated LoreBookHandle should have refCount=1")

        val addResult = HandleRegistry.addRef(handleId)
        assertEquals(0, addResult, "addRef should return 0 on success")
        assertEquals(2, HandleRegistry.getRefCount(handleId),
            "refCount should be 2 after addRef")

        HandleRegistry.release(handleId)
        assertEquals(1, HandleRegistry.getRefCount(handleId),
            "refCount should be 1 after one release")

        HandleRegistry.release(handleId)
        assertEquals(false, HandleRegistry.isValid(handleId),
            "handle should be invalid after final release")
    }
}
