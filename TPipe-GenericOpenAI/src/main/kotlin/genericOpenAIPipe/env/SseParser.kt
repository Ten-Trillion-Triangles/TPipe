package genericOpenAIPipe.env

import com.TTT.Util.deserialize

/**
 * Parser for Server-Sent Events (SSE) streams.
 *
 * Handles the OpenAI-compatible SSE format used by OpenRouter's streaming chat completions.
 * Each chunk is a line of the form: `data: {...json...}`
 *
 * @see <a href="https://developer.mozilla.org/en-US/docs/Web/API/Server-sent_events/Using_server-sent_events">SSE Format</a>
 */
object SseParser
{
    /**
     * Result of parsing an SSE line.
     */
    sealed class SseLine
    {
        /** A data line with parsed content */
        data class Data(val content: String) : SseLine()

        /** The terminal `[DONE]` signal */
        data object Done : SseLine()

        /** A comment line (starts with `:`) */
        data object Comment : SseLine()

        /** An empty line */
        data object Empty : SseLine()

        /** A malformed or unrecognized line */
        data class Invalid(val raw: String) : SseLine()
    }

    /**
     * Parses a single SSE line.
     *
     * @param line The raw line from the SSE stream
     * @return SseLine indicating the type and content of the line
     */
    fun parseLine(line: String): SseLine
    {
        val trimmed = line.trim()

        if(trimmed.isEmpty())
        {
            return SseLine.Empty
        }

        if(trimmed.startsWith(":"))
        {
            return SseLine.Comment
        }

        if(trimmed == "data: [DONE]")
        {
            return SseLine.Done
        }

        if(trimmed.startsWith("data: "))
        {
            val json = trimmed.substringAfter("data: ")
            return SseLine.Data(json)
        }

        return SseLine.Invalid(line)
    }

    /**
     * Parses a data JSON string into a StreamingChunk.
     *
     * @param json The JSON string from a `data: {...}` line
     * @return The parsed StreamingChunk, or null if parsing fails
     */
    fun parseChunk(json: String): StreamingChunk?
    {
        return try
        {
            deserialize<StreamingChunk>(json)
        }
        catch(e: Exception)
        {
            null
        }
    }

    /**
     * Extracts text content from a StreamingChunk delta.
     *
     * @param chunk The streaming chunk
     * @return The text content from the first choice's delta, or empty string if none
     */
    fun extractContent(chunk: StreamingChunk): String
    {
        return chunk.choices.firstOrNull()?.delta?.content ?: ""
    }

    /**
     * Processes a raw SSE line and returns the content delta if it's a valid data chunk.
     *
     * This is a convenience method that combines parseLine + parseChunk + extractContent.
     *
     * @param line Raw SSE line
     * @return The text content delta, or null if not a data chunk
     */
    fun extractContentFromLine(line: String): String?
    {
        return when(val parsed = parseLine(line))
        {
            is SseLine.Data ->
            {
                val chunk = parseChunk(parsed.content)
                chunk?.let { extractContent(it) }
            }
            else -> null
        }
    }

    /**
     * Iterates over SSE lines from an iterator, emitting content deltas.
     *
     * @param lines Iterator of raw SSE lines
     * @param onChunk Called for each successfully parsed content delta
     * @param onDone Called when `[DONE]` is received
     * @return Total accumulated text
     */
    fun iterateLines(
        lines: Iterator<String>,
        onChunk: (String) -> Unit = {},
        onDone: () -> Unit = {}
    ): String
    {
        val accumulator = StringBuilder()

        while(lines.hasNext())
        {
            val line = lines.next()
            val sseLine = parseLine(line)

            val content = if(sseLine is SseLine.Data)
            {
                parseChunk(sseLine.content)?.let { extractContent(it) }
            }
            else null

            if(content != null && content.isNotEmpty())
            {
                accumulator.append(content)
                onChunk(content)
            }

            when(sseLine)
            {
                is SseLine.Done ->
                {
                    onDone()
                    break
                }
                else -> { /* continue */ }
            }
        }

        return accumulator.toString()
    }
}