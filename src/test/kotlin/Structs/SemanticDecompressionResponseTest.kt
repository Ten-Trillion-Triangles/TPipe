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
}
