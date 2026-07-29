package genericOpenAIPipe.mantle

import genericOpenAIPipe.GenericOpenAIPipe
import genericOpenAIPipe.MockStreamingConnectionFactory
import genericOpenAIPipe.api.ApiMode
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions

class MantleSseFixtureReplayTest
{
    @Test
    fun testMantleChatCompletionsSseFixtureReplay() = runBlocking<Unit>
    {
        val fixture = "data: {\"choices\":[{\"delta\":{\"content\":\"Hel\"}}]}\n\n" +
            "data: {\"choices\":[{\"delta\":{\"content\":\"lo\"}}]}\n\n" +
            "data: [DONE]\n\n"

        val factory = MockStreamingConnectionFactory(responseBodySupplier = { fixture })

        // setModel returns the parent Pipe type, so call it on the typed
        // GenericOpenAIPipe reference after the chained setters.
        val pipe = GenericOpenAIPipe()
            .setApiKey("test-key")
            .setBaseUrl("https://bedrock-mantle.us-east-1.api.aws/openai/v1")
            .setApiMode(ApiMode.OpenAI)
        pipe.setModel("google.gemma-4-31b")
        pipe.setStreamingEnabled(true)
        pipe.injectStreamingConnectionFactoryForTest(factory)
        pipe.initForTest()

        try
        {
            val result = pipe.generateTextForTest("hi")
            Assertions.assertEquals("Hello", result)
            Assertions.assertTrue(
                factory.capturedUrl.endsWith("/chat/completions"),
                "Request URL must point at the Chat Completions endpoint. Got: ${factory.capturedUrl}"
            )
            Assertions.assertTrue(
                factory.capturedHeaders["Authorization"] == "Bearer test-key",
                "Bearer auth header must be sent. Got: ${factory.capturedHeaders["Authorization"]}"
            )
        }
        finally
        {
            pipe.abortForTest()
        }
    }

    @Test
    fun testMantleResponsesApiSseFixtureReplay() = runBlocking<Unit>
    {
        val fixture = "data: {\"type\":\"response.output_text.delta\",\"delta\":\"pong\"}\n\n" +
            "data: {\"type\":\"response.completed\",\"response\":{\"id\":\"resp_fixture\",\"object\":\"response\",\"model\":\"google.gemma-4-31b\",\"usage\":{\"input_tokens\":3,\"output_tokens\":1,\"total_tokens\":4}}}\n\n"

        val factory = MockStreamingConnectionFactory(responseBodySupplier = { fixture })

        val pipe = GenericOpenAIPipe()
            .setApiKey("test-key")
            .setBaseUrl("https://bedrock-mantle.us-east-1.api.aws/openai/v1")
            .setApiMode(ApiMode.OpenAIResponses)
        pipe.setModel("google.gemma-4-31b")
        pipe.setStreamingEnabled(true)
        pipe.injectStreamingConnectionFactoryForTest(factory)
        pipe.initForTest()

        try
        {
            val result = pipe.generateTextForTest("hi")
            Assertions.assertEquals("pong", result)
            Assertions.assertTrue(
                factory.capturedUrl.endsWith("/responses"),
                "Request URL must point at the Responses endpoint. Got: ${factory.capturedUrl}"
            )
            Assertions.assertTrue(
                factory.capturedHeaders["Authorization"] == "Bearer test-key",
                "Bearer auth header must be sent. Got: ${factory.capturedHeaders["Authorization"]}"
            )
        }
        finally
        {
            pipe.abortForTest()
        }
    }
}
