package com.TTT

import com.TTT.Context.Dictionary
import com.TTT.Pipe.TruncationSettings
import com.TTT.Util.semanticCompress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SemanticCompressionPipeTest
{
    @Test
    fun compressUserPromptAppliesBeforeBudgetTruncation()
    {
        val rawPrompt = """
            Alice Johnson and Alice Johnson are going to review the launch proposal in order to make sure
            Alice Johnson can help the team with the final deck before Alice Johnson meets Bob Smith.

            Bob Smith will then coordinate with Alice Johnson, Alice Johnson, and the broader launch team in
            order to confirm that the review notes, the launch checklist, and the training materials are all
            aligned. In order to keep the rollout on track, Alice Johnson should compare the draft against
            the previous launch report, verify the customer support guidance, and ensure that Bob Smith
            receives the final status update before the approval meeting.

            The team should not lose the important facts, but it does need to remove the filler language,
            trim the repeated names, and keep the key action items clear enough that the model can still
            reconstruct the original intent without wasting a large number of tokens on boilerplate.
        """.trimIndent()

        val directCompression = semanticCompress(rawPrompt)
        val pipeCompression = MockTokenPipe("CompressionPipe").compressPrompt(rawPrompt)

        assertEquals(directCompression, pipeCompression, "Pipe helper should delegate to the shared compressor")
        assertTrue(directCompression.legend.startsWith("Legend:"), "Compression should surface a legend")
        assertTrue(directCompression.legendMap.isNotEmpty(), "Repeated proper nouns should be mapped into the legend")

        val compressedPrompt = if(directCompression.legend.isNotEmpty())
        {
            "${directCompression.legend}\n\n${directCompression.compressedText}"
        }
        else
        {
            directCompression.compressedText
        }

        val truncationSettings = TruncationSettings()
        val rawTokens = Dictionary.countTokens(rawPrompt, truncationSettings)
        val compressedTokens = Dictionary.countTokens(compressedPrompt, truncationSettings)

        assertTrue(rawTokens > compressedTokens, "Compression should reduce the token budget for prompt text")
        assertFalse(
            compressedPrompt
                .substringBefore("\"Alice Johnson")
                .contains("in order to"),
            "Prompt compression should remove common filler phrases outside quoted spans"
        )
    }

    @Test
    fun structuredPromptsBypassSemanticCompression()
    {
        val pipe = MockTokenPipe("CompressionPipe")

        assertTrue(
            pipe.shouldBypassSemanticCompression("""{"name":"Alice Johnson","task":"Review the proposal"}"""),
            "JSON-like prompts should bypass semantic compression"
        )

        assertTrue(
            pipe.shouldBypassSemanticCompression("""
                ```kotlin
                val message = "Alice Johnson"
                ```
            """.trimIndent()),
            "Code fences should bypass semantic compression"
        )

        assertFalse(
            pipe.shouldBypassSemanticCompression("Alice Johnson will review the proposal."),
            "Plain natural language should remain eligible for semantic compression"
        )
    }
}
