package com.TTT.Context

import com.TTT.Enums.ContextWindowSettings
import com.TTT.Pipe.TruncationSettings
import com.TTT.Util.combine
import com.TTT.Util.deepCopy
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import java.util.ArrayDeque

@kotlinx.serialization.Serializable
data class ContextWindow(
    @kotlinx.serialization.Transient val isInitialized: Boolean = false)
{

    /**
     * Lorebook keys and values. Used for weighted context injection
     * @see LoreBook
     */
    @OptIn(ExperimentalSerializationApi::class)
    @kotlinx.serialization.Serializable
    @kotlinx.serialization.EncodeDefault(EncodeDefault.Mode.ALWAYS)
    var loreBookKeys = mutableMapOf<String, LoreBook>()

    /**
     * List of context elements as raw strings. Used for raw context injection.
     */
    @kotlinx.serialization.Serializable
    var contextElements = mutableListOf<String>()

    /**
     * List of conversations that has been carried out between the "user" and the llm agent. This allows for
     * structured conversation storage when desired.
     */
    @kotlinx.serialization.Serializable
    var converseHistory = ConverseHistory()

    /**
     * Version of the context window to manage remote synchronization.
     */
    @kotlinx.serialization.Serializable
    var version: Long = 0



    /**
     * Transient metadata that we can use to store various system settings and metadata. This is commonly used
     * to store settings, and supply metadata for other features of TPipe so caution should be used by the user
     * to avoid overlapping with keys reserved by the system. In general users should avoid even touching this
     * unless they know exactly what they're doing.
     */
    @kotlinx.serialization.Transient
    var metaData = mutableMapOf<Any, Any>()

    /**
     * Finds lorebook keys that match any substrings in the input text using local lock visibility only.
     * Includes matches from both main keys and alias keys.
     *
     * Remote-aware callers should use [findMatchingLoreBookKeysSuspend].
     *
     * @param text Input text to scan for matching keys.
     * @return List of matching lorebook keys.
     */
    fun findMatchingLoreBookKeys(text: String): List<String>
    {
        val lowerText = text.lowercase()
        val matchingKeys = mutableSetOf<String>()
        
        loreBookKeys.forEach { (key, loreBook) ->
            // Check main key
            if(lowerText.contains(key.lowercase()))
            {
                // Check if key is locked before adding
                if(canSelectLoreBookKey(key))
                {
                    matchingKeys.add(key)
                }
            }
            
            // Check alias keys
            loreBook.aliasKeys.forEach { alias ->
                if(lowerText.contains(alias.lowercase()))
                {
                    // Check if key is locked before adding
                    if(canSelectLoreBookKey(key))
                    {
                        matchingKeys.add(key)
                    }
                }
            }
        }
        
        return matchingKeys.toList()
    }

    /**
     * Finds lorebook keys that match any substrings in the input text while honoring remote lock state.
     * Includes matches from both main keys and alias keys.
     *
     * @param text Input text to scan for matching keys.
     * @return List of matching lorebook keys.
     */
    suspend fun findMatchingLoreBookKeysSuspend(text: String): List<String>
    {
        val lowerText = text.lowercase()
        val matchingKeys = mutableSetOf<String>()

        loreBookKeys.forEach { (key, loreBook) ->
            if(lowerText.contains(key.lowercase()))
            {
                if(canSelectLoreBookKeySuspend(key))
                {
                    matchingKeys.add(key)
                }
            }

            loreBook.aliasKeys.forEach { alias ->
                if(lowerText.contains(alias.lowercase()))
                {
                    if(canSelectLoreBookKeySuspend(key))
                    {
                        matchingKeys.add(key)
                    }
                }
            }
        }

        return matchingKeys.toList()
    }

    /**
     * Counts occurrences of each key and sorts by hit count (highest first)
     * @param hitKeys List of matching keys to count
     * @return List of key-count pairs sorted by count descending
     */
    fun countAndSortKeyHits(hitKeys: List<String>): List<Pair<String, Int>> {
        return hitKeys.groupingBy { it }.eachCount().toList().sortedByDescending { it.second }
    }

    /**
     * Validates that all required dependency keys are present in the matched keys set.
     * Checks both direct key matches and alias key matches to satisfy dependencies.
     * Empty requiredKeys list means no dependencies and the entry is always eligible.
     * 
     * @param matchedKeys Set of keys that were found in the input text
     * @return Map of key to boolean indicating if dependencies are satisfied
     */
    private fun checkKeyDependencies(matchedKeys: Set<String>): Map<String, Boolean>
    {
        return loreBookKeys.mapValues { (_, loreBook) ->
            // Empty requiredKeys means no dependencies (always satisfied)
            if(loreBook.requiredKeys.isEmpty())
            {
                true
            } 

            else 
            {
                // All required keys must be present in matched set
                loreBook.requiredKeys.all { requiredKey ->
                    // Check if the required key is directly in the matched set
                    matchedKeys.contains(requiredKey) ||
                    // Check if any matched key corresponds to the required key (by main key or alias)
                    matchedKeys.any { matchedKey ->
                        // The matched key itself equals the required key
                        matchedKey == requiredKey ||
                        // Or the matched key has the required key as an alias
                        loreBookKeys[matchedKey]?.aliasKeys?.contains(requiredKey) == true ||
                        // Or the required key has the matched key as an alias
                        loreBookKeys[requiredKey]?.aliasKeys?.contains(matchedKey) == true
                    }
                }
            }
        }
    }


    /**
     * Selects lorebook context entries based on weight, hit count, and token budget using local lock visibility only.
     * Prioritizes highest weight entries first, then uses hit count as tiebreaker for equal weights.
     *
     * Remote-aware callers should use [selectLoreBookContextSuspend].
     *
     * @param text Input text to scan for matching lorebook keys.
     * @param maxTokens Maximum token budget for selected context.
     * @param countSubWordsInFirstWord Token counting parameter - count subwords in first word.
     * @param favorWholeWords Token counting parameter - prefer whole words over subwords.
     * @param countOnlyFirstWordFound Token counting parameter - only count first word match.
     * @param splitForNonWordChar Token counting parameter - split on non-word characters.
     * @param alwaysSplitIfWholeWordExists Token counting parameter - always split if whole word exists.
     * @param countSubWordsIfSplit Token counting parameter - count subwords after splitting.
     * @param nonWordSplitCount Token counting parameter - character count per token for non-words.
     * @return List of selected lorebook keys that fit within token budget.
     */
    fun selectLoreBookContext(
        text: String,
        maxTokens: Int,
        countSubWordsInFirstWord: Boolean = true,
        favorWholeWords: Boolean = true,
        countOnlyFirstWordFound: Boolean = false,
        splitForNonWordChar: Boolean = true,
        alwaysSplitIfWholeWordExists: Boolean = false,
        countSubWordsIfSplit: Boolean = false,
        nonWordSplitCount: Int = 4,
        tokenCountingBias: Double = 0.0
    ): List<String> {
        // Step 1: Find all lorebook keys that match substrings in the input text (includes aliases)
        val matchingKeys = findMatchingLoreBookKeys(text)
        
        // Step 2: Expand matching keys to include linked keys recursively
        val expandedKeys = mutableSetOf<String>()
        val toProcess = mutableSetOf<String>()
        toProcess.addAll(matchingKeys)
        
        while(toProcess.isNotEmpty())
        {
            val currentKey = toProcess.first()
            toProcess.remove(currentKey)
            
            if(!expandedKeys.contains(currentKey))
            {
                expandedKeys.add(currentKey)
                
                // Add linked keys to processing queue
                loreBookKeys[currentKey]?.linkedKeys?.forEach { linkedKey ->
                    if(loreBookKeys.containsKey(linkedKey) && !expandedKeys.contains(linkedKey))
                    {
                        toProcess.add(linkedKey)
                    }
                }
            }
        }
        
        // Step 2.5: Add keys whose dependencies are satisfied by the expanded keys
        val dependencyEligibleKeys = mutableSetOf<String>()
        dependencyEligibleKeys.addAll(expandedKeys)
        
        // Check all lorebook keys to see if their dependencies are satisfied
        loreBookKeys.forEach { (key, loreBook) ->
            if(!dependencyEligibleKeys.contains(key) && loreBook.requiredKeys.isNotEmpty())
            {
                // Check if all required keys are present in expanded keys
                val allDependenciesSatisfied = loreBook.requiredKeys.all { requiredKey ->
                    expandedKeys.contains(requiredKey) ||
                    // Check if any expanded key corresponds to the required key
                    expandedKeys.any { expandedKey ->
                        expandedKey == requiredKey ||
                        loreBookKeys[expandedKey]?.aliasKeys?.contains(requiredKey) == true ||
                        loreBookKeys[requiredKey]?.aliasKeys?.contains(expandedKey) == true
                    }
                }
                
                if(allDependenciesSatisfied)
                {
                    dependencyEligibleKeys.add(key)
                }
            }
        }
        
        // Step 3: Count how many times each key appears and convert to map for lookup
        val keyHitCounts = countAndSortKeyHits(dependencyEligibleKeys.toList()).toMap()
        
        // Step 3.5: Filter out keys whose dependencies are not satisfied
        val dependencyStatus = checkKeyDependencies(dependencyEligibleKeys)
        val eligibleKeys = dependencyEligibleKeys.filter { key ->
            dependencyStatus[key] == true
        }
        
        // Step 4: Create candidates with key, lorebook entry, and hit count
        // Filter to only eligible keys and create sortable triples
        val candidates = loreBookKeys.filter { (key, _) -> key in eligibleKeys }
            .map { (key, loreBook) -> 
                Triple(key, loreBook, keyHitCounts[key] ?: 0)
            }
            // Filter out locked keys
            .filter { (key, _, _) -> canSelectLoreBookKey(key) }
            // Step 5: Sort by weight (descending) then by hit count (descending)
            // This ensures highest weight entries are selected first, with hit count as tiebreaker
            .sortedWith(compareByDescending<Triple<String, LoreBook, Int>> { it.second.weight }
                .thenByDescending { it.third })
        
        val selected = mutableListOf<String>()
        var usedTokens = 0
        
        // Step 6: Select entries that fit within token budget, respecting priority order
        for((key, loreBook, _) in candidates)
        {
            // Calculate token cost of this lorebook value using Dictionary tokenizer
            val valueTokens = Dictionary.countTokens(
                loreBook.value, countSubWordsInFirstWord, favorWholeWords, 
                countOnlyFirstWordFound, splitForNonWordChar, alwaysSplitIfWholeWordExists, 
                countSubWordsIfSplit, nonWordSplitCount, tokenCountingBias
            )
            
            // Only add if it fits within remaining token budget
            if(usedTokens + valueTokens <= maxTokens)
            {
                selected.add(key)
                usedTokens += valueTokens
            }
            // If entry doesn't fit, skip it and continue with next candidate
        }
        
        return selected
    }

    /**
     * Selects lorebook context entries based on weight, hit count, and token budget while honoring remote lock state.
     * Prioritizes highest weight entries first, then uses hit count as tiebreaker for equal weights.
     *
     * @param text Input text to scan for matching lorebook keys.
     * @param maxTokens Maximum token budget for selected context.
     * @param countSubWordsInFirstWord Token counting parameter - count subwords in first word.
     * @param favorWholeWords Token counting parameter - prefer whole words over subwords.
     * @param countOnlyFirstWordFound Token counting parameter - only count first word match.
     * @param splitForNonWordChar Token counting parameter - split on non-word characters.
     * @param alwaysSplitIfWholeWordExists Token counting parameter - always split if whole word exists.
     * @param countSubWordsIfSplit Token counting parameter - count subwords after splitting.
     * @param nonWordSplitCount Token counting parameter - character count per token for non-words.
     * @return List of selected lorebook keys that fit within token budget.
     */
    suspend fun selectLoreBookContextSuspend(
        text: String,
        maxTokens: Int,
        countSubWordsInFirstWord: Boolean = true,
        favorWholeWords: Boolean = true,
        countOnlyFirstWordFound: Boolean = false,
        splitForNonWordChar: Boolean = true,
        alwaysSplitIfWholeWordExists: Boolean = false,
        countSubWordsIfSplit: Boolean = false,
        nonWordSplitCount: Int = 4,
        tokenCountingBias: Double = 0.0
    ): List<String>
    {
        val matchingKeys = findMatchingLoreBookKeysSuspend(text)
        val expandedKeys = mutableSetOf<String>()
        val toProcess = mutableSetOf<String>()
        toProcess.addAll(matchingKeys)

        while(toProcess.isNotEmpty())
        {
            val currentKey = toProcess.first()
            toProcess.remove(currentKey)

            if(!expandedKeys.contains(currentKey))
            {
                expandedKeys.add(currentKey)

                loreBookKeys[currentKey]?.linkedKeys?.forEach { linkedKey ->
                    if(loreBookKeys.containsKey(linkedKey) && !expandedKeys.contains(linkedKey))
                    {
                        toProcess.add(linkedKey)
                    }
                }
            }
        }

        val dependencyEligibleKeys = mutableSetOf<String>()
        dependencyEligibleKeys.addAll(expandedKeys)

        loreBookKeys.forEach { (key, loreBook) ->
            if(!dependencyEligibleKeys.contains(key) && loreBook.requiredKeys.isNotEmpty())
            {
                val allDependenciesSatisfied = loreBook.requiredKeys.all { requiredKey ->
                    expandedKeys.contains(requiredKey) ||
                    expandedKeys.any { expandedKey ->
                        expandedKey == requiredKey ||
                            loreBookKeys[expandedKey]?.aliasKeys?.contains(requiredKey) == true ||
                            loreBookKeys[requiredKey]?.aliasKeys?.contains(expandedKey) == true
                    }
                }

                if(allDependenciesSatisfied)
                {
                    dependencyEligibleKeys.add(key)
                }
            }
        }

        val keyHitCounts = countAndSortKeyHits(dependencyEligibleKeys.toList()).toMap()
        val dependencyStatus = checkKeyDependencies(dependencyEligibleKeys)
        val eligibleKeys = dependencyEligibleKeys.filter { key ->
            dependencyStatus[key] == true
        }

        val candidateKeys = loreBookKeys.filter { (key, _) -> key in eligibleKeys }
            .map { (key, loreBook) ->
                Triple(key, loreBook, keyHitCounts[key] ?: 0)
            }

        val unlockedCandidates = mutableListOf<Triple<String, LoreBook, Int>>()
        for(candidate in candidateKeys)
        {
            if(canSelectLoreBookKeySuspend(candidate.first))
            {
                unlockedCandidates.add(candidate)
            }
        }

        val candidates = unlockedCandidates.sortedWith(compareByDescending<Triple<String, LoreBook, Int>> { it.second.weight }
            .thenByDescending { it.third })

        val selected = mutableListOf<String>()
        var usedTokens = 0

        for((key, loreBook, _) in candidates)
        {
            val valueTokens = Dictionary.countTokens(
                loreBook.value,
                countSubWordsInFirstWord,
                favorWholeWords,
                countOnlyFirstWordFound,
                splitForNonWordChar,
                alwaysSplitIfWholeWordExists,
                countSubWordsIfSplit,
                nonWordSplitCount,
                tokenCountingBias
            )

            if(usedTokens + valueTokens <= maxTokens)
            {
                selected.add(key)
                usedTokens += valueTokens
            }
        }

        return selected
    }

    /**
     * Selects lorebook entries using priority selection followed by weight-based filling with local lock visibility only.
     *
     * Remote-aware callers should use [selectAndFillLoreBookContextSuspend].
     *
     * @param text Input text used for matching lorebook keys.
     * @param maxTokens Maximum token budget available for lorebook entries.
     * @param countSubWordsInFirstWord Token counting parameter - count subwords in first word.
     * @param favorWholeWords Token counting parameter - prefer whole words over subwords.
     * @param countOnlyFirstWordFound Token counting parameter - only count first occurrence of each word.
     * @param splitForNonWordChar Token counting parameter - split on non-word characters.
     * @param alwaysSplitIfWholeWordExists Token counting parameter - always split when whole word exists.
     * @param countSubWordsIfSplit Token counting parameter - count subwords after splitting.
     * @param nonWordSplitCount Token counting parameter - token count for non-word characters.
     * @return Ordered list of lorebook keys that fit within the specified budget.
     */
    fun selectAndFillLoreBookContext(
        text: String,
        maxTokens: Int,
        countSubWordsInFirstWord: Boolean = true,
        favorWholeWords: Boolean = true,
        countOnlyFirstWordFound: Boolean = false,
        splitForNonWordChar: Boolean = true,
        alwaysSplitIfWholeWordExists: Boolean = false,
        countSubWordsIfSplit: Boolean = false,
        nonWordSplitCount: Int = 4,
        tokenCountingBias: Double = 0.0
    ): List<String>
    {
        if(maxTokens <= 0) return listOf()

        val priorityKeys = selectLoreBookContext(
            text,
            maxTokens,
            countSubWordsInFirstWord,
            favorWholeWords,
            countOnlyFirstWordFound,
            splitForNonWordChar,
            alwaysSplitIfWholeWordExists,
            countSubWordsIfSplit,
            nonWordSplitCount,
            tokenCountingBias
        )

        val selectedKeys = priorityKeys.toMutableList()
        val selectedSet = selectedKeys.toMutableSet()

        var usedTokens = selectedKeys.sumOf { key ->
            val loreBook = loreBookKeys[key]
            if(loreBook == null) return@sumOf 0
            Dictionary.countTokens(
                loreBook.value,
                countSubWordsInFirstWord,
                favorWholeWords,
                countOnlyFirstWordFound,
                splitForNonWordChar,
                alwaysSplitIfWholeWordExists,
                countSubWordsIfSplit,
                nonWordSplitCount,
                tokenCountingBias
            )
        }

        if(usedTokens >= maxTokens) return selectedKeys

        val fillCandidates = loreBookKeys.keys
            .filter { it !in selectedSet }
            // Filter out locked keys
            .filter { key -> canSelectLoreBookKey(key) }
            .mapNotNull { key -> loreBookKeys[key]?.let { key to it } }
            .sortedByDescending { (_, loreBook) -> loreBook.weight }

        for((key, loreBook) in fillCandidates)
        {
            val dependencyStatus = checkKeyDependencies(selectedSet)
            if(dependencyStatus[key] != true) continue

            val tokenCost = Dictionary.countTokens(
                loreBook.value,
                countSubWordsInFirstWord,
                favorWholeWords,
                countOnlyFirstWordFound,
                splitForNonWordChar,
                alwaysSplitIfWholeWordExists,
                countSubWordsIfSplit,
                nonWordSplitCount,
                tokenCountingBias
            )

            if(usedTokens + tokenCost <= maxTokens)
            {
                selectedKeys.add(key)
                selectedSet.add(key)
                usedTokens += tokenCost
            }

            if(usedTokens >= maxTokens)
            {
                break
            }
        }

        return selectedKeys
    }

    /**
     * Selects lorebook entries using priority selection followed by weight-based filling while honoring remote lock state.
     *
     * @param text Input text used for matching lorebook keys.
     * @param maxTokens Maximum token budget available for lorebook entries.
     * @param countSubWordsInFirstWord Token counting parameter - count subwords in first word.
     * @param favorWholeWords Token counting parameter - prefer whole words over subwords.
     * @param countOnlyFirstWordFound Token counting parameter - only count first occurrence of each word.
     * @param splitForNonWordChar Token counting parameter - split on non-word characters.
     * @param alwaysSplitIfWholeWordExists Token counting parameter - always split when whole word exists.
     * @param countSubWordsIfSplit Token counting parameter - count subwords after splitting.
     * @param nonWordSplitCount Token counting parameter - token count for non-word characters.
     * @return Ordered list of lorebook keys that fit within the specified budget.
     */
    suspend fun selectAndFillLoreBookContextSuspend(
        text: String,
        maxTokens: Int,
        countSubWordsInFirstWord: Boolean = true,
        favorWholeWords: Boolean = true,
        countOnlyFirstWordFound: Boolean = false,
        splitForNonWordChar: Boolean = true,
        alwaysSplitIfWholeWordExists: Boolean = false,
        countSubWordsIfSplit: Boolean = false,
        nonWordSplitCount: Int = 4,
        tokenCountingBias: Double = 0.0
    ): List<String>
    {
        if(maxTokens <= 0) return listOf()

        val priorityKeys = selectLoreBookContextSuspend(
            text,
            maxTokens,
            countSubWordsInFirstWord,
            favorWholeWords,
            countOnlyFirstWordFound,
            splitForNonWordChar,
            alwaysSplitIfWholeWordExists,
            countSubWordsIfSplit,
            nonWordSplitCount,
            tokenCountingBias
        )

        val selectedKeys = priorityKeys.toMutableList()
        val selectedSet = selectedKeys.toMutableSet()

        var usedTokens = selectedKeys.sumOf { key ->
            val loreBook = loreBookKeys[key]
            if(loreBook == null) return@sumOf 0
            Dictionary.countTokens(
                loreBook.value,
                countSubWordsInFirstWord,
                favorWholeWords,
                countOnlyFirstWordFound,
                splitForNonWordChar,
                alwaysSplitIfWholeWordExists,
                countSubWordsIfSplit,
                nonWordSplitCount,
                tokenCountingBias
            )
        }

        if(usedTokens >= maxTokens) return selectedKeys

        val fillCandidates = mutableListOf<Pair<String, LoreBook>>()
        for(key in loreBookKeys.keys.filter { it !in selectedSet })
        {
            if(canSelectLoreBookKeySuspend(key))
            {
                loreBookKeys[key]?.let { loreBook ->
                    fillCandidates.add(key to loreBook)
                }
            }
        }

        fillCandidates.sortByDescending { (_, loreBook) -> loreBook.weight }

        for((key, loreBook) in fillCandidates)
        {
            val dependencyStatus = checkKeyDependencies(selectedSet)
            if(dependencyStatus[key] != true) continue

            val tokenCost = Dictionary.countTokens(
                loreBook.value,
                countSubWordsInFirstWord,
                favorWholeWords,
                countOnlyFirstWordFound,
                splitForNonWordChar,
                alwaysSplitIfWholeWordExists,
                countSubWordsIfSplit,
                nonWordSplitCount,
                tokenCountingBias
            )

            if(usedTokens + tokenCost <= maxTokens)
            {
                selectedKeys.add(key)
                selectedSet.add(key)
                usedTokens += tokenCost
            }

            if(usedTokens >= maxTokens)
            {
                break
            }
        }

        return selectedKeys
    }
    
    /**
     * Helper function to select a lorebook using settings instead of passing the raw inputs forward.
     */
    fun selectLoreBookContextWithSettings(settings: TruncationSettings, text: String, maxTokens: Int) : List<String>
    {
       return selectLoreBookContext(
            text,
            maxTokens,
            settings.countSubWordsInFirstWord,
            settings.favorWholeWords,
            settings.countOnlyFirstWordFound,
            settings.splitForNonWordChar,
            settings.alwaysSplitIfWholeWordExists,
            settings.countSubWordsIfSplit,
            settings.nonWordSplitCount,
            settings.tokenCountingBias
        )
    }

    /**
     * Helper to run remote-aware LoreBook selection using a [TruncationSettings] object.
     *
     * @param settings TruncationSettings containing tokenization configuration.
     * @param text Input text used to match LoreBook keys.
     * @param maxTokens Maximum tokens allocated for selected LoreBook context.
     * @return Ordered list of LoreBook keys selected while honoring remote lock state.
     */
    suspend fun selectLoreBookContextWithSettingsSuspend(settings: TruncationSettings, text: String, maxTokens: Int) : List<String>
    {
        return selectLoreBookContextSuspend(
            text,
            maxTokens,
            settings.countSubWordsInFirstWord,
            settings.favorWholeWords,
            settings.countOnlyFirstWordFound,
            settings.splitForNonWordChar,
            settings.alwaysSplitIfWholeWordExists,
            settings.countSubWordsIfSplit,
            settings.nonWordSplitCount,
            settings.tokenCountingBias
        )
    }
    
    /**
     * Helper to run the select-and-fill LoreBook strategy using a settings object.
     *
     * @param settings TruncationSettings containing tokenization and fillMode configuration
     * @param text Input text used to match LoreBook keys
     * @param maxTokens Maximum tokens allocated for selected LoreBook context
     * @return Ordered list of LoreBook keys selected via the select-and-fill strategy
     */
    fun selectAndFillLoreBookContextWithSettings(settings: TruncationSettings, text: String, maxTokens: Int): List<String>
    {
        return selectAndFillLoreBookContext(
            text,
            maxTokens,
            settings.countSubWordsInFirstWord,
            settings.favorWholeWords,
            settings.countOnlyFirstWordFound,
            settings.splitForNonWordChar,
            settings.alwaysSplitIfWholeWordExists,
            settings.countSubWordsIfSplit,
            settings.nonWordSplitCount,
            settings.tokenCountingBias
        )
    }

    /**
     * Helper to run remote-aware select-and-fill LoreBook selection using a [TruncationSettings] object.
     *
     * @param settings TruncationSettings containing tokenization and fillMode configuration.
     * @param text Input text used to match LoreBook keys.
     * @param maxTokens Maximum tokens allocated for selected LoreBook context.
     * @return Ordered list of LoreBook keys selected while honoring remote lock state.
     */
    suspend fun selectAndFillLoreBookContextWithSettingsSuspend(settings: TruncationSettings, text: String, maxTokens: Int): List<String>
    {
        return selectAndFillLoreBookContextSuspend(
            text,
            maxTokens,
            settings.countSubWordsInFirstWord,
            settings.favorWholeWords,
            settings.countOnlyFirstWordFound,
            settings.splitForNonWordChar,
            settings.alwaysSplitIfWholeWordExists,
            settings.countSubWordsIfSplit,
            settings.nonWordSplitCount,
            settings.tokenCountingBias
        )
    }
    
    /**
     * Convenience function to add a lorebook entry with key, value, and weight
     * @param key The trigger key to match in text
     * @param value The context value to inject when key matches
     * @param weight Priority weight (higher = more important, default = 0)
     * @param linkedKeys List of keys that are linked to this key, and will be instantly invoked if this key
     * is also invoked.
     * @param aliasKeys List of keys that are also equal to this lorebook's key and will count as invoking
     * this key if hit.
     * @param requiredKeys List of keys that must ALL be matched for this entry to be eligible for activation.
     */
    fun addLoreBookEntry(key: String, value: String, weight: Int = 0, linkedKeys: List<String> = listOf(), aliasKeys: List<String> = listOf(), requiredKeys: List<String> = listOf())
    {
        loreBookKeys[key] = LoreBook().apply {
            this.key = key
            this.value = value
            this.weight = weight
            this.linkedKeys.addAll(linkedKeys)
            this.aliasKeys.addAll(aliasKeys)
            this.aliasKeys.add(key.uppercase())
            this.aliasKeys.add(key.lowercase())
            this.requiredKeys.addAll(requiredKeys)
        }
    }

    /**
     * Helper function that allows a lorebook entry to be added using an object of its type. Useful for copies,
     * cleanup and re-adds and other in place edits. Internally wraps addLoreBookEntry
     *
     * @see addLoreBookEntry
     */
    fun addLoreBookEntryWithObject(lorebook: LoreBook)
    {
        addLoreBookEntry(
            lorebook.key,
            lorebook.value,
            lorebook.weight,
            lorebook.linkedKeys,
            lorebook.aliasKeys,
            lorebook.requiredKeys)
    }

    /**
     * Helper function to find a lorebook entry by key, or alias.
     */
    fun findLoreBookEntry(key: String) : LoreBook?
    {
        if(loreBookKeys.containsKey(key.uppercase())) return loreBookKeys[key.uppercase()]

        if(loreBookKeys.containsKey(key.lowercase())) return loreBookKeys[key.lowercase()]

        if(loreBookKeys.containsKey(key)) return loreBookKeys[key]

        //Try to find every alias key.
        for((_, loreBookEntry) in loreBookKeys)
        {
            if(loreBookEntry.aliasKeys.contains(key.uppercase())) return loreBookEntry

            if(loreBookEntry.aliasKeys.contains(key.lowercase())) return loreBookEntry

            if(loreBookEntry.aliasKeys.contains(key)) return loreBookEntry
        }

        return null
    }
    
    /**
     * Merges another context window into this one, adding missing lorebook entries and combining context elements
     * @param other The context window to merge from
     * @param emplaceLoreBookKeys Weather to outright replace existing lorebook keys when a key of that name is found.
     * This is useful for lorebook schemes where the llm has been ordered to append, or update the value of the key
     * as context continues forward. Generally, this is the most likely case so this value will be defaulted to
     * true.
     * @param appendKeys Alternate key scheme where the contents of existing keys gets appended by the new key.
     * This is useful in cases where you don't want to allow an automatic llm scanner agent to stomp existing values
     * and only add new content to a lorebook key's context. If true, this will ignore emplaceLoreBookKeys normal
     * behavior.
     * @param emplaceConverseHistory Whether to merge converse history from the source context window. When false,
     * converse history is not merged at all. When true, behavior depends on onlyEmplaceIfNull parameter.
     * @param onlyEmplaceIfNull When used with emplaceConverseHistory=true, controls merge behavior. If true,
     * converse history is only copied when the target context window has empty conversation history. If false,
     * the entire target conversation history is replaced with the source conversation history. This parameter
     * is ignored when emplaceConverseHistory=false.
     */
    fun merge(other: ContextWindow,
              emplaceLoreBookKeys: Boolean = true,
              appendKeys : Boolean = false,
              emplaceConverseHistory: Boolean = false,
              onlyEmplaceIfNull: Boolean = false)
    {
        // Add missing lorebook entries
        other.loreBookKeys.forEach { (key, loreBook) ->
            if(!loreBookKeys.containsKey(key))
            {
                loreBookKeys[key] = loreBook
            }

            /**
             * Override default emplacement scheme if true. Append any lorebook content instead of outright
             * replacing it.
              */
            else if(appendKeys)
            {
                 loreBookKeys[key]?.value = loreBookKeys[key]?.value + " ${loreBook.value}"
            }


            /**
             * Apply key emplacement scheme if emplacement has been set to true. This ensures lorebook keys do not
             * become immutable. With emplacement off, lorebook keys cannot be changed automatically, updated,
             * or improved without coder intervention.
             */
            else if(emplaceLoreBookKeys)
            {
                loreBookKeys[key] = loreBook
            }

            //Merge any new alias, or linked keys in.
            loreBookKeys[key]?.linkedKeys  = combine(loreBookKeys[key]?.linkedKeys?.toList() ?: mutableListOf<String>(), loreBook.linkedKeys).toMutableList()
            loreBookKeys[key]?.aliasKeys  = combine(loreBookKeys[key]?.aliasKeys?.toList() ?: mutableListOf<String>(), loreBook.aliasKeys).toMutableList()
            loreBookKeys[key]?.requiredKeys  = combine(loreBookKeys[key]?.requiredKeys?.toList() ?: mutableListOf<String>(), loreBook.requiredKeys).toMutableList()
        }
        
        // Merge context elements, skipping duplicates
        other.contextElements.forEach { element ->
            if(!contextElements.contains(element))
            {
                contextElements.add(element)
            }
        }
        
        // Handle converse history merging based on parameters
        if(emplaceConverseHistory)
        {
            if(onlyEmplaceIfNull && converseHistory.history.isEmpty())
            {
                // Copy converse history only if target is empty
                converseHistory.history.addAll(other.converseHistory.history)
            }
            else if(!onlyEmplaceIfNull)
            {
                // Replace entire converse history
                converseHistory.history.clear()
                converseHistory.history.addAll(other.converseHistory.history)
            }
        }
    }
    
    /**
     * Truncates the contextElements array to fit within token budget using specified truncation strategy
     * @param maxTokens Maximum token budget for context elements
     * @param multiplyWindowSizeBy Multiplier for window size calculation
     * @param truncateSettings Truncation strategy (TruncateTop, TruncateBottom, TruncateMiddle)
     * @param countSubWordsInFirstWord Token counting parameter - count subwords in first word
     * @param favorWholeWords Token counting parameter - prefer whole words over subwords
     * @param countOnlyFirstWordFound Token counting parameter - only count first word match
     * @param splitForNonWordChar Token counting parameter - split on non-word characters
     * @param alwaysSplitIfWholeWordExists Token counting parameter - always split if whole word exists
     * @param countSubWordsIfSplit Token counting parameter - count subwords after splitting
     * @param nonWordSplitCount Token counting parameter - character count per token for non-words
     */
    fun truncateContextElements(
        maxTokens: Int,
        multiplyWindowSizeBy: Int,
        truncateSettings: com.TTT.Enums.ContextWindowSettings,
        countSubWordsInFirstWord: Boolean = true,
        favorWholeWords: Boolean = true,
        countOnlyFirstWordFound: Boolean = false,
        splitForNonWordChar: Boolean = true,
        alwaysSplitIfWholeWordExists: Boolean = false,
        countSubWordsIfSplit: Boolean = false,
        nonWordSplitCount: Int = 4,
        tokenCountingBias: Double = 0.0,
        inputText: String = "",
        preserveTextMatches: Boolean = false
    )
    {
        if(maxTokens <= 0) return

        if(preserveTextMatches && inputText.isNotBlank())
        {
            val inputWords = inputText.lowercase()
                .split(Regex("\\W+"))
                .filter { it.isNotBlank() }

            if(inputWords.isNotEmpty() && contextElements.isNotEmpty())
            {
                val (textMatching, regular) = contextElements.partition { element ->
                    val lowerElement = element.lowercase()
                    inputWords.any { word -> lowerElement.contains(word) }
                }

                val matchingTokens = textMatching.sumOf { element ->
                    Dictionary.countTokens(
                        element,
                        countSubWordsInFirstWord,
                        favorWholeWords,
                        countOnlyFirstWordFound,
                        splitForNonWordChar,
                        alwaysSplitIfWholeWordExists,
                        countSubWordsIfSplit,
                        nonWordSplitCount,
                        tokenCountingBias
                    )
                }

                val preservedMatching = if(matchingTokens <= maxTokens) {
                    textMatching
                }
                else
                {
                    Dictionary.truncate(
                        textMatching,
                        maxTokens,
                        multiplyWindowSizeBy,
                        truncateSettings,
                        countSubWordsInFirstWord,
                        favorWholeWords,
                        countOnlyFirstWordFound,
                        splitForNonWordChar,
                        alwaysSplitIfWholeWordExists,
                        countSubWordsIfSplit,
                        nonWordSplitCount,
                        tokenCountingBias
                    )
                }

                val usedTokens = preservedMatching.sumOf { element ->
                    Dictionary.countTokens(
                        element,
                        countSubWordsInFirstWord,
                        favorWholeWords,
                        countOnlyFirstWordFound,
                        splitForNonWordChar,
                        alwaysSplitIfWholeWordExists,
                        countSubWordsIfSplit,
                        nonWordSplitCount,
                        tokenCountingBias
                    )
                }

                val remainingBudget = maxTokens - usedTokens
                val preservedRegular = if(remainingBudget > 0 && regular.isNotEmpty()) {
                    Dictionary.truncate(
                        regular,
                        remainingBudget,
                        multiplyWindowSizeBy,
                        truncateSettings,
                        countSubWordsInFirstWord,
                        favorWholeWords,
                        countOnlyFirstWordFound,
                        splitForNonWordChar,
                        alwaysSplitIfWholeWordExists,
                        countSubWordsIfSplit,
                        nonWordSplitCount,
                        tokenCountingBias
                    )
                }
                else
                {
                    emptyList()
                }

                contextElements = (preservedMatching + preservedRegular).toMutableList()
                return
            }
        }

        contextElements = Dictionary.truncate(
            contextElements,
            maxTokens,
            multiplyWindowSizeBy = multiplyWindowSizeBy,
            truncateSettings,
            countSubWordsInFirstWord,
            favorWholeWords,
            countOnlyFirstWordFound,
            splitForNonWordChar,
            alwaysSplitIfWholeWordExists,
            countSubWordsIfSplit,
            nonWordSplitCount,
            tokenCountingBias
        ).toMutableList()
    }
    
    /**
     * Wrapper for selectAndTruncateContext using TruncationSettings.
     * 
     * @param text Input text to scan for matching lorebook keys
     * @param totalTokenBudget Total token budget to divide between lorebook and context elements
     * @param truncateSettings Truncation strategy for context elements
     * @param settings TruncationSettings containing all tokenization parameters
     */
    fun selectAndTruncateContext(
        text: String,
        totalTokenBudget: Int,
        truncateSettings: com.TTT.Enums.ContextWindowSettings,
        settings: com.TTT.Pipe.TruncationSettings,
        fillMode: Boolean = false,
        fillAndSplitMode: Boolean = false,
        preserveTextMatches: Boolean = false
    ) {
        selectAndTruncateContext(
            text,
            totalTokenBudget,
            settings.multiplyWindowSizeBy,
            truncateSettings,
            settings.countSubWordsInFirstWord,
            settings.favorWholeWords,
            settings.countOnlyFirstWordFound,
            settings.splitForNonWordChar,
            settings.alwaysSplitIfWholeWordExists,
            settings.countSubWordsIfSplit,
            settings.nonWordSplitCount,
            settings.tokenCountingBias,
            fillMode,
            fillAndSplitMode,
            preserveTextMatches
        )
    }

    /**
     * Wrapper for remote-aware context truncation using [TruncationSettings].
     *
     * @param text Input text to scan for matching lorebook keys.
     * @param totalTokenBudget Total token budget to divide between lorebook and context elements.
     * @param truncateSettings Truncation strategy for context elements.
     * @param settings TruncationSettings containing all tokenization parameters.
     * @param fillMode If true, fill the remaining lorebook budget by weight after priority selection.
     * @param preserveTextMatches If true, preserve context/history entries that match the input text first.
     */
    suspend fun selectAndTruncateContextSuspend(
        text: String,
        totalTokenBudget: Int,
        truncateSettings: com.TTT.Enums.ContextWindowSettings,
        settings: com.TTT.Pipe.TruncationSettings,
        fillMode: Boolean = false,
        fillAndSplitMode: Boolean = false,
        preserveTextMatches: Boolean = false
    )
    {
        selectAndTruncateContextSuspend(
            text,
            totalTokenBudget,
            settings.multiplyWindowSizeBy,
            truncateSettings,
            settings.countSubWordsInFirstWord,
            settings.favorWholeWords,
            settings.countOnlyFirstWordFound,
            settings.splitForNonWordChar,
            settings.alwaysSplitIfWholeWordExists,
            settings.countSubWordsIfSplit,
            settings.nonWordSplitCount,
            settings.tokenCountingBias,
            fillMode,
            fillAndSplitMode,
            preserveTextMatches
        )
    }

    /**
     * Combined helper that manages token budget between lorebook, context elements, and conversation history.
     * Uses three-way split when converseHistory is present, falls back to two-way split when empty.
     * Truncates this context object in place by filtering lorebook keys, context elements, and conversation history.
     * @param text Input text to scan for matching lorebook keys
     * @param totalTokenBudget Total token budget to divide between components
     * @param multiplyWindowSizeBy Multiplier for window size calculation
     * @param truncateSettings Truncation strategy for context elements and conversation history
     * @param countSubWordsInFirstWord Token counting parameter
     * @param favorWholeWords Token counting parameter
     * @param countOnlyFirstWordFound Token counting parameter
     * @param splitForNonWordChar Token counting parameter
     * @param alwaysSplitIfWholeWordExists Token counting parameter
     * @param countSubWordsIfSplit Token counting parameter
     * @param nonWordSplitCount Token counting parameter
     */
    fun selectAndTruncateContext(
        text: String,
        totalTokenBudget: Int,
        multiplyWindowSizeBy: Int,
        truncateSettings: com.TTT.Enums.ContextWindowSettings,
        countSubWordsInFirstWord: Boolean = true,
        favorWholeWords: Boolean = true,
        countOnlyFirstWordFound: Boolean = false,
        splitForNonWordChar: Boolean = true,
        alwaysSplitIfWholeWordExists: Boolean = false,
        countSubWordsIfSplit: Boolean = false,
        nonWordSplitCount: Int = 4,
        tokenCountingBias: Double = 0.0,
        fillMode: Boolean = false,
        fillAndSplitMode: Boolean = false,
        preserveTextMatches: Boolean = false
    )
    {

        if(totalTokenBudget == 0) return

        var multipliedTokenBudget = totalTokenBudget
        if(multiplyWindowSizeBy > 0)
        {
            multipliedTokenBudget = totalTokenBudget * multiplyWindowSizeBy
        }

        val hasContextElements = contextElements.isNotEmpty()
        val hasConverseHistory = converseHistory.history.isNotEmpty()

        if(fillMode || fillAndSplitMode)
        {
            if(multipliedTokenBudget <= 0) return

            val hasRestContent = hasContextElements || hasConverseHistory
            val lorebookBudget = if(fillAndSplitMode && hasRestContent) multipliedTokenBudget / 2 else multipliedTokenBudget

            val selectedLorebookKeys = selectAndFillLoreBookContext(
                text,
                lorebookBudget,
                countSubWordsInFirstWord,
                favorWholeWords,
                countOnlyFirstWordFound,
                splitForNonWordChar,
                alwaysSplitIfWholeWordExists,
                countSubWordsIfSplit,
                nonWordSplitCount,
                tokenCountingBias
            )

            val lorebookTokensUsed = selectedLorebookKeys.sumOf { key ->
                val loreBook = loreBookKeys[key]
                if(loreBook == null) return@sumOf 0
                Dictionary.countTokens(
                    loreBook.value,
                    countSubWordsInFirstWord,
                    favorWholeWords,
                    countOnlyFirstWordFound,
                    splitForNonWordChar,
                    alwaysSplitIfWholeWordExists,
                    countSubWordsIfSplit,
                    nonWordSplitCount,
                    tokenCountingBias
                )
            }

            loreBookKeys = loreBookKeys.filterKeys { it in selectedLorebookKeys }.toMutableMap()

            val remainingBudget = multipliedTokenBudget - lorebookTokensUsed

            if(remainingBudget <= 0) return

            if(hasContextElements && hasConverseHistory)
            {
                val contextBudget = remainingBudget / 2
                truncateContextElements(
                    contextBudget, 1, truncateSettings,
                    countSubWordsInFirstWord, favorWholeWords, countOnlyFirstWordFound,
                    splitForNonWordChar, alwaysSplitIfWholeWordExists, countSubWordsIfSplit, nonWordSplitCount, tokenCountingBias,
                    inputText = text,
                    preserveTextMatches = preserveTextMatches
                )

                val historyBudget = remainingBudget - contextBudget
                truncateConverseHistory(
                    historyBudget, 1, truncateSettings,
                    countSubWordsInFirstWord, favorWholeWords, countOnlyFirstWordFound,
                    splitForNonWordChar, alwaysSplitIfWholeWordExists, countSubWordsIfSplit, nonWordSplitCount, tokenCountingBias,
                    inputText = text,
                    preserveTextMatches = preserveTextMatches
                )
            }

            else if(hasContextElements)
            {
                truncateContextElements(
                    remainingBudget, 1, truncateSettings,
                    countSubWordsInFirstWord, favorWholeWords, countOnlyFirstWordFound,
                    splitForNonWordChar, alwaysSplitIfWholeWordExists, countSubWordsIfSplit, nonWordSplitCount, tokenCountingBias,
                    inputText = text,
                    preserveTextMatches = preserveTextMatches
                )
            }

            else if(hasConverseHistory)
            {
                truncateConverseHistory(
                    remainingBudget, 1, truncateSettings,
                    countSubWordsInFirstWord, favorWholeWords, countOnlyFirstWordFound,
                    splitForNonWordChar, alwaysSplitIfWholeWordExists, countSubWordsIfSplit, nonWordSplitCount, tokenCountingBias,
                    inputText = text,
                    preserveTextMatches = preserveTextMatches
                )
            }

            return
        }

        if(!hasContextElements && !hasConverseHistory)
        {
            // Only lorebook - give it full budget
            val selectedLorebookKeys = selectLoreBookContext(
                text, multipliedTokenBudget,
                countSubWordsInFirstWord, favorWholeWords, countOnlyFirstWordFound,
                splitForNonWordChar, alwaysSplitIfWholeWordExists, countSubWordsIfSplit, nonWordSplitCount, tokenCountingBias
            )
            loreBookKeys = loreBookKeys.filterKeys { it in selectedLorebookKeys }.toMutableMap()
        }

        else if(!hasContextElements && hasConverseHistory)
        {
            // Two-way split: lorebook and converseHistory
            val halfBudget = multipliedTokenBudget / 2

            truncateConverseHistory(
                halfBudget, 1, truncateSettings,
                countSubWordsInFirstWord, favorWholeWords, countOnlyFirstWordFound,
                splitForNonWordChar, alwaysSplitIfWholeWordExists, countSubWordsIfSplit, nonWordSplitCount, tokenCountingBias,
                inputText = text,
                preserveTextMatches = preserveTextMatches
            )

            val conversationTokensUsed = countConverseHistoryTokens(
                countSubWordsInFirstWord, favorWholeWords, countOnlyFirstWordFound,
                splitForNonWordChar, alwaysSplitIfWholeWordExists, countSubWordsIfSplit, nonWordSplitCount, tokenCountingBias
            )

            val lorebookBudget = multipliedTokenBudget - conversationTokensUsed
            val selectedLorebookKeys = selectLoreBookContext(
                text, lorebookBudget,
                countSubWordsInFirstWord, favorWholeWords, countOnlyFirstWordFound,
                splitForNonWordChar, alwaysSplitIfWholeWordExists, countSubWordsIfSplit, nonWordSplitCount, tokenCountingBias
            )

            loreBookKeys = loreBookKeys.filterKeys { it in selectedLorebookKeys }.toMutableMap()
        }

        else if(hasContextElements && !hasConverseHistory)
        {
            // Two-way split: lorebook and contextElements
            val halfBudget = multipliedTokenBudget / 2

            truncateContextElements(
                halfBudget, 1, truncateSettings,
                countSubWordsInFirstWord, favorWholeWords, countOnlyFirstWordFound,
                splitForNonWordChar, alwaysSplitIfWholeWordExists, countSubWordsIfSplit, nonWordSplitCount, tokenCountingBias,
                inputText = text,
                preserveTextMatches = preserveTextMatches
            )

            val contextTokensUsed = contextElements.sumOf { element ->
                Dictionary.countTokens(
                    element, countSubWordsInFirstWord, favorWholeWords,
                    countOnlyFirstWordFound, splitForNonWordChar, alwaysSplitIfWholeWordExists,
                    countSubWordsIfSplit, nonWordSplitCount, tokenCountingBias
                )
            }

            val lorebookBudget = multipliedTokenBudget - contextTokensUsed
            val selectedLorebookKeys = selectLoreBookContext(
                text, lorebookBudget,
                countSubWordsInFirstWord, favorWholeWords, countOnlyFirstWordFound,
                splitForNonWordChar, alwaysSplitIfWholeWordExists, countSubWordsIfSplit, nonWordSplitCount, tokenCountingBias
            )

            loreBookKeys = loreBookKeys.filterKeys { it in selectedLorebookKeys }.toMutableMap()
        }

        else
        {
            // Three-way split: lorebook, contextElements, and converseHistory
            val thirdBudget = multipliedTokenBudget / 3

            // Truncate each component to its allocated budget
            truncateContextElements(
                thirdBudget, 1, truncateSettings,
                countSubWordsInFirstWord, favorWholeWords, countOnlyFirstWordFound,
                splitForNonWordChar, alwaysSplitIfWholeWordExists, countSubWordsIfSplit, nonWordSplitCount, tokenCountingBias,
                inputText = text,
                preserveTextMatches = preserveTextMatches
            )

            truncateConverseHistory(
                thirdBudget, 1, truncateSettings,
                countSubWordsInFirstWord, favorWholeWords, countOnlyFirstWordFound,
                splitForNonWordChar, alwaysSplitIfWholeWordExists, countSubWordsIfSplit, nonWordSplitCount, tokenCountingBias,
                inputText = text,
                preserveTextMatches = preserveTextMatches
            )

            // Calculate actual tokens used by truncated components
            val contextTokensUsed = contextElements.sumOf { element ->
                Dictionary.countTokens(
                    element, countSubWordsInFirstWord, favorWholeWords,
                    countOnlyFirstWordFound, splitForNonWordChar, alwaysSplitIfWholeWordExists,
                    countSubWordsIfSplit, nonWordSplitCount, tokenCountingBias
                )
            }

            val conversationTokensUsed = countConverseHistoryTokens(
                countSubWordsInFirstWord, favorWholeWords, countOnlyFirstWordFound,
                splitForNonWordChar, alwaysSplitIfWholeWordExists, countSubWordsIfSplit, nonWordSplitCount, tokenCountingBias
            )

            // Give lorebook remaining budget (third + unused tokens from other components)
            val lorebookBudget = multipliedTokenBudget - contextTokensUsed - conversationTokensUsed
            val selectedLorebookKeys = selectLoreBookContext(
                text, lorebookBudget,
                countSubWordsInFirstWord, favorWholeWords, countOnlyFirstWordFound,
                splitForNonWordChar, alwaysSplitIfWholeWordExists, countSubWordsIfSplit, nonWordSplitCount, tokenCountingBias
            )

            loreBookKeys = loreBookKeys.filterKeys { it in selectedLorebookKeys }.toMutableMap()
        }
    }

    /**
     * Combined helper that manages token budget between lorebook, context elements, and conversation history while
     * honoring remote lock state for lorebook selection.
     *
     * @param text Input text to scan for matching lorebook keys.
     * @param totalTokenBudget Total token budget to divide between components.
     * @param multiplyWindowSizeBy Multiplier for window size calculation.
     * @param truncateSettings Truncation strategy for context elements and conversation history.
     * @param countSubWordsInFirstWord Token counting parameter.
     * @param favorWholeWords Token counting parameter.
     * @param countOnlyFirstWordFound Token counting parameter.
     * @param splitForNonWordChar Token counting parameter.
     * @param alwaysSplitIfWholeWordExists Token counting parameter.
     * @param countSubWordsIfSplit Token counting parameter.
     * @param nonWordSplitCount Token counting parameter.
     * @param tokenCountingBias Token counting bias.
     * @param fillMode If true, fill the remaining lorebook budget by weight after priority selection.
     * @param preserveTextMatches If true, preserve context/history entries that match the input text first.
     */
    suspend fun selectAndTruncateContextSuspend(
        text: String,
        totalTokenBudget: Int,
        multiplyWindowSizeBy: Int,
        truncateSettings: com.TTT.Enums.ContextWindowSettings,
        countSubWordsInFirstWord: Boolean = true,
        favorWholeWords: Boolean = true,
        countOnlyFirstWordFound: Boolean = false,
        splitForNonWordChar: Boolean = true,
        alwaysSplitIfWholeWordExists: Boolean = false,
        countSubWordsIfSplit: Boolean = false,
        nonWordSplitCount: Int = 4,
        tokenCountingBias: Double = 0.0,
        fillMode: Boolean = false,
        fillAndSplitMode: Boolean = false,
        preserveTextMatches: Boolean = false
    )
    {
        if(totalTokenBudget == 0) return

        var multipliedTokenBudget = totalTokenBudget
        if(multiplyWindowSizeBy > 0)
        {
            multipliedTokenBudget = totalTokenBudget * multiplyWindowSizeBy
        }

        val hasContextElements = contextElements.isNotEmpty()
        val hasConverseHistory = converseHistory.history.isNotEmpty()

        if(fillMode || fillAndSplitMode)
        {
            if(multipliedTokenBudget <= 0) return

            val hasRestContent = hasContextElements || hasConverseHistory
            val lorebookBudget = if(fillAndSplitMode && hasRestContent) multipliedTokenBudget / 2 else multipliedTokenBudget

            val selectedLorebookKeys = selectAndFillLoreBookContextSuspend(
                text,
                lorebookBudget,
                countSubWordsInFirstWord,
                favorWholeWords,
                countOnlyFirstWordFound,
                splitForNonWordChar,
                alwaysSplitIfWholeWordExists,
                countSubWordsIfSplit,
                nonWordSplitCount,
                tokenCountingBias
            )

            val lorebookTokensUsed = selectedLorebookKeys.sumOf { key ->
                val loreBook = loreBookKeys[key]
                if(loreBook == null) return@sumOf 0
                Dictionary.countTokens(
                    loreBook.value,
                    countSubWordsInFirstWord,
                    favorWholeWords,
                    countOnlyFirstWordFound,
                    splitForNonWordChar,
                    alwaysSplitIfWholeWordExists,
                    countSubWordsIfSplit,
                    nonWordSplitCount,
                    tokenCountingBias
                )
            }

            loreBookKeys = loreBookKeys.filterKeys { it in selectedLorebookKeys }.toMutableMap()

            val remainingBudget = multipliedTokenBudget - lorebookTokensUsed

            if(remainingBudget <= 0) return

            if(hasContextElements && hasConverseHistory)
            {
                val contextBudget = remainingBudget / 2
                truncateContextElements(
                    contextBudget,
                    1,
                    truncateSettings,
                    countSubWordsInFirstWord,
                    favorWholeWords,
                    countOnlyFirstWordFound,
                    splitForNonWordChar,
                    alwaysSplitIfWholeWordExists,
                    countSubWordsIfSplit,
                    nonWordSplitCount,
                    tokenCountingBias,
                    inputText = text,
                    preserveTextMatches = preserveTextMatches
                )

                val historyBudget = remainingBudget - contextBudget
                truncateConverseHistory(
                    historyBudget,
                    1,
                    truncateSettings,
                    countSubWordsInFirstWord,
                    favorWholeWords,
                    countOnlyFirstWordFound,
                    splitForNonWordChar,
                    alwaysSplitIfWholeWordExists,
                    countSubWordsIfSplit,
                    nonWordSplitCount,
                    tokenCountingBias,
                    inputText = text,
                    preserveTextMatches = preserveTextMatches
                )
            }

            else if(hasContextElements)
            {
                truncateContextElements(
                    remainingBudget,
                    1,
                    truncateSettings,
                    countSubWordsInFirstWord,
                    favorWholeWords,
                    countOnlyFirstWordFound,
                    splitForNonWordChar,
                    alwaysSplitIfWholeWordExists,
                    countSubWordsIfSplit,
                    nonWordSplitCount,
                    tokenCountingBias,
                    inputText = text,
                    preserveTextMatches = preserveTextMatches
                )
            }

            else if(hasConverseHistory)
            {
                truncateConverseHistory(
                    remainingBudget,
                    1,
                    truncateSettings,
                    countSubWordsInFirstWord,
                    favorWholeWords,
                    countOnlyFirstWordFound,
                    splitForNonWordChar,
                    alwaysSplitIfWholeWordExists,
                    countSubWordsIfSplit,
                    nonWordSplitCount,
                    tokenCountingBias,
                    inputText = text,
                    preserveTextMatches = preserveTextMatches
                )
            }

            return
        }

        if(!hasContextElements && !hasConverseHistory)
        {
            val selectedLorebookKeys = selectLoreBookContextSuspend(
                text,
                multipliedTokenBudget,
                countSubWordsInFirstWord,
                favorWholeWords,
                countOnlyFirstWordFound,
                splitForNonWordChar,
                alwaysSplitIfWholeWordExists,
                countSubWordsIfSplit,
                nonWordSplitCount,
                tokenCountingBias
            )
            loreBookKeys = loreBookKeys.filterKeys { it in selectedLorebookKeys }.toMutableMap()
        }

        else if(!hasContextElements && hasConverseHistory)
        {
            val halfBudget = multipliedTokenBudget / 2

            truncateConverseHistory(
                halfBudget,
                1,
                truncateSettings,
                countSubWordsInFirstWord,
                favorWholeWords,
                countOnlyFirstWordFound,
                splitForNonWordChar,
                alwaysSplitIfWholeWordExists,
                countSubWordsIfSplit,
                nonWordSplitCount,
                tokenCountingBias,
                inputText = text,
                preserveTextMatches = preserveTextMatches
            )

            val conversationTokensUsed = countConverseHistoryTokens(
                countSubWordsInFirstWord,
                favorWholeWords,
                countOnlyFirstWordFound,
                splitForNonWordChar,
                alwaysSplitIfWholeWordExists,
                countSubWordsIfSplit,
                nonWordSplitCount,
                tokenCountingBias
            )

            val lorebookBudget = multipliedTokenBudget - conversationTokensUsed
            val selectedLorebookKeys = selectLoreBookContextSuspend(
                text,
                lorebookBudget,
                countSubWordsInFirstWord,
                favorWholeWords,
                countOnlyFirstWordFound,
                splitForNonWordChar,
                alwaysSplitIfWholeWordExists,
                countSubWordsIfSplit,
                nonWordSplitCount,
                tokenCountingBias
            )

            loreBookKeys = loreBookKeys.filterKeys { it in selectedLorebookKeys }.toMutableMap()
        }

        else if(hasContextElements && !hasConverseHistory)
        {
            val halfBudget = multipliedTokenBudget / 2

            truncateContextElements(
                halfBudget,
                1,
                truncateSettings,
                countSubWordsInFirstWord,
                favorWholeWords,
                countOnlyFirstWordFound,
                splitForNonWordChar,
                alwaysSplitIfWholeWordExists,
                countSubWordsIfSplit,
                nonWordSplitCount,
                tokenCountingBias,
                inputText = text,
                preserveTextMatches = preserveTextMatches
            )

            val contextTokensUsed = contextElements.sumOf { element ->
                Dictionary.countTokens(
                    element,
                    countSubWordsInFirstWord,
                    favorWholeWords,
                    countOnlyFirstWordFound,
                    splitForNonWordChar,
                    alwaysSplitIfWholeWordExists,
                    countSubWordsIfSplit,
                    nonWordSplitCount,
                    tokenCountingBias
                )
            }

            val lorebookBudget = multipliedTokenBudget - contextTokensUsed
            val selectedLorebookKeys = selectLoreBookContextSuspend(
                text,
                lorebookBudget,
                countSubWordsInFirstWord,
                favorWholeWords,
                countOnlyFirstWordFound,
                splitForNonWordChar,
                alwaysSplitIfWholeWordExists,
                countSubWordsIfSplit,
                nonWordSplitCount,
                tokenCountingBias
            )

            loreBookKeys = loreBookKeys.filterKeys { it in selectedLorebookKeys }.toMutableMap()
        }

        else
        {
            val thirdBudget = multipliedTokenBudget / 3

            truncateContextElements(
                thirdBudget,
                1,
                truncateSettings,
                countSubWordsInFirstWord,
                favorWholeWords,
                countOnlyFirstWordFound,
                splitForNonWordChar,
                alwaysSplitIfWholeWordExists,
                countSubWordsIfSplit,
                nonWordSplitCount,
                tokenCountingBias,
                inputText = text,
                preserveTextMatches = preserveTextMatches
            )

            truncateConverseHistory(
                thirdBudget,
                1,
                truncateSettings,
                countSubWordsInFirstWord,
                favorWholeWords,
                countOnlyFirstWordFound,
                splitForNonWordChar,
                alwaysSplitIfWholeWordExists,
                countSubWordsIfSplit,
                nonWordSplitCount,
                tokenCountingBias,
                inputText = text,
                preserveTextMatches = preserveTextMatches
            )

            val contextTokensUsed = contextElements.sumOf { element ->
                Dictionary.countTokens(
                    element,
                    countSubWordsInFirstWord,
                    favorWholeWords,
                    countOnlyFirstWordFound,
                    splitForNonWordChar,
                    alwaysSplitIfWholeWordExists,
                    countSubWordsIfSplit,
                    nonWordSplitCount,
                    tokenCountingBias
                )
            }

            val conversationTokensUsed = countConverseHistoryTokens(
                countSubWordsInFirstWord,
                favorWholeWords,
                countOnlyFirstWordFound,
                splitForNonWordChar,
                alwaysSplitIfWholeWordExists,
                countSubWordsIfSplit,
                nonWordSplitCount,
                tokenCountingBias
            )

            val lorebookBudget = multipliedTokenBudget - contextTokensUsed - conversationTokensUsed
            val selectedLorebookKeys = selectLoreBookContextSuspend(
                text,
                lorebookBudget,
                countSubWordsInFirstWord,
                favorWholeWords,
                countOnlyFirstWordFound,
                splitForNonWordChar,
                alwaysSplitIfWholeWordExists,
                countSubWordsIfSplit,
                nonWordSplitCount,
                tokenCountingBias
            )

            loreBookKeys = loreBookKeys.filterKeys { it in selectedLorebookKeys }.toMutableMap()
        }
    }
    
    /**
     * Combines lorebook values and context elements into a single string, then truncates using Dictionary.truncate
     * @param text Input text to scan for matching lorebook keys
     * @param totalTokenBudget Total token budget for the combined content
     * @param multiplyWindowSizeBy Multiplier for window size calculation
     * @param truncateSettings Truncation strategy for the combined string
     * @param countSubWordsInFirstWord Token counting parameter
     * @param favorWholeWords Token counting parameter
     * @param countOnlyFirstWordFound Token counting parameter
     * @param splitForNonWordChar Token counting parameter
     * @param alwaysSplitIfWholeWordExists Token counting parameter
     * @param countSubWordsIfSplit Token counting parameter
     * @param nonWordSplitCount Token counting parameter
     * @return Truncated combined string
     */
    fun combineAndTruncateAsString(
        text: String,
        totalTokenBudget: Int,
        multiplyWindowSizeBy: Int,
        truncateSettings: com.TTT.Enums.ContextWindowSettings,
        countSubWordsInFirstWord: Boolean = true,
        favorWholeWords: Boolean = true,
        countOnlyFirstWordFound: Boolean = false,
        splitForNonWordChar: Boolean = true,
        alwaysSplitIfWholeWordExists: Boolean = false,
        countSubWordsIfSplit: Boolean = false,
        nonWordSplitCount: Int = 4,
        tokenCountingBias: Double = 0.0
    ): String {
        // Get selected lorebook keys
        val selectedKeys = selectLoreBookContext(
            text, totalTokenBudget,
            countSubWordsInFirstWord, favorWholeWords, countOnlyFirstWordFound,
            splitForNonWordChar, alwaysSplitIfWholeWordExists, countSubWordsIfSplit, nonWordSplitCount, tokenCountingBias
        )
        
        // Combine lorebook values into single string
        val lorebookString = selectedKeys.mapNotNull { key ->
            loreBookKeys[key]?.value
        }.joinToString(" ")
        
        // Combine context elements into single string
        val contextString = contextElements.joinToString(" ")
        
        // Combine both into final string
        val combinedString = listOf(lorebookString, contextString)
            .filter { it.isNotBlank() }
            .joinToString(" ")
        
        // Truncate combined string using Dictionary
        if(multiplyWindowSizeBy == 0) return combinedString
        
        return Dictionary.truncate(
            combinedString,
            (totalTokenBudget + multiplyWindowSizeBy - 1) / multiplyWindowSizeBy,
            multiplyWindowSizeBy,
            truncateSettings,
            countSubWordsInFirstWord, favorWholeWords, countOnlyFirstWordFound,
            splitForNonWordChar, alwaysSplitIfWholeWordExists, countSubWordsIfSplit, nonWordSplitCount, tokenCountingBias
        )
    }

    /**
     * Helper function to simplify combineAndTruncateAsString with settings
     * @param text Input text to scan for matching lorebook keys
     * @param tokenBudget Total token budget for the combined content
     * @param multiplyBy Multiplier for window size calculation
     * @param truncateMethod Truncation strategy for the combined string
     * @param settings Truncation settings for the combined string
     */
    fun combineAndTruncateAsStringWithSettings(
        text: String = "",
        tokenBudget: Int = 0,
        settings: TruncationSettings = TruncationSettings(),
        truncateMethod: ContextWindowSettings = ContextWindowSettings.TruncateTop,
        multiplyBy: Int = 0,
        fillMode: Boolean = false) : String
    {
        if(fillMode)
        {
            val selectedKeys = selectAndFillLoreBookContextWithSettings(settings, text, tokenBudget)
            loreBookKeys = loreBookKeys.filterKeys { it in selectedKeys }.toMutableMap()
        }
       return combineAndTruncateAsString(
            text,
            tokenBudget,
            multiplyBy,
            truncateMethod,
            settings.countSubWordsInFirstWord,
            settings.favorWholeWords,
            settings.countOnlyFirstWordFound,
            settings.splitForNonWordChar,
            settings.alwaysSplitIfWholeWordExists,
            settings.countSubWordsIfSplit,
            settings.nonWordSplitCount,
            settings.tokenCountingBias
        )
    }

    /**
     * Helper function to simplify remote-aware combine-and-truncate behavior with settings.
     *
     * @param text Input text to scan for matching lorebook keys.
     * @param tokenBudget Total token budget for the combined content.
     * @param settings TruncationSettings for the combined string.
     * @param truncateMethod Truncation strategy for the combined string.
     * @param multiplyBy Multiplier for window size calculation.
     * @param fillMode If true, fill the lorebook budget by weight after priority selection.
     * @return Truncated combined string.
     */
    suspend fun combineAndTruncateAsStringWithSettingsSuspend(
        text: String = "",
        tokenBudget: Int = 0,
        settings: TruncationSettings = TruncationSettings(),
        truncateMethod: ContextWindowSettings = ContextWindowSettings.TruncateTop,
        multiplyBy: Int = 0,
        fillMode: Boolean = false
    ) : String
    {
        if(fillMode)
        {
            val selectedKeys = selectAndFillLoreBookContextWithSettingsSuspend(settings, text, tokenBudget)
            loreBookKeys = loreBookKeys.filterKeys { it in selectedKeys }.toMutableMap()
        }

        else
        {
            val selectedKeys = selectLoreBookContextWithSettingsSuspend(settings, text, tokenBudget)
            loreBookKeys = loreBookKeys.filterKeys { it in selectedKeys }.toMutableMap()
        }

        return combineAndTruncateAsString(
            text,
            tokenBudget,
            multiplyBy,
            truncateMethod,
            settings.countSubWordsInFirstWord,
            settings.favorWholeWords,
            settings.countOnlyFirstWordFound,
            settings.splitForNonWordChar,
            settings.alwaysSplitIfWholeWordExists,
            settings.countSubWordsIfSplit,
            settings.nonWordSplitCount,
            settings.tokenCountingBias
        )
    }

    /**
     * Extracts all text content from converseHistory for lorebook key matching.
     * Combines user and assistant messages while maintaining conversation flow context.
     * @return Combined text from all conversation entries
     */
    private fun extractConverseHistoryText(): String
    {
        return converseHistory.history.joinToString(" ") { converseData ->
            converseData.content.text
        }
    }

    /**
     * Counts tokens in converseHistory using Dictionary tokenizer.
     * @param countSubWordsInFirstWord Token counting parameter
     * @param favorWholeWords Token counting parameter
     * @param countOnlyFirstWordFound Token counting parameter
     * @param splitForNonWordChar Token counting parameter
     * @param alwaysSplitIfWholeWordExists Token counting parameter
     * @param countSubWordsIfSplit Token counting parameter
     * @param nonWordSplitCount Token counting parameter
     * @return Total token count for all conversation entries
     */
    private fun countConverseHistoryTokens(
        countSubWordsInFirstWord: Boolean = true,
        favorWholeWords: Boolean = true,
        countOnlyFirstWordFound: Boolean = false,
        splitForNonWordChar: Boolean = true,
        alwaysSplitIfWholeWordExists: Boolean = false,
        countSubWordsIfSplit: Boolean = false,
        nonWordSplitCount: Int = 4,
        tokenCountingBias: Double = 0.0
    ): Int
    {
        return converseHistory.history.sumOf { converseData ->
            Dictionary.countTokens(
                converseData.content.text,
                countSubWordsInFirstWord,
                favorWholeWords,
                countOnlyFirstWordFound,
                splitForNonWordChar,
                alwaysSplitIfWholeWordExists,
                countSubWordsIfSplit,
                nonWordSplitCount,
                tokenCountingBias
            )
        }
    }

    private fun filterConverseEntriesByText(
        entries: List<ConverseData>,
        targetTexts: List<String>
    ): List<ConverseData> {
        if(entries.isEmpty() || targetTexts.isEmpty()) return emptyList()

        val entryQueues = entries.groupBy { it.content.text }
            .mapValues { (_, value) -> ArrayDeque(value) }

        val preservedEntries = mutableListOf<ConverseData>()
        for(text in targetTexts)
        {
            val queue = entryQueues[text]
            if(!queue.isNullOrEmpty())
            {
                preservedEntries.add(queue.removeFirst())
            }
        }

        return preservedEntries
    }

    /**
     * Selects lorebook context entries based on converseHistory content and token budget.
     * Uses conversation text to find matching lorebook keys.
     * @param maxTokens Maximum token budget for selected context
     * @param countSubWordsInFirstWord Token counting parameter
     * @param favorWholeWords Token counting parameter
     * @param countOnlyFirstWordFound Token counting parameter
     * @param splitForNonWordChar Token counting parameter
     * @param alwaysSplitIfWholeWordExists Token counting parameter
     * @param countSubWordsIfSplit Token counting parameter
     * @param nonWordSplitCount Token counting parameter
     * @return List of selected lorebook keys that fit within token budget
     */
    fun selectConverseHistoryLoreBookContext(
        maxTokens: Int,
        countSubWordsInFirstWord: Boolean = true,
        favorWholeWords: Boolean = true,
        countOnlyFirstWordFound: Boolean = false,
        splitForNonWordChar: Boolean = true,
        alwaysSplitIfWholeWordExists: Boolean = false,
        countSubWordsIfSplit: Boolean = false,
        nonWordSplitCount: Int = 4,
        tokenCountingBias: Double = 0.0
    ): List<String>
    {
        val conversationText = extractConverseHistoryText()
        return selectLoreBookContext(
            conversationText,
            maxTokens,
            countSubWordsInFirstWord,
            favorWholeWords,
            countOnlyFirstWordFound,
            splitForNonWordChar,
            alwaysSplitIfWholeWordExists,
            countSubWordsIfSplit,
            nonWordSplitCount,
            tokenCountingBias
        )
    }

    /**
     * Truncates converseHistory to fit within token budget using specified truncation strategy.
     * @param maxTokens Maximum token budget for conversation history
     * @param multiplyWindowSizeBy Multiplier for window size calculation
     * @param truncateSettings Truncation strategy (TruncateTop, TruncateBottom, TruncateMiddle)
     * @param countSubWordsInFirstWord Token counting parameter
     * @param favorWholeWords Token counting parameter
     * @param countOnlyFirstWordFound Token counting parameter
     * @param splitForNonWordChar Token counting parameter
     * @param alwaysSplitIfWholeWordExists Token counting parameter
     * @param countSubWordsIfSplit Token counting parameter
     * @param nonWordSplitCount Token counting parameter
     */
    fun truncateConverseHistory(
        maxTokens: Int,
        multiplyWindowSizeBy: Int,
        truncateSettings: com.TTT.Enums.ContextWindowSettings,
        countSubWordsInFirstWord: Boolean = true,
        favorWholeWords: Boolean = true,
        countOnlyFirstWordFound: Boolean = false,
        splitForNonWordChar: Boolean = true,
        alwaysSplitIfWholeWordExists: Boolean = false,
        countSubWordsIfSplit: Boolean = false,
        nonWordSplitCount: Int = 4,
        tokenCountingBias: Double = 0.0,
        inputText: String = "",
        preserveTextMatches: Boolean = false
    )
    {
        if(maxTokens <= 0) return

        if(preserveTextMatches && inputText.isNotBlank())
        {
            val inputWords = inputText.lowercase()
                .split(Regex("\\W+"))
                .filter { it.isNotBlank() }

            if(inputWords.isNotEmpty() && converseHistory.history.isNotEmpty())
            {
                val textMatchingEntries = converseHistory.history.filter { converseData ->
                    val lowerContent = converseData.content.text.lowercase()
                    inputWords.any { word -> lowerContent.contains(word) }
                }

                val regularEntries = converseHistory.history.filter { it !in textMatchingEntries }

                val matchingTokens = textMatchingEntries.sumOf { converseData ->
                    Dictionary.countTokens(
                        converseData.content.text,
                        countSubWordsInFirstWord,
                        favorWholeWords,
                        countOnlyFirstWordFound,
                        splitForNonWordChar,
                        alwaysSplitIfWholeWordExists,
                        countSubWordsIfSplit,
                        nonWordSplitCount,
                        tokenCountingBias
                    )
                }

                val matchingTexts = textMatchingEntries.map { it.content.text }
                val preservedMatchingTexts = if(matchingTokens <= maxTokens) {
                    matchingTexts
                }
                else
                {
                    Dictionary.truncate(
                        matchingTexts,
                        maxTokens,
                        multiplyWindowSizeBy,
                        truncateSettings,
                        countSubWordsInFirstWord,
                        favorWholeWords,
                        countOnlyFirstWordFound,
                        splitForNonWordChar,
                        alwaysSplitIfWholeWordExists,
                        countSubWordsIfSplit,
                        nonWordSplitCount,
                        tokenCountingBias
                    )
                }

                val usedTokens = preservedMatchingTexts.sumOf { text ->
                    Dictionary.countTokens(
                        text,
                        countSubWordsInFirstWord,
                        favorWholeWords,
                        countOnlyFirstWordFound,
                        splitForNonWordChar,
                        alwaysSplitIfWholeWordExists,
                        countSubWordsIfSplit,
                        nonWordSplitCount,
                        tokenCountingBias
                    )
                }

                val remainingBudget = maxTokens - usedTokens
                val regularTexts = regularEntries.map { it.content.text }
                val preservedRegularTexts = if(remainingBudget > 0 && regularEntries.isNotEmpty()) {
                    Dictionary.truncate(
                        regularTexts,
                        remainingBudget,
                        multiplyWindowSizeBy,
                        truncateSettings,
                        countSubWordsInFirstWord,
                        favorWholeWords,
                        countOnlyFirstWordFound,
                        splitForNonWordChar,
                        alwaysSplitIfWholeWordExists,
                        countSubWordsIfSplit,
                        nonWordSplitCount,
                        tokenCountingBias
                    )
                }
                else
                {
                    emptyList()
                }

                val preservedMatchingEntries = filterConverseEntriesByText(textMatchingEntries, preservedMatchingTexts)
                val preservedRegularEntries = filterConverseEntriesByText(regularEntries, preservedRegularTexts)

                converseHistory.history.clear()
                converseHistory.history.addAll(preservedMatchingEntries + preservedRegularEntries)
                return
            }
        }

        val conversationTexts = converseHistory.history.map { it.content.text }
        val truncatedTexts = Dictionary.truncate(
            conversationTexts,
            maxTokens,
            multiplyWindowSizeBy,
            truncateSettings,
            countSubWordsInFirstWord,
            favorWholeWords,
            countOnlyFirstWordFound,
            splitForNonWordChar,
            alwaysSplitIfWholeWordExists,
            countSubWordsIfSplit,
            nonWordSplitCount,
            tokenCountingBias
        )

        // Filter conversation history to keep only entries with text that survived truncation
        converseHistory.history.retainAll { converseData ->
            truncatedTexts.contains(converseData.content.text)
        }
    }


    /**
     * Helper function to truncate converse history with supplied settings object.
     */
    fun truncateConverseHistoryWithObject(
        tokenBudget: Int = 32000,
        multiplyBy: Int = 0,
        truncateMethod: ContextWindowSettings = ContextWindowSettings.TruncateTop,
        truncationSettings: TruncationSettings = TruncationSettings()
    )
    {
        truncateConverseHistory(
            tokenBudget,
            multiplyBy,
            truncateMethod,
            truncationSettings.countSubWordsInFirstWord,
            truncationSettings.favorWholeWords,
            truncationSettings.countOnlyFirstWordFound,
            truncationSettings.splitForNonWordChar,
            truncationSettings.alwaysSplitIfWholeWordExists,
            truncationSettings.countSubWordsIfSplit,
            truncationSettings.nonWordSplitCount,
            truncationSettings.tokenCountingBias
        )
    }

    /**
     * Denotes if our context window is empty.
     */
    fun isEmpty() : Boolean
    {
        return contextElements.isEmpty() && loreBookKeys.isEmpty() && converseHistory.history.isEmpty()
    }

    /**
     * Clears all context data including context elements, lorebook keys, and conversation history.
     */
    fun clear()
    {
        contextElements.clear()
        loreBookKeys.clear()
        converseHistory.history.clear()
    }

    /**
     * Cleans up any lorebook keys that have been added with bad characters or missing case fixing.
     * @param bannedChars List of banned characters to remove from lorebook keys. Each banned char is separated
     * using the delimiter: ", " (comma and space).
     * @param replaceBannedCharWith Replacement string for banned characters. Any banned chars will be replaced
     * but this substring. This is intended to allow fixing of common bizarre behaviors like using _ instead of a
     * space.
     */
    fun cleanLorebook(bannedChars: String = "", replaceBannedCharWith: String = "")
    {
        //Separated using delimiters to make it easier for the coder to call this.
        val bannedCharsAsList = bannedChars.split(", ")


        val loreBookCopy = loreBookKeys.deepCopy()
        loreBookKeys.clear()

        //Replace any banned chars an llm likes to add, then ensure alias keys are case-insensitive and add back.
        for(loreBookEntry in loreBookCopy)
        {
            var loreBookKey = loreBookEntry.key

            for(bannedChar in bannedCharsAsList)
            {
                loreBookKey = loreBookKey.replace(bannedChar, replaceBannedCharWith)
            }

            var loreBookValue = loreBookEntry.value
            loreBookValue.key = loreBookKey

            //Re-add now that we've cleaned up any of the nonsense like banned chars, missing case fixing alias keys etc.
            addLoreBookEntryWithObject(loreBookValue)
        }
    }

    /**
     * Checks if this ContextWindow is currently locked by ContextLock system
     *
     * @return True if the ContextWindow has been marked as locked via ContextLock metadata
     */
    fun isContextLocked(): Boolean
    {
        return metaData["isLocked"] as? Boolean ?: false
    }

    /**
     * Checks if a specific lorebook key can be selected based on local [ContextLock] state.
     * This method handles passthrough functions and respects local lock states to determine
     * if a key should be available for selection during lorebook processing.
     *
     * Remote-aware callers should use [canSelectLoreBookKeySuspend].
     *
     * @param key The lorebook key to check for selection eligibility.
     * @return True if the key can be selected, false if it's locked and should be excluded.
     */
    fun canSelectLoreBookKey(key: String): Boolean
    {
        if(!isContextLocked()) return true
        
        // Check if key has passthrough function that allows bypass
        val bundle = ContextLock.getKeyBundle(key)
        if(bundle?.passthroughFunction != null)
        {
            try
            {
                // Execute passthrough function - if returns true, allow selection
                return bundle.passthroughFunction?.invoke() ?: false
            }
            catch(e: Exception)
            {
                // If passthrough function fails, default to lock state
                return !bundle.isLocked
            }
        }
        
        return !ContextLock.isKeyLocked(key)
    }

    /**
     * Checks if a specific lorebook key can be selected while honoring remote [ContextLock] state.
     *
     * @param key The lorebook key to check for selection eligibility.
     * @return True if the key can be selected, false if it's locked and should be excluded.
     */
    suspend fun canSelectLoreBookKeySuspend(key: String): Boolean
    {
        if(!isContextLocked()) return true

        val bundle = ContextLock.getKeyBundle(key)
        if(bundle?.passthroughFunction != null)
        {
            try
            {
                return bundle.passthroughFunction?.invoke() ?: false
            }
            catch(e: Exception)
            {
                return !bundle.isLocked
            }
        }

        return !ContextLock.isKeyLockedSuspend(key)
    }

    /**
     * Gets list of locked lorebook keys that affect this ContextWindow.
     * Only returns keys when the ContextWindow is marked as locked.
     *
     * @return Set of locked lorebook key names, or empty set if not locked
     */
    fun getLockedKeys(): Set<String>
    {
        if(!isContextLocked()) return emptySet()
        return ContextLock.getLockedKeysForContext(this)
    }
}
