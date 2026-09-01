package codexPipe.model

/** A service tier advertised by the Codex model catalog. */
data class CodexServiceTier(
    val id: String,
    val name: String? = null,
    val description: String? = null,
)

/**
 * Normalized model-picker metadata returned by the Codex `/models` endpoint.
 * Unknown upstream fields are deliberately ignored by the parser.
 */
data class CodexModelInfo(
    val slug: String,
    val displayName: String? = null,
    val description: String? = null,
    val visibility: String? = null,
    val priority: Int? = null,
    val defaultReasoningLevel: String? = null,
    val supportedReasoningLevels: List<String> = emptyList(),
    val contextWindow: Long? = null,
    val maxContextWindow: Long? = null,
    val effectiveContextWindowPercent: Double? = null,
    val inputModalities: List<String> = emptyList(),
    val supportsVerbosity: Boolean? = null,
    val serviceTiers: List<CodexServiceTier> = emptyList(),
    val defaultServiceTier: String? = null,
)

/** Catalog result plus metadata needed for cache and conditional diagnostics. */
data class CodexModelCatalog(
    val models: List<CodexModelInfo>,
    val etag: String? = null,
    val fetchedAtMillis: Long,
)
