package com.TTT.Context

import com.TTT.Pipe.TruncationSettings
import com.TTT.Enums.ContextWindowSettings
import org.junit.Test
import org.junit.Assert.*

class DictionaryChunkTest
{
    @Test
    fun testChunkByTokensBasic()
    {
        val text = "the quick brown fox jumps over the lazy dog"
        val chunks = Dictionary.chunkByTokens(text, 3)

        assertTrue("Should produce multiple chunks", chunks.size > 1)
        chunks.forEach { chunk ->
            val tokens = Dictionary.countTokens(chunk)
            assertTrue("Each chunk must be <= 3 tokens, got $tokens: '$chunk'", tokens <= 3)
        }
    }

    @Test
    fun testChunkByTokensWithSettings()
    {
        val settings = TruncationSettings(
            countSubWordsInFirstWord = true,
            favorWholeWords = true,
            splitForNonWordChar = true,
            nonWordSplitCount = 4,
            tokenCountingBias = 0.0
        )
        val text = "alpha bravo charlie delta echo foxtrot golf hotel india juliet"
        val chunks = Dictionary.chunkByTokensWithSettings(text, 4, settings)

        assertTrue("Should produce chunks", chunks.isNotEmpty())
        chunks.forEach { chunk ->
            val tokens = Dictionary.countTokens(chunk, settings)
            assertTrue("Each chunk must be <= 4 tokens, got $tokens: '$chunk'", tokens <= 4)
        }
    }

    @Test
    fun testChunkByTokensOverlap()
    {
        val text = "one two three four five six seven eight nine ten"
        val chunks = Dictionary.chunkByTokens(text, 4, overlapTokens = 1)

        assertTrue("Should produce multiple chunks", chunks.size > 1)
        // With overlap, consecutive chunks should share some tokens
        if(chunks.size >= 2)
        {
            val firstChunkWords = chunks[0].split(" ")
            val secondChunkWords = chunks[1].split(" ")
            // If overlap worked, second chunk's first words should match first chunk's last words
            val overlapCount = secondChunkWords.takeWhile { chunks[0].contains(it) }.size
            assertTrue("Overlap should produce shared context between chunks", overlapCount >= 1)
        }
    }

    @Test
    fun testChunkByTokensPreserveWordBoundary()
    {
        val text = "one two three four five six seven eight nine ten eleven"
        val chunksWithPreserve = Dictionary.chunkByTokens(text, 3, preserveWordBoundary = true)
        val chunksWithoutPreserve = Dictionary.chunkByTokens(text, 3, preserveWordBoundary = false)

        assertTrue("preserveWordBoundary=true should produce chunks", chunksWithPreserve.isNotEmpty())
        assertTrue("preserveWordBoundary=false should produce chunks", chunksWithoutPreserve.isNotEmpty())
        // All chunks should be non-empty
        assertFalse("No chunk should be empty with preserveWordBoundary=true",
            chunksWithPreserve.any { it.isEmpty() })
        assertFalse("No chunk should be empty with preserveWordBoundary=false",
            chunksWithoutPreserve.any { it.isEmpty() })
    }

    @Test
    fun testChunkByTokensEmptyInput()
    {
        val chunks = Dictionary.chunkByTokens("", 5)
        assertTrue("Empty input should return empty list", chunks.isEmpty())
    }

    @Test
    fun testChunkByTokensSingleWordExceedingMaxTokens()
    {
        val chunks = Dictionary.chunkByTokens("superlongwordthatismorethantwotokens", 2)
        assertTrue("Single word exceeding maxTokens should still produce a chunk", chunks.isNotEmpty())
        // The word itself may exceed maxTokens in token count - that's the edge case
        chunks.forEach { chunk ->
            assertTrue("Chunk should not be empty", chunk.isNotEmpty())
        }
    }

    @Test
    fun testChunkByTokensTokenConservation()
    {
        val text = "a b c d e f g h i j k l m n o p q r s t u v w x y z"
        val allChunks = Dictionary.chunkByTokens(text, 5)
        val totalIn = Dictionary.countTokens(text)
        val totalOut = allChunks.sumOf { Dictionary.countTokens(it) }

        // Tokens from original should appear in chunks (may differ slightly due to bias/word boundaries)
        assertTrue("Should produce multiple chunks for long text", allChunks.size > 1)
        // The combined chunk token count should be close to input (within rounding)
        assertTrue("Total output tokens should roughly equal input", totalOut >= totalIn - 5)
    }

    @Test
    fun testChunkByTokensNoEmptyChunks()
    {
        val text = "short words one two three four five six seven eight nine ten"
        val chunks = Dictionary.chunkByTokens(text, 3)
        assertFalse("No chunk should be empty string", chunks.any { it.isEmpty() })
    }

    @Test
    fun testChunkByTokensZeroMaxTokens()
    {
        val text = "any text here"
        val chunks = Dictionary.chunkByTokens(text, 0)
        assertTrue("Zero maxTokens should return empty list", chunks.isEmpty())
    }
}