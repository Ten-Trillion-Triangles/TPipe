package com.TTT.Native

import com.TTT.Context.MiniBank
import com.TTT.Context.ContextWindow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * TDD tests for MiniBankHandle — the C ABI wrapper around TPipe's MiniBank
 * (a multi-page context container with contextMap: Map<String, ContextWindow>).
 *
 * RED PHASE: These tests define expected behavior. Some may fail until the full
 * ABI implementation is complete. Following TDD: RED first, then GREEN.
 */
class MiniBankHandleTest {

    //==========================================================================
    // Creation and Type Discriminator
    //==========================================================================

    @Test
    fun testCreate() {
        val miniBank = MiniBank()
        val handle = MiniBankHandle(miniBank)
        assertNotNull(handle, "MiniBankHandle constructor should return a non-null handle")
        assertTrue(handle.isEmpty(), "fresh MiniBankHandle should be empty")
        assertEquals(0, handle.pageCount(), "fresh MiniBankHandle should have 0 pages")
    }

    @Test
    fun testTypeDiscriminator() {
        // MINIBANK discriminator must match HandleTypes.MINIBANK (=6)
        assertEquals(6, HandleTypes.MINIBANK, "HandleTypes.MINIBANK should be 6")
    }

    //==========================================================================
    // isEmpty() / clear() / pageCount() / getPageKeys()
    //==========================================================================

    @Test
    fun testIsEmpty() {
        val handle = MiniBankHandle(MiniBank())
        assertTrue(handle.isEmpty(), "fresh handle should report empty")

        handle.getOrCreatePage("page-a")
        assertEquals(false, handle.isEmpty(),
            "handle should not be empty after creating a page")
    }

    @Test
    fun testClear() {
        val handle = MiniBankHandle(MiniBank())
        handle.getOrCreatePage("page-a")
        handle.getOrCreatePage("page-b")
        assertEquals(2, handle.pageCount(), "should have 2 pages before clear")

        handle.clear()
        assertEquals(0, handle.pageCount(), "should have 0 pages after clear")
        assertTrue(handle.isEmpty(), "should report empty after clear")
    }

    @Test
    fun testPageCount() {
        val handle = MiniBankHandle(MiniBank())
        assertEquals(0, handle.pageCount(), "fresh handle should have pageCount=0")

        handle.getOrCreatePage("page-a")
        assertEquals(1, handle.pageCount(), "pageCount should be 1 after one getOrCreatePage")
        handle.getOrCreatePage("page-b")
        assertEquals(2, handle.pageCount(), "pageCount should be 2 after two getOrCreatePage calls")
    }

    @Test
    fun testGetPageKeys() {
        val handle = MiniBankHandle(MiniBank())
        assertEquals(emptyList(), handle.getPageKeys(),
            "fresh handle should return empty key list")

        handle.getOrCreatePage("page-a")
        handle.getOrCreatePage("page-b")
        val keys = handle.getPageKeys()
        assertEquals(2, keys.size, "should return 2 keys")
        assertTrue("page-a" in keys, "keys should contain page-a, got: $keys")
        assertTrue("page-b" in keys, "keys should contain page-b, got: $keys")
    }

    //==========================================================================
    // getOrCreatePage()
    //==========================================================================

    @Test
    fun testGetOrCreatePage() {
        val handle = MiniBankHandle(MiniBank())

        val pageA = handle.getOrCreatePage("page-a")
        assertNotNull(pageA, "getOrCreatePage should return non-null ContextWindow")

        val pageAReturned = handle.getOrCreatePage("page-a")
        assertSame(pageA, pageAReturned,
            "second getOrCreatePage for same key should return the same ContextWindow")

        val pageB = handle.getOrCreatePage("page-b")
        assertNotSame(pageA, pageB,
            "getOrCreatePage for a new key should return a different ContextWindow")
    }

    //==========================================================================
    // merge() Two MiniBanks
    //==========================================================================

    @Test
    fun testMerge() {
        val handleA = MiniBankHandle(MiniBank())
        val handleB = MiniBankHandle(MiniBank())

        handleA.getOrCreatePage("page-a")
        handleB.getOrCreatePage("page-b")
        handleB.getOrCreatePage("page-c")

        handleA.merge(handleB)
        val mergedKeys = handleA.getPageKeys()
        assertEquals(3, mergedKeys.size, "merged MiniBank should have 3 pages")
        assertTrue("page-a" in mergedKeys, "merge should retain page-a")
        assertTrue("page-b" in mergedKeys, "merge should add page-b")
        assertTrue("page-c" in mergedKeys, "merge should add page-c")
    }

    //==========================================================================
    // HandleRegistry Integration
    //==========================================================================

    @Test
    fun testRefCounting() {
        val miniBank = MiniBank()
        val handle = MiniBankHandle(miniBank)
        val handleId = HandleRegistry.allocate(HandleTypes.MINIBANK, handle)
        assertTrue(handleId >= 0, "allocate() should return non-negative handle, got: $handleId")
        assertEquals(1, HandleRegistry.getRefCount(handleId),
            "newly allocated MiniBankHandle should have refCount=1")

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
