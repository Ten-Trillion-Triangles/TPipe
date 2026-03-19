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
