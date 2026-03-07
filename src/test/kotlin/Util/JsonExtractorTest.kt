package com.TTT.Util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class JsonExtractorTest
{
    @Test
    fun `extract handles braces inside strings`()
    {
        val input = "prefix {\"message\": \"Value with } brace\", \"count\": 1} suffix"
        val elements = extractAllJsonObjects(input)
        assertEquals(1, elements.size)
        val obj = elements.first() as JsonObject
        assertEquals("Value with } brace", obj["message"]?.jsonPrimitive?.content)
        assertEquals(1, obj["count"]?.jsonPrimitive?.int)
    }

    @Test
    fun `extract auto closes truncated object`()
    {
        val input = "Truncated -> {\"a\":1, \"b\":2"
        val elements = extractAllJsonObjects(input)
        assertEquals(1, elements.size)
        val obj = elements.first() as JsonObject
        assertEquals(1, obj["a"]?.jsonPrimitive?.int)
        assertEquals(2, obj["b"]?.jsonPrimitive?.int)
    }

    @Test
    fun `extract quotes unquoted keys and values`()
    {
        val input = "Noise {foo: bar, raw_value: 42, flag:true} tail"
        val elements = extractAllJsonObjects(input)
        assertEquals(1, elements.size)
        val obj = elements.first() as JsonObject
        assertEquals("bar", obj["foo"]?.jsonPrimitive?.content)
        assertEquals(42, obj["raw_value"]?.jsonPrimitive?.int)
        assertTrue(obj["flag"]?.jsonPrimitive?.boolean == true)
    }

    @Test
    fun `extract cleans array with trailing commas`()
    {
        val input = "List: [{\"id\":1}, {id: two},]"
        val elements = extractAllJsonObjects(input)
        assertEquals(1, elements.size)
        val array = elements.first().jsonArray
        assertEquals(2, array.size)
        assertEquals(1, array[0].jsonObject["id"]?.jsonPrimitive?.int)
        assertEquals("two", array[1].jsonObject["id"]?.jsonPrimitive?.content)
    }

    @Test
    fun `extractNonJsonText returns only non-JSON text`()
    {
        val input = "Hello {\"key\":\"value\"} World"
        val expected = "Hello  World"
        assertEquals(expected, extractNonJsonText(input))
    }

    @Test
    fun `extractNonJsonText removes multiple JSON objects and arrays`()
    {
        val input = "Prefix {\"a\": 1} Middle [1, 2] Suffix"
        val expected = "Prefix  Middle  Suffix"
        assertEquals(expected, extractNonJsonText(input))
    }

    @Test
    fun `extractNonJsonText handles nested JSON objects inside arrays`()
    {
        val input = "text [1, {\"a\": 1}, 2] text"
        val expected = "text [1, , 2] text"
        assertEquals(expected, extractNonJsonText(input))
    }

    @Test
    fun `extractNonJsonText handles empty input`()
    {
        val input = ""
        val expected = ""
        assertEquals(expected, extractNonJsonText(input))
    }

    @Test
    fun `extractNonJsonText handles input with no JSON`()
    {
        val input = "This is just regular text without any JSON."
        val expected = "This is just regular text without any JSON."
        assertEquals(expected, extractNonJsonText(input))
    }

    @Test
    fun `extractNonJsonText handles input that is only JSON`()
    {
        val input = "{\"key\":\"value\"}"
        assertEquals("", extractNonJsonText(input))

        val input2 = "[1, 2, 3]"
        assertEquals("", extractNonJsonText(input2))
    }

    @Test
    fun `extractNonJsonText handles unclosed JSON-like structures gracefully`()
    {
        val input1 = "Text with unclosed { object and text"
        assertEquals("Text with unclosed ", extractNonJsonText(input1))

        val input2 = "Text with unclosed [ array and text"
        assertEquals("Text with unclosed ", extractNonJsonText(input2))
    }
}
