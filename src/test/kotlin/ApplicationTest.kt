package com.TTT

import com.TTT.Context.Dictionary
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlin.test.Test
import kotlin.test.assertEquals

class ApplicationTest {

    @Test
    fun testRoot() = testApplication {
        application {
            module()
        }
        client.get("/").apply {
            assertEquals(HttpStatusCode.OK, status)
        }
    }

    @Test
    fun countTokens() {
        val runtime = Runtime.getRuntime()
        val mb = 1024 * 1024
        
        runtime.gc()
        val beforeMemory = runtime.totalMemory() - runtime.freeMemory()
        
        val text = "This is a test of the number of tokens this string happens to have"
        val tokenCount = Dictionary.countTokens(text)
        
        runtime.gc()
        val afterMemory = runtime.totalMemory() - runtime.freeMemory()
        val memoryUsed = (afterMemory - beforeMemory) / mb
        
        println("=== REFACTORED HASHSET IMPLEMENTATION ===")
        println("Token count: $tokenCount")
        println("Memory used: ${memoryUsed}MB")
        println("Total memory: ${runtime.totalMemory() / mb}MB")
        println("Used memory: ${(runtime.totalMemory() - runtime.freeMemory()) / mb}MB")
    }

    @Test
    fun tokenizationComparison() {
        val testTexts = listOf(
            "Hello world! This is a test.",
            "The quick brown fox jumps over the lazy dog.",
            "AI/ML models use sub-word tokenization for better performance.",
            "GPT-4 tokenizes differently than BERT or T5 models.",
            "Special chars: @#$%^&*()_+-=[]{}|;':,.<>?/~`",
            "Mixed case: CamelCase snake_case kebab-case UPPERCASE lowercase"
        )
        
        val configurations = listOf(
            "Default" to mapOf<String, Any>(),
            "GPT-like" to mapOf(
                "countSubWordsInFirstWord" to true,
                "favorWholeWords" to false,
                "splitForNonWordChar" to true,
                "nonWordSplitCount" to 3
            ),
            "BERT-like" to mapOf(
                "countSubWordsInFirstWord" to true,
                "favorWholeWords" to true,
                "countOnlyFirstWordFound" to false,
                "splitForNonWordChar" to false
            ),
            "Conservative" to mapOf(
                "favorWholeWords" to true,
                "countOnlyFirstWordFound" to true,
                "splitForNonWordChar" to false
            ),
            "Aggressive" to mapOf(
                "countSubWordsInFirstWord" to true,
                "favorWholeWords" to false,
                "alwaysSplitIfWholeWordExists" to true,
                "countSubWordsIfSplit" to true,
                "nonWordSplitCount" to 2
            )
        )
        
        println("=== TOKENIZATION COMPARISON ===")
        println("Text samples vs different tokenization configurations:\n")
        
        for ((configName, config) in configurations) {
            println("--- $configName Configuration ---")
            
            for (text in testTexts) {
                val tokenCount = Dictionary.countTokens(
                    text = text,
                    countSubWordsInFirstWord = config["countSubWordsInFirstWord"] as? Boolean ?: true,
                    favorWholeWords = config["favorWholeWords"] as? Boolean ?: true,
                    countOnlyFirstWordFound = config["countOnlyFirstWordFound"] as? Boolean ?: false,
                    splitForNonWordChar = config["splitForNonWordChar"] as? Boolean ?: true,
                    alwaysSplitIfWholeWordExists = config["alwaysSplitIfWholeWordExists"] as? Boolean ?: false,
                    countSubWordsIfSplit = config["countSubWordsIfSplit"] as? Boolean ?: false,
                    nonWordSplitCount = config["nonWordSplitCount"] as? Int ?: 4
                )
                
                println("  \"${text.take(50)}${if (text.length > 50) "..." else ""}\" -> $tokenCount tokens")
            }
            println()
        }
        
        // Summary comparison table
        println("--- Summary Table ---")
        print("Text Sample".padEnd(35))
        for ((configName, _) in configurations) {
            print(configName.padEnd(12))
        }
        println()
        
        for (text in testTexts) {
            print("\"${text.take(30)}${if (text.length > 30) "..." else ""}\"".padEnd(35))
            
            for ((_, config) in configurations) {
                val tokenCount = Dictionary.countTokens(
                    text = text,
                    countSubWordsInFirstWord = config["countSubWordsInFirstWord"] as? Boolean ?: true,
                    favorWholeWords = config["favorWholeWords"] as? Boolean ?: true,
                    countOnlyFirstWordFound = config["countOnlyFirstWordFound"] as? Boolean ?: false,
                    splitForNonWordChar = config["splitForNonWordChar"] as? Boolean ?: true,
                    alwaysSplitIfWholeWordExists = config["alwaysSplitIfWholeWordExists"] as? Boolean ?: false,
                    countSubWordsIfSplit = config["countSubWordsIfSplit"] as? Boolean ?: false,
                    nonWordSplitCount = config["nonWordSplitCount"] as? Int ?: 4
                )
                print(tokenCount.toString().padEnd(12))
            }
            println()
        }
    }

}
