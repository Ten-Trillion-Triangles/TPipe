package openrouterPipe

import com.TTT.P2P.P2PError
import com.TTT.P2P.P2PException
import env.OpenRouterResponsesOutputItem
import env.OpenRouterResponsesResponse
import env.OpenRouterResponsesUsage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Parses an OpenRouter Responses response body onto the P2P surface:
 *  - [parse] decodes the body, mapping the `{"error": {...}}` envelope and a
 *    `failed` response's embedded error onto a [P2PException]
 *  - [flatten] projects text / reasoning / function calls / usage onto a
 *    single [OpenRouterResponsesFlattened] result the pipe can surface.
 *
 * The flatten projection mirrors the chat-completions reasoning flatten:
 * `reasoning_text` + `summary_text` parts across reasoning items form the
 * reasoning transcript; `output_text` parts form the visible answer; a
 * `refusal` part forces the `refusal` finish reason; an `incomplete` status
 * forces the `incomplete` finish reason.
 */
class OpenRouterResponsesResponseParser
{
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
    }

    /**
     * Decodes a Responses response body.
     *
     * @param body The raw response (or `{"error": {...}}`) body.
     * @return The decoded response.
     * @throws P2PException When the body is an error envelope, or a failed
     *   response carries an embedded error.
     */
    fun parse(body: String): OpenRouterResponsesResponse
    {
        val root = json.parseToJsonElement(body).jsonObject

        // A bare error envelope has an `error` object but no response identity.
        val bareErrorEnvelope = root["error"]?.jsonObject != null && root["object"] == null && root["id"] == null
        if(bareErrorEnvelope)
        {
            val error = root["error"]!!.jsonObject
            val message = error["message"]?.jsonPrimitive?.contentOrNull ?: "unknown error"
            val code = error["code"]?.jsonPrimitive?.contentOrNull
            val type = error["type"]?.jsonPrimitive?.contentOrNull
            val limitSource = error["metadata"]?.jsonObject?.get("limit_source")?.jsonPrimitive?.contentOrNull

            val p2pError = when
            {
                type == "auth_error" || code == "401" -> P2PError.auth
                code == "402" -> P2PError.configuration
                type == "rate_limit_error" || code == "429" || limitSource == "rate_limit" -> P2PError.transport
                else -> P2PError.transport
            }
            throw P2PException(p2pError, message, Exception(message))
        }

        val response = json.decodeFromString<OpenRouterResponsesResponse>(body)
        if(response.status == "failed" && response.error != null)
        {
            val failedMessage = response.error?.message ?: "response failed"
            throw P2PException(P2PError.transport, failedMessage, Exception(failedMessage))
        }
        return response
    }

    /**
     * Projects a decoded response onto the P2P surface.
     *
     * @param response The decoded Responses response.
     * @return The flattened text / reasoning / function calls / usage.
     */
    fun flatten(response: OpenRouterResponsesResponse): OpenRouterResponsesFlattened
    {
        val textBuilder = StringBuilder()
        val reasoningBuilder = StringBuilder()
        val functionCalls = mutableListOf<OpenRouterResponsesOutputItem.FunctionCall>()
        var hasRefusal = false

        for(item in response.output)
        {
            when(item)
            {
                is OpenRouterResponsesOutputItem.Message ->
                {
                    for(part in item.content)
                    {
                        when(part)
                        {
                            is env.OpenRouterResponsesContentPart.OutputText -> textBuilder.append(part.text)
                            is env.OpenRouterResponsesContentPart.Refusal -> hasRefusal = true
                            is env.OpenRouterResponsesContentPart.ReasoningText -> reasoningBuilder.append(part.text)
                            is env.OpenRouterResponsesContentPart.SummaryText -> reasoningBuilder.append(part.text)
                            else -> {}
                        }
                    }
                }

                is OpenRouterResponsesOutputItem.Reasoning ->
                {
                    for(part in item.content + item.summary)
                    {
                        when(part)
                        {
                            is env.OpenRouterResponsesContentPart.ReasoningText -> reasoningBuilder.append(part.text)
                            is env.OpenRouterResponsesContentPart.SummaryText -> reasoningBuilder.append(part.text)
                            else -> {}
                        }
                    }
                }

                is OpenRouterResponsesOutputItem.FunctionCall -> functionCalls.add(item)

                is OpenRouterResponsesOutputItem.Unknown -> {}
            }
        }

        val finishReason = when
        {
            response.status == "incomplete" -> "incomplete"
            hasRefusal -> "refusal"
            else -> "stop"
        }

        return OpenRouterResponsesFlattened(
            text = textBuilder.toString(),
            finishReason = finishReason,
            reasoning = reasoningBuilder.toString(),
            functionCalls = functionCalls,
            usage = response.usage
        )
    }
}

/**
 * The P2P projection of a decoded OpenRouter Responses response.
 *
 * @property text The concatenated user-visible answer (`output_text` parts).
 * @property finishReason `stop`, `refusal`, or `incomplete`.
 * @property reasoning The concatenated chain-of-thought (`reasoning_text` +
 *   `summary_text` parts across reasoning items).
 * @property functionCalls The `function_call` items, ready for PCP mapping.
 * @property usage Token usage + detail breakdowns, when the provider reported them.
 */
data class OpenRouterResponsesFlattened(
    val text: String,
    val finishReason: String,
    val reasoning: String,
    val functionCalls: List<OpenRouterResponsesOutputItem.FunctionCall>,
    val usage: OpenRouterResponsesUsage?
)
