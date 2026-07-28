package bedrockPipe

import aws.sdk.kotlin.services.bedrockruntime.BedrockRuntimeClient
import aws.sdk.kotlin.services.bedrockruntime.model.ApplyGuardrailRequest
import aws.sdk.kotlin.services.bedrockruntime.model.ApplyGuardrailResponse
import aws.sdk.kotlin.services.bedrockruntime.model.ContentBlock
import aws.sdk.kotlin.services.bedrockruntime.model.ContentBlockDelta
import aws.sdk.kotlin.services.bedrockruntime.model.ContentBlockDeltaEvent
import aws.sdk.kotlin.services.bedrockruntime.model.ContentBlockStopEvent
import aws.sdk.kotlin.services.bedrockruntime.model.ConversationRole
import aws.sdk.kotlin.services.bedrockruntime.model.ConverseRequest
import aws.sdk.kotlin.services.bedrockruntime.model.ConverseResponse
import aws.sdk.kotlin.services.bedrockruntime.model.ConverseStreamMetadataEvent
import aws.sdk.kotlin.services.bedrockruntime.model.ConverseStreamMetrics
import aws.sdk.kotlin.services.bedrockruntime.model.ConverseStreamOutput
import aws.sdk.kotlin.services.bedrockruntime.model.ConverseStreamRequest
import aws.sdk.kotlin.services.bedrockruntime.model.ConverseStreamResponse
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
import aws.sdk.kotlin.services.bedrockruntime.model.Message
import aws.sdk.kotlin.services.bedrockruntime.model.MessageStartEvent
import aws.sdk.kotlin.services.bedrockruntime.model.MessageStopEvent
import aws.sdk.kotlin.services.bedrockruntime.model.StartAsyncInvokeRequest
import aws.sdk.kotlin.services.bedrockruntime.model.StartAsyncInvokeResponse
import aws.sdk.kotlin.services.bedrockruntime.model.StopReason
import aws.sdk.kotlin.services.bedrockruntime.model.TokenUsage
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class StreamingMetricsTest
{
    @Test
    fun streamingLatencyFromMetadataIsCapturedIntoBedrockCallMetadata()
    {
        // Hand-craft a stream that includes a Metadata event whose `metrics`
        // block carries `latencyMs = 1234L`. After the streaming call the
        // pipe's `lastCallMetadata` should reflect that latency value.
        val events = listOf(
            ConverseStreamOutput.MessageStart(MessageStartEvent { role = ConversationRole.Assistant }),
            ConverseStreamOutput.ContentBlockDelta(ContentBlockDeltaEvent {
                contentBlockIndex = 0
                delta = ContentBlockDelta.Text("ok")
            }),
            ConverseStreamOutput.ContentBlockStop(ContentBlockStopEvent { contentBlockIndex = 0 }),
            ConverseStreamOutput.MessageStop(MessageStopEvent { stopReason = StopReason.EndTurn }),
            ConverseStreamOutput.Metadata(ConverseStreamMetadataEvent {
                usage = TokenUsage { inputTokens = 10; outputTokens = 1; totalTokens = 11 }
                metrics = ConverseStreamMetrics { latencyMs = 1234L }
            })
        )

        val fakeClient = FakeBedrockRuntimeClientStreamingMetrics(events)
        val pipe = BedrockPipe()
        pipe.useConverseApi()

        val request = ConverseStreamRequest {
            modelId = "anthropic.claude-3-haiku-20240307-v1:0"
            messages = listOf(Message {
                role = ConversationRole.User
                content = listOf(ContentBlock.Text("hi"))
            })
        }

        runBlocking {
            pipe.executeConverseStreamForTest(fakeClient, "anthropic.claude-3-haiku-20240307-v1:0", request, "test")
        }

        val metadata = pipe.getLastCallMetadata()
        assertNotNull(metadata, "BedrockCallMetadata should be populated after streaming call")
        assertEquals(1234L, metadata?.latencyMs, "latencyMs from ConverseStreamMetrics should be captured")
    }

    @Test
    fun streamingMetricsWithoutLatencyFieldIsNull()
    {
        // Stream with Metadata but no `metrics` block — latencyMs should remain null
        // (metrics.latencyMs is a primitive `Long` on the SDK, so ConverseStreamMetrics{}
        // with no `latencyMs = ...` defaults to 0. The pipe must treat absence as null
        // rather than letting 0 leak through as a fake latency.)
        val events = listOf(
            ConverseStreamOutput.MessageStart(MessageStartEvent { role = ConversationRole.Assistant }),
            ConverseStreamOutput.ContentBlockDelta(ContentBlockDeltaEvent {
                contentBlockIndex = 0
                delta = ContentBlockDelta.Text("ok")
            }),
            ConverseStreamOutput.ContentBlockStop(ContentBlockStopEvent { contentBlockIndex = 0 }),
            ConverseStreamOutput.MessageStop(MessageStopEvent { stopReason = StopReason.EndTurn }),
            ConverseStreamOutput.Metadata(ConverseStreamMetadataEvent {
                usage = TokenUsage { inputTokens = 5; outputTokens = 1; totalTokens = 6 }
                // No metrics block — getMetrics() returns null on the wire, so the
                // SDK doesn't materialize an empty ConverseStreamMetrics instance.
            })
        )

        val fakeClient = FakeBedrockRuntimeClientStreamingMetrics(events)
        val pipe = BedrockPipe()
        pipe.useConverseApi()

        val request = ConverseStreamRequest {
            modelId = "anthropic.claude-3-haiku-20240307-v1:0"
            messages = listOf(Message {
                role = ConversationRole.User
                content = listOf(ContentBlock.Text("hi"))
            })
        }

        runBlocking {
            pipe.executeConverseStreamForTest(fakeClient, "anthropic.claude-3-haiku-20240307-v1:0", request, "test")
        }

        val metadata = pipe.getLastCallMetadata()
        assertNotNull(metadata)
        assertNull(metadata?.latencyMs, "latencyMs should be null when metrics not provided")
    }
}

/**
 * Fake BedrockRuntimeClient tailored to StreamingMetricsTest. Mirrors the
 * pattern in StreamingBlockEventTest — only `converseStream` is exercised
 * by the unit tests in this file; every other method throws so a test
 * wiring regression is loud rather than silent.
 */
private class FakeBedrockRuntimeClientStreamingMetrics(
    private val cannedEvents: List<ConverseStreamOutput>
) : BedrockRuntimeClient
{
    override val config: aws.sdk.kotlin.services.bedrockruntime.BedrockRuntimeClient.Config
        get() = throw UnsupportedOperationException("FakeBedrockRuntimeClientStreamingMetrics.config should not be read by these tests")

    override suspend fun <T> converseStream(
        input: ConverseStreamRequest,
        block: suspend (ConverseStreamResponse) -> T
    ): T
    {
        val response = ConverseStreamResponse {
            stream = flowOf(*cannedEvents.toTypedArray())
        }
        return block(response)
    }

    override suspend fun converse(input: ConverseRequest): ConverseResponse =
        throw UnsupportedOperationException("converse not exercised in StreamingMetricsTest")

    override suspend fun invokeModel(input: InvokeModelRequest): InvokeModelResponse =
        throw UnsupportedOperationException("invokeModel not exercised in StreamingMetricsTest")

    override suspend fun applyGuardrail(input: ApplyGuardrailRequest): ApplyGuardrailResponse =
        throw UnsupportedOperationException("applyGuardrail not exercised in StreamingMetricsTest")

    override suspend fun invokeGuardrailChecks(input: InvokeGuardrailChecksRequest): InvokeGuardrailChecksResponse =
        throw UnsupportedOperationException("invokeGuardrailChecks not exercised in StreamingMetricsTest")

    override suspend fun countTokens(input: CountTokensRequest): CountTokensResponse =
        throw UnsupportedOperationException("countTokens not exercised in StreamingMetricsTest")

    override suspend fun startAsyncInvoke(input: StartAsyncInvokeRequest): StartAsyncInvokeResponse =
        throw UnsupportedOperationException("startAsyncInvoke not exercised in StreamingMetricsTest")

    override suspend fun getAsyncInvoke(input: GetAsyncInvokeRequest): GetAsyncInvokeResponse =
        throw UnsupportedOperationException("getAsyncInvoke not exercised in StreamingMetricsTest")

    override suspend fun listAsyncInvokes(
        input: ListAsyncInvokesRequest
    ): ListAsyncInvokesResponse =
        throw UnsupportedOperationException("listAsyncInvokes not exercised in StreamingMetricsTest")

    override suspend fun <T> invokeModelWithResponseStream(
        input: InvokeModelWithResponseStreamRequest,
        block: suspend (InvokeModelWithResponseStreamResponse) -> T
    ): T = throw UnsupportedOperationException("invokeModelWithResponseStream not exercised in StreamingMetricsTest")

    override suspend fun <T> invokeModelWithBidirectionalStream(
        input: InvokeModelWithBidirectionalStreamRequest,
        block: suspend (InvokeModelWithBidirectionalStreamResponse) -> T
    ): T = throw UnsupportedOperationException("invokeModelWithBidirectionalStream not exercised in StreamingMetricsTest")

    override fun close()
    {
        // no-op; the fake owns no resources
    }
}
