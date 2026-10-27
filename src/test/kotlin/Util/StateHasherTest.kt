package com.TTT.Util

import com.TTT.Pipe.DummyPipe
import com.TTT.Pipe.Pipe
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class StateHasherTest
{
    data class FlatConfig(
        val name: String = "alpha",
        val limit: Int = 42,
        val enabled: Boolean = true,
        val ratio: Double = 0.5
    )

    class NestedTarget
    {
        var label: String = "one"

        val inner: Inner = Inner()

        class Inner
        {
            var code: Int = 7
        }
    }

    @Test
    fun identicalStructuresHashEqual()
    {
        val firstConfig = FlatConfig()
        val secondConfig = FlatConfig()

        assertEquals(stateHash(firstConfig), stateHash(secondConfig))
    }

    @Test
    fun nestedMutationFlipsHash()
    {
        val target = NestedTarget()

        val beforeHash = stateHash(target)

        target.inner.code = 8
        assertNotEquals(beforeHash, stateHash(target))
    }

    @Test
    fun nullRootIsStableAndDistinct()
    {
        assertEquals(stateHash(null), stateHash(null))
        assertNotEquals(stateHash(null), stateHash(42))
    }

    @Test
    fun mapInsertionOrderInsensitive()
    {
        val orderedMap = linkedMapOf("a" to 1, "b" to 2, "c" to 3)
        val reversedMap = linkedMapOf("c" to 3, "b" to 2, "a" to 1)

        assertEquals(stateHash(orderedMap), stateHash(reversedMap))
    }

    @Test
    fun setInsertionOrderInsensitive()
    {
        val orderedSet = linkedSetOf("x", "y", "z")
        val shuffledSet = linkedSetOf("z", "x", "y")

        assertEquals(stateHash(orderedSet), stateHash(shuffledSet))
    }

    @Test
    fun listOrderSensitiveAndContentSensitive()
    {
        assertEquals(stateHash(listOf(1, 2, 3)), stateHash(listOf(1, 2, 3)))
        assertNotEquals(stateHash(listOf(1, 2, 3)), stateHash(listOf(3, 2, 1)))
        assertNotEquals(stateHash(listOf(1, 2, 3)), stateHash(listOf(1, 2, 4)))
    }

    @Test
    fun arraysEncodeByContent()
    {
        val firstArray = intArrayOf(5, 6, 7)
        val secondArray = intArrayOf(5, 6, 7)
        val thirdArray = intArrayOf(5, 6, 8)

        assertEquals(stateHash(firstArray), stateHash(secondArray))
        assertNotEquals(stateHash(firstArray), stateHash(thirdArray))
    }

    class CycleHolder
    {
        var self: String = "a"

        val partner: CyclePartner = CyclePartner()

        class CyclePartner
        {
            var self: String = "b"

            var back: CycleHolder? = null
        }
    }

    class ChainLevel5
    {
        val level4 = ChainLevel4()

        class ChainLevel4
        {
            val level3 = ChainLevel3()

            class ChainLevel3
            {
                val level2 = ChainLevel2()

                class ChainLevel2
                {
                    val leaf: String = "leaf"
                }
            }
        }
    }

    @Test
    fun cycleTerminatesAndIsStable()
    {
        fun buildHolder(): CycleHolder
        {
            val holder = CycleHolder()
            holder.partner.back = holder
            return holder
        }
        val firstHolder = buildHolder()
        val secondHolder = buildHolder()

        val firstHash = stateHash(firstHolder)
        val secondHash = stateHash(secondHolder)

        assertEquals(firstHash, secondHash)
    }

    @Test
    fun depthCapTruncatesDeterministically()
    {
        val chain = ChainLevel5()
        val cappedOptions = StateHashOptions().setMaxDepth(2)

        assertEquals(stateHash(chain, cappedOptions), stateHash(chain, cappedOptions))
        assertNotEquals(stateHash(chain, cappedOptions), stateHash(chain))
    }

    @Test
    fun objectCapTruncatesDeterministically()
    {
        val chain = ChainLevel5()
        val cappedOptions = StateHashOptions().setMaxObjects(2)

        assertEquals(stateHash(chain, cappedOptions), stateHash(chain, cappedOptions))
        assertNotEquals(stateHash(chain, cappedOptions), stateHash(chain))
    }

    class ExclusionConfig
    {
        var model: String = "m1"

        @kotlinx.serialization.Transient
        var scratchBuffer: String = ""

        @RuntimeState
        var runCount: Int = 0
    }

    class SignalNoiseConfig
    {
        var signal: String = "s"
        var noise: Int = 0
    }

    @Test
    fun transientAndRuntimeStateFieldsExcludedByDefault()
    {
        val firstConfig = ExclusionConfig()
        val secondConfig = ExclusionConfig()

        firstConfig.scratchBuffer = "junk"
        firstConfig.runCount = 99
        assertEquals(stateHash(firstConfig), stateHash(secondConfig))

        firstConfig.model = "m2"
        assertNotEquals(stateHash(firstConfig), stateHash(secondConfig))
    }

    @Test
    fun customExclusionPredicateOverridesDefaults()
    {
        val firstConfig = SignalNoiseConfig()
        val secondConfig = SignalNoiseConfig()
        firstConfig.noise = 7

        val quietOptions = StateHashOptions().exclude { property -> property.name == "noise" }
        assertEquals(stateHash(firstConfig, quietOptions), stateHash(secondConfig, quietOptions))
        assertNotEquals(stateHash(firstConfig), stateHash(secondConfig))
    }

    class FunctionHolder
    {
        var handler: ((Int) -> Int)? = null

        fun withOffset(offsetValue: Int): FunctionHolder
        {
            handler = { value -> value + offsetValue }
            return this
        }
    }

    class ThrowingGetter
    {
        var stable: String = "stable"

        val broken: Int
            get() = throw IllegalStateException("getter disabled in tests")
    }

    @Test
    fun functionFieldsStableByDefaultAcrossFreshInstances()
    {
        val firstHolder = FunctionHolder().withOffset(1)
        val secondHolder = FunctionHolder().withOffset(1)

        assertEquals(stateHash(firstHolder), stateHash(secondHolder))

        val identityOptions = StateHashOptions().setEncodeFunctionIdentity(true)
        assertNotEquals(stateHash(firstHolder, identityOptions), stateHash(secondHolder, identityOptions))
    }

    @Test
    fun throwingGetterDegradesToStableMarker()
    {
        val firstGetter = ThrowingGetter()
        val secondGetter = ThrowingGetter()

        assertEquals(stateHash(firstGetter), stateHash(secondGetter))

        firstGetter.stable = "changed"
        assertNotEquals(stateHash(firstGetter), stateHash(secondGetter))
    }

    @Test
    fun pipeSnapshotDetectsDrift()
    {
        val builtPipe = DummyPipe().setPipeName("probe")
        val referencePipe = DummyPipe().setPipeName("probe")

        // pipeId (Pipe.kt:2069) is a per-instance generated UUID that the approved boundary hashes
        // deliberately. Pin it so the equality assertion measures structural parity, not identity noise.
        pinGeneratedPipeId(builtPipe, "fixed-pipe-id")
        pinGeneratedPipeId(referencePipe, "fixed-pipe-id")

        val snapshotHash = builtPipe.stateSnapshot()
        assertEquals(stateHash(referencePipe), snapshotHash)
        assertFalse(builtPipe.isDirtySince(snapshotHash))

        builtPipe.setPipeName("drifted")
        assertTrue(builtPipe.isDirtySince(snapshotHash))
    }

    @Test
    fun pipeExtensionMirrorsStateHash()
    {
        val builtPipe = DummyPipe().setPipeName("mirror")

        assertEquals(stateHash(builtPipe), builtPipe.stateSnapshot())
    }

    /**
     * Pins the protected [Pipe.pipeId] to a fixed value so two independently built pipes share the
     * only per-instance generated field in the hash boundary.
     */
    private fun pinGeneratedPipeId(pipe: Pipe, fixedPipeId: String)
    {
        val pipeIdField = Pipe::class.java.getDeclaredField("pipeId")
        pipeIdField.isAccessible = true
        pipeIdField.set(pipe, fixedPipeId)
    }
}
