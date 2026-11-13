package openrouterPipe

import com.TTT.Util.serialize
import env.ChatMessage
import env.OpenRouterChatRequest
import env.OpenRouterResponsesInputPart
import env.OpenRouterResponsesMessageItem
import env.OpenRouterResponsesReasoning
import env.OpenRouterResponsesRequest
import env.OpenRouterResponsesTextConfig
import env.OpenRouterResponsesTextFormat
import env.OpenRouterResponsesTool

/**
 * Pure `OpenRouterChatRequest` -> OpenRouter Responses wire-spec conversion.
 *
 * This is the in-process chat-completions normalisation mapped onto the
 * Responses API shape so the pipe can speak both endpoints from one request
 * model. The mapping rules are pinned by
 * [OpenRouterResponsesRequestSerializerTest]:
 *  - system messages are hoisted into `instructions` (blank ones dropped,
 *    joined by a blank line)
 *  - every non-system message becomes an `input` item with a single
 *    [OpenRouterResponsesInputPart.InputTextPart]
 *  - `max_tokens` -> `max_output_tokens`
 *  - `response_format` -> `text.format`
 *  - nested chat-completions `function` tools -> flat Responses tools
 *  - `reasoning` / flat `reasoning_effort` -> the Responses `reasoning` block
 *
 * No HTTP is performed here; [serialize] only produces the request body.
 */
class OpenRouterResponsesRequestSerializer
{
    /**
     * Converts an in-process [OpenRouterChatRequest] into the Responses wire
     * request.
     *
     * @param request The chat-completions request.
     * @param stream Whether the request will stream (`stream` flag on the wire).
     * @return The Responses-shaped request.
     */
    fun convert(request: OpenRouterChatRequest, stream: Boolean): OpenRouterResponsesRequest
    {
        val system = request.messages.filter{ it.role == "system" && it.content.isNotBlank() }
        val instructions = if(system.isEmpty()) null
            else system.joinToString("\n\n") { it.content }

        val input = request.messages.filter{ it.role != "system" }.map { message ->
            OpenRouterResponsesMessageItem(role = message.role, content = listOf(
                OpenRouterResponsesInputPart.InputTextPart(message.content)
            ))
        }

        val tools = request.tools?.map { toolDefinition ->
            OpenRouterResponsesTool(
                type = toolDefinition.type,
                name = toolDefinition.function.name,
                description = toolDefinition.function.description,
                parameters = toolDefinition.function.parameters
            )
        }

        val reasoning = when
        {
            request.reasoning != null -> OpenRouterResponsesReasoning(
                effort = request.reasoning!!.effort,
                summary = request.reasoning!!.summary
            )

            request.reasoningEffort != null -> OpenRouterResponsesReasoning(effort = request.reasoningEffort)

            else -> null
        }

        return OpenRouterResponsesRequest(
            model = request.model,
            input = input,
            instructions = instructions,
            maxOutputTokens = request.maxTokens,
            temperature = request.temperature,
            topP = request.topP,
            topK = request.topK,
            stream = stream,
            tools = tools,
            toolChoice = request.toolChoice,
            parallelToolCalls = request.parallelToolCalls,
            text = request.responseFormat?.let { OpenRouterResponsesTextConfig(
                format = OpenRouterResponsesTextFormat(type = it.type, schema = it.jsonSchema)
            ) },
            reasoning = reasoning
        )
    }

    /**
     * Produces the JSON body to POST to the Responses endpoint.
     *
     * @param request The chat-completions request.
     * @param stream Whether the request will stream.
     * @return The Responses-shaped request body as JSON.
     */
    fun serialize(request: OpenRouterChatRequest, stream: Boolean): String
    {
        return serialize(convert(request, stream), encodedefault = false)
    }
}
