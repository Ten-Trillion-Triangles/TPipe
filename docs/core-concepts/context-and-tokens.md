# Context and Tokens - Managing the Flow

In any hydraulic system, you have to manage **Volume** and **Pressure**. In TPipe, **Tokens** are the volume. AI models have a fixed "Intake Capacity" (the context window), and if you try to pump more tokens than the model can handle, the system bursts—the API call fails.

TPipe provides sophisticated token management to solve this. It ensures your flow remains within model limits, optimizes how space is used, and prevents runtime failures through intelligent truncation.

## Simple vs. Advanced Truncation

TPipe offers two ways to manage token volume:

### 1. Simple Truncation (`autoTruncateContext`)
The basic "Overflow Valve." It trims your context to fit the model's limits but doesn't allow for complex budgeting across different sources.

```kotlin
pipe.autoTruncateContext() // Basic overflow protection
```

### 2. Advanced Truncation (`TokenBudgetSettings`)
The "Smart Metering" system. This allows you to define exactly how many tokens are allocated to the user prompt, the model's reasoning, and the final output. **Multi-page budgeting strategies only work with this advanced mode.**

```kotlin
pipe.setTokenBudget(TokenBudgetSettings(...))
```

---

## Token Budgets: Predictable Resource Allocation

Without a budget, it's hard to predict if your input will fit or if the model will have enough "thinking" space. `TokenBudgetSettings` gives you deterministic control.

```kotlin
val budget = TokenBudgetSettings(
    contextWindowSize = 32000,        // Total capacity of the reservoir
    maxTokens = 8000,                 // Reserved space for the output flow
    userPromptSize = 12000,           // Maximum space for the user's input
    reasoningBudget = 4000,           // Reserved space for "thinking" (DeepSeek/Claude)
    allowUserPromptTruncation = true  // If true, TPipe trims the input if it's too big
)
```

### Dynamic vs. Explicit Allocation

*   **Explicit Allocation**: You set a fixed size (e.g., `userPromptSize = 12000`). This is predictable and best for production systems.
*   **Dynamic Allocation**: You set `userPromptSize = null`. TPipe calculates the exact size of your input and allocates only what's needed, maximizing the space left for other context. This is the "High Efficiency" mode.

---

## Truncation: Intelligent Content Reduction

When your reservoir (context) is too full, you need to decide what to "drain." TPipe supports several strategies:

*   **TruncateTop**: Removes the oldest messages first. Ideal for **Chatbots** where the latest context is most important.
*   **TruncateBottom**: Removes the newest content. Ideal for **Document Analysis** where the initial instructions or document structure must be preserved.

```kotlin
pipe.setContextWindowSettings(ContextWindowSettings.TruncateTop)
```

> [!TIP]
> **Keyword Preservation**: Enable `preserveTextMatches = true` to ensure that context elements containing words from your current prompt are the last ones to be removed during truncation.

---

## Advanced Strategies: DYNAMIC_FILL

When you have multiple pages of context (e.g., "World Rules", "Character Bio", "Inventory"), simple splitting can waste space. The **DYNAMIC_FILL** strategy (now the default) uses an iterative process to:
1.  See how much each page actually uses.
2.  Redistribute unused budget from "lean" pages to "heavy" pages.
3.  Ensure your context reservoir is as full as possible with relevant data.

```kotlin
pipe.enableDynamicFill()
```

---

## The Thinking Space (Reasoning Tokens)

Models like **DeepSeek-R1** or **Claude 3.5** often generate internal reasoning before their final answer. This Thinking uses tokens. TPipe allows you to explicitly budget for this so it doesn't crowd out your actual output.

```kotlin
pipe.setReasoning(8000) // Reserve 8,000 tokens for the model to think
```

---

## Summary of Best Practices

*   **Set a Buffer**: Always set your `contextWindowSize` slightly lower (10-15%) than the model's absolute maximum to account for overhead.
*   **Match the Strategy**: Use `TruncateTop` for conversations and `TruncateBottom` for data processing.
*   **Use Dynamic Fill**: In multi-page scenarios, always enable `DYNAMIC_FILL` to maximize token efficiency.
*   **Monitor the Gauges**: Use `PipeTracer` to see exactly how many tokens were spent and which parts of your context were truncated.

---

## Next Steps

Now that you can manage the volume of your flow, learn about the advanced tuning options for token counting.

**→ [Token Counting, Truncation, and Tokenizer Tuning](token-counting-and-truncation.md)** - Advanced token handling.
