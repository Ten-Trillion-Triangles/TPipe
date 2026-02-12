package com.TTT

import com.TTT.Context.Dictionary
import kotlin.test.Test
import kotlin.test.assertEquals

class TokenCountingBugTest {

    @Test
    fun testPunctuationCountingWithSplit() {
        // "Hello" (1 token) + "!!!" (3 chars / 2 per token = 2 tokens) = 3 tokens total
        val count = Dictionary.countTokens(
            text = "Hello!!!",
            countSubWordsInFirstWord = true,
            favorWholeWords = true,
            countOnlyFirstWordFound = false,
            splitForNonWordChar = true,
            alwaysSplitIfWholeWordExists = false,
            countSubWordsIfSplit = true,
            nonWordSplitCount = 2
        )
        
        assertEquals(3, count, "Hello!!! should be 3 tokens (1 for Hello + 2 for !!!)")
    }

    @Test
    fun testMiddlePunctuationCounting() {
        // "Word" (1) + "," (1) + "Next" (1) = 3 tokens
        val count = Dictionary.countTokens(
            text = "Word,Next",
            countSubWordsInFirstWord = true,
            favorWholeWords = true,
            countOnlyFirstWordFound = false,
            splitForNonWordChar = true,
            alwaysSplitIfWholeWordExists = false,
            countSubWordsIfSplit = true,
            nonWordSplitCount = 2
        )
        
        assertEquals(3, count, "Word,Next should be 3 tokens (1 for Word + 1 for , + 1 for Next)")
    }

    @Test
    fun testMultiplePunctuationBetweenWords() {
        // "One" (1) + "..." (2) + "Two" (1) = 4 tokens
        val count = Dictionary.countTokens(
            text = "One...Two",
            countSubWordsInFirstWord = false,
            favorWholeWords = true,
            countOnlyFirstWordFound = false,
            splitForNonWordChar = true,
            alwaysSplitIfWholeWordExists = false,
            countSubWordsIfSplit = true,
            nonWordSplitCount = 2
        )
        
        assertEquals(4, count, "One...Two should be 4 tokens (1 + 2 + 1)")
    }
}
