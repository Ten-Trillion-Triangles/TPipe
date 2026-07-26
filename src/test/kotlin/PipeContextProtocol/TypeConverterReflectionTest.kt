package com.TTT.PipeContextProtocol

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the [PrimitiveConverter] / [CollectionConverter] / [ObjectConverter]
 * surfaces, including the multi-classloader enum-resolution path that
 * [PrimitiveConverter.resolveEnumClass] walks at runtime. Kotlin 2.3 changes
 * how `KClass.java.enumConstants` and `KClass.isSubclassOf` resolve under the
 * K2 frontend, so the three converters are pinned independently.
 */
class TypeConverterReflectionTest
{
    enum class Side { TOP, BOTTOM }

    @After
    fun cleanup() {
        // Type converters are stateless; no global state to clear.
    }

    @Test
    fun `PrimitiveConverter converts string to enum using the system class loader fallback`() {
        val converter = PrimitiveConverter()
        val result = converter.convert("TOP", "com.TTT.PipeContextProtocol.TypeConverterReflectionTest.Side")
        assertEquals(Side.TOP, result)
    }

    @Test
    fun `PrimitiveConverter rejects empty string for non-nullable target`() {
        val converter = PrimitiveConverter()
        assertFailsWith<IllegalArgumentException> {
            converter.convert("", "kotlin.Int")
        }
    }

    @Test
    fun `PrimitiveConverter returns null when target string is empty and target is nullable`() {
        val converter = PrimitiveConverter()
        val result = converter.convert("", "kotlin.Int?")
        assertNull(result)
    }

    @Test
    fun `PrimitiveConverter returns clean error for non-existent enum class`() {
        val converter = PrimitiveConverter()
        val ex = assertFailsWith<IllegalArgumentException> {
            converter.convert("X", "com.example.NoSuchEnum")
        }
        assertTrue(ex.message!!.contains("not found"),
            "Expected 'not found' error; got: ${ex.message}")
    }

    @Test
    fun `PrimitiveConverter converts Int from string`() {
        val converter = PrimitiveConverter()
        val result = converter.convert("42", "kotlin.Int")
        assertEquals(42, result)
    }

    @Test
    fun `PrimitiveConverter converts Boolean from string`() {
        val converter = PrimitiveConverter()
        assertEquals(true, converter.convert("true", "kotlin.Boolean"))
        assertEquals(false, converter.convert("false", "kotlin.Boolean"))
    }

    @Test
    fun `CollectionConverter requires a serializable element type for List conversion`() {
        // The current CollectionConverter implementation deserializes JSON into a
        // typed collection via kotlinx.serialization. If the element type is not
        // @Serializable, the converter raises an IllegalArgumentException. This
        // test pins the current contract — when the converter grows a fallback
        // path for plain Int/String, this assertion should flip to expect success.
        val converter = CollectionConverter()
        val ex = assertFailsWith<IllegalArgumentException> {
            converter.convert("""[1,2,3]""", "List<kotlin.Int>")
        }
        // Acceptable: the failure is either because the inner Int type cannot
        // be resolved, or because a generic Any fallback is rejected.
        assertTrue(
            ex.message!!.contains("Serializer") || ex.message!!.contains("convert"),
            "Expected serialization-related error; got: ${ex.message}"
        )
    }

    @Test
    fun `ObjectConverter falls back to string when JSON parsing fails`() {
        val converter = ObjectConverter()
        val result = converter.convert("not json at all", "kotlin.Any")
        assertEquals("not json at all", result)
    }
}
