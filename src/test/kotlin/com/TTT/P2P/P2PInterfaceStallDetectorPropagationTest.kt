package com.TTT.P2P

import com.TTT.Pipe.Pipe
import com.TTT.Pipe.StallCallback
import com.TTT.Pipe.StallEvent
import com.TTT.Pipe.StreamingStallConfig
import com.TTT.Pipeline.Connector
import com.TTT.Pipeline.DistributionGrid
import com.TTT.Pipeline.Manifold
import com.TTT.Pipeline.MultiConnector
import com.TTT.Pipeline.Pipeline
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.reflect.full.declaredMemberProperties
import kotlin.reflect.jvm.isAccessible

/**
 * Verifies that [P2PInterface.enableStallDetectorRecursive] on a P2PInterface
 * root propagates the stall config + callback to every leaf pipe in the tree.
 *
 * The recursion shape mirrors [P2PInterface.setStreamingCallbackRecursive]:
 *  - Container overrides (Manifold, Connector, MultiConnector, DistributionGrid)
 *    walk their P2PInterface children.
 *  - Pipeline walks its direct child [Pipe]s and calls [Pipe.propagateStallDetection]
 *    on each, which recursively wires validator/transformation/branch/reasoning
 *    child pipes.
 *
 * Junction, Splitter, and PumpStation use private internal binding classes that
 * cannot be constructed from outside their files. They are verified by the
 * compile-time override presence + the broader *Stall* test surface, not by
 * unit tests in this class.
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

    private fun setPrivateField(target: Any, name: String, value: Any?) {
        val prop = target::class.declaredMemberProperties.first { it.name == name }
        prop.isAccessible = true
        (prop as kotlin.reflect.KMutableProperty1<Any, Any?>).set(target, value)
    }

    private fun <T> getPrivateList(target: Any, name: String): MutableList<T> {
        val prop = target::class.declaredMemberProperties.first { it.name == name }
        prop.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return (prop.getter.call(target) as MutableList<T>)
    }

    //=== Pipeline ============================================================

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

    //=== Manifold =============================================================

    @Test
    fun `Manifold root propagates to manager pipeline and every worker component`() = runBlocking {
        val managerLeaf = makeStubPipe("managerLeaf")
        val managerPipeline = Pipeline().apply { add(managerLeaf) }

        val workerLeaf = makeStubPipe("workerLeaf")
        val workerPipeline = Pipeline().apply { add(workerLeaf) }

        val manifold = Manifold()
        setPrivateField(manifold, "managerPipeline", managerPipeline)
        getPrivateList<com.TTT.P2P.P2PInterface>(manifold, "workerComponents").add(workerPipeline)

        val (cb, _) = capturedCallback()
        manifold.enableStallDetectorRecursive(newConfig(), cb)

        assertTrue(managerLeaf.enableStallDetector)
        assertTrue(workerLeaf.enableStallDetector)
        assertSame(cb, managerLeaf.stallCallback)
        assertSame(cb, workerLeaf.stallCallback)
    }

    //=== Connector ============================================================

    @Test
    fun `Connector root propagates to every branch pipeline`() = runBlocking {
        val leaf1 = makeStubPipe("leaf1")
        val leaf2 = makeStubPipe("leaf2")
        val branch1 = Pipeline().apply { add(leaf1) }
        val branch2 = Pipeline().apply { add(leaf2) }

        val connector = Connector()
        setPrivateField(connector, "branches", linkedMapOf<String, Pipeline>("a" to branch1, "b" to branch2))

        val (cb, _) = capturedCallback()
        connector.enableStallDetectorRecursive(newConfig(), cb)

        assertTrue(leaf1.enableStallDetector)
        assertTrue(leaf2.enableStallDetector)
        assertSame(cb, leaf1.stallCallback)
        assertSame(cb, leaf2.stallCallback)
    }

    //=== MultiConnector =======================================================

    @Test
    fun `MultiConnector root propagates to every connector`() = runBlocking {
        val leaf1 = makeStubPipe("leaf1")
        val leaf2 = makeStubPipe("leaf2")
        val branch1 = Pipeline().apply { add(leaf1) }
        val branch2 = Pipeline().apply { add(leaf2) }
        val connector1 = Connector()
        val connector2 = Connector()
        setPrivateField(connector1, "branches", linkedMapOf<String, Pipeline>("a" to branch1))
        setPrivateField(connector2, "branches", linkedMapOf<String, Pipeline>("b" to branch2))

        val mc = MultiConnector()
        setPrivateField(mc, "connectors", mutableListOf(connector1, connector2))

        val (cb, _) = capturedCallback()
        mc.enableStallDetectorRecursive(newConfig(), cb)

        assertTrue(leaf1.enableStallDetector)
        assertTrue(leaf2.enableStallDetector)
        assertSame(cb, leaf1.stallCallback)
        assertSame(cb, leaf2.stallCallback)
    }

    //=== DistributionGrid ====================================================

    @Test
    fun `DistributionGrid root propagates to entry and worker pipelines`() = runBlocking {
        val entryLeaf = makeStubPipe("entryLeaf")
        val workerLeaf = makeStubPipe("workerLeaf")
        val entryPipeline = Pipeline().apply { add(entryLeaf) }
        val workerPipeline = Pipeline().apply { add(workerLeaf) }

        val grid = DistributionGrid()
        setPrivateField(grid, "entryPipeline", entryPipeline)
        setPrivateField(grid, "workerPipelines", mutableListOf(workerPipeline))

        val (cb, _) = capturedCallback()
        grid.enableStallDetectorRecursive(newConfig(), cb)

        assertTrue(entryLeaf.enableStallDetector)
        assertTrue(workerLeaf.enableStallDetector)
        assertSame(cb, entryLeaf.stallCallback)
        assertSame(cb, workerLeaf.stallCallback)
    }

    //=== Pipe leaf ============================================================

    @Test
    fun `Pipe leaf override is a no-op so recursion does not double-wire the detector`() = runBlocking {
        val leaf = makeStubPipe("leaf")
        val (cb, _) = capturedCallback()
        val cfg = newConfig()
        leaf.enableStallDetector(cfg, cb)
        assertTrue(leaf.enableStallDetector)

        leaf.enableStallDetectorRecursive(cfg, cb)
        assertTrue(leaf.enableStallDetector)
        assertEquals(cfg, leaf.stallDetectorConfig)
    }

    //=== Cross-cutting ========================================================

    @Test
    fun `Recursive call on a parent with a shared child-pipe reference does not double-wire the detector`() = runBlocking {
        val shared = makeStubPipe("shared")
        // Same pipe wired as both validatorPipe and branchPipe of a parent.
        // The visited-set in propagateStallDetection must dedup so enableStallDetector
        // fires exactly once on `shared` and the config object is set once.
        val parent = makeStubPipe("parent")
        parent.setValidatorPipe(shared)
        parent.setBranchPipe(shared)

        val root = Pipeline().apply { add(parent) }
        val (cb, _) = capturedCallback()
        root.enableStallDetectorRecursive(newConfig(), cb)

        assertTrue(shared.enableStallDetector)
        assertEquals(newConfig(), shared.stallDetectorConfig)
    }

    @Test
    fun `Recursive call propagates through a 2-level deep Manifold tree (Manifold + Pipeline + leaf)`() = runBlocking {
        val deepLeaf = makeStubPipe("deepLeaf")
        // Manifold.managerPipeline holds a Pipeline; that Pipeline holds the leaf directly.
        val managerPipeline = Pipeline().apply { add(deepLeaf) }

        val manifold = Manifold()
        setPrivateField(manifold, "managerPipeline", managerPipeline)

        val (cb, _) = capturedCallback()
        manifold.enableStallDetectorRecursive(newConfig(), cb)

        assertTrue(deepLeaf.enableStallDetector)
        assertSame(cb, deepLeaf.stallCallback)
    }

    @Test
    fun `Recursive call with default config propagates StreamingStallConfig defaults to every leaf`() = runBlocking {
        val leaf1 = makeStubPipe("leaf1")
        val root = Pipeline().apply { add(leaf1) }

        root.enableStallDetectorRecursive()  // No config, no callback.

        assertTrue(leaf1.enableStallDetector)
        assertEquals(StreamingStallConfig(), leaf1.stallDetectorConfig)
    }
}
