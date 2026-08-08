package com.TTT.Context

import com.TTT.Pipe.TruncationSettings
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlin.system.measureNanoTime

/**
 * Benchmarks [Dictionary.countTokens] across configurations against a real corpus. The test
 * exercises the same code path that the memory system hits when it tokenizes a large context
 * window, so the timings here are directly comparable to the speed-bottleneck target.
 *
 * The default corpus is the Project Gutenberg Shakespeare file at `/tmp/pg100.txt`. If that
 * file is absent the test is skipped rather than failed — the intent is to give the
 * benchmark a known quantity of roughly 1M tokens, and on machines where the file has
 * already been pulled the timings are reproducible.
 *
 * Each configuration is timed in isolation after JVM warmup; the printed line shows the
 * elapsed milliseconds and the returned token count so a regression in either dimension is
 * visible at a glance.
 */
class DictionaryCountBenchmarkTest
{
    private val corpusPath = "/tmp/pg100.txt"

    /**
     * Sweeps the [TruncationSettings] flags that influence the inner loop of
     * [Dictionary.countTokens] and prints the elapsed wall time and resulting token count
     * for each. Warmup pass uses the default English dictionary on a small slice so the
     * first hot-loop iteration doesn't pay JIT/IO cost; each timed call follows.
     */
    @Test
    fun benchmarkCountTokensConfigurations()
    {
        val text = loadCorpus() ?: return

        // Warmup — exercise the lazy default-instance load + JIT the hot path.
        Dictionary.countTokens(text.take(50_000))

        val configurations = listOf(
            BenchCase(
                label = "default (subword-first, favor-whole-words)",
                settings = TruncationSettings()
            ),
            BenchCase(
                label = "favorWholeWords=false",
                settings = TruncationSettings().apply { favorWholeWords = false }
            ),
            BenchCase(
                label = "countSubWordsInFirstWord=false",
                settings = TruncationSettings().apply { countSubWordsInFirstWord = false }
            ),
            BenchCase(
                label = "splitForNonWordChar=false",
                settings = TruncationSettings().apply { splitForNonWordChar = false }
            ),
            BenchCase(
                label = "alwaysSplitIfWholeWordExists=true",
                settings = TruncationSettings().apply { alwaysSplitIfWholeWordExists = true }
            ),
            BenchCase(
                label = "countSubWordsIfSplit=true",
                settings = TruncationSettings().apply { countSubWordsIfSplit = true }
            ),
            BenchCase(
                label = "countOnlyFirstWordFound=true",
                settings = TruncationSettings().apply { countOnlyFirstWordFound = true }
            ),
            BenchCase(
                label = "nonWordSplitCount=2",
                settings = TruncationSettings().apply { nonWordSplitCount = 2 }
            ),
            BenchCase(
                label = "nonWordSplitCount=8",
                settings = TruncationSettings().apply { nonWordSplitCount = 8 }
            ),
            BenchCase(
                label = "tokenCountingBias=0.10",
                settings = TruncationSettings().apply { tokenCountingBias = 0.10 }
            )
        )

        val corpusChars = text.length
        println("=== Dictionary.countTokens benchmark ===")
        println("Corpus: $corpusPath ($corpusChars chars)")

        for(case in configurations)
        {
            // Resolve the dictionary once per case so the cost of `resolveDictionary` (cached
            // after the first call) does not pollute the token-counting measurement.
            Dictionary.resolveDictionary(case.settings)

            val elapsedNanos = measureNanoTime {
                Dictionary.countTokens(text, case.settings)
            }

            val tokenCount = Dictionary.countTokens(text, case.settings)
            val elapsedMillis = elapsedNanos / 1_000_000.0
            val charsPerSecond = if(elapsedNanos > 0)
            {
                corpusChars * 1_000_000_000.0 / elapsedNanos
            }
            else 0.0

            println(
                String.format(
                    "%-55s | %8.2f ms | %8d tokens | %10.0f chars/s",
                    case.label,
                    elapsedMillis,
                    tokenCount,
                    charsPerSecond
                )
            )
        }
    }

    /**
     * Times the simple-string overload to surface the cost of the [Dictionary.countTokens]
     * defaults — useful as a baseline before evaluating the configuration matrix above.
     */
    @Test
    fun benchmarkCountTokensDefaultOverload()
    {
        val text = loadCorpus() ?: return

        // Warmup.
        Dictionary.countTokens(text.take(50_000))

        val corpusChars = text.length
        val elapsedNanos = measureNanoTime { Dictionary.countTokens(text) }
        val tokenCount = Dictionary.countTokens(text)
        val elapsedMillis = elapsedNanos / 1_000_000.0
        val charsPerSecond = if(elapsedNanos > 0)
        {
            corpusChars * 1_000_000_000.0 / elapsedNanos
        }
        else 0.0

        println("=== Dictionary.countTokens default overload ===")
        println(
            String.format(
                "default                                              | %8.2f ms | %8d tokens | %10.0f chars/s",
                elapsedMillis,
                tokenCount,
                charsPerSecond
            )
        )
    }

    /**
     * Loads the benchmark corpus if it is present on disk. Returns null when the file is
     * absent so the calling test can skip via JUnit's Assume facility rather than fail.
     */
    private fun loadCorpus(): String?
    {
        val file = java.io.File(corpusPath)
        assumeTrue("Benchmark corpus not found at $corpusPath — skipping", file.exists() && file.length() > 0)
        return file.readText()
    }

    private data class BenchCase(val label: String, val settings: TruncationSettings)
}