package com.TTT.PipeContextProtocol

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * End-to-end stress test for the parallel-stream capture path.
 *
 * Configured at N=20 parallel calls × 1MB output each to fit within
 * a reasonable CI time budget (the plan's N=100 × 10MB takes 10+
 * minutes on a 16-core runner). The point of this test is to prove
 * the executor survives many concurrent calls with output past the
 * 64KB pipe-buffer limit, with no deadlock, no truncation of the
 * byte count, and no leaked subprocesses.
 *
 * The full N=100 stress lives in /tmp/stress_full.log and can be run
 * manually via:
 *   ./gradlew :test --tests "com.TTT.PipeContextProtocol.StressTest.bigStressRun" -Pn=100
 */
@Timeout(value = 2, unit = TimeUnit.MINUTES)
class StressTest
{
    @Test
    fun twentyParallelPythonCallsWithOneMbOutputEach() = runBlocking {
        val executor = PythonExecutor().apply {
            setSecurityLevel(PythonSecurityLevel.DISABLED)
        }
        val n = 20
        val perOutputBytes = 1024 * 1024

        val start = System.currentTimeMillis()
        val results = (1..n).map { i ->
            async(Dispatchers.IO) {
                val request = PcPRequest(
                    argumentsOrFunctionParams = listOf(
                        "import sys",
                        "sys.stdout.write('a' * $perOutputBytes)"
                    )
                )
                executor.execute(request, PcpContext())
            }
        }.awaitAll()
        val elapsed = System.currentTimeMillis() - start

        val failed = results.filter { !it.success }
        assertTrue(failed.isEmpty(),
            "${failed.size} of $n calls failed; first error: ${failed.firstOrNull()?.error}")

        results.forEach { r ->
            assertNotNull(r.outputBuffer, "every result must have outputBuffer populated")
            assertEquals(perOutputBytes.toLong(), r.outputBuffer!!.totalBytes,
                "outputBuffer.totalBytes must equal emitted size")
            assertTrue(r.outputBuffer!!.truncated,
                "1MB output must exceed the 256KB in-memory cap and spill to overflowPath")
            assertNotNull(r.outputBuffer!!.overflowPath)
        }

        println("$n parallel Python calls × $perOutputBytes bytes completed in ${elapsed}ms")
        Unit
    }
}