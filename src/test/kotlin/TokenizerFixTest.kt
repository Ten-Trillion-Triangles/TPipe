package com.TTT

import com.TTT.Context.Dictionary
import com.TTT.Pipe.TruncationSettings
import kotlin.test.Test
import kotlin.test.assertEquals

class TokenizerFixTest {

    private val settings = TruncationSettings(
        countSubWordsInFirstWord = true,
        favorWholeWords = true,
        countOnlyFirstWordFound = false,
        splitForNonWordChar = true,
        alwaysSplitIfWholeWordExists = false,
        countSubWordsIfSplit = true,
        nonWordSplitCount = 2
    )

    @Test
    fun testLeadingNonWordCharacters() {
        // "123" (2 tokens since nonWordSplitCount=2) + "hello" (1 token) = 3 tokens
        val count = Dictionary.countTokens("123hello", settings)
        assertEquals(3, count, "123hello should be 3 tokens (2 for 123 + 1 for hello)")
    }

    @Test
    fun testNonWordCharactersBetweenMatches() {
        // "Hello" (1) + "!!!" (2) + "World" (1) = 4 tokens
        val count = Dictionary.countTokens("Hello!!!World", settings)
        assertEquals(4, count, "Hello!!!World should be 4 tokens (1 + 2 + 1)")
    }

    @Test
    fun testLeadingNonWordStartOfWord() {
        // "!!!" (2) + "Hello" (1) = 3 tokens
        val count = Dictionary.countTokens("!!!Hello", settings)
        assertEquals(3, count, "!!!Hello should be 3 tokens (2 for !!! + 1 for Hello)")
    }

    @Test
    fun testTrailingNonWordCharacters() {
        // "Hello" (1) + "!!!" (2) = 3 tokens
        val count = Dictionary.countTokens("Hello!!!", settings)
        assertEquals(3, count, "Hello!!! should be 3 tokens (1 for Hello + 2 for !!!)")
    }
}
