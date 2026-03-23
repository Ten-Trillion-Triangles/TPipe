package com.TTT.Util

import java.text.Normalizer

/**
 * Configuration for semantic prompt compression.
 *
 * The compressor is intentionally deterministic and dependency-free. Callers can extend the built-in
 * phrase removal list or stop-word list, but the default behavior is designed to work out of the box
 * for general natural-language prompts.
 *
 * @param maxLegendEntries Maximum number of proper-noun legend entries to emit. Additional repeated
 * proper nouns are left untouched once this ceiling is reached so the legend never lies.
 * @param additionalCommonPhrases Extra phrases to strip in addition to the built-in phrase table.
 * @param additionalStopWords Extra function words or discourse fillers to remove in addition to the
 * built-in stop-word table.
 */
data class SemanticCompressionSettings(
    val maxLegendEntries: Int = 48,
    val additionalCommonPhrases: List<String> = emptyList(),
    val additionalStopWords: Set<String> = emptySet()
)

/**
 * Result of semantic prompt compression.
 *
 * @param compressedText The compressed prompt body, excluding the legend.
 * @param legend The human-readable legend that maps short codes back to the original repeated proper nouns.
 * @param legendMap The machine-readable legend mapping where keys are the short codes and values are the original
 * proper noun phrases.
 */
data class SemanticCompressionResult(
    val compressedText: String,
    val legend: String,
    val legendMap: Map<String, String>
)

/**
 * Builds the system-prompt prelude that teaches the model how to read a semantic-compressed prompt.
 *
 * This prelude is intentionally generic and stable so it can be prepended ahead of any other system prompt
 * injection. It tells the model that the user prompt was compressed by TPipe Semantic Compression, explains
 * that the goal is near-lossless semantic restoration of the original intent and data, and gives an exact
 * decompression order before the rest of the system prompt continues.
 *
 * @return A system-prompt prelude that describes how to decompress semantic-compressed text.
 */
fun buildSemanticDecompressionInstructions(): String
{
    return """
        |The user prompt has been compressed using TPipe Semantic Compression.
        |This is a TPipe-specific, legend-backed prompt reduction format, so do not assume prior knowledge of it.
        |The compressed text is a loss-minimized encoding of the original prompt, and your job is to reconstruct the original text as completely and faithfully as possible.
        |The output should read like normal human English again, not like a compressed note, fragment list, or summary.
        |Some surface words may differ from the original text, but the original intent, meaning, data, and contents
        |must be restored as closely as possible.
        |The compression removed common function words, common phrases, Unicode characters, and most punctuation
        |outside quoted text, while repeated proper nouns were replaced with short codes that must be expanded back.
        |Use inference to restore omitted articles, conjunctions, prepositions, auxiliaries, and punctuation so the
        |final reconstruction matches the original meaning, structure, and wording as closely as possible.
        |Do not leave the text compressed, and do not preserve the compressed style in the final output.
        |
        |Do this first before following any other instructions:
        |
        |1. Read the legend at the top of the prompt first.
        |2. Treat the legend as a decoding table, not as prose to summarize.
        |3. The legend begins with `Legend:` and continues with `code: phrase` lines.
        |4. The legend block ends at the first blank line after those mapping lines.
        |5. Build a map from each 2-character ASCII code to the phrase on the same line.
        |6. Read the compressed body after the legend block.
        |7. Replace matching codes in the compressed body using the legend map.
        |8. Replace codes only in unquoted text.
        |9. Leave quoted text exactly as written.
        |10. Restore the prompt as faithfully as possible, not as a summary.
        |11. Do not invent unrelated meaning or rewrite quoted spans.
        |12. After the compressed prompt has been reconstructed, continue with the rest of the system prompt
        |    using the reconstructed text as the basis for any remaining instructions.
        |
        |The goal is to recover the original text's intent and content as closely as possible before any other
        |system instructions are followed.
    """.trimMargin()
}

private data class ProperNounCandidate(
    val phrase: String,
    var count: Int = 0,
    val firstIndex: Int
)

private data class QuoteSpan(
    val placeholder: String,
    val content: String
)

private val DEFAULT_STOP_WORDS = setOf(
    "a",
    "about",
    "after",
    "again",
    "against",
    "all",
    "almost",
    "along",
    "also",
    "although",
    "always",
    "among",
    "an",
    "and",
    "any",
    "are",
    "around",
    "as",
    "at",
    "be",
    "because",
    "been",
    "before",
    "being",
    "below",
    "between",
    "both",
    "but",
    "by",
    "can",
    "could",
    "did",
    "do",
    "does",
    "doing",
    "down",
    "during",
    "each",
    "either",
    "enough",
    "every",
    "few",
    "for",
    "from",
    "further",
    "get",
    "got",
    "had",
    "has",
    "have",
    "having",
    "he",
    "her",
    "here",
    "hers",
    "him",
    "himself",
    "his",
    "how",
    "i",
    "if",
    "in",
    "into",
    "is",
    "it",
    "its",
    "just",
    "kind",
    "less",
    "like",
    "many",
    "may",
    "me",
    "more",
    "most",
    "much",
    "must",
    "my",
    "myself",
    "no",
    "nor",
    "not",
    "of",
    "off",
    "on",
    "once",
    "one",
    "only",
    "or",
    "other",
    "our",
    "ours",
    "out",
    "over",
    "own",
    "perhaps",
    "please",
    "really",
    "same",
    "she",
    "should",
    "so",
    "some",
    "still",
    "such",
    "than",
    "that",
    "the",
    "their",
    "theirs",
    "them",
    "themselves",
    "then",
    "there",
    "these",
    "they",
    "this",
    "those",
    "through",
    "to",
    "too",
    "under",
    "until",
    "up",
    "very",
    "was",
    "we",
    "were",
    "what",
    "when",
    "where",
    "which",
    "while",
    "who",
    "whom",
    "why",
    "will",
    "with",
    "within",
    "without",
    "would",
    "you",
    "your",
    "yours",
    "yourself",
    "yourselves",
    "actually",
    "basically",
    "clearly",
    "definitely",
    "exactly",
    "factually",
    "generally",
    "honestly",
    "literally",
    "maybe",
    "obviously",
    "okay",
    "ok",
    "quite",
    "rather",
    "simply",
    "somewhat",
    "basically",
    "yeah",
    "yes",
    "nope",
    "nah",
    "well",
    "let",
    "lets"
)

private val DEFAULT_COMMON_PHRASES = listOf(
    "in order to",
    "at the end of the day",
    "as a matter of fact",
    "in fact",
    "for the purpose of",
    "due to the fact that",
    "in the event that",
    "with respect to",
    "in addition to",
    "as well as",
    "in spite of",
    "because of",
    "in regard to",
    "in relation to",
    "in the case of",
    "by means of",
    "based on the fact that",
    "for example",
    "for instance",
    "for the most part",
    "in other words",
    "on the other hand",
    "at this point in time",
    "as soon as possible",
    "if at all possible",
    "in the process of",
    "taking into account",
    "in light of",
    "with the exception of",
    "as a result of",
    "in comparison with",
    "in order that",
    "in accordance with",
    "in terms of",
    "in the interest of",
    "for the sake of",
    "kind of",
    "sort of",
    "you know",
    "in the same way",
    "in a way",
    "in summary",
    "to be honest",
    "to put it simply",
    "if possible",
    "as needed",
    "as required",
    "as appropriate",
    "as requested",
    "more or less",
    "the fact that",
    "the reason why",
    "in some cases",
    "at least",
    "at most"
)

private val PROPER_NOUN_CONNECTORS = setOf(
    "of",
    "de",
    "van",
    "von",
    "la",
    "le",
    "di",
    "da",
    "del",
    "du",
    "dos",
    "das",
    "al",
    "bin",
    "binti",
    "ben",
    "y",
    "e",
)

private val COMMON_CAPITALIZED_SENTENCE_WORDS = setOf(
    "Please",
    "Thanks",
    "Thank",
    "Yes",
    "No",
    "Well",
    "Okay",
    "Ok",
    "Maybe",
    "Actually",
    "Basically",
    "Really",
    "Simply",
    "Literally"
)

private val TOKEN_PATTERN = Regex("[A-Za-z0-9]+")

private object SemanticCompressionLexicon
{
    private const val RESOURCE_ROOT = "/semantic-compression"
    private val STOP_WORD_RESOURCE_FILES = listOf(
        "stopwords-en.txt",
        "stopwords-extra-en.txt",
        "stopwords-note-ext.txt"
    )
    private val COMMON_PHRASE_RESOURCE_FILES = listOf(
        "common-phrases-en.txt",
        "common-phrases-extra-en.txt",
        "common-phrases-note-ext.txt"
    )

    val stopWords: Set<String> by lazy {
        STOP_WORD_RESOURCE_FILES
            .flatMap { loadWordList(it) }
            .map { it.lowercase() }
            .toSet()
    }

    val commonPhrases: List<String> by lazy {
        COMMON_PHRASE_RESOURCE_FILES
            .flatMap { loadWordList(it) }
            .distinct()
    }

    val properNounConnectors: Set<String> by lazy {
        loadWordSet("proper-noun-connectors.txt")
    }

    val commonCapitalizedSentenceWords: Set<String> by lazy {
        loadWordSet("capitalized-sentence-words.txt")
    }

    val contractions: Map<String, String> by lazy {
        loadKeyValueMap("contractions-en.txt")
    }

    fun buildPhrasePattern(phrases: Collection<String>): Regex?
    {
        val normalizedPhrases = phrases
            .map { normalizePhrase(it) }
            .filter { it.isNotBlank() }
            .distinct()
            .sortedWith(
                compareByDescending<String> { phraseTokenCount(it) }
                    .thenByDescending { it.length }
            )

        if(normalizedPhrases.isEmpty())
        {
            return null
        }

        val alternation = normalizedPhrases.joinToString("|") { Regex.escape(it) }
        return Regex("\\b(?:$alternation)\\b", RegexOption.IGNORE_CASE)
    }

    private fun loadWordList(resourceName: String): List<String>
    {
        return loadResourceLines(resourceName)
            .map { normalizePhrase(it) }
            .filter { it.isNotBlank() }
    }

    private fun loadWordSet(resourceName: String): Set<String>
    {
        return loadWordList(resourceName)
            .map { it.lowercase() }
            .toSet()
    }

    private fun loadKeyValueMap(resourceName: String): Map<String, String>
    {
        return loadResourceLines(resourceName)
            .mapNotNull { entry ->
                val separatorIndex = entry.indexOf('=')
                if(separatorIndex <= 0 || separatorIndex >= entry.lastIndex)
                {
                    return@mapNotNull null
                }

                val key = entry.substring(0, separatorIndex).trim().lowercase()
                val value = entry.substring(separatorIndex + 1).trim()

                if(key.isBlank() || value.isBlank())
                {
                    return@mapNotNull null
                }

                key to value
            }
            .toMap()
    }

    private fun loadResourceLines(resourceName: String): List<String>
    {
        val resourcePath = "$RESOURCE_ROOT/$resourceName"
        val stream = SemanticCompressionLexicon::class.java.getResourceAsStream(resourcePath)
            ?: return emptyList()

        return stream.bufferedReader().useLines { lines ->
            lines.map { it.trim() }
                .filter { it.isNotBlank() }
                .filterNot { it.startsWith("#") }
                .toList()
        }
    }
}

/**
 * Compresses natural-language prompt text using deterministic semantic stripping and legend generation.
 *
 * Quoted spans are preserved verbatim and are not compressed. Unquoted text is normalized to ASCII, function
 * words and common phrases are stripped, repeated proper nouns are replaced with 2-character codes in AA/AB/AC
 * order, and punctuation is reduced to the smallest useful surface form.
 *
 * @param input Prompt text to compress.
 * @param settings Optional compression settings controlling legend size and extension phrase/stop-word tables.
 *
 * @return The compressed prompt body and the legend needed to decode repeated proper noun codes.
 */
fun semanticCompress(
    input: String,
    settings: SemanticCompressionSettings = SemanticCompressionSettings()
): SemanticCompressionResult
{
    if(input.isEmpty())
    {
        return SemanticCompressionResult("", "", emptyMap())
    }

    val quoteSpans = mutableListOf<QuoteSpan>()
    val maskedInput = maskQuotedSpans(input, quoteSpans)
    val asciiMaskedInput = normalizeAscii(maskedInput)
    val expandedContractions = expandContractions(
        asciiMaskedInput,
        SemanticCompressionLexicon.contractions
    )

    val stopWords = DEFAULT_STOP_WORDS +
        SemanticCompressionLexicon.stopWords +
        settings.additionalStopWords
    val allPhrases = DEFAULT_COMMON_PHRASES +
        SemanticCompressionLexicon.commonPhrases +
        settings.additionalCommonPhrases
    val properNounCandidates = collectProperNounCandidates(
        expandedContractions,
        stopWords,
        PROPER_NOUN_CONNECTORS + SemanticCompressionLexicon.properNounConnectors,
        COMMON_CAPITALIZED_SENTENCE_WORDS + SemanticCompressionLexicon.commonCapitalizedSentenceWords
    )
    val selectedCandidates = properNounCandidates
        .filter { candidate ->
            candidate.count >= properNounReplacementThreshold(candidate.phrase)
        }
        .take(settings.maxLegendEntries.coerceIn(0, 676))

    val phraseToCode = selectedCandidates
        .mapIndexed { index, candidate ->
            candidate.phrase to toLegendCode(index)
        }
        .toMap()

    var compressed = expandedContractions
    compressed = replaceProperNouns(compressed, phraseToCode)
    compressed = removeCommonPhrases(
        compressed,
        SemanticCompressionLexicon.buildPhrasePattern(allPhrases)
    )
    compressed = removeStopWords(
        compressed,
        stopWords
    )
    compressed = collapseWhitespace(removePunctuation(compressed))

    val restored = restoreQuotedSpans(compressed, quoteSpans)
    val legendMap = phraseToCode.entries.associate { (phrase, code) ->
        code to phrase
    }
    val legendText = buildLegendText(legendMap)

    return SemanticCompressionResult(
        compressedText = restored,
        legend = legendText,
        legendMap = legendMap
    )
}

private fun maskQuotedSpans(
    input: String,
    quoteSpans: MutableList<QuoteSpan>
): String
{
    val builder = StringBuilder()
    val currentQuote = StringBuilder()
    var inQuote = false

    fun flushQuote()
    {
        if(currentQuote.isNotEmpty())
        {
            val placeholder = buildPlaceholder(quoteSpans.size, input)
            quoteSpans.add(QuoteSpan(placeholder, currentQuote.toString()))
            builder.append(placeholder)
            currentQuote.clear()
        }
    }

    for(character in input)
    {
        if(character == '"')
        {
            currentQuote.append(character)

            if(inQuote)
            {
                flushQuote()
            }

            inQuote = !inQuote
            continue
        }

        if(inQuote)
        {
            currentQuote.append(character)
        }
        else
        {
            builder.append(character)
        }
    }

    if(currentQuote.isNotEmpty())
    {
        val placeholder = buildPlaceholder(quoteSpans.size, input)
        quoteSpans.add(QuoteSpan(placeholder, currentQuote.toString()))
        builder.append(placeholder)
    }

    return builder.toString()
}

private fun buildPlaceholder(
    index: Int,
    input: String
): String
{
    var attempt = 0

    while(true)
    {
        val candidate = "qx${index.toString(36)}${attempt.toString(36)}qx"
        if(!input.contains(candidate))
        {
            return candidate
        }

        attempt++
    }
}

private fun restoreQuotedSpans(
    input: String,
    quoteSpans: List<QuoteSpan>
): String
{
    var result = input

    for(span in quoteSpans)
    {
        result = result.replace(span.placeholder, span.content)
    }

    return result
}

private fun normalizeAscii(input: String): String
{
    val transliterated = Normalizer.normalize(input, Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")
        .replace("ß", "ss")
        .replace("Æ", "AE")
        .replace("æ", "ae")
        .replace("Œ", "OE")
        .replace("œ", "oe")
        .replace("Ø", "O")
        .replace("ø", "o")
        .replace("Ð", "D")
        .replace("ð", "d")
        .replace("Þ", "Th")
        .replace("þ", "th")
        .replace("Ł", "L")
        .replace("ł", "l")

    return transliterated
        .replace("“", "\"")
        .replace("”", "\"")
        .replace("‘", "'")
        .replace("’", "'")
        .replace("—", " ")
        .replace("–", " ")
        .replace("…", " ")
        .replace(Regex("[^\\x00-\\x7F]"), " ")
}

private fun removePunctuation(input: String): String
{
    return input.replace(Regex("[^A-Za-z0-9:\\s]"), " ")
}

private fun collapseWhitespace(input: String): String
{
    return input.replace(Regex("\\s+"), " ").trim()
}

private fun removeStopWords(
    input: String,
    stopWords: Set<String>
): String
{
    return TOKEN_PATTERN.replace(input) { match ->
        if(match.value.lowercase() in stopWords)
        {
            " "
        }
        else
        {
            match.value
        }
    }
}

private fun removeCommonPhrases(
    input: String,
    phrasePattern: Regex?
): String
{
    if(phrasePattern == null)
    {
        return input
    }

    return phrasePattern.replace(input, " ")
}

private fun normalizePhrase(phrase: String): String
{
    return collapseWhitespace(removePunctuation(normalizeAscii(phrase)))
}

private fun expandContractions(
    input: String,
    contractions: Map<String, String>
): String
{
    if(contractions.isEmpty())
    {
        return input
    }

    var result = input
    val keys = contractions.keys.sortedByDescending { it.length }

    for(key in keys)
    {
        val replacement = contractions[key] ?: continue
        val pattern = Regex("\\b${Regex.escape(key)}\\b", RegexOption.IGNORE_CASE)
        result = pattern.replace(result, replacement)
    }

    return result
}

private fun phraseTokenCount(phrase: String): Int
{
    return phrase.split(" ").count { it.isNotBlank() }
}

private fun replaceProperNouns(
    input: String,
    phraseToCode: Map<String, String>
): String
{
    var result = input

    val phrases = phraseToCode.keys.sortedByDescending { it.length }
    for(phrase in phrases)
    {
        val pattern = Regex("\\b${Regex.escape(phrase)}\\b", RegexOption.IGNORE_CASE)
        result = pattern.replace(result, phraseToCode[phrase] ?: phrase)
    }

    return result
}

private fun collectProperNounCandidates(
    input: String,
    stopWords: Set<String>,
    connectors: Set<String>,
    commonCapitalizedSentenceWords: Set<String>
): List<ProperNounCandidate>
{
    val candidates = linkedMapOf<String, ProperNounCandidate>()
    val tokenMatches = TOKEN_PATTERN.findAll(input)
        .map { match -> match.value to match.range.first }
        .toList()
    val connectorSet = connectors.map { it.lowercase() }.toSet()
    var index = 0

    while(index < tokenMatches.size)
    {
        val (token, _) = tokenMatches[index]

        if(!isProperNounToken(token, connectorSet))
        {
            index++
            continue
        }

        val runTokens = mutableListOf<Pair<String, Int>>()

        while(index < tokenMatches.size && isProperNounToken(tokenMatches[index].first, connectorSet))
        {
            runTokens.add(tokenMatches[index])
            index++
        }

        collectProperNounRunCandidates(
            runTokens = trimConnectorEdges(runTokens, connectorSet),
            candidates = candidates,
            stopWords = stopWords,
            commonCapitalizedSentenceWords = commonCapitalizedSentenceWords,
            connectorSet = connectorSet
        )
    }

    return candidates.values
        .filter { candidate ->
            val phrase = candidate.phrase
            val words = phrase.split(" ").filter { it.isNotBlank() }
            val repeatedEnough = candidate.count >= properNounReplacementThreshold(phrase)
            val looksLikeName = words.size > 1 ||
                words.any { it.firstOrNull()?.isUpperCase() == true } ||
                words.any { it.any { char -> char.isDigit() } } ||
                phrase !in commonCapitalizedSentenceWords

            repeatedEnough && looksLikeName
        }
        .sortedWith(
            compareByDescending<ProperNounCandidate> { it.count }
                .thenBy { it.firstIndex }
                .thenByDescending { it.phrase.length }
        )
}

private fun collectProperNounRunCandidates(
    runTokens: List<Pair<String, Int>>,
    candidates: MutableMap<String, ProperNounCandidate>,
    stopWords: Set<String>,
    commonCapitalizedSentenceWords: Set<String>,
    connectorSet: Set<String>
)
{
    if(runTokens.size < 2)
    {
        return
    }

    var cursor = 0

    while(cursor < runTokens.size)
    {
        val remaining = trimConnectorEdges(runTokens.drop(cursor), connectorSet)

        if(remaining.size < 2)
        {
            return
        }

        val repeatedPrefix = findRepeatedPrefix(remaining)

        if(repeatedPrefix != null)
        {
            val (unitSize, repeatCount) = repeatedPrefix
            if(unitSize <= 1)
            {
                return
            }

            registerProperNounCandidate(
                unitTokens = remaining.take(unitSize),
                repeatCount = repeatCount,
                candidates = candidates,
                stopWords = stopWords,
                commonCapitalizedSentenceWords = commonCapitalizedSentenceWords
            )
            cursor += unitSize * repeatCount
            continue
        }

        registerProperNounCandidate(
            unitTokens = remaining,
            repeatCount = 1,
            candidates = candidates,
            stopWords = stopWords,
            commonCapitalizedSentenceWords = commonCapitalizedSentenceWords
        )
        return
    }
}

private fun registerProperNounCandidate(
    unitTokens: List<Pair<String, Int>>,
    repeatCount: Int,
    candidates: MutableMap<String, ProperNounCandidate>,
    stopWords: Set<String>,
    commonCapitalizedSentenceWords: Set<String>
)
{
    if(unitTokens.size < 2)
    {
        return
    }

    val phrase = collapseWhitespace(unitTokens.joinToString(" ") { it.first })
    val lowerPhrase = phrase.lowercase()

    if(phrase.isBlank() || phrase.length < 2)
    {
        return
    }

    if(lowerPhrase in stopWords)
    {
        return
    }

    if(phrase in commonCapitalizedSentenceWords)
    {
        return
    }

    val count = repeatCount.coerceAtLeast(1)
    val firstIndex = unitTokens.first().second

    candidates[phrase] = candidates[phrase]?.let { existing ->
        existing.count += count
        existing
    } ?: ProperNounCandidate(
        phrase = phrase,
        count = count,
        firstIndex = firstIndex
    )
}

private fun trimConnectorEdges(
    tokens: List<Pair<String, Int>>,
    connectorSet: Set<String>
): List<Pair<String, Int>>
{
    var start = 0
    var end = tokens.size

    while(start < end && tokens[start].first.lowercase() in connectorSet)
    {
        start++
    }

    while(end > start && tokens[end - 1].first.lowercase() in connectorSet)
    {
        end--
    }

    return tokens.subList(start, end)
}

private fun findRepeatedPrefix(
    tokens: List<Pair<String, Int>>
): Pair<Int, Int>?
{
    val values = tokens.map { it.first }

    for(unitSize in 2..(values.size / 2))
    {
        if(values.size < unitSize * 2)
        {
            continue
        }

        val unit = values.subList(0, unitSize)
        var repeatCount = 1

        while(repeatCount * unitSize + unitSize <= values.size &&
            values.subList(repeatCount * unitSize, repeatCount * unitSize + unitSize) == unit)
        {
            repeatCount++
        }

        if(repeatCount >= 2)
        {
            return unitSize to repeatCount
        }
    }

    return null
}

private fun isProperNounToken(
    token: String,
    connectorSet: Set<String>
): Boolean
{
    val lower = token.lowercase()
    return lower in connectorSet ||
        token.firstOrNull()?.isUpperCase() == true ||
        token.any { it.isDigit() }
}

private fun toLegendCode(index: Int): String
{
    val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
    val base = alphabet.length
    require(index in 0 until base * base)
    val first = alphabet[index / base]
    val second = alphabet[index % base]
    return "$first$second"
}

private fun buildLegendText(legendMap: Map<String, String>): String
{
    if(legendMap.isEmpty())
    {
        return ""
    }

    return buildString {
        append("Legend:\n")
        legendMap.forEach { (code, phrase) ->
            append(code)
            append(": ")
            append(phrase)
            append('\n')
        }
    }.trimEnd()
}

private fun properNounReplacementThreshold(phrase: String): Int
{
    val tokenCount = phrase.split(" ").count { it.isNotBlank() }
    return when
    {
        tokenCount <= 1 -> Int.MAX_VALUE
        tokenCount == 2 -> 6
        tokenCount == 3 -> 4
        tokenCount in 4..5 -> 3
        else -> 2
    }
}

internal fun semanticCompressionStopWords(): Set<String>
{
    return DEFAULT_STOP_WORDS + SemanticCompressionLexicon.stopWords
}

internal fun semanticCompressionCommonPhrases(): Set<String>
{
    return (DEFAULT_COMMON_PHRASES + SemanticCompressionLexicon.commonPhrases).toSet()
}
