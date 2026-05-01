package genericOpenAIPipe.api

import genericOpenAIPipe.env.ChatChoice
import genericOpenAIPipe.env.ChatMessage
import genericOpenAIPipe.env.GenericOpenAIChatResponse
import genericOpenAIPipe.env.MessageContent
import genericOpenAIPipe.env.UsageInfo
import kotlinx.serialization.json.Json

/**
 * Response parser for Anthropic Messages API responses.
 *
 * Transforms Anthropic's response format to OpenAI-compatible format:
 * - Extracts `content[0].text` → `choices[0].message.content`
 * - Maps `stop_reason` → `finish_reason`
 * - Maps `input_tokens` → `prompt_tokens`, `output_tokens` → `completion_tokens`
 *
 * Anthropic content blocks are processed as follows:
 * - [ResponseContentBlock.TextContentBlock] → extracted as text content
 * - [ResponseContentBlock.ThinkingBlock] → currently ignored in V1 (extraction not supported)
 *
 * For responses with multiple content blocks, only the first text block is used.
 *
 * @property json The Json instance for deserializing Anthropic responses
 */
class AnthropicResponseParser(private val json: Json) : ResponseParser
{
    /**
     * Parses an Anthropic /v1/messages API response into a GenericOpenAIChatResponse.
     *
     * @param response The raw JSON response body from Anthropic API
     * @param apiMode The target API mode (unused for Anthropic - always uses Messages API)
     * @return A GenericOpenAIChatResponse normalized to OpenAI format
     * @throws Exception If parsing fails or response indicates an error
     */
    override fun parse(response: String, apiMode: ApiMode): GenericOpenAIChatResponse
    {
        val anthropicResponse = json.decodeFromString<AnthropicMessagesResponse>(response)
        return anthropicResponse.toGenericOpenAIResponse()
    }

    /**
     * Transforms an AnthropicMessagesResponse to GenericOpenAIChatResponse.
     *
     * Transformation rules:
     * 1. `id` maps directly to `id`
     * 2. `type` = "message" maps to `objectType` = "chat.completion"
     * 3. `created` timestamp generated from current time (Anthropic doesn't provide one)
     * 4. `model` maps directly to `model`
     * 5. `content[0].text` (first TextContentBlock) → `choices[0].message.content`
     * 6. `stop_reason` → `choices[0].finish_reason`
     * 7. `input_tokens` → `usage.prompt_tokens`
     * 8. `output_tokens` → `usage.completion_tokens`
     * 9. `session_id` carried forward if present
     *
     * @return GenericOpenAIChatResponse in OpenAI-compatible format
     */
    private fun AnthropicMessagesResponse.toGenericOpenAIResponse(): GenericOpenAIChatResponse
    {
        val extractedContent = extractTextContent(content)
        val finishReason = stopReason

        val usageInfo = UsageInfo(
            promptTokens = usage.inputTokens,
            completionTokens = usage.outputTokens,
            totalTokens = usage.inputTokens + usage.outputTokens,
            promptTokensDetails = null,
            completionTokensDetails = null
        )

        return GenericOpenAIChatResponse(
            id = id,
            objectType = "chat.completion",
            created = System.currentTimeMillis() / 1000,
            model = model,
            choices = listOf(
                ChatChoice(
                    index = 0,
                    message = ChatMessage(
                        role = role,
                        content = MessageContent.TextContent(extractedContent)
                    ),
                    finishReason = finishReason,
                    logprobs = null
                )
            ),
            usage = usageInfo,
            systemFingerprint = null
        )
    }

    /**
     * Extracts text content from Anthropic content blocks.
     *
     * Iterates through content blocks and returns the text from the first
     * TextContentBlock found. Thinking blocks and other block types are ignored.
     *
     * @param contentBlocks The list of content blocks from Anthropic response
     * @return The text content from the first text block, or empty string if none found
     */
    private fun extractTextContent(contentBlocks: List<ResponseContentBlock>): String
    {
        for (block in contentBlocks)
        {
            when (block)
            {
                is ResponseContentBlock.TextContentBlock -> return block.text
                is ResponseContentBlock.ThinkingBlock -> { /* Ignore for V1 */ }
            }
        }
        return ""
    }
}