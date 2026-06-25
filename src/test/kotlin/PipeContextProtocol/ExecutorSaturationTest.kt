package com.TTT.PipeContextProtocol

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CountDownLatch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ExecutorSaturationTest
{
    @Test
    fun pythonExecutorReturnsSaturatedErrorWhenPoolFull() = runBlocking {
        // Build a pool small enough to saturate quickly. We can't easily
        // shrink Runtime.availableProcessors() but we can pre-fill it
        // with a latch-parked blocker per worker, then verify the next
        // submit is rejected.
        val smallPool = PcpThreadPool.create()
        val executor = PythonExecutor(threadPool = smallPool).apply {
            setSecurityLevel(PythonSecurityLevel.DISABLED)
        }
        val releaseLatch = CountDownLatch(1)

        val blockingRequest = PcPRequest(
            argumentsOrFunctionParams = listOf("import time; time.sleep(60)")
        )

        try
        {
            // Saturate the pool by submitting blocking tasks directly
            val blockers = (1..smallPool.maxConcurrency).map {
                smallPool.submit<Unit> { releaseLatch.await() }
            }

            // Yield so blockers actually start holding their workers
            delay(100)

            // Now submit through the executor — must be rejected
            val rejectedResult = executor.execute(blockingRequest, PcpContext())

            assertEquals(false, rejectedResult.success)
            assertNotNull(rejectedResult.error)
            assertTrue(rejectedResult.error!!.contains("saturated", ignoreCase = true),
                "expected saturated error, got: ${rejectedResult.error}")

            // Release blockers so the pool can shut down
            releaseLatch.countDown()
            blockers.forEach { it.get(5, java.util.concurrent.TimeUnit.SECONDS) }
        }
        finally
        {
            releaseLatch.countDown()
            smallPool.shutdown()
        }
        Unit
    }
}