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

    @Test
    fun semanticDecompressionPromptRequiresContentIdentificationGate()
    {
        val prompt = ReasoningPrompts.semanticDecompressionPrompt(
            depth = ReasoningDepth.Med,
            duration = ReasoningDuration.Med
        )

        //Content identification gate is present in the decompression process.
        assertTrue(prompt.contains("CONTENT IDENTIFICATION GATE"),
            "Prompt must contain the content identification gate heading")
        assertTrue(prompt.contains("Enumerate all plausible interpretations"),
            "Prompt must require hypothesis enumeration")
        assertTrue(prompt.contains("evidence"),
            "Prompt must require evidence evaluation")
        assertTrue(prompt.contains("likelihood"),
            "Prompt must require likelihood assessment")
        assertTrue(prompt.contains("Select the most likely interpretation"),
            "Prompt must require selecting a final interpretation")

        //Content identification appears in the OUTPUT SHAPE.
        assertTrue(prompt.contains("contentIdentification"),
            "OUTPUT SHAPE must document the contentIdentification field")
        assertTrue(prompt.contains("hypotheses"),
            "OUTPUT SHAPE must document the hypotheses sub-field")
        assertTrue(prompt.contains("evidenceAnalysis"),
            "OUTPUT SHAPE must document the evidenceAnalysis sub-field")
        assertTrue(prompt.contains("selectedInterpretation"),
            "OUTPUT SHAPE must document the selectedInterpretation sub-field")

        //Critical rule forbids skipping the gate.
        assertTrue(prompt.contains("MUST complete the content identification gate"),
            "Critical rules must forbid skipping the content identification gate")
    }

    @Test
    fun semanticDecompressionContentIdentificationGateAppearsAfterLegendExpansion()
    {
        val prompt = ReasoningPrompts.semanticDecompressionPrompt()

        val legendExpansionIndex = prompt.indexOf("expand all legend codes back to their original phrases")
        val contentIdIndex = prompt.indexOf("CONTENT IDENTIFICATION GATE")
        val taskIdIndex = prompt.indexOf("Identify what task the parent pipe")

        assertTrue(legendExpansionIndex > 0, "Legend expansion step must exist")
        assertTrue(contentIdIndex > 0, "Content identification gate must exist")
        assertTrue(taskIdIndex > 0, "Task identification step must exist")
        assertTrue(legendExpansionIndex < contentIdIndex,
            "Content identification gate must appear after legend expansion")
        assertTrue(contentIdIndex < taskIdIndex,
            "Content identification gate must appear before task identification")
    }
}
