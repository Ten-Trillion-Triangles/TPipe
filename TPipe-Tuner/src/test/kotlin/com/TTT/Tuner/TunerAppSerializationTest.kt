package com.TTT.Tuner

import com.TTT.Pipe.TruncationSettings
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertTrue

class TunerAppSerializationTest {
    @Test
    fun `tuner output includes every TruncationSettings default`() {
        val json = Json {
            prettyPrint = true
            encodeDefaults = true
        }

        val output = json.encodeToString(TruncationSettings())

        listOf(
            "\"multiplyWindowSizeBy\": 0",
            "\"countSubWordsInFirstWord\": true",
            "\"favorWholeWords\": true",
            "\"countOnlyFirstWordFound\": false",
            "\"splitForNonWordChar\": true",
            "\"alwaysSplitIfWholeWordExists\": false",
            "\"countSubWordsIfSplit\": false",
            "\"nonWordSplitCount\": 4",
            "\"tokenCountingBias\": 0.0",
            "\"fillMode\": false",
            "\"fillAndSplitMode\": false",
            "\"multiPageBudgetStrategy\": null",
            "\"pageWeights\": null"
        ).forEach { expected ->
            assertTrue(output.contains(expected), "Expected tuner JSON to include $expected\n$output")
        }
    }
}

class TunerAppPrintDefaultStringTest {
    @Test
    fun `print-default-string emits the full default string and exits cleanly`() {
        val baos = java.io.ByteArrayOutputStream()
        val originalOut = System.out
        System.setOut(java.io.PrintStream(baos))

        try {
            main(arrayOf("--print-default-string"))
        } catch (e: RuntimeException) {
            // exit() in main() throws — caught and rethrown by Kotlin
            // After exit, System.out is already restored by the JVM teardown
            // re-check if we actually got output before the exit
        } finally {
            System.setOut(originalOut)
        }

        val output = baos.toString()
        assertTrue(output.isNotBlank(), "Expected non-empty output from --print-default-string")
        assertTrue(output.contains("The quick brown fox"), "Output should start with the stress-test string header")
        assertTrue(output.contains("floccinaucinihilipilification"), "Output should contain the known OOV word from DEFAULT_TEST_STRING")
        assertTrue(output.contains("你好，世界"), "Output should contain Chinese multilingual content from DEFAULT_TEST_STRING")
    }

    @Test
    fun `print-default-string does not require --expected-tokens`() {
        val baos = java.io.ByteArrayOutputStream()
        val originalOut = System.out
        System.setOut(java.io.PrintStream(baos))

        try {
            main(arrayOf("--print-default-string"))
        } catch (e: RuntimeException) {
            // exit() is NOT called for --print-default-string (returns instead)
            // If this throws, it means exit() was called — fail the test
            throw AssertionError("Unexpected exit() during --print-default-string: ${e.message}")
        } finally {
            System.setOut(originalOut)
        }

        // Should reach here without throwing
        val output = baos.toString()
        assertTrue(output.isNotBlank(), "Output must not be blank")
    }
}
