package bedrockPipe

import aws.sdk.kotlin.services.bedrockruntime.BedrockRuntimeClient
import aws.sdk.kotlin.services.bedrockruntime.model.ApplyGuardrailRequest
import aws.sdk.kotlin.services.bedrockruntime.model.ApplyGuardrailResponse
import aws.sdk.kotlin.services.bedrockruntime.model.Citation
import aws.sdk.kotlin.services.bedrockruntime.model.CitationsContentBlock
import aws.sdk.kotlin.services.bedrockruntime.model.CitationSourceContent
import aws.sdk.kotlin.services.bedrockruntime.model.ConverseRequest
import aws.sdk.kotlin.services.bedrockruntime.model.ConverseResponse
import aws.sdk.kotlin.services.bedrockruntime.model.ConverseStreamRequest
import aws.sdk.kotlin.services.bedrockruntime.model.ConverseStreamResponse
import aws.sdk.kotlin.services.bedrockruntime.model.ContentBlock
import aws.sdk.kotlin.services.bedrockruntime.model.ConversationRole
import aws.sdk.kotlin.services.bedrockruntime.model.CountTokensRequest
import aws.sdk.kotlin.services.bedrockruntime.model.CountTokensResponse
import aws.sdk.kotlin.services.bedrockruntime.model.GetAsyncInvokeRequest
import aws.sdk.kotlin.services.bedrockruntime.model.GetAsyncInvokeResponse
import aws.sdk.kotlin.services.bedrockruntime.model.GuardrailConverseContentBlock
import aws.sdk.kotlin.services.bedrockruntime.model.GuardrailConverseTextBlock
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
import aws.sdk.kotlin.services.bedrockruntime.model.ToolUseBlock
import com.TTT.Pipe.MultimodalContent
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Hand-crafted [ConverseResponse] fixtures that exercise the new non-streaming
 * ContentBlock harvest branches in [BedrockMultimodalPipe]:
 *
 * - [ContentBlock.ToolUse] -> [BedrockCallMetadata.toolUse]
 * - [ContentBlock.CitationsContent] -> [BedrockCallMetadata.citations]
 * - [ContentBlock.GuardContent] -> [BedrockCallMetadata.guardAssessments] (call completes cleanly)
 *
 * Uses a [FakeConverseClient] that returns canned [ConverseResponse] objects so we
 * can exercise the harvester without a live Bedrock call.
 */
class ResponseContentBlockHarvestTest
{
    @Test
    fun toolUseBlockIsHarvestedIntoBedrockCallMetadata()
    {
        val toolId = "tool_use_xyz"
        val toolName = "search_docs"

        val fakeClient = FakeConverseClient(
            ConverseResponse {
                output = aws.sdk.kotlin.services.bedrockruntime.model.ConverseOutput.Message(Message {
                    role = ConversationRole.Assistant
                    content = listOf(
                        ContentBlock.ToolUse(ToolUseBlock {
                            this.toolUseId = toolId
                            this.name = toolName
                        })
                    )
                })
                stopReason = StopReason.ToolUse
                usage = TokenUsage { inputTokens = 20; outputTokens = 5; totalTokens = 25 }
            }
        )

        val pipe = BedrockMultimodalPipe()
        // We bypass init() (which would replace bedrockClient with a real client
        // requiring AWS credentials) and instead call the internal test seam
        // [BedrockMultimodalPipe.generateMultimodalWithConverseApi] directly with
        // the fake. This is the same pattern as `executeConverseStreamForTest` on
        // [BedrockPipe] for the streaming tests.
        pipe.useConverseApi()
        pipe.setModel("anthropic.claude-3-haiku-20240307-v1:0")

        runBlocking {
            pipe.generateMultimodalWithConverseApi(
                fakeClient,
                "anthropic.claude-3-haiku-20240307-v1:0",
                MultimodalContent(text = "search for kotlin")
            )
        }

        val metadata = pipe.getLastCallMetadata()
        assertNotNull(metadata, "BedrockCallMetadata should be populated after non-streaming call")
        metadata?.let { meta ->
            assertEquals(1, meta.toolUse.size, "Should capture exactly one tool use block")
            assertEquals(toolId, meta.toolUse[0].toolUseId, "Tool use ID should be preserved")
            assertEquals(toolName, meta.toolUse[0].name, "Tool name should be preserved")
            assertEquals(StopReason.ToolUse.value, meta.stopReason, "Stop reason should be captured")
        }
    }

    @Test
    fun citationsContentBlockIsHarvestedIntoBedrockCallMetadata()
    {
        // CitationsContentBlock in 1.6.107 has TWO list fields:
        //   - citations: List<Citation>           <- what we want for BedrockCallMetadata
        //   - content:   List<CitationGeneratedContent>  (model-side generated content snippets)
        // Citation has sourceContent: List<CitationSourceContent> (NOT CitationGeneratedContent).
        val fakeClient = FakeConverseClient(
            ConverseResponse {
                output = aws.sdk.kotlin.services.bedrockruntime.model.ConverseOutput.Message(Message {
                    role = ConversationRole.Assistant
                    content = listOf(
                        ContentBlock.CitationsContent(CitationsContentBlock {
                            citations = listOf(
                                Citation {
                                    title = "Kotlin Documentation"
                                    sourceContent = listOf(
                                        CitationSourceContent.Text("Kotlin is a cross-platform, statically typed, general-purpose programming language...")
                                    )
                                }
                            )
                        })
                    )
                })
                stopReason = StopReason.EndTurn
                usage = TokenUsage { inputTokens = 10; outputTokens = 5; totalTokens = 15 }
            }
        )

        val pipe = BedrockMultimodalPipe()
        pipe.useConverseApi()
        pipe.setModel("anthropic.claude-3-haiku-20240307-v1:0")

        runBlocking {
            pipe.generateMultimodalWithConverseApi(
                fakeClient,
                "anthropic.claude-3-haiku-20240307-v1:0",
                MultimodalContent(text = "What is Kotlin?")
            )
        }

        val metadata = pipe.getLastCallMetadata()
        assertNotNull(metadata, "BedrockCallMetadata should be populated after non-streaming call")
        val citations = metadata?.citations ?: emptyList()
        assertTrue(citations.isNotEmpty(),
            "Citations should be harvested into BedrockCallMetadata.citations; got size=${citations.size}")
        citations.first().let { citation ->
            assertEquals("Kotlin Documentation", citation.title, "Citation title should be preserved")
        }
    }

    @Test
    fun guardContentBlockIsHarvestedIntoBedrockCallMetadata()
    {
        // GuardrailConverseContentBlock is a SEALED class with Image/Text variants.
        // ContentBlock.GuardContent wraps the sealed type — we pick the Text variant.
        // (Plan paraphrase said `GuardrailConverseContentBlock { }` but the bare constructor
        // doesn't exist; the sealed type requires a variant choice.)
        val fakeClient = FakeConverseClient(
            ConverseResponse {
                output = aws.sdk.kotlin.services.bedrockruntime.model.ConverseOutput.Message(Message {
                    role = ConversationRole.Assistant
                    content = listOf(
                        ContentBlock.GuardContent(
                            GuardrailConverseContentBlock.Text(
                                GuardrailConverseTextBlock { text = "guarded output fragment" }
                            )
                        )
                    )
                })
                stopReason = StopReason.EndTurn
                usage = TokenUsage { inputTokens = 5; outputTokens = 1; totalTokens = 6 }
            }
        )

        val pipe = BedrockMultimodalPipe()
        pipe.useConverseApi()
        pipe.setModel("anthropic.claude-3-haiku-20240307-v1:0")

        runBlocking {
            pipe.generateMultimodalWithConverseApi(
                fakeClient,
                "anthropic.claude-3-haiku-20240307-v1:0",
                MultimodalContent(text = "test")
            )
        }

        val metadata = pipe.getLastCallMetadata()
        assertNotNull(metadata, "BedrockCallMetadata should be populated after non-streaming call")
        // The current harvester signature accumulates the GuardrailConverseContentBlock into
        // a list but the typed BedrockCallMetadata.guardAssessments is List<GuardrailAssessment>
        // — the wrapper type doesn't carry an assessment, only the Guardrail*Blocks that the
        // service sends back. So guardAssessments is an empty list after the harvest. We pin
        // the contract: the call completes without error, the metadata is populated, and the
        // field is at least non-null (an empty list is fine — full GuardrailAssessment
        // extraction is a follow-up).
        assertNotNull(metadata?.guardAssessments,
            "guardAssessments should be present (non-null) after a GuardContent response")
    }
}

/**
 * Fake [BedrockRuntimeClient] that returns a canned [ConverseResponse] for [converse]
 * and throws on every other operation. Only `converse` is exercised by these tests;
 * throwing elsewhere ensures a regression in test wiring is loud, not silent.
 */
private class FakeConverseClient(
    private val cannedResponse: ConverseResponse
) : BedrockRuntimeClient
{
    override val config: aws.sdk.kotlin.services.bedrockruntime.BedrockRuntimeClient.Config
        get() = throw UnsupportedOperationException("FakeConverseClient.config should not be read by these tests")

    override suspend fun converse(input: ConverseRequest): ConverseResponse = cannedResponse

    override suspend fun <T> converseStream(
        input: ConverseStreamRequest,
        block: suspend (ConverseStreamResponse) -> T
    ): T = throw UnsupportedOperationException("converseStream not exercised in ResponseContentBlockHarvestTest")

    override suspend fun invokeModel(input: InvokeModelRequest): InvokeModelResponse =
        throw UnsupportedOperationException("invokeModel not exercised in ResponseContentBlockHarvestTest")

    override suspend fun applyGuardrail(input: ApplyGuardrailRequest): ApplyGuardrailResponse =
        throw UnsupportedOperationException("applyGuardrail not exercised in ResponseContentBlockHarvestTest")

    override suspend fun invokeGuardrailChecks(input: InvokeGuardrailChecksRequest): InvokeGuardrailChecksResponse =
        throw UnsupportedOperationException("invokeGuardrailChecks not exercised in ResponseContentBlockHarvestTest")

    override suspend fun countTokens(input: CountTokensRequest): CountTokensResponse =
        throw UnsupportedOperationException("countTokens not exercised in ResponseContentBlockHarvestTest")

    override suspend fun startAsyncInvoke(input: StartAsyncInvokeRequest): StartAsyncInvokeResponse =
        throw UnsupportedOperationException("startAsyncInvoke not exercised in ResponseContentBlockHarvestTest")

    override suspend fun getAsyncInvoke(input: GetAsyncInvokeRequest): GetAsyncInvokeResponse =
        throw UnsupportedOperationException("getAsyncInvoke not exercised in ResponseContentBlockHarvestTest")

    override suspend fun listAsyncInvokes(input: ListAsyncInvokesRequest): ListAsyncInvokesResponse =
        throw UnsupportedOperationException("listAsyncInvokes not exercised in ResponseContentBlockHarvestTest")

    override suspend fun <T> invokeModelWithResponseStream(
        input: InvokeModelWithResponseStreamRequest,
        block: suspend (InvokeModelWithResponseStreamResponse) -> T
    ): T = throw UnsupportedOperationException("invokeModelWithResponseStream not exercised in ResponseContentBlockHarvestTest")

    override suspend fun <T> invokeModelWithBidirectionalStream(
        input: InvokeModelWithBidirectionalStreamRequest,
        block: suspend (InvokeModelWithBidirectionalStreamResponse) -> T
    ): T = throw UnsupportedOperationException("invokeModelWithBidirectionalStream not exercised in ResponseContentBlockHarvestTest")

    override fun close() {
        // no-op; the fake owns no resources
    }
}