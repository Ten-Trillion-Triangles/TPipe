package com.TTT.Pipe

import kotlinx.coroutines.*
import kotlin.test.*
import com.TTT.Debug.*
import com.TTT.Pipe.*
import com.TTT.P2P.P2PError
import com.TTT.P2P.P2PException

class PipeTimeoutTest {

    private class TimeoutTestPipe : Pipe() {
        var callCount = 0
        var wasAborted = false
        var simulationDelay = 2000L

        override suspend fun generateText(promptInjector: String): String = ""

        override suspend fun generateContent(content: MultimodalContent): MultimodalContent {
            callCount++
            delay(simulationDelay) 
            return content.apply { text = "success" }
        }

        override suspend fun abort() {
            super.abort()
            wasAborted = true
        }

        override fun truncateModuleContext(): Pipe = this
    }

    @Test
    fun testTimeoutFailure() = runBlocking {
        val pipe = TimeoutTestPipe()
        pipe.pipeTimeout = 500 // 500ms timeout
        pipe.enablePipeTimeout = true
        pipe.timeoutStrategy = PipeTimeoutStrategy.Fail

        val result = pipe.execute("test")

        assertTrue(pipe.wasAborted, "Pipe should have been aborted")
        assertEquals("", result, "Result should be empty (failure)")
    }

    @Test
    fun testTimeoutRetry() = runBlocking {
        val pipe = TimeoutTestPipe()
        pipe.pipeTimeout = 500
        pipe.enablePipeTimeout = true
        pipe.timeoutStrategy = PipeTimeoutStrategy.Retry
        pipe.maxRetryAttempts = 1

        // First call will timeout, retry call will also timeout because delay is fixed at 2s
        pipe.execute("test")

        assertEquals(2, pipe.callCount, "Should have been called twice due to 1 retry")
        assertTrue(pipe.wasAborted)
    }

    private class FlexiblePipe : Pipe() {
        var calls = 0
        var timeoutMs = 500L
        
        override suspend fun generateText(promptInjector: String): String = ""
        override suspend fun generateContent(content: MultimodalContent): MultimodalContent {
            calls++
            if (calls < 3) delay(1000) else delay(100)
            return content.apply { text = "success on call $calls" }
        }
        override fun truncateModuleContext(): Pipe = this
        override suspend fun abort() { super.abort() }
    }

    @Test
    fun testSuccessfulTimeoutRetry() = runBlocking {
        val flexiblePipe = FlexiblePipe()
        flexiblePipe.pipeTimeout = 500
        flexiblePipe.enablePipeTimeout = true
        flexiblePipe.timeoutStrategy = PipeTimeoutStrategy.Retry
        flexiblePipe.maxRetryAttempts = 3
        
        val result = flexiblePipe.execute("start")
        
        assertEquals(3, flexiblePipe.calls)
        assertEquals("success on call 3", result)
    }

    private class TransportFailureThenSuccessPipe : Pipe() {
        var calls = 0

        override suspend fun generateText(promptInjector: String): String = ""

        override suspend fun generateContent(content: MultimodalContent): MultimodalContent {
            calls++
            if (calls == 1) {
                throw P2PException(
                    P2PError.transport,
                    "simulated transport failure",
                    IllegalStateException("simulated transport failure")
                )
            }
            return content.apply { text = "recovered" }
        }

        override fun truncateModuleContext(): Pipe = this
    }

    @Test
    fun testTransportFailureRetriesThroughPipeExecution() = runBlocking {
        val pipe = TransportFailureThenSuccessPipe()
        pipe.enablePipeTimeout(
            duration = 5_000,
            autoRetry = true,
            retryLimit = 1
        )

        val result = pipe.execute("start")

        assertEquals(2, pipe.calls, "A retryable transport failure should retry once")
        assertEquals("recovered", result)
    }

    private class AlwaysFailingTransportPipe : Pipe() {
        var calls = 0

        override suspend fun generateText(promptInjector: String): String = ""

        override suspend fun generateContent(content: MultimodalContent): MultimodalContent {
            calls++
            throw P2PException(
                P2PError.transport,
                "simulated persistent transport failure",
                IllegalStateException("simulated persistent transport failure")
            )
        }

        override fun truncateModuleContext(): Pipe = this
    }

    @Test
    fun testTransportFailureRetryIsBounded() = runBlocking {
        val pipe = AlwaysFailingTransportPipe()
        pipe.enablePipeTimeout(
            duration = 5_000,
            autoRetry = true,
            retryLimit = 2
        )

        pipe.execute("start")

        assertEquals(3, pipe.calls, "Two retries must produce exactly three total attempts")
    }

    private class TransportFailureOnEachNewExecutionPipe : Pipe() {
        var calls = 0

        override suspend fun generateText(promptInjector: String): String = ""

        override suspend fun generateContent(content: MultimodalContent): MultimodalContent {
            calls++
            if(calls % 2 == 1) {
                throw P2PException(
                    P2PError.transport,
                    "simulated per-execution transport failure",
                    IllegalStateException("simulated per-execution transport failure")
                )
            }
            return content.apply { text = "recovered" }
        }

        override fun truncateModuleContext(): Pipe = this
    }

    @Test
    fun testSuccessfulExecutionClearsTransportRetryCount() = runBlocking {
        val pipe = TransportFailureOnEachNewExecutionPipe()
        pipe.enablePipeTimeout(
            duration = 5_000,
            autoRetry = true,
            retryLimit = 1
        )

        assertEquals("recovered", pipe.execute("first"))
        assertEquals("recovered", pipe.execute("second"))
        assertEquals(4, pipe.calls, "Each execution should receive its own retry budget")
    }

    @Test
    fun testRecursiveTimeoutConfiguration() = runBlocking {
        val parent = TimeoutTestPipe()
        val child = TimeoutTestPipe()
        
        // Setup parent-child relationship
        parent.branchPipe = child
        
        // Configure parent with recursion
        parent.enablePipeTimeout(
            applyRecursively = true,
            duration = 1000,
            retryLimit = 3
        )
        
        // Initialize parent (should trigger recursion)
        parent.init()
        
        // Verify child settings
        assertTrue(child.enablePipeTimeout, "Child should have timeout enabled")
        assertEquals(1000L, child.pipeTimeout, "Child should inherit timeout duration")
        assertEquals(3, child.maxRetryAttempts, "Child should inherit retry limit")
        assertEquals(true, child.applyTimeoutRecursively, "Child should have recursion enabled")
    }

    @Test
    fun testPipelineTimeoutConfiguration() = runBlocking {
        val pipeline = com.TTT.Pipeline.Pipeline()
        val pipe1 = TimeoutTestPipe()
        val pipe2 = TimeoutTestPipe()
        
        pipeline.add(pipe1)
        pipeline.add(pipe2)
        
        // Configure pipeline timeout
        pipeline.enablePipeTimeout(
            applyRecursively = true,
            duration = 2000,
            retryLimit = 2
        )
        
        // Initialize pipeline (should apply settings to pipes)
        pipeline.init()
        
        // Verify pipe1 settings
        assertTrue(pipe1.enablePipeTimeout, "Pipe1 should have timeout enabled")
        assertEquals(2000L, pipe1.pipeTimeout, "Pipe1 should inherit timeout duration")
        assertEquals(2, pipe1.maxRetryAttempts, "Pipe1 should inherit retry limit")
        
        // Verify pipe2 settings
        assertTrue(pipe2.enablePipeTimeout, "Pipe2 should have timeout enabled")
        assertEquals(2000L, pipe2.pipeTimeout, "Pipe2 should inherit timeout duration")
        assertEquals(2, pipe2.maxRetryAttempts, "Pipe2 should inherit retry limit")
    }
}
