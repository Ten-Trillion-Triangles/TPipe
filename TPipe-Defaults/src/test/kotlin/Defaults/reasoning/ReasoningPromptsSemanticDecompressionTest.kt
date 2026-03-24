package Defaults.reasoning

import kotlin.test.Test
import kotlin.test.assertTrue

class ReasoningPromptsSemanticDecompressionTest
{
    @Test
    fun semanticDecompressionPromptRequiresStructuredRestoration()
    {
        val prompt = ReasoningPrompts.semanticDecompressionPrompt(
            depth = ReasoningDepth.High,
            duration = ReasoningDuration.Long
        )

        assertTrue(prompt.contains("sentence-by-sentence"), "Semantic decompression should require sentence-level reconstruction")
        assertTrue(prompt.contains("paragraph-by-paragraph"), "Semantic decompression should require paragraph-level reconstruction")
        assertTrue(prompt.contains("quoteSpans"), "Semantic decompression should surface exact quote preservation")
        assertTrue(prompt.contains("restoredSentences"), "Semantic decompression should ask for restoredSentences")
        assertTrue(prompt.contains("restoredParagraphs"), "Semantic decompression should ask for restoredParagraphs")
        assertTrue(prompt.contains("restoredContent"), "Semantic decompression should keep the canonical restoredContent field")
        assertTrue(prompt.contains("Do not paraphrase"), "Semantic decompression should explicitly forbid paraphrase drift")
    }
}
