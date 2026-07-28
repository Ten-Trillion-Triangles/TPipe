package bedrockPipe

import aws.sdk.kotlin.services.bedrockruntime.BedrockRuntimeClient
import aws.sdk.kotlin.services.bedrockruntime.model.ApplyGuardrailRequest
import aws.sdk.kotlin.services.bedrockruntime.model.ApplyGuardrailResponse
import aws.sdk.kotlin.services.bedrockruntime.model.Citation
import aws.sdk.kotlin.services.bedrockruntime.model.CitationsContentBlock
import aws.sdk.kotlin.services.bedrockruntime.model.ConverseRequest
import aws.sdk.kotlin.services.bedrockruntime.model.ConverseResponse
import aws.sdk.kotlin.services.bedrockruntime.model.ConverseStreamRequest
import aws.sdk.kotlin.services.bedrockruntime.model.ContentBlock
import aws.sdk.kotlin.services.bedrockruntime.model.ConverseStreamResponse
import aws.sdk.kotlin.services.bedrockruntime.model.ConversationRole
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
import aws.sdk.kotlin.services.bedrockruntime.model.StartAsyncInvokeRequest
import aws.sdk.kotlin.services.bedrockruntime.model.StartAsyncInvokeResponse
import aws.sdk.kotlin.services.bedrockruntime.model.StopReason
import aws.sdk.kotlin.services.bedrockruntime.model.TokenUsage
import com.TTT.Pipe.MultimodalContent
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Pins the non-streaming Converse-API citation flatten contract on
 * [BedrockMultimodalPipe].
 *
 * The Task 9 harvester walks the response content blocks and flattens any
 * [CitationsContentBlock] entries via:
 *
 *     responseCitations.flatMap { it.citations ?: emptyList() }
 *
 * That should land as `List<Citation>` on [BedrockCallMetadata.citations].
 * This test pins that contract with two cases:
 *
 * - Happy path: two [CitationsContentBlock] entries, each carrying one
 *   [Citation] in its `citations: List<Citation>` field -> 2 [Citation]s on
 *   metadata, with both titles preserved.
 * - Absent field: a [CitationsContentBlock] whose `citations` field is not
 *   populated (per AWS docs the field is optional) -> empty list, no NPE.
 *
 * Deviations from the plan's draft snippet (Step 1 of Task 5 in the plan at
 * .hermes/plans/2026-07-28_152342-citation-reassembly.md):
 *
 * - The plan's `BedrockRuntimeClient({ region = "us-east-1" })` constructor
 *   call does not compile against 1.6.107: `BedrockRuntimeClient` is an
 *   interface, not a builder. We mirror the [FakeConverseClient] pattern
 *   shipped in [ResponseContentBlockHarvestTest] -- every unused API throws,
 *   so a regression in test wiring is loud rather than silent.
 * - `pipe.bedrockClient = fakeClient` MUST come AFTER `pipe.init()`,
 *   because [BedrockPipe.init] unconditionally reassigns `bedrockClient`
 *   to a real [BedrockRuntimeClient] (see BedrockPipe.kt:1160). The plan's
 *   pre-init assignment would be immediately overwritten; we move the
 *   injection to immediately before `generateContent(...)`.
 *
 * No production code change -- this is a contract pin, not a fix.
 */
class NonStreamingCitationsFlattenTest
{
    @Test
    fun twoCitationsContentBlocksFlattenToListOfCitation()
    {
        // Two CitationsContentBlocks, each with one Citation in its `citations` field.
        // The Task 9 flatten `responseCitations.flatMap { it.citations ?: emptyList() }`
        // should produce a List<Citation> on BedrockCallMetadata.citations with size 2.
        val fakeClient = FakeConverseClientWithCitations(
            ConverseResponse {
                output = aws.sdk.kotlin.services.bedrockruntime.model.ConverseOutput.Message(Message {
                    role = ConversationRole.Assistant
                    content = listOf(
                        ContentBlock.CitationsContent(CitationsContentBlock {
                            citations = listOf(
                                Citation {
                                    title = "First Doc"
                                    source = "src-1"
                                }
                            )
                        }),
                        ContentBlock.CitationsContent(CitationsContentBlock {
                            citations = listOf(
                                Citation {
                                    title = "Second Doc"
                                    source = "src-2"
                                }
                            )
                        })
                    )
                })
                stopReason = StopReason.EndTurn
                usage = TokenUsage { inputTokens = 5; outputTokens = 5; totalTokens = 10 }
            }
        )

        val pipe = BedrockMultimodalPipe()
        pipe.useConverseApi()
        pipe.setModel("anthropic.claude-3-haiku-20240307-v1:0")
        runBlocking { pipe.init() }
        // MUST be assigned AFTER init(); see BedrockPipe.kt:1160.
        pipe.bedrockClient = fakeClient

        runBlocking { pipe.generateContent(MultimodalContent(text = "test")) }

        val metadata = pipe.getLastCallMetadata()
        assertNotNull(metadata, "BedrockCallMetadata should be populated after non-streaming call")
        assertEquals(2, metadata?.citations?.size,
            "Two CitationsContentBlocks should flatten to 2 Citations")
        metadata?.citations?.let { citations ->
            assertTrue(citations.any { it.title == "First Doc" },
                "First Doc citation should be preserved through flatten")
            assertTrue(citations.any { it.title == "Second Doc" },
                "Second Doc citation should be preserved through flatten")
        }
    }

    @Test
    fun citationsContentBlockWithNoCitationsListProducesEmptyResult()
    {
        // Defensive: CitationsContentBlock with absent `citations` field (per AWS
        // docs the field is optional). Flatten should produce an empty list, no NPE.
        val fakeClient = FakeConverseClientWithCitations(
            ConverseResponse {
                output = aws.sdk.kotlin.services.bedrockruntime.model.ConverseOutput.Message(Message {
                    role = ConversationRole.Assistant
                    content = listOf(
                        ContentBlock.CitationsContent(CitationsContentBlock {
                            // citations field not set
                        })
                    )
                })
                stopReason = StopReason.EndTurn
                usage = TokenUsage { inputTokens = 5; outputTokens = 5; totalTokens = 10 }
            }
        )

        val pipe = BedrockMultimodalPipe()
        pipe.useConverseApi()
        pipe.setModel("anthropic.claude-3-haiku-20240307-v1:0")
        runBlocking { pipe.init() }
        pipe.bedrockClient = fakeClient

        runBlocking { pipe.generateContent(MultimodalContent(text = "test")) }

        val metadata = pipe.getLastCallMetadata()
        assertNotNull(metadata, "BedrockCallMetadata should be populated after non-streaming call")
        assertEquals(0, metadata?.citations?.size ?: 0,
            "Empty citations list should produce empty result, not NPE")
    }
}

/**
 * File-local fake [BedrockRuntimeClient] that returns a canned [ConverseResponse]
 * for [converse] and throws on every other operation. Only `converse` is exercised
 * by these tests; throwing elsewhere ensures a regression in test wiring is loud,
 * not silent. Mirrors [FakeConverseClient] from
 * [ResponseContentBlockHarvestTest] but with a distinct FQN (Kotlin forbids two
 * `private` top-level classes with the same name in the same package).
 */
private class FakeConverseClientWithCitations(
    private val cannedResponse: ConverseResponse
) : BedrockRuntimeClient
{
    override val config: aws.sdk.kotlin.services.bedrockruntime.BedrockRuntimeClient.Config
        get() = throw UnsupportedOperationException("FakeConverseClientWithCitations.config should not be read by these tests")

    override suspend fun converse(input: ConverseRequest): ConverseResponse = cannedResponse

    override suspend fun <T> converseStream(
        input: ConverseStreamRequest,
        block: suspend (ConverseStreamResponse) -> T
    ): T = throw UnsupportedOperationException("converseStream not exercised in NonStreamingCitationsFlattenTest")

    override suspend fun invokeModel(input: InvokeModelRequest): InvokeModelResponse =
        throw UnsupportedOperationException("invokeModel not exercised in NonStreamingCitationsFlattenTest")

    override suspend fun applyGuardrail(input: ApplyGuardrailRequest): ApplyGuardrailResponse =
        throw UnsupportedOperationException("applyGuardrail not exercised in NonStreamingCitationsFlattenTest")

    override suspend fun invokeGuardrailChecks(input: InvokeGuardrailChecksRequest): InvokeGuardrailChecksResponse =
        throw UnsupportedOperationException("invokeGuardrailChecks not exercised in NonStreamingCitationsFlattenTest")

    override suspend fun countTokens(input: CountTokensRequest): CountTokensResponse =
        throw UnsupportedOperationException("countTokens not exercised in NonStreamingCitationsFlattenTest")

    override suspend fun startAsyncInvoke(input: StartAsyncInvokeRequest): StartAsyncInvokeResponse =
        throw UnsupportedOperationException("startAsyncInvoke not exercised in NonStreamingCitationsFlattenTest")

    override suspend fun getAsyncInvoke(input: GetAsyncInvokeRequest): GetAsyncInvokeResponse =
        throw UnsupportedOperationException("getAsyncInvoke not exercised in NonStreamingCitationsFlattenTest")

    override suspend fun listAsyncInvokes(input: ListAsyncInvokesRequest): ListAsyncInvokesResponse =
        throw UnsupportedOperationException("listAsyncInvokes not exercised in NonStreamingCitationsFlattenTest")

    override suspend fun <T> invokeModelWithResponseStream(
        input: InvokeModelWithResponseStreamRequest,
        block: suspend (InvokeModelWithResponseStreamResponse) -> T
    ): T = throw UnsupportedOperationException("invokeModelWithResponseStream not exercised in NonStreamingCitationsFlattenTest")

    override suspend fun <T> invokeModelWithBidirectionalStream(
        input: InvokeModelWithBidirectionalStreamRequest,
        block: suspend (InvokeModelWithBidirectionalStreamResponse) -> T
    ): T = throw UnsupportedOperationException("invokeModelWithBidirectionalStream not exercised in NonStreamingCitationsFlattenTest")

    override fun close() {
        // no-op; the fake owns no resources
    }
}
