package com.TTT.Pipeline

import com.TTT.Context.ConverseData
import com.TTT.Context.ConverseRole
import com.TTT.Enums.MemoryWriteKind
import com.TTT.Pipe.MultimodalContent
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for the pre-memory DITL hook ([PumpStation.setPreMemoryFunction]).
 *
 * The hook is the developer-side gate at the memory-write point. It receives a
 * [MemoryWriteProposal] describing what a memory agent wants to write, and returns the write to
 * apply — possibly modified — or null to reject it.
 *
 * Covered here:
 * - unbound hook leaves the write path unchanged
 * - the hook observes typed and legacy lorebook proposals and summary proposals
 * - rejecting a lorebook write blocks it and leaves the cursor untouched
 * - modifying a proposal changes what lands
 * - a mismatched proposal subtype is rejected rather than crashing
 * - a throwing hook rejects the write fail-closed without escaping the background coroutine
 * - [MemoryWriteEvaluated] is emitted on approve and reject, and not at all when unbound
 */
class PumpStationPreMemoryHookTest
{
    private fun turn(text: String, role: ConverseRole = ConverseRole.user): ConverseData
    {
        return ConverseData(role = role, content = MultimodalContent(text = text))
    }

    /**
     * Build a station wired with a lorebook agent that returns the supplied payload.
     */
    private fun stationWithLorebook(payload: String): PumpStation
    {
        val station = PumpStation().setDispatchAgent(Pipeline())
        station.setLorebookAgent(MockP2PAgent(script = listOf(MultimodalContent(text = payload))))
        return station
    }

    /**
     * Build a typed lorebook payload for the given entries.
     */
    private fun typedLorebookPayload(
        updates: List<LorebookUpdate>,
        compactedThroughTurn: Int = 3,
        deletions: List<String> = emptyList()
    ): String
    {
        val output = LorebookAgentOutput(
            updates = updates,
            deletions = deletions,
            compactedThroughTurn = compactedThroughTurn
        )
        return com.TTT.Util.serialize(output, false)
    }

    @Test
    fun unboundHookAppliesLorebookWriteUnchanged()
    {
        val station = stationWithLorebook(
            typedLorebookPayload(listOf(LorebookUpdate(key = "alice", value = "developer", weight = 5)))
        )
        runBlocking { station.updateLorebook() }
        val entry = station.contextWindow.loreBookKeys["alice"]
        assertNotNull(entry, "lorebook entry should land when no hook is bound")
        assertEquals("developer", entry.value)
        assertEquals(5, entry.weight)
        assertEquals(3, station.lorebookCursorInternal.lastUpdatedTurnIndex)
    }

    @Test
    fun hookReceivesTypedLorebookProposal()
    {
        val station = stationWithLorebook(
            typedLorebookPayload(
                updates = listOf(LorebookUpdate(key = "alice", value = "developer", weight = 5)),
                compactedThroughTurn = 4,
                deletions = listOf("carol")
            )
        )
        var seen: MemoryWriteProposal.Lorebook? = null
        station.setPreMemoryFunction { proposal, _ ->
            seen = proposal as? MemoryWriteProposal.Lorebook
            proposal
        }
        runBlocking { station.updateLorebook() }
        assertNotNull(seen, "hook should have received the lorebook proposal")
        assertEquals(false, seen!!.legacy)
        assertEquals(4, seen!!.compactedThroughTurn)
        assertEquals(1, seen!!.updates.size)
        assertEquals("alice", seen!!.updates[0].key)
        assertEquals(listOf("carol"), seen!!.deletions)
    }

    @Test
    fun hookReceivesLegacyLorebookProposal()
    {
        val legacy = """{"key": "legacy_key", "value": "legacy value", "weight": 2}"""
        val station = stationWithLorebook(legacy)
        var seen: MemoryWriteProposal.Lorebook? = null
        station.setPreMemoryFunction { proposal, _ ->
            seen = proposal as? MemoryWriteProposal.Lorebook
            proposal
        }
        runBlocking { station.updateLorebook() }
        assertNotNull(seen, "hook should have received the legacy lorebook proposal")
        assertEquals(true, seen!!.legacy)
        assertEquals(1, seen!!.updates.size)
        assertEquals("legacy_key", seen!!.updates[0].key)
        assertEquals("legacy value", seen!!.updates[0].value)
    }

    @Test
    fun hookReturningNullRejectsLorebookWrite()
    {
        val station = stationWithLorebook(
            typedLorebookPayload(listOf(LorebookUpdate(key = "alice", value = "developer", weight = 5)))
        )
        station.setPreMemoryFunction { _, _ -> null }
        runBlocking { station.updateLorebook() }
        assertTrue(
            station.contextWindow.loreBookKeys.isEmpty(),
            "rejected lorebook write must not land; got ${station.contextWindow.loreBookKeys.keys}"
        )
    }

    @Test
    fun hookReturningNullDoesNotAdvanceLorebookCursor()
    {
        val station = stationWithLorebook(
            typedLorebookPayload(listOf(LorebookUpdate(key = "alice", value = "developer", weight = 5)))
        )
        assertEquals(-1, station.lorebookCursorInternal.lastUpdatedTurnIndex)
        station.setPreMemoryFunction { _, _ -> null }
        runBlocking { station.updateLorebook() }
        assertEquals(
            -1,
            station.lorebookCursorInternal.lastUpdatedTurnIndex,
            "a rejected lorebook write must leave the cursor untouched so the turns are re-offered"
        )
    }

    @Test
    fun hookCanModifyLorebookUpdates()
    {
        val station = stationWithLorebook(
            typedLorebookPayload(
                listOf(
                    LorebookUpdate(key = "alice", value = "developer", weight = 5),
                    LorebookUpdate(key = "mallory", value = "suspicious", weight = 9)
                )
            )
        )
        station.setPreMemoryFunction { proposal, _ ->
            val lorebook = proposal as MemoryWriteProposal.Lorebook
            lorebook.copy(updates = lorebook.updates.filter { it.key != "mallory" })
        }
        runBlocking { station.updateLorebook() }
        assertNotNull(station.contextWindow.loreBookKeys["alice"], "approved entry should land")
        assertNull(station.contextWindow.loreBookKeys["mallory"], "filtered entry must not land")
    }

    @Test
    fun hookCanForceReplaceOperation()
    {
        val station = PumpStation().setDispatchAgent(Pipeline())
        val first = typedLorebookPayload(
            listOf(LorebookUpdate(key = "alice", value = "first", weight = 3)),
            compactedThroughTurn = 3
        )
        val second = typedLorebookPayload(
            listOf(LorebookUpdate(key = "alice", value = "second", weight = 7)),
            compactedThroughTurn = 4
        )
        station.setLorebookAgent(
            MockP2PAgent(
                script = listOf(
                    MultimodalContent(text = first),
                    MultimodalContent(text = second)
                )
            )
        )
        runBlocking { station.updateLorebook() }
        assertEquals("first", station.contextWindow.loreBookKeys["alice"]?.value)
        assertEquals(3, station.lorebookCursorInternal.lastUpdatedTurnIndex)

        // Second round: the hook forces Replace so the value is overwritten, not concatenated.
        station.setPreMemoryFunction { proposal, _ ->
            val lorebook = proposal as MemoryWriteProposal.Lorebook
            lorebook.copy(
                updates = lorebook.updates.map { it.copy(operation = LorebookOperation.Replace) }
            )
        }
        runBlocking { station.updateLorebook() }
        val entry = station.contextWindow.loreBookKeys["alice"]
        assertNotNull(entry)
        assertEquals("second", entry.value)
        assertEquals(7, entry.weight)
    }

    @Test
    fun hookReceivesSummaryProposal()
    {
        val station = PumpStation().setDispatchAgent(Pipeline())
        station.setSummaryAgent(MockP2PAgent(script = listOf(MultimodalContent(text = "summary body"))))
        var seen: MemoryWriteProposal.Summary? = null
        station.setPreMemoryFunction { proposal, _ ->
            seen = proposal as? MemoryWriteProposal.Summary
            proposal
        }
        runBlocking { station.updateSummary() }
        assertNotNull(seen, "hook should have received the summary proposal")
        assertEquals("summary body", seen!!.text)
    }

    @Test
    fun hookReturningNullRejectsSummaryWrite()
    {
        val station = PumpStation().setDispatchAgent(Pipeline())
        station.setSummaryAgent(MockP2PAgent(script = listOf(MultimodalContent(text = "rejected summary"))))
        station.turnSummary = "previous summary"
        station.setPreMemoryFunction { _, _ -> null }
        runBlocking { station.updateSummary() }
        assertEquals(
            "previous summary",
            station.turnSummary,
            "a rejected summary write must leave the previous summary in place"
        )
    }

    @Test
    fun hookReturningMismatchedSubtypeRejectsWrite()
    {
        val station = stationWithLorebook(
            typedLorebookPayload(listOf(LorebookUpdate(key = "alice", value = "developer", weight = 5)))
        )
        // The hook answers a lorebook proposal with a summary proposal.
        station.setPreMemoryFunction { _, _ ->
            MemoryWriteProposal.Summary(runId = "r", turnIndex = 0, text = "wrong type")
        }
        runBlocking { station.updateLorebook() }
        assertTrue(
            station.contextWindow.loreBookKeys.isEmpty(),
            "a mismatched proposal subtype must be rejected, not applied"
        )
        assertEquals(-1, station.lorebookCursorInternal.lastUpdatedTurnIndex)
    }

    @Test
    fun hookThrowingRejectsWriteAndHarnessSurvives()
    {
        val station = stationWithLorebook(
            typedLorebookPayload(listOf(LorebookUpdate(key = "alice", value = "developer", weight = 5)))
        )
        station.setPreMemoryFunction { _, _ -> throw IllegalStateException("boom") }
        // The exception must not escape the memory job.
        runBlocking { station.updateLorebook() }
        assertTrue(
            station.contextWindow.loreBookKeys.isEmpty(),
            "a throwing hook must fail closed and not apply the write"
        )
        assertNull(station.getTaskState().lastError, "a hook exception must not surface as a harness error")
    }

    @Test
    fun memoryWriteEvaluatedEventEmittedOnApproveAndReject()
    {
        val events = mutableListOf<PumpStationEvent>()
        val station = stationWithLorebook(
            typedLorebookPayload(listOf(LorebookUpdate(key = "alice", value = "developer", weight = 5)))
        )
        station.setEventObserver { events.add(it) }
        station.setPreMemoryFunction { proposal, _ -> proposal }
        runBlocking { station.updateLorebook() }

        val approved = events.filterIsInstance<MemoryWriteEvaluated>()
        assertEquals(1, approved.size, "expected exactly one evaluation event; got $approved")
        assertTrue(approved[0].approved, "the approved write should report approved = true")
        assertEquals(MemoryWriteKind.LOREBOOK, approved[0].kind)
        assertEquals(1, approved[0].updateCount)

        events.clear()
        val rejecting = stationWithLorebook(
            typedLorebookPayload(listOf(LorebookUpdate(key = "alice", value = "developer", weight = 5)))
        )
        rejecting.setEventObserver { events.add(it) }
        rejecting.setPreMemoryFunction { _, _ -> null }
        runBlocking { rejecting.updateLorebook() }

        val rejected = events.filterIsInstance<MemoryWriteEvaluated>()
        assertEquals(1, rejected.size, "expected exactly one evaluation event; got $rejected")
        assertTrue(!rejected[0].approved, "the rejected write should report approved = false")
    }

    @Test
    fun noEventEmittedWhenHookNotBound()
    {
        val events = mutableListOf<PumpStationEvent>()
        val station = stationWithLorebook(
            typedLorebookPayload(listOf(LorebookUpdate(key = "alice", value = "developer", weight = 5)))
        )
        station.setEventObserver { events.add(it) }
        runBlocking { station.updateLorebook() }
        assertTrue(
            events.none { it is MemoryWriteEvaluated },
            "no evaluation event should be published when no hook is bound"
        )
    }
}
