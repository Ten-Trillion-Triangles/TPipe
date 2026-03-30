package bedrockPipe

import kotlin.test.Test
import kotlin.test.assertTrue

class SemanticCompressionTraceFixtureTest
{
    @Test
    fun qwenSemanticCompressionTraceFixtureCapturesKnownBadLegendKeys()
    {
        val mappings = loadTraceFixtureLines(
            "bedrockPipe/qwen-semantic-compression-round-trip/legend-analysis-mappings.txt"
        )

        val offenders = mappings.filterNot { it.looksLikeTrueProperNounPhrase() }

        assertTrue(
            mappings.isNotEmpty(),
            "The trace fixture should contain the live legend-analysis mappings captured from the round-trip run"
        )
        assertTrue(
            offenders.isNotEmpty(),
            "The saved live trace should still show the bad legend keying we were investigating"
        )
        assertTrue(
            offenders.contains("dark blue suit") &&
                offenders.contains("briefcase") &&
                offenders.contains("love hate") &&
                offenders.contains("tried read book"),
            "The fixture should preserve representative non-proper-noun legend keys: $offenders"
        )
    }

    private fun loadTraceFixtureLines(resourcePath: String): List<String>
    {
        val stream = javaClass.classLoader.getResourceAsStream(resourcePath)
            ?: error("Missing test resource: $resourcePath")

        return stream.bufferedReader().useLines { lines ->
            lines.map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .toList()
        }
    }

    private fun String.looksLikeTrueProperNounPhrase(): Boolean
    {
        val connectors = setOf(
            "of", "the", "and", "de", "van", "von", "da", "del", "la", "le", "di", "du", "a", "an", "in", "on", "at", "for", "to"
        )

        val tokens = split(Regex("\\s+")).filter { it.isNotBlank() }
        if(tokens.isEmpty())
        {
            return false
        }

        return tokens.all { token ->
            val normalized = token.trim('\"', '“', '”', '(', ')', ',', '.', ';', ':', '!', '?')
            if(normalized.isBlank())
            {
                true
            }
            else if(normalized.lowercase() in connectors)
            {
                true
            }
            else
            {
                normalized.firstOrNull()?.isUpperCase() == true
            }
        }
    }
}
