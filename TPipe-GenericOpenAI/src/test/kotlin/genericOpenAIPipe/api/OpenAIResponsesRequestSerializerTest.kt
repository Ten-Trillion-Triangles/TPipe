package genericOpenAIPipe.api

import com.TTT.Util.deserialize
import com.TTT.Util.serialize
import genericOpenAIPipe.env.ChatMessage
import genericOpenAIPipe.env.ContentBlock
import genericOpenAIPipe.env.GenericOpenAIChatRequest
import genericOpenAIPipe.env.GenericOpenAIEnv
import genericOpenAIPipe.env.MessageContent
import genericOpenAIPipe.env.OpenAIResponsesInputPart
import genericOpenAIPipe.env.OpenAIResponsesRequest
import genericOpenAIPipe.env.PromptCacheOptions
import genericOpenAIPipe.env.ReasoningConfig
import genericOpenAIPipe.env.ResponseFormat
import genericOpenAIPipe.env.ToolDefinition
import genericOpenAIPipe.env.FunctionSchema
import genericOpenAIPipe.mantle.MantleGpt56CacheBoundary
import genericOpenAIPipe.mantle.MantleGpt56PromptCacheMetadata
import genericOpenAIPipe.mantle.MantleMetadataKeys
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions

/**
 * Unit tests for [OpenAIResponsesRequestSerializer].
 *
 * Verifies the translation from a normalised [GenericOpenAIChatRequest] (the in-process
 * shape every other mode already speaks) to the OpenAI Responses wire spec, in particular:
 *  - system message hoisting into the top-level `instructions` field,
 *  - text vs multimodal input parts (`input_text` / `input_image`),
 *  - `response_format` translation to the `text.format` wrapper,
 *  - tools and reasoning pass-through,
 *  - error when used with a non-Responses apiMode.
 */
class OpenAIResponsesRequestSerializerTest
{

    private val serializer = OpenAIResponsesRequestSerializer()

//=========================================System Message Hoisting=========================================

    @Test
    fun testSystemMessageHoistedToInstructions()
    {
        val request = GenericOpenAIChatRequest(
            model = "MiniMax-M2.7",
            messages = listOf(
                ChatMessage(role = "system", content = MessageContent.TextContent("You are a helpful assistant.")),
                ChatMessage(role = "user", content = MessageContent.TextContent("Hi"))
            )
        )

        val json = serializer.serialize(request, ApiMode.OpenAIResponses)
        val parsed = deserialize<OpenAIResponsesRequest>(json)

        Assertions.assertEquals("You are a helpful assistant.", parsed!!.instructions)
        Assertions.assertEquals(1, parsed!!.input.size)
        Assertions.assertEquals("user", parsed!!.input[0].role)
    }

    @Test
    fun testMultipleSystemMessagesConcatenateToInstructions()
    {
        val request = GenericOpenAIChatRequest(
            model = "MiniMax-M2.7",
            messages = listOf(
                ChatMessage(role = "system", content = MessageContent.TextContent("First.")),
                ChatMessage(role = "system", content = MessageContent.TextContent("Second.")),
                ChatMessage(role = "user", content = MessageContent.TextContent("Hi"))
            )
        )

        val json = serializer.serialize(request, ApiMode.OpenAIResponses)
        val parsed = deserialize<OpenAIResponsesRequest>(json)

        Assertions.assertNotNull(parsed!!.instructions)
        Assertions.assertTrue(parsed!!.instructions!!.contains("First."))
        Assertions.assertTrue(parsed!!.instructions!!.contains("Second."))
        Assertions.assertEquals(1, parsed!!.input.size)
    }

    @Test
    fun testNoSystemMessageLeavesInstructionsNull()
    {
        val request = GenericOpenAIChatRequest(
            model = "MiniMax-M2.7",
            messages = listOf(
                ChatMessage(role = "user", content = MessageContent.TextContent("Hi"))
            )
        )

        val json = serializer.serialize(request, ApiMode.OpenAIResponses)
        val parsed = deserialize<OpenAIResponsesRequest>(json)

        Assertions.assertEquals(null, parsed!!.instructions)
        Assertions.assertEquals(1, parsed!!.input.size)
    }

    @Test
    fun testBlankSystemMessageLeavesInstructionsNull()
    {
        val request = GenericOpenAIChatRequest(
            model = "MiniMax-M2.7",
            messages = listOf(
                ChatMessage(role = "system", content = MessageContent.TextContent("   ")),
                ChatMessage(role = "user", content = MessageContent.TextContent("Hi"))
            )
        )

        val json = serializer.serialize(request, ApiMode.OpenAIResponses)
        val parsed = deserialize<OpenAIResponsesRequest>(json)

        Assertions.assertEquals(null, parsed!!.instructions)
    }

//=========================================Input Item Mapping=========================================

    @Test
    fun testUserTextMessageBecomesMessageItem()
    {
        val request = GenericOpenAIChatRequest(
            model = "MiniMax-M2.7",
            messages = listOf(
                ChatMessage(role = "user", content = MessageContent.TextContent("Hello"))
            )
        )

        val json = serializer.serialize(request, ApiMode.OpenAIResponses)
        val parsed = deserialize<OpenAIResponsesRequest>(json)

        Assertions.assertEquals(1, parsed!!.input.size)
        val first = parsed!!.input[0]
        Assertions.assertEquals("user", first.role)
        // The first part should be the input_text
        val firstPart = first.content.firstOrNull()
        Assertions.assertNotNull(firstPart)
        Assertions.assertTrue(firstPart is OpenAIResponsesInputPart.InputTextPart)
        Assertions.assertEquals("Hello", (firstPart as genericOpenAIPipe.env.OpenAIResponsesInputPart.InputTextPart).text)
    }

    @Test
    fun testAssistantTextMessageBecomesMessageItem()
    {
        val request = GenericOpenAIChatRequest(
            model = "MiniMax-M2.7",
            messages = listOf(
                ChatMessage(role = "user", content = MessageContent.TextContent("Hi")),
                ChatMessage(role = "assistant", content = MessageContent.TextContent("Hello!"))
            )
        )

        val json = serializer.serialize(request, ApiMode.OpenAIResponses)
        val parsed = deserialize<OpenAIResponsesRequest>(json)

        Assertions.assertEquals(2, parsed!!.input.size)
        Assertions.assertEquals("assistant", parsed!!.input[1].role)
    }

    @Test
    fun testMultimodalUserMessageBecomesMixedParts()
    {
        val blocks = listOf(
            ContentBlock.TextBlock("What is this?"),
            ContentBlock.ImageUrlBlock(url = "data:image/png;base64,AAA", detail = "high")
        )
        val request = GenericOpenAIChatRequest(
            model = "MiniMax-M2.7",
            messages = listOf(
                ChatMessage(role = "user", content = MessageContent.MultimodalContent(blocks))
            )
        )

        val json = serializer.serialize(request, ApiMode.OpenAIResponses)
        val parsed = deserialize<OpenAIResponsesRequest>(json)

        val parts = parsed!!.input[0].content
        Assertions.assertEquals(2, parts.size)
        Assertions.assertTrue(parts[0] is OpenAIResponsesInputPart.InputTextPart)
        Assertions.assertTrue(parts[1] is OpenAIResponsesInputPart.InputImagePart)
        val image = parts[1] as OpenAIResponsesInputPart.InputImagePart
        Assertions.assertEquals("data:image/png;base64,AAA", image.imageUrl)
        Assertions.assertEquals("high", image.detail)
    }

    @Test
    fun testPlainContentBecomesInputText()
    {
        val request = GenericOpenAIChatRequest(
            model = "MiniMax-M2.7",
            messages = listOf(
                ChatMessage(role = "user", content = MessageContent.PlainContent("raw"))
            )
        )

        val json = serializer.serialize(request, ApiMode.OpenAIResponses)
        val parsed = deserialize<OpenAIResponsesRequest>(json)
        val firstPart = parsed!!.input[0].content.firstOrNull()
        Assertions.assertTrue(firstPart is OpenAIResponsesInputPart.InputTextPart)
        Assertions.assertEquals("raw", (firstPart as genericOpenAIPipe.env.OpenAIResponsesInputPart.InputTextPart).text)
    }

//=========================================Response Format Translation=========================================

    @Test
    fun testResponseFormatText()
    {
        val request = GenericOpenAIChatRequest(
            model = "MiniMax-M2.7",
            messages = listOf(ChatMessage(role = "user", content = MessageContent.TextContent("hi"))),
            responseFormat = ResponseFormat(type = "text")
        )

        val json = serializer.serialize(request, ApiMode.OpenAIResponses)
        val parsed = deserialize<OpenAIResponsesRequest>(json)
        Assertions.assertNotNull(parsed!!.text)
        Assertions.assertEquals("text", parsed!!.text!!.format.type)
    }

    @Test
    fun testResponseFormatJsonObject()
    {
        val request = GenericOpenAIChatRequest(
            model = "MiniMax-M2.7",
            messages = listOf(ChatMessage(role = "user", content = MessageContent.TextContent("hi"))),
            responseFormat = ResponseFormat(type = "json_object")
        )

        val json = serializer.serialize(request, ApiMode.OpenAIResponses)
        val parsed = deserialize<OpenAIResponsesRequest>(json)
        Assertions.assertEquals("json_object", parsed!!.text?.format?.type)
    }

    @Test
    fun testResponseFormatJsonSchema()
    {
        val schema = JsonObject(mapOf(
            "type" to JsonPrimitive("object"),
            "properties" to JsonObject(mapOf("answer" to JsonObject(mapOf("type" to JsonPrimitive("string")))))
        ))
        val request = GenericOpenAIChatRequest(
            model = "MiniMax-M2.7",
            messages = listOf(ChatMessage(role = "user", content = MessageContent.TextContent("hi"))),
            responseFormat = ResponseFormat(type = "json_schema", jsonSchema = schema)
        )

        val json = serializer.serialize(request, ApiMode.OpenAIResponses)
        val parsed = deserialize<OpenAIResponsesRequest>(json)
        Assertions.assertEquals("json_schema", parsed!!.text?.format?.type)
        Assertions.assertNotNull(parsed!!.text?.format?.schema)
    }

//=========================================Tools and Reasoning=========================================

    @Test
    fun testToolsPassThrough()
    {
        val tools = listOf(
            ToolDefinition(
                type = "function",
                function = FunctionSchema(
                    name = "get_weather",
                    description = "Get the weather",
                    parameters = JsonObject(mapOf("type" to JsonPrimitive("object")))
                )
            )
        )
        val request = GenericOpenAIChatRequest(
            model = "MiniMax-M2.7",
            messages = listOf(ChatMessage(role = "user", content = MessageContent.TextContent("hi"))),
            tools = tools,
            toolChoice = "auto"
        )

        val json = serializer.serialize(request, ApiMode.OpenAIResponses)
        val parsed = deserialize<OpenAIResponsesRequest>(json)
        Assertions.assertEquals(1, parsed!!.tools?.size)
        Assertions.assertEquals("get_weather", parsed!!.tools?.first()?.function?.name)
        Assertions.assertEquals("auto", parsed!!.toolChoice)
    }

    @Test
    fun testReasoningPassThrough()
    {
        val request = GenericOpenAIChatRequest(
            model = "MiniMax-M2.7",
            messages = listOf(ChatMessage(role = "user", content = MessageContent.TextContent("hi"))),
            reasoning = ReasoningConfig(effort = "medium", maxTokens = 1024)
        )

        val json = serializer.serialize(request, ApiMode.OpenAIResponses)
        val parsed = deserialize<OpenAIResponsesRequest>(json)
        Assertions.assertEquals("medium", parsed!!.reasoning?.effort)
        Assertions.assertEquals(1024, parsed!!.reasoning?.maxTokens)
    }

//=========================================Other Parameters=========================================

    @Test
    fun testMaxTokensMapsToMaxOutputTokens()
    {
        val request = GenericOpenAIChatRequest(
            model = "MiniMax-M2.7",
            messages = listOf(ChatMessage(role = "user", content = MessageContent.TextContent("hi"))),
            maxTokens = 256
        )

        val json = serializer.serialize(request, ApiMode.OpenAIResponses)
        val parsed = deserialize<OpenAIResponsesRequest>(json)
        Assertions.assertEquals(256, parsed!!.maxOutputTokens)
    }

    @Test
    fun testTemperatureAndTopPPassThrough()
    {
        val request = GenericOpenAIChatRequest(
            model = "MiniMax-M2.7",
            messages = listOf(ChatMessage(role = "user", content = MessageContent.TextContent("hi"))),
            temperature = 0.3,
            topP = 0.9
        )

        val json = serializer.serialize(request, ApiMode.OpenAIResponses)
        val parsed = deserialize<OpenAIResponsesRequest>(json)
        Assertions.assertEquals(0.3, parsed!!.temperature)
        Assertions.assertEquals(0.9, parsed!!.topP)
    }

    @Test
    fun testCodexPolicySuppressesGenericControlsAndAddsResponsesControls()
    {
        val request = GenericOpenAIChatRequest(
            model = "gpt-5-codex",
            messages = listOf(
                ChatMessage(role = "system", content = MessageContent.TextContent("rules")),
                ChatMessage(role = "user", content = MessageContent.TextContent("hi")),
            ),
            temperature = 0.2,
            topP = 0.8,
            maxTokens = 512,
            user = "do-not-send",
            stream = false,
        )
        val policy = OpenAIResponsesWirePolicy(
            emitMaxOutputTokens = false,
            emitSamplingParameters = false,
            emitUser = false,
            store = false,
            include = listOf("reasoning.encrypted_content"),
            messageItemType = "message",
            forceStreaming = true,
        )

        val parsed = deserialize<OpenAIResponsesRequest>(
            serializer.serialize(
                request,
                ApiMode.OpenAIResponses,
                RequestSerializationOptions(responsesPolicy = policy),
            )
        )

        Assertions.assertNotNull(parsed)
        Assertions.assertEquals(true, parsed!!.stream)
        Assertions.assertEquals(false, parsed.store)
        Assertions.assertEquals(listOf("reasoning.encrypted_content"), parsed.include)
        Assertions.assertEquals(null, parsed.maxOutputTokens)
        Assertions.assertEquals(null, parsed.temperature)
        Assertions.assertEquals(null, parsed.topP)
        Assertions.assertEquals(null, parsed.user)
        Assertions.assertEquals("message", parsed.input[0].type)
    }

    @Test
    fun testCodexPolicyDoesNotInventNativeTools()
    {
        val request = GenericOpenAIChatRequest(
            model = "gpt-5-codex",
            messages = listOf(ChatMessage(role = "user", content = MessageContent.TextContent("hi"))),
        )
        val policy = OpenAIResponsesWirePolicy(messageItemType = "message")

        val parsed = deserialize<OpenAIResponsesRequest>(
            serializer.serialize(
                request,
                ApiMode.OpenAIResponses,
                RequestSerializationOptions(responsesPolicy = policy),
            )
        )

        Assertions.assertNull(parsed!!.tools)
    }

//=========================================Error Cases=========================================

    @Test
    fun testThrowsWhenApiModeIsNotOpenAIResponses()
    {
        val request = GenericOpenAIChatRequest(
            model = "MiniMax-M2.7",
            messages = listOf(ChatMessage(role = "user", content = MessageContent.TextContent("hi")))
        )

        Assertions.assertThrows(IllegalArgumentException::class.java) { serializer.serialize(request, ApiMode.OpenAI) }
        Assertions.assertThrows(IllegalArgumentException::class.java) { serializer.serialize(request, ApiMode.Anthropic) }
    }

//=========================================Mantle GPT-5.6 Prompt Caching=========================================

    @Test
    fun testPromptCacheOptionsAbsentWhenMetadataAbsent()
    {
        val request = GenericOpenAIChatRequest(
            model = "openai.gpt-5.6-luna",
            messages = listOf(
                ChatMessage(role = "system", content = MessageContent.TextContent("Stable rules.")),
                ChatMessage(role = "user", content = MessageContent.TextContent("hi")),
            )
        )

        val json = serializer.serialize(
            request, ApiMode.OpenAIResponses,
            RequestSerializationOptions(),
        )
        val parsedJson = deserialize<JsonObject>(json)

        Assertions.assertFalse(
            parsedJson!!.containsKey("prompt_cache_options"),
            "Expected no prompt_cache_options field when Mantle metadata is absent; got: $json",
        )
    }

    @Test
    fun testPromptCacheOptionsEmittedWhenMetadataPresent()
    {
        val request = GenericOpenAIChatRequest(
            model = "openai.gpt-5.6-luna",
            messages = listOf(
                ChatMessage(role = "system", content = MessageContent.TextContent("Stable rules.")),
                ChatMessage(role = "user", content = MessageContent.TextContent("hi")),
            )
        )
        val options = RequestSerializationOptions(
            metadata = mapOf(
                MantleMetadataKeys.GPT56_PROMPT_CACHING to MantleGpt56PromptCacheMetadata(
                    mode = "explicit", ttl = "30m", boundary = MantleGpt56CacheBoundary.NONE,
                )
            )
        )

        val json = serializer.serialize(request, ApiMode.OpenAIResponses, options)
        val parsedJson = deserialize<JsonObject>(json)

        Assertions.assertNotNull(parsedJson)
        Assertions.assertTrue(
            parsedJson!!.containsKey("prompt_cache_options"),
            "Expected prompt_cache_options at top level; got: $json",
        )
        val cacheOptions = parsedJson["prompt_cache_options"] as JsonObject
        Assertions.assertEquals("explicit", (cacheOptions["mode"] as JsonPrimitive).content)
        Assertions.assertEquals("30m", (cacheOptions["ttl"] as JsonPrimitive).content)
    }

    @Test
    fun testPromptCacheBreakpointOnInputTextWhenBoundaryIsAfterInstructions()
    {
        val request = GenericOpenAIChatRequest(
            model = "openai.gpt-5.6-luna",
            messages = listOf(
                ChatMessage(role = "system", content = MessageContent.TextContent("Stable rules.")),
                ChatMessage(role = "user", content = MessageContent.TextContent("hi")),
            )
        )
        val options = RequestSerializationOptions(
            metadata = mapOf(
                MantleMetadataKeys.GPT56_PROMPT_CACHING to MantleGpt56PromptCacheMetadata(
                    mode = "explicit", ttl = "30m", boundary = MantleGpt56CacheBoundary.AFTER_INSTRUCTIONS,
                )
            )
        )

        val json = serializer.serialize(request, ApiMode.OpenAIResponses, options)
        val parsed = deserialize<OpenAIResponsesRequest>(json)

        Assertions.assertNotNull(parsed)
        Assertions.assertNull(
            parsed!!.instructions,
            "Expected instructions to be null when boundary=AFTER_INSTRUCTIONS routes system to a developer input block",
        )
        Assertions.assertTrue(
            parsed.input.isNotEmpty(),
            "Expected at least one input message after boundary transformation",
        )
        val first = parsed.input.first()
        Assertions.assertEquals("developer", first.role, "Expected first input item to be a developer-role message")
        Assertions.assertEquals(1, first.content.size)
        val part = first.content.first() as OpenAIResponsesInputPart.InputTextPart
        Assertions.assertEquals("Stable rules.", part.text)
        Assertions.assertNotNull(
            part.promptCacheBreakpoint,
            "Expected promptCacheBreakpoint to be set on the developer-role input_text part",
        )
        Assertions.assertEquals("explicit", part.promptCacheBreakpoint!!.mode)
    }

    @Test
    fun testPromptCacheBreakpointThrowsWhenModelIsNotGpt56()
    {
        val request = GenericOpenAIChatRequest(
            model = "google.gemma-4-e2b",
            messages = listOf(
                ChatMessage(role = "system", content = MessageContent.TextContent("Stable rules.")),
                ChatMessage(role = "user", content = MessageContent.TextContent("hi")),
            )
        )
        val options = RequestSerializationOptions(
            metadata = mapOf(
                MantleMetadataKeys.GPT56_PROMPT_CACHING to MantleGpt56PromptCacheMetadata(
                    mode = "explicit", ttl = "30m", boundary = MantleGpt56CacheBoundary.NONE,
                )
            )
        )

        Assertions.assertThrows(IllegalStateException::class.java) {
            serializer.serialize(request, ApiMode.OpenAIResponses, options)
        }
    }
}
