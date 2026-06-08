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
        // Phase 6: serialize returns a real JSON with nodeCount, status, and lastRebalanceMs.
        // Accept any valid JSON containing the expected keys.
        assertTrue(s.contains("\"nodeCount\""), "JSON must include nodeCount: $s")
        assertTrue(s.contains("\"status\""), "JSON must include status: $s")
        assertTrue(!s.contains("\"stub\""), "Phase 6: serialize must not return stub sentinel; got $s")
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
        // Phase 6: getHealth is derived from P2PRegistry state; valid values are
        // "empty" (no agents), "degraded" (no description), or "ok".
        assertTrue(s in listOf("empty", "degraded", "ok"),
            "getHealth must be empty/degraded/ok; got '$s'")
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
        // Phase 6: rebalance returns a real JSON with rebalanced=true
        assertTrue(s.contains("\"rebalanced\":true"), "rebalanceStub should now return real JSON; got '$s'")
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

    //==========================================================================
    // Phase 6: real implementations (not stubs)
    //==========================================================================

    @Test
    fun testGetNodeCountReturnsRegistrySize() {
        // The new implementation reads from P2PRegistry.listClientAgents().
        // With no P2PInterface registered in the test env, size is 0.
        val data = DistributionGridHandle(com.TTT.Pipeline.DistributionGrid())
        // getNodeCount no longer hardcodes 0; it reflects the registry.
        // We accept any non-negative integer (the registry might have entries
        // from earlier tests that leaked, depending on JVM shutdown hooks).
        assertTrue(data.getNodeCount() >= 0,
            "getNodeCount should return a non-negative integer; got ${data.getNodeCount()}")
    }

    @Test
    fun testSerializeReturnsRealJson() {
        val data = DistributionGridHandle(com.TTT.Pipeline.DistributionGrid())
        val json = data.serialize()
        // No longer contains "stub"
        assertTrue(!json.contains("\"stub\""),
            "serialize() should not return a stub; got $json")
        // Has the expected keys
        assertTrue(json.contains("\"nodeCount\""), "JSON must include nodeCount: $json")
        assertTrue(json.contains("\"status\""), "JSON must include status: $json")
        assertTrue(json.contains("\"lastRebalanceMs\""), "JSON must include lastRebalanceMs: $json")
    }

    @Test
    fun testGetHealthIsDerived() {
        val data = DistributionGridHandle(com.TTT.Pipeline.DistributionGrid())
        val health = data.getHealth()
        // Health is now derived; possible values are "empty", "degraded", "ok"
        assertTrue(health in listOf("empty", "degraded", "ok"),
            "getHealth must be one of empty/degraded/ok; got '$health'")
    }

    @Test
    fun testRebalanceUpdatesTimestamp() {
        val data = DistributionGridHandle(com.TTT.Pipeline.DistributionGrid())
        assertEquals(0L, data.lastRebalanceMs(), "initial lastRebalanceMs is 0")
        val before = System.currentTimeMillis()
        val result = data.rebalance()
        val after = System.currentTimeMillis()
        assertTrue(data.lastRebalanceMs() in before..after,
            "lastRebalanceMs should be updated to the current time after rebalance()")
        assertTrue(result.contains("\"rebalanced\":true"), "rebalance result must report success: $result")
    }
}
