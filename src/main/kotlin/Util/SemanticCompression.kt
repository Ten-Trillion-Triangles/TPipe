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

private const val PARAGRAPH_MARKER = "¶"
private val PARAGRAPH_BREAK_PATTERN = Regex("(?:\\r?\\n\\s*){2,}")

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
        |Paragraph breaks are represented with the pilcrow character `¶`; treat each pilcrow as a paragraph
        |boundary and restore it as a blank line between paragraphs.
        |Use inference to restore omitted articles, conjunctions, prepositions, auxiliaries, and punctuation so the
        |final reconstruction matches the original meaning, sentence structure, and wording as closely as possible.
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
        |10. Reconstruct the source sentence-by-sentence before you write the final restored content.
        |11. Treat the pilcrow character `¶` as an explicit paragraph boundary and restore a blank line there.
        |12. Restore the prompt as faithfully as possible, not as a summary.
        |13. Do not invent unrelated meaning or rewrite quoted spans.
        |14. After the compressed prompt has been reconstructed, continue with the rest of the system prompt
        |    using the reconstructed text as the basis for any remaining instructions.
        |
        |The goal is to recover the original text's intent and content as closely as possible before any other
        |system instructions are followed.
    """.trimMargin()
}

private data class ProperNounCandidate(
    val phrase: String,
    var count: Int = 0,
    val firstIndex: Int,
    var seenNotAtSentenceStart: Boolean = false
)

private data class TokenData(
    val text: String,
    val index: Int,
    val isSentenceStart: Boolean
)

private data class QuoteSpan(
    val placeholder: String,
    val content: String
)

private val DEFAULT_STOP_WORDS = setOf(
    "a", "aboard", "about", "above", "across", "after", "afterwards", "again", "against", "albeit",
    "all", "almost", "alone", "along", "already", "also", "although", "always", "am", "amid",
    "amidst", "among", "amongst", "an", "and", "another", "any", "anybody", "anyhow", "anyone",
    "anything", "anyway", "anywhere", "are", "around", "as", "at", "atop", "barring", "be",
    "because", "been", "before", "beforehand", "behind", "being", "below", "beneath", "beside", "besides",
    "between", "beyond", "both", "but", "by", "can", "cannot", "circa", "concerning", "considering",
    "could", "dare", "despite", "did", "do", "does", "done", "down", "during", "each",
    "either", "else", "elsewhere", "enough", "even", "ever", "every", "everybody", "everyone", "everything",
    "everywhere", "except", "excluding", "few", "first", "for", "former", "from", "further", "furthermore",
    "given", "had", "has", "have", "he", "hence", "her", "here", "hereabouts", "hereafter",
    "hereby", "herein", "hereinafter", "hereof", "hereto", "heretofore", "hereunder", "hereupon", "herewith", "hers",
    "herself", "him", "himself", "his", "how", "however", "i", "if", "in", "including",
    "indeed", "inside", "instead", "into", "is", "it", "its", "itself", "latter", "least",
    "less", "lest", "like", "many", "may", "maybe", "me", "meanwhile", "might", "mine",
    "minus", "more", "moreover", "most", "mostly", "much", "must", "my", "myself", "namely",
    "near", "need", "neither", "never", "nevertheless", "no", "nobody", "none", "nonetheless", "nor",
    "not", "nothing", "notwithstanding", "now", "nowhere", "of", "off", "often", "oftentimes", "on",
    "once", "one", "oneself", "only", "onto", "or", "other", "others", "otherwise", "ought",
    "our", "ours", "ourselves", "out", "outside", "over", "pending", "per", "perhaps", "plus",
    "rather", "re", "regarding", "same", "sans", "second", "several", "shall", "she", "should",
    "since", "so", "some", "somebody", "somehow", "someone", "something", "sometime", "sometimes", "somewhat",
    "somewhere", "still", "such", "than", "that", "the", "their", "theirs", "them", "themselves",
    "then", "thence", "there", "thereabouts", "thereafter", "thereby", "therefore", "therein", "thereof", "thereon",
    "thereupon", "these", "they", "third", "this", "those", "though", "through", "throughout", "thru",
    "thus", "till", "to", "together", "too", "toward", "towards", "under", "underneath", "unless",
    "unlike", "until", "up", "upon", "us", "used", "versus", "very", "via", "was",
    "we", "well", "were", "what", "whatever", "whatsoever", "when", "whence", "whenever", "where",
    "whereafter", "whereas", "whereby", "wherein", "whereof", "whereupon", "wherever", "whether", "which",
    "whichever", "while", "whither", "who", "whoever", "whole", "whom", "whomever", "whose", "why",
    "whyever", "will", "with", "within", "without", "would", "yes", "yet", "you", "your",
    "yours", "yourself", "yourselves"
)

private val PRONOUNS = setOf(
    "i", "me", "my", "mine", "myself",
    "we", "us", "our", "ours", "ourselves",
    "you", "your", "yours", "yourself", "yourselves",
    "he", "him", "his", "himself",
    "she", "her", "hers", "herself",
    "it", "its", "itself",
    "they", "them", "their", "theirs", "themselves",
    "this", "that", "these", "those",
    "who", "whom", "whose", "which", "what", "where", "when", "why", "how",
    "some", "any", "no", "every", "all", "both", "each", "few", "many", "most", "other", "somebody", "someone", "something",
    "anybody", "anyone", "anything", "nobody", "none", "nothing", "everybody", "everyone", "everything"
)

private val DEFAULT_COMMON_PHRASES = listOf(
    "a piece of cake", "break a leg", "hit the nail on the head", "cost an arm and a leg", "beat around the bush",
    "bite the bullet", "call it a day", "cut corners", "get out of hand", "hang in there",
    "in a nutshell", "it's not rocket science", "kill two birds with one stone", "let the cat out of the bag", "miss the boat",
    "no pain no gain", "on the ball", "once in a blue moon", "pull someone's leg", "rain on someone's parade",
    "speak of the devil", "the ball is in your court", "the best of both worlds", "the last straw", "through thick and thin",
    "under the weather", "wrap your head around something", "you can say that again", "a blessing in disguise", "a dime a dozen",
    "a drop in the bucket", "a fish out of water", "a slap on the wrist", "a taste of your own medicine", "add fuel to the fire",
    "against the clock", "all ears", "all in the same boat", "at the drop of a hat", "back to the drawing board",
    "bark up the wrong tree", "be glad to see the back of", "beat someone to the punch", "beggars can't be choosers", "behind someone's back",
    "below the belt", "bend over backwards", "better late than never", "bite off more than you can chew", "blow off steam",
    "break the ice", "burn the midnight oil", "burn your bridges", "bury the hatchet", "by the skin of your teeth",
    "call someone's bluff", "chomp at the bit", "clear the air", "come rain or shine", "cool as a cucumber",
    "crack someone up", "cross that bridge when you come to it", "cry over spilled milk", "curiosity killed the cat", "cut to the chase",
    "dig deep", "don't count your chickens before they hatch", "don't give up your day job", "don't put all your eggs in one basket", "down to earth",
    "drive someone up the wall", "easier said than done", "every cloud has a silver lining", "face the music", "find your feet",
    "fit as a fiddle", "get a taste of your own medicine", "get cold feet", "get something off your chest", "get your act together",
    "give someone the benefit of the doubt", "give someone the cold shoulder", "go back to the drawing board", "go down in flames", "go the extra mile",
    "good things come to those who wait", "hear it on the grapevine", "hit the sack", "hit the roof", "in the heat of the moment",
    "it takes two to tango", "jump on the bandwagon", "jump the gun", "keep an eye on", "keep your chin up",
    "kick the bucket", "kill time", "knock on wood", "know the ropes", "leave no stone unturned",
    "let sleeping dogs lie", "let someone off the hook", "look before you leap", "lose your touch", "make a long story short",
    "make ends meet", "miss the mark", "no ifs ands or buts", "off the top of my head", "on cloud nine",
    "on thin ice", "out of the blue", "out of the frying pan and into the fire", "over the moon", "pass with flying colors",
    "pay through the nose", "play devil's advocate", "play it by ear", "pull yourself together", "put something on ice",
    "put the cart before the horse", "put your foot down", "read between the lines", "ring a bell", "rise and shine",
    "rub salt in the wound", "rule of thumb", "run like the wind", "see eye to eye", "shoot from the hip",
    "sick and tired", "sit tight", "sleep on it", "spill the beans", "start from scratch", "stay in touch",
    "steal someone's thunder", "stick to your guns", "take a rain check", "take it with a grain of salt", "the early bird gets the worm",
    "the elephant in the room", "the whole nine yards", "there's no place like home", "throw in the towel", "tickled pink",
    "time flies", "up in arms", "wake up on the wrong side of the bed", "waste not want not", "watch your mouth",
    "water under the bridge", "wear your heart on your sleeve", "weigh your words", "when pigs fly", "white lie",
    "wipe the slate clean", "with flying colors", "worth its weight in gold", "you can't judge a book by its cover", "your guess is as good as mine",
    "at the end of the day", "for the time being", "in light of the fact that", "with all due respect", "as a matter of fact",
    "all things considered", "by and large", "for the most part", "in a manner of speaking", "last but not least",
    "more or less", "sooner or later", "time and time again", "first and foremost", "odds and ends",
    "part and parcel", "safe and sound", "null and void", "bits and pieces", "peace and quiet",
    "leaps and bounds", "prim and proper", "spick and span", "tried and true", "short and sweet",
    "high and dry", "now or never", "do or die", "sink or swim", "make or break",
    "the one and only", "the one and the same", "in this day and age", "from time to time", "from head to toe",
    "from start to finish", "from top to bottom", "from beginning to end", "through and through", "day in and day out",
    "year in and year out", "here and there", "now and then", "little by little", "step by step",
    "word for word", "time after time", "over and over again", "again and again", "on and on",
    "so on and so forth", "to and fro", "back and forth", "up and down", "in and out",
    "round and round", "by the way", "in other words", "on the other hand", "for example",
    "as a result", "in addition", "in conclusion", "in summary", "in fact",
    "of course", "as well as", "such as", "due to", "because of",
    "in order to", "with regard to", "according to", "based on", "in case of",
    "in spite of", "instead of", "looking forward to", "as far as", "as long as",
    "as soon as", "as if", "as though", "even if", "even though",
    "so that", "provided that", "in the event that", "the fact that", "the point is",
    "the thing is", "what I mean is", "having said that", "that being said", "be that as it may",
    "for all intents and purposes", "in the final analysis", "when it comes to", "in terms of", "with respect to",
    "a lot of", "a couple of", "a number of", "a great deal of", "a variety of",
    "a means of", "a source of", "a form of", "a type of", "a kind of",
    "an example of", "the rest of", "the majority of", "the purpose of", "the importance of",
    "the impact of", "the role of", "the process of", "the development of", "the use of",
    "the study of", "the concept of", "the idea of", "the theory of", "the principle of",
    "it is important to", "it is necessary to", "it is possible to", "it is likely that", "it seems that",
    "it appears that", "there is no doubt that", "there is a need to", "there is a way to", "research has shown",
    "studies have found", "it has been suggested", "it has been argued", "it can be seen", "as can be seen",
    "as shown in", "as demonstrated by", "in the context of", "in the field of", "in the area of",
    "in the process of", "in the form of", "in the case of", "on the basis of", "on the part of",
    "at the level of", "at the time of", "for the purpose of", "with the exception of", "without a doubt",
    "without question", "without a second thought", "in no time", "in the meantime", "in the long run",
    "in the short term", "for good", "for sure", "for real", "for free",
    "for sale", "for rent", "for hire", "for example", "for instance",
    "from now on", "from then on", "from here on out", "up to date", "up to you",
    "up to a point", "out of date", "out of order", "out of context", "out of control",
    "out of the question", "by accident", "by chance", "by hand", "by heart",
    "by mistake", "by myself", "by no means", "by the book", "on purpose",
    "on time", "on schedule", "on average", "on fire", "on sale",
    "on display", "on hold", "on board", "on duty", "on vacation",
    "on the phone", "on the internet", "on the one hand", "on the other hand", "at first",
    "at last", "at least", "at most", "at best", "at worst", "at large", "at length", "at once",
    "at present", "at random", "at risk", "at stake", "at work", "in advance", "in charge",
    "in common", "in detail", "in effect", "in full", "in general", "in particular", "in person",
    "in place", "in private", "in public", "in reality", "in return", "in stock", "in style",
    "in theory", "in trouble", "in turn", "in use", "in vain", "in view of",
    "with care", "with ease", "with luck", "with pleasure", "with respect",
    "with success", "without delay", "without fail", "without warning", "all of a sudden",
    "as a whole", "at all costs", "by all means", "for the best", "in the end",
    "of all time", "to some extent", "to a certain extent", "to a great extent", "to the full",
    "to the point", "under control", "under pressure", "under the circumstances", "within reason",
    "without exception", "and so on", "and so forth", "and the like", "and whatnot", "et cetera"
)

private val PROPER_NOUN_CONNECTORS = setOf(
    "of", "de", "van", "von", "la", "le", "di", "da", "del", "du", "dos", "das", "al", "bin", "binti", "ben", "y", "e"
)

private val COMMON_CAPITALIZED_SENTENCE_WORDS = setOf(
    "Please", "Thanks", "Thank", "Yes", "No", "Well", "Okay", "Ok", "Maybe", "Actually", "Basically", "Really", "Simply", "Literally"
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

    // Subtract ALL pronouns to preserve them in the text as requested.
    val stopWords = (DEFAULT_STOP_WORDS +
        SemanticCompressionLexicon.stopWords +
        settings.additionalStopWords.map { it.lowercase() }) - PRONOUNS
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

    val compressedParagraphs = splitParagraphBlocks(expandedContractions)
        .mapNotNull { paragraph ->
            compressParagraph(
                paragraph = paragraph,
                phraseToCode = phraseToCode,
                stopWords = stopWords,
                allPhrases = allPhrases
            )
        }

    val compressed = compressedParagraphs.joinToString(" $PARAGRAPH_MARKER ")

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

private fun splitParagraphBlocks(input: String): List<String>
{
    return PARAGRAPH_BREAK_PATTERN.split(input)
        .map { it.trim() }
        .filter { it.isNotBlank() }
}

private fun compressParagraph(
    paragraph: String,
    phraseToCode: Map<String, String>,
    stopWords: Set<String>,
    allPhrases: Collection<String>
): String?
{
    if(paragraph.isBlank())
    {
        return null
    }

    var compressed = paragraph
    compressed = replaceProperNouns(compressed, phraseToCode)
    compressed = removeCommonPhrases(
        compressed,
        SemanticCompressionLexicon.buildPhrasePattern(allPhrases)
    )
    compressed = removeStopWords(
        compressed,
        stopWords
    )
    return collapseWhitespace(removePunctuation(compressed))
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
        if(character == '"' || character == '“' || character == '”')
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
    // Preserve quotation marks and colons as requested.
    return input.replace(Regex("[^A-Za-z0-9:\"“”\\s]"), " ")
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
    val tokenMatches = TOKEN_PATTERN.findAll(input).toList()
    val connectorSet = connectors.map { it.lowercase() }.toSet()
    
    val tokens = tokenMatches.mapIndexed { i, match ->
        val isSentenceStart = if (i == 0) true else {
            val prevMatch = tokenMatches[i - 1]
            val gap = input.substring(prevMatch.range.last + 1, match.range.first)
            gap.contains(Regex("[.?!\\n]"))
        }
        TokenData(match.value, match.range.first, isSentenceStart)
    }

    var index = 0

    while(index < tokens.size)
    {
        val tokenData = tokens[index]

        if(!isProperNounToken(tokenData.text, connectorSet))
        {
            index++
            continue
        }

        val runTokens = mutableListOf<TokenData>()

        while(index < tokens.size && isProperNounToken(tokens[index].text, connectorSet))
        {
            if (runTokens.isNotEmpty() && tokens[index].isSentenceStart) {
                break
            }
            runTokens.add(tokens[index])
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
            val lowerPhrase = phrase.lowercase()
            val repeatedEnough = candidate.count >= properNounReplacementThreshold(phrase)
            val isValidProperNoun = candidate.seenNotAtSentenceStart
            repeatedEnough && isValidProperNoun && 
                phrase !in commonCapitalizedSentenceWords &&
                lowerPhrase !in PRONOUNS
        }
        .sortedWith(
            compareByDescending<ProperNounCandidate> { it.count }
                .thenBy { it.firstIndex }
                .thenByDescending { it.phrase.length }
        )
}

private fun collectProperNounRunCandidates(
    runTokens: List<TokenData>,
    candidates: MutableMap<String, ProperNounCandidate>,
    stopWords: Set<String>,
    commonCapitalizedSentenceWords: Set<String>,
    connectorSet: Set<String>
)
{
    if(runTokens.isEmpty())
    {
        return
    }

    if(runTokens.size == 1)
    {
        val remaining = trimConnectorEdges(runTokens, connectorSet)
        if (remaining.isNotEmpty()) {
            registerProperNounCandidate(
                unitTokens = remaining,
                repeatCount = 1,
                candidates = candidates,
                stopWords = stopWords,
                commonCapitalizedSentenceWords = commonCapitalizedSentenceWords
            )
        }
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
    unitTokens: List<TokenData>,
    repeatCount: Int,
    candidates: MutableMap<String, ProperNounCandidate>,
    stopWords: Set<String>,
    commonCapitalizedSentenceWords: Set<String>
)
{
    if(unitTokens.isEmpty())
    {
        return
    }

    val phrase = collapseWhitespace(unitTokens.joinToString(" ") { it.text })
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
    val firstIndex = unitTokens.first().index
    val isSentenceStart = unitTokens.first().isSentenceStart

    candidates[phrase] = candidates[phrase]?.let { existing ->
        existing.count += count
        if(!isSentenceStart) existing.seenNotAtSentenceStart = true
        existing
    } ?: ProperNounCandidate(
        phrase = phrase,
        count = count,
        firstIndex = firstIndex,
        seenNotAtSentenceStart = !isSentenceStart
    )
}

private fun trimConnectorEdges(
    tokens: List<TokenData>,
    connectorSet: Set<String>
): List<TokenData>
{
    var start = 0
    var end = tokens.size

    while(start < end && tokens[start].text.lowercase() in connectorSet)
    {
        start++
    }

    while(end > start && tokens[end - 1].text.lowercase() in connectorSet)
    {
        end--
    }

    return tokens.subList(start, end)
}

private fun findRepeatedPrefix(
    tokens: List<TokenData>
): Pair<Int, Int>?
{
    val values = tokens.map { it.text }

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
        token.firstOrNull()?.isUpperCase() == true
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
