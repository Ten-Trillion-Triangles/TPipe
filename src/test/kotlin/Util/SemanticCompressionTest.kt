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

    @Test
    fun semanticCompressionHandlesEssaySizedPromptsWithExpandedLexicon()
    {
        val input = """
            Alice Johnson and Bob Smith are coordinating Project Atlas at the same time that the product team is
            preparing the launch memo. In the meantime, Alice Johnson is reviewing the draft, Bob Smith is
            comparing the risk list, and the broader group is making sure the handoff notes stay clear. For the
            most part, the team agrees that the plan is stable, although they still want to compare the current
            outline with the previous release report, the support summary, and the incident notes in order to
            make sure nothing important is missing. As a result, Alice Johnson will gather the final edits,
            Bob Smith will confirm the customer response section, and the reviewers will keep the language as
            concise as possible so the model does not waste attention on boilerplate.

            Although the draft is already in good shape, the team keeps checking whether the support guidance,
            the training notes, and the implementation checklist are all aligned. On the other hand, Bob Smith
            says the paragraph should not repeat filler phrases like at the end of the day, in the meantime,
            or with respect to, because those phrases add noise without changing the actual meaning. The group
            also wants to preserve the important facts about the launch timeline, the owner assignments, and
            the review sequence so the prompt still reads like a useful working document after compression.

            "Quoted text should stay exactly as it is, even when it contains Alice Johnson, Bob Smith, and in the meantime."

            This essay-length fixture exists to stress the expanded stop-word tables, the longer phrase list,
            and the repeated proper-noun legend generation. It should compress substantially more than a short
            paragraph while still preserving the quoted span, the key action items, and the semantic structure
            the model needs to recover the intent.
        """.trimIndent()

        val result = semanticCompress(input)

        assertTrue(result.legend.startsWith("Legend:"), "A repeated proper noun should still generate a legend")
        assertTrue(result.legendMap.isNotEmpty(), "The expanded fixture should produce legend entries")
        assertTrue(
            result.compressedText.contains("\"Quoted text should stay exactly as it is, even when it contains Alice Johnson, Bob Smith, and in the meantime.\""),
            "Quoted text must remain untouched"
        )
        assertFalse(result.compressedText.contains("at the same time"), "Expanded phrase tables should remove common boilerplate")
        assertFalse(
            result.compressedText.substringBefore("\"Quoted text should stay exactly as it is, even when it contains Alice Johnson, Bob Smith, and in the meantime.\"")
                .contains("in the meantime"),
            "Expanded phrase tables should remove boilerplate phrases outside quoted spans"
        )
        assertFalse(result.compressedText.contains("for the most part"), "Expanded phrase tables should remove filler phrases")
        assertFalse(result.compressedText.contains("with respect to"), "Expanded phrase tables should remove boilerplate phrases")

        val rawTokens = Dictionary.countTokens(input, TruncationSettings())
        val compressedTokens = Dictionary.countTokens(result.compressedText, TruncationSettings())

        assertTrue(compressedTokens < rawTokens, "Semantic compression should reduce the token count")
        assertTrue(
            compressedTokens <= (rawTokens * 0.8).toInt(),
            "The expanded lexicon should deliver a meaningful reduction on an essay-sized prompt"
        )
    }

    @Test
    fun semanticCompressionRemovesCommonContractionsAndBroaderBoilerplate()
    {
        val input = """
            We can't keep repeating boilerplate like at the end of the day, for the avoidance of doubt, or with
            respect to the same launch proposal. If you're going to review the deck, we're going to need you to
            confirm that Bob Smith and Alice Johnson can keep the customer notes aligned, because we're not
            trying to waste attention on filler or on phrases that do not change the actual meaning.

            In the meantime, let's make sure the summary stays readable, the launch checklist stays aligned, and
            the reviewer can keep the important facts without carrying around a lot of needless phrasing that
            the compressor should be able to strip away.
        """.trimIndent()

        val result = semanticCompress(input)

        assertFalse(Regex("\\bwon\\b").containsMatchIn(result.compressedText.lowercase()))
        assertFalse(Regex("\\bgonna\\b").containsMatchIn(result.compressedText.lowercase()))
        assertFalse(Regex("\\bkinda\\b").containsMatchIn(result.compressedText.lowercase()))
        assertFalse(Regex("\\bsorta\\b").containsMatchIn(result.compressedText.lowercase()))
        assertFalse(Regex("\\blemme\\b").containsMatchIn(result.compressedText.lowercase()))
        assertFalse(result.compressedText.contains("at the end of the day"))
        assertFalse(result.compressedText.contains("for the avoidance of doubt"))
        assertFalse(result.compressedText.contains("with respect to"))
        assertFalse(result.compressedText.contains("in the meantime"))
        assertTrue(result.compressedText.contains("launch checklist"))
        assertTrue(result.compressedText.contains("customer notes"))

        val rawTokens = Dictionary.countTokens(input, TruncationSettings())
        val compressedTokens = Dictionary.countTokens(result.compressedText, TruncationSettings())

        assertTrue(compressedTokens < rawTokens, "Broader lexicon coverage should reduce the token count")
    }

    @Test
    fun semanticCompressionAuditReportsResidualPhraseCandidates()
    {
        val inputs = listOf(
            """
                At the current juncture, the team should review the draft and keep the plan stable. At the current
                juncture, the reviewer should keep the launch notes aligned without adding filler language.
            """.trimIndent(),
            """
                For all practical purposes, the rollout is stable, but for all practical purposes the team still
                wants to keep the summary clear and the handoff notes readable.
            """.trimIndent()
        )

        val report = auditSemanticCompressionCorpus(inputs)

        assertTrue(report.inputCount == 2, "The audit should reflect the number of inputs")
        assertTrue(report.tokenSavings > 0, "Compression should still save tokens across the audit corpus")
        assertTrue(
            report.residualPhrases.any { it.phrase.contains("current juncture") },
            "The audit should surface recurring phrase gaps for future lexicon tuning"
        )
        assertTrue(
            report.residualPhrases.any { it.phrase.contains("practical purposes") },
            "The audit should surface repeated boilerplate phrases that are not yet covered"
        )
    }
}
