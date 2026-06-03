package com.TTT.Native

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * TDD tests for PipelineHandle — wraps a TPipe Pipeline for the C ABI.
 *
 * RED PHASE: These tests define expected behavior. Some may fail until the
 * full ABI implementation is complete. Following TDD: RED first, then GREEN.
 */
class PipelineHandleTest {

    //==========================================================================
    // Creation and Type Discriminator
    //==========================================================================

    @Test
    fun testCreate() {
        val pipeline = com.TTT.Pipeline.Pipeline()
        val handle = PipelineHandle(pipeline, "test-pipeline")
        assertNotNull(handle, "PipelineHandle should be non-null")
        assertEquals(pipeline, handle.pipeline, "pipeline field should be the wrapped Pipeline")
    }

    @Test
    fun testTypeDiscriminator() {
        // PIPELINE discriminator must match HandleTypes.PIPELINE (=4)
        assertEquals(4, HandleTypes.PIPELINE, "HandleTypes.PIPELINE should be 4")
    }

    //==========================================================================
    // execute(content)
    //==========================================================================

    @Test
    fun testExecute() {
        // With no pipes, pipeline.execute should still return a MultimodalContent.
        val pipeline = com.TTT.Pipeline.Pipeline()
        val handle = PipelineHandle(pipeline, "exec-test")
        val input = ContentHandle("hello world")
        val result = handle.execute(input)
        assertNotNull(result, "execute should return a Result")
        assertTrue(
            result is PipelineHandle.Result.Success,
            "execute on empty pipeline should succeed (no pipes means passthrough)"
        )
    }

    @Test
    fun testExecuteError() {
        // If pipeline is somehow broken, execute should produce an Error result
        // rather than throw. We can't easily force an error here without mocking,
        // but verify the sealed Result class structure.
        val result: PipelineHandle.Result = PipelineHandle.Result.Error("test")
        assertTrue(result is PipelineHandle.Result.Error)
        assertEquals("test", (result as PipelineHandle.Result.Error).message)
    }

    //==========================================================================
    // getOutcome / getOutcomeAsJson
    //==========================================================================

    @Test
    fun testGetOutcome() {
        val pipeline = com.TTT.Pipeline.Pipeline()
        val handle = PipelineHandle(pipeline, "outcome-test")
        val outcome = handle.getOutcome()
        assertNotNull(outcome, "getOutcome should return non-null string")
        assertTrue(outcome.contains("outcome-test"), "outcome should contain pipeline name")
        assertTrue(outcome.contains("status"), "outcome should contain status field")
    }

    //==========================================================================
    // getName / setName
    //==========================================================================

    @Test
    fun testGetName() {
        val pipeline = com.TTT.Pipeline.Pipeline()
        val handle = PipelineHandle(pipeline, "named-pipeline")
        assertEquals("named-pipeline", handle.getName())
    }

    @Test
    fun testSetName() {
        val pipeline = com.TTT.Pipeline.Pipeline()
        val handle = PipelineHandle(pipeline, "initial-name")
        handle.setName("renamed-pipeline")
        assertEquals("renamed-pipeline", handle.getName(), "getName should reflect new name")
        assertEquals("renamed-pipeline", pipeline.pipelineName, "Pipeline.pipelineName should also be updated")
    }

    //==========================================================================
    // getContextWindow / getMiniBank
    //==========================================================================

    @Test
    fun testGetContextWindow() {
        val pipeline = com.TTT.Pipeline.Pipeline()
        val handle = PipelineHandle(pipeline, "context-test")
        val contextWindow = handle.getContextWindow()
        assertNotNull(contextWindow, "getContextWindow should return non-null ContextWindow")
        assertEquals(pipeline.context, contextWindow, "should return the pipeline's context window")
    }

    @Test
    fun testGetMiniBank() {
        val pipeline = com.TTT.Pipeline.Pipeline()
        val handle = PipelineHandle(pipeline, "minibank-test")
        val miniBank = handle.getMiniBank()
        assertNotNull(miniBank, "getMiniBank should return non-null MiniBank")
        assertEquals(pipeline.miniBank, miniBank, "should return the pipeline's miniBank")
    }

    //==========================================================================
    // Reference Counting
    //==========================================================================

    @Test
    fun testRefCounting() {
        val pipeline = com.TTT.Pipeline.Pipeline()
        val handle = PipelineHandle(pipeline, "refcount-test")
        val handleId = HandleRegistry.allocate(HandleTypes.PIPELINE, handle)
        assertTrue(handleId >= 0, "allocate should return non-negative handle")
        assertEquals(1, HandleRegistry.getRefCount(handleId), "new handle should have refCount=1")
        assertEquals(0, HandleRegistry.addRef(handleId), "addRef should succeed")
        assertEquals(2, HandleRegistry.getRefCount(handleId), "refCount should be 2 after addRef")
        // cleanup
        HandleRegistry.release(handleId)
        HandleRegistry.release(handleId)
    }
}
