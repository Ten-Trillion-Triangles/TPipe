package com.TTT.Util

import java.text.Normalizer
import kotlin.test.Test
import kotlin.test.assertTrue

class SemanticCompressionLexiconCoverageTest
{
    @Test
    fun researchNoteLexiconExtensionsAreLoaded()
    {
        val stopWords = semanticCompressionStopWords()
        val phrases = semanticCompressionCommonPhrases()

        listOf(
            "aboard",
            "albeit",
            "amid",
            "amidst",
            "atop",
            "circa",
            "excluding",
            "minus",
            "pending",
            "per",
            "sans",
            "used",
            "via",
            "versus",
            "whence",
            "whenever",
            "wherever",
            "whither",
            "whyever"
        ).forEach { word ->
            assertTrue(stopWords.contains(word), "Expected research-note stop word to be loaded: $word")
        }

        listOf(
            "a piece of cake",
            "break a leg",
            "hit the nail on the head",
            "cost an arm and a leg",
            "beat around the bush",
            "bite the bullet",
            "call it a day",
            "cut corners",
            "get out of hand",
            "hang in there",
            "in a nutshell",
            "it's not rocket science",
            "kill two birds with one stone",
            "let the cat out of the bag",
            "miss the boat",
            "no pain no gain",
            "on the ball",
            "once in a blue moon",
            "pull someone's leg",
            "speak of the devil",
            "the ball is in your court",
            "the best of both worlds",
            "the last straw",
            "through thick and thin",
            "under the weather",
            "wrap your head around something",
            "you can say that again",
            "a blessing in disguise",
            "a dime a dozen",
            "a drop in the bucket",
            "a fish out of water",
            "a slap on the wrist",
            "a taste of your own medicine",
            "add fuel to the fire",
            "against the clock",
            "all ears",
            "all in the same boat",
            "at the drop of a hat"
        ).map(::normalizeCoveragePhrase).forEach { phrase ->
            assertTrue(phrases.contains(phrase), "Expected research-note phrase to be loaded: $phrase")
        }
    }
}

private fun normalizeCoveragePhrase(input: String): String
{
    return Normalizer.normalize(input, Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")
        .replace(Regex("[^\\x00-\\x7F]"), " ")
        .replace(Regex("[^A-Za-z0-9:\\s]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
        .lowercase()
}
