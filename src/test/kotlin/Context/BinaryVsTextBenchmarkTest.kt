package com.TTT.Context

import com.TTT.MockTokenPipe
import com.TTT.Pipe.BinaryContent
import com.TTT.Pipe.MultimodalContent
import com.TTT.Pipe.TruncationSettings
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.system.measureNanoTime

/**
 * Fresh apples-to-apples benchmark: binary vs text token counting at matched byte sizes.
 * Captures the printed scaling table to stdout so the run is auditable.
 *
 * Corpora:
 *   /tmp/hubble_ngc6530.jpg  4.05 MB public-domain Hubble NGC 6530 JPEG (NASA/ESA)
 *   /tmp/pg100.txt           5.64 MB Project Gutenberg text
 *
 * Skips cleanly if either file is absent.
 */
class BinaryVsTextBenchmarkTest
{
    private val jpegPath = "/tmp/hubble_ngc6530.jpg"
    private val textPath = "/tmp/pg100.txt"
    private val warmup = 2
    private val timed = 5

    private fun fmt(n: Long): String = "%,d".format(n)

    private data class Row(val kb: Int, val tokens: Int, val minMs: Double)

    private fun medianMs(block: () -> Unit): Double {
        val samples = (1..timed).map { measureNanoTime(block) }.sorted()
        val median = samples[samples.size / 2]
        return median / 1_000_000.0
    }

    @Test
    fun benchmarkBinaryVsTextScalingSweep()
    {
        val jpegFile = File(jpegPath)
        val textFile = File(textPath)
        assumeTrue(
            "Benchmark corpora missing: $jpegPath or $textPath",
            jpegFile.exists() && jpegFile.length() > 0 && textFile.exists() && textFile.length() > 0
        )

        val jpegBytes = jpegFile.readBytes()
        val text = textFile.readText()
        val settings = TruncationSettings()  // default HYBRID, no override -> byte-exact
        val pipe = MockTokenPipe("bin-vs-text")

        val sizesKb = listOf(8, 16, 32, 64, 128, 256, 512, 1024)

        println("=== Binary vs Text Token Counting Benchmark (v1.2.0) ===")
        println("JVM: ${System.getProperty("java.version")} (${System.getProperty("java.vm.name")})")
        println("Binary corpus: $jpegPath (${fmt(jpegBytes.size.toLong())} bytes)")
        println("Text corpus:   $textPath (${fmt(text.length.toLong())} chars)")
        println("Iterations: $timed timed runs per size, picking the median (warmup=$warmup)")
        println()

        val binaryRows = mutableListOf<Row>()
        val textRows = mutableListOf<Row>()
        for (kb in sizesKb)
        {
            // Binary row.
            val binSlice = jpegBytes.copyOfRange(0, (kb * 1024).coerceAtMost(jpegBytes.size))
            val binContent = MultimodalContent(binaryContent = mutableListOf(BinaryContent.Bytes(binSlice, "image/jpeg")))
            // Warmup.
            repeat(warmup) { pipe.countBinaryTokens(binContent, settings) }
            val binTokens = pipe.countBinaryTokens(binContent, settings)
            val binMs = medianMs { pipe.countBinaryTokens(binContent, settings) }
            binaryRows += Row(kb, binTokens, binMs)

            // Text row (same KB by char count).
            val textSlice = text.substring(0, (kb * 1024).coerceAtMost(text.length))
            repeat(warmup) { Dictionary.countTokens(textSlice, settings) }
            val textTokens = Dictionary.countTokens(textSlice, settings)
            val textMs = medianMs { Dictionary.countTokens(textSlice, settings) }
            textRows += Row(kb, textTokens, textMs)
        }

        println("=== Scaling sweep (per-size median time) ===")
        println("  size    binary ms   binary tokens   binary tokens/s   text ms   text tokens   text tokens/s")
        for (i in sizesKb.indices)
        {
            val b = binaryRows[i]
            val t = textRows[i]
            val bTokPerSec = if (b.minMs > 0) b.tokens / (b.minMs / 1000.0) else 0.0
            val tTokPerSec = if (t.minMs > 0) t.tokens / (t.minMs / 1000.0) else 0.0
            println(String.format(
                "  %4d KB   %8.3f   %12s   %14.0f   %7.3f   %11s   %13.0f",
                b.kb, b.minMs, fmt(b.tokens.toLong()), bTokPerSec,
                t.minMs, fmt(t.tokens.toLong()), tTokPerSec,
            ))
        }

        println()
        println("=== Full-scale (entire corpus) ===")
        val fullBinContent = MultimodalContent(binaryContent = mutableListOf(BinaryContent.Bytes(jpegBytes, "image/jpeg")))
        repeat(warmup) { pipe.countBinaryTokens(fullBinContent, settings) }
        val fullBinTokens = pipe.countBinaryTokens(fullBinContent, settings)
        val fullBinMs = medianMs { pipe.countBinaryTokens(fullBinContent, settings) }

        repeat(warmup) { Dictionary.countTokens(text, settings) }
        val fullTextTokens = Dictionary.countTokens(text, settings)
        val fullTextMs = medianMs { Dictionary.countTokens(text, settings) }

        val fullBinTokPerSec = if (fullBinMs > 0) fullBinTokens / (fullBinMs / 1000.0) else 0.0
        val fullTextTokPerSec = if (fullTextMs > 0) fullTextTokens / (fullTextMs / 1000.0) else 0.0
        println("  binary: ${fmt(fullBinTokens.toLong())} tokens in ${"%.3f".format(fullBinMs)} ms (${"%.0f".format(fullBinTokPerSec)} tokens/s)")
        println("  text:   ${fmt(fullTextTokens.toLong())} tokens in ${"%.3f".format(fullTextMs)} ms (${"%.0f".format(fullTextTokPerSec)} tokens/s)")
        println("  binary speedup vs text at full scale: ${"%.2f".format(fullBinTokPerSec / fullTextTokPerSec)}x")

        println()
        println("=== Token-count parity (matched slice size) ===")
        val r256 = binaryRows[5]
        val t256 = textRows[5]
        println("  binary 256 KB raw -> ${fmt(r256.tokens.toLong())} tokens (= ceil(bytes/4))")
        println("  text 256 KB chars -> ${fmt(t256.tokens.toLong())} tokens (BPE on natural language)")
        println("  ratio text/binary (256 KB): ${"%.2f".format(t256.tokens.toDouble() / r256.tokens)}")
        println()
        println("  (text token count is naturally lower than ceil(bytes/4) because BPE compresses")
        println("   repeated English words into shared tokens; for binary the byte-exact formula has")
        println("   no such compression opportunity.)")
    }
}
