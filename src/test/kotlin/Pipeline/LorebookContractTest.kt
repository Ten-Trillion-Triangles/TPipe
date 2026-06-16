package com.TTT.Pipeline

import com.TTT.Context.ConverseData
import com.TTT.Context.ConverseRole
import com.TTT.Pipe.MultimodalContent
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for the v3 typed lorebook agent envelope (LorebookAgentInput /
 * LorebookAgentOutput) and the cursor-based staleness check. Verifies:
 * - typed envelope round-trips through MultimodalContent JSON
 * - stale output (compactedThroughTurn <= cursor) is discarded
 * - merges and deletes are applied
 * - cursor advances on apply
 * - legacy free-form JSON still works (backward compat)
 * - harnessGeneration in input matches compaction cursor
 */
class LorebookContractTest
{
    private fun turn(text: String, role: ConverseRole = ConverseRole.user): ConverseData
    {
        return ConverseData(role = role, content = MultimodalContent(text = text))
    }

    @Test
    fun testTypedEnvelopeRoundTrip()
    {
        val input = LorebookAgentInput(
            turnsSinceLastUpdate = listOf(turn("user said hello"), turn("user said bye", ConverseRole.assistant)),
            lastLorebookUpdateTurnIndex = 5,
            currentLorebook = emptyList(),
            taskContext = LorebookTaskContext(
                task = "test task", persona = "p", systemTask = "st", userGuidelines = "ug"
            ),
            harnessGeneration = 3L
        )
        val json = com.TTT.Util.serialize(input, false)
        val parsed = com.TTT.Util.deserialize<LorebookAgentInput>(json)
        assertNotNull(parsed)
        assertEquals(5, parsed.lastLorebookUpdateTurnIndex)
        assertEquals(3L, parsed.harnessGeneration)
        assertEquals(2, parsed.turnsSinceLastUpdate.size)
    }

    @Test
    fun testStaleOutputIsDiscarded()
    {
        // Set up a station with the cursor at turn 5.
        val station = PumpStation().setDispatchAgent(Pipeline())
        station.lorebookCursorWrite = LorebookCursor(lastUpdatedTurnIndex = 5)
        // Agent returns compactedThroughTurn = 3, which is <= 5 → stale → discarded.
        val output = LorebookAgentOutput(
            updates = listOf(LorebookUpdate(key = "alice", value = "developer", weight = 5)),
            compactedThroughTurn = 3
        )
        station.applyTypedLorebookUpdates(output)
        // Cursor did not advance.
        assertEquals(5, station.lorebookCursorInternal.lastUpdatedTurnIndex)
        // Lorebook is empty (the update was discarded).
        assertTrue(station.contextWindow.loreBookKeys.isEmpty())
    }

    @Test
    fun testFreshOutputIsApplied()
    {
        val station = PumpStation().setDispatchAgent(Pipeline())
        station.lorebookCursorWrite = LorebookCursor(lastUpdatedTurnIndex = 5)
        val output = LorebookAgentOutput(
            updates = listOf(LorebookUpdate(key = "alice", value = "developer", weight = 5)),
            compactedThroughTurn = 7
        )
        station.applyTypedLorebookUpdates(output)
        // Cursor advanced to 7.
        assertEquals(7, station.lorebookCursorInternal.lastUpdatedTurnIndex)
        // Lorebook contains the new entry.
        val entry = station.contextWindow.loreBookKeys["alice"]
        assertNotNull(entry)
        assertEquals("developer", entry.value)
        assertEquals(5, entry.weight)
    }

    @Test
    fun testMergesAndDeletesApplied()
    {
        val station = PumpStation().setDispatchAgent(Pipeline())
        station.lorebookCursorWrite = LorebookCursor(lastUpdatedTurnIndex = 0)
        // Seed two entries.
        val seed = LorebookAgentOutput(
            updates = listOf(
                LorebookUpdate(key = "alice", value = "first impression", weight = 3, operation = LorebookOperation.Merge),
                LorebookUpdate(key = "bob", value = "bob's fact", weight = 1)
            ),
            compactedThroughTurn = 5
        )
        station.applyTypedLorebookUpdates(seed)
        // Now: merge into alice, replace bob, delete a non-existent key (no-op).
        val next = LorebookAgentOutput(
            updates = listOf(
                LorebookUpdate(key = "alice", value = "second impression", weight = 9, operation = LorebookOperation.Merge),
                LorebookUpdate(key = "bob", value = "bob's new fact", weight = 5, operation = LorebookOperation.Replace)
            ),
            deletions = listOf("carol"),
            compactedThroughTurn = 8
        )
        station.applyTypedLorebookUpdates(next)
        val alice = station.contextWindow.loreBookKeys["alice"]!!
        // Merge: both impressions are in the value.
        assertTrue(alice.value.contains("first impression"))
        assertTrue(alice.value.contains("second impression"))
        // Weight updated.
        // combineValue does not touch weight; Replace would.
        assertEquals(3, alice.weight)
        val bob = station.contextWindow.loreBookKeys["bob"]!!
        // Replace: only the new value.
        assertEquals("bob's new fact", bob.value)
        assertEquals(5, bob.weight)
    }

    @Test
    fun testCursorAdvancesOnApply()
    {
        val station = PumpStation().setDispatchAgent(Pipeline())
        assertEquals(-1, station.lorebookCursorInternal.lastUpdatedTurnIndex)
        val output = LorebookAgentOutput(updates = emptyList(), compactedThroughTurn = 12)
        station.applyTypedLorebookUpdates(output)
        assertEquals(12, station.lorebookCursorInternal.lastUpdatedTurnIndex)
    }

    @Test
    fun testLegacyFreeFormJsonStillWorks()
    {
        // The legacy applyLorebookUpdates path parses free-form JSON. The pre-existing
        // ApplyLorebookUpdatesTest covers this in detail; here we just verify the
        // v3 updateLorebook path falls back to the legacy parser when the response
        // is not a typed envelope.
        val station = PumpStation().setDispatchAgent(Pipeline())
        val legacy = """{"key": "legacy_key", "value": "legacy value", "weight": 2}"""
        station.applyLorebookUpdates(MultimodalContent(text = legacy))
        val entry = station.contextWindow.loreBookKeys["legacy_key"]
        assertNotNull(entry)
        assertEquals("legacy value", entry.value)
    }

    @Test
    fun testUpdateLorebookDispatchesTypedEnvelope()
    {
        // Drive the full updateLorebook path: build the typed input, mock the agent
        // to return a typed output, verify the lorebook was updated and the cursor
        // advanced. Verifies the wiring from updateLorebook -> applyTypedLorebookUpdates.
        val station = PumpStation().setDispatchAgent(Pipeline())
        repeat(3) { i -> station.turnHistory.add(turn("turn $i")) }
        station.compactionCursorWrite = CompactionCursor(generation = 7L)
        val typed = LorebookAgentOutput(
            updates = listOf(LorebookUpdate(key = "shipped", value = "true", weight = 1)),
            compactedThroughTurn = 3
        )
        val json = com.TTT.Util.serialize(typed, false)
        station.setLorebookAgent(MockP2PAgent(script = listOf(MultimodalContent(text = json))))
        runBlocking { station.updateLorebook() }
        // Cursor advanced.
        assertEquals(3, station.lorebookCursorInternal.lastUpdatedTurnIndex)
        // harnessGeneration recorded.
        assertEquals(7L, station.lorebookCursorInternal.lastUpdateGeneration)
        // Lorebook applied.
        assertNotNull(station.contextWindow.loreBookKeys["shipped"])
    }
}
