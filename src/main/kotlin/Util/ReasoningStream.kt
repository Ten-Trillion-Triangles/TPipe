package com.TTT.Util

/**
 * Delimiter contract and split-back helper for streamed model reasoning.
 *
 * Providers that stream internal model reasoning deliver it over the same
 * streaming callback channel as text, wrapped in a reasoning segment delimited
 * by [OPEN_TAG] / [CLOSE_TAG] (the "\u003Cthink\u003E" markers the Ollama
 * think-model wire convention already ships, so one parser covers both
 * streamed and legacy embedded reasoning).
 *
 * The stream direction is provider- and base-level (see
 * [com.TTT.Pipe.Pipe.emitReasoningStreamingStart] and friends). This object
 * owns the read-back side: [split] recovers the reasoning trace and the
 * visible text from a collected stream, with zero loss.
 */
object ReasoningStream
{
    /**
     * \u003C = "<", \u003E = ">" — escaped so the literals stay single-line
     * and survive tooling that mangles raw angle-bracket/newline strings.
     */
    const val OPEN_TAG = "<think>"
    const val CLOSE_TAG = "</think>"

    /**
     * A recovered pair from a collected reasoning stream.
     *
     * @property reasoning The concatenated reasoning trace (multiple segments
     * joined with a newline, mirroring the Ollama legacy split precedent).
     * Empty when no reasoning was streamed.
     * @property text The visible answer text with all reasoning segments
     * removed and the resulting whitespace collapsed.
     */
    data class ReasoningSplit(
        val reasoning: String,
        val text: String
    )

    private val completeSegment = Regex("(?is)" + OPEN_TAG + "(.*?)" + CLOSE_TAG)
    private val openTagOnly = Regex("(?is)" + OPEN_TAG)

    /**
     * Splits a collected reasoning stream into its reasoning trace and its
     * visible text.
     *
     * Behavior:
     * - No markers present: the entire input is text.
     * - Complete "
...
" segments: their
     * interiors are joined with a newline into `reasoning`; everything
     * outside the segments is `text`, trimmed.
     * - An unclosed trailing "
" (stream aborted mid-reasoning):
     * everything after it is reasoning; text is whatever preceded it.
     * - Matching is case-insensitive, so legacy embedded
     * variants (e.g. "Think") parse identically to the emitted canonical
     * form.
     *
     * @param content The collected stream content, potentially containing
     * reasoning segments.
     * @return The recovered [ReasoningSplit] pair.
     */
    fun split(content: String): ReasoningSplit
    {
        if(content.isEmpty())
        {
            return ReasoningSplit("", "")
        }

        // Unclosed trailing open tag: everything after it is reasoning.
        val trailingOpen = openTagOnly.findAll(content).toList().lastOrNull()
        if(trailingOpen != null && completeSegment.find(content) == null)
        {
            val reasoning = content.substring(trailingOpen.range.last + 1).trim()
            val text = content.substring(0, trailingOpen.range.first).trim()
            return ReasoningSplit(reasoning, text)
        }

        val segments = completeSegment.findAll(content).map { it.value }.toList()
        if(segments.isEmpty())
        {
            return ReasoningSplit("", content.trim())
        }

        val reasoning = segments
            /**
             * substring (not removeSurrounding): the parser regex is
             * case-insensitive for legacy markers, but String.removeSurrounding
             * matches case-sensitively and would fail on mixed-case variants.
             */
            .map { segment ->
                val interior = segment.substring(
                    OPEN_TAG.length, segment.length - CLOSE_TAG.length)
                interior.trim()
            }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
        val text = completeSegment.replace(content, "").trim()
        return ReasoningSplit(reasoning, text)
    }
}
