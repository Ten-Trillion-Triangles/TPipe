# Dictionary Object API

## Table of Contents
- [Overview](#overview)
- [Public Properties](#public-properties)
- [Public Functions](#public-functions)
  - [Token Counting](#token-counting)
  - [Text Truncation](#text-truncation)
  - [List Truncation](#list-truncation)

## Overview

The `Dictionary` object provides token counting and text truncation capabilities for TPipe, using a dictionary-based approach to approximate LLM tokenization behavior for context window management.

```kotlin
object Dictionary
```

## Public Properties

**`words`**
```kotlin
val words: List<String> by lazy
```
Dictionary word list loaded from `/Words.txt` resource file. Used for token counting and text matching operations. Lazily initialized and cached for performance. Falls back to empty list if resource loading fails.

## Public Functions

### Token Counting

#### `countTokens(text: String, settings: TruncationSettings): Int`
Counts tokens using TruncationSettings configuration object.

**Behavior:** Wrapper method that extracts all tokenization parameters from TruncationSettings and calls the full countTokens method. Provides convenient interface for consistent token counting across TPipe.

#### `countTokens(text: String, countSubWordsInFirstWord: Boolean = true, favorWholeWords: Boolean = true, countOnlyFirstWordFound: Boolean = false, splitForNonWordChar: Boolean = true, alwaysSplitIfWholeWordExists: Boolean = false, countSubWordsIfSplit: Boolean = false, nonWordSplitCount: Int = 4): Int`
Advanced token counting with comprehensive configuration options.

**Behavior:** Complex tokenization algorithm with multiple strategies:

**Word Processing:**
- Splits text by spaces and processes each word individually
- Ensures minimum 1 token per non-empty word

**First Word Handling:**
- **`countSubWordsInFirstWord = true`**: Uses overlapping matches to count all subwords in first word
- **`countSubWordsInFirstWord = false`**: Treats first word like other words

**Dictionary Matching:**
- **`favorWholeWords = true`**: Prioritizes complete word matches over subword fragments
- **`favorWholeWords = false`**: Uses longest available matches regardless of word boundaries

**Match Limiting:**
- **`countOnlyFirstWordFound = true`**: Stops after finding first dictionary match per word
- **`countOnlyFirstWordFound = false`**: Continues processing remainder of word

**Splitting Logic:**
- **`splitForNonWordChar = true`**: Splits at non-letter characters and processes remainder
- **`alwaysSplitIfWholeWordExists = true`**: Forces splitting even when whole word matches found
- **`countSubWordsIfSplit = true`**: Continues dictionary matching after split
- **`countSubWordsIfSplit = false`**: Uses character-based counting after split

**Fallback Counting:**
- **`nonWordSplitCount`**: Characters per token when no dictionary matches found
- Applied when no matches exist or after splitting with `countSubWordsIfSplit = false`

---

### Text Truncation

#### `truncate(text: String, windowSize: Int, multiplyWindowSizeBy: Int = 1000, truncateSettings: ContextWindowSettings, ...tokenization parameters...): String`
Truncates text to fit within token budget using specified strategy.

**Behavior:** 

**Token Budget Calculation:**
- Final limit = `windowSize * multiplyWindowSizeBy`
- Default multiplier of 1000 allows readable input values

**Truncation Strategies:**
- **`TruncateTop`**: Removes words from beginning, preserves end
- **`TruncateBottom`**: Removes words from end, preserves beginning  
- **`TruncateMiddle`**: Removes middle section, preserves both beginning and end

**Word-Level Processing:**
- Splits text by spaces and processes complete words
- Never truncates within individual words
- Uses same token counting logic for consistency

**Early Return:**
- Returns original text if already within token limit
- Avoids unnecessary processing when truncation not needed

#### `truncateWithSettings(content: String, tokenBudget: Int, truncationMethod: ContextWindowSettings, settings: TruncationSettings): String`
Convenience method for truncation using TruncationSettings object.

**Behavior:** Extracts all parameters from TruncationSettings and calls full truncate method. Provides consistent interface matching token counting approach.

---

### List Truncation

#### `truncate(messages: List<String>, windowSize: Int, multiplyWindowSizeBy: Int = 1000, truncateSettings: ContextWindowSettings, ...tokenization parameters...): List<String>`
Truncates list of strings by removing entire elements to fit token budget.

**Behavior:**

**Element-Level Processing:**
- Removes complete list elements rather than truncating individual strings
- Useful for chat histories where complete messages should be preserved or removed

**Token Calculation:**
- Sums token counts across all list elements
- Uses same tokenization parameters as text truncation

**Truncation Strategies:**
- **`TruncateTop`**: Removes elements from beginning of list
- **`TruncateBottom`**: Removes elements from end of list
- **`TruncateMiddle`**: Removes elements from middle, preserving start and end elements

**Preservation Logic:**
- Maintains chronological order of remaining elements
- Ensures no partial message truncation
- Returns original list if already within token budget

## Key Behaviors

### Dictionary-Based Tokenization
Uses loaded word dictionary to approximate LLM tokenization behavior. Provides configurable strategies to match different LLM tokenizer characteristics without requiring actual tokenizer access.

### Consistent Token Counting
All truncation methods use identical token counting logic to ensure truncated content fits within specified budgets. Token counts are deterministic and repeatable.

### Word Boundary Preservation
Text truncation operates at word boundaries to maintain readability and semantic coherence. Never splits individual words during truncation process.

### Flexible Configuration
Extensive parameterization allows fine-tuning for different LLM types and use cases. Settings can be optimized for specific tokenizer behaviors or content types.

### Performance Optimization
Lazy loading of dictionary and early return conditions minimize computational overhead. Caching and efficient algorithms support high-frequency usage.

### Fallback Mechanisms
Graceful handling of edge cases including empty inputs, missing dictionary resources, and no-match scenarios. Always provides reasonable token estimates even with incomplete data.
