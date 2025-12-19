# Text-Matching Extension Implementation Plan

## Overview

Extend the existing lorebook text-matching behavior to context elements and conversation history by adding a single boolean parameter to existing functions. When enabled, content matching the user prompt gets preservation priority.

## Current System

**Existing Lorebook Matching:**
- `findMatchingLoreBookKeys(text)` finds lorebook entries matching user prompt
- Selected by weight/hit count, gets highest priority in budget allocation
- Works seamlessly with fill mode and all truncation strategies

**Current Priority:**
1. Lorebook (selected by text matching + weight)
2. Context elements (truncated equally)
3. Conversation history (truncated equally)

## Proposed Extension

**New Priority (when flag enabled):**
1. Lorebook (unchanged)
2. **Text-matching context elements** (new)
3. **Text-matching conversation history** (new)
4. Regular context elements
5. Regular conversation history

**Implementation:** Add single boolean parameter to existing functions, reuse existing text-matching logic.

## Implementation Steps

### Step 1: Add Boolean Parameter to ContextWindow Functions

**File**: `src/main/kotlin/Context/ContextWindow.kt`

Add `preserveTextMatches: Boolean = false` to existing function signatures:

```kotlin
fun selectAndTruncateContext(
    text: String,
    totalTokenBudget: Int,
    truncateSettings: com.TTT.Enums.ContextWindowSettings,
    settings: com.TTT.Pipe.TruncationSettings,
    fillMode: Boolean = false,
    preserveTextMatches: Boolean = false  // NEW PARAMETER
)

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
    fillMode: Boolean = false,
    preserveTextMatches: Boolean = false  // NEW PARAMETER
)
```

### Step 2: Modify ContextWindow Truncation Functions

**File**: `src/main/kotlin/Context/ContextWindow.kt`

Update existing truncation functions to handle text-matching priority:

```kotlin
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
    inputText: String = "",  // NEW
    preserveTextMatches: Boolean = false  // NEW
) {
    if (maxTokens <= 0) return
    
    if (preserveTextMatches && inputText.isNotEmpty()) {
        // NEW: Text-matching priority logic
        val inputWords = inputText.lowercase().split(Regex("\\W+")).filter { it.isNotEmpty() }
        
        // Separate elements by text matching
        val textMatching = contextElements.filter { element ->
            val lowerElement = element.lowercase()
            inputWords.any { word -> lowerElement.contains(word) }
        }
        val regular = contextElements.filter { it !in textMatching }
        
        // Calculate tokens for text-matching elements
        val matchingTokens = textMatching.sumOf { element ->
            Dictionary.countTokens(element, countSubWordsInFirstWord, favorWholeWords, 
                countOnlyFirstWordFound, splitForNonWordChar, alwaysSplitIfWholeWordExists, 
                countSubWordsIfSplit, nonWordSplitCount)
        }
        
        // Preserve text-matching elements first
        val preservedMatching = if (matchingTokens <= maxTokens) {
            textMatching
        } else {
            // Truncate text-matching elements if they exceed budget
            Dictionary.truncate(textMatching, maxTokens, multiplyWindowSizeBy, truncateSettings,
                countSubWordsInFirstWord, favorWholeWords, countOnlyFirstWordFound,
                splitForNonWordChar, alwaysSplitIfWholeWordExists, countSubWordsIfSplit, nonWordSplitCount)
        }
        
        // Use remaining budget for regular elements
        val usedTokens = preservedMatching.sumOf { element ->
            Dictionary.countTokens(element, countSubWordsInFirstWord, favorWholeWords, 
                countOnlyFirstWordFound, splitForNonWordChar, alwaysSplitIfWholeWordExists, 
                countSubWordsIfSplit, nonWordSplitCount)
        }
        val remainingBudget = maxTokens - usedTokens
        
        val preservedRegular = if (remainingBudget > 0 && regular.isNotEmpty()) {
            Dictionary.truncate(regular, remainingBudget, multiplyWindowSizeBy, truncateSettings,
                countSubWordsInFirstWord, favorWholeWords, countOnlyFirstWordFound,
                splitForNonWordChar, alwaysSplitIfWholeWordExists, countSubWordsIfSplit, nonWordSplitCount)
        } else {
            emptyList()
        }
        
        // Update contextElements with prioritized order
        contextElements = (preservedMatching + preservedRegular).toMutableList()
    } else {
        // EXISTING: Use existing truncation logic
        contextElements = Dictionary.truncate(
            contextElements, maxTokens, multiplyWindowSizeBy, truncateSettings,
            countSubWordsInFirstWord, favorWholeWords, countOnlyFirstWordFound,
            splitForNonWordChar, alwaysSplitIfWholeWordExists, countSubWordsIfSplit, nonWordSplitCount
        ).toMutableList()
    }
}

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
    inputText: String = "",  // NEW
    preserveTextMatches: Boolean = false  // NEW
) {
    if (maxTokens <= 0) return
    
    if (preserveTextMatches && inputText.isNotEmpty()) {
        // NEW: Text-matching priority logic
        val inputWords = inputText.lowercase().split(Regex("\\W+")).filter { it.isNotEmpty() }
        
        // Separate conversation history by text matching
        val textMatching = converseHistory.history.filter { converseData ->
            val lowerContent = converseData.content.text.lowercase()
            inputWords.any { word -> lowerContent.contains(word) }
        }
        val regular = converseHistory.history.filter { it !in textMatching }
        
        // Extract texts for truncation
        val matchingTexts = textMatching.map { it.content.text }
        val regularTexts = regular.map { it.content.text }
        
        // Calculate tokens for text-matching history
        val matchingTokens = matchingTexts.sumOf { text ->
            Dictionary.countTokens(text, countSubWordsInFirstWord, favorWholeWords, 
                countOnlyFirstWordFound, splitForNonWordChar, alwaysSplitIfWholeWordExists, 
                countSubWordsIfSplit, nonWordSplitCount)
        }
        
        // Preserve text-matching history first
        val preservedMatchingTexts = if (matchingTokens <= maxTokens) {
            matchingTexts
        } else {
            Dictionary.truncate(matchingTexts, maxTokens, multiplyWindowSizeBy, truncateSettings,
                countSubWordsInFirstWord, favorWholeWords, countOnlyFirstWordFound,
                splitForNonWordChar, alwaysSplitIfWholeWordExists, countSubWordsIfSplit, nonWordSplitCount)
        }
        
        // Use remaining budget for regular history
        val usedTokens = preservedMatchingTexts.sumOf { text ->
            Dictionary.countTokens(text, countSubWordsInFirstWord, favorWholeWords, 
                countOnlyFirstWordFound, splitForNonWordChar, alwaysSplitIfWholeWordExists, 
                countSubWordsIfSplit, nonWordSplitCount)
        }
        val remainingBudget = maxTokens - usedTokens
        
        val preservedRegularTexts = if (remainingBudget > 0 && regularTexts.isNotEmpty()) {
            Dictionary.truncate(regularTexts, remainingBudget, multiplyWindowSizeBy, truncateSettings,
                countSubWordsInFirstWord, favorWholeWords, countOnlyFirstWordFound,
                splitForNonWordChar, alwaysSplitIfWholeWordExists, countSubWordsIfSplit, nonWordSplitCount)
        } else {
            emptyList()
        }
        
        // Filter conversation history to keep only preserved entries
        val allPreservedTexts = preservedMatchingTexts + preservedRegularTexts
        converseHistory.history.retainAll { converseData ->
            allPreservedTexts.contains(converseData.content.text)
        }
    } else {
        // EXISTING: Use existing truncation logic
        val conversationTexts = converseHistory.history.map { it.content.text }
        val truncatedTexts = Dictionary.truncate(
            conversationTexts, maxTokens, multiplyWindowSizeBy, truncateSettings,
            countSubWordsInFirstWord, favorWholeWords, countOnlyFirstWordFound,
            splitForNonWordChar, alwaysSplitIfWholeWordExists, countSubWordsIfSplit, nonWordSplitCount
        )
        
        converseHistory.history.retainAll { converseData ->
            truncatedTexts.contains(converseData.content.text)
        }
    }
}
```

### Step 3: Update Main selectAndTruncateContext Logic

**File**: `src/main/kotlin/Context/ContextWindow.kt`

Pass the new parameters through existing truncation calls:

```kotlin
fun selectAndTruncateContext(
    text: String,
    totalTokenBudget: Int,
    multiplyWindowSizeBy: Int,
    truncateSettings: com.TTT.Enums.ContextWindowSettings,
    // ... existing parameters
    fillMode: Boolean = false,
    preserveTextMatches: Boolean = false
) {
    // ... existing budget calculation logic (unchanged)
    
    // Update existing truncation calls to pass new parameters
    if(fillMode) {
        // ... existing fill mode logic
        
        if(hasContextElements && hasConverseHistory) {
            val contextBudget = remainingBudget / 2
            truncateContextElements(
                contextBudget, 1, truncateSettings,
                countSubWordsInFirstWord, favorWholeWords, countOnlyFirstWordFound,
                splitForNonWordChar, alwaysSplitIfWholeWordExists, countSubWordsIfSplit, nonWordSplitCount,
                inputText = text,  // NEW
                preserveTextMatches = preserveTextMatches  // NEW
            )

            val historyBudget = remainingBudget - contextBudget
            truncateConverseHistory(
                historyBudget, 1, truncateSettings,
                countSubWordsInFirstWord, favorWholeWords, countOnlyFirstWordFound,
                splitForNonWordChar, alwaysSplitIfWholeWordExists, countSubWordsIfSplit, nonWordSplitCount,
                inputText = text,  // NEW
                preserveTextMatches = preserveTextMatches  // NEW
            )
        }
        // ... handle other fill mode cases
    }
    else {
        // ... existing standard mode logic
        
        // Three-way split
        val thirdBudget = multipliedTokenBudget / 3

        truncateContextElements(
            thirdBudget, 1, truncateSettings,
            countSubWordsInFirstWord, favorWholeWords, countOnlyFirstWordFound,
            splitForNonWordChar, alwaysSplitIfWholeWordExists, countSubWordsIfSplit, nonWordSplitCount,
            inputText = text,  // NEW
            preserveTextMatches = preserveTextMatches  // NEW
        )

        truncateConverseHistory(
            thirdBudget, 1, truncateSettings,
            countSubWordsInFirstWord, favorWholeWords, countOnlyFirstWordFound,
            splitForNonWordChar, alwaysSplitIfWholeWordExists, countSubWordsIfSplit, nonWordSplitCount,
            inputText = text,  // NEW
            preserveTextMatches = preserveTextMatches  // NEW
        )
        
        // ... rest of existing logic (unchanged)
    }
}

### Step 4: Add to TokenBudgetSettings

**File**: `src/main/kotlin/Pipe/Pipe.kt`

Add boolean to TokenBudgetSettings data class:

```kotlin
@kotlinx.serialization.Serializable
data class TokenBudgetSettings(
    var userPromptSize: Int? = null,
    var maxTokens: Int? = null,
    var reasoningBudget: Int? = null,
    var contextWindowSize: Int? = null,
    var allowUserPromptTruncation: Boolean = false,
    var preserveJsonInUserPrompt: Boolean = true,
    var compressUserPrompt: Boolean = false,
    var truncateContextWindowAsString: Boolean = false,
    var truncationMethod: ContextWindowSettings = ContextWindowSettings.TruncateTop,
    var multiPageBudgetStrategy: MultiPageBudgetStrategy = MultiPageBudgetStrategy.DYNAMIC_FILL,
    var pageWeights: Map<String, Double>? = null,
    var reserveEmptyPageBudget: Boolean = true,
    var preserveTextMatches: Boolean = false  // NEW PARAMETER
)
```

### Step 5: Update All Call Sites

**Files**: `src/main/kotlin/Pipe/Pipe.kt`, `TPipe-Bedrock/src/main/kotlin/bedrockPipe/BedrockPipe.kt`

Update existing selectAndTruncateContext calls to use the TokenBudgetSettings value:

```kotlin
// Single context window truncation (around line 3008)
contextWindow.selectAndTruncateContext(
    content.text,
    workingContextWindowSpace,
    budget.truncationMethod,
    truncationSettings,
    fillMode = loreBookFillMode,
    preserveTextMatches = budget.preserveTextMatches  // NEW: Use from budget settings
)

// MiniBank truncation (around line 3217)
for((pageKey, contextWindow) in miniContextBank.contextMap) {
    val allocatedBudget = pageBudgets[pageKey] ?: 0
    if(allocatedBudget <= 0) continue

    contextWindow.selectAndTruncateContext(
        content.text,
        allocatedBudget,
        budget.truncationMethod,
        truncationSettings,
        fillMode = loreBookFillMode,
        preserveTextMatches = budget.preserveTextMatches  // NEW: Use from budget settings
    )

    totalUsedBudget += countContextWindowTokens(contextWindow, truncationSettings)
}
```

### Step 6: Add Pipe Configuration

**File**: `src/main/kotlin/Pipe/Pipe.kt`

Add simple boolean property and setter:

```kotlin
/**
 * Enables text-matching preservation for context elements and conversation history.
 * When enabled, content matching words from the user input gets preservation priority.
 */
fun enableTextMatchingPreservation(): Pipe {
    // Update the token budget settings
    if (tokenBudgetSettings == null) {
        tokenBudgetSettings = TokenBudgetSettings()
    }
    tokenBudgetSettings!!.preserveTextMatches = true
    return this
}

fun disableTextMatchingPreservation(): Pipe {
    tokenBudgetSettings?.preserveTextMatches = false
    return this
}
```

## Key Benefits

- **Minimal changes**: Only adds boolean parameters to existing functions
- **No new functions**: Extends existing `Dictionary.truncate()` and helper functions
- **No user complexity**: Just enable/disable with single method call
- **Reuses existing logic**: Uses same text-matching approach as lorebook
- **Full compatibility**: Works with all existing truncation strategies and fill modes
- **Backward compatible**: Defaults to false (existing behavior)

## Usage Example

```kotlin
val pipe = BedrockPipe()
    .setSystemPrompt("You are a helpful assistant")
    .enableTextMatchingPreservation()  // Single method call

// Or configure via TokenBudgetSettings
val budgetSettings = TokenBudgetSettings(
    contextWindowSize = 32000,
    preserveTextMatches = true  // Enable text matching preservation
)
pipe.setTokenBudget(budgetSettings)

// When user asks "Tell me about dragons"
// Context elements containing "dragons" get preservation priority
// Conversation history mentioning "dragons" gets preservation priority
// Works for both single context windows and MiniBank multi-page contexts
// Everything else works exactly as before
```

## Testing

Simple unit tests to verify:
- Text-matching elements are preserved when flag is true
- Existing behavior when flag is false
- Works with all truncation strategies
- Works with fill mode

## Success Criteria

- [ ] Single boolean parameter added to existing ContextWindow functions
- [ ] Boolean parameter added to TokenBudgetSettings data class
- [ ] Text-matching preservation works for single context windows
- [ ] Text-matching preservation works for MiniBank multi-page contexts
- [ ] No new function overloads or complex APIs
- [ ] Reuses existing lorebook text-matching logic
- [ ] Works seamlessly with fill mode and all truncation strategies
- [ ] Backward compatible (defaults to existing behavior)
- [ ] User enables with single method call or TokenBudgetSettings
- [ ] No performance impact when disabled
- [ ] Minimal code changes to existing system
