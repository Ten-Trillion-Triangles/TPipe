package genericOpenAIPipe

import com.TTT.Util.deserialize
import genericOpenAIPipe.env.AnthropicDelta
import genericOpenAIPipe.env.AnthropicSseParser
import genericOpenAIPipe.env.AnthropicStreamEvent
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Pins the contract that the Anthropic SSE dispatcher used by
 * `executeStreamingDirect` (`GenericOpenAIPipe.kt:948`) correctly produces
 * typed events from real wire payloads.
 *
 * The production bug was that `executeStreamingDirect` called
 * `deserialize<AnthropicStreamEvent>` directly. That call returned null for
 * EVERY Anthropic SSE payload — including valid `content_block_delta` events
 * — because the sealed class's subclasses don't share a common `type` field
 * shape that kotlinx.serialization can dispatch on, and the wire JSON for
 * `content_block_delta` doesn't match the `ContentBlockDelta(val chunk)`
 * class shape (the wire has `index`+`delta` at the outer level, not nested
 * under `chunk`). The fix replaces the direct `deserialize` call with the
 * existing `AnthropicSseParser.parseAnthropicLine` wrapper, which manually
 * dispatches by the outer `type` field.
 *
 * These tests verify the contract the fix pins:
 *
 *  1. `AnthropicSseParser.parseAnthropicLine` resolves every real wire event
 *     to the correct subclass (or `Unknown` for lifecycle events).
 *  2. `parseAnthropicLine` for `content_block_delta` with a `thinking_delta`
 *     inner delta correctly produces a `ThinkingDelta` accessible via
 *     `event.chunk.delta.thinking` — this is the Fix-2 contract: thinking
 *     content must be captured into the reasoning accumulator.
 *  3. The buggy direct `deserialize<AnthropicStreamEvent>` path returns null
 *     for the same payloads — this is the diagnostic that the bug existed and
 *     would silently regress if reintroduced.
 */
class AnthropicStreamingDispatchTest
{
    @Test
    fun parseAnthropicLine_resolves_text_delta_to_content_block_delta()
    {
        val line = """data: {"type":"content_block_delta","index":1,"delta":{"type":"text_delta","text":"Hello there"}}"""
        val event = AnthropicSseParser.parseAnthropicLine(line)
        assertTrue(event is AnthropicStreamEvent.ContentBlockDelta, "Expected ContentBlockDelta, got ${event::class.simpleName}")
        val delta = (event as AnthropicStreamEvent.ContentBlockDelta).chunk.delta
        assertTrue(delta is AnthropicDelta.TextDelta, "Expected TextDelta, got ${delta::class.simpleName}")
        assertEquals("Hello there", (delta as AnthropicDelta.TextDelta).text)
    }

    @Test
    fun parseAnthropicLine_resolves_thinking_delta_to_thinking_delta_inner()
    {
        // Fix 2 contract: thinking_delta payloads must be reachable as
        // ThinkingDelta so the streaming parser can accumulate them into
        // the reasoning builder. This is the property the previous buggy
        // code at GenericOpenAIPipe.kt:963 silently dropped.
        val line = """data: {"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"The user wants a greeting"}}"""
        val event = AnthropicSseParser.parseAnthropicLine(line)
        assertTrue(event is AnthropicStreamEvent.ContentBlockDelta, "Expected ContentBlockDelta, got ${event::class.simpleName}")
        val delta = (event as AnthropicStreamEvent.ContentBlockDelta).chunk.delta
        assertTrue(delta is AnthropicDelta.ThinkingDelta, "Expected ThinkingDelta, got ${delta::class.simpleName}")
        assertEquals("The user wants a greeting", (delta as AnthropicDelta.ThinkingDelta).thinking)
    }

    @Test
    fun parseAnthropicLine_resolves_message_delta_with_stop_reason()
    {
        // Anthropic wire shape: stop_reason is nested under a `delta` field
        // and the parser extracts it manually via extractJsonString.
        val line = """data: {"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"input_tokens":50,"output_tokens":20}}"""
        val event = AnthropicSseParser.parseAnthropicLine(line)
        assertTrue(event is AnthropicStreamEvent.MessageDelta, "Expected MessageDelta, got ${event::class.simpleName}")
        assertEquals("end_turn", (event as AnthropicStreamEvent.MessageDelta).stopReason)
    }

    @Test
    fun parseAnthropicLine_maps_lifecycle_events_to_unknown()
    {
        // Events with no direct subclass (message_start, content_block_start,
        // content_block_stop, ping) should map to Unknown so the streaming
        // parser skips them rather than aborting.
        val lifecyclePayloads = listOf(
            """data: {"type":"message_start","message":{"id":"x","type":"message","role":"assistant"}}""",
            """data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""",
            """data: {"type":"content_block_stop","index":0}""",
            """data: {"type":"ping"}"""
        )
        for(line in lifecyclePayloads)
        {
            val event = AnthropicSseParser.parseAnthropicLine(line)
            assertEquals(
                AnthropicStreamEvent.Unknown, event,
                "Expected Unknown for lifecycle payload: ${line.take(80)}"
            )
        }
    }

    @Test
    fun direct_deserialize_of_streaming_event_remains_unsupported()
    {
        // Diagnostic: documents WHY the fix uses AnthropicSseParser instead of
        // deserialize<AnthropicStreamEvent>. If this assertion ever starts
        // returning non-null, the underlying constraint may have shifted and
        // the call-site at GenericOpenAIPipe.kt:955 can be revisited.
        val payload = """{"type":"content_block_delta","index":1,"delta":{"type":"text_delta","text":"Hello"}}"""
        val direct: AnthropicStreamEvent? = deserialize(payload)
        assertTrue(
            direct == null || direct !is AnthropicStreamEvent.ContentBlockDelta,
            "deserialize<AnthropicStreamEvent> unexpectedly started working — " +
                "revisit executeStreamingDirect to see if direct dispatch is now viable."
        )
    }
}