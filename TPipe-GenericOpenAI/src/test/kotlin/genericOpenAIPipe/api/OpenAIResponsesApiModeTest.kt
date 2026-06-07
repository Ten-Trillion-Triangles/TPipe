package genericOpenAIPipe.api

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions

/**
 * Unit tests for the new [ApiMode.OpenAIResponses] variant and the
 * [RequestSerializer] / [ResponseParser] factory dispatch covering the third mode.
 *
 * The [ApiMode] sealed class is extended (not modified) to add the new variant;
 * these tests pin the new variant down and make sure both factories recognise it.
 */
class OpenAIResponsesApiModeTest
{

//=========================================ApiMode.OpenAIResponses Variant Tests=========================================

    @Test
    fun testApiModeOpenAIResponsesInstance()
    {
        val apiMode = ApiMode.OpenAIResponses
        Assertions.assertNotNull(apiMode)
        Assertions.assertTrue(apiMode is ApiMode.OpenAIResponses)
    }

    @Test
    fun testApiModeSealedClassExhaustivenessThreeVariants()
    {
        val modes = listOf(ApiMode.OpenAI, ApiMode.Anthropic, ApiMode.OpenAIResponses)
        Assertions.assertEquals(3, modes.size)
    }

    @Test
    fun testApiModeDefaultIsStillOpenAI()
    {
        Assertions.assertEquals(ApiMode.OpenAI, ApiMode.DEFAULT)
    }

    @Test
    fun testApiModeOpenAIResponsesNotEqualsOpenAI()
    {
        Assertions.assertTrue(ApiMode.OpenAIResponses != ApiMode.OpenAI)
        Assertions.assertTrue(ApiMode.OpenAIResponses != ApiMode.Anthropic)
    }

//=========================================RequestSerializer Factory Dispatch Tests=====================================

    @Test
    fun testRequestSerializerFactoryDispatchesOpenAIResponses()
    {
        val factory = RequestSerializer.Factory.create()
        val request = genericOpenAIPipe.env.GenericOpenAIChatRequest(
            model = "MiniMax-M2.7",
            messages = listOf(
                genericOpenAIPipe.env.ChatMessage(
                    role = "user",
                    content = genericOpenAIPipe.env.MessageContent.TextContent("hi")
                )
            ),
            maxTokens = 16
        )

        val openAiJson = factory.serialize(request, ApiMode.OpenAI)
        val responsesJson = factory.serialize(request, ApiMode.OpenAIResponses)
        val anthropicJson = factory.serialize(request, ApiMode.Anthropic)

        // Each mode produces a JSON string; OpenAIResponses must differ from chat-completions
        Assertions.assertTrue(openAiJson.contains("\"messages\""))
        Assertions.assertTrue(responsesJson.contains("\"input\""))
        Assertions.assertTrue(!responsesJson.contains("\"messages\""), "Responses JSON must use 'input', not 'messages'")
        Assertions.assertTrue(anthropicJson.contains("\"messages\""))
    }

//=========================================ResponseParser Factory Dispatch Tests=========================================

    @Test
    fun testResponseParserFactoryDispatchesOpenAIResponses()
    {
        val factory = ResponseParser.Factory.create()
        val responsesBody = """
            {
              "id": "resp_test_1",
              "object": "response",
              "created_at": 1700000000,
              "status": "completed",
              "model": "MiniMax-M2.7",
              "output": [
                {
                  "type": "message",
                  "id": "msg_1",
                  "role": "assistant",
                  "status": "completed",
                  "content": [
                    { "type": "output_text", "text": "pong", "annotations": [] }
                  ]
                }
              ],
              "usage": { "input_tokens": 1, "output_tokens": 1, "total_tokens": 2 }
            }
        """.trimIndent()

        val response = factory.parse(responsesBody, ApiMode.OpenAIResponses)
        Assertions.assertEquals("resp_test_1", response.id)
        val firstChoice = response.choices.firstOrNull()
        Assertions.assertNotNull(firstChoice)
        val content = firstChoice!!.message.content
        Assertions.assertTrue(content is genericOpenAIPipe.env.MessageContent.TextContent)
        Assertions.assertEquals("pong", (content as genericOpenAIPipe.env.MessageContent.TextContent).text)
    }
}
