# Token Counting, Truncation, and Tokenizer Tuning

In an industrial plumbing system, different fluids have different viscosities. In TPipe, **Tokens** are the same way. Every AI model has its own Tokenizer—the way it breaks down text into tokens. Because every model is different, a gallon of text for Claude might be 1.2 gallons for Llama.

TPipe's `Dictionary` and `TruncationSettings` allow you to tune your Flow Meters to match your specific model's tokenizer with high precision.

## Table of Contents
- [The Problem with Token Counting](#the-problem-with-token-counting)
- [TruncationSettings: Tokenizer Configuration](#truncationsettings-tokenizer-configuration)
- [Tokenizer Profiles: Tuning for Models](#tokenizer-profiles-tuning-for-models)
- [Token Counting Methods](#token-counting-methods)
- [Text Truncation Methods](#text-truncation-methods)
- [Automated Tuning (truncateModuleContext)](#automated-tuning-truncatemodulecontext)
- [Truncation Strategies](#truncation-strategies)
- [Next Steps](#next-steps)

---

## The Problem with Token Counting

Different AI models use different tokenizers with varying behaviors:
- **GPT models**: Use BPE (Byte Pair Encoding) with aggressive subword splitting.
- **Claude models**: Favor whole words and use different subword rules.
- **Open source models**: Often use SentencePiece or custom tokenizers.

TPipe's token counting system provides a configurable approximation that can be tuned to match your target model's behavior without requiring access to their proprietary logic.

---

## TruncationSettings: Tokenizer Configuration

The `TruncationSettings` class is where you configure the viscosity of your text flow.

```kotlin
data class TruncationSettings(
    var multiplyWindowSizeBy: Int = 1000,           // The Multiplier
    var countSubWordsInFirstWord: Boolean = true,   // High-precision start
    var favorWholeWords: Boolean = true,            // Prioritize semantic units
    var countOnlyFirstWordFound: Boolean = false,   // Matching strategy
    var splitForNonWordChar: Boolean = true,        // Split on punctuation
    var alwaysSplitIfWholeWordExists: Boolean = false,
    var countSubWordsIfSplit: Boolean = false,      // High-precision fragments
    var nonWordSplitCount: Int = 4                  // Character-to-token ratio
)
```

### The "Multiplier"
In a large mainline, writing `128000` everywhere is tedious. TPipe uses a **Multiplier** so you can use smaller, more readable numbers.

```kotlin
// actual budget = windowSize * multiplyWindowSizeBy
Dictionary.truncate(text, windowSize = 32, multiplyWindowSizeBy = 1000) // 32,000 tokens
```

---

## Tokenizer Profiles: Tuning for Models

### 1. Claude-Style Models (Anthropic)
Claude tends to be conservative and favors whole words.
```kotlin
val claudeSettings = TruncationSettings(
    favorWholeWords = true,
    nonWordSplitCount = 4
)
```

### 2. GPT-Style Models (OpenAI)
GPT models use aggressive BPE and often split words into many small sub-tokens.
```kotlin
val gptSettings = TruncationSettings(
    favorWholeWords = false,
    nonWordSplitCount = 2
)
```

---

## Token Counting Methods

### Basic Counting
```kotlin
// Use default settings
val count = Dictionary.countTokens("Hello world!")

// Use custom settings
val count = Dictionary.countTokens("Hello world!", customSettings)
```

### Advanced Precision
You can manually override every parameter for high-precision tuning:
```kotlin
val count = Dictionary.countTokens(
    text = "...",
    countSubWordsInFirstWord = true,
    favorWholeWords = true,
    splitForNonWordChar = true,
    nonWordSplitCount = 4
)
```

---

## Text Truncation Methods

### String-Based Cutoff
Trims a string so that its token count falls within the specified budget. TPipe never cuts in the middle of a full word.

```kotlin
val result = Dictionary.truncateWithSettings(
    content = longText,
    tokenBudget = 32, // 32,000 tokens (if multiplier is 1000)
    truncationMethod = ContextWindowSettings.TruncateTop,
    settings = mySettings
)
```

### List-Based Cutoff
Specialized for `ConverseHistory`. This method removes **entire elements** from a list rather than chopping individual strings, ensuring no partial messages enter the prompt.

---

## Automated Tuning (`truncateModuleContext`)

You don't have to manually tune these settings for every model. TPipe includes a Smart Sensor that can automatically configure your pipe based on the model name you've selected.

```kotlin
val pipe = BedrockPipe()
    .setModel("anthropic.claude-3-5-sonnet-20241022-v2:0")
    .truncateModuleContext() // Auto-configures for Claude's tokenizer
```

---

## Truncation Strategies

When the reservoir overflows, you need to choose where to cut the flow:

*   **`TruncateTop`**: Removes oldest data first. (Best for **Chat**).
*   **`TruncateBottom`**: Removes newest data first. (Best for **Logic/Instructions**).
*   **`TruncateMiddle`**: Preserves start and end, dropping the center. (Best for **Summarization**).

---

## Next Steps

Now that you can tune your flow meters, learn how to automate the injection of memory into your pipes.

**→ [Automatic Context Injection](automatic-context-injection.md)** - Seamless context integration.
