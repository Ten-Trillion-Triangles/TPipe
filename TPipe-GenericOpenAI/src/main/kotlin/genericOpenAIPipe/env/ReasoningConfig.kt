package genericOpenAIPipe.env

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Reasoning model configuration (e.g., for models supporting reasoning).
 *
 * @property effort Reasoning effort: "xhigh", "high", "medium", "low", "minimal", "none"
 * @property maxTokens Maximum tokens to use for reasoning
 * @property exclude Whether to exclude reasoning tokens from response
 * @property enabled Whether reasoning is enabled
 */
@Serializable
data class ReasoningConfig(
    val effort: String? = null,
    @SerialName("max_tokens")
    val maxTokens: Int? = null,
    val exclude: Boolean? = null,
    val enabled: Boolean? = null
)

/**
 * Anthropic-style cache control configuration.
 *
 * @property type Cache type (e.g., "ephemeral")
 * @property ttl Cache TTL (e.g., "5m", "1h")
 */
@Serializable
data class CacheControl(
    val type: String,
    val ttl: String? = null
)