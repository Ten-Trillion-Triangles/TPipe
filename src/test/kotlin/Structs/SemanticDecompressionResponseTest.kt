package com.TTT.Structs

import kotlin.test.Test
import kotlin.test.assertTrue

class SemanticDecompressionResponseTest
{
    @Test
    fun semanticDecompressionResponseUnravelsStructuredRestorationFields()
    {
        val response = SemanticDecompressionResponse(
            legendAnalysis = LegendAnalysis(
                codesFound = listOf("AA"),
                mappings = listOf("AA -> Alpha Beta")
            ),
            contentIdentification = ContentIdentification(
                hypotheses = listOf("system prompt", "technical document", "user request"),
                evidenceAnalysis = listOf(
                    ContentHypothesisEvidence(
                        hypothesis = "system prompt",
                        supportingEvidence = listOf("contains instruction-like phrasing"),
                        contradictingEvidence = listOf("no role assignment found"),
                        likelihood = "medium"
                    ),
                    ContentHypothesisEvidence(
                        hypothesis = "technical document",
                        supportingEvidence = listOf("Alpha Beta appears as a technical term"),
                        contradictingEvidence = listOf(),
                        likelihood = "high"
                    )
                ),
                selectedInterpretation = "technical document describing Alpha Beta"
            ),
            taskIdentification = TaskIdentification(
                taskDescription = "Restore the compressed prompt faithfully",
                taskType = "decompression",
                requiresFullDecompression = true
            ),
            keyDataPoints = listOf("Alpha Beta must remain visible", "Quoted spans must survive"),
            quoteSpans = listOf("\"Keep this exact.\""),
            restoredSentences = listOf(
                "Alpha Beta must remain visible.",
                "\"Keep this exact.\""
            ),
            restoredParagraphs = listOf(
                "Alpha Beta must remain visible. \"Keep this exact.\""
            ),
            decompressionStrategy = DecompressionStrategy(
                approach = "decode legend first, then rebuild surface form",
                legendExpansionNotes = "expanded AA to Alpha Beta",
                inferenceNotes = "restored punctuation and quote boundaries"
            ),
            restoredContent = "Alpha Beta must remain visible. \"Keep this exact.\""
        )

        val stream = response.unravel()

        assertTrue(stream.contains("Legend mappings found: AA -> Alpha Beta"))
        assertTrue(stream.contains("Task identified: Restore the compressed prompt faithfully"))
        assertTrue(stream.contains("This task requires full decompression"))
        assertTrue(stream.contains("Quoted spans preserved: \"Keep this exact.\""))
        assertTrue(stream.contains("Restored sentences: Alpha Beta must remain visible. | \"Keep this exact.\""))
        assertTrue(stream.contains("Restored paragraphs: Alpha Beta must remain visible. \"Keep this exact.\""))
        assertTrue(stream.contains("Decompression approach: decode legend first, then rebuild surface form"))
        assertTrue(stream.contains("Legend expansion: expanded AA to Alpha Beta"))
        assertTrue(stream.contains("Inference notes: restored punctuation and quote boundaries"))
        assertTrue(stream.contains("Restored content: Alpha Beta must remain visible. \"Keep this exact.\""))
        assertTrue(
            stream.indexOf("Quoted spans preserved") < stream.indexOf("Restored sentences"),
            "Structured quote recovery should appear before sentence reconstruction in the flattened stream"
        )
    }

    @Test
    fun unravelEmitsContentIdentificationBeforeTaskIdentification()
    {
        val response = SemanticDecompressionResponse(
            legendAnalysis = LegendAnalysis(
                mappings = listOf("AA -> Some Phrase")
            ),
            contentIdentification = ContentIdentification(
                hypotheses = listOf("configuration file", "API documentation"),
                evidenceAnalysis = listOf(
                    ContentHypothesisEvidence(
                        hypothesis = "configuration file",
                        supportingEvidence = listOf("key-value pairs present"),
                        contradictingEvidence = listOf("narrative sentences found"),
                        likelihood = "low"
                    ),
                    ContentHypothesisEvidence(
                        hypothesis = "API documentation",
                        supportingEvidence = listOf("endpoint references", "parameter descriptions"),
                        contradictingEvidence = listOf(),
                        likelihood = "high"
                    )
                ),
                selectedInterpretation = "API documentation for a REST service"
            ),
            taskIdentification = TaskIdentification(
                taskDescription = "Decompress and restore the API docs",
                taskType = "decompression",
                requiresFullDecompression = true
            ),
            restoredContent = "The API endpoint accepts GET requests."
        )

        val stream = response.unravel()

        //Content identification fields are present.
        assertTrue(stream.contains("Content hypotheses considered: configuration file; API documentation"),
            "Hypotheses should be listed")
        assertTrue(stream.contains("Hypothesis: configuration file [low]"),
            "Per-hypothesis evidence should include hypothesis name and likelihood")
        assertTrue(stream.contains("supports: key-value pairs present"),
            "Supporting evidence should be emitted")
        assertTrue(stream.contains("contradicts: narrative sentences found"),
            "Contradicting evidence should be emitted")
        assertTrue(stream.contains("Hypothesis: API documentation [high]"),
            "Second hypothesis should appear with its likelihood")
        assertTrue(stream.contains("Selected interpretation: API documentation for a REST service"),
            "Selected interpretation should be emitted")

        //Content identification appears before task identification.
        assertTrue(
            stream.indexOf("Content hypotheses considered") < stream.indexOf("Task identified"),
            "Content identification must appear before task identification in the flattened stream"
        )
        assertTrue(
            stream.indexOf("Selected interpretation") < stream.indexOf("Task identified"),
            "Selected interpretation must appear before task identification"
        )
    }

    @Test
    fun unravelHandlesEmptyContentIdentificationGracefully()
    {
        val response = SemanticDecompressionResponse(
            taskIdentification = TaskIdentification(
                taskDescription = "simple restore",
                taskType = "decompression"
            ),
            restoredContent = "Hello world."
        )

        val stream = response.unravel()

        //Should not contain content identification markers when empty.
        assertTrue(!stream.contains("Content hypotheses considered"),
            "Empty hypotheses should not produce output")
        assertTrue(!stream.contains("Selected interpretation"),
            "Empty selectedInterpretation should not produce output")

        //Task identification should still be present.
        assertTrue(stream.contains("Task identified: simple restore"))
    }
}
