package genericOpenAIPipe.env

import com.TTT.P2P.P2PError
import com.TTT.P2P.P2PException
import com.TTT.Util.deserialize
import kotlinx.serialization.Serializable

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

/**
 * Parser for Anthropic's SSE streaming format (`data: {...}` lines from /v1/messages).
 *
 * Anthropic streams events in this format:
 * ```
 * data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hello"}}
 * data: {"type":"message_delta","stop_reason":"end_turn","usage":{"input_tokens":10,"output_tokens":20}}
 * data: [DONE]
 * ```
 *
 * Error events:
 * ```
 * data: {"type":"error","error":{"type":"authentication_error","message":"..."}}
 * ```
 *
 * @see [AnthropicStreaming] for the data classes this parser produces
 */
object AnthropicSseParser
{
    /**
     * Parses a raw SSE `data: {...}` line into an [AnthropicStreamEvent].
     *
     * @param line Raw line from the SSE stream (may include or omit the `data: ` prefix)
     * @return The corresponding [AnthropicStreamEvent]
     * @throws P2PException when the line contains an error event
     */
    fun parseAnthropicLine(line: String): AnthropicStreamEvent
    {
        val trimmed = line.trim()

        if(trimmed.isEmpty())
        {
            return AnthropicStreamEvent.Done
        }

        // Strip "data: " prefix if present
        val json = if(trimmed.startsWith("data: "))
        {
            trimmed.substringAfter("data: ")
        }
        else if(trimmed.startsWith("data:"))
        {
            trimmed.substringAfter("data:")
        }
        else
        {
            return AnthropicStreamEvent.Done
        }

        // [DONE] terminal marker
        if(json == "[DONE]" || json.trim() == "[DONE]")
        {
            return AnthropicStreamEvent.Done
        }

        // Peek at the event type before committing to a parse strategy
        val eventType = json.extractJsonString("type")

        when(eventType)
        {
            "content_block_delta" ->
            {
                val chunk = parseAnthropicChunk(json) ?: return AnthropicStreamEvent.Done
                return AnthropicStreamEvent.ContentBlockDelta(chunk)
            }
            "message_delta" ->
            {
                // message_delta structure: {"type":"message_delta","stop_reason":"end_turn",
                //                         "usage":{"input_tokens":10,"output_tokens":20}}
                // delta field is absent or null for message_delta events
                val stopReason = json.extractJsonString("stop_reason")
                val usage = parseAnthropicUsageInfo(json)
                return AnthropicStreamEvent.MessageDelta(stopReason, usage)
            }
            "error" ->
            {
                val err = parseAnthropicError(json)
                throw P2PException(
                    errorTypeToP2PError(err.type),
                    "Anthropic streaming error: ${err.message}",
                    Exception(err.message)
                )
            }
            else -> return AnthropicStreamEvent.Unknown
        }
    }

    /**
     * Deserializes a JSON string into an [AnthropicStreamingChunk].
     *
     * @param json Raw JSON string from a data line
     * @return The parsed chunk, or null if deserialization fails
     */
    fun parseAnthropicChunk(json: String): AnthropicStreamingChunk?
    {
        return try
        {
            deserialize<AnthropicStreamingChunk>(json)
        }
        catch(e: Exception)
        {
            null
        }
    }

    /**
     * Extracts text content from an [AnthropicStreamingChunk].
     *
     * @param chunk The streaming chunk
     * @return The text from a [AnthropicDelta.TextDelta], or empty string for [AnthropicDelta.InputJsonDelta]
     */
    fun extractContent(chunk: AnthropicStreamingChunk): String
    {
        return when(val delta = chunk.delta)
        {
            is AnthropicDelta.TextDelta -> delta.text
            is AnthropicDelta.ThinkingDelta -> delta.thinking
            is AnthropicDelta.InputJsonDelta -> ""   // structured output — caller handles separately
            else -> ""
        }
    }

    /**
     * Convenience: parse a raw SSE line and extract its text content in one call.
     *
     * @param line Raw SSE line
     * @return The text content delta, or null if the line is not a content block
     */
    fun extractContentFromLine(line: String): String?
    {
        return try
        {
            val event = parseAnthropicLine(line)
            if(event is AnthropicStreamEvent.ContentBlockDelta)
            {
                extractContent(event.chunk)
            }
            else null
        }
        catch(e: P2PException)
        {
            throw e   // re-throw errors instead of swallowing them
        }
        catch(e: Exception)
        {
            null
        }
    }

    /**
     * Maps an Anthropic error type string to the corresponding [P2PError] enum value.
     */
    private fun errorTypeToP2PError(type: String): P2PError
    {
        return when(type)
        {
            "authentication_error" -> P2PError.auth
            "invalid_request_error", "invalid_api_key" -> P2PError.prompt
            "rate_limit_error", "api_error", "server_error" -> P2PError.transport
            else -> P2PError.transport
        }
    }

    /**
     * Lightweight error parser for error events — extracts type and message without
     * requiring a full [AnthropicErrorResponse] deserialization into the event stream.
     */
    private fun parseAnthropicError(json: String): AnthropicErrorDetail
    {
        return try
        {
            deserialize<AnthropicErrorResponse>(json)?.error
                ?: throw IllegalStateException("Failed to parse AnthropicErrorResponse")
        }
        catch(e: Exception)
        {
            // Fallback: extract type and message via simple string operations
            // to avoid deserialization failures masking the original error
            val type = json.extractJsonString("type") ?: "server_error"
            val message = json.extractJsonString("message") ?: "Unknown Anthropic streaming error"
            AnthropicErrorDetail(type, message)
        }
    }

    /**
     * Parses the `usage` block from a message_delta JSON string.
     *
     * message_delta usage looks like: "usage":{"input_tokens":10,"output_tokens":20}
     * We extract these via regex since the top-level fields don't deserialize into
     * AnthropicStreamingChunk (which expects a `delta` field that message_delta lacks).
     */
    private fun parseAnthropicUsageInfo(json: String): AnthropicUsageInfo?
    {
        val inputTokens = json.extractJsonString("input_tokens")?.toIntOrNull() ?: return null
        val outputTokens = json.extractJsonString("output_tokens")?.toIntOrNull() ?: return null
        return AnthropicUsageInfo(inputTokens, outputTokens)
    }
}

/**
 * Private extension function used by [AnthropicSseParser] to extract string values
 * from JSON when full deserialization is not warranted (peeking at event type,
 * or fallback extraction for error events).
 */
private fun String.extractJsonString(key: String): String?
{
    val pattern = """"$key"\s*:\s*"?([^",}]+)"?"""
    return Regex(pattern).find(this)?.groupValues?.get(1)?.trim()?.trim('"', ' ', '\n', '\r')
}