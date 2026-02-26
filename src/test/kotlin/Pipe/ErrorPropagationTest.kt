package com.TTT.Pipe

import com.TTT.Debug.TraceEventType
import com.TTT.Debug.TracePhase
import com.TTT.Pipeline.Pipeline
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class ErrorPropagationTest
{
    private class FailingPipe(val shouldFail: Boolean = true) : Pipe()
    {
        override suspend fun generateText(promptInjector: String): String = ""

        override suspend fun generateContent(content: MultimodalContent): MultimodalContent
        {
            if (shouldFail)
            {
                throw RuntimeException("Test failure")
            }
            return content.apply { text = "success" }
        }

        override fun truncateModuleContext(): Pipe = this
    }

    @Test
    fun testPipeErrorCapture() = runBlocking {
        val pipe = FailingPipe()
        pipe.pipeName = "TestPipe"
        pipe.init()

        try {
            pipe.execute("test")
        } catch (e: Exception) {
            // Exception may propagate during init, ignore
        }

        assertTrue(pipe.hasError(), "Pipe should have error stored")
        assertNotNull(pipe.lastError, "lastError should not be null")
        assertEquals("Test failure", pipe.getErrorMessage())
        assertEquals(TraceEventType.API_CALL_FAILURE, pipe.getErrorType())
    }

    @Test
    fun testPipeErrorClear() = runBlocking {
        val pipe = FailingPipe()
        pipe.init()
        
        try {
            pipe.execute("test")
        } catch (e: Exception) {
            // Exception may propagate, ignore
        }

        assertTrue(pipe.hasError())
        pipe.clearError()
        assertFalse(pipe.hasError(), "Error should be cleared")
        assertNull(pipe.lastError)
    }

    @Test
    fun testMultimodalContentErrorPropagation() = runBlocking {
        val pipe = FailingPipe()
        pipe.init()
        
        val result = try {
            pipe.execute(MultimodalContent("test"))
        } catch (e: Exception) {
            // If exception propagates, return empty content
            MultimodalContent()
        }

        // Error should be captured in pipe regardless
        assertTrue(pipe.hasError(), "Pipe should have error")
        
        // If result has error, check it
        if (result.hasError()) {
            assertNotNull(result.pipeError)
            assertEquals("Test failure", result.pipeError?.message)
        }
    }

    @Test
    fun testPipelineErrorCapture() = runBlocking {
        val pipeline = Pipeline()
        val failingPipe = FailingPipe()
        failingPipe.pipeName = "FailingPipe"

        pipeline.add(failingPipe)
        pipeline.init()

        try {
            pipeline.execute("test")
        } catch (e: Exception) {
            // Exception may propagate, ignore
        }

        assertTrue(pipeline.hasError(), "Pipeline should have error")
        assertNotNull(pipeline.lastFailedPipe, "lastFailedPipe should not be null")
        assertEquals("FailingPipe", pipeline.getFailedPipeName())
        assertEquals("Test failure", pipeline.getErrorMessage())
        assertTrue(pipeline.wasTerminatedByError())
    }

    @Test
    fun testPipelineErrorContext() = runBlocking {
        val pipeline = Pipeline()
        val failingPipe = FailingPipe()
        failingPipe.pipeName = "FailingPipe"

        pipeline.add(failingPipe)
        pipeline.init()
        
        try {
            pipeline.execute("test")
        } catch (e: Exception) {
            // Exception may propagate, ignore
        }

        val errorContext = pipeline.getFullErrorContext()
        assertTrue(errorContext.contains("FailingPipe"), "Error context should contain pipe name")
        assertTrue(errorContext.contains("Test failure"), "Error context should contain error message")
    }

    @Test
    fun testPipelineClearErrors() = runBlocking {
        val pipeline = Pipeline()
        val failingPipe = FailingPipe()

        pipeline.add(failingPipe)
        pipeline.init()
        
        try {
            pipeline.execute("test")
        } catch (e: Exception) {
            // Exception may propagate, ignore
        }

        assertTrue(pipeline.hasError())
        pipeline.clearErrors()
        assertFalse(pipeline.hasError(), "Errors should be cleared")
        assertNull(pipeline.lastFailedPipe)
        assertNull(pipeline.lastError)
    }

    @Test
    fun testSuccessfulPipeNoError() = runBlocking {
        val pipe = FailingPipe(shouldFail = false)
        pipe.init()
        val result = pipe.execute(MultimodalContent("test"))

        assertFalse(pipe.hasError(), "Successful pipe should not have error")
        assertNull(pipe.lastError)
        assertFalse(result.hasError())
    }

    @Test
    fun testPipelineWithSuccessfulPipe() = runBlocking {
        val pipeline = Pipeline()
        val successPipe = FailingPipe(shouldFail = false)

        pipeline.add(successPipe)
        pipeline.init()
        pipeline.execute("test")

        assertFalse(pipeline.hasError(), "Pipeline with successful pipe should not have error")
        assertNull(pipeline.lastFailedPipe)
    }
}
