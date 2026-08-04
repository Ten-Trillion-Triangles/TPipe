package genericOpenAIPipe.api

import com.TTT.P2P.P2PError
import com.TTT.P2P.P2PException
import com.TTT.Util.deserialize
import genericOpenAIPipe.env.OpenAIResponsesResponse
import genericOpenAIPipe.env.OpenAIResponsesStreamEvent
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Parser for the SSE wire format of the OpenAI `/v1/responses` streaming endpoint.
 *
 * Each event is delivered as either:
 * ```
 * event: <type>
 * data: <json>
 * ```
 * or, less commonly, as a bare `data: <json>` line with the type embedded in the JSON.
 *
 * The pipe consumes:
 *  - text-content deltas (and the terminal `response.completed` marker) for the
 *    user-visible answer,
 *  - reasoning-text deltas (and the `response.reasoning_text.done` marker) for
 *    the model's chain-of-thought, which surfaces on
 *    `MultimodalContent.modelReasoning` and ends up in the trace as
 *    `reasoningContent`.
 *
 * Implementation choices:
 *  - sealed-class hierarchy so callers can `when`-match exhaustively,
 *  - returns [OpenAIResponsesStreamEvent.Unknown] for any event type the pipe
 *    does not model — intentional, so the stream keeps going when OpenAI rolls
 *    out new event types,
 *  - throws [P2PException] on `event: error` so streaming failures surface as
 *    P2P exceptions just like non-streaming errors.
 */
object OpenAIResponsesSseParser
{

    /**
     * Parses one raw SSE line (which may itself carry both the `event:` and `data:`
     * fields, separated by a newline) into an [OpenAIResponsesStreamEvent].
     *
     * Empty / comment lines yield [OpenAIResponsesStreamEvent.Unknown] so the stream
     * loop can `continue` without special-casing them.
     *
     * @param line Raw line from the SSE stream
     * @return The parsed [OpenAIResponsesStreamEvent]
     * @throws P2PException when the line is an `error` event
     */
    fun parseLine(line: String): OpenAIResponsesStreamEvent
    {
        val raw = line.trim()
        if(raw.isEmpty()) return OpenAIResponsesStreamEvent.Unknown(raw)
        if(raw.startsWith(":")) return OpenAIResponsesStreamEvent.Unknown(raw)

        val (eventType, dataJson) = splitEventAndData(raw)
        if(dataJson.isBlank()) return OpenAIResponsesStreamEvent.Unknown(raw)

        // The terminal `[DONE]` sentinel that the chat-completions API uses.
        // The Responses API does not officially emit it, but we tolerate it for
        // proxy/middleware compatibility.
        if(dataJson == "[DONE]") return OpenAIResponsesStreamEvent.Unknown(raw)

        return when(eventType ?: peekEventType(dataJson))
        {
            "response.created" -> parseResponseCreated(dataJson, raw)
            "response.in_progress" -> parseResponseInProgress(dataJson, raw)
            "response.completed" -> parseResponseCompleted(dataJson, raw)
            "response.failed" -> parseResponseFailed(dataJson, raw)
            "response.output_text.delta" -> parseOutputTextDelta(dataJson, raw)
            "response.output_text.done" -> parseOutputTextDone(dataJson, raw)
            "response.reasoning_text.delta" -> parseReasoningTextDelta(dataJson, raw)
            "response.reasoning_text.done" -> parseReasoningTextDone(dataJson, raw)
            // Mantle emits the shorter event names on its `/v1/responses`
            // SSE wire (`response.reasoning.delta` / `response.reasoning.done`)
            // instead of OpenAI's `response.reasoning_text.delta` /
            // `…_done`. Both shapes decode to the same wire payload — a
            // `TextDeltaWrapper`/`TextDoneWrapper` carrying `delta`/`text`
            // — so they share the existing parsers without changes.
            "response.reasoning.delta" -> parseReasoningTextDelta(dataJson, raw)
            "response.reasoning.done" -> parseReasoningTextDone(dataJson, raw)
            "response.function_call_arguments.delta" -> parseFunctionCallArgumentsDelta(dataJson, raw)
            "response.function_call_arguments.done" -> parseFunctionCallArgumentsDone(dataJson, raw)
            "error" -> throwError(dataJson)
            null -> OpenAIResponsesStreamEvent.Unknown(raw)
            else -> OpenAIResponsesStreamEvent.Unknown(raw)
        }
    }

    /**
     * Extracts the text-delta payload from an [OpenAIResponsesStreamEvent], or
     * returns `null` for events that do not carry text.
     *
     * Returns the delta for both `response.output_text.delta` and
     * `response.reasoning_text.delta` events — callers that want to treat
     * reasoning text differently (e.g. accumulate it on a separate buffer)
     * should `when`-match on the event type themselves.
     */
    fun extractTextDelta(event: OpenAIResponsesStreamEvent): String?
    {
        return when(event)
        {
            is OpenAIResponsesStreamEvent.ResponseOutputTextDelta -> event.delta
            is OpenAIResponsesStreamEvent.ResponseReasoningTextDelta -> event.delta
            else -> null
        }
    }

    /**
     * Same as [extractTextDelta] — kept as an explicit alias for the
     * "output text content" name used by the existing unit tests.
     */
    fun extractContentDelta(event: OpenAIResponsesStreamEvent): String?
    {
        return extractTextDelta(event)
    }

    /**
     * Splits a possibly multi-line raw input into (`eventType`, `dataJson`).
     *
     * Handles both shapes:
     *  - `event: foo\ndata: {...}` (the canonical form),
     *  - `data: {...}` (bare, no event prefix).
     */
    private fun splitEventAndData(raw: String): Pair<String?, String>
    {
        var eventType: String? = null
        var dataJson: String? = null

        for(part in raw.split("\n"))
        {
            val trimmed = part.trim()
            if(trimmed.isEmpty()) continue
            if(trimmed.startsWith("event:"))
            {
                eventType = trimmed.substringAfter("event:").trim()
            }
            else if(trimmed.startsWith("data:"))
            {
                dataJson = trimmed.substringAfter("data:").trim()
            }
        }

        return eventType to (dataJson ?: "")
    }

    /**
     * Peek the JSON `type` field for a bare-`data:` line where the type discriminator
     * sits inside the JSON payload.
     */
    private fun peekEventType(json: String): String?
    {
        return try
        {
            val obj: JsonObject = kotlinx.serialization.json.Json.parseToJsonElement(json).jsonObject
            (obj["type"] as? JsonPrimitive)?.jsonPrimitive?.content
        }
        catch(e: Exception)
        {
            null
        }
    }

    private fun parseResponseCreated(json: String, raw: String): OpenAIResponsesStreamEvent
    {
        val wrapper: ResponseCreatedWrapper? = try { deserialize<ResponseCreatedWrapper>(json) } catch(e: Exception) { null }
        val response: OpenAIResponsesResponse = wrapper?.response ?: placeholderResponse(raw)
        return OpenAIResponsesStreamEvent.ResponseCreated(response)
    }

    private fun parseResponseInProgress(json: String, raw: String): OpenAIResponsesStreamEvent
    {
        val wrapper: ResponseCreatedWrapper? = try { deserialize<ResponseCreatedWrapper>(json) } catch(e: Exception) { null }
        val response: OpenAIResponsesResponse = wrapper?.response ?: placeholderResponse(raw)
        return OpenAIResponsesStreamEvent.ResponseInProgress(response)
    }

    private fun parseResponseCompleted(json: String, raw: String): OpenAIResponsesStreamEvent
    {
        val wrapper: ResponseCreatedWrapper? = try { deserialize<ResponseCreatedWrapper>(json) } catch(e: Exception) { null }
        val response: OpenAIResponsesResponse = wrapper?.response ?: placeholderResponse(raw)
        return OpenAIResponsesStreamEvent.ResponseCompleted(response)
    }

    private fun parseResponseFailed(json: String, raw: String): OpenAIResponsesStreamEvent
    {
        val wrapper: ResponseCreatedWrapper? = try { deserialize<ResponseCreatedWrapper>(json) } catch(e: Exception) { null }
        val response: OpenAIResponsesResponse = wrapper?.response ?: placeholderResponse(raw)
        return OpenAIResponsesStreamEvent.ResponseFailed(response)
    }

    private fun parseOutputTextDelta(json: String, raw: String): OpenAIResponsesStreamEvent
    {
        val wrapper: TextDeltaWrapper = (try { deserialize<TextDeltaWrapper>(json) } catch(e: Exception) { null })
            ?: return OpenAIResponsesStreamEvent.Unknown(raw)
        return OpenAIResponsesStreamEvent.ResponseOutputTextDelta(
            itemId = wrapper.item_id,
            outputIndex = wrapper.output_index ?: 0,
            contentIndex = wrapper.content_index ?: 0,
            delta = wrapper.delta ?: ""
        )
    }

    private fun parseOutputTextDone(json: String, raw: String): OpenAIResponsesStreamEvent
    {
        val wrapper: TextDoneWrapper = (try { deserialize<TextDoneWrapper>(json) } catch(e: Exception) { null })
            ?: return OpenAIResponsesStreamEvent.Unknown(raw)
        return OpenAIResponsesStreamEvent.ResponseOutputTextDone(
            itemId = wrapper.item_id,
            outputIndex = wrapper.output_index ?: 0,
            contentIndex = wrapper.content_index ?: 0,
            text = wrapper.text ?: ""
        )
    }

    private fun parseReasoningTextDelta(json: String, raw: String): OpenAIResponsesStreamEvent
    {
        // The wire shape is identical to `output_text.delta`, only the
        // `type` discriminator differs. The wrapper type is also shared.
        val wrapper: TextDeltaWrapper = (try { deserialize<TextDeltaWrapper>(json) } catch(e: Exception) { null })
            ?: return OpenAIResponsesStreamEvent.Unknown(raw)
        return OpenAIResponsesStreamEvent.ResponseReasoningTextDelta(
            itemId = wrapper.item_id,
            outputIndex = wrapper.output_index ?: 0,
            contentIndex = wrapper.content_index ?: 0,
            delta = wrapper.delta ?: ""
        )
    }

    private fun parseReasoningTextDone(json: String, raw: String): OpenAIResponsesStreamEvent
    {
        val wrapper: TextDoneWrapper = (try { deserialize<TextDoneWrapper>(json) } catch(e: Exception) { null })
            ?: return OpenAIResponsesStreamEvent.Unknown(raw)
        return OpenAIResponsesStreamEvent.ResponseReasoningTextDone(
            itemId = wrapper.item_id,
            outputIndex = wrapper.output_index ?: 0,
            contentIndex = wrapper.content_index ?: 0,
            text = wrapper.text ?: ""
        )
    }

    private fun parseFunctionCallArgumentsDelta(json: String, raw: String): OpenAIResponsesStreamEvent
    {
        val wrapper: FunctionCallDeltaWrapper = (try { deserialize<FunctionCallDeltaWrapper>(json) } catch(e: Exception) { null })
            ?: return OpenAIResponsesStreamEvent.Unknown(raw)
        return OpenAIResponsesStreamEvent.ResponseFunctionCallArgumentsDelta(
            itemId = wrapper.item_id,
            outputIndex = wrapper.output_index ?: 0,
            delta = wrapper.delta ?: ""
        )
    }

    private fun parseFunctionCallArgumentsDone(json: String, raw: String): OpenAIResponsesStreamEvent
    {
        val wrapper: FunctionCallDoneWrapper = (try { deserialize<FunctionCallDoneWrapper>(json) } catch(e: Exception) { null })
            ?: return OpenAIResponsesStreamEvent.Unknown(raw)
        return OpenAIResponsesStreamEvent.ResponseFunctionCallArgumentsDone(
            itemId = wrapper.item_id,
            outputIndex = wrapper.output_index ?: 0,
            arguments = wrapper.arguments ?: ""
        )
    }

    /**
     * Throws a [P2PException] with the right [P2PError] family for an `error` event,
     * using the same auth/transport/prompt mapping the non-streaming parser uses.
     */
    private fun throwError(json: String): Nothing
    {
        val wrapper: ErrorEventWrapper? = try { deserialize<ErrorEventWrapper>(json) } catch(e: Exception) { null }
        val code = wrapper?.code ?: "unknown_error"
        val message = wrapper?.message ?: "Unknown OpenAI Responses streaming error"
        val param = wrapper?.param

        val p2pError = when
        {
            code == "invalid_api_key" || code == "authentication_error" -> P2PError.auth
            code == "rate_limit_exceeded" || code == "rate_limit_error" -> P2PError.transport
            code == "invalid_request" || code == "invalid_request_error" -> P2PError.prompt
            code.startsWith("server_") || code == "api_error" -> P2PError.transport
            param != null -> P2PError.prompt
            else -> P2PError.transport
        }
        throw P2PException(
            p2pError,
            "OpenAI Responses streaming error ($code): $message",
            Exception(message)
        )
    }

    /**
     * Builds a placeholder [OpenAIResponsesResponse] for lifecycle events whose JSON
     * we could not parse; the stream loop still gets a non-null response object so
     * it can record a `response.created` event without crashing.
     */
    private fun placeholderResponse(raw: String): OpenAIResponsesResponse
    {
        return OpenAIResponsesResponse(
            id = "",
            objectType = "response",
            createdAt = 0L,
            model = "",
            status = "unknown",
            output = emptyList(),
            usage = null,
            error = null
        )
    }
}

//=========================================Wire-Spec Wrappers (used only by the SSE parser)=============================
//
// The Responses API puts a `response` object inside lifecycle events and flat
// `item_id`/`delta` fields on the delta events. Modelling them inline in the sealed
// hierarchy would require either optional fields on every variant or a single
// `JsonObject` field; the wrapper approach keeps the wire-spec-faithful types
// (snake_case) out of the public env/ models.

@Serializable
private data class ResponseCreatedWrapper(
    val response: OpenAIResponsesResponse? = null
)

@Serializable
private data class TextDeltaWrapper(
    @kotlinx.serialization.SerialName("item_id") val item_id: String? = null,
    @kotlinx.serialization.SerialName("output_index") val output_index: Int? = null,
    @kotlinx.serialization.SerialName("content_index") val content_index: Int? = null,
    val delta: String? = null
)

@Serializable
private data class TextDoneWrapper(
    @kotlinx.serialization.SerialName("item_id") val item_id: String? = null,
    @kotlinx.serialization.SerialName("output_index") val output_index: Int? = null,
    @kotlinx.serialization.SerialName("content_index") val content_index: Int? = null,
    val text: String? = null
)

@Serializable
private data class FunctionCallDeltaWrapper(
    @kotlinx.serialization.SerialName("item_id") val item_id: String? = null,
    @kotlinx.serialization.SerialName("output_index") val output_index: Int? = null,
    val delta: String? = null
)

@Serializable
private data class FunctionCallDoneWrapper(
    @kotlinx.serialization.SerialName("item_id") val item_id: String? = null,
    @kotlinx.serialization.SerialName("output_index") val output_index: Int? = null,
    val arguments: String? = null
)

@Serializable
private data class ErrorEventWrapper(
    val code: String? = null,
    val message: String? = null,
    val param: String? = null
)
