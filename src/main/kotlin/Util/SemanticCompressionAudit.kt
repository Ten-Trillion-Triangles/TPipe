package com.TTT.Util

private val AUDIT_TOKEN_PATTERN = Regex("[A-Za-z0-9]+")
private val AUDIT_LEGEND_CODE_PATTERN = Regex("^[0-9A-Z]{2}$")

/**
 * A single phrase or token candidate surfaced by the semantic-compression audit.
 *
 * @param phrase The recurring residual phrase or token.
 * @param count The number of times the candidate appeared across the audited corpus.
 */
data class SemanticCompressionAuditEntry(
    val phrase: String,
    val count: Int
)

/**
 * A deterministic audit report for a corpus of semantic-compression samples.
 *
 * The report is intended to help tune the compressor lexicon by surfacing residual phrases and tokens that
 * continue to survive compression in repeated prompts.
 *
 * @param inputCount The number of sample strings that were audited.
 * @param rawTokenCount The total token count across the raw inputs.
 * @param compressedTokenCount The total token count across the compressed outputs.
 * @param tokenSavings The net token savings observed across the corpus.
 * @param residualPhrases The most common recurring residual phrases found in the compressed outputs.
 * @param residualTokens The most common recurring residual tokens found in the compressed outputs.
 */
data class SemanticCompressionAuditReport(
    val inputCount: Int,
    val rawTokenCount: Int,
    val compressedTokenCount: Int,
    val tokenSavings: Int,
    val residualPhrases: List<SemanticCompressionAuditEntry>,
    val residualTokens: List<SemanticCompressionAuditEntry>
)

/**
 * Audits a corpus of prompt samples for semantic-compression gaps.
 *
 * The report surfaces recurring residual phrases and tokens that still survive compression so the lexicon can
 * be expanded in a deterministic, reviewable way. This is a tuning aid, not a rewrite engine.
 *
 * @param inputs Sample prompts or prompt fragments to audit.
 * @param settings Compression settings to use while generating the compressed side of the audit.
 * @param minPhraseLength Minimum phrase length, in tokens, to consider for residual phrase suggestions.
 * @param maxPhraseLength Maximum phrase length, in tokens, to consider for residual phrase suggestions.
 * @param topK Maximum number of residual phrases and residual tokens to return.
 *
 * @return A corpus audit report with token-savings metadata and candidate residual phrases/tokens.
 */
fun auditSemanticCompressionCorpus(
    inputs: Collection<String>,
    settings: SemanticCompressionSettings = SemanticCompressionSettings(),
    minPhraseLength: Int = 2,
    maxPhraseLength: Int = 5,
    topK: Int = 25
): SemanticCompressionAuditReport
{
    if(inputs.isEmpty())
    {
        return SemanticCompressionAuditReport(
            inputCount = 0,
            rawTokenCount = 0,
            compressedTokenCount = 0,
            tokenSavings = 0,
            residualPhrases = emptyList(),
            residualTokens = emptyList()
        )
    }

    val normalizedMinPhraseLength = minPhraseLength.coerceAtLeast(2)
    val normalizedMaxPhraseLength = maxPhraseLength.coerceAtLeast(normalizedMinPhraseLength)
    val phraseCounts = linkedMapOf<String, Int>()
    val tokenCounts = linkedMapOf<String, Int>()
    var rawTokenCount = 0
    var compressedTokenCount = 0

    inputs.forEach { input ->
        rawTokenCount += countAuditTokens(input)

        val compressed = semanticCompress(input, settings)
        compressedTokenCount += countAuditTokens(compressed.compressedText)

        val tokens = AUDIT_TOKEN_PATTERN.findAll(compressed.compressedText)
            .map { it.value.lowercase() }
            .filter { it.isNotBlank() }
            .toList()

        collectResidualTokens(tokens, tokenCounts)
        collectResidualPhrases(tokens, normalizedMinPhraseLength, normalizedMaxPhraseLength, phraseCounts)
    }

    val residualPhrases = phraseCounts.entries
        .asSequence()
        .filter { (phrase, count) ->
            count >= 2 && phrase.any { it.isLetterOrDigit() } && !phrase.allLegendCodes()
        }
        .sortedWith(
            compareByDescending<Map.Entry<String, Int>> { it.value }
                .thenByDescending { it.key.length }
                .thenBy { it.key }
        )
        .take(topK)
        .map { SemanticCompressionAuditEntry(it.key, it.value) }
        .toList()

    val residualTokens = tokenCounts.entries
        .asSequence()
        .filter { (token, count) ->
            count >= 2 && token.length > 2 && !token.allLegendCodes()
        }
        .sortedWith(
            compareByDescending<Map.Entry<String, Int>> { it.value }
                .thenByDescending { it.key.length }
                .thenBy { it.key }
        )
        .take(topK)
        .map { SemanticCompressionAuditEntry(it.key, it.value) }
        .toList()

    return SemanticCompressionAuditReport(
        inputCount = inputs.size,
        rawTokenCount = rawTokenCount,
        compressedTokenCount = compressedTokenCount,
        tokenSavings = rawTokenCount - compressedTokenCount,
        residualPhrases = residualPhrases,
        residualTokens = residualTokens
    )
}

private fun countAuditTokens(text: String): Int
{
    return AUDIT_TOKEN_PATTERN.findAll(text).count()
}

private fun collectResidualTokens(
    tokens: List<String>,
    counts: MutableMap<String, Int>
)
{
    tokens.forEach { token ->
        if(token.allLegendCodes())
        {
            return@forEach
        }

        counts[token] = (counts[token] ?: 0) + 1
    }
}

private fun collectResidualPhrases(
    tokens: List<String>,
    minPhraseLength: Int,
    maxPhraseLength: Int,
    counts: MutableMap<String, Int>
)
{
    if(tokens.size < minPhraseLength)
    {
        return
    }

    for(startIndex in tokens.indices)
    {
        for(phraseLength in minPhraseLength..maxPhraseLength)
        {
            val endIndex = startIndex + phraseLength
            if(endIndex > tokens.size)
            {
                break
            }

            val phraseTokens = tokens.subList(startIndex, endIndex)
            if(phraseTokens.allLegendCodes())
            {
                continue
            }

            val phrase = phraseTokens.joinToString(" ")
            counts[phrase] = (counts[phrase] ?: 0) + 1
        }
    }
}

private fun List<String>.allLegendCodes(): Boolean
{
    return isNotEmpty() && all { token -> AUDIT_LEGEND_CODE_PATTERN.matches(token) }
}

private fun String.allLegendCodes(): Boolean
{
    return if(isBlank())
    {
        false
    }
    else
    {
        split(" ").allLegendCodes()
    }
}
