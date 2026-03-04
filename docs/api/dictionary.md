# Dictionary Object API

The `Dictionary` object provides the flow meters and cutoff valves for the TPipe ecosystem. It implements dictionary-based token counting and text truncation, allowing TPipe to approximate the behavior of various LLM tokenizers without needing access to their proprietary logic. This is critical for managing context windows and preventing system overflows.

```kotlin
object Dictionary
```

## Table of Contents
- [Token Counting Methods](#token-counting-methods)
- [Text Truncation](#text-truncation)
- [List Truncation](#list-truncation)
- [Key Behaviors](#key-behaviors)

---

## Token Counting Methods

#### `countTokens(text: String, settings: TruncationSettings): Int`
The standard entry point for token counting. It uses a `TruncationSettings` object to define the viscosity of the text (how aggressively it should be split into tokens).

#### `countTokens(text: String, ...detailed parameters...): Int`
The high-precision method that powers TPipe's resource accounting.

**Technical Mechanics:**
*   **Dictionary Matching**: TPipe loads a specialized word list from `/Words.txt`. It attempts to find the longest available matches in the input text to estimate token counts.
*   **First Word Handling**: If `countSubWordsInFirstWord` is true, the system uses overlapping matches to ensure a conservative (higher) estimate for the start of the prompt.
*   **Non-Word Handling**: Characters that are not found in the dictionary (punctuation, symbols, rare characters) are counted based on the `nonWordSplitCount`. For example, if this is set to 4, every 4 non-word characters count as 1 token.
*   **Splitting Logic**: If `splitForNonWordChar` is enabled, the system breaks words at symbols and processes the fragments independently, mirroring how modern BPE tokenizers handle complex strings.

---

## Text Truncation: The Flow Cutoff

#### `truncate(text, windowSize, multiplyWindowSizeBy, truncateSettings, ...)`
Trims a string so that its token count falls within the specified budget.

**Operational Parameters:**
*   **Budget Calculation**: The final limit is `windowSize * multiplyWindowSizeBy`. The default multiplier of 1000 allows you to pass simple numbers like `32` to represent a 32,000 token limit.
*   **Word Boundary Safety**: TPipe never cuts in the middle of a word. It identifies the last full word that fits within the budget to ensure the resulting text remains readable and coherent for the model.

**Truncation Strategies:**
*   **`TruncateTop`**: Removes data from the beginning of the string.
*   **`TruncateBottom`**: Removes data from the end.
*   **`TruncateMiddle`**: Removes a "chunk" from the center, preserving the start (instructions) and the end (latest context).

---

## List Truncation: Managing Histories

#### `truncate(messages: List<String>, ...)`
Specialized for `ConverseHistory`. Instead of chopping individual strings, this method removes **entire elements** from the list. This ensures that an agent never receives a half-finished message, which can cause logic errors or hallucinations.

---

## Key Behaviors

### 1. Deterministic Approximation
The Dictionary provides a deterministic, repeatable estimate of token counts. While it is an approximation, it is designed to be slightly conservative to ensure that if TPipe says it fits, the model provider's API will also accept it.

### 2. High Performance
The word list is lazily initialized and cached. Truncation and counting algorithms are optimized for the high-frequency execution required in parallel agent swarms.

### 3. Word Boundary Preservation
Coherence is prioritized. By operating at the word level, the Dictionary ensures that the "Blueprint" (System Prompt) and "Cargo" (User Data) remain semantically intact after truncation.
