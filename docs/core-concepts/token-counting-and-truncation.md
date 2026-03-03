# Token Counting, Truncation, and Tokenizer Tuning

## Table of Contents
- [The Problem with Token Counting](#the-problem-with-token-counting)
- [TruncationSettings - Tokenizer Configuration](#truncationsettings---tokenizer-configuration)
- [Token Counting Methods](#token-counting-methods)
- [Truncation Methods](#truncation-methods)
- [Tuning for Different Tokenizers](#tuning-for-different-tokenizers)
- [Truncation Strategies](#truncation-strategies)
- [Practical Examples](#practical-examples)
- [Pipe Convenience Functions](#pipe-convenience-functions)

TPipe provides sophisticated token counting and truncation capabilities that can be tuned to match different LLM tokenizers. This system helps prevent context overflow and ensures optimal token usage across different AI models.

## The Problem with Token Counting

Different AI models use different tokenizers with varying behaviors:
- **GPT models**: Subword tokenization with BPE
- **Claude models**: Different subword splitting rules
- **Open source models**: Often use SentencePiece or custom tokenizers
- **Reasoning models**: May have special token handling

TPipe's token counting system provides configurable approximation that can be tuned to match your target model's tokenizer behavior.

## TruncationSettings - Tokenizer Configuration

The `TruncationSettings` class controls how TPipe counts tokens and truncates content:

```kotlin
data class TruncationSettings(
    var multiplyWindowSizeBy: Int = 1000,           // Multiplier for convenience (1000 = use input as-is)
    var countSubWordsInFirstWord: Boolean = true,   // Count subwords in first word
    var favorWholeWords: Boolean = true,            // Prefer whole words over subwords
    var countOnlyFirstWordFound: Boolean = false,   // Stop after first word match
    var splitForNonWordChar: Boolean = true,        // Split on punctuation/symbols
    var alwaysSplitIfWholeWordExists: Boolean = false, // Force splitting behavior
    var countSubWordsIfSplit: Boolean = false,      // Count subwords after splitting
    var nonWordSplitCount: Int = 4                  // Characters per token for non-words
)
```

### Token Multiplier Explanation

The `multiplyWindowSizeBy` parameter is a convenience feature that lets you use smaller numbers in your inputs:

```kotlin
// Instead of writing large numbers everywhere:
Dictionary.truncate(text, windowSize = 32000)

// You can use smaller numbers with the multiplier:
Dictionary.truncate(text, windowSize = 32, multiplyWindowSizeBy = 1000)
// Actual token budget = 32 * 1000 = 32,000 tokens

// Common usage patterns:
multiplyWindowSizeBy = 1000  // Input 32 = 32,000 tokens
multiplyWindowSizeBy = 100   // Input 320 = 32,000 tokens  
multiplyWindowSizeBy = 1     // Input 32000 = 32,000 tokens (no multiplication)
```

## Token Counting Methods

### Basic Token Counting
```kotlin
// Simple token counting with default settings
val tokenCount = Dictionary.countTokens("Hello world, how are you?")

// Token counting with custom settings
val settings = TruncationSettings(
    favorWholeWords = true,
    countSubWordsInFirstWord = true,
    nonWordSplitCount = 3
)
val tokenCount = Dictionary.countTokens("Hello world!", settings)
```

### Advanced Token Counting
```kotlin
// Detailed token counting with all parameters
val tokenCount = Dictionary.countTokens(
    text = "Hello world, how are you?",
    countSubWordsInFirstWord = true,    // Count all subwords in first word
    favorWholeWords = true,             // Prefer "hello" over "hel" + "lo"
    countOnlyFirstWordFound = false,    // Count all words, not just first
    splitForNonWordChar = true,         // Split on punctuation
    alwaysSplitIfWholeWordExists = false, // Don't force splits
    countSubWordsIfSplit = false,       // Use character counting after splits
    nonWordSplitCount = 4               // 4 characters = 1 token for non-words
)
```

## Truncation Methods

### String Truncation
```kotlin
// Truncate using small numbers with multiplier
val truncatedText = Dictionary.truncate(
    text = longText,
    windowSize = 32,                    // Will be multiplied by multiplyWindowSizeBy
    multiplyWindowSizeBy = 1000,        // 32 * 1000 = 32,000 token budget
    truncateSettings = ContextWindowSettings.TruncateTop
)

// Or use actual token numbers
val truncatedText = Dictionary.truncate(
    text = longText,
    windowSize = 32000,                 // Actual token budget
    multiplyWindowSizeBy = 1,           // No multiplication
    truncateSettings = ContextWindowSettings.TruncateTop
)
```

### Truncation with Settings Object
```kotlin
val settings = TruncationSettings(
    multiplyWindowSizeBy = 1000,        // Use smaller input numbers
    favorWholeWords = true,
    nonWordSplitCount = 3
)

val truncatedText = Dictionary.truncateWithSettings(
    content = longText,
    tokenBudget = 32,                   // Will be 32 * 1000 = 32,000 tokens
    truncationMethod = ContextWindowSettings.TruncateTop,
    settings = settings
)
```

## Tuning for Different Tokenizers

### GPT-Style Models (BPE Tokenization)
```kotlin
val gptSettings = TruncationSettings(
    countSubWordsInFirstWord = true,    // GPT counts all subwords
    favorWholeWords = false,            // BPE doesn't favor whole words
    splitForNonWordChar = true,         // Split on punctuation
    countSubWordsIfSplit = true,        // Count subwords after splits
    nonWordSplitCount = 2               // Aggressive subword splitting
)
```

### Claude-Style Models
```kotlin
val claudeSettings = TruncationSettings(
    countSubWordsInFirstWord = true,
    favorWholeWords = true,             // Claude tends to preserve whole words
    splitForNonWordChar = true,
    countSubWordsIfSplit = false,       // Less aggressive subword counting
    nonWordSplitCount = 4               // More conservative character counting
)
```

### Open Source Models (SentencePiece)
```kotlin
val sentencePieceSettings = TruncationSettings(
    countSubWordsInFirstWord = false,   // SentencePiece handles first word differently
    favorWholeWords = true,
    splitForNonWordChar = false,        // SentencePiece handles punctuation internally
    countSubWordsIfSplit = true,
    nonWordSplitCount = 3
)
```

## Truncation Strategies

### TruncateTop (Remove Oldest)
```kotlin
ContextWindowSettings.TruncateTop
```
**Use for**: Chat applications, ongoing conversations where recent context is most important.

### TruncateBottom (Remove Newest)
```kotlin
ContextWindowSettings.TruncateBottom
```
**Use for**: Document analysis, tasks where initial instructions are critical.

### TruncateMiddle (Remove Middle)
```kotlin
ContextWindowSettings.TruncateMiddle
```
**Use for**: Summarization tasks where context and conclusion are both important.

## Practical Examples

### Tuning for a Specific Model
```kotlin
// Test with sample text to tune settings
val sampleText = "Your typical application text here..."
val actualTokens = getActualTokenCountFromModel(sampleText) // From your model's API

// Adjust settings to match
var settings = TruncationSettings()
var estimatedTokens = Dictionary.countTokens(sampleText, settings)

// Tune parameters based on results
if (estimatedTokens < actualTokens) {
    // TPipe is underestimating, be more aggressive
    settings.nonWordSplitCount = 2
    settings.countSubWordsIfSplit = true
} else if (estimatedTokens > actualTokens) {
    // TPipe is overestimating, be more conservative
    settings.favorWholeWords = true
    settings.nonWordSplitCount = 5
}
```

### Using Multiplier for Convenience
```kotlin
// Easy to read configuration using multiplier
val settings = TruncationSettings(multiplyWindowSizeBy = 1000)

// Now you can use simple numbers
Dictionary.truncate(text, windowSize = 4)    // 4,000 tokens
Dictionary.truncate(text, windowSize = 32)   // 32,000 tokens
Dictionary.truncate(text, windowSize = 128)  // 128,000 tokens
```

## Pipe Convenience Functions

### Auto-Assigning Truncation Settings
```kotlin
// Automatically configure truncation settings based on the model
val pipe = BedrockPipe()
    .setModel("anthropic.claude-3-sonnet-20240229-v1:0")
    .truncateModuleContext()  // Auto-assigns optimal settings for Claude

// Different models get different optimized settings
val gptPipe = BedrockPipe()
    .setModel("openai.gpt-oss-20b-1:0")
    .truncateModuleContext()  // Auto-assigns optimal settings for GPT

val novaPipe = BedrockPipe()
    .setModel("amazon.nova-pro-v1:0")
    .truncateModuleContext()  // Auto-assigns optimal settings for Nova
```

**What truncateModuleContext() does**:
- Analyzes the model name to determine the best tokenizer settings
- Automatically sets context window size, multiplier, and all tokenization parameters
- Configures truncation strategy (TruncateTop, TruncateBottom, etc.)
- Optimizes for the specific model's tokenizer behavior

### Getting Current Truncation Settings
```kotlin
// Get the current truncation settings from any pipe
val pipe = BedrockPipe()
    .setModel("anthropic.claude-3-sonnet-20240229-v1:0")
    .truncateModuleContext()  // Auto-configure for Claude

val settings = pipe.getTruncationSettings()
// Returns a TruncationSettings object with all current pipe settings

// Use these settings for manual token counting
val tokenCount = Dictionary.countTokens("Some text", settings)
val truncatedText = Dictionary.truncateWithSettings(
    content = longText, 
    tokenBudget = 1000, 
    truncationMethod = ContextWindowSettings.TruncateTop, 
    settings = settings
)
```

### Model-Specific Auto-Configuration Examples
```kotlin
// Claude models - optimized for Claude's tokenizer
val claudePipe = BedrockPipe()
    .setModel("anthropic.claude-3-sonnet-20240229-v1:0")
    .truncateModuleContext()
// Auto-sets: favorWholeWords=true, countSubWordsIfSplit=true, nonWordSplitCount=4

// Nova models - optimized for Amazon's tokenizer  
val novaPipe = BedrockPipe()
    .setModel("amazon.nova-pro-v1:0")
    .truncateModuleContext()
// Auto-sets: contextWindowSize=300K, optimized splitting rules

// GPT models - optimized for OpenAI's tokenizer
val gptPipe = BedrockPipe()
    .setModel("openai.gpt-oss-20b-1:0") 
    .truncateModuleContext()
// Auto-sets: Different parameters optimized for GPT tokenization

// Get the auto-configured settings for external use
val claudeSettings = claudePipe.getTruncationSettings()
val novaSettings = novaPipe.getTruncationSettings()
val gptSettings = gptPipe.getTruncationSettings()
```

Proper token counting and truncation tuning ensures your TPipe applications work reliably across different AI models while maximizing context utilization and preventing overflow errors.

## Next Steps

Now that you understand advanced token handling, learn about seamless context integration:

**→ [Automatic Context Injection](automatic-context-injection.md)** - Seamless context integration
