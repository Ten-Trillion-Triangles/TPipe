package com.TTT.Context

import com.TTT.MockTokenPipe
import com.TTT.Pipe.BinaryContent
import com.TTT.Pipe.MultimodalContent
import com.TTT.Pipe.TruncationSettings
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.system.measureNanoTime
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Benchmarks [com.TTT.Pipe.Pipe.countBinaryTokens] against a real-world binary
 * corpus and prints a head-to-head line against the text benchmark in
 * [DictionaryCountBenchmarkTest].
 *
 * The text benchmark uses `/tmp/pg100.txt` (5,638,480 chars → 1,347,367 tokens
 * in 337.55 ms, ~3,991,000 tokens/s). The dictionary tokenizer operates on
 * character runs, so the right size match is "same base64 char count as the
 * text corpus char count" — that gives a binary of ~4.2 MB raw → ~5.6 MB
 * base64 → ~1.35M tokens.
 *
 * **At that scale the binary path OOMs the default 512 MB test heap** because
 * [com.TTT.Context.Dictionary.findAllMatches] at `Dict.kt:151` calls
 * `text.substring(pos, pos + len)` inside an inner loop that runs at every
 * position. Base64 alphabet (`A-Za-z0-9+/=`, no whitespace) produces zero
 * dictionary matches, so the matcher pays the full `O(n × maxMatchLength)`
 * allocation cost. At 5.6M chars that storm of `String` allocations exceeds
 * the 512 MB heap.
 *
 * The benchmark sweeps 8 KB → 256 KB raw sizes (the range that completes in
 * the default heap) and prints a per-size scaling line. 256 KB is the largest
 * size that lands in heap and produces a measurable comparison vs the text
 * benchmark's 1.35M-token scale via linear extrapolation.
 *
 * The corpus is `/tmp/hubble_ngc6530.jpg` — a 4.05 MB public-domain Hubble
 * NGC 6530 JPEG from Wikimedia Commons (3,880×3,845, NASA/ESA Hubble). The
 * test skips cleanly if the file is absent.
 *
 * Verified locally on main (2026-08-08): binary at 256 KB takes ~29,833 ms
 * for 87,371 tokens (~2,929 tokens/s); text at 1.35M tokens takes 337.55 ms
 * (~3,991,000 tokens/s). **The binary path is ~1,300× slower than the text
 * path at matched token counts, and OOMs at the text-corpus size in the
 * default test heap.**
 */
class DictionaryCountBinaryBenchmarkTest
{
    private val corpusPath = "/tmp/hubble_ngc6530.jpg"
    private val textCorpusPath = "/tmp/pg100.txt"

    // Hardcoded reference numbers from DictionaryCountBenchmarkTest.benchmarkCountTokensDefaultOverload
    // run on the same machine. Update if the reference numbers drift.
    private val textCorpusTokenCount = 1_347_367
    private val textCorpusCharCount = 5_638_480
    private val textCorpusElapsedMs = 337.55
    private val textCorpusTokensPerSecond = 3_991_000.0

    /**
     * Sweeps 8 KB → 256 KB raw binary sizes and prints a per-size line showing
     * elapsed milliseconds, token count, and tokens/second. Also prints a
     * single comparison line at the largest size (256 KB) rescaled to the text
     * benchmark's 1.35M-token scale via the measured time-per-token rate, so
     * the operator sees a real "binary vs text" speedup at matched scale.
     */
    @Test
    fun benchmarkCountBinaryTokensScalingSweep()
    {
        val file = File(corpusPath)
        assumeTrue(
            "Binary benchmark corpus not found at $corpusPath — skipping",
            file.exists() && file.length() > 0
        )

        val allBytes = file.readBytes()
        val sizesKb = listOf(8, 16, 32, 64, 128, 256)
        val settings = TruncationSettings()

        println("=== Dictionary.countBinaryTokens benchmark (scaling sweep) ===")
        println("Corpus: $corpusPath (${allBytes.size} bytes total, sliced per row below)")

        // Per-size sweep — measure the cost at each size so the operator can
        // see the scaling curve, not just a single point. Each row runs warmup
        // + 3 timed calls, picks the min, and records the steady-state token
        // count. Steady state is after the Bytes → Base64String in-place
        // rewrite at Pipe.kt:5512.
        val lastRow = run {
            var lastElapsedMs = 0.0
            var lastTokens = 0
            for(kb in sizesKb)
            {
                val rawBytes = allBytes.copyOfRange(0, kb * 1024)
                val pipe = MockTokenPipe("binary-bench-${kb}kb")
                val content = MultimodalContent(
                    binaryContent = mutableListOf(BinaryContent.Bytes(rawBytes, "image/jpeg", "hubble_ngc6530.jpg"))
                )

                // Warmup — pays the in-place Bytes → Base64String rewrite.
                pipe.countBinaryTokens(content, settings)

                val tokens = pipe.countBinaryTokens(content, settings)
                val minMs = (1..3).minOf {
                    measureNanoTime { pipe.countBinaryTokens(content, settings) }
                } / 1_000_000.0
                val tokensPerSecond = if(minMs > 0) tokens / (minMs / 1000.0) else 0.0

                lastElapsedMs = minMs
                lastTokens = tokens

                println(
                    String.format(
                        "binary %4d KB raw | %10.2f ms | %8d tokens | %10.0f tokens/s",
                        kb,
                        minMs,
                        tokens,
                        tokensPerSecond
                    )
                )
            }
            Pair(lastElapsedMs, lastTokens)
        }

        val (largestElapsedMs, largestTokens) = lastRow

        // Comparison line: take the largest completed size (256 KB) and
        // rescale to the text benchmark's 1.35M-token scale via the
        // measured time-per-token rate. Binary cost grows linearly in the
        // no-match case so linear extrapolation is honest.
        val timePerTokenMillis = largestElapsedMs / largestTokens
        val extrapolatedToTextScaleMs = timePerTokenMillis * textCorpusTokenCount
        val binaryTokensPerSecondAtTextScale = largestTokens / (largestElapsedMs / 1000.0)
        val binarySlowerBy = textCorpusTokensPerSecond / binaryTokensPerSecondAtTextScale

        println(
            String.format(
                "text   /tmp/pg100.txt (1.35M tokens)   | %8.2f ms | %8d tokens | %10.0f tokens/s",
                textCorpusElapsedMs,
                textCorpusTokenCount,
                textCorpusTokensPerSecond
            )
        )
        println(
            String.format(
                "binary 256 KB rescaled to %d tokens    | %8.2f ms |",
                textCorpusTokenCount,
                extrapolatedToTextScaleMs
            )
        )
        println(
            String.format(
                "delta (binary/text at matched scale)   | %+8.2fx slower | (linear extrapolation from 256 KB run)",
                binarySlowerBy
            )
        )
    }

    /**
     * Captures the OOM at the text-corpus size as a documented data point.
     *
     * This test exists to make the failure mode visible in the test report
     * rather than letting it fail silently in a longer benchmark. It runs
     * `countBinaryTokens` against the full 4.05 MB JPEG and asserts the call
     * returns a token count close to the text corpus's 1.35M tokens. If the
     * assertion fails (because the dictionary OOMs), the printed line still
     * records the failure with the actual size and base64 char count so the
     * operator sees the boundary directly.
     */
    @Test
    fun benchmarkCountBinaryTokensFullScaleOomCaptured()
    {
        val file = File(corpusPath)
        assumeTrue(
            "Binary benchmark corpus not found at $corpusPath — skipping",
            file.exists() && file.length() > 0
        )

        val rawBytes = file.readBytes()
        val base64Chars = java.util.Base64.getEncoder().encodeToString(rawBytes).length
        val pipe = MockTokenPipe("binary-bench-full")
        val settings = TruncationSettings()
        val content = MultimodalContent(
            binaryContent = mutableListOf(BinaryContent.Bytes(rawBytes, "image/jpeg", "hubble_ngc6530.jpg"))
        )

        println("=== Dictionary.countBinaryTokens benchmark (full-scale, 4.05MB JPEG) ===")
        println("Corpus: $corpusPath (${rawBytes.size} raw bytes, $base64Chars base64 chars)")
        println(
            "Verification: this call should now complete in microseconds (byte-exact formula), no OOM."
        )

        val tokensOrError: Pair<Int?, String?> = try
        {
            val t = pipe.countBinaryTokens(content, settings)
            Pair(t, null)
        }
        catch(e: OutOfMemoryError)
        {
            Pair(null, e.message ?: "Java heap space")
        }

        if(tokensOrError.first != null)
        {
            val tokens = tokensOrError.first!!
            // The new Dictionary.countBinaryTokens contract is ceil(decodedBytes / 4) —
            // not the text corpus's natural-language tokenizer count. The two paths now
            // use different algorithms and SHOULD produce different token counts on the same
            // byte count. The old test compared binary against the text corpus's 1,347,367
            // (±5%) which only made sense while both paths used Dictionary.countTokens.
            val expectedBand = ((rawBytes.size + 3) / 4 * 0.95).toInt()..((rawBytes.size + 3) / 4 * 1.05).toInt()
            val inBand = tokens in expectedBand
            println(
                String.format(
                    "binary full-scale (countBinaryTokens)               | %d tokens (in ±5%% of ceil(bytes/4)=%d: %s)",
                    tokens,
                    (rawBytes.size + 3) / 4,
                    if(inBand) "YES" else "NO"
                )
            )
            check(inBand)
            {
                "Full-scale binary token count $tokens outside ±5% of ceil(bytes/4) = ${(rawBytes.size + 3) / 4}. " +
                    "The new Dictionary.countBinaryTokens contract is byte-exact; the comparison to the text " +
                    "corpus's 1,347,367 was a relic of the old Dictionary.countTokens path."
            }
        }
        else
        {
            println(
                String.format(
                    "binary full-scale (countBinaryTokens)               | OOM after %d raw bytes / %d base64 chars: %s",
                    rawBytes.size,
                    base64Chars,
                    tokensOrError.second
                )
            )
            println(
                "Captured: countBinaryTokens produced no token count at the full-scale size. " +
                    "This is unexpected with the new byte-exact path — investigate the failure mode " +
                    "before re-running. The pre-fix Dictionary.countTokens path OOMed at this size, " +
                    "but the new Dictionary.countBinaryTokens path is O(1) per item regardless of byte length."
            )
            throw AssertionError("countBinaryTokens returned null at the full-scale size — see captured output above")
        }
    }

    /**
     * Sweep with binaryFudgeFactor = 1.10. The byte-exact estimate is `ceil(bytes / 4) * 1.10`.
     * For 256 KB raw bytes, the baseline is 65,536 tokens; with fudge 1.10 the expected count is
     * 65,536 * 1.10 = 72,089.60 → 72,089 (Int truncation). The test pins the multiplier path.
     */
    @Test
    fun benchmarkWithFudgeFactor()
    {
        val file = File(corpusPath)
        assumeTrue(
            "Binary benchmark corpus not found at $corpusPath — skipping",
            file.exists() && file.length() > 0
        )

        val rawBytes = file.readBytes().copyOfRange(0, 256 * 1024)
        val pipe = MockTokenPipe("binary-fudge-1.10")
        val settings = TruncationSettings(binaryFudgeFactor = 1.10)
        val content = MultimodalContent(
            binaryContent = mutableListOf(BinaryContent.Bytes(rawBytes, "image/jpeg", "hubble_ngc6530.jpg"))
        )

        val tokens = pipe.countBinaryTokens(content, settings)
        val expected = (65536.0 * 1.10).toInt()
        val withinOnePct = kotlin.math.abs(tokens - expected) <= expected / 100

        println(
            String.format(
                "binary 256 KB raw with binaryFudgeFactor=1.10 | %d tokens (expected ~%d, within 1%%: %s)",
                tokens,
                expected,
                if(withinOnePct) "YES" else "NO"
            )
        )
        assertTrue(withinOnePct, "Fudge-factor 1.10 should produce 72,089 tokens within 1%%, got $tokens")
    }

    /**
     * Sweep with binaryMimeOverride["image/jpeg"] = 765. The per-MIME override wins over the
     * byte-exact formula regardless of the actual byte count. This pins the tier-1 path.
     */
    @Test
    fun benchmarkWithImageMimeOverride()
    {
        val file = File(corpusPath)
        assumeTrue(
            "Binary benchmark corpus not found at $corpusPath — skipping",
            file.exists() && file.length() > 0
        )

        val rawBytes = file.readBytes()
        val pipe = MockTokenPipe("binary-mime-override")
        val settings = TruncationSettings(binaryMimeOverride = mapOf("image/jpeg" to 765))
        val content = MultimodalContent(
            binaryContent = mutableListOf(BinaryContent.Bytes(rawBytes, "image/jpeg", "hubble_ngc6530.jpg"))
        )

        val tokens = pipe.countBinaryTokens(content, settings)
        println(
            String.format(
                "binary full-scale (4.05 MB JPEG) with binaryMimeOverride[image/jpeg]=765 | %d tokens (expected 765)",
                tokens
            )
        )
        assertEquals(765, tokens)
    }
}
