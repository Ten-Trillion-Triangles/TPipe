package com.TTT.P2P

import com.TTT.Pipe.Pipe
import com.TTT.Pipe.StallCallback
import com.TTT.Pipe.StallEvent
import com.TTT.Pipe.StreamingStallConfig
import com.TTT.Pipeline.Pipeline
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Verifies that [P2PInterface.enableStallDetectorRecursive] on a Pipeline
 * root propagates the stall config + callback to every leaf pipe in the
 * tree, including nested P2PInterface children.
 *
 * Each per-container test in this class pins a different walk shape. The
 * RED-then-GREEN cycle: write the test (compile error = RED because the
 * container does not yet override the method), implement the override
 * (GREEN), commit, move to the next container.
 */
class P2PInterfaceStallDetectorPropagationTest
{
    private fun newConfig() = StreamingStallConfig(windowSize = 17, stddevMultiplier = 2.5)

    private fun capturedCallback(): Pair<StallCallback, MutableList<StallEvent>> {
        val events = mutableListOf<StallEvent>()
        val cb: StallCallback = { event -> events.add(event) }
        return cb to events
    }

    private fun makeStubPipe(name: String): Pipe = object : Pipe() {
        override suspend fun generateText(promptInjector: String): String = ""
        override fun truncateModuleContext(): Pipe = this
    }.apply { setPipeName(name) }

    @Test
    fun `Pipeline root propagates stall config to every direct child pipe`() = runBlocking {
        val leaf1 = makeStubPipe("leaf1")
        val leaf2 = makeStubPipe("leaf2")
        val root = Pipeline().apply { add(leaf1); add(leaf2) }

        val (cb, _) = capturedCallback()
        val cfg = newConfig()
        root.enableStallDetectorRecursive(cfg, cb)

        assertTrue(leaf1.enableStallDetector)
        assertTrue(leaf2.enableStallDetector)
        assertEquals(cfg, leaf1.stallDetectorConfig)
        assertEquals(cfg, leaf2.stallDetectorConfig)
    }

    @Test
    fun `Pipeline root recurses into a child pipe's child pipes (validator transformation branch reasoning)`() = runBlocking {
        val grandChild = makeStubPipe("grandChild")
        val parent = makeStubPipe("parent")
        parent.setValidatorPipe(grandChild)
        val root = Pipeline().apply { add(parent) }

        val (cb, _) = capturedCallback()
        root.enableStallDetectorRecursive(newConfig(), cb)

        // Both the direct child AND its child pipe (via setValidatorPipe) get wired.
        assertTrue(parent.enableStallDetector)
        assertTrue(grandChild.enableStallDetector)
        assertEquals(newConfig(), grandChild.stallDetectorConfig)
    }

    @Test
    fun `Pipeline root override-wins on pre-configured child pipes`() = runBlocking {
        val childWithConfig = makeStubPipe("childWithConfig")
        val originalConfig = StreamingStallConfig(windowSize = 5, stddevMultiplier = 1.5)
        val originalEvents = mutableListOf<StallEvent>()
        val originalCb: StallCallback = { event -> originalEvents.add(event) }
        childWithConfig.enableStallDetector(originalConfig, originalCb)

        val root = Pipeline().apply { add(childWithConfig) }
        val (newCb, _) = capturedCallback()
        val newConfig = newConfig()
        root.enableStallDetectorRecursive(newConfig, newCb)

        // Parent-override-wins: child's config and callback are replaced.
        assertEquals(newConfig, childWithConfig.stallDetectorConfig)
        assertSame(newCb, childWithConfig.stallCallback)
    }

    @Test
    fun `Pipeline root propagates the same callback object reference to every leaf`() = runBlocking {
        val leaf1 = makeStubPipe("leaf1")
        val leaf2 = makeStubPipe("leaf2")
        val root = Pipeline().apply { add(leaf1); add(leaf2) }

        val (cb, _) = capturedCallback()
        root.enableStallDetectorRecursive(newConfig(), cb)

        assertSame(cb, leaf1.stallCallback)
        assertSame(cb, leaf2.stallCallback)
    }
}
