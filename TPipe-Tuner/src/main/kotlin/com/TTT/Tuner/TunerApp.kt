package com.TTT.Tuner

import com.TTT.Context.Dictionary
import com.TTT.Pipe.TruncationSettings
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.math.abs

/**
 * Entry point for the TPipe tuner application.
 * This tool iterates through a wide range of combinations for Token Counting TruncationSettings
 * to find the most accurate configuration compared to an LLM tokenizer's actual output.
 * It also computes a token counting bias fine-tuning parameter.
 */
fun main(args: Array<String>) {
    var testString = ""
    var expectedTokens = -1

    // 1. Simple manual parsing of command line args
    var i = 0
    while(i < args.size) {
        when(args[i]) {
            "--test-string", "-s" -> {
                if(i + 1 < args.size) {
                    testString = args[i + 1]
                    i++
                }
            }
            "--expected-tokens", "-t" -> {
                if(i + 1 < args.size) {
                    expectedTokens = args[i + 1].toIntOrNull() ?: -1
                    i++
                }
            }
        }
        i++
    }

    if(testString.isEmpty() || expectedTokens <= 0) {
        System.err.println("Usage: tuner --test-string \"<string>\" --expected-tokens <integer>")
        System.err.println("Example: tuner --test-string \"Hello, world!\" --expected-tokens 4")
        System.exit(1)
    }

    println("Starting Tuner with Expected Tokens: $expectedTokens")
    println("Input Text Length: ${testString.length} chars")

    // 2. Setup testing ranges for sane limits
    val booleans = listOf(true, false)
    val nonWordSplitCounts = (1..8).toList()

    var bestConfig: TruncationSettings? = null
    var bestDiff = Int.MAX_VALUE
    var bestCount = -1

    // 3. Iterate over all reasonable combinations of the parameters
    for(countSubWordsInFirstWord in booleans) {
        for(favorWholeWords in booleans) {
            for(countOnlyFirstWordFound in booleans) {
                for(splitForNonWordChar in booleans) {
                    for(alwaysSplitIfWholeWordExists in booleans) {
                        for(countSubWordsIfSplit in booleans) {
                            for(nonWordSplitCount in nonWordSplitCounts) {

                                val settings = TruncationSettings(
                                    countSubWordsInFirstWord = countSubWordsInFirstWord,
                                    favorWholeWords = favorWholeWords,
                                    countOnlyFirstWordFound = countOnlyFirstWordFound,
                                    splitForNonWordChar = splitForNonWordChar,
                                    alwaysSplitIfWholeWordExists = alwaysSplitIfWholeWordExists,
                                    countSubWordsIfSplit = countSubWordsIfSplit,
                                    nonWordSplitCount = nonWordSplitCount,
                                    tokenCountingBias = 0.0 // Keep bias at 0 for initial scan
                                )

                                val count = Dictionary.countTokens(testString, settings)
                                val diff = abs(count - expectedTokens)

                                // We prefer a diff that is closer to the expected tokens.
                                // If tied, we prefer one that slightly UNDERESTIMATES tokens rather than overestimates,
                                // because underestimating tokens is safer for truncation and preventing overflow.
                                if(diff < bestDiff) {
                                    bestDiff = diff
                                    bestCount = count
                                    bestConfig = settings.copy() // Deep copy the data class
                                } else if(diff == bestDiff && bestConfig != null) {
                                    // Tie breaker: If the current diff is the same, favor the one that is closer to underestimating
                                    // wait, if diff is the same, one is +diff and one is -diff.
                                    // We want the one that is `count < expectedTokens`
                                    if(count < expectedTokens && bestCount > expectedTokens) {
                                         bestCount = count
                                         bestConfig = settings.copy()
                                    }
                                }

                                // Perfect match found without bias
                                if(bestDiff == 0) {
                                    break
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if(bestConfig == null) {
        System.err.println("Failed to find any viable combinations.")
        System.exit(1)
    }

    println("Best base configuration found with count $bestCount (Diff: $bestDiff)")

    // 4. Fine-Tune with Token Counting Bias
    var finalConfig = bestConfig!!.copy()

    if(bestCount != expectedTokens) {
        println("Applying fine-grained token counting bias...")
        var currentBias = 0.0
        val targetTokens = expectedTokens.toDouble()

        // Use fine-grained tuning to calculate exactly what percentage bias gets us to the target.
        // Math.round(tokenCount * (1.0 + bias)) = expectedTokens
        // tokenCount * (1.0 + bias) ~ expectedTokens
        // 1.0 + bias ~ expectedTokens / tokenCount
        // bias ~ (expectedTokens / tokenCount) - 1.0

        val idealBias = (expectedTokens.toDouble() / bestCount.toDouble()) - 1.0

        // Let's test standard increments close to idealBias to ensure Math.round behaves as expected
        val searchRange = -0.5..0.5
        var bestBiasDiff = Int.MAX_VALUE
        var bestBiasCount = -1
        var bestBias = 0.0

        // Scan around idealBias with high precision
        var testBias = idealBias - 0.05
        val maxTestBias = idealBias + 0.05
        val step = 0.001 // 0.1% increments

        while(testBias <= maxTestBias) {
            val settingsWithBias = finalConfig.copy(tokenCountingBias = testBias)
            val biasCount = Dictionary.countTokens(testString, settingsWithBias)
            val diff = abs(biasCount - expectedTokens)

            if(diff < bestBiasDiff) {
                bestBiasDiff = diff
                bestBiasCount = biasCount
                bestBias = testBias
            }
            testBias += step
        }

        finalConfig = finalConfig.copy(tokenCountingBias = bestBias)
        println("Optimal Bias Applied: ${String.format("%.4f", bestBias)} resulting in $bestBiasCount tokens (Diff: $bestBiasDiff)")
    } else {
        println("No bias tuning required.")
    }

    // 5. Output JSON schema configuration
    val json = Json { prettyPrint = true }
    val jsonString = json.encodeToString(finalConfig)

    println("\n================ OPTIMAL CONFIGURATION ================")
    println(jsonString)
    println("=======================================================\n")
}
