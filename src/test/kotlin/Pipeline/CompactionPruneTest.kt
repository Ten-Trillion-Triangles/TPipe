package com.TTT.Pipeline

import com.TTT.Context.ConverseData
import com.TTT.Context.ConverseRole
import com.TTT.Pipe.MultimodalContent
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for the v3 pre-prune step. Verifies all 8 default rules of
 * [defaultPrePruneForCompaction] and the developer-extension / replacement paths.
 */
class CompactionPruneTest
{
    private fun turn(
        role: ConverseRole,
        text: String,
        metadata: Map<Any, Any> = emptyMap()
    ): ConverseData
    {
        val c = MultimodalContent(text = text)
        c.metadata.putAll(metadata)
        return ConverseData(role = role, content = c)
    }

    @Test
    fun testRule1DropsBlankTurns() = runBlocking {
        val station = PumpStation().setDispatchAgent(Pipeline())
        val input = listOf(
            turn(ConverseRole.user, "hello"),
            turn(ConverseRole.assistant, ""),
            turn(ConverseRole.user, "   "),
            turn(ConverseRole.user, "world")
        )
        val out = station.defaultPrePruneForCompaction(input)
        assertEquals(2, out.size)
        assertEquals("hello", out[0].content.text)
        assertEquals("world", out[1].content.text)
    }

    @Test
    fun testRule2DropsStashPlaceholders() = runBlocking {
        val station = PumpStation().setDispatchAgent(Pipeline())
        val input = listOf(
            turn(ConverseRole.assistant, "real content"),
            turn(ConverseRole.assistant, "[Stashed: x — see manifest]", metadata = mapOf("stashId" to "x"))
        )
        val out = station.defaultPrePruneForCompaction(input)
        assertEquals(1, out.size)
        assertEquals("real content", out[0].content.text)
    }

    @Test
    fun testRule3KeepsOnlyMostRecentSystemMessage() = runBlocking {
        val station = PumpStation().setDispatchAgent(Pipeline())
        val input = listOf(
            turn(ConverseRole.system, "old system"),
            turn(ConverseRole.user, "msg 1"),
            turn(ConverseRole.system, "middle system"),
            turn(ConverseRole.user, "msg 2"),
            turn(ConverseRole.system, "newest system")
        )
        val out = station.defaultPrePruneForCompaction(input)
        // One system, two users. The kept system message stays at its original
        // (last) position because the rule only filters out earlier duplicates.
        assertEquals(3, out.size)
        val systemTurns = out.filter { it.role == ConverseRole.system }
        assertEquals(1, systemTurns.size)
        assertEquals("newest system", systemTurns[0].content.text)
        val userTexts = out.filter { it.role == ConverseRole.user }.map { it.content.text }
        assertEquals(listOf("msg 1", "msg 2"), userTexts)
    }

    @Test
    fun testRule4DropsPureEchoes() = runBlocking {
        val station = PumpStation().setDispatchAgent(Pipeline())
        val input = listOf(
            turn(ConverseRole.user, "what is X?"),
            turn(ConverseRole.user, "what is X?"),  // exact echo
            turn(ConverseRole.user, "what is X?"),  // another echo
            turn(ConverseRole.user, "what is Y?")
        )
        val out = station.defaultPrePruneForCompaction(input)
        assertEquals(2, out.size)
        assertEquals("what is X?", out[0].content.text)
        assertEquals("what is Y?", out[1].content.text)
    }

    @Test
    fun testRule5CollapsesToolCallResultPairs() = runBlocking {
        val station = PumpStation().setDispatchAgent(Pipeline())
        val input = listOf(
            turn(ConverseRole.tool_response, "tool output A"),
            turn(ConverseRole.assistant, "{\"name\":\"A\"}"),
            turn(ConverseRole.user, "ok thanks")
        )
        val out = station.defaultPrePruneForCompaction(input)
        // The pair should collapse into a single assistant turn, then the user turn
        // is preserved.
        assertEquals(2, out.size)
        assertEquals(ConverseRole.assistant, out[0].role)
        assertTrue(out[0].content.text.contains("[tool-call"))
    }

    @Test
    fun testRule6StripsExcessMetadata() = runBlocking {
        val station = PumpStation().setDispatchAgent(Pipeline())
        val input = listOf(
            turn(
                ConverseRole.assistant, "hi",
                metadata = mapOf("pathName" to "p1", "requestId" to "r1", "timestamp" to "now")
            )
        )
        val out = station.defaultPrePruneForCompaction(input)
        val meta = out[0].content.metadata
        assertTrue("pathName" in meta)
        assertTrue("requestId" !in meta)
        assertTrue("timestamp" !in meta)
    }

    @Test
    fun testRule7NormalizesWhitespace() = runBlocking {
        val station = PumpStation().setDispatchAgent(Pipeline())
        val input = listOf(
            turn(ConverseRole.user, "hello\n\n\n\n\nworld   ")
        )
        val out = station.defaultPrePruneForCompaction(input)
        assertEquals("hello\n\nworld", out[0].content.text)
    }

    @Test
    fun testRule8DropsTurnsAlreadyInSummary() = runBlocking {
        val station = PumpStation().setDispatchAgent(Pipeline())
        // Summary text contains the first turn verbatim, so it is considered
        // already-captured and should be dropped by Rule 8.
        station.turnSummary = "user asked about the weather in tokyo last week"
        val input = listOf(
            // 33 chars; lowercased "the weather in tokyo last week" is contained
            // in the summary, so this turn should be dropped.
            turn(ConverseRole.user, "the weather in Tokyo last week"),
            // 70 chars; not contained in the summary, should survive.
            turn(ConverseRole.user, "a wholly different query about something completely unrelated here")
        )
        val out = station.defaultPrePruneForCompaction(input)
        // The first turn is dropped (its text is in the summary).
        // The second survives.
        assertEquals(1, out.size)
        assertTrue(out[0].content.text.contains("wholly different"))
    }

    @Test
    fun testPrePruneTransformReplacement() = runBlocking {
        val station = PumpStation().setDispatchAgent(Pipeline())
        station.setPrePruneTransform { turns, _ ->
            turns.filter { it.content.text.startsWith("KEEP:") }
        }
        val input = listOf(
            turn(ConverseRole.user, "drop me"),
            turn(ConverseRole.user, "KEEP: me"),
            turn(ConverseRole.user, "also drop")
        )
        val out = station.prePruneForCompaction(input)
        assertEquals(1, out.size)
        assertEquals("KEEP: me", out[0].content.text)
    }

    @Test
    fun testAppendPrePruneTransformWrapsDefault() = runBlocking {
        val station = PumpStation().setDispatchAgent(Pipeline())
        station.appendPrePruneTransform { turns, _ ->
            // Tag every remaining turn with a marker.
            turns.map { turn ->
                val c = MultimodalContent(text = "[t] ${turn.content.text}")
                ConverseData(role = turn.role, content = c)
            }
        }
        val input = listOf(
            turn(ConverseRole.user, "hello"),
            turn(ConverseRole.user, "world")
        )
        val out = station.prePruneForCompaction(input)
        assertEquals(2, out.size)
        assertTrue(out[0].content.text.startsWith("[t] "))
        assertTrue(out[1].content.text.startsWith("[t] "))
    }
}
