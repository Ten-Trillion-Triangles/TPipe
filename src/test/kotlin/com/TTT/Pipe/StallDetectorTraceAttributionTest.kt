package com.TTT.Pipe

import com.TTT.Debug.PipeTracer
import com.TTT.Debug.TraceConfig
import com.TTT.Debug.TraceDetailLevel
import com.TTT.Debug.TraceEvent
import com.TTT.P2P.P2PError
import com.TTT.P2P.P2PException
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Regression tests for stall-detector trace attribution.
 *
 * BUG: when the stall detector kills a pipe's in-flight stream, the trace does not
 * say so. [PipeTimeoutManager.handleStallSignal] emits `PIPE_RETRY` with stall
 * metadata but no `reason` key, so the row is not self-identifying. Worse, the
 * `abort()` that follows tears down the transport, which the provider layer reports
 * as an empty response (`reason=emptyProviderResponse`) and which
 * [PipeTimeoutManager.handleExceptionSignal] then re-reports as
 * `reason=transportFailure`. A trace reader concludes "the provider returned nothing /
 * the transport failed" when in fact the stall detector was the cause.
 *
 * Observed in production (2026-10-01, NeoWritingAgent narrative turn):
 *
 *     +87201ms author API_CALL_FAILURE  reason: emptyProviderResponse
 *     +87208ms author PIPE_RETRY        attempt: 1  reason: transportFailure
 *
 * …on pipe `mantle writing pipe (g31b)`, whose stall signature was
 * `stallTokensSeen=4003, stallSilenceMs=132238`, `stallExpectedIntervalMs=2650.72`.
 *
 * These tests pin the contract; each fails before the corresponding fix.
 */
class StallDetectorTraceAttributionTest
{
    private val traceId = "stall-attribution-test"

    /**
     * Builds a pipe wired for tracing so its [Pipe.timeoutTrace] rows land in
     * [PipeTracer] under [traceId]. `DEBUG` detail level is required because both
     * `PIPE_RETRY` and `PIPE_FAILURE` must be observable.
     */
    private fun tracedPipe(): DummyPipe
    {
        val pipe = DummyPipe()
        pipe.pipeName = "attribution-probe"
        pipe.enableTracing(TraceConfig(enabled = true, detailLevel = TraceDetailLevel.DEBUG))
        pipe.addTraceId(traceId)
        return pipe
    }

    /**
     * A stall event with a signature large enough to be unambiguous in assertions.
     */
    private fun stallEvent(): StallEvent = StallEvent(
        pipeName = "attribution-probe",
        elapsedMs = 132_238L,
        tokensSeen = 4003,
        lastTokenTimestamp = 132_138L,
        silenceMs = 132_238L,
        expectedIntervalMs = 2650.72,
        actualIntervalMs = 132_238L,
        stddevMultiplier = 3.0,
        retryAttempt = 0
    )

    private fun eventsOf(type: String): List<TraceEvent> =
        PipeTracer.getTrace(traceId).filter { it.eventType.name == type }

    private fun reasonOf(event: TraceEvent): Any? = event.metadata["reason"]

    @BeforeEach
    fun setup()
    {
        PipeTracer.enable()
        PipeTracer.startTrace(traceId)
    }

    @AfterEach
    fun cleanup()
    {
        PipeTracer.getAllTraces().keys.forEach { PipeTracer.clearTrace(it) }
        PipeTracer.disable()
    }

    /**
     * The stall retry row must name the stall detector as the cause. Before the fix
     * the row carries only `attempt`, so a reader cannot tell a stall retry from a
     * timeout retry (both emit `PIPE_RETRY` with an `attempt` key).
     */
    @Test
    fun `stall retry row carries reason stallTimeout`()
    {
        val pipe = tracedPipe()
        pipe.enableStallDetector(StreamingStallConfig(maxStallRetries = 3))

        val content = MultimodalContent("prompt")
        content.saveSnapshot()

        PipeTimeoutManager.handleStallSignal(pipe, content, stallEvent())

        val retries = eventsOf("PIPE_RETRY")
        assertEquals(1, retries.size, "Expected exactly one PIPE_RETRY from the stall retry branch")
        assertEquals(
            "stallTimeout", reasonOf(retries.first()),
            "Stall retry must self-identify via reason=stallTimeout; metadata was ${retries.first().metadata}"
        )
        assertEquals(
            true, retries.first().metadata["stallDetectorKilled"],
            "Stall retry must flag stallDetectorKilled=true"
        )
    }

    /**
     * The give-up row must also name the stall detector. Note this row is emitted by
     * [PipeTimeoutManager.handleStallSignal]'s terminate branch — in production the
     * pipe has usually already died from the abort-induced provider failure, which is
     * exactly why this row needs to be unmistakable when it does appear.
     */
    @Test
    fun `stall give-up row carries reason stallTimeoutGaveUp`()
    {
        val pipe = tracedPipe()
        pipe.enableStallDetector(StreamingStallConfig(maxStallRetries = 0))

        val content = MultimodalContent("prompt")
        content.saveSnapshot()

        // maxStallRetries=0 means attempts(0) < max(0) is false -> immediate give-up.
        PipeTimeoutManager.handleStallSignal(pipe, content, stallEvent())

        val failures = eventsOf("PIPE_FAILURE")
        assertEquals(1, failures.size, "Expected exactly one PIPE_FAILURE from the give-up branch")
        assertEquals(
            "stallTimeoutGaveUp", reasonOf(failures.first()),
            "Stall give-up must self-identify via reason=stallTimeoutGaveUp; metadata was ${failures.first().metadata}"
        )
    }

    /**
     * When the stall retry branch is taken but no snapshot is available to restore, the
     * pipe cannot re-execute and fails. That failure is still stall-caused, so it must
     * carry `reason=stallTimeout` rather than looking like an anonymous pipe failure.
     *
     * This also pins that the retry row and the failure row coexist for one stall: the
     * retry is recorded (attempt incremented) before the snapshot lookup fails.
     */
    @Test
    fun `stall retry without snapshot fails with reason stallTimeout`()
    {
        val pipe = tracedPipe()
        pipe.enableStallDetector(StreamingStallConfig(maxStallRetries = 3))

        // Deliberately NO saveSnapshot() call: the retry branch is reachable, but the
        // restore step has nothing to restore.
        val content = MultimodalContent("prompt")

        PipeTimeoutManager.handleStallSignal(pipe, content, stallEvent())

        val retries = eventsOf("PIPE_RETRY")
        assertEquals(1, retries.size, "Retry branch must still record the attempt")
        assertEquals("stallTimeout", reasonOf(retries.first()))

        val failures = eventsOf("PIPE_FAILURE")
        assertEquals(1, failures.size, "Missing snapshot must produce exactly one PIPE_FAILURE")
        assertEquals(
            "stallTimeout", reasonOf(failures.first()),
            "A snapshot-less stall failure is still stall-caused; metadata was ${failures.first().metadata}"
        )
        assertEquals(
            true, failures.first().metadata["stallDetectorKilled"],
            "Snapshot-less stall failure must flag stallDetectorKilled=true"
        )
    }

    /**
     * A pipe whose provider call fails with a transport exception on demand.
     *
     * [DummyPipe] is `final`, so this extends [Pipe] directly. The call counter proves
     * whether the transport retry path ran.
     *
     * @param throwAlways When true every `generateText` call throws the transport
     *        exception. When false, calls succeed normally.
     */
    private class TransportFailingPipe(
        private val throwAlways: Boolean
    ) : Pipe()
    {
        var generateCalls: Int = 0
            private set

        override fun truncateModuleContext(): Pipe = this

        override suspend fun generateText(promptInjector: String): String
        {
            generateCalls++
            if(throwAlways)
            {
                throw P2PException(
                    P2PError.transport,
                    "simulated transport failure",
                    Exception("simulated transport failure")
                )
            }
            return promptInjector
        }
    }

    /**
     * Pins the attribution defect: once the stall detector has aborted the in-flight
     * stream, the resulting transport exception must NOT be re-reported as a transport
     * failure, and must NOT consume the transport retry budget. The stall detector
     * already accounted for that attempt via its own retry counter.
     *
     * Before the fix this fails: `handleExceptionSignal` emits
     * `PIPE_RETRY reason=transportFailure` (the production `+87208ms` row).
     */
    @Test
    fun `stall abort does not emit a transport failure retry`()
    {
        val pipe = TransportFailingPipe(throwAlways = true)
        pipe.pipeName = "stall-abort-probe"
        pipe.enableTracing(TraceConfig(enabled = true, detailLevel = TraceDetailLevel.DEBUG))
        pipe.addTraceId(traceId)
        pipe.enablePipeTimeout(autoRetry = true, retryLimit = 5)

        // Simulate the stall detector having just aborted the in-flight stream.
        pipe.markStallAbortInFlight()
        val transportRetriesBefore = PipeTimeoutManager.getRetryCount(pipe)

        val content = MultimodalContent("prompt")
        content.saveSnapshot()

        val transportError = P2PException(
            P2PError.transport,
            "simulated transport failure",
            Exception("simulated transport failure")
        )
        PipeTimeoutManager.handleExceptionSignal(pipe, content, transportError)

        val reasonLabels = eventsOf("PIPE_RETRY").map { reasonOf(it) }
        assertTrue(
            reasonLabels.none { it == "transportFailure" },
            "A stall-caused abort must not be reported as reason=transportFailure; rows were $reasonLabels"
        )
        assertEquals(
            transportRetriesBefore, PipeTimeoutManager.getRetryCount(pipe),
            "A stall-caused abort must not consume the transport retry counter"
        )
    }

    /**
     * Negative control. A genuine transport failure — one with no stall abort behind it
     * — must still be reported as `reason=transportFailure` and must still consume the
     * transport retry counter. This pins the boundary that the previous test must not
     * over-reach past.
     *
     * This passes both before and after the fix; if it ever fails, the suppression has
     * swallowed real provider failures.
     */
    @Test
    fun `genuine transport failure still reports transportFailure`()
    {
        val pipe = TransportFailingPipe(throwAlways = true)
        pipe.pipeName = "genuine-transport-probe"
        pipe.enableTracing(TraceConfig(enabled = true, detailLevel = TraceDetailLevel.DEBUG))
        pipe.addTraceId(traceId)
        pipe.enablePipeTimeout(autoRetry = true, retryLimit = 5)

        // No stall abort in flight — this is an ordinary provider transport failure.
        val transportRetriesBefore = PipeTimeoutManager.getRetryCount(pipe)

        val content = MultimodalContent("prompt")
        content.saveSnapshot()

        val transportError = P2PException(
            P2PError.transport,
            "simulated transport failure",
            Exception("simulated transport failure")
        )
        PipeTimeoutManager.handleExceptionSignal(pipe, content, transportError)

        val reasonLabels = eventsOf("PIPE_RETRY").map { reasonOf(it) }
        assertTrue(
            reasonLabels.any { it == "transportFailure" },
            "A genuine transport failure must still report reason=transportFailure; rows were $reasonLabels"
        )
        assertEquals(
            transportRetriesBefore + 1, PipeTimeoutManager.getRetryCount(pipe),
            "A genuine transport failure must still consume the transport retry counter"
        )
    }
}
