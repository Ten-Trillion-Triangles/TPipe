package com.TTT.PipeContextProtocol

import kotlinx.coroutines.runBlocking
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PcpThreadPoolTest
{
    @Test
    fun poolSizeIsAvailableProcessorsTimesTwo()
    {
        val pool = PcpThreadPool.create()
        try
        {
            assertEquals(Runtime.getRuntime().availableProcessors() * 2, pool.maxConcurrency)
        }
        finally { pool.shutdown() }
    }

    @Test
    fun saturationRejectsNewSubmissionsWithRejectedExecutionException()
    {
        val pool = PcpThreadPool.create()
        // A latch the test holds closed so the blocker workers stay parked
        // until we explicitly release them. The next submit must be rejected
        // while they are still parked, proving the saturated-path backpressure.
        val releaseLatch = java.util.concurrent.CountDownLatch(1)
        try
        {
            // Fill every worker with a blocker parked on the latch
            val blockers = (1..pool.maxConcurrency).map {
                pool.submit { releaseLatch.await() }
            }

            val rejected = try
            {
                pool.submit { "should not run" }
                false
            }
            catch(_: RejectedExecutionException)
            {
                true
            }

            assertTrue(rejected, "saturated pool must reject new submissions")
            // Let the blockers complete so the test doesn't hang the JVM
            releaseLatch.countDown()
            blockers.forEach { it.get(5, TimeUnit.SECONDS) }
        }
        finally
        {
            releaseLatch.countDown()
            pool.shutdown()
        }
    }

    @Test
    fun shutdownRejectsAllSubmissions() = runBlocking {
        val pool = PcpThreadPool.create()
        pool.shutdown()

        val rejected = try
        {
            pool.submit { "never" }
            false
        }
        catch(_: RejectedExecutionException)
        {
            true
        }

        assertTrue(rejected)
    }
}