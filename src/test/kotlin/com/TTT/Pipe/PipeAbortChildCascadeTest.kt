package com.TTT.Pipe

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Tests that [Pipe.abort] recurses into child pipes (validatorPipe,
 * transformationPipe, branchPipe, reasoningPipe) so a cancellation
 * reaches the entire descendant tree, not just the immediate parent.
 *
 * Captures Issue 4 from the 2026-08-17 round-1 verification report:
 * the base `Pipe.abort()` only clears `activeJob` / `activeStallDetector`
 * on the immediate pipe — it does not walk child references.
 */
class PipeAbortChildCascadeTest {

    /**
     * Test pipe that flips an [abortCount] flag every time [abort] is invoked.
     * Other overrides are stubs so the pipe compiles in the test harness.
     */
    private class AbortTrackingPipe(overrideName: String) : Pipe()
    {
        init {
            pipeName = overrideName
        }

        var abortCount: Int = 0

        override suspend fun abort()
        {
            abortCount++
            super.abort()
        }

        override suspend fun generateText(promptInjector: String): String = ""

        override fun truncateModuleContext(): Pipe = this
    }

    @Test
    fun `abort on parent invokes abort on direct child reasoningPipe`()
    {
        val parent = DummyPipe()
        val child = AbortTrackingPipe("child")
        parent.reasoningPipe = child

        runBlocking { parent.abort() }

        assertEquals(1, child.abortCount, "Child abort should be invoked once. Got: ${child.abortCount}")
    }

    @Test
    fun `abort on parent invokes abort on each of the four child slots`()
    {
        val parent = DummyPipe()
        val validator = AbortTrackingPipe("validator")
        val transformation = AbortTrackingPipe("transformation")
        val branch = AbortTrackingPipe("branch")
        val reasoning = AbortTrackingPipe("reasoning")

        parent.validatorPipe = validator
        parent.transformationPipe = transformation
        parent.branchPipe = branch
        parent.reasoningPipe = reasoning

        runBlocking { parent.abort() }

        assertEquals(1, validator.abortCount, "validatorPipe abort count: ${validator.abortCount}")
        assertEquals(1, transformation.abortCount, "transformationPipe abort count: ${transformation.abortCount}")
        assertEquals(1, branch.abortCount, "branchPipe abort count: ${branch.abortCount}")
        assertEquals(1, reasoning.abortCount, "reasoningPipe abort count: ${reasoning.abortCount}")
    }

    @Test
    fun `abort on parent invokes abort on grandchild nested two levels deep`()
    {
        val parent = DummyPipe()
        val child = DummyPipe()
        val grandchild = AbortTrackingPipe("grandchild")

        child.reasoningPipe = grandchild
        parent.reasoningPipe = child

        runBlocking { parent.abort() }

        assertEquals(1, grandchild.abortCount, "Grandchild abort should fire once. Got: ${grandchild.abortCount}")
    }
}
