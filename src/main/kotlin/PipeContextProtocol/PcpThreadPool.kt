package com.TTT.PipeContextProtocol

import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Bounded thread pool for PCP code-execution sandboxes.
 *
 * Caps concurrent tasks submitted to this pool at
 * Runtime.availableProcessors() * 2. KotlinExecutor owns its separate
 * daemon-thread timeout boundary and does not submit script execution here.
 * Saturated submissions throw
 * RejectedExecutionException immediately (no unbounded queue) so the
 * dispatcher can convert the rejection into a clean error response
 * instead of spawning unbounded OS processes.
 *
 * Lifecycle:
 * - create() returns a live pool
 * - submit(task) returns a Future, or throws RejectedExecutionException if saturated
 * - shutdown() rejects all future submissions and waits for in-flight tasks to complete
 */
class PcpThreadPool private constructor(
    private val delegate: ThreadPoolExecutor
)
{
    val maxConcurrency: Int get() = delegate.corePoolSize

    fun <T> submit(task: () -> T): Future<T> = delegate.submit(task)

    fun shutdown()
    {
        delegate.shutdown()
        if(!delegate.awaitTermination(30, TimeUnit.SECONDS))
        {
            delegate.shutdownNow()
        }
    }

    companion object
    {
        fun create(): PcpThreadPool
        {
            val size = Runtime.getRuntime().availableProcessors() * 2
            val delegate = ThreadPoolExecutor(
                size, size,
                0L, TimeUnit.MILLISECONDS,
                SynchronousQueue(),
                { r ->
                    Thread(r, "pcp-worker").apply { isDaemon = true }
                },
                ThreadPoolExecutor.AbortPolicy()
            )
            return PcpThreadPool(delegate)
        }
    }
}
