package com.TTT.Pipe

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException

/**
 * TDD proof of the validator/railroad thrash loop (live trace, 2026-10-08):
 *
 * A wall-clock [PipeTimeoutManager] timer expires on a healthy in-flight stream, marks the pipe
 * timed out, and calls [Pipe.abort] to close the connection. The streaming read loop surfaces
 * that self-close as an ordinary transport-shaped exception ([IOException] "Stream closed") —
 * NOT a [kotlinx.coroutines.CancellationException] — so the timeout branch of the catch block in
 * [Pipe.executeMultimodal] is skipped and [PipeTimeoutManager.handleExceptionSignal] launders OUR
 * OWN abort into a `reason=transportFailure` provider fault, re-firing the identical pipe. That
 * is the stall → roll-back → provider-moves-again → crash loop.
 *
 * Contract under test: when a pipe is marked timed out, the exception that just surfaced is the
 * consequence of OUR abort, not a provider defect. [PipeTimeoutManager.handleExceptionSignal]
 * must hand the decision to the timeout policy ([PipeTimeoutManager.handleTimeoutSignal]) — which
 * owns the strategy, the retry budget, and the correct trace reason — instead of re-attributing
 * it as a provider transport failure or silently falling through to empty content.
 *
 * The crisp discriminator is the **Fail** strategy: `handleTimeoutSignal` terminates the pipeline
 * (`terminatePipeline=true`) when a Fail-strategy pipe times out, whereas the buggy transport path
 * returns plain content (no terminate) and the catch block falls through to a stale empty result.
 */
class TimeoutAbortMisattributionTest
{
    private class DummyPipe : Pipe()
    {
        override fun truncateModuleContext(): Pipe = this

        override suspend fun generateText(promptInjector: String): String
        {
            return promptInjector
        }
    }

    private fun selfAbort(): IOException = IOException("Stream closed")

    @Test
    fun `a wall-clock self-abort on a Fail-strategy pipe routes to the timeout policy (terminate, not silent fallthrough)`()
    {
        // Under Fail, a timed-out self-abort must terminate the pipeline cleanly. The buggy
        // transport path leaves the content untouched (no terminate), so the catch block falls
        // through to a stale empty generatedContent — a silent ghost success.
        val pipe = DummyPipe()
        pipe.timeoutStrategy = PipeTimeoutStrategy.Fail
        pipe.maxRetryAttempts = 5
        PipeTimeoutManager.markTimedOutForTest(pipe)

        val content = MultimodalContent("prompt")
        content.saveSnapshot()

        val result = PipeTimeoutManager.handleExceptionSignal(pipe, content, selfAbort())

        assertTrue(
            result.terminatePipeline,
            "a wall-clock self-abort on a Fail-strategy pipe must route to the timeout policy, " +
                "which terminates — not fall through to a silent empty result"
        )
        assertFalse(result.repeatPipe, "a Fail-strategy timeout does not retry")
    }

    @Test
    fun `a wall-clock self-abort on a Retry-strategy pipe routes to the timeout policy (retry, not transport)`()
    {
        // Under Retry (autoRetry=true, the Autogenesis config), a timed-out self-abort is retried
        // by the TIMEOUT policy within its own budget — correctly attributed, not as a provider
        // transport failure.
        val pipe = DummyPipe()
        pipe.timeoutStrategy = PipeTimeoutStrategy.Retry
        pipe.maxRetryAttempts = 5
        PipeTimeoutManager.markTimedOutForTest(pipe)

        val content = MultimodalContent("prompt")
        content.saveSnapshot()

        val result = PipeTimeoutManager.handleExceptionSignal(pipe, content, selfAbort())

        // handleTimeoutSignal (Retry, under limit) restores the snapshot and sets repeatPipe=true.
        assertTrue(
            result.repeatPipe,
            "a wall-clock self-abort on a Retry-strategy pipe must be retried by the timeout policy"
        )
        assertFalse(result.terminatePipeline, "under the retry limit the timeout policy retries, not terminates")
    }

    @Test
    fun `a genuine transport failure on a non-timed-out pipe still retries (regression guard)`()
    {
        // The new timeout routing must not swallow ordinary provider transport faults. No timer
        // fired on this pipe, so a mid-stream IOException is a real provider event and takes the
        // transport retry path exactly as before.
        val pipe = DummyPipe()
        pipe.timeoutStrategy = PipeTimeoutStrategy.Retry
        pipe.maxRetryAttempts = 5

        val content = MultimodalContent("prompt")
        content.saveSnapshot()

        val result = PipeTimeoutManager.handleExceptionSignal(pipe, content, selfAbort())

        assertTrue(result.repeatPipe, "a genuine mid-stream transport fault on a non-timed-out pipe must still retry")
        assertEquals(1, PipeTimeoutManager.getRetryCount(pipe), "the transport retry budget is consumed once for a genuine fault")
    }

    @Test
    fun `a stall abort is still declined regardless of timeout state (regression guard)`()
    {
        // The pre-existing stall-abort guard (isStallAbortInFlight) must keep working, including
        // on a pipe that is simultaneously marked timed out. The stall detector owns its own
        // retry, so the exception signal declines it.
        val pipe = DummyPipe()
        pipe.timeoutStrategy = PipeTimeoutStrategy.Retry
        pipe.maxRetryAttempts = 5
        pipe.markStallAbortInFlight()
        PipeTimeoutManager.markTimedOutForTest(pipe)

        val content = MultimodalContent("prompt")
        content.saveSnapshot()

        val result = PipeTimeoutManager.handleExceptionSignal(pipe, content, selfAbort())

        assertFalse(result.repeatPipe, "a stall abort must not be re-fired through the exception signal")
        assertFalse(result.terminatePipeline, "declining a stall abort is not a terminal failure")
    }
}
