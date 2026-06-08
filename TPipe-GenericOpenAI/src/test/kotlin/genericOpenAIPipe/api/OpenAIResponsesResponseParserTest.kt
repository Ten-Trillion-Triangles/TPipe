package genericOpenAIPipe.api

import com.TTT.P2P.P2PError
import com.TTT.P2P.P2PException
import genericOpenAIPipe.env.MessageContent
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions

/**
 * Unit tests for [OpenAIResponsesResponseParser].
 *
 * The parser takes a raw OpenAI Responses JSON body and projects it into the
 * [genericOpenAIPipe.env.GenericOpenAIChatResponse] shape the rest of the pipe
 * already consumes. These tests verify the projection, refusal handling, reasoning
 * token accounting, finish_reason mapping, and error-response -> P2PException
 * translation.
 */
class OpenAIResponsesResponseParserTest
{

    private val parser = OpenAIResponsesResponseParser()

//=========================================Happy-Path Projection=========================================

    @Test
    fun testParsesSingleTextMessage()
    {
        val body = """
            {
              "id": "resp_1",
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

        val response = parser.parse(body, ApiMode.OpenAIResponses)
        Assertions.assertEquals("resp_1", response.id)
        Assertions.assertEquals("response", response.objectType)
        Assertions.assertEquals(1700000000L, response.created)
        Assertions.assertEquals("MiniMax-M2.7", response.model)
        val firstChoice = response.choices.firstOrNull()
        Assertions.assertNotNull(firstChoice)
        val content = firstChoice!!.message.content
        Assertions.assertTrue(content is MessageContent.TextContent)
        Assertions.assertEquals("pong", (content as MessageContent.TextContent).text)
        Assertions.assertEquals("stop", firstChoice!!.finishReason)
    }

    @Test
    fun testConcatenatesMultipleOutputTextParts()
    {
        val body = """
            {
              "id": "resp_1",
              "object": "response",
              "created_at": 0,
              "status": "completed",
              "model": "MiniMax-M2.7",
              "output": [
                {
                  "type": "message",
                  "id": "msg_1",
                  "role": "assistant",
                  "status": "completed",
                  "content": [
                    { "type": "output_text", "text": "Hel", "annotations": [] },
                    { "type": "output_text", "text": "lo", "annotations": [] }
                  ]
                }
              ],
              "usage": { "input_tokens": 0, "output_tokens": 0, "total_tokens": 0 }
            }
        """.trimIndent()

        val response = parser.parse(body, ApiMode.OpenAIResponses)
        val content = response.choices[0].message.content
        Assertions.assertTrue(content is MessageContent.TextContent)
        Assertions.assertEquals("Hello", (content as MessageContent.TextContent).text)
    }

    @Test
    fun testSkipsReasoningItemsButKeepsReasoningTokens()
    {
        val body = """
            {
              "id": "resp_1",
              "object": "response",
              "created_at": 0,
              "status": "completed",
              "model": "MiniMax-M2.7",
              "output": [
                { "type": "reasoning", "id": "rs_1", "summary": [] },
                {
                  "type": "message",
                  "id": "msg_1",
                  "role": "assistant",
                  "status": "completed",
                  "content": [
                    { "type": "output_text", "text": "after-thinking", "annotations": [] }
                  ]
                }
              ],
              "usage": {
                "input_tokens": 5,
                "output_tokens": 10,
                "total_tokens": 15,
                "output_tokens_details": { "reasoning_tokens": 7 }
              }
            }
        """.trimIndent()

        val response = parser.parse(body, ApiMode.OpenAIResponses)
        val content = response.choices[0].message.content
        Assertions.assertTrue(content is MessageContent.TextContent)
        Assertions.assertEquals("after-thinking", (content as MessageContent.TextContent).text)
        Assertions.assertNotNull(response.usage)
        Assertions.assertEquals(5, response.usage!!.promptTokens)
        Assertions.assertEquals(10, response.usage!!.completionTokens)
        Assertions.assertEquals(7, response.usage!!.completionTokensDetails?.reasoningTokens)
    }



    @Test
    fun testExtractsReasoningTextContentFromReasoningItems()
    {
        val body = """
            {
              "id": "resp_r1",
              "object": "response",
              "created_at": 0,
              "status": "completed",
              "model": "MiniMax-M2.7",
              "output": [
                {
                  "type": "reasoning",
                  "id": "rs_1",
                  "summary": [],
                  "content": [
                    { "type": "reasoning_text", "text": "Let me think about this carefully. " },
                    { "type": "reasoning_text", "text": "The answer is 4." }
                  ]
                },
                {
                  "type": "message",
                  "id": "msg_1",
                  "role": "assistant",
                  "status": "completed",
                  "content": [
                    { "type": "output_text", "text": "The answer is 4.", "annotations": [] }
                  ]
                }
              ],
              "usage": {
                "input_tokens": 5,
                "output_tokens": 10,
                "total_tokens": 15,
                "output_tokens_details": { "reasoning_tokens": 7 }
              }
            }
        """.trimIndent()

        val response = parser.parse(body, ApiMode.OpenAIResponses)

        // The user-visible answer must still come through unchanged
        val content = response.choices[0].message.content
        Assertions.assertTrue(content is MessageContent.TextContent)
        Assertions.assertEquals("The answer is 4.", (content as MessageContent.TextContent).text)

        // Reasoning content is extracted, concatenated across reasoning_text parts, in order
        Assertions.assertNotNull(response.reasoningContent)
        val reasoning = response.reasoningContent!!
        Assertions.assertTrue(reasoning.contains("Let me think about this carefully."))
        Assertions.assertTrue(reasoning.contains("The answer is 4."))
        // The two parts are joined with a newline
        Assertions.assertEquals(
            "Let me think about this carefully. \nThe answer is 4.",
            reasoning
        )
    }

    @Test
    fun testReasoningContentIsNullWhenNoReasoningItemsPresent()
    {
        val body = """
            {
              "id": "resp_nr",
              "object": "response",
              "created_at": 0,
              "status": "completed",
              "model": "gpt-4o",
              "output": [
                {
                  "type": "message",
                  "id": "msg_1",
                  "role": "assistant",
                  "status": "completed",
                  "content": [
                    { "type": "output_text", "text": "no reasoning here", "annotations": [] }
                  ]
                }
              ],
              "usage": { "input_tokens": 1, "output_tokens": 2, "total_tokens": 3 }
            }
        """.trimIndent()

        val response = parser.parse(body, ApiMode.OpenAIResponses)
        Assertions.assertNull(response.reasoningContent)
    }

//=========================================Refusal Handling=========================================

    @Test
    fun testRefusalSetsFinishReasonRefusal()
    {
        val body = """
            {
              "id": "resp_1",
              "object": "response",
              "created_at": 0,
              "status": "completed",
              "model": "MiniMax-M2.7",
              "output": [
                {
                  "type": "message",
                  "id": "msg_1",
                  "role": "assistant",
                  "status": "completed",
                  "content": [
                    { "type": "refusal", "refusal": "I cannot help with that." }
                  ]
                }
              ],
              "usage": { "input_tokens": 1, "output_tokens": 1, "total_tokens": 2 }
            }
        """.trimIndent()

        val response = parser.parse(body, ApiMode.OpenAIResponses)
        val content = response.choices[0].message.content
        Assertions.assertTrue(content is MessageContent.TextContent)
        Assertions.assertEquals("", (content as MessageContent.TextContent).text)
        Assertions.assertEquals("refusal", response.choices[0].finishReason)
    }

//=========================================Status Mapping=========================================

    @Test
    fun testIncompleteStatusMapsToIncompleteFinishReason()
    {
        val body = """
            {
              "id": "resp_1",
              "object": "response",
              "created_at": 0,
              "status": "incomplete",
              "model": "MiniMax-M2.7",
              "output": [
                {
                  "type": "message",
                  "id": "msg_1",
                  "role": "assistant",
                  "status": "incomplete",
                  "content": [
                    { "type": "output_text", "text": "partial", "annotations": [] }
                  ]
                }
              ],
              "usage": { "input_tokens": 0, "output_tokens": 0, "total_tokens": 0 }
            }
        """.trimIndent()

        val response = parser.parse(body, ApiMode.OpenAIResponses)
        Assertions.assertEquals("incomplete", response.choices[0].finishReason)
    }

    @Test
    fun testEmptyOutputYieldsEmptyText()
    {
        val body = """
            {
              "id": "resp_1",
              "object": "response",
              "created_at": 0,
              "status": "completed",
              "model": "MiniMax-M2.7",
              "output": [],
              "usage": { "input_tokens": 0, "output_tokens": 0, "total_tokens": 0 }
            }
        """.trimIndent()

        val response = parser.parse(body, ApiMode.OpenAIResponses)
        val content = response.choices[0].message.content
        Assertions.assertTrue(content is MessageContent.TextContent)
        Assertions.assertEquals("", (content as MessageContent.TextContent).text)
    }

//=========================================Error Response Mapping=========================================

    @Test
    fun testAuthErrorMapsToP2PExceptionAuth()
    {
        val body = """
            { "error": { "type": "invalid_api_key", "code": "401", "message": "Invalid API key" } }
        """.trimIndent()

        val exception = Assertions.assertThrows(P2PException::class.java) { parser.parse(body, ApiMode.OpenAIResponses) }
        Assertions.assertEquals(P2PError.auth, exception.errorType)
        Assertions.assertTrue(exception.message!!.contains("Invalid API key"))
    }

    @Test
    fun testRateLimitErrorMapsToP2PExceptionTransport()
    {
        val body = """
            { "error": { "type": "rate_limit_error", "code": "429", "message": "Slow down" } }
        """.trimIndent()

        val exception = Assertions.assertThrows(P2PException::class.java) { parser.parse(body, ApiMode.OpenAIResponses) }
        Assertions.assertEquals(P2PError.transport, exception.errorType)
    }

    @Test
    fun testInvalidRequestErrorMapsToP2PExceptionPrompt()
    {
        val body = """
            { "error": { "type": "invalid_request_error", "code": "400", "message": "Bad request" } }
        """.trimIndent()

        val exception = Assertions.assertThrows(P2PException::class.java) { parser.parse(body, ApiMode.OpenAIResponses) }
        Assertions.assertEquals(P2PError.prompt, exception.errorType)
    }
}
