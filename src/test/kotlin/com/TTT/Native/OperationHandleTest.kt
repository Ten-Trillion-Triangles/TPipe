package com.TTT.Native

import com.TTT.Native.EnumMappings.OperationStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * TDD tests for OperationHandle — async operation result wrapper.
 *
 * RED PHASE: These tests define expected behavior. Some may fail until the
 * full ABI implementation is complete. Following TDD: RED first, then GREEN.
 */
class OperationHandleTest {

    //==========================================================================
    // Type Discriminator
    //==========================================================================

    @Test
    fun testTypeDiscriminator() {
        // OPERATION discriminator must match HandleTypes.OPERATION (=15)
        assertEquals(15, HandleTypes.OPERATION, "HandleTypes.OPERATION should be 15")
    }

    //==========================================================================
    // Status Construction
    //==========================================================================

    @Test
    fun testCreatePending() {
        val op = OperationHandle(OperationStatus.PENDING, 0L, null)
        assertEquals(OperationStatus.PENDING, op.status)
        assertEquals(0L, op.resultHandle)
        assertNull(op.errorMessage)
    }

    @Test
    fun testCreateComplete() {
        val op = OperationHandle(OperationStatus.COMPLETE, 42L, null)
        assertEquals(OperationStatus.COMPLETE, op.status)
        assertEquals(42L, op.resultHandle)
        assertNull(op.errorMessage)
    }

    @Test
    fun testCreateFailed() {
        val op = OperationHandle(OperationStatus.FAILED, 0L, "something went wrong")
        assertEquals(OperationStatus.FAILED, op.status)
        assertEquals(0L, op.resultHandle)
        assertEquals("something went wrong", op.errorMessage)
    }

    //==========================================================================
    // Status Predicates
    //==========================================================================

    @Test
    fun testPoll() {
        val pending = OperationHandle(OperationStatus.PENDING, 0L, null)
        val complete = OperationHandle(OperationStatus.COMPLETE, 1L, null)
        val failed = OperationHandle(OperationStatus.FAILED, 0L, "err")
        assertEquals(OperationStatus.PENDING, pending.poll())
        assertEquals(OperationStatus.COMPLETE, complete.poll())
        assertEquals(OperationStatus.FAILED, failed.poll())
    }

    @Test
    fun testIsDone() {
        val pending = OperationHandle(OperationStatus.PENDING, 0L, null)
        val complete = OperationHandle(OperationStatus.COMPLETE, 1L, null)
        val failed = OperationHandle(OperationStatus.FAILED, 0L, "err")
        assertFalse(pending.isDone(), "PENDING is not done")
        assertTrue(complete.isDone(), "COMPLETE is done")
        assertTrue(failed.isDone(), "FAILED is done")
    }

    @Test
    fun testIsSuccessful() {
        val pending = OperationHandle(OperationStatus.PENDING, 0L, null)
        val complete = OperationHandle(OperationStatus.COMPLETE, 1L, null)
        val failed = OperationHandle(OperationStatus.FAILED, 0L, "err")
        assertFalse(pending.isSuccessful(), "PENDING is not successful")
        assertTrue(complete.isSuccessful(), "COMPLETE is successful")
        assertFalse(failed.isSuccessful(), "FAILED is not successful")
    }

    @Test
    fun testIsFailed() {
        val pending = OperationHandle(OperationStatus.PENDING, 0L, null)
        val complete = OperationHandle(OperationStatus.COMPLETE, 1L, null)
        val failed = OperationHandle(OperationStatus.FAILED, 0L, "err")
        assertFalse(pending.isFailed(), "PENDING is not failed")
        assertFalse(complete.isFailed(), "COMPLETE is not failed")
        assertTrue(failed.isFailed(), "FAILED is failed")
    }

    //==========================================================================
    // Result / Error
    //==========================================================================

    @Test
    fun testGetResult() {
        val complete = OperationHandle(OperationStatus.COMPLETE, 1234L, null)
        val pending = OperationHandle(OperationStatus.PENDING, 0L, null)
        val failed = OperationHandle(OperationStatus.FAILED, 0L, "err")
        assertEquals(1234L, complete.getResult(), "complete result handle should be returned")
        assertEquals(0L, pending.getResult(), "pending result should be 0")
        assertEquals(0L, failed.getResult(), "failed result should be 0")
    }

    @Test
    fun testGetError() {
        val failed = OperationHandle(OperationStatus.FAILED, 0L, "bad things")
        val complete = OperationHandle(OperationStatus.COMPLETE, 1L, null)
        val pending = OperationHandle(OperationStatus.PENDING, 0L, null)
        assertEquals("bad things", failed.getError())
        assertNull(complete.getError(), "successful op should have no error")
        assertNull(pending.getError(), "pending op should have no error")
    }

    //==========================================================================
    // Cancel
    //==========================================================================

    @Test
    fun testCancel() {
        val op = OperationHandle(OperationStatus.PENDING, 0L, null)
        val cancelled = op.cancel()
        assertTrue(cancelled, "cancel on pending should return true")
        assertEquals(OperationStatus.FAILED, op.status, "cancelled op should be FAILED")
        assertEquals("Cancelled by caller", op.errorMessage, "cancelled op should have error message")
    }

    @Test
    fun testCancelOnCompleteFails() {
        val op = OperationHandle(OperationStatus.COMPLETE, 1L, null)
        val cancelled = op.cancel()
        assertFalse(cancelled, "cancel on complete should return false")
        assertEquals(OperationStatus.COMPLETE, op.status, "status should not change")
    }

    @Test
    fun testCancelOnFailedFails() {
        val op = OperationHandle(OperationStatus.FAILED, 0L, "prior error")
        val cancelled = op.cancel()
        assertFalse(cancelled, "cancel on failed should return false")
        assertEquals(OperationStatus.FAILED, op.status)
    }

    //==========================================================================
    // Reference Counting
    //==========================================================================

    @Test
    fun testRefCounting() {
        val op = OperationHandle(OperationStatus.PENDING, 0L, null)
        val handleId = HandleRegistry.allocate(HandleTypes.OPERATION, op)
        assertTrue(handleId >= 0, "allocate should return non-negative handle")
        assertEquals(1, HandleRegistry.getRefCount(handleId), "new handle should have refCount=1")
        assertEquals(0, HandleRegistry.addRef(handleId), "addRef should succeed")
        assertEquals(2, HandleRegistry.getRefCount(handleId), "refCount should be 2 after addRef")
        // cleanup
        HandleRegistry.release(handleId)
        HandleRegistry.release(handleId)
    }
}
