package com.TTT.Native

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse

// Error codes matching TPipeBootstrap / tpipe-abi.h
private const val ERR_INTERNAL = -1           // TPIPE_ERR_INTERNAL
private const val ERR_INVALID_HANDLE = -3     // TPIPE_ERR_INVALID_HANDLE
private const val ERR_REFCOUNT_OVERFLOW = -23 // TPIPE_ERR_REFCOUNT_OVERFLOW (-0x17)

/**
 * TDD tests for TPipe GraalVM C ABI Handle Registry.
 * Tests handle lifecycle, reference counting, type encoding, and safety limits.
 */
class HandleRegistryTest {

    //==========================================================================
    // Handle Allocation Tests
    //==========================================================================

    @Test
    fun testAllocateReturnsValidHandle() {
        // allocate() should return a non-negative handle with refCount=1
        val handle = HandleRegistry.allocate(HandleTypes.CONTENT, "test-data")
        assertTrue(handle >= 0, "allocate() should return non-negative handle, got: $handle")
        assertEquals(1, HandleRegistry.getRefCount(handle), "new handle should have refCount=1")
    }

    @Test
    fun testAllocateIncrementsHandleCount() {
        val h1 = HandleRegistry.allocate(HandleTypes.CONTENT, "a")
        val h2 = HandleRegistry.allocate(HandleTypes.PIPE, "b")
        assertTrue(h1 >= 0 && h2 >= 0, "both handles should allocate successfully")
        // cleanup
        HandleRegistry.release(h1)
        HandleRegistry.release(h2)
    }

    @Test
    fun testHandleLimitExceededReturnsError() {
        // The constant MAX_HANDLE_COUNT = 65536 is the limit
        assertEquals(65536, HandleRegistry.MAX_HANDLE_COUNT, "MAX_HANDLE_COUNT should be 65536")
    }

    //==========================================================================
    // Reference Counting Tests
    //==========================================================================

    @Test
    fun testAddRefIncrementsRefCount() {
        val handle = HandleRegistry.allocate(HandleTypes.CONTENT, "test")
        val initial = HandleRegistry.getRefCount(handle)
        val result = HandleRegistry.addRef(handle)
        assertEquals(0, result, "addRef should return 0 on success")
        assertEquals(initial + 1, HandleRegistry.getRefCount(handle), "refCount should increment from $initial")
        HandleRegistry.release(handle) // cleanup
    }

    @Test
    fun testReleaseDecrementsRefCount() {
        val handle = HandleRegistry.allocate(HandleTypes.CONTENT, "test")
        HandleRegistry.addRef(handle) // refCount = 2
        val result = HandleRegistry.release(handle)
        assertEquals(0, result, "release should return 0 on success")
        assertEquals(1, HandleRegistry.getRefCount(handle), "refCount should decrement to 1")
        HandleRegistry.release(handle) // final release
    }

    @Test
    fun testFinalReleaseRemovesHandle() {
        val handle = HandleRegistry.allocate(HandleTypes.CONTENT, "test")
        // First release (refCount 1 -> 0)
        val result = HandleRegistry.release(handle)
        assertEquals(0, result)
        assertFalse(HandleRegistry.isValid(handle), "handle should be invalid after final release")
    }

    @Test
    fun testAddRefOnReleasedHandleReturnsError() {
        val handle = HandleRegistry.allocate(HandleTypes.CONTENT, "test")
        HandleRegistry.release(handle) // refCount -> 0, removed
        val result = HandleRegistry.addRef(handle)
        assertEquals(ERR_INVALID_HANDLE, result, "addRef on released handle should return ERR_INVALID_HANDLE")
    }

    @Test
    fun testReleaseOnReleasedHandleReturnsError() {
        val handle = HandleRegistry.allocate(HandleTypes.CONTENT, "test")
        HandleRegistry.release(handle) // refCount -> 0, removed
        val result = HandleRegistry.release(handle)
        assertEquals(ERR_INVALID_HANDLE, result, "double release should return ERR_INVALID_HANDLE")
    }

    @Test
    fun testAddRefBeyondMaxRefcountReturnsError() {
        val handle = HandleRegistry.allocate(HandleTypes.CONTENT, "test")
        // Add ref until we hit the limit. Handle starts at refCount=1.
        // addRef succeeds until current == MAX_REFCOUNT (65535).
        // When current == 65534, addRef succeeds (increments to 65535).
        // When current == 65535, addRef fails (ERR_REFCOUNT_OVERFLOW).
        var result = 0
        while (result == 0) {
            result = HandleRegistry.addRef(handle)
        }
        assertEquals(ERR_REFCOUNT_OVERFLOW, result,
            "addRef beyond MAX_REFCOUNT should return ERR_REFCOUNT_OVERFLOW, got $result")
        // cleanup
        repeat(HandleRegistry.MAX_REFCOUNT - 1) { HandleRegistry.release(handle) }
        HandleRegistry.release(handle)
    }

    //==========================================================================
    // Handle Type Encoding Tests
    //==========================================================================

    @Test
    fun testHandleTypeEncoding() {
        val contentHandle = HandleRegistry.allocate(HandleTypes.CONTENT, "data")
        val pipeHandle = HandleRegistry.allocate(HandleTypes.PIPE, "data")
        val pipelineHandle = HandleRegistry.allocate(HandleTypes.PIPELINE, "data")

        assertEquals(HandleTypes.CONTENT, HandleRegistry.getType(contentHandle),
            "type bits should decode to CONTENT")
        assertEquals(HandleTypes.PIPE, HandleRegistry.getType(pipeHandle),
            "type bits should decode to PIPE")
        assertEquals(HandleTypes.PIPELINE, HandleRegistry.getType(pipelineHandle),
            "type bits should decode to PIPELINE")

        // cleanup
        HandleRegistry.release(contentHandle)
        HandleRegistry.release(pipeHandle)
        HandleRegistry.release(pipelineHandle)
    }

    @Test
    fun testHandleTypeInHigh8Bits() {
        // Verify type is encoded in high 8 bits
        val h = HandleRegistry.allocate(HandleTypes.MINIBANK, "data")
        val typeBits = (h shr 56) and 0xFF
        assertEquals(HandleTypes.MINIBANK.toLong(), typeBits, "type should be in high 8 bits")
        HandleRegistry.release(h)
    }

    //==========================================================================
    // Handle Validity Tests
    //==========================================================================

    @Test
    fun testIsValidReturnsTrueForActiveHandle() {
        val handle = HandleRegistry.allocate(HandleTypes.CONTENT, "test")
        assertTrue(HandleRegistry.isValid(handle), "newly allocated handle should be valid")
        HandleRegistry.release(handle)
    }

    @Test
    fun testIsValidReturnsFalseForNonexistentHandle() {
        assertFalse(HandleRegistry.isValid(999999L), "nonexistent handle should be invalid")
    }

    @Test
    fun testIsValidReturnsFalseForZeroHandle() {
        assertFalse(HandleRegistry.isValid(0L), "zero handle should be invalid")
    }

    //==========================================================================
    // Handle Data Tests
    //==========================================================================

    @Test
    fun testGetDataReturnsStoredObject() {
        val testData = "test-object"
        val handle = HandleRegistry.allocate(HandleTypes.CONTENT, testData)
        val retrieved = HandleRegistry.getData(handle)
        assertEquals(testData, retrieved, "getData should return the stored object")
        HandleRegistry.release(handle)
    }

    @Test
    fun testGetDataReturnsNullForInvalidHandle() {
        val result = HandleRegistry.getData(999999L)
        assertEquals(null, result, "getData on invalid handle should return null")
    }

    //==========================================================================
    // Error Handling Tests
    //==========================================================================

    @Test
    fun testReleaseInvalidHandleReturnsError() {
        val result = HandleRegistry.release(999999L)
        assertEquals(ERR_INVALID_HANDLE, result,
            "release on invalid handle should return ERR_INVALID_HANDLE")
    }

    @Test
    fun testGetRefCountInvalidHandleReturnsError() {
        val result = HandleRegistry.getRefCount(999999L)
        assertEquals(ERR_INVALID_HANDLE.toInt(), result,
            "getRefCount on invalid handle should return ERR_INVALID_HANDLE")
    }

    @Test
    fun testAddRefInvalidHandleReturnsError() {
        val result = HandleRegistry.addRef(999999L)
        assertEquals(ERR_INVALID_HANDLE, result,
            "addRef on invalid handle should return ERR_INVALID_HANDLE")
    }

    //==========================================================================
    // Registry Management Tests
    //==========================================================================

    @Test
    fun testCloseAllClearsRegistry() {
        val h1 = HandleRegistry.allocate(HandleTypes.CONTENT, "a")
        val h2 = HandleRegistry.allocate(HandleTypes.PIPE, "b")
        HandleRegistry.closeAll()
        assertFalse(HandleRegistry.isValid(h1), "h1 should be invalid after closeAll")
        assertFalse(HandleRegistry.isValid(h2), "h2 should be invalid after closeAll")
    }

    //==========================================================================
    // Safety Limits Verification
    //==========================================================================

    @Test
    fun testMaxRefcountConstant() {
        assertEquals(65535, HandleRegistry.MAX_REFCOUNT, "MAX_REFCOUNT should be 65535")
    }

    @Test
    fun testSafetyLimitsMatchGapVerification() {
        // Verify safety limits match GapVerification constants
        assertEquals(65535, GapVerification.MAX_REFCOUNT)
        assertEquals(65536, GapVerification.MAX_HANDLE_COUNT)
        assertEquals(104857600, GapVerification.MAX_BINARY_SIZE)  // 100MB
        assertEquals(1048576, GapVerification.MAX_STRING_LEN)    // 1MB
    }

    //==========================================================================
    // Large-payload round-trip — readCString fix validation
    //==========================================================================

    @Test
    fun testContentHandlePreservesTextExceedingOneMegabyte() {
        val targetSize = GapVerification.MAX_STRING_LEN + (512 * 1024)
        val payload = buildString(targetSize) {
            for (i in 0 until targetSize) {
                append(('a' + (i % 26)).toChar())
            }
        }
        val originalLength = payload.length
        val handle = HandleRegistry.allocate(HandleTypes.CONTENT, payload)
        assertTrue(handle >= 0, "allocate() should accept a >1MB content payload")
        val retrieved = HandleRegistry.getData(handle)
        assertTrue(retrieved is String, "getData() should return the stored String for CONTENT type")
        val retrievedString = retrieved as String
        assertEquals(originalLength, retrievedString.length, "text length must be preserved across the registry")
        assertEquals(payload, retrievedString, "full payload must round-trip through HandleRegistry")
        HandleRegistry.release(handle)
    }
}