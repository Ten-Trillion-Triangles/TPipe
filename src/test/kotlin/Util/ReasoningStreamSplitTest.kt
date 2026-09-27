package com.TTT.Util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Spec for [ReasoningStream.split] — the split-back helper that recovers the
 * reasoning trace and visible text from a collected reasoning stream.
 *
 * Covers: no markers, single/multiple complete segments, unclosed trailing
 * open tag (aborted stream), case-insensitive legacy markers, empty input,
 * and adjacent empty segments.
 */
class ReasoningStreamSplitTest
{
    private val openTag = ReasoningStream.OPEN_TAG
    private val closeTag = ReasoningStream.CLOSE_TAG

    @Test
    fun noMarkersReturnsEntireInputAsText()
    {
        val split = ReasoningStream.split("Just an answer, no reasoning markers here.")
        assertEquals("", split.reasoning)
        assertEquals("Just an answer, no reasoning markers here.", split.text)
    }

    @Test
    fun emptyInputReturnsEmptyPair()
    {
        val split = ReasoningStream.split("")
        assertEquals("", split.reasoning)
        assertEquals("", split.text)
    }

    @Test
    fun singleCompleteSegmentSplitsCleanly()
    {
        val content = "$openTag step one... step two...$closeTag Here is the answer."
        val split = ReasoningStream.split(content)
        assertEquals("step one... step two...", split.reasoning)
        assertEquals("Here is the answer.", split.text)
    }

    @Test
    fun multipleSegmentsJoinReasoningWithNewline()
    {
        // Reasoning interiors join with a newline; text is the segments
        // removed, ends trimmed (interior spacing between segments is kept
        // as-is — providers emit segments adjacent, no inter-marker spaces).
        val content = "${openTag}A${closeTag} mid ${openTag}B${closeTag} end"
        val split = ReasoningStream.split(content)
        assertEquals("A\nB", split.reasoning)
        assertEquals("mid  end", split.text)
    }

    @Test
    fun unclosedTrailingOpenTagPutsTailIntoReasoning()
    {
        // Stream aborted mid-reasoning: open marker present, no close.
        val content = "$openTag partial thought never finished"
        val split = ReasoningStream.split(content)
        assertEquals("partial thought never finished", split.reasoning)
        assertEquals("", split.text)
    }

    @Test
    fun unclosedTrailingOpenTagPreservesLeadingText()
    {
        val content = "intro text${openTag} tail reasoning"
        val split = ReasoningStream.split(content)
        assertEquals("tail reasoning", split.reasoning)
        assertEquals("intro text", split.text)
    }

    @Test
    fun caseInsensitiveLegacyMarkersParse()
    {
        // Legacy Ollama embedded variants use mixed case; parser must be
        // case-insensitive. \u003C / \u003E are < / > — escaped to keep the
        // literal single-line in source.
        val content = "\u003CThink\u003Elegacy-thought\u003C/Think\u003Eanswer"
        val split = ReasoningStream.split(content)
        assertEquals("legacy-thought", split.reasoning)
        assertEquals("answer", split.text)
    }

    @Test
    fun adjacentEmptySegmentsProduceNoBlankLines()
    {
        val content = "${openTag}$closeTag${openTag}real$closeTag"
        val split = ReasoningStream.split(content)
        assertEquals("real", split.reasoning)
        assertTrue(!split.reasoning.contains("\n"))
    }

    @Test
    fun roundTripMatchesEmissionShape()
    {
        // Mirror the exact base-hook emission sequence: open, chunks, close, text.
        val collected = buildString {
            append(openTag)
            append("thought-a")
            append("thought-b")
            append(closeTag)
            append("answer")
        }
        val split = ReasoningStream.split(collected)
        assertEquals("thought-athought-b", split.reasoning)
        assertEquals("answer", split.text)
    }
}
