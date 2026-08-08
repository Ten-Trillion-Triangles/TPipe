package com.TTT

import com.TTT.Pipe.BinaryContent
import com.TTT.Pipe.MultimodalContent
import com.TTT.Pipe.TruncationSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.system.measureNanoTime

/**
 * Regression suite for the binary-content token counting path on Pipe.
 *
 * The previous implementation routed every binary payload through [com.TTT.Context.Dictionary.countTokens]
 * after locally rebasing it into base64. Base64 alphabet is A-Za-z0-9+/= with no whitespace, so the
 * tokenizer saw one giant "word" per binary and burned O(n * maxMatchLength) substring allocations per
 * call. A 34 KB binary choked the system for tens of milliseconds; the expected upper bound is sub-millisecond.
 *
 * The contract now is: token cost = ceil(decodedByteCount / 4). Both [BinaryContent.Bytes] and
 * [BinaryContent.Base64String] converge to the same byte count before the divide, so a round-tripped
 * payload produces the same token cost on both paths. These tests lock that contract:
 *  - the input list is not mutated (a previous bug),
 *  - bytes and base64 of the same payload produce the same token count,
 *  - 34 KB of binary finishes in under 2 ms,
 *  - text documents and cloud references still flow through the dictionary tokenizer.
 */
class CountBinaryTokensTest
{
    private val settings = TruncationSettings()

    @Test
    fun rawBytesAreCountedFromByteLength()
    {
        val pipe = MockTokenPipe("bytes-count")
        val binary = ByteArray(34_000) { (it and 0xFF).toByte() }
        val content = MultimodalContent(binaryContent = mutableListOf(BinaryContent.Bytes(binary, "image/png")))

        val tokens = pipe.countBinaryTokens(content, settings)

        // 34000 bytes / 4 bytes-per-token rounded up = 8500 tokens.
        assertEquals(8_500, tokens)
    }

    @Test
    fun base64StringIsCountedFromDecodedByteLength()
    {
        val pipe = MockTokenPipe("b64-count")
        // 34000 random bytes encode to 45336 base64 chars (34000 / 3 = 11333.33, * 4 = 45336).
        val base64 = "A".repeat(45_336)
        val content = MultimodalContent(binaryContent = mutableListOf(BinaryContent.Base64String(base64, "image/png")))

        val tokens = pipe.countBinaryTokens(content, settings)

        // Decoded byte length = (45336 / 4) * 3 = 11334 * 3 = 34002. Tokens = 34002 / 4 rounded up = 8501.
        assertEquals(8_501, tokens)
    }

    @Test
    fun bytesAndBase64WithEquivalentPayloadProduceSameTokenCount()
    {
        val pipe = MockTokenPipe("equiv-cost")
        val rawBytes = ByteArray(3_000) { (it and 0xFF).toByte() }
        val base64 = java.util.Base64.getEncoder().encodeToString(rawBytes)
        val bytesContent = MultimodalContent(binaryContent = mutableListOf(BinaryContent.Bytes(rawBytes, "image/png")))
        val b64Content = MultimodalContent(binaryContent = mutableListOf(BinaryContent.Base64String(base64, "image/png")))

        val bytesTokens = pipe.countBinaryTokens(bytesContent, settings)
        val b64Tokens = pipe.countBinaryTokens(b64Content, settings)

        // Both paths converge to the same decoded byte count and the same divide-by-four rule, so
        // a round-tripped payload must produce an identical token count on both paths.
        assertEquals(bytesTokens, b64Tokens, "Bytes and base64 paths must agree on token cost for round-tripped payloads")
        assertEquals((rawBytes.size + 3) / 4, bytesTokens)
    }

    @Test
    fun base64PaddingIsAbsorbedByIntegerDivision()
    {
        val pipe = MockTokenPipe("padding-ok")
        // 30 bytes -> ceil(30/3)*4 = 40 base64 chars. Padding characters (==) would only appear
        // for inputs not divisible by 3; here we exercise the clean-multiple case where the
        // decoded length is exactly 3/4 of the base64 length.
        val base64 = "A".repeat(40)
        val content = MultimodalContent(binaryContent = mutableListOf(BinaryContent.Base64String(base64, "image/png")))

        val tokens = pipe.countBinaryTokens(content, settings)

        // Decoded = 40/4 * 3 = 30 bytes. Tokens = 30/4 round up = 8.
        assertEquals(8, tokens)
    }

    @Test
    fun inputBinaryListIsNotMutated()
    {
        val pipe = MockTokenPipe("no-mutate")
        val rawBytes = ByteArray(8_000) { (it and 0xFF).toByte() }
        val original = BinaryContent.Bytes(rawBytes, "image/png")
        val originalRef = original
        val content = MultimodalContent(binaryContent = mutableListOf(original))

        pipe.countBinaryTokens(content, settings)

        // The original Bytes instance must remain a Bytes — no silent base64 conversion.
        assertTrue(content.binaryContent[0] === originalRef)
        assertTrue(content.binaryContent[0] is BinaryContent.Bytes)
        assertEquals(1, content.binaryContent.size)
    }

    @Test
    fun textDocumentStillUsesDictionaryTokenizer()
    {
        val pipe = MockTokenPipe("text-doc")
        val document = BinaryContent.TextDocument(content = "Hello world", mimeType = "text/plain")
        val content = MultimodalContent(binaryContent = mutableListOf(document))

        val tokens = pipe.countBinaryTokens(content, settings)

        // "Hello world" tokenizes to 2 tokens under the default settings, not 3 (which is what raw
        // byte-based counting would return for 11 chars). The text-document path must keep flowing
        // through the dictionary tokenizer.
        assertEquals(2, tokens)
    }

    @Test
    fun cloudReferenceUsesDictionaryTokenizer()
    {
        val pipe = MockTokenPipe("cloud-ref")
        val cloudRef = BinaryContent.CloudReference(uri = "s3://bucket/note.txt", mimeType = "text/plain")
        val content = MultimodalContent(binaryContent = mutableListOf(cloudRef))

        val tokens = pipe.countBinaryTokens(content, settings)

        // The URI is short and natural-language-ish; the exact count is whatever the tokenizer says,
        // but it must be a small positive number reflecting the URI text — not zero, not a base64 proxy.
        assertTrue(tokens in 1..20, "URI token count out of expected band: $tokens")
    }

    @Test
    fun thirtyFourKilobyteBinaryCompletesInUnderTwoMilliseconds()
    {
        val pipe = MockTokenPipe("perf-gate")
        val rawBytes = ByteArray(34_000) { (it and 0xFF).toByte() }
        val base64 = java.util.Base64.getEncoder().encodeToString(rawBytes)
        val content = MultimodalContent(
            binaryContent = mutableListOf(
                BinaryContent.Bytes(rawBytes, "image/png"),
                BinaryContent.Base64String(base64, "image/png")
            )
        )

        // Warmup so the first call doesn't pay JVM/IO cost.
        pipe.countBinaryTokens(content, settings)

        val elapsedNanos = measureNanoTime {
            pipe.countBinaryTokens(content, settings)
        }
        val elapsedMillis = elapsedNanos / 1_000_000.0

        assertTrue(
            elapsedMillis < 2.0,
            "countBinaryTokens took ${elapsedMillis}ms for two 34KB binaries — expected < 2ms"
        )
    }

    @Test
    fun emptyBinaryContentReturnsZero()
    {
        val pipe = MockTokenPipe("empty")
        val content = MultimodalContent()

        assertEquals(0, pipe.countBinaryTokens(content, settings))
    }

    @Test
    fun imageMimeOverrideWinsOverDefault()
    {
        val pipe = MockTokenPipe("mime-override")
        val mimeSettings = TruncationSettings(binaryMimeOverride = mapOf("image/png" to 765))
        val rawBytes = ByteArray(34_000) { (it and 0xFF).toByte() }
        val content = MultimodalContent(binaryContent = mutableListOf(BinaryContent.Bytes(rawBytes, "image/png")))

        val tokens = pipe.countBinaryTokens(content, mimeSettings)

        // 34,000 bytes would default to 8,500 tokens via the byte-exact formula.
        // The per-MIME override for image/png forces 765.
        assertEquals(765, tokens)
    }

    @Test
    fun cloudReferenceFallsBackToDictionary()
    {
        val pipe = MockTokenPipe("cloud-fallback")
        val fudgedSettings = TruncationSettings(binaryFudgeFactor = 2.0)
        val cloudRef = BinaryContent.CloudReference(uri = "s3://bucket/note.txt", mimeType = "text/plain")
        val content = MultimodalContent(binaryContent = mutableListOf(cloudRef))

        val tokens = pipe.countBinaryTokens(content, fudgedSettings)

        // CloudReference is routed through Dictionary.countTokens(uri), NOT through countBinaryTokens.
        // The binaryFudgeFactor applies only to byte-exact tier-0 — it does NOT apply to URI text counts.
        // The URI token count is whatever the dictionary says; compare to the unfudged call.
        val baselineTokens = pipe.countBinaryTokens(content, settings)
        assertEquals(baselineTokens, tokens)
    }

    @Test
    fun emptyBinaryContentReturnsZeroWithFudgeFactor()
    {
        val pipe = MockTokenPipe("empty-fudged")
        val fudgedSettings = TruncationSettings(binaryFudgeFactor = 1.5)
        val content = MultimodalContent()

        assertEquals(0, pipe.countBinaryTokens(content, fudgedSettings))
    }

    @Test
    fun base64WithPaddingDecodesCorrectly()
    {
        val pipe = MockTokenPipe("b64-padded")
        // "AA==" base64-decodes to a single byte (0x00). Decoded bytes = 1.
        // Tokens = ceil(1 / 4) = 1.
        val padded = "AA=="
        val content = MultimodalContent(binaryContent = mutableListOf(BinaryContent.Base64String(padded, "image/png")))

        val tokens = pipe.countBinaryTokens(content, settings)

        assertEquals(1, tokens)
    }
}
