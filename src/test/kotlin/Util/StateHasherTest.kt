package com.TTT.Util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

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
}
