package com.TTT.Pipeline

import com.TTT.Context.LoreBook
import com.TTT.Pipe.MultimodalContent
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ApplyLorebookUpdatesTest
{
    @Test
    fun testAddsNewLorebookEntryFromValidResponse()
    {
        val station = PumpStation()
        val response = MultimodalContent(
            text = """{"key": "alice", "value": "a curious developer", "weight": 5}"""
        )
        station.applyLorebookUpdates(response)
        val entry = station.contextWindow.loreBookKeys["alice"]
        assertNotNull(entry)
        assertEquals("a curious developer", entry!!.value)
        assertEquals(5, entry.weight)
    }

    @Test
    fun testMergesIntoExistingEntryViaCombineValue()
    {
        val station = PumpStation()
        // Pre-seed a key
        station.applyLorebookUpdates(MultimodalContent(
            text = """{"key": "alice", "value": "first impression", "weight": 3}"""
        ))
        // Update with a second payload — should merge, not replace
        station.applyLorebookUpdates(MultimodalContent(
            text = """{"key": "alice", "value": "second impression", "weight": 9}"""
        ))
        val entry = station.contextWindow.loreBookKeys["alice"]
        assertNotNull(entry)
        assertTrue(entry!!.value.contains("first impression"))
        assertTrue(entry.value.contains("second impression"))
    }

    @Test
    fun testHandlesInvalidJson()
    {
        val station = PumpStation()
        // Garbage text — should silently no-op
        station.applyLorebookUpdates(MultimodalContent(text = "this is not json at all"))
        assertTrue(station.contextWindow.loreBookKeys.isEmpty())
    }

    @Test
    fun testEmptyResponseIsNoOp()
    {
        val station = PumpStation()
        station.applyLorebookUpdates(MultimodalContent(text = ""))
        assertTrue(station.contextWindow.loreBookKeys.isEmpty())
    }

    @Test
    fun testSkipsEntryWithEmptyKey()
    {
        val station = PumpStation()
        station.applyLorebookUpdates(MultimodalContent(
            text = """{"key": "", "value": "orphan", "weight": 1}"""
        ))
        assertTrue(station.contextWindow.loreBookKeys.isEmpty())
    }

    @Test
    fun testParsesAllOptionalFields()
    {
        val station = PumpStation()
        val response = MultimodalContent(text = """
            {"key": "wonderland",
             "value": "a strange place",
             "weight": 7,
             "linkedKeys": ["rabbit", "queen"],
             "aliasKeys": ["wonder-land"],
             "requiredKeys": ["alice"]}
        """.trimIndent())
        station.applyLorebookUpdates(response)
        val entry = station.contextWindow.loreBookKeys["wonderland"]
        assertNotNull(entry)
        assertEquals(7, entry!!.weight)
        assertEquals(listOf("rabbit", "queen"), entry.linkedKeys)
        assertEquals(listOf("wonder-land"), entry.aliasKeys)
        assertEquals(listOf("alice"), entry.requiredKeys)
    }

    @Test
    fun testArrayOfLoreBookEntries()
    {
        val station = PumpStation()
        val response = MultimodalContent(text = """
            [
              {"key": "alice", "value": "developer"},
              {"key": "bob", "value": "tester"}
            ]
        """.trimIndent())
        station.applyLorebookUpdates(response)
        assertNotNull(station.contextWindow.loreBookKeys["alice"])
        assertNotNull(station.contextWindow.loreBookKeys["bob"])
        assertEquals("developer", station.contextWindow.loreBookKeys["alice"]!!.value)
        assertEquals("tester", station.contextWindow.loreBookKeys["bob"]!!.value)
    }

    @Test
    fun testMalformedJsonWithTrailingCommaRepairs()
    {
        val station = PumpStation()
        // Trailing comma — the lenient deserializer allows this
        val response = MultimodalContent(text = """{"key": "alice", "value": "developer",}""")
        station.applyLorebookUpdates(response)
        assertNotNull(station.contextWindow.loreBookKeys["alice"])
    }
}
