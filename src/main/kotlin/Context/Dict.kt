package com.TTT.Context

import com.TTT.Enums.ContextWindowSettings
import com.TTT.Pipe.TruncationSettings


/**
 * Singleton that provides support of dictionary lookups, tokenization count and truncation for TPipe.
 * Dictionary allows both string based truncation, and array based truncation to constrain a TPipe context window
 * into a token budget.
 *
 * @see ContextWindow
 */
object Dictionary
{
    val words: List<String> by lazy {
        try{
            val stream = object {}.javaClass.getResourceAsStream("/Words.txt") ?: error("No dictionary")
            stream.bufferedReader().use { it.readLines().filter { line -> line.isNotBlank() } }
        }
        catch(e : Exception)
        {
            emptyList()
        }
    }

    private val wordsSet by lazy { words.map { it.lowercase() }.toHashSet() }
    
    private fun findLongestMatch(text: String, allowOverlaps: Boolean = true, wholeWordsOnly: Boolean = false): String? {
        val lowerText = text.lowercase()
        var longestMatch: String? = null
        
        for(len in minOf(text.length, 50) downTo 1)
        {
            val substring = lowerText.substring(0, len)
            
            if(wholeWordsOnly)
            {
                // Check if it's a complete word boundary
                val isWholeWord = (len == text.length || !text[len].isLetter())
                if(!isWholeWord) continue
            }
            
            if(wordsSet.contains(substring))
            {
                longestMatch = substring
                break
            }
        }
        
        return longestMatch
    }
    
    private fun findAllMatches(text: String, allowOverlaps: Boolean = true): List<Pair<String, Int>> {
        val lowerText = text.lowercase()
        val matches = mutableListOf<Pair<String, Int>>()
        var pos = 0
        
        while(pos < text.length)
        {
            var found = false
            
            for(len in minOf(text.length - pos, 50) downTo 1)
            {
                val substring = lowerText.substring(pos, pos + len)
                
                if(wordsSet.contains(substring))
                {
                    matches.add(substring to pos)
                    pos += if(allowOverlaps) 1 else len
                    found = true
                    break
                }
            }
            
            if(!found) pos++
        }
        
        return matches
    }


    /**
     * Count tokens using TruncationSettings configuration.
     * 
     * @param text String of text to count the tokens.
     * @param settings TruncationSettings containing all tokenization parameters.
     * @return Number of tokens in the text.
     */
    fun countTokens(text: String, settings: TruncationSettings): Int {
        return countTokens(
            text,
            settings.countSubWordsInFirstWord,
            settings.favorWholeWords,
            settings.countOnlyFirstWordFound,
            settings.splitForNonWordChar,
            settings.alwaysSplitIfWholeWordExists,
            settings.countSubWordsIfSplit,
            settings.nonWordSplitCount
        )
    }

    /**
     * Count tokens in a string to help estimate the token size of data before sending to an llm.
     * Supports multiple configurations to help attempt to approximate the correct number of tokens for
     * different types of llm's. This function will try to create a close enough approximation to avoid truncation
     * of critical text but does not guarantee 100% accuracy to an llm's actual tokenizer.
     *
     * @param text String of text to count the tokens.
     * @param countSubWordsInFirstWord Many llm's treat all subwords as tokens in the very first word. If true,
     * we will always count all subwords as a token in the first word of the string.
     * @param favorWholeWords If true, we will attempt to chose subwords that are always whole words over
     * lesser words that are only subwords. IE Shotgun will be preferred over counting both "shot" and "gun.
     * @param splitForNonWordChar When we encounter a non word char we will split it into multiple tokens starting
     * from the right side of the non word char. This will occur in addition to counting any whole words found prior.
     * @param alwaysSplitIfWholeWordExists If true, we'll split if a non-word token is found and a whole word match
     * was also found. And then we'll proceed to count forward from the split for additional tokens.
     * @param countSubWordsIfSplit If true, subwords to the right of the split will be counted. If false, split count
     * by char number will be used to count up extra tokens.
     * @param nonWordSplitCount Number of chars that counts as a token if we hit no match, or do not set any additional
     * rules on how counting should work if we do split. This value is also used if all sub words, or other counting
     * mechanisms have been met and the string is still not fully counted. In this case,
     * we'll fall back to this for the remainder of the string.
     */
    fun countTokens(
        text: String,
        countSubWordsInFirstWord : Boolean = true,
        favorWholeWords : Boolean = true,
        countOnlyFirstWordFound : Boolean = false,
        splitForNonWordChar : Boolean = true,
        alwaysSplitIfWholeWordExists : Boolean = false,
        countSubWordsIfSplit : Boolean = false,
        nonWordSplitCount : Int = 4

        ) : Int
    {
        // Handle empty input - no tokens to count
        if(text.isEmpty()) return 0
        
        var tokenCount = 0

        // Split text into space-separated words for processing
        val words = text.split(" ")

        // Process each word individually
        for((index, word) in words.withIndex())
        {
            // Skip empty words (multiple spaces)
            if(word.isEmpty()) continue
            
            // Track if this is the first word for special handling
            val isFirstWord = index == 0
            var remainingWord = word
            var wordTokens = 0
            
            // Process the word until fully consumed
            while(remainingWord.isNotEmpty()) 
            {
                // Handle leading non-letter characters
                if(!remainingWord[0].isLetter())
                {
                    var nonWordEnd = 0
                    while(nonWordEnd < remainingWord.length && !remainingWord[nonWordEnd].isLetter()) nonWordEnd++

                    if(nonWordEnd > 0)
                    {
                        wordTokens += (nonWordEnd + nonWordSplitCount - 1) / nonWordSplitCount
                        remainingWord = remainingWord.substring(nonWordEnd)
                        continue
                    }
                }

                // Find matches based on configuration
                val bestMatch = when {
                    // First word gets loose matching for subword counting
                    isFirstWord && countSubWordsInFirstWord -> {
                        val matches = findAllMatches(remainingWord, allowOverlaps = true)
                        matches.maxByOrNull { it.first.length }
                    }
                    
                    // Favor whole words over subword fragments
                    favorWholeWords -> {
                        findLongestMatch(remainingWord, wholeWordsOnly = true)?.let { it to 0 }
                    }
                    
                    // Default to strict multi-token matching
                    else -> {
                        findLongestMatch(remainingWord, allowOverlaps = false)?.let { it to 0 }
                    }
                }
                
                // Process found dictionary match
                if(bestMatch != null)
                {
                    val matchText = bestMatch.first
                    val matchLength = matchText.length
                    
                    // Count this match as one token
                    wordTokens++
                    
                    // Stop processing if only counting first match
                    if(countOnlyFirstWordFound)
                    {
                        break
                    }
                    
                    // Calculate position after the match
                    val matchEnd = matchLength
                    // Check if there's a non-letter character immediately after the match
                    val hasNonWordAfter = matchEnd < remainingWord.length && 
                                        !remainingWord[matchEnd].isLetter()
                    
                    // Handle splitting logic based on configuration
                    if((splitForNonWordChar && hasNonWordAfter) || alwaysSplitIfWholeWordExists) 
                    {
                        // Determine where to split the remaining word
                        // Find split point after non-letter chars
                        val splitPoint = if(hasNonWordAfter && splitForNonWordChar) 
                        {
                            // Skip past non-letter characters to find next letter
                            var i = matchEnd
                            while(i < remainingWord.length && !remainingWord[i].isLetter()) i++
                            
                            // FIX: Count the skipped non-word characters
                            val skippedLength = i - matchEnd
                            if(skippedLength > 0)
                            {
                                wordTokens += (skippedLength + nonWordSplitCount - 1) / nonWordSplitCount
                            }
                            i
                        } 
                        else matchEnd
                        
                        // Update remaining word to process after split point
                        remainingWord = if(splitPoint < remainingWord.length) 
                                      remainingWord.substring(splitPoint) else ""
                        
                        // Handle remainder after split based on configuration
                        // Count remainder by character if not counting subwords
                        if(remainingWord.isNotEmpty() && !countSubWordsIfSplit) 
                        {
                            // Count remaining characters as tokens using fallback method
                            wordTokens += (remainingWord.length + nonWordSplitCount - 1) / nonWordSplitCount
                            break
                        }
                    }
                    // No splitting - continue from end of match
                    // Continue from end of match
                    else 
                    {
                        remainingWord = if(matchEnd < remainingWord.length) 
                                      remainingWord.substring(matchEnd) else ""
                    }
                }
                // No dictionary match found - use fallback counting
                // No match found - count by character
                else 
                {
                    // Count remaining characters as tokens using character-based method
                    wordTokens += (remainingWord.length + nonWordSplitCount - 1) / nonWordSplitCount
                    break
                }
            }
            
            // Ensure at least 1 token per non-empty word, add to total count
            // Ensure at least 1 token per word
            tokenCount += maxOf(1, wordTokens)
        }

        return tokenCount
    }



    /**
     * Truncates text to fit within a specified token window size based on the truncation strategy.
     * Uses the same token counting logic as countTokens to ensure consistency. The windowSize parameter
     * is multiplied by 1000 to avoid requiring users to input large numbers.
     *
     * @param text String of text to truncate.
     * @param windowSize Token limit (multiplied by 100 internally by default).
     * @param multiplyWindowSizeBy Amount to multiply token size by to help keep this function's params readable.
     * @param truncateSettings Strategy for truncation: TruncateTop removes from beginning,
     * TruncateBottom removes from end, TruncateMiddle removes from middle while preserving start and end.
     * @param countSubWordsInFirstWord Many llm's treat all subwords as tokens in the very first word. If true,
     * we will always count all subwords as a token in the first word of the string.
     * @param favorWholeWords If true, we will attempt to chose subwords that are always whole words over
     * lesser words that are only subwords. IE Shotgun will be preferred over counting both "shot" and "gun.
     * @param splitForNonWordChar When we encounter a non word char we will split it into multiple tokens starting
     * from the right side of the non word char. This will occur in addition to counting any whole words found prior.
     * @param alwaysSplitIfWholeWordExists If true, we'll split if a non-word token is found and a whole word match
     * was also found. And then we'll proceed to count forward from the split for additional tokens.
     * @param countSubWordsIfSplit If true, subwords to the right of the split will be counted. If false, split count
     * by char number will be used to count up extra tokens.
     * @param nonWordSplitCount Number of chars that counts as a token if we hit no match, or do not set any additional
     * rules on how counting should work if we do split.
     */
    fun truncate(
        text: String,
        windowSize: Int,
        multiplyWindowSizeBy : Int = 0,
        truncateSettings: ContextWindowSettings,
        countSubWordsInFirstWord : Boolean = true,
        favorWholeWords : Boolean = true,
        countOnlyFirstWordFound : Boolean = false,
        splitForNonWordChar : Boolean = true,
        alwaysSplitIfWholeWordExists : Boolean = false,
        countSubWordsIfSplit : Boolean = false,
        nonWordSplitCount : Int = 4

        ) : String
    {
        var tokenMax = windowSize

        if(multiplyWindowSizeBy > 0)
        {
            tokenMax = windowSize * multiplyWindowSizeBy
        }

        
        // Check current token count using same parameters
        val currentTokens = countTokens(
            text, countSubWordsInFirstWord, favorWholeWords, countOnlyFirstWordFound, 
            splitForNonWordChar, alwaysSplitIfWholeWordExists, countSubWordsIfSplit, nonWordSplitCount
        )
        
        // Return original text if already within limit
        if(currentTokens <= tokenMax) return text
        
        // Split text into words for processing
        val words = text.split(" ")
        
        // Apply truncation strategy based on settings
        return when(truncateSettings)
        {
            // Remove words from beginning, keep end
            ContextWindowSettings.TruncateTop ->
            {
                var tokens = 0
                val result = mutableListOf<String>()
                
                // Process words from end to beginning
                for(i in words.indices.reversed())
                {
                    val wordTokens = countTokens(
                        words[i], countSubWordsInFirstWord, favorWholeWords, countOnlyFirstWordFound,
                        splitForNonWordChar, alwaysSplitIfWholeWordExists, countSubWordsIfSplit, nonWordSplitCount
                    )
                    
                    // Add word if it fits within token limit
                    if(tokens + wordTokens <= tokenMax)
                    {
                        result.add(0, words[i])
                        tokens += wordTokens
                    } 
                    else break
                }
                
                result.joinToString(" ")
            }
            
            // Remove words from end, keep beginning
            ContextWindowSettings.TruncateBottom ->
            {
                var tokens = 0
                val result = mutableListOf<String>()
                
                // Process words from beginning to end
                for(word in words)
                {
                    val wordTokens = countTokens(
                        word, countSubWordsInFirstWord, favorWholeWords, countOnlyFirstWordFound,
                        splitForNonWordChar, alwaysSplitIfWholeWordExists, countSubWordsIfSplit, nonWordSplitCount
                    )
                    
                    // Add word if it fits within token limit
                    if(tokens + wordTokens <= tokenMax)
                    {
                        result.add(word)
                        tokens += wordTokens
                    } 
                    else break
                }
                
                result.joinToString(" ")
            }
            
            // Remove middle section, keep beginning and end
            ContextWindowSettings.TruncateMiddle ->
            {
                // Split available tokens between beginning and end
                val halfTarget = tokenMax / 2
                var topTokens = 0
                var bottomTokens = 0
                val topWords = mutableListOf<String>()
                val bottomWords = mutableListOf<String>()
                
                // Collect words from beginning
                for(word in words)
                {
                    val wordTokens = countTokens(
                        word, countSubWordsInFirstWord, favorWholeWords, countOnlyFirstWordFound,
                        splitForNonWordChar, alwaysSplitIfWholeWordExists, countSubWordsIfSplit, nonWordSplitCount
                    )
                    
                    if(topTokens + wordTokens <= halfTarget)
                    {
                        topWords.add(word)
                        topTokens += wordTokens
                    } 
                    else break
                }
                
                // Collect words from end, avoiding overlap with beginning
                for(i in words.indices.reversed())
                {
                    val wordTokens = countTokens(
                        words[i], countSubWordsInFirstWord, favorWholeWords, countOnlyFirstWordFound,
                        splitForNonWordChar, alwaysSplitIfWholeWordExists, countSubWordsIfSplit, nonWordSplitCount
                    )
                    
                    // Only add if within token limit and not overlapping with top words
                    if(bottomTokens + wordTokens <= halfTarget && i >= topWords.size)
                    {
                        bottomWords.add(0, words[i])
                        bottomTokens += wordTokens
                    } 
                    else if(i < topWords.size) break
                }
                
                // Combine beginning and end words
                (topWords + bottomWords).joinToString(" ")
            }
        }
    }

    /**
     * Helper function to allow calling [truncate] using a truncation settings objets instead of having to always
     * fill the entire set of params. This pairs well with [com.TTT.Pipe.getTruncationSettings]
     */
    fun truncateWithSettings(
        content: String,
        tokenBudget: Int,
        truncationMethod: ContextWindowSettings,
        settings: TruncationSettings) : String
    {
      return truncate(
          text = content,
          windowSize = tokenBudget,
          multiplyWindowSizeBy = settings.multiplyWindowSizeBy,
          truncateSettings = truncationMethod,
          countSubWordsInFirstWord = settings.countSubWordsInFirstWord,
          favorWholeWords = settings.favorWholeWords,
          countOnlyFirstWordFound = settings.countOnlyFirstWordFound,
          splitForNonWordChar = settings.splitForNonWordChar,
          alwaysSplitIfWholeWordExists = settings.alwaysSplitIfWholeWordExists,
          countSubWordsIfSplit = settings.countSubWordsIfSplit,
          nonWordSplitCount = settings.nonWordSplitCount
      )
    }


    /**
     * Truncates a list of strings to fit within a specified token window size based on the truncation strategy.
     * Removes entire list elements rather than truncating individual string contents. Useful for chat contexts
     * where older messages need to be removed to fit within token limits.
     *
     * @param messages List of strings to truncate.
     * @param windowSize Token limit.
     * @param multiplyWindowSizeBy Default multiplier to apply to tokens to shorten numbers needed to be passed.
     * @param truncateSettings Strategy for truncation: TruncateTop removes from beginning,
     * TruncateBottom removes from end, TruncateMiddle removes from middle while preserving start and end.
     * @param countSubWordsInFirstWord Many llm's treat all subwords as tokens in the very first word.
     * @param favorWholeWords If true, favor whole words over subword fragments.
     * @param countOnlyFirstWordFound If true, only count the first word match found.
     * @param splitForNonWordChar Split on non-word characters for token counting.
     * @param alwaysSplitIfWholeWordExists Always split if whole word match exists.
     * @param countSubWordsIfSplit Count subwords after splitting.
     * @param nonWordSplitCount Character count per token for non-word matches.
     */
    fun truncate(
        messages: List<String>,
        windowSize: Int,
        multiplyWindowSizeBy : Int = 0,
        truncateSettings: ContextWindowSettings,
        countSubWordsInFirstWord : Boolean = true,
        favorWholeWords : Boolean = true,
        countOnlyFirstWordFound : Boolean = false,
        splitForNonWordChar : Boolean = true,
        alwaysSplitIfWholeWordExists : Boolean = false,
        countSubWordsIfSplit : Boolean = false,
        nonWordSplitCount : Int = 4

        ) : List<String>
    {
        var tokenMax = windowSize

        if(multiplyWindowSizeBy > 0)
        {
            tokenMax = windowSize * multiplyWindowSizeBy
        }

        
        // Calculate total tokens across all messages
        val totalTokens = messages.sumOf { message ->
            countTokens(
                message, countSubWordsInFirstWord, favorWholeWords, countOnlyFirstWordFound,
                splitForNonWordChar, alwaysSplitIfWholeWordExists, countSubWordsIfSplit, nonWordSplitCount
            )
        }
        
        // Return original list if already within limit
        if(totalTokens <= tokenMax) return messages
        
        // Apply truncation strategy based on settings
        return when(truncateSettings)
        {
            // Remove messages from beginning, keep end
            ContextWindowSettings.TruncateTop ->
            {
                var tokens = 0
                val result = mutableListOf<String>()
                
                // Process messages from end to beginning
                for(i in messages.indices.reversed())
                {
                    val messageTokens = countTokens(
                        messages[i], countSubWordsInFirstWord, favorWholeWords, countOnlyFirstWordFound,
                        splitForNonWordChar, alwaysSplitIfWholeWordExists, countSubWordsIfSplit, nonWordSplitCount
                    )
                    
                    // Add message if it fits within token limit
                    if(tokens + messageTokens <= tokenMax)
                    {
                        result.add(0, messages[i])
                        tokens += messageTokens
                    }
                    else break
                }
                
                result
            }
            
            // Remove messages from end, keep beginning
            ContextWindowSettings.TruncateBottom ->
            {
                var tokens = 0
                val result = mutableListOf<String>()
                
                // Process messages from beginning to end
                for(message in messages)
                {
                    val messageTokens = countTokens(
                        message, countSubWordsInFirstWord, favorWholeWords, countOnlyFirstWordFound,
                        splitForNonWordChar, alwaysSplitIfWholeWordExists, countSubWordsIfSplit, nonWordSplitCount
                    )
                    
                    // Add message if it fits within token limit
                    if(tokens + messageTokens <= tokenMax)
                    {
                        result.add(message)
                        tokens += messageTokens
                    }
                    else break
                }
                
                result
            }
            
            // Remove middle messages, keep beginning and end
            ContextWindowSettings.TruncateMiddle ->
            {
                // Split available tokens between beginning and end
                val halfTarget = tokenMax / 2
                var topTokens = 0
                var bottomTokens = 0
                val topMessages = mutableListOf<String>()
                val bottomMessages = mutableListOf<String>()
                
                // Collect messages from beginning
                for(message in messages)
                {
                    val messageTokens = countTokens(
                        message, countSubWordsInFirstWord, favorWholeWords, countOnlyFirstWordFound,
                        splitForNonWordChar, alwaysSplitIfWholeWordExists, countSubWordsIfSplit, nonWordSplitCount
                    )
                    
                    if(topTokens + messageTokens <= halfTarget)
                    {
                        topMessages.add(message)
                        topTokens += messageTokens
                    }
                    else break
                }
                
                // Collect messages from end, avoiding overlap with beginning
                for(i in messages.indices.reversed())
                {
                    val messageTokens = countTokens(
                        messages[i], countSubWordsInFirstWord, favorWholeWords, countOnlyFirstWordFound,
                        splitForNonWordChar, alwaysSplitIfWholeWordExists, countSubWordsIfSplit, nonWordSplitCount
                    )
                    
                    // Only add if within token limit and not overlapping with top messages
                    if(bottomTokens + messageTokens <= halfTarget && i >= topMessages.size)
                    {
                        bottomMessages.add(0, messages[i])
                        bottomTokens += messageTokens
                    }
                    else if(i < topMessages.size) break
                }
                
                // Combine beginning and end messages
                topMessages + bottomMessages
            }
        }
    }

}