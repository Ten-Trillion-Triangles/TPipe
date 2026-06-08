package com.TTT.Native

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * TDD tests for [ConnectorHandle] and the Connector C ABI surface.
 *
 * These tests verify the Kotlin-side NativeBridge + ConnectorHandle
 * contract, which is the same code path the Java `@CEntryPoint` shims in
 * [TPipeBootstrap] delegate to.
 */
class ConnectorHandleTest
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
    // Cycle 3 — Configuration surface
    //==========================================================================

    @Test
    fun testConnectorAdd()
    {
        val ch = NativeBridge.connectorCreate()
        val ph = NativeBridge.pipelineCreate()
        val rc = NativeBridge.connectorAdd(ch, "default", ph)
        assertEquals(0, rc, "connectorAdd should return 0 on success")
        HandleRegistry.release(ph)
        HandleRegistry.release(ch)
    }

    @Test
    fun testConnectorGetReturnsZeroForUnknownKey()
    {
        val ch = NativeBridge.connectorCreate()
        val ph = NativeBridge.connectorGet(ch, "missing")
        assertEquals(0L, ph, "get on missing key should return 0")
        HandleRegistry.release(ch)
    }

    @Test
    fun testConnectorGetReturnsHandleForKnownKey()
    {
        val ch = NativeBridge.connectorCreate()
        val ph = NativeBridge.pipelineCreate()
        NativeBridge.connectorAdd(ch, "default", ph)
        val got = NativeBridge.connectorGet(ch, "default")
        // Note: connectorGet allocates a fresh handle wrapping the same
        // Pipeline object that was registered via connectorAdd. The handles
        // are distinct but point to the same data.
        assertTrue(got != 0L, "get on known key should return a non-zero handle")
        assertTrue(got != ph, "get should return a new handle, not reuse ph")
        // Both ph and got should map to PipelineHandle instances.
        val phData = HandleRegistry.getData(ph)
        val gotData = HandleRegistry.getData(got)
        assertTrue(phData is PipelineHandle)
        assertTrue(gotData is PipelineHandle)
        assertEquals(
            (phData as PipelineHandle).pipeline,
            (gotData as PipelineHandle).pipeline,
            "both handles should point to the same Pipeline instance"
        )
        HandleRegistry.release(got)
        HandleRegistry.release(ph)
        HandleRegistry.release(ch)
    }

    @Test
    fun testConnectorAddRejectsBadPipelineType()
    {
        val ch = NativeBridge.connectorCreate()
        val content = NativeBridge.contentCreate("hi")
        val rc = NativeBridge.connectorAdd(ch, "key", content)
        assertEquals(-0x13, rc, "add with CONTENT handle should return TYPE_MISMATCH")
        HandleRegistry.release(content)
        HandleRegistry.release(ch)
    }

    @Test
    fun testConnectorConfigMethodsRejectNonConnectorHandle()
    {
        val ph = NativeBridge.pipelineCreate()
        val rc = NativeBridge.connectorAdd(ph, "k", ph)
        assertEquals(-0x03, rc, "connectorAdd on PIPELINE should return INVALID_HANDLE")
        HandleRegistry.release(ph)
    }
}
