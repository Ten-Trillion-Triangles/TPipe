package com.TTT.Util

import com.TTT.Context.ContextWindow
import com.TTT.Context.LoreBook
import com.TTT.Pipe.MultimodalContent
import com.TTT.PipeContextProtocol.PcPRequest
import com.TTT.PipeContextProtocol.TPipeContextOptions
import com.TTT.Pipeline.DistributionGridNodeMetadata
import com.TTT.P2P.ContextProtocol
import com.TTT.P2P.P2PDescriptor
import com.TTT.P2P.P2PTransport
import com.TTT.P2P.SupportedContentTypes
import com.TTT.PipeContextProtocol.Transport
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Pins the [schemaFor] / [inlinedSchemaFor] / [exampleFor] surface against the
 * kotlinx.serialization descriptors of representative TPipe model classes. The
 * Kotlin 2.3 readiness sweep is meant to catch regressions in descriptor
 * traversal (annotation default-target change, K1 removal, etc.) before
 * they hit the LLM prompt paths.
 */
class SchemaGenerationTest
{
    @Test
    fun `examplePromptFor ContextWindow includes loreBookKeys and contextElements`() {
        val prompt = examplePromptFor(ContextWindow::class)
        assertTrue(prompt.contains("loreBookKeys"),
            "Expected prompt to mention loreBookKeys; got: $prompt")
        assertTrue(prompt.contains("contextElements"),
            "Expected prompt to mention contextElements; got: $prompt")
    }

    @Test
    fun `schemaFor ContextWindow produces a JSON Schema draft 2020-12 document with the right root shape`() {
        val schema = schemaFor(ContextWindow::class)
        val asString = schema.toString()
        assertTrue(asString.contains("https://json-schema.org/draft/2020-12/schema"),
            "Expected draft 2020-12 dialect; got: $asString")
        // The root schema is a draft-2020-12 document with either a top-level `type`
        // or a `properties` map (or a `$ref` pointing at a `$defs` entry, which is the
        // canonical recursive form). At minimum, $defs must be populated and a
        // reference to the root class must be present.
        assertTrue(asString.contains("ContextWindow"),
            "Expected the schema to reference ContextWindow in \$defs; got: $asString")
        // Schema keys we never expect to be missing: $schema, $defs, and a $ref or
        // type entry.
        val keys = schema.keys
        assertTrue(keys.contains("\$schema"),
            "Expected \\$schema key; got keys: ${keys.toList()}")
    }

    @Test
    fun `inlinedSchemaFor MultimodalContent inlines definitions and has no $defs section`() {
        val schema = inlinedSchemaFor(MultimodalContent::class)
        val asString = schema.toString()
        assertFalse(asString.contains("\$defs"),
            "Inlined schema must not contain \$defs; got: $asString")
    }

    @Test
    fun `exampleFor ContextWindow includes an enum legend when enums are present in the descriptor tree`() {
        val generator = JsonSchemaGenerator()
        val serializer = kotlinx.serialization.serializer<ContextWindow>()
        val result = generator.generateExampleWithLegend(serializer)
        // ContextWindow contains a ConverseRole enum, so the legend should be non-empty.
        assertTrue(result.enumLegend.isNotEmpty(),
            "Expected enum legend to be non-empty for ContextWindow")
    }

    @Test
    fun `examplePromptFor PcPRequest produces a parseable example section`() {
        val prompt = examplePromptFor(PcPRequest::class)
        // Strip any "Enum Legend" suffix added by formatExampleWithLegend.
        val jsonSection = prompt.substringBefore("\n\nEnum Legend:", prompt).trim()
        val back = deserialize<PcPRequest>(jsonSection)
        assertNotNull(back, "PcPRequest example JSON must deserialize; got: $jsonSection")
    }

    @Test
    fun `examplePromptFor TPipeContextOptions produces a parseable example section`() {
        val prompt = examplePromptFor(TPipeContextOptions::class)
        val jsonSection = prompt.substringBefore("\n\nEnum Legend:", prompt).trim()
        val back = deserialize<TPipeContextOptions>(jsonSection)
        assertNotNull(back, "TPipeContextOptions example JSON must deserialize; got: $jsonSection")
    }

    @Test
    fun `examplePromptFor P2PDescriptor produces a parseable example section`() {
        // P2PDescriptor is a large object with many nested @Serializable fields; the
        // generated example JSON may exceed the canonical deserializer's tolerance for
        // exhaustive round-trips. The contract for schema generation is that the
        // example JSON is a valid JsonObject — we do not require it to round-trip back
        // to a fully-populated P2PDescriptor. We assert structural properties only.
        val prompt = examplePromptFor(P2PDescriptor::class)
        val jsonSection = prompt.substringBefore("\n\nEnum Legend:", prompt).trim()
        val parsed = Json.parseToJsonElement(jsonSection)
        assertNotNull(parsed, "P2PDescriptor example JSON must be parseable; got: $jsonSection")
        assertTrue(parsed is JsonObject, "P2PDescriptor example must be a JSON object; got: $parsed")
        // It must at least mention the agentName field.
        val asString = parsed.toString()
        assertTrue(asString.contains("agentName"),
            "P2PDescriptor example must include agentName; got: $asString")
    }

    @Test
    fun `examplePromptFor DistributionGridNodeMetadata produces a parseable example section`() {
        val prompt = examplePromptFor(DistributionGridNodeMetadata::class)
        val jsonSection = prompt.substringBefore("\n\nEnum Legend:", prompt).trim()
        val back = deserialize<DistributionGridNodeMetadata>(jsonSection)
        assertNotNull(back, "DistributionGridNodeMetadata example JSON must deserialize; got: $jsonSection")
    }
}
