package com.TTT.Util

import com.TTT.Context.Dictionary
import com.TTT.Pipe.TruncationSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SemanticCompressionTest
{
    @Test
    fun semanticCompressionPreservesQuotedSpansAndBuildsLegend()
    {
        val input = """
            Alice Johnson and Alice Johnson will review the proposal in order to help the team at the café.
            Alice Johnson will prepare the deck in addition to a status update for Alice Johnson, and the
            group will compare the launch plan with the customer response summary, the executive briefing, and
            the support notes before the review call.

            "Quoted naïve façade stays untouched with Alice Johnson and in order to."

            Alice Johnson will prepare the deck in addition to a status update for Alice Johnson, and Bob Smith
            will gather the final review notes, the risk list, and the implementation checklist before the next
            meeting so that the team can move quickly without repeating the same boilerplate instructions.
        """.trimIndent()

        val result = semanticCompress(input)
        val aliceEntry = result.legendMap.entries.firstOrNull { it.value == "Alice Johnson" }

        assertTrue(result.legend.startsWith("Legend:"), "Repeated proper nouns should produce a legend")
        assertTrue(aliceEntry != null, "Repeated proper nouns should be mapped into the legend")
        assertEquals(2, aliceEntry!!.key.length, "Legend codes must stay two characters long")
        assertTrue(
            result.compressedText.contains("\"Quoted naïve façade stays untouched with Alice Johnson and in order to.\""),
            "Quoted text must remain untouched"
        )
        assertTrue(result.compressedText.contains("cafe"), "Unicode outside quotes should be normalized to ASCII")
        assertFalse(
            result.compressedText
                .substringBefore("\"Quoted naïve façade stays untouched with Alice Johnson and in order to.\"")
                .contains("in order to"),
            "Common phrases should be removed outside the preserved quoted span"
        )
        assertTrue(
            Dictionary.countTokens(result.compressedText, TruncationSettings()) <
                Dictionary.countTokens(input, TruncationSettings()),
            "Semantic compression should reduce token usage"
        )
    }

    @Test
    fun semanticCompressionIsDeterministic()
    {
        val input = """
            Alice Johnson and Alice Johnson will review the proposal in order to help the team.
            Alice Johnson and Alice Johnson will prepare the deck as soon as possible.
            Bob Smith will compare the revised launch checklist against the old report, and Alice Johnson will
            confirm that the customer response summary, the rollout plan, and the support notes are all aligned.
        """.trimIndent()

        val first = semanticCompress(input)
        val second = semanticCompress(input)

        assertEquals(first, second, "Compression must be deterministic for the same input")
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
        assertFalse(result.compressedText.split(" ").any { it.equals("the", ignoreCase = true) })
        assertFalse(result.compressedText.split(" ").any { it.equals("to", ignoreCase = true) })
        assertTrue(
            Dictionary.countTokens(result.compressedText, TruncationSettings()) <
                Dictionary.countTokens(input, TruncationSettings()),
            "Removing function words should lower token usage"
        )
        assertNotEquals(input, result.compressedText, "The compressor should actually change compressible text")
    }
}
