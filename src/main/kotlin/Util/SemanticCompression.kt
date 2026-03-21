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
    "the",
    "and",
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
    "&"
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

/**
 * Compresses natural-language prompt text using deterministic semantic stripping and legend generation.
 *
 * Quoted spans are preserved verbatim and are not compressed. Unquoted text is normalized to ASCII, function
 * words and common phrases are stripped, repeated proper nouns are replaced with 2-character codes, and
 * punctuation is reduced to the smallest useful surface form.
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

    val stopWords = DEFAULT_STOP_WORDS + settings.additionalStopWords
    val properNounCandidates = collectProperNounCandidates(
        asciiMaskedInput,
        stopWords
    )
    val selectedCandidates = properNounCandidates
        .filter { it.count >= 2 }
        .take(settings.maxLegendEntries)

    val phraseToCode = selectedCandidates
        .mapIndexed { index, candidate ->
            candidate.phrase to toLegendCode(index)
        }
        .toMap()

    var compressed = asciiMaskedInput
    compressed = replaceProperNouns(compressed, phraseToCode)
    compressed = removeCommonPhrases(
        compressed,
        DEFAULT_COMMON_PHRASES + settings.additionalCommonPhrases
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
    phrases: List<String>
): String
{
    var result = input

    val normalizedPhrases = phrases
        .map { normalizePhrase(it) }
        .filter { it.isNotBlank() }
        .distinct()
        .sortedByDescending { phraseTokenCount(it) }

    for(phrase in normalizedPhrases)
    {
        val pattern = Regex("\\b${Regex.escape(phrase)}\\b", RegexOption.IGNORE_CASE)
        result = pattern.replace(result, " ")
    }

    return result
}

private fun normalizePhrase(phrase: String): String
{
    return collapseWhitespace(removePunctuation(normalizeAscii(phrase)))
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
    stopWords: Set<String>
): List<ProperNounCandidate>
{
    val candidates = linkedMapOf<String, ProperNounCandidate>()
    val regex = Regex(
        "\\b(?:[A-Z][A-Za-z0-9]*|[A-Z]{2,}|[0-9]+)(?:\\s+(?:of|the|and|de|van|von|la|le|di|da|del|du|dos|das|al|bin|binti|ben|[A-Z][A-Za-z0-9]*|[A-Z]{2,}|[0-9]+))*\\b"
    )

    regex.findAll(input).forEach { match ->
        val phrase = collapseWhitespace(match.value)
        val lowerPhrase = phrase.lowercase()

        if(phrase.isBlank())
        {
            return@forEach
        }

        if(phrase.length < 2)
        {
            return@forEach
        }

        if(lowerPhrase in stopWords)
        {
            return@forEach
        }

        if(phrase !in candidates)
        {
            candidates[phrase] = ProperNounCandidate(
                phrase = phrase,
                count = 0,
                firstIndex = match.range.first
            )
        }

        candidates[phrase]?.count = candidates[phrase]?.count?.plus(1) ?: 1
    }

    return candidates.values
        .filter { candidate ->
            val phrase = candidate.phrase
            val words = phrase.split(" ").filter { it.isNotBlank() }
            val repeatedEnough = candidate.count >= 2
            val looksLikeName = words.size > 1 ||
                words.any { it.firstOrNull()?.isUpperCase() == true } ||
                words.any { it.any { char -> char.isDigit() } } ||
                phrase !in COMMON_CAPITALIZED_SENTENCE_WORDS

            repeatedEnough && looksLikeName
        }
        .sortedWith(
            compareByDescending<ProperNounCandidate> { it.count }
                .thenBy { it.firstIndex }
                .thenByDescending { it.phrase.length }
        )
}

private fun toLegendCode(index: Int): String
{
    val alphabet = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ"
    val base = alphabet.length
    val first = alphabet[(index / base) % base]
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
