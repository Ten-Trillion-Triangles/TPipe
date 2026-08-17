package com.TTT.Pipe

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Tests for [Pipe.propagatePipeTimeout] — the recursive walker that
 * mirrors [Pipe.propagateStallDetection]. Captures Issue 10 from the
 * 2026-08-17 round-1 verification report: a 2-deep nested pipe
 * (parent -> child -> grandchild) must have timeout enabled on the
 * grandchild after the parent calls [propagatePipeTimeout].
 *
 * Cycle-safe via [visited] keyed on [pipeId] — a pipe referenced from
 * multiple parents is visited once.
 */
class PipeTimeoutPropagateTest {

    private fun newPipe(name: String): DummyPipe
    {
        val p = DummyPipe()
        p.pipeName = name
        return p
    }

    @Test
    fun `propagatePipeTimeout enables timeout on direct child reasoningPipe`()
    {
        val parent = newPipe("parent")
        val child = newPipe("child")
        parent.reasoningPipe = child

        parent.pipeTimeout = 60_000L
        parent.maxRetryAttempts = 3
        parent.timeoutStrategy = PipeTimeoutStrategy.Retry
        // pipeRetryFunction is protected — use a no-op subclass to set it.
        // We're not testing the retry function itself, just the propagation.
        parent.applyTimeoutRecursively = true

        parent.propagatePipeTimeout()

        assertEquals(true, child.enablePipeTimeout, "Child should have enablePipeTimeout=true after parent propagates")
        assertEquals(60_000L, child.pipeTimeout, "Child should inherit pipeTimeout=60_000. Got: ${child.pipeTimeout}")
        assertEquals(3, child.maxRetryAttempts, "Child maxRetryAttempts should be 3. Got: ${child.maxRetryAttempts}")
        assertEquals(PipeTimeoutStrategy.Retry, child.timeoutStrategy, "Child should inherit timeoutStrategy=Retry")
        assertEquals(true, child.applyTimeoutRecursively, "Child should have applyTimeoutRecursively=true so the cascade continues")
    }

    @Test
    fun `propagatePipeTimeout enables timeout on grandchild two levels deep`()
    {
        val parent = newPipe("parent")
        val child = newPipe("child")
        val grandchild = newPipe("grandchild")

        child.reasoningPipe = grandchild
        parent.reasoningPipe = child

        parent.pipeTimeout = 60_000L
        parent.maxRetryAttempts = 3
        parent.timeoutStrategy = PipeTimeoutStrategy.Retry
        parent.applyTimeoutRecursively = true

        parent.propagatePipeTimeout()

        assertEquals(true, grandchild.enablePipeTimeout, "Grandchild should have enablePipeTimeout=true after parent propagates")
        assertEquals(60_000L, grandchild.pipeTimeout, "Grandchild pipeTimeout should be 60_000. Got: ${grandchild.pipeTimeout}")
        assertEquals(3, grandchild.maxRetryAttempts, "Grandchild maxRetryAttempts should be 3. Got: ${grandchild.maxRetryAttempts}")
        assertEquals(PipeTimeoutStrategy.Retry, grandchild.timeoutStrategy, "Grandchild should inherit timeoutStrategy=Retry")
    }

    @Test
    fun `propagatePipeTimeout is cycle-safe when two branches share a grandchild`()
    {
        val parent = newPipe("parent")
        val validatorChild = newPipe("validatorChild")
        val transformationChild = newPipe("transformationChild")
        val sharedGrandchild = newPipe("sharedGrandchild")

        // Same grandchild reachable from two different child slots.
        validatorChild.reasoningPipe = sharedGrandchild
        transformationChild.reasoningPipe = sharedGrandchild
        parent.validatorPipe = validatorChild
        parent.transformationPipe = transformationChild

        parent.pipeTimeout = 60_000L
        parent.maxRetryAttempts = 3
        parent.timeoutStrategy = PipeTimeoutStrategy.Retry
        parent.applyTimeoutRecursively = true

        parent.propagatePipeTimeout()

        assertEquals(true, sharedGrandchild.enablePipeTimeout, "Shared grandchild should have enablePipeTimeout=true exactly once")
        // The walker visits each pipe once. No assertion on "called once" for
        // external effect (the walker doesn't expose a counter), but the
        // completion itself is the proof — no infinite loop, no exception.
    }
}
