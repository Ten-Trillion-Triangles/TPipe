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
}
