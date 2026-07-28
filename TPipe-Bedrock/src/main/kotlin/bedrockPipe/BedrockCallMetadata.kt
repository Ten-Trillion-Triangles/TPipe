package bedrockPipe

import aws.sdk.kotlin.services.bedrockruntime.model.Citation
import aws.sdk.kotlin.services.bedrockruntime.model.GuardrailAssessment
import aws.sdk.kotlin.services.bedrockruntime.model.ServiceTierType
import aws.sdk.kotlin.services.bedrockruntime.model.ToolUseBlock

/**
 * Per-call metadata harvested from a Bedrock response.
 *
 * Lives on [BedrockPipe] via [BedrockPipe.getLastCallMetadata] so that
 * callers can inspect the wire-level response details WITHOUT polluting
 * [com.TTT.Pipe.MultimodalContent]. Each field is nullable or empty-by-default
 * so a call that did not produce a given artifact (e.g. no citations, no
 * guardrail) round-trips cleanly.
 *
 * @property latencyMs Per-call server-side latency from ConverseMetrics. Null if unavailable.
 * @property toolUse Tool use blocks returned by the model (from ContentBlock.ToolUse).
 * @property citations Citation blocks returned by the model (from ContentBlock.CitationsContent).
 * @property guardAssessments Guardrail assessments returned inline in the response (from ContentBlock.GuardContent).
 * @property cacheReadInputTokens Tokens read from prompt cache, if prompt caching was active.
 * @property cacheWriteInputTokens Tokens written to prompt cache, if prompt caching was active.
 * @property serviceTier The service tier that served the call (echoed from request, useful for cost attribution).
 * @property stopReason Stop reason from the model: end_turn, max_tokens, tool_use, etc.
 */
data class BedrockCallMetadata(
    val latencyMs: Long? = null,
    val toolUse: List<ToolUseBlock> = emptyList(),
    val citations: List<Citation> = emptyList(),
    val guardAssessments: List<GuardrailAssessment> = emptyList(),
    val cacheReadInputTokens: Long? = null,
    val cacheWriteInputTokens: Long? = null,
    val serviceTier: ServiceTierType? = null,
    val stopReason: String? = null
)