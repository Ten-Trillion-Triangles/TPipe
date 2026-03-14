package com.TTT.Tuner

import com.TTT.Context.Dictionary
import com.TTT.Pipe.TruncationSettings
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.math.abs

private val DEFAULT_TEST_STRING = """
The quick brown fox jumps over the lazy dog.
Now, let's test some less common vocabulary: defenestration, floccinaucinihilipilification, antidisestablishmentarianism, and pneumonoultramicroscopicsilicovolcanoconiosis.

How about nonsense or out-of-vocabulary (OOV) words? Twas brillig, and the slithy toves did gyre. Asdfghjkl qwertyuiop zxcvbnm123! xqxqxqxq ptakh.

Let's stress test numbers and formats: 42, 0, -273.15, 6.022e23, NaN, Infinity.
IP Address: 192.168.255.255, IPv6: 2001:0db8:85a3:0000:0000:8a2e:0370:7334.
Dates and Times: 2026-03-14T09:32:42Z, 14/03/2026, March 14th, 2026.
Phone: +1-(800)-555-0199 ext. 1234.

Symbols, Punctuation, and Currency:
!@#$%^&*()_+-=[]{}|;':",./<>?`~\\
$1,000.00, €50.99, ¥10000, £20, ₹500.

Programming syntaxes and casings:
camelCaseVariable, PascalCaseClass, snake_case_function, kebab-case-id, sPoNgEbObCaSe, SCREAMING_SNAKE_CASE.
{"json_key": ["value1", "value2\n", null, true]}
<html lang="en"><head><title>Test</title></head><body>hello</body></html>
def foo(x: int) -> int: return x ** 2

URLs and Emails:
john.doe+spamfilter@subdomain.co.uk
https://www.example-site.com:8080/path/to/resource.html?query=token&sort=desc#fragment-identifier

Unicode, Accents, and Multilingual Support:
café, naïve, jalapeño, façade, über.
Russian: Привет, мир!
Chinese: 你好，世界！
Japanese: こんにちは世界
Arabic: مرحبا بالعالم
Korean: 안녕하세요

Emojis and Zero-Width Joiners (ZWJ):
😀🚀🤖✨
Family (ZWJ sequence): 👨‍👩‍👧‍👦
Facepalm with skin tone (Modifiers): 🤦🏽‍♀️
Kaomoji: ¯\_(ツ)_/¯ (╯°□°）╯︵ ┻━┻

Whitespace anomalies (spaces, tabs, newlines):
\tLeading tab.    Four spaces.
Multiple\n\n\nnewlines and \r\n carriage returns.
Trailing spaces ->

As a default test string, that if a test string is not supplied. This is what gets passed through. Since this is a pretty solid and universal stress tester for most tokenizers this string should be generally optimized to get the tuning correct most of the time.
""".trimIndent()

/**
 * Entry point for the TPipe tuner application.
 * This tool iterates through a wide range of combinations for Token Counting TruncationSettings
 * to find the most accurate configuration compared to an LLM tokenizer's actual output.
 * It also computes a token counting bias fine-tuning parameter.
 */
fun main(args: Array<String>) {
    var testString = ""
    var expectedTokens = -1

    // Check if args are provided via file (from tuner.sh)
    val argsFile = System.getProperty("tunerArgsFile")
    val actualArgs = if (argsFile != null) {
        try {
            // Read file and split by separator
            val content = java.io.File(argsFile).readText()
            val split = content.split("---ARG---").toTypedArray()
            System.err.println("DEBUG: Read ${split.size} args from file")
            split.forEachIndexed { i, arg ->
                System.err.println("DEBUG: arg[$i] = ${arg.take(50)}... (${arg.length} chars)")
            }
            split
        } catch (e: Exception) {
            System.err.println("ERROR: Could not read args file: $argsFile")
            System.err.println("ERROR: ${e.message}")
            args
        }
    } else {
        args
    }

    // 1. Simple manual parsing of command line args
    var i = 0
    while(i < actualArgs.size) {
        when(actualArgs[i]) {
            "--test-string", "-s" -> {
                if(i + 1 < actualArgs.size) {
                    testString = actualArgs[i + 1]
                    i++
                }
            }
            "--expected-tokens", "-t" -> {
                if(i + 1 < actualArgs.size) {
                    expectedTokens = actualArgs[i + 1].toIntOrNull() ?: -1
                    i++
                }
            }
        }
        i++
    }

    if (testString.isBlank()) {
        println("No test string supplied; using the built-in stress-test string.")
        testString = DEFAULT_TEST_STRING
    }

    if(expectedTokens <= 0) {
        System.err.println("Usage: tuner --expected-tokens <integer> [--test-string \"<string>\"]")
        System.err.println("Omit --test-string or provide an empty value to use the default stress-test string.")
        System.exit(1)
    }

    println("Starting Tuner with Expected Tokens: $expectedTokens")
    println("Input Text Length: ${testString.length} chars")
    
    // Estimate time for large inputs
    if(testString.length > 1000) {
        println("Note: Large input detected. This may take 1-2 minutes...")
    }

    // 2. Setup testing ranges for sane limits
    val booleans = listOf(true, false)
    val nonWordSplitCounts = (1..8).toList()

    var bestConfig: TruncationSettings? = null
    var bestDiff = Int.MAX_VALUE
    var bestCount = -1
    
    var combinationsTested = 0
    val totalCombinations = 512

    // 3. Iterate over all reasonable combinations of the parameters
    for(countSubWordsInFirstWord in booleans) {
        for(favorWholeWords in booleans) {
            for(countOnlyFirstWordFound in booleans) {
                for(splitForNonWordChar in booleans) {
                    for(alwaysSplitIfWholeWordExists in booleans) {
                        for(countSubWordsIfSplit in booleans) {
                            for(nonWordSplitCount in nonWordSplitCounts) {
                                
                                combinationsTested++
                                
                                // Progress indicator for large inputs
                                if(testString.length > 1000 && combinationsTested % 64 == 0) {
                                    System.err.print(".")
                                    System.err.flush()
                                }

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
                            if(bestDiff == 0) break
                        }
                        if(bestDiff == 0) break
                    }
                    if(bestDiff == 0) break
                }
                if(bestDiff == 0) break
            }
            if(bestDiff == 0) break
        }
        if(bestDiff == 0) break
    }
    
    if(testString.length > 1000) {
        System.err.println() // New line after progress dots
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
