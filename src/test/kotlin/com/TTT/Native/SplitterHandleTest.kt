package com.TTT.Native

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * TDD tests for [SplitterHandle] and the Splitter C ABI surface.
 *
 * These tests verify the Kotlin-side NativeBridge + SplitterHandle
 * contract, which is the same code path the Java `@CEntryPoint` shims in
 * [TPipeBootstrap] delegate to.
 */
class SplitterHandleTest
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
    fun testSplitterAddPipeline()
    {
        val sh = NativeBridge.splitterCreate()
        val ph = NativeBridge.pipelineCreate()
        val rc = NativeBridge.splitterAddPipeline(sh, ph)
        assertEquals(0, rc, "addPipeline should return 0 on success")
        val count = NativeBridge.splitterGetAllChildPipelines(sh)
        assertEquals(1, count, "splitter should have 1 child after add")
        HandleRegistry.release(ph)
        HandleRegistry.release(sh)
    }

    @Test
    fun testSplitterAddPipelineRejectsBadPipelineType()
    {
        val sh = NativeBridge.splitterCreate()
        val ch = NativeBridge.contentCreate("hello")  // CONTENT, not PIPELINE
        val rc = NativeBridge.splitterAddPipeline(sh, ch)
        assertEquals(-0x13, rc, "addPipeline on CONTENT should return TYPE_MISMATCH")
        HandleRegistry.release(ch)
        HandleRegistry.release(sh)
    }

    @Test
    fun testSplitterRemovePipeline()
    {
        val sh = NativeBridge.splitterCreate()
        val ph = NativeBridge.pipelineCreate()
        NativeBridge.splitterAddPipeline(sh, ph)
        val rc = NativeBridge.splitterRemovePipeline(sh, ph)
        assertEquals(0, rc)
        val count = NativeBridge.splitterGetAllChildPipelines(sh)
        assertEquals(0, count, "splitter should have 0 children after remove")
        HandleRegistry.release(ph)
        HandleRegistry.release(sh)
    }

    @Test
    fun testSplitterGetAllChildPipelinesEmpty()
    {
        val sh = NativeBridge.splitterCreate()
        val count = NativeBridge.splitterGetAllChildPipelines(sh)
        assertEquals(0, count)
        HandleRegistry.release(sh)
    }

    @Test
    fun testSplitterGetChildCount()
    {
        val sh = NativeBridge.splitterCreate()
        assertEquals(0, NativeBridge.splitterGetChildCount(sh))
        val ph = NativeBridge.pipelineCreate()
        NativeBridge.splitterAddPipeline(sh, ph)
        assertEquals(1, NativeBridge.splitterGetChildCount(sh))
        HandleRegistry.release(ph)
        HandleRegistry.release(sh)
    }

    @Test
    fun testSplitterConfigMethodsRejectNonSplitterHandle()
    {
        val ch = NativeBridge.contentCreate("hello")
        val rc = NativeBridge.splitterGetAllChildPipelines(ch)
        assertEquals(-0x03, rc, "getAllChildPipelines on CONTENT should return INVALID_HANDLE")
        HandleRegistry.release(ch)
    }
}
