package com.TTT.Native

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * TDD tests for MapHandle — string-keyed map of uint64_t handle IDs.
 *
 * RED PHASE: These tests define expected behavior. Some may fail until the
 * full ABI implementation is complete. Following TDD: RED first, then GREEN.
 */
class MapHandleTest {

    //==========================================================================
    // Creation and Type Discriminator
    //==========================================================================

    @Test
    fun testCreate() {
        val map = MapHandle.create()
        assertNotNull(map, "create() should return a non-null MapHandle")
        assertTrue(map.isEmpty(), "new map should be empty")
        assertEquals(0, map.size(), "new map should have size 0")
    }

    @Test
    fun testTypeDiscriminator() {
        // MAP discriminator must match HandleTypes.MAP (=13)
        assertEquals(13, HandleTypes.MAP, "HandleTypes.MAP should be 13")
    }

    //==========================================================================
    // set / setString
    //==========================================================================

    @Test
    fun testSet() {
        val map = MapHandle.create()
        val valueHandle = HandleRegistry.allocate(HandleTypes.CONTENT, ContentHandle("v"))
        val result = map.set("key1", valueHandle)
        assertSame(map, result, "set should return this for chaining")
        assertEquals(valueHandle, map.get("key1"), "value should match what was set")
        // cleanup
        HandleRegistry.release(valueHandle)
    }

    @Test
    fun testSetString() {
        val map = MapHandle.create()
        val result = map.setString("name", "Alice")
        assertSame(map, result, "setString should return this for chaining")
        val storedHandle = map.get("name")
        assertNotNull(storedHandle, "get should return a handle ID for the set string")
        assertEquals(HandleTypes.CONTENT, HandleRegistry.getType(storedHandle),
            "string should be stored as CONTENT handle")
        // cleanup
        HandleRegistry.release(storedHandle)
    }

    //==========================================================================
    // get / has / size / isEmpty
    //==========================================================================

    @Test
    fun testGet() {
        val map = MapHandle.create()
        val h1 = HandleRegistry.allocate(HandleTypes.CONTENT, ContentHandle("v1"))
        map.set("alpha", h1)
        assertEquals(h1, map.get("alpha"))
        assertNull(map.get("nonexistent"), "get on missing key should return null")
        // cleanup
        HandleRegistry.release(h1)
    }

    @Test
    fun testHas() {
        val map = MapHandle.create()
        val h1 = HandleRegistry.allocate(HandleTypes.CONTENT, ContentHandle("v"))
        map.set("present", h1)
        assertTrue(map.has("present"), "has should return true for present key")
        assertFalse(map.has("absent"), "has should return false for absent key")
        // cleanup
        HandleRegistry.release(h1)
    }

    @Test
    fun testSize() {
        val map = MapHandle.create()
        assertEquals(0, map.size(), "empty map size should be 0")
        map.setString("a", "1")
        assertEquals(1, map.size(), "size should be 1 after one setString")
        map.setString("b", "2")
        map.setString("c", "3")
        assertEquals(3, map.size(), "size should be 3 after three setString calls")
    }

    @Test
    fun testIsEmpty() {
        val map = MapHandle.create()
        assertTrue(map.isEmpty(), "new map should be empty")
        map.setString("k", "v")
        assertFalse(map.isEmpty(), "map with one entry should not be empty")
    }

    //==========================================================================
    // build() — commits to HandleRegistry, returns handle ID
    //==========================================================================

    @Test
    fun testBuild() {
        val map = MapHandle.create()
        map.setString("greeting", "hello")
        map.setString("farewell", "goodbye")
        val builtHandleId = map.build()
        assertTrue(builtHandleId >= 0, "build() should return non-negative handle")
        assertEquals(HandleTypes.MAP, HandleRegistry.getType(builtHandleId),
            "built handle should be of type MAP")
        val stored = HandleRegistry.getData(builtHandleId)
        assertSame(map, stored, "stored data should be the MapHandle instance")
        // cleanup
        HandleRegistry.release(builtHandleId)
    }

    @Test
    fun testBuildTwiceFails() {
        val map = MapHandle.create()
        map.build()
        assertFailsWith<IllegalStateException>("calling build() twice should throw") {
            map.build()
        }
    }

    //==========================================================================
    // Reference Counting
    //==========================================================================

    @Test
    fun testRefCounting() {
        val map = MapHandle.create()
        map.setString("k", "v")
        val builtHandleId = map.build()
        assertTrue(builtHandleId >= 0, "build() should return non-negative handle")
        assertEquals(1, HandleRegistry.getRefCount(builtHandleId), "new handle should have refCount=1")
        assertEquals(0, HandleRegistry.addRef(builtHandleId), "addRef should succeed")
        assertEquals(2, HandleRegistry.getRefCount(builtHandleId), "refCount should be 2 after addRef")
        // cleanup
        HandleRegistry.release(builtHandleId)
        HandleRegistry.release(builtHandleId)
    }
}
