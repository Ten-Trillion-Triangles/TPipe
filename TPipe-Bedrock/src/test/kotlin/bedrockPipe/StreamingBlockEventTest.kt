package bedrockPipe

import aws.sdk.kotlin.services.bedrockruntime.BedrockRuntimeClient
import aws.sdk.kotlin.services.bedrockruntime.model.ContentBlockDelta
import aws.sdk.kotlin.services.bedrockruntime.model.ContentBlockDeltaEvent
import aws.sdk.kotlin.services.bedrockruntime.model.ContentBlockStart
import aws.sdk.kotlin.services.bedrockruntime.model.ContentBlockStartEvent
import aws.sdk.kotlin.services.bedrockruntime.model.ContentBlockStopEvent
import aws.sdk.kotlin.services.bedrockruntime.model.ConverseStreamRequest
import aws.sdk.kotlin.services.bedrockruntime.model.ConverseStreamResponse
import aws.sdk.kotlin.services.bedrockruntime.model.ConverseStreamOutput
import aws.sdk.kotlin.services.bedrockruntime.model.MessageStartEvent
import aws.sdk.kotlin.services.bedrockruntime.model.MessageStopEvent
import aws.sdk.kotlin.services.bedrockruntime.model.StopReason
import aws.sdk.kotlin.services.bedrockruntime.model.ToolUseBlockDelta
import aws.sdk.kotlin.services.bedrockruntime.model.ToolUseBlockStart
import aws.sdk.kotlin.services.bedrockruntime.model.TokenUsage
import aws.sdk.kotlin.services.bedrockruntime.model.ConversationRole
import aws.sdk.kotlin.services.bedrockruntime.model.Message
import aws.sdk.kotlin.services.bedrockruntime.model.ContentBlock
import aws.sdk.kotlin.services.bedrockruntime.model.ConverseStreamMetadataEvent
import aws.sdk.kotlin.services.bedrockruntime.model.ApplyGuardrailRequest
import aws.sdk.kotlin.services.bedrockruntime.model.ApplyGuardrailResponse
import aws.sdk.kotlin.services.bedrockruntime.model.ConverseRequest
import aws.sdk.kotlin.services.bedrockruntime.model.ConverseResponse
import aws.sdk.kotlin.services.bedrockruntime.model.CountTokensRequest
import aws.sdk.kotlin.services.bedrockruntime.model.CountTokensResponse
import aws.sdk.kotlin.services.bedrockruntime.model.GetAsyncInvokeRequest
import aws.sdk.kotlin.services.bedrockruntime.model.GetAsyncInvokeResponse
import aws.sdk.kotlin.services.bedrockruntime.model.InvokeGuardrailChecksRequest
import aws.sdk.kotlin.services.bedrockruntime.model.InvokeGuardrailChecksResponse
import aws.sdk.kotlin.services.bedrockruntime.model.InvokeModelRequest
import aws.sdk.kotlin.services.bedrockruntime.model.InvokeModelResponse
import aws.sdk.kotlin.services.bedrockruntime.model.InvokeModelWithBidirectionalStreamRequest
import aws.sdk.kotlin.services.bedrockruntime.model.InvokeModelWithBidirectionalStreamResponse
import aws.sdk.kotlin.services.bedrockruntime.model.InvokeModelWithResponseStreamRequest
import aws.sdk.kotlin.services.bedrockruntime.model.InvokeModelWithResponseStreamResponse
import aws.sdk.kotlin.services.bedrockruntime.model.ListAsyncInvokesRequest
import aws.sdk.kotlin.services.bedrockruntime.model.ListAsyncInvokesResponse
import aws.sdk.kotlin.services.bedrockruntime.model.StartAsyncInvokeRequest
import aws.sdk.kotlin.services.bedrockruntime.model.StartAsyncInvokeResponse
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

class StreamingBlockEventTest
{
    @Test
    fun streamingToolUseIsCapturedIntoBedrockCallMetadata()
    {
        // Hand-craft a stream with: MessageStart -> ContentBlockStart(toolUse) ->
        // 2x ContentBlockDelta(toolUse input) -> ContentBlockStop -> MessageStop -> Metadata
        val toolId = "tool_use_abc123"
        val toolName = "get_weather"
        val toolInputPart1 = """{"location":"""
        val toolInputPart2 = """"San Francisco"}"""

        val events = listOf(
            ConverseStreamOutput.MessageStart(MessageStartEvent { role = ConversationRole.Assistant }),
            ConverseStreamOutput.ContentBlockStart(ContentBlockStartEvent {
                contentBlockIndex = 0
                start = ContentBlockStart.ToolUse(ToolUseBlockStart {
                    this.toolUseId = toolId
                    this.name = toolName
                })
            }),
            ConverseStreamOutput.ContentBlockDelta(ContentBlockDeltaEvent {
                contentBlockIndex = 0
                delta = ContentBlockDelta.ToolUse(ToolUseBlockDelta {
                    this.input = toolInputPart1
                })
            }),
            ConverseStreamOutput.ContentBlockDelta(ContentBlockDeltaEvent {
                contentBlockIndex = 0
                delta = ContentBlockDelta.ToolUse(ToolUseBlockDelta {
                    this.input = toolInputPart2
                })
            }),
            ConverseStreamOutput.ContentBlockStop(ContentBlockStopEvent { contentBlockIndex = 0 }),
            ConverseStreamOutput.MessageStop(MessageStopEvent {
                stopReason = StopReason.ToolUse
            }),
            ConverseStreamOutput.Metadata(ConverseStreamMetadataEvent {
                usage = TokenUsage {
                    inputTokens = 50
                    outputTokens = 20
                    totalTokens = 70
                }
            })
        )

        val fakeClient = FakeBedrockRuntimeClient(events)
        val pipe = BedrockPipe()
        pipe.useConverseApi()

        val request = ConverseStreamRequest {
            modelId = "anthropic.claude-3-haiku-20240307-v1:0"
            messages = listOf(Message {
                role = ConversationRole.User
                content = listOf(ContentBlock.Text("What's the weather?"))
            })
        }

        runBlocking {
            pipe.executeConverseStreamForTest(fakeClient, "anthropic.claude-3-haiku-20240307-v1:0", request, "test")
        }

        val metadata = pipe.getLastCallMetadata()
        assertNotNull(metadata, "BedrockCallMetadata should be populated after streaming call")
        metadata?.let { meta ->
            assertEquals(1, meta.toolUse.size, "Should capture exactly one tool use block")
            val captured = meta.toolUse[0]
            assertEquals(toolId, captured.toolUseId, "Tool use ID should be captured")
            assertEquals(toolName, captured.name, "Tool name should be captured from start event")
            assertNotNull(captured.input, "Tool input Document should be populated")
            // The two input fragments should be concatenated verbatim into the
            // Document.String value backing this Document instance.
            val expectedInput = toolInputPart1 + toolInputPart2
            val actualInputString = captured.input?.asString()
            assertEquals(expectedInput, actualInputString, "Input JSON fragments should be concatenated")
            assertEquals("tool_use", meta.stopReason, "stopReason should be captured from MessageStop")
        }
    }

    @Test
    fun streamingTextOnlyNoToolUseHasEmptyToolUseList()
    {
        val events = listOf(
            ConverseStreamOutput.MessageStart(MessageStartEvent { role = ConversationRole.Assistant }),
            ConverseStreamOutput.ContentBlockDelta(ContentBlockDeltaEvent {
                contentBlockIndex = 0
                delta = ContentBlockDelta.Text("Hello, world!")
            }),
            ConverseStreamOutput.ContentBlockStop(ContentBlockStopEvent { contentBlockIndex = 0 }),
            ConverseStreamOutput.MessageStop(MessageStopEvent {
                stopReason = StopReason.EndTurn
            }),
            ConverseStreamOutput.Metadata(ConverseStreamMetadataEvent {
                usage = TokenUsage { inputTokens = 5; outputTokens = 3; totalTokens = 8 }
            })
        )

        val fakeClient = FakeBedrockRuntimeClient(events)
        val pipe = BedrockPipe()
        pipe.useConverseApi()

        val request = ConverseStreamRequest {
            modelId = "anthropic.claude-3-haiku-20240307-v1:0"
            messages = listOf(Message {
                role = ConversationRole.User
                content = listOf(ContentBlock.Text("Hi"))
            })
        }

        runBlocking {
            pipe.executeConverseStreamForTest(fakeClient, "anthropic.claude-3-haiku-20240307-v1:0", request, "test")
        }

        val metadata = pipe.getLastCallMetadata()
        assertNotNull(metadata)
        assertEquals(0, metadata?.toolUse?.size ?: 0, "Text-only stream should have empty toolUse")
        assertEquals("end_turn", metadata?.stopReason)
    }
}

/**
 * Fake BedrockRuntimeClient that returns a canned event flow when converseStream is called.
 * Implements the [BedrockRuntimeClient] interface directly — only [converseStream] is exercised
 * by these tests; every other method throws if called (so a regression in test wiring is loud,
 * not silent).
 */
private class FakeBedrockRuntimeClient(
    private val cannedEvents: List<ConverseStreamOutput>
) : BedrockRuntimeClient
{
    override val config: aws.sdk.kotlin.services.bedrockruntime.BedrockRuntimeClient.Config
        get() = throw UnsupportedOperationException("FakeBedrockRuntimeClient.config should not be read by these tests")

    override suspend fun <T> converseStream(
        input: ConverseStreamRequest,
        block: suspend (ConverseStreamResponse) -> T
    ): T
    {
        // Provide the canned flow as the response stream
        val response = ConverseStreamResponse {
            stream = flowOf(*cannedEvents.toTypedArray())
        }
        return block(response)
    }

    override suspend fun converse(input: ConverseRequest): ConverseResponse =
        throw UnsupportedOperationException("converse not exercised in StreamingBlockEventTest")

    override suspend fun invokeModel(input: InvokeModelRequest): InvokeModelResponse =
        throw UnsupportedOperationException("invokeModel not exercised in StreamingBlockEventTest")

    override suspend fun applyGuardrail(input: ApplyGuardrailRequest): ApplyGuardrailResponse =
        throw UnsupportedOperationException("applyGuardrail not exercised in StreamingBlockEventTest")

    override suspend fun invokeGuardrailChecks(input: InvokeGuardrailChecksRequest): InvokeGuardrailChecksResponse =
        throw UnsupportedOperationException("invokeGuardrailChecks not exercised in StreamingBlockEventTest")

    override suspend fun countTokens(input: CountTokensRequest): CountTokensResponse =
        throw UnsupportedOperationException("countTokens not exercised in StreamingBlockEventTest")

    override suspend fun startAsyncInvoke(input: StartAsyncInvokeRequest): StartAsyncInvokeResponse =
        throw UnsupportedOperationException("startAsyncInvoke not exercised in StreamingBlockEventTest")

    override suspend fun getAsyncInvoke(input: GetAsyncInvokeRequest): GetAsyncInvokeResponse =
        throw UnsupportedOperationException("getAsyncInvoke not exercised in StreamingBlockEventTest")

    override suspend fun listAsyncInvokes(
        input: ListAsyncInvokesRequest
    ): ListAsyncInvokesResponse =
        throw UnsupportedOperationException("listAsyncInvokes not exercised in StreamingBlockEventTest")

    override suspend fun <T> invokeModelWithResponseStream(
        input: InvokeModelWithResponseStreamRequest,
        block: suspend (InvokeModelWithResponseStreamResponse) -> T
    ): T = throw UnsupportedOperationException("invokeModelWithResponseStream not exercised in StreamingBlockEventTest")

    override suspend fun <T> invokeModelWithBidirectionalStream(
        input: InvokeModelWithBidirectionalStreamRequest,
        block: suspend (InvokeModelWithBidirectionalStreamResponse) -> T
    ): T = throw UnsupportedOperationException("invokeModelWithBidirectionalStream not exercised in StreamingBlockEventTest")

    override fun close()
    {
        // no-op; the fake owns no resources
    }
}
