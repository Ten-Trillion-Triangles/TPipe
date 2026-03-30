package com.TTT.Util

import org.junit.jupiter.api.Test
import kotlin.test.*

class SemanticCompressionTest
{
    @Test
    fun semanticCompressReturnsBodyAndLegend()
    {
        val input = "Alice Johnson and Bob Smith went to Project Atlas. Alice Johnson discussed Project Atlas with Bob Smith."
        val result = semanticCompress(input)

        assertNotNull(result.compressedText)
        assertNotNull(result.legend)
        assertNotNull(result.legendMap)
    }

    @Test
    fun semanticCompressionMasksAndRestoresQuotesVerbatim()
    {
        val input = "He said \"DO NOT COMPRESS THIS\" and then left."
        val result = semanticCompress(input)

        assertTrue(result.compressedText.contains("DO NOT COMPRESS THIS"), "Quoted text should be preserved verbatim")
        assertFalse(result.compressedText.contains("DO NOT COMPRESS THIS".lowercase()), "Quoted text should not be lowercase-normalized")
    }

    @Test
    fun semanticCompressionHandlesSmartQuotesVerbatim()
    {
        val input = "He said “DO NOT COMPRESS THIS” and then left."
        val result = semanticCompress(input)

        assertTrue(result.compressedText.contains("DO NOT COMPRESS THIS"), "Smart-quoted text should be preserved verbatim")
    }

    @Test
    fun semanticCompressionNormalizesToAscii()
    {
        val input = "Café au lait is résumé-worthy."
        val result = semanticCompress(input)

        assertFalse(result.compressedText.contains("é"), "Unicode characters should be normalized out")
        assertTrue(result.compressedText.contains("Cafe"), "Accented characters should be transliterated")
    }

    @Test
    fun semanticCompressionExpandsContractions()
    {
        val input = "I don't know if they'll arrive on time."
        val result = semanticCompress(input)

        // "don't" -> "do not", "they'll" -> "they will"
        // "do", "not", "they", "will" might be stop words or phrases depending on lexicon.
        // The important part is that the contraction is gone.
        assertFalse(result.compressedText.contains("don't"), "Contractions should be expanded")
        assertFalse(result.compressedText.contains("they'll"), "Contractions should be expanded")
    }

    @Test
    fun semanticCompressionRemovesFunctionWordsAndPhrases()
    {
        val input = """
            We are going to do this in order to make sure the team can, if possible, finish as soon as possible.
            The group should compare the launch notes, the training guide, and the status summary in order to
            keep the plan clear, reduce duplication, and make the final review easier for everyone involved.
        """.trimIndent()

        val result = semanticCompress(input)

        assertFalse(result.compressedText.contains("in order to"), "Phrase removal should strip boilerplate")
        assertFalse(result.compressedText.contains("as soon as possible"), "Phrase removal should strip common filler")
        
        // "the" is in stop words
        assertFalse(result.compressedText.split(" ").any { it.equals("the", ignoreCase = true) })
        
        assertTrue(
            result.compressedText.length < input.length,
            "Removing function words should lower character usage"
        )
    }

    @Test
    fun semanticCompressionUsesResearchNoteLegendThresholds()
    {
        // 1 token: do not replace
        assertTrue(
            semanticCompress("He said, Alice. " .repeat(10).trim()).legend.isEmpty(),
            "Single-token proper nouns should never be legend-coded"
        )

        // 2 tokens: must occur 6 times
        assertTrue(
            semanticCompress(
                "He said, Alice Johnson. ".repeat(5).trim()
            ).legend.isEmpty(),
            "Two-token proper nouns should not be coded before 6 occurrences"
        )

        assertTrue(
            semanticCompress(
                "He said, Alice Johnson. ".repeat(6).trim()
            ).legend.contains("AA: Alice Johnson"),
            "Two-token proper nouns should code at 6 occurrences"
        )

        // 3 tokens: must occur 4 times
        assertTrue(
            semanticCompress(
                "He said, Alpha Beta Gamma. ".repeat(3).trim()
            ).legend.isEmpty(),
            "Three-token proper nouns should not be coded before 4 occurrences"
        )

        assertTrue(
            semanticCompress(
                "He said, Alpha Beta Gamma. ".repeat(4).trim()
            ).legend.contains("AA: Alpha Beta Gamma"),
            "Three-token proper nouns should code at 4 occurrences"
        )

        // 4 tokens: must occur 3 times
        assertTrue(
            semanticCompress(
                "He said, Alpha Beta Gamma Delta. ".repeat(2).trim()
            ).legend.isEmpty(),
            "Four-token proper nouns should not be coded before 3 occurrences"
        )

        assertTrue(
            semanticCompress(
                "He said, Alpha Beta Gamma Delta. ".repeat(3).trim()
            ).legend.contains("AA: Alpha Beta Gamma Delta"),
            "Four-token proper nouns should code at 3 occurrences"
        )

        // 6+ tokens: must occur 2 times
        assertTrue(
            semanticCompress(
                "He said, One Two Three Four Five Six. ".repeat(2).trim()
            ).legend.contains("AA: One Two Three Four Five Six"),
            "Six-token proper nouns should code at 2 occurrences"
        )
    }

    @Test
    fun semanticCompressionPreservesPronouns()
    {
        val input = "I and we should make sure that he and she can see it and what they know about where we are."
        val result = semanticCompress(input)
        val words = result.compressedText.lowercase().split(" ")

        assertTrue(words.contains("i"), "First person pronoun 'I' should be preserved")
        assertTrue(words.contains("we"), "First person pronoun 'we' should be preserved")
        assertTrue(words.contains("he"), "Third person pronoun 'he' should be preserved")
        assertTrue(words.contains("she"), "Third person pronoun 'she' should be preserved")
        assertTrue(words.contains("it"), "Third person pronoun 'it' should be preserved")
        assertTrue(words.contains("what"), "Relative/Interrogative pronoun 'what' should be preserved")
        assertTrue(words.contains("they"), "Third person pronoun 'they' should be preserved")
        assertTrue(words.contains("where"), "Relative/Interrogative pronoun 'where' should be preserved")
    }

    @Test
    fun semanticCompressionLegendCodesStartAtAaAndAdvanceDeterministically()
    {
        val input = buildString {
            repeat(6) { append("Alice Johnson ") }
            repeat(6) { append("Bob Smith ") }
        }.trim()

        val result = semanticCompress(input)
        val legendLines = result.legend.lines()

        assertTrue(result.legend.startsWith("Legend:"), "Legend should still begin with the expected header")
        assertTrue(legendLines.any { it.startsWith("AA: Alice Johnson") }, "First legend entry should use AA")
        assertTrue(legendLines.any { it.startsWith("AB: Bob Smith") }, "Second legend entry should use AB")
    }

    @Test
    fun semanticCompressionCollapsesWhitespaceAndStripsMostPunctuation()
    {
        val input = "Hello,   world! This: is a test... with multiple spaces and punctuation."
        val result = semanticCompress(input)

        // Colons are preserved
        assertTrue(result.compressedText.contains(":"), "Colons should be preserved")
        
        // Punctuation is removed (replaced with space and collapsed)
        assertFalse(result.compressedText.contains(","), "Commas should be removed")
        assertFalse(result.compressedText.contains("!"), "Exclamations should be removed")
        assertFalse(result.compressedText.contains("..."), "Ellipses should be removed")
        
        // Whitespace is collapsed
        assertFalse(result.compressedText.contains("   "), "Multiple spaces should be collapsed")
    }

    @Test
    fun semanticCompressionPreservesParagraphBreaksWithPilcrowMarkers()
    {
        val input = """
            Alice Johnson met Bob Smith in the morning.
            They reviewed the launch plan together.

            Later that day, Alice Johnson and Bob Smith sent the final update.
            They confirmed the launch plan was ready.
        """.trimIndent()

        val result = semanticCompress(input)

        assertTrue(result.compressedText.contains("¶"), "Paragraph breaks should survive compression as pilcrow markers")
        assertFalse(result.compressedText.contains("\n\n"), "Paragraph breaks should no longer be flattened into blank lines")
        assertTrue(result.compressedText.contains("Alice Johnson") || result.legend.contains("Alice Johnson"), "Repeated proper nouns should still be represented")
    }
}
