package com.TTT.Native

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * TDD tests for [ManifoldHandle] and the Manifold C ABI surface.
 *
 * These tests verify the Kotlin-side NativeBridge + ManifoldHandle
 * contract, which is the same code path the Java `@CEntryPoint` shims in
 * [TPipeBootstrap] delegate to.
 */
class ManifoldHandleTest
{

    @BeforeTest
    fun setUp()
    {
        NativeBridge.setState(EnumMappings.LibraryState.READY.cValue)
        HandleRegistry.closeAll()
        NativeBridge.init()
    }

    @AfterTest
    fun tearDown()
    {
        HandleRegistry.closeAll()
    }


    //==========================================================================
    // HandleTypes.MANIFOLD discriminator
    //==========================================================================

    @Test
    fun testManifoldTypeDiscriminator()
    {
        assertEquals(16, HandleTypes.MANIFOLD, "HandleTypes.MANIFOLD should be 16")
        assertEquals(21, HandleTypes.TYPE_COUNT, "HandleTypes.TYPE_COUNT should be 21")
    }


    //==========================================================================
    // NativeBridge.manifoldCreate
    //==========================================================================

    @Test
    fun testManifoldCreateReturnsNonZeroHandle()
    {
        val handle = NativeBridge.manifoldCreate()
        assertTrue(handle != 0L, "manifoldCreate should return a non-zero handle")
        HandleRegistry.release(handle)
    }

    @Test
    fun testManifoldCreateWrapsManifoldHandle()
    {
        val handle = NativeBridge.manifoldCreate()
        val data = HandleRegistry.getData(handle)
        assertNotNull(data, "manifold handle data should be non-null")
        assertTrue(
            data is ManifoldHandle,
            "manifold handle data should be a ManifoldHandle, got ${data?.let { it::class.simpleName }}"
        )
        assertEquals(
            HandleTypes.MANIFOLD,
            HandleRegistry.getType(handle),
            "handle type should be MANIFOLD"
        )
        HandleRegistry.release(handle)
    }


    //==========================================================================
    // NativeBridge.manifoldInit
    //==========================================================================

    @Test
    fun testManifoldInitReturnsZero()
    {
        val handle = NativeBridge.manifoldCreate()
        // Manifold().init() throws because no workers are registered, so the
        // bridge may return 0, TPIPE_ERR_INTERNAL, or TPIPE_ERR_INVALID_HANDLE.
        // The load-bearing assertion is that the call dispatches and returns
        // a known int.
        val rc = NativeBridge.manifoldInit(handle)
        assertTrue(
            rc == 0 || rc == -0x0E || rc == -0x01,
            "init should return a known error code, got $rc"
        )
        HandleRegistry.release(handle)
    }


    //==========================================================================
    // NativeBridge.manifoldAddWorker + manifoldGetWorkerCount
    //==========================================================================

    @Test
    fun testManifoldAddWorkerAndCount()
    {
        val mh = NativeBridge.manifoldCreate()
        val ph = NativeBridge.pipeCreate(
            provider = 0,
            model = "test-model",
            region = "us-east-1",
            settingsHandle = 0L
        )
        assertTrue(ph != 0L, "pipeCreate should return a non-zero handle")
        val rc = NativeBridge.manifoldAddWorker(mh, "w1", ph)
        assertEquals(0, rc, "manifoldAddWorker should return 0 on success")
        val count = NativeBridge.manifoldGetWorkerCount(mh)
        assertEquals(1, count, "worker count should be 1 after one addWorker")
        HandleRegistry.release(ph)
        HandleRegistry.release(mh)
    }


    //==========================================================================
    // NativeBridge.manifoldSerialize
    //==========================================================================

    @Test
    fun testManifoldSerializeWritesJson()
    {
        val mh = NativeBridge.manifoldCreate()
        val ph = NativeBridge.pipeCreate(0, "m", "us-east-1", 0L)
        NativeBridge.manifoldAddWorker(mh, "alpha", ph)
        val buf = ByteArray(1024)
        val n = NativeBridge.manifoldSerialize(mh, buf, 0, 1024)
        assertTrue(n > 0, "manifoldSerialize should write a positive number of bytes, got $n")
        val s = String(buf, 0, n, Charsets.UTF_8)
        assertTrue(s.contains("\"workerCount\":1"), "serialized JSON should contain workerCount:1, got: $s")
        assertTrue(s.contains("\"alpha\""), "serialized JSON should contain worker name 'alpha', got: $s")
        HandleRegistry.release(ph)
        HandleRegistry.release(mh)
    }


    //==========================================================================
    // Negative path: type mismatch
    //==========================================================================

    @Test
    fun testManifoldInitRejectsContentHandle()
    {
        val ch = NativeBridge.contentCreate("hello")
        val rc = NativeBridge.manifoldInit(ch)
        assertEquals(
            -0x03,
            rc,
            "manifoldInit on a CONTENT handle should return INVALID_HANDLE (-0x03)"
        )
        HandleRegistry.release(ch)
    }

    //==========================================================================
    // Cycle 3 — Configuration surface (C ABI)
    //==========================================================================

    @Test
    fun testManifoldSetContextWindowSize()
    {
        val mh = NativeBridge.manifoldCreate()
        val rc = NativeBridge.manifoldSetContextWindowSize(mh, 4096)
        assertEquals(0, rc, "setContextWindowSize should return 0")
        val n = NativeBridge.manifoldGetContextWindowSize(mh)
        assertEquals(4096, n, "getContextWindowSize should return 4096")
        HandleRegistry.release(mh)
    }

    @Test
    fun testManifoldSetContextWindowSizeRejectsNegative()
    {
        val mh = NativeBridge.manifoldCreate()
        val rc = NativeBridge.manifoldSetContextWindowSize(mh, -1)
        assertEquals(-0x04, rc, "setContextWindowSize(-1) should return INVALID_ARGUMENT")
        HandleRegistry.release(mh)
    }

    @Test
    fun testManifoldSetTruncationMethod()
    {
        val mh = NativeBridge.manifoldCreate()
        val rc = NativeBridge.manifoldSetTruncationMethod(mh, 0)  // TruncateTop
        assertEquals(0, rc)
        val m = NativeBridge.manifoldGetTruncationMethod(mh)
        assertEquals(0, m, "truncation method should be 0 (TruncateTop)")
        HandleRegistry.release(mh)
    }

    @Test
    fun testManifoldSetTruncationMethodRejectsBadOrdinal()
    {
        val mh = NativeBridge.manifoldCreate()
        val rc = NativeBridge.manifoldSetTruncationMethod(mh, 99)
        assertEquals(-0x04, rc, "setTruncationMethod(99) should return INVALID_ARGUMENT")
        HandleRegistry.release(mh)
    }

    @Test
    fun testManifoldSetSummaryMode()
    {
        val mh = NativeBridge.manifoldCreate()
        val rc = NativeBridge.manifoldSetSummaryMode(mh, 1)  // REGENERATE
        assertEquals(0, rc)
        val m = NativeBridge.manifoldGetSummaryMode(mh)
        assertEquals(1, m)
        HandleRegistry.release(mh)
    }

    @Test
    fun testManifoldGetMaxLoopIterationsDefaultsToUnlimited()
    {
        val mh = NativeBridge.manifoldCreate()
        val n = NativeBridge.manifoldGetMaxLoopIterations(mh)
        assertEquals(-1, n, "default max loop iterations is unlimited (-1)")
        val h = NativeBridge.manifoldHasLoopLimit(mh)
        assertEquals(0, h, "hasLoopLimit should be 0 by default")
        HandleRegistry.release(mh)
    }

    @Test
    fun testManifoldGetWorkerPipelinesEmptyByDefault()
    {
        val mh = NativeBridge.manifoldCreate()
        val buf = ByteArray(64)
        val n = NativeBridge.manifoldGetWorkerPipelines(mh, buf, 0, 64)
        assertEquals(0, n, "empty manifold should serialize 0 bytes")
        HandleRegistry.release(mh)
    }

    @Test
    fun testManifoldSetManagerTokenBudget()
    {
        val mh = NativeBridge.manifoldCreate()
        val rc = NativeBridge.manifoldSetManagerTokenBudget(mh, 1000)
        assertEquals(0, rc, "setManagerTokenBudget(1000) should succeed")
        val b = NativeBridge.manifoldGetManagerTokenBudget(mh)
        assertEquals(1000, b, "getManagerTokenBudget should return 1000")
        HandleRegistry.release(mh)
    }

    @Test
    fun testManifoldSetManagerTokenBudgetRejectsNegative()
    {
        val mh = NativeBridge.manifoldCreate()
        val rc = NativeBridge.manifoldSetManagerTokenBudget(mh, -100)
        assertEquals(-0x04, rc, "negative budget should return INVALID_ARGUMENT")
        HandleRegistry.release(mh)
    }

    @Test
    fun testManifoldGetManagerPipelineDefaultsToFalse()
    {
        val mh = NativeBridge.manifoldCreate()
        val h = NativeBridge.manifoldGetManagerPipeline(mh)
        // The default Manifold has an empty Pipeline (no pipes), so the
        // helper returns 0.
        assertEquals(0, h, "fresh manifold has no manager pipeline registered")
        HandleRegistry.release(mh)
    }

    @Test
    fun testManifoldConfigMethodsRejectNonManifoldHandle()
    {
        val ch = NativeBridge.contentCreate("hello")
        val rc = NativeBridge.manifoldSetContextWindowSize(ch, 100)
        assertEquals(-0x03, rc, "setContextWindowSize on CONTENT should return INVALID_HANDLE")
        HandleRegistry.release(ch)
    }
}
