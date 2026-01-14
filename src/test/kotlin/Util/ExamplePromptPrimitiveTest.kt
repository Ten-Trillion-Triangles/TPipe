package com.TTT.Util

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlin.test.Test
import kotlin.test.assertTrue

@Serializable
enum class TestEnum {
    VALUE1, VALUE2, VALUE3
}

class ExamplePromptPrimitiveTest {

    @Test
    fun testInt() {
        println("Testing Int...")
        val result = examplePromptFor<Int>()
        println("Result for Int:\n$result")
        assertTrue(result.isNotEmpty())
    }

    @Test
    fun testBoolean() {
        println("Testing Boolean...")
        val result = examplePromptFor<Boolean>()
        println("Result for Boolean:\n$result")
        assertTrue(result.isNotEmpty())
    }

    @Test
    fun testEnum() {
        println("Testing Enum...")
        val result = examplePromptFor<TestEnum>()
        println("Result for Enum:\n$result")
        assertTrue(result.isNotEmpty())
        assertTrue(result.contains("VALUE1"))
    }

    @Test
    fun testListReified() {
        println("Testing List<String> (Reified)...")
        try {
            val result = examplePromptFor<List<String>>()
            println("Result for List<String> (Reified):\n$result")
        } catch (e: Exception) {
            println("Crashed for List<String> (Reified): ${e.message}")
        }
    }

    @Test
    fun testListExplicit() {
        println("Testing List<String> (Explicit Serializer)...")
        val result = examplePromptFor(ListSerializer(String.serializer()))
        println("Result for List<String> (Explicit Serializer):\n$result")
        assertTrue(result.isNotEmpty())
    }

    @Test
    fun testMapReified() {
        println("Testing Map<String, Int> (Reified)...")
        try {
            val result = examplePromptFor<Map<String, Int>>()
            println("Result for Map<String, Int> (Reified):\n$result")
        } catch (e: Exception) {
            println("Crashed for Map<String, Int> (Reified): ${e.message}")
        }
    }

    @Test
    fun testMapExplicit() {
        println("Testing Map<String, Int> (Explicit Serializer)...")
        val result = examplePromptFor(MapSerializer(String.serializer(), Int.serializer()))
        println("Result for Map<String, Int> (Explicit Serializer):\n$result")
        assertTrue(result.isNotEmpty())
    }
}
