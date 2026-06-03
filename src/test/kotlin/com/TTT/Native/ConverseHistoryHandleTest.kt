package com.TTT.Native

import com.TTT.Context.ConverseHistory
import com.TTT.Context.ConverseRole
import com.TTT.Context.ConverseData
import com.TTT.Pipe.MultimodalContent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse

/**
 * TDD tests for ConverseHistoryHandle — the C ABI wrapper around TPipe's
 * ConverseHistory (a list of ConverseData turns).
 *
 * RED PHASE: These tests define expected behavior. Some may fail until the full
 * ABI implementation is complete. Following TDD: RED first, then GREEN.
 */
class ConverseHistoryHandleTest {

    //==========================================================================
    // Creation and Type Discriminator
    //==========================================================================

    @Test
    fun testCreate() {
        val history = ConverseHistory()
        val handle = ConverseHistoryHandle(history)
        assertNotNull(handle, "ConverseHistoryHandle constructor should return a non-null handle")
        assertEquals(0, handle.size(), "fresh ConverseHistoryHandle should be empty")
    }

    @Test
    fun testTypeDiscriminator() {
        // CONVERSE_HISTORY discriminator must match HandleTypes.CONVERSE_HISTORY (=8)
        assertEquals(8, HandleTypes.CONVERSE_HISTORY,
            "HandleTypes.CONVERSE_HISTORY should be 8")
    }

    //==========================================================================
    // add() Variants
    //==========================================================================

    @Test
    fun testAddRoleContent() {
        val history = ConverseHistory()
        val handle = ConverseHistoryHandle(history)
        val contentHandle = ContentHandle(text = "Hello, world!")
        handle.add(ConverseRole.user, contentHandle)
        assertEquals(1, handle.size(), "size should be 1 after one add")
        val turn = handle.get(0)
        assertNotNull(turn, "get(0) should return non-null ConverseData")
        assertEquals(ConverseRole.user, turn.role, "first turn role should be user")
    }

    @Test
    fun testAddConverseData() {
        val history = ConverseHistory()
        val handle = ConverseHistoryHandle(history)
        val converseData = ConverseData(
            role = ConverseRole.assistant,
            content = MultimodalContent("Hi there!")
        )
        converseData.setUUID()
        handle.converseHistory.add(converseData)
        assertEquals(1, handle.size(), "size should be 1 after adding ConverseData")
        val turn = handle.get(0)
        assertNotNull(turn, "get(0) should return the added ConverseData")
        assertEquals(ConverseRole.assistant, turn.role, "role should be assistant")
    }

    //==========================================================================
    // size() / isEmpty() / clear()
    //==========================================================================

    @Test
    fun testSize() {
        val history = ConverseHistory()
        val handle = ConverseHistoryHandle(history)
        assertEquals(0, handle.size(), "fresh handle should have size=0")
        val contentHandle = ContentHandle(text = "x")
        handle.add(ConverseRole.user, contentHandle)
        assertEquals(1, handle.size(), "size should be 1 after one add")
    }

    @Test
    fun testIsEmpty() {
        val history = ConverseHistory()
        val handle = ConverseHistoryHandle(history)
        assertTrue(handle.isEmpty(), "fresh handle should be empty")

        val contentHandle = ContentHandle(text = "x")
        handle.add(ConverseRole.user, contentHandle)
        assertFalse(handle.isEmpty(), "handle should not be empty after an add")
    }

    @Test
    fun testClear() {
        val history = ConverseHistory()
        val handle = ConverseHistoryHandle(history)
        val contentHandle = ContentHandle(text = "x")
        handle.add(ConverseRole.user, contentHandle)
        handle.add(ConverseRole.assistant, contentHandle)
        assertEquals(2, handle.size(), "size should be 2 before clear")

        handle.clear()
        assertEquals(0, handle.size(), "size should be 0 after clear")
        assertTrue(handle.isEmpty(), "handle should be empty after clear")
    }

    //==========================================================================
    // get(index) Indexing
    //==========================================================================

    @Test
    fun testGet() {
        val history = ConverseHistory()
        val handle = ConverseHistoryHandle(history)
        val userContent = ContentHandle(text = "user turn")
        val assistantContent = ContentHandle(text = "assistant turn")
        handle.add(ConverseRole.user, userContent)
        handle.add(ConverseRole.assistant, assistantContent)

        val first = handle.get(0)
        val second = handle.get(1)
        val outOfBounds = handle.get(99)

        assertNotNull(first, "get(0) should return non-null")
        assertNotNull(second, "get(1) should return non-null")
        assertNull(outOfBounds, "get(99) should return null for out-of-bounds index")
        assertEquals(ConverseRole.user, first.role, "first turn should be user")
        assertEquals(ConverseRole.assistant, second.role, "second turn should be assistant")
    }

    //==========================================================================
    // JSON Serialization
    //==========================================================================

    @Test
    fun testToJson() {
        val history = ConverseHistory()
        val handle = ConverseHistoryHandle(history)
        val contentHandle = ContentHandle(text = "Hello")
        handle.add(ConverseRole.user, contentHandle)

        val json = handle.toJson()
        assertNotNull(json, "toJson should return non-null string")
        assertTrue(json.contains("history"), "toJson should contain 'history' field, got: $json")
        assertTrue(json.contains("user"), "toJson should contain role 'user', got: $json")
        assertTrue(json.contains("Hello"), "toJson should contain the turn text, got: $json")
    }

    //==========================================================================
    // HandleRegistry Integration
    //==========================================================================

    @Test
    fun testRefCounting() {
        val history = ConverseHistory()
        val handle = ConverseHistoryHandle(history)
        val handleId = HandleRegistry.allocate(HandleTypes.CONVERSE_HISTORY, handle)
        assertTrue(handleId >= 0, "allocate() should return non-negative handle, got: $handleId")
        assertEquals(1, HandleRegistry.getRefCount(handleId),
            "newly allocated ConverseHistoryHandle should have refCount=1")

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
