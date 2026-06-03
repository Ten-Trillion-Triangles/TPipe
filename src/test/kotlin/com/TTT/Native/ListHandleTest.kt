package com.TTT.Native

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * TDD tests for ListHandle — string-keyed list of uint64_t handle IDs.
 *
 * RED PHASE: These tests define expected behavior. Some may fail until the
 * full ABI implementation is complete. Following TDD: RED first, then GREEN.
 */
class ListHandleTest {

    //==========================================================================
    // Creation and Type Discriminator
    //==========================================================================

    @Test
    fun testCreate() {
        val list = ListHandle.create()
        assertNotNull(list, "create() should return a non-null ListHandle")
        assertTrue(list.isEmpty(), "new list should be empty")
        assertEquals(0, list.size(), "new list should have size 0")
    }

    @Test
    fun testTypeDiscriminator() {
        // LIST discriminator must match HandleTypes.LIST (=12)
        assertEquals(12, HandleTypes.LIST, "HandleTypes.LIST should be 12")
    }

    //==========================================================================
    // setCapacity / addItem / addString
    //==========================================================================

    @Test
    fun testSetCapacity() {
        val list = ListHandle.create()
        val result = list.setCapacity(64)
        assertSame(list, result, "setCapacity should return this for chaining")
    }

    @Test
    fun testAddItem() {
        val list = ListHandle.create().setCapacity(8)
        // Need a real handle ID. Allocate any content handle and use its ID.
        val contentHandle = HandleRegistry.allocate(HandleTypes.CONTENT, ContentHandle("a"))
        val result = list.addItem(contentHandle)
        assertSame(list, result, "addItem should return this for chaining")
        assertEquals(1, list.size(), "size should be 1 after addItem")
        assertEquals(contentHandle, list.get(0), "item at index 0 should match added handle")
        // cleanup
        HandleRegistry.release(contentHandle)
    }

    @Test
    fun testAddString() {
        val list = ListHandle.create().setCapacity(8)
        val result = list.addString("hello")
        assertSame(list, result, "addString should return this for chaining")
        assertEquals(1, list.size(), "size should be 1 after addString")
        val handleId = list.get(0)
        assertNotNull(handleId, "get(0) should return a handle ID for the added string")
        // The added string should be stored as a CONTENT handle.
        assertEquals(HandleTypes.CONTENT, HandleRegistry.getType(handleId),
            "string should be stored as CONTENT handle")
        // cleanup
        HandleRegistry.release(handleId)
    }

    //==========================================================================
    // build() — commits to HandleRegistry, returns handle ID
    //==========================================================================

    @Test
    fun testBuild() {
        val list = ListHandle.create().setCapacity(8)
        list.addString("alpha")
        list.addString("beta")
        val builtHandleId = list.build()
        assertTrue(builtHandleId >= 0, "build() should return non-negative handle")
        assertEquals(HandleTypes.LIST, HandleRegistry.getType(builtHandleId),
            "built handle should be of type LIST")
        val stored = HandleRegistry.getData(builtHandleId)
        assertSame(list, stored, "stored data should be the ListHandle instance")
        // cleanup
        HandleRegistry.release(builtHandleId)
    }

    @Test
    fun testBuildTwiceFails() {
        val list = ListHandle.create()
        list.build()
        assertFailsWith<IllegalStateException>("calling build() twice should throw") {
            list.build()
        }
    }

    //==========================================================================
    // get / size / isEmpty
    //==========================================================================

    @Test
    fun testGet() {
        val list = ListHandle.create().setCapacity(8)
        val h1 = HandleRegistry.allocate(HandleTypes.CONTENT, ContentHandle("first"))
        val h2 = HandleRegistry.allocate(HandleTypes.CONTENT, ContentHandle("second"))
        list.addItem(h1)
        list.addItem(h2)
        assertEquals(h1, list.get(0))
        assertEquals(h2, list.get(1))
        assertNull(list.get(99), "get out of bounds should return null")
        // cleanup
        HandleRegistry.release(h1)
        HandleRegistry.release(h2)
    }

    @Test
    fun testSize() {
        val list = ListHandle.create().setCapacity(8)
        assertEquals(0, list.size(), "empty list size should be 0")
        list.addString("a")
        assertEquals(1, list.size(), "size should be 1 after one addString")
        list.addString("b")
        list.addString("c")
        assertEquals(3, list.size(), "size should be 3 after three addString calls")
    }

    @Test
    fun testIsEmpty() {
        val list = ListHandle.create()
        assertTrue(list.isEmpty(), "new list should be empty")
        list.addString("a")
        assertEquals(false, list.isEmpty(), "list with one item should not be empty")
    }

    //==========================================================================
    // Reference Counting
    //==========================================================================

    @Test
    fun testRefCounting() {
        val list = ListHandle.create()
        list.addString("a")
        val builtHandleId = list.build()
        assertTrue(builtHandleId >= 0, "build() should return non-negative handle")
        assertEquals(1, HandleRegistry.getRefCount(builtHandleId), "new handle should have refCount=1")
        assertEquals(0, HandleRegistry.addRef(builtHandleId), "addRef should succeed")
        assertEquals(2, HandleRegistry.getRefCount(builtHandleId), "refCount should be 2 after addRef")
        // cleanup
        HandleRegistry.release(builtHandleId)
        HandleRegistry.release(builtHandleId)
    }
}
