package com.TTT.Util

import com.TTT.Pipeline.DistributionGrid
import com.TTT.Pipeline.DistributionGridRoutingPolicy
import com.TTT.Pipeline.Manifold
import com.TTT.Pipeline.Pipeline
import com.TTT.testing.TestCapturingPipe
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Pins the [cloneInstance] reflection walk against the regressions that bit the
 * Kotlin 2.3 readiness sweep:
 *
 * - non-data classes with no-arg constructors (e.g. Manifold holding a worker Pipeline)
 *   used to recurse forever through the template → cloneValue → cloneInstance loop,
 *   surfacing as a `StackOverflowError` from `Util.cloneInstance`.
 * - `@RuntimeState` and `@Transient` exclusion lists must still skip the right
 *   properties.
 * - the no-arg constructor requirement must surface as a clean `IllegalStateException`.
 *
 * Lives in the root `src/test/kotlin` tree because the test exercises the public
 * extension function `com.TTT.Util.cloneInstance` which is defined in the root
 * TPipe module. Same-module tests can call it directly without reflection.
 */
class UtilCloneInstanceTest
{
    @Test
    fun `cloneInstance on a data class with mutable body state preserves all fields and deep-copies the collection`() {
        val original = TestData(fieldA = "a", fieldB = mutableListOf(1, 2, 3))

        val clone = cloneInstance(original)

        assertNotSame(original, clone)
        assertEquals("a", clone.fieldA)
        assertEquals(listOf(1, 2, 3), clone.fieldB)
        assertNotSame(original.fieldB, clone.fieldB, "MutableList must be deep-copied, not shared by reference")
    }

    @Test
    fun `cloneInstance skips properties annotated with @RuntimeState`() {
        val original = WithRuntimeState(configField = "hello", runtimeCounter = 42)

        val clone = cloneInstance(original)

        assertEquals("hello", clone.configField)
        assertEquals(0, clone.runtimeCounter, "@RuntimeState properties must be left at their default value")
    }

    @Test
    fun `cloneInstance skips properties annotated with @kotlinx serialization Transient`() {
        val original = WithTransient(serialized = "kept", nonSerialized = "leak")

        val clone = cloneInstance(original)

        assertEquals("kept", clone.serialized)
        assertEquals("", clone.nonSerialized, "@Transient properties must be left at their default value")
    }

    @Test
    fun `cloneInstance on a class without a no-arg constructor throws IllegalStateException`() {
        assertFailsWith<IllegalStateException> {
            cloneInstance(NoNoArgCtor("x"))
        }
    }

    @Test
    fun `cloneInstance on a Manifold with worker pipelines does not stack-overflow`() {
        val manifold = Manifold()
        val worker = Pipeline().apply {
            add(TestCapturingPipe().setPipeName("echo"))
        }
        manifold.addWorkerPipeline(worker)

        val clone = cloneInstance(manifold)

        assertNotSame(manifold, clone)
        assertTrue(clone.getWorkerPipelines().isNotEmpty(), "Worker pipelines must be preserved on the clone")
        assertEquals(1, clone.getWorkerPipelines().size)
        assertNotSame(worker, clone.getWorkerPipelines().first(),
            "Worker pipeline must be a fresh instance, not the original")
    }

    @Test
    fun `cloneInstance on a DistributionGrid preserves routing policy configuration`() {
        val grid = DistributionGrid()
        grid.setRoutingPolicy(DistributionGridRoutingPolicy(maxHopCount = 7))

        val clone = cloneInstance(grid)

        assertEquals(7, clone.getRoutingPolicy().maxHopCount)
    }

    @Test
    fun `cloneInstance on a Manifold whose worker Pipeline holds pipes preserves the pipe list shape`() {
        val manifold = Manifold()
        val worker = Pipeline().apply {
            add(TestCapturingPipe().setPipeName("a"))
            add(TestCapturingPipe().setPipeName("b"))
        }
        manifold.addWorkerPipeline(worker)

        val clone = cloneInstance(manifold)

        assertEquals(2, clone.getWorkerPipelines().first().getPipes().size)
    }

    @Test
    fun `cloneInstance on a self-referential non-data class does not stack-overflow`() {
        // Synthetic test: a non-data class that holds a reference to itself.
        // Without the visited-map fix, cloneValue → cloneInstance → cloneValue
        // recurses without termination.
        val original = SelfReferential()
        original.name = "root"
        original.peer = original

        val clone = cloneInstance(original)

        assertNotSame(original, clone)
        assertEquals("root", clone.name)
        // The peer may be the original, the in-flight clone, or a fresh instance
        // depending on cloning order — what matters is that we do not overflow.
        assertNotSame(original, clone.peer!!)
    }

    data class TestData(
        val fieldA: String = "",
        val fieldB: MutableList<Int> = mutableListOf()
    )

    data class WithRuntimeState(
        val configField: String = "",
        @RuntimeState var runtimeCounter: Int = 0
    )

    data class WithTransient(
        val serialized: String = "",
        @kotlinx.serialization.Transient val nonSerialized: String = ""
    )

    class NoNoArgCtor(val required: String)

    class SelfReferential
    {
        var name: String = ""
        var peer: SelfReferential? = null
    }
}
