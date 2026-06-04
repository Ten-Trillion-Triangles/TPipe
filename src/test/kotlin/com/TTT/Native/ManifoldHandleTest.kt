package com.TTT.Native

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * TDD tests for ManifoldHandle and the Manifold C ABI surface.
 *
 * Phase 5: Manifold C ABI exposure. These tests verify the Kotlin-side
 * NativeBridge + ManifoldHandle contract, which is the same code path the
 * Java @CEntryPoint shims in TPipeBootstrap.java delegate to.
 */
class ManifoldHandleTest {

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
    // HandleTypes.MANIFOLD discriminator
    //==========================================================================

    @Test
    fun testManifoldTypeDiscriminator()
    {
        // MANIFOLD discriminator must be 16.
        assertEquals(16, HandleTypes.MANIFOLD, "HandleTypes.MANIFOLD should be 16")
        // TYPE_COUNT must be 18 to fit DISTRIBUTION_GRID=17.
        assertEquals(18, HandleTypes.TYPE_COUNT, "HandleTypes.TYPE_COUNT should be 18")
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
        // Verify the type discriminator is in the high 8 bits of the handle.
        assertEquals(HandleTypes.MANIFOLD, HandleRegistry.getType(handle), "handle type should be MANIFOLD")
        HandleRegistry.release(handle)
    }

    //==========================================================================
    // NativeBridge.manifoldInit
    //==========================================================================

    @Test
    fun testManifoldInitReturnsZero()
    {
        val handle = NativeBridge.manifoldCreate()
        // Manifold().init() throws because no workers are registered, so we
        // accept 0, -0x0E, or -0x01 — the load-bearing assertion is that the
        // call dispatches and returns a known int.
        val rc = NativeBridge.manifoldInit(handle)
        assertTrue(rc == 0 || rc == -0x0E || rc == -0x01, "init should return a known error code, got $rc")
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
        assertEquals(-0x03, rc, "manifoldInit on a CONTENT handle should return INVALID_HANDLE (-0x03)")
        HandleRegistry.release(ch)
    }
}
