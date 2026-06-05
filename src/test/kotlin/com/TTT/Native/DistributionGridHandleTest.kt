package com.TTT.Native

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * TDD tests for [DistributionGridHandle] and the DistributionGrid C ABI surface.
 *
 * These tests verify the Kotlin-side NativeBridge + DistributionGridHandle
 * contract, which is the same code path the Java `@CEntryPoint` shims in
 * [TPipeBootstrap] delegate to.
 *
 * These tests cover the 6 symbols exposed by the C ABI. The full 240+ method
 * DistributionGrid (distributed node routing, P2P registry wiring) is not
 * bound by the C ABI.
 *
 * @see [DistributionGridHandle]
 * @see [NativeBridge]
 */
class DistributionGridHandleTest {

    @BeforeTest
    fun setUp()
    {
        NativeBridge.setState(EnumMappings.LibraryState.READY.cValue)
        HandleRegistry.closeAll()
        NativeBridge.init()
    }

    @AfterTest
    fun tearDown() {
        HandleRegistry.closeAll()
    }

    //==========================================================================
    // HandleTypes.DISTRIBUTION_GRID discriminator
    //==========================================================================

    @Test
    fun testDistributionGridTypeDiscriminator()
    {
        assertEquals(17, HandleTypes.DISTRIBUTION_GRID, "HandleTypes.DISTRIBUTION_GRID should be 17")
        assertEquals(21, HandleTypes.TYPE_COUNT, "HandleTypes.TYPE_COUNT should be 21")
    }

    //==========================================================================
    // NativeBridge.distributionGridCreate
    //==========================================================================

    @Test
    fun distributionGridCreateReturnsNonZeroHandle()
    {
        val handle = NativeBridge.distributionGridCreate()
        assertTrue(handle != 0L, "distributionGridCreate should return a non-zero handle")
        HandleRegistry.release(handle)
    }

    @Test
    fun distributionGridHandleDataIsDistributionGridHandle()
    {
        val handle = NativeBridge.distributionGridCreate()
        val data = HandleRegistry.getData(handle)
        assertNotNull(data, "DistributionGrid handle data should be non-null")
        assertTrue(
            data is DistributionGridHandle,
            "handle data should be a DistributionGridHandle, got ${data?.let { it::class.simpleName }}"
        )
        // Verify the type discriminator is in the high 8 bits of the handle.
        assertEquals(
            HandleTypes.DISTRIBUTION_GRID,
            HandleRegistry.getType(handle),
            "handle type should be DISTRIBUTION_GRID"
        )
        HandleRegistry.release(handle)
    }

    //==========================================================================
    // NativeBridge.distributionGridGetNodeCount
    //==========================================================================

    @Test
    fun distributionGridGetNodeCountReturnsInt()
    {
        val handle = NativeBridge.distributionGridCreate()
        val count = NativeBridge.distributionGridGetNodeCount(handle)
        assertEquals(0, count, "stub should always return 0 nodes")
        HandleRegistry.release(handle)
    }

    //==========================================================================
    // NativeBridge.distributionGridSerialize
    //==========================================================================

    @Test
    fun distributionGridSerializeWritesJson()
    {
        val handle = NativeBridge.distributionGridCreate()
        val buf = ByteArray(256)
        val n = NativeBridge.distributionGridSerialize(handle, buf, 0, 256)
        assertTrue(n > 0, "serialize should write a positive number of bytes, got $n")
        val s = String(buf, 0, n, Charsets.UTF_8)
        assertEquals(
            "{\"nodeCount\":0,\"status\":\"stub\"}",
            s,
            "stub serialize should return the fixed JSON sentinel"
        )
        HandleRegistry.release(handle)
    }

    //==========================================================================
    // NativeBridge.distributionGridGetHealth
    //==========================================================================

    @Test
    fun distributionGridGetHealthReturnsString()
    {
        val handle = NativeBridge.distributionGridCreate()
        val buf = ByteArray(32)
        val n = NativeBridge.distributionGridGetHealth(handle, buf, 0, 32)
        assertTrue(n > 0, "getHealth should write a positive number of bytes, got $n")
        val s = String(buf, 0, n, Charsets.UTF_8)
        assertEquals("ok", s, "stub getHealth should return 'ok'")
        HandleRegistry.release(handle)
    }

    //==========================================================================
    // NativeBridge.distributionGridRebalanceStub
    //==========================================================================

    @Test
    fun distributionGridRebalanceStubReturnsSentinel()
    {
        val handle = NativeBridge.distributionGridCreate()
        val buf = ByteArray(128)
        val n = NativeBridge.distributionGridRebalanceStub(handle, buf, 0, 128)
        assertTrue(n > 0, "rebalanceStub should write a positive number of bytes, got $n")
        val s = String(buf, 0, n, Charsets.UTF_8)
        assertEquals(
            "rebalance not yet implemented (stub)",
            s,
            "rebalanceStub should return the fixed sentinel string"
        )
        HandleRegistry.release(handle)
    }

    //==========================================================================
    // NativeBridge.distributionGridRelease — negative path
    //==========================================================================

    @Test
    fun distributionGridReleaseRejectsContentHandle()
    {
        val ch = NativeBridge.contentCreate("hello")
        val rc = NativeBridge.distributionGridRelease(ch)
        assertEquals(
            -0x03,
            rc,
            "distributionGridRelease on a CONTENT handle should return INVALID_HANDLE (-0x03)"
        )
        HandleRegistry.release(ch)
    }

    @Test
    fun distributionGridGetNodeCountRejectsContentHandle()
    {
        val ch = NativeBridge.contentCreate("hello")
        val rc = NativeBridge.distributionGridGetNodeCount(ch)
        assertEquals(
            -0x03,
            rc,
            "distributionGridGetNodeCount on a CONTENT handle should return INVALID_HANDLE (-0x03)"
        )
        HandleRegistry.release(ch)
    }
}
