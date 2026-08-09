package com.TTT.Context

import com.TTT.Pipe.TruncationSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Tests for [Dictionary.countBinaryTokens] — the new entry point that takes List<BinaryBytes> +
 * TruncationSettings and applies the 4-tier decision tree (per-MIME override, external encoder,
 * byte-exact fallback). The complementary `Pipe.countBinaryTokens` test suite is at
 * `src/test/kotlin/CountBinaryTokensTest.kt` and exercises the Pipe-side mapper.
 *
 * The default mode is [Dictionary.BinaryEstimationMode.HYBRID] with an empty MIME override map,
 * so these tests lock the contract that the default behavior is identical to PER_ENCODER_RULE
 * for unknown MIME types.
 */
class DictionaryCountBinaryTokensTest
{
    private val settings = TruncationSettings()

    @Test
    fun dictionaryCountBinaryTokensReturnsZeroForEmptyList()
    {
        val tokens = Dictionary.countBinaryTokens(emptyList(), settings)
        assertEquals(0, tokens)
    }

    @Test
    fun dictionaryCountBinaryTokensUsesByteExactForUnknownMime()
    {
        val items = listOf(Dictionary.BinaryBytes(ByteArray(34_000) { (it and 0xFF).toByte() }, "image/unknown"))
        val tokens = Dictionary.countBinaryTokens(items, settings)
        // 34,000 bytes / 4 bytes-per-token = 8,500 tokens (byte-exact).
        assertEquals(8_500, tokens)
    }

    @Test
    fun dictionaryCountBinaryTokensHonorsMimeOverride()
    {
        val perMimeSettings = TruncationSettings(
            binaryMimeOverride = mapOf("image/png" to 765)
        )
        val items = listOf(Dictionary.BinaryBytes(ByteArray(34_000) { (it and 0xFF).toByte() }, "image/png"))
        val tokens = Dictionary.countBinaryTokens(items, perMimeSettings)
        // Per-MIME override wins over byte-exact. The override value is what we get regardless of byte count.
        assertEquals(765, tokens)
    }

    @Test
    fun dictionaryCountBinaryTokensAppliesFudgeFactor()
    {
        val fudgedSettings = TruncationSettings(binaryFudgeFactor = 2.0)
        val items = listOf(Dictionary.BinaryBytes(ByteArray(40) { (it and 0xFF).toByte() }, "image/unknown"))
        val tokens = Dictionary.countBinaryTokens(items, fudgedSettings)
        // 40 bytes / 4 = 10 tokens base. Fudge factor 2.0 doubles it to 20.
        assertEquals(20, tokens)
    }

    @Test
    fun dictionaryCountBinaryTokensPerMimeTypeModeThrowsOnMissingEntry()
    {
        val strictSettings = TruncationSettings(
            binaryTokenEstimation = Dictionary.BinaryEstimationMode.PER_MIME_TYPE,
            binaryMimeOverride = mapOf("image/png" to 765)
        )
        val items = listOf(Dictionary.BinaryBytes(ByteArray(100), "image/unknown"))
        val ex = assertFailsWith<IllegalArgumentException> {
            Dictionary.countBinaryTokens(items, strictSettings)
        }
        assertTrue(
            ex.message!!.contains("image/unknown"),
            "Exception should name the missing MIME, got: ${ex.message}"
        )
    }

    @Test
    fun dictionaryCountBinaryTokensAcceptsImaginedZeroBytePayload()
    {
        // 0 bytes / 4 = 0 tokens, even with a fudge factor.
        val items = listOf(Dictionary.BinaryBytes(ByteArray(0), "image/png"))
        val tokens = Dictionary.countBinaryTokens(items, settings)
        assertEquals(0, tokens)
    }

    @Test
    fun dictionaryCountBinaryTokensAccumulatesAcrossMultipleItems()
    {
        val items = listOf(
            Dictionary.BinaryBytes(ByteArray(40), "image/unknown"),
            Dictionary.BinaryBytes(ByteArray(80), "image/unknown"),
        )
        val tokens = Dictionary.countBinaryTokens(items, settings)
        // 40 / 4 + 80 / 4 = 10 + 20 = 30 tokens
        assertEquals(30, tokens)
    }

    private class CountingEncoder(val tokensPerChunk: Int) : Dictionary.BpeEncoder
    {
        override fun encode(text: String): IntArray = IntArray(tokensPerChunk)
    }
}
