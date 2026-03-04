# Token Counting and Tokenizer Tuning

In an industrial plumbing system, different fluids have different viscosities. In TPipe, **Tokens** are the same way. Every AI model has its own "Tokenizer"—the way it breaks down text into tokens. Because every model is different, a "gallon" of text for Claude might be "1.2 gallons" for Llama.

TPipe's `Dictionary` and `TruncationSettings` allow you to tune your Flow Meters to match your specific model's tokenizer with high precision.

## The TruncationSettings: Tuning the Meter

The `TruncationSettings` class is where you configure the "viscosity" of your text flow. It controls how TPipe estimates token counts and how it decides to chop text when the reservoir is full.

```kotlin
data class TruncationSettings(
    var multiplyWindowSizeBy: Int = 1000,           // The "Multiplier" (e.g., 32 * 1000 = 32K tokens)
    var favorWholeWords: Boolean = true,            // Prioritize keeping words intact
    var splitForNonWordChar: Boolean = true,        // Split on punctuation and symbols
    var nonWordSplitCount: Int = 4                  // How many characters count as one token for non-words
)
```

---

## Tokenizer Profiles: Tuning for Different Models

Because models use different math to count tokens, TPipe allows you to Tuning your settings for the specific provider you're using.

### 1. Claude-Style Models (Anthropic)
Claude tends to be conservative and favors whole words.
*   `favorWholeWords = true`
*   `nonWordSplitCount = 4`

### 2. GPT-Style Models (OpenAI)
GPT models use aggressive BPE (Byte Pair Encoding) and often split words into many small sub-tokens.
*   `favorWholeWords = false`
*   `nonWordSplitCount = 2` (More aggressive)

### 3. Open Source Models (Llama, Mistral)
Often use SentencePiece, which handles punctuation differently.
*   `splitForNonWordChar = false`
*   `countSubWordsIfSplit = true`

---

## The "Multiplier": Convenience in Configuration

In a large mainline, writing `128000` everywhere is tedious and error-prone. TPipe uses a **Multiplier** so you can use smaller, more readable numbers in your code.

```kotlin
// Set the multiplier once in your settings
val settings = TruncationSettings(multiplyWindowSizeBy = 1000)

// Now you can use simple numbers for your budget
Dictionary.truncate(text, windowSize = 32, settings)  // 32,000 tokens
Dictionary.truncate(text, windowSize = 128, settings) // 128,000 tokens
```

---

## Automated Tuning (`truncateModuleContext`)

You don't have to manually tune these settings for every model. TPipe includes a Smart Sensor that can automatically configure your pipe based on the model name you've selected.

```kotlin
val pipe = BedrockPipe()
    .setModel("anthropic.claude-3-5-sonnet-20241022-v2:0")
    .truncateModuleContext() // Auto-configures for Claude's specific tokenizer
```

**What this does**:
- Detects the model (Claude, Nova, Llama, etc.).
- Sets the optimal `nonWordSplitCount` and splitting rules.
- Configures the standard `contextWindowSize` for that specific model.

---

## Truncation Strategies: Choosing the Cut

When the reservoir overflows, you need to choose where to cut the flow:

*   **`TruncateTop`**: Drops the oldest data. (Best for **Chat**).
*   **`TruncateBottom`**: Drops the newest data. (Best for **Logic/Instructions**).
*   **`TruncateMiddle`**: Drops the center, keeping the beginning and end. (Best for **Summarization**).

```kotlin
pipe.setContextWindowSettings(ContextWindowSettings.TruncateTop)
```

---

## Next Steps

Now that you can tune your flow meters, learn how to automate the injection of memory into your pipes.

**→ [Automatic Context Injection](automatic-context-injection.md)** - Seamless context integration.
