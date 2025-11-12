# ContextWindow Class API

## Table of Contents
- [Overview](#overview)
- [Public Properties](#public-properties)
- [Public Functions](#public-functions)
  - [LoreBook Management](#lorebook-management)
  - [Context Selection](#context-selection)
  - [Context Merging](#context-merging)
  - [Truncation](#truncation)
  - [Conversation Management](#conversation-management)
  - [Utilities](#utilities)

## Overview

The `ContextWindow` class manages context data including lorebook entries, context elements, and conversation history with intelligent selection and truncation capabilities.

```kotlin
@Serializable
data class ContextWindow(@Transient val cinit: Boolean = false)
```

## Public Properties

**`loreBookKeys`**
```kotlin
var loreBookKeys: MutableMap<String, LoreBook> = mutableMapOf()
```
Lorebook keys and values for weighted context injection. Each entry contains key-value pairs with metadata like weight, aliases, and dependencies.

**`contextElements`**
```kotlin
var contextElements: MutableList<String> = mutableListOf()
```
List of raw context strings for direct context injection without lorebook processing.

**`converseHistory`**
```kotlin
var converseHistory: ConverseHistory = ConverseHistory()
```
Structured conversation history between user and LLM agent for maintaining conversation context.

**`contextSize`**
```kotlin
var contextSize: Int = 8000
```
Maximum token size for the context window. Overridden by Pipe class settings during execution.

## Public Functions

### LoreBook Management

#### `addLoreBookEntry(key: String, value: String, weight: Int = 0, linkedKeys: List<String> = listOf(), aliasKeys: List<String> = listOf(), requiredKeys: List<String> = listOf())`
Adds a new lorebook entry with comprehensive metadata.

**Behavior:** Creates `LoreBook` object with specified parameters. Automatically adds uppercase and lowercase versions of the key as aliases for case-insensitive matching.

#### `addLoreBookEntryWithObject(lorebook: LoreBook)`
Adds lorebook entry using existing LoreBook object.

**Behavior:** Wrapper around `addLoreBookEntry()` that extracts parameters from LoreBook object. Useful for copying and re-adding entries.

#### `findLoreBookEntry(key: String): LoreBook?`
Finds lorebook entry by key or alias with case-insensitive matching.

**Behavior:** Searches in order: uppercase key, lowercase key, exact key, then all alias keys (uppercase, lowercase, exact). Returns first match or null.

#### `cleanLorebook(bannedChars: String = "", replaceBannedCharWith: String = "")`
Cleans lorebook keys by removing or replacing banned characters.

**Behavior:** 
- Parses `bannedChars` as comma-space delimited list
- Creates copy of all lorebook entries
- Replaces banned characters with replacement string
- Re-adds entries with cleaned keys and regenerated aliases

---

### Context Selection

#### `findMatchingLoreBookKeys(text: String): List<String>`
Finds lorebook keys that match substrings in input text.

**Behavior:** Case-insensitive substring matching against both main keys and alias keys. Returns list of matching main keys (not aliases).

#### `countAndSortKeyHits(hitKeys: List<String>): List<Pair<String, Int>>`
Counts key occurrences and sorts by frequency.

**Behavior:** Groups identical keys, counts occurrences, returns key-count pairs sorted by count descending.

#### `selectLoreBookContext(text: String, maxTokens: Int, countSubWordsInFirstWord: Boolean = true, favorWholeWords: Boolean = true, countOnlyFirstWordFound: Boolean = false, splitForNonWordChar: Boolean = true, alwaysSplitIfWholeWordExists: Boolean = false, countSubWordsIfSplit: Boolean = false, nonWordSplitCount: Int = 4): List<String>`
Intelligently selects lorebook entries based on text relevance and token budget.

**Behavior:** Complex selection algorithm:
- Finds matching keys in input text
- Expands selection to include linked keys
- Validates dependency requirements
- Sorts by hit count and weight
- Selects entries within token budget using tokenizer configuration parameters
- Prioritizes higher-weighted and more frequently matched entries

#### `selectAndFillLoreBookContext(text: String, maxTokens: Int, countSubWordsInFirstWord: Boolean = true, favorWholeWords: Boolean = true, countOnlyFirstWordFound: Boolean = false, splitForNonWordChar: Boolean = true, alwaysSplitIfWholeWordExists: Boolean = false, countSubWordsIfSplit: Boolean = false, nonWordSplitCount: Int = 4): List<String>`
Selects lorebook entries with an additional fill phase to maximize token usage.

**Behavior:**
- Reuses the priority selection from `selectLoreBookContext` to gather matching lorebook entries.
- Computes remaining budget after the priority phase.
- Iterates through non-matching entries (sorted by weight) and adds them as long as the total token usage stays within `maxTokens` and dependencies are satisfied.
- Returns an ordered list that can be used to filter `loreBookKeys` before truncating the rest of the context.

#### `selectLoreBookContextWithSettings(settings: TruncationSettings, text: String, maxTokens: Int): List<String>`
Alternative selection method using TruncationSettings for token counting.

#### `selectAndFillLoreBookContextWithSettings(settings: TruncationSettings, text: String, maxTokens: Int): List<String>`
Selects LoreBook entries using the select-and-fill strategy while reusing a settings object.

**Behavior:** Same as `selectAndFillLoreBookContext()` but uses the passed `TruncationSettings` for tokenizer configuration, preserving token counting behavior across helper methods.

**Parameters:**
- `settings` - TruncationSettings containing tokenization parameters
- `text` - Input text to scan for matching LoreBook keys
- `maxTokens` - Maximum tokens to allocate for lorebook selection

**Returns:** Ordered list of LoreBook keys selected via the priority + fill strategy

---

### Context Merging

#### `merge(other: ContextWindow, emplaceLoreBookKeys: Boolean = true, appendKeys: Boolean = false)`
Merges another ContextWindow into this one with configurable strategies.

**Behavior:** Complex merge with multiple strategies:
- **New entries**: Always added if key doesn't exist
- **Existing entries with `appendKeys = true`**: Appends new value to existing value
- **Existing entries with `emplaceLoreBookKeys = true`**: Replaces entire entry
- **Existing entries with both false**: Keeps original entry unchanged
- **Alias/Linked keys**: Always merged using combine utility
- **Context elements**: Appended to existing list
- **Conversation history**: Merged using ConverseHistory merge logic

---

### Truncation

#### `truncateContextElements(maxTokens: Int, multiplyWindowSizeBy: Int, truncateSettings: ContextWindowSettings, countSubWordsInFirstWord: Boolean = true, favorWholeWords: Boolean = true, countOnlyFirstWordFound: Boolean = false, splitForNonWordChar: Boolean = true, alwaysSplitIfWholeWordExists: Boolean = false, countSubWordsIfSplit: Boolean = false, nonWordSplitCount: Int = 4)`
Truncates context elements to fit token budget.

**Behavior:** Uses Dictionary truncation with specified method (TruncateTop, TruncateBottom, TruncateMiddle) and tokenizer configuration parameters.

#### `selectAndTruncateContext(text: String, totalTokenBudget: Int, multiplyWindowSizeBy: Int, truncateSettings: ContextWindowSettings, countSubWordsInFirstWord: Boolean = true, favorWholeWords: Boolean = true, countOnlyFirstWordFound: Boolean = false, splitForNonWordChar: Boolean = true, alwaysSplitIfWholeWordExists: Boolean = false, countSubWordsIfSplit: Boolean = false, nonWordSplitCount: Int = 4)`
Selects and truncates context with automatic budget allocation.

**Behavior:** Intelligent budget allocation based on available content types:
- **Lorebook only**: Full budget to lorebook selection
- **Context + Lorebook**: 50/50 split
- **Conversation + Lorebook**: Conversation first, remainder to lorebook  
- **All three types**: 1/3 each, with lorebook getting remainder
- **Returns**: Unit (modifies context in place)

**Additional Parameter:**
- `fillMode: Boolean = false` — when true, `selectAndTruncateContext` first runs the select-and-fill LoreBook flow (`selectAndFillLoreBookContext`) using the full budget, then splits the remaining tokens between context elements and conversation history.

#### `combineAndTruncateAsString(text: String, totalTokenBudget: Int, multiplyWindowSizeBy: Int, truncateSettings: ContextWindowSettings, countSubWordsInFirstWord: Boolean = true, favorWholeWords: Boolean = true, countOnlyFirstWordFound: Boolean = false, splitForNonWordChar: Boolean = true, alwaysSplitIfWholeWordExists: Boolean = false, countSubWordsIfSplit: Boolean = false, nonWordSplitCount: Int = 4): String`
Combines lorebook values with context elements into single string with truncation.

**Behavior:** Selects lorebook context, combines with context elements, truncates result to fit token budget using tokenizer parameters.

#### `combineAndTruncateAsStringWithSettings(text: String, tokenBudget: Int, settings: TruncationSettings, truncateMethod: ContextWindowSettings, multiplyBy: Int, fillMode: Boolean = false): String`
Enhanced version with TruncationSettings object, configurable truncation method, and optional lorebook fill mode.

**Behavior:** When `fillMode = true`, applies `selectAndFillLoreBookContextWithSettings()` before combining lorebook and context strings. After filtering keys based on the fill-mode selection, it concatenates the remaining sections and truncates them down to `tokenBudget`, using the supplied tokenizer parameters.

**Note:** Fill mode only affects which LoreBook entries remain—it does not change how the final string is truncated. This helper therefore preserves the standard string-based truncation semantics while still respecting advanced lorebook selection.

---

### Conversation Management

#### `selectConverseHistoryLoreBookContext(maxTokens: Int, countSubWordsInFirstWord: Boolean = true, favorWholeWords: Boolean = true, countOnlyFirstWordFound: Boolean = false, splitForNonWordChar: Boolean = true, alwaysSplitIfWholeWordExists: Boolean = false, countSubWordsIfSplit: Boolean = false, nonWordSplitCount: Int = 4): List<String>`
Selects lorebook context relevant to conversation history.

**Behavior:** Extracts text from conversation history, finds matching lorebook entries using tokenizer parameters, returns list of selected lorebook keys.

#### `truncateConverseHistory(maxTokens: Int, multiplyWindowSizeBy: Int, truncateSettings: ContextWindowSettings, countSubWordsInFirstWord: Boolean = true, favorWholeWords: Boolean = true, countOnlyFirstWordFound: Boolean = false, splitForNonWordChar: Boolean = true, alwaysSplitIfWholeWordExists: Boolean = false, countSubWordsIfSplit: Boolean = false, nonWordSplitCount: Int = 4)`
Truncates conversation history to fit token budget.

**Behavior:** Extracts conversation text, truncates using Dictionary methods with tokenizer parameters, reconstructs conversation with truncated content.

#### `truncateConverseHistoryWithObject(tokenBudget: Int, multiplyBy: Int, truncateMethod: ContextWindowSettings, truncationSettings: TruncationSettings)`
Truncates conversation using settings object for token counting parameters.

---

### Utilities

#### `isEmpty(): Boolean`
Checks if context window contains any data.

**Behavior:** Returns true if all collections (contextElements, loreBookKeys, converseHistory) are empty.

#### `clear()`
Clears all context data.

**Behavior:** Empties all three main collections: contextElements, loreBookKeys, and converseHistory.
