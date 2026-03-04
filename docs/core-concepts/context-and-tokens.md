# Context Window Size, Token Budgets, and Truncation

TPipe provides sophisticated token management to solve the fundamental problem of AI model context limits. These features prevent runtime failures, optimize token usage, and ensure predictable behavior when dealing with large inputs.

## Table of Contents
- [Simple vs Advanced Truncation](#simple-vs-advanced-truncation)
- [Context Window Size: Total Token Capacity](#context-window-size-total-token-capacity)
- [Token Budgets: Resource Allocation](#token-budgets-resource-allocation)
- [Dynamic vs Explicit User Prompt Allocation](#dynamic-vs-explicit-user-prompt-allocation)
- [Truncation: Intelligent Content Reduction](#truncation-intelligent-content-reduction)
- [Advanced Truncation Control](#advanced-truncation-control)
- [Practical Implementation Patterns](#practical-implementation-patterns)
- [Error Prevention and Handling](#error-prevention-and-handling)
- [Best Practices](#best-practices)
- [Next Steps](#next-steps)

---

## Simple vs Advanced Truncation

TPipe offers two ways to manage token volume:

### 1. Simple Truncation (`autoTruncateContext`)
The basic overflow valve. It trims your context to fit the model's limits but doesn't allow for complex budgeting across different sources.

```kotlin
pipe.autoTruncateContext()  // Basic truncation, no multi-page budgeting
pipe.autoTruncateContext(fillMode = true)  // Basic truncation with select-and-fill mode
```

### 2. Advanced Truncation (`TokenBudgetSettings`)
The smart metering system. This allows you to define exactly how many tokens are allocated to the user prompt, the model's reasoning, and the final output. **Multi-page budgeting strategies only work with this advanced mode.**

```kotlin
pipe.setTokenBudget(TokenBudgetSettings())  // Automatically enables advanced truncation
```

---

## Context Window Size: Total Token Capacity

AI models have hard limits on total tokens they can process (input + output combined). Exceed this limit and your API call fails.

```kotlin
pipe.setContextWindowSize(32000)  // Set total token budget
    .setMaxTokens(8000)          // Reserve tokens for output
```

**How it works**: TPipe calculates available input space as `contextWindowSize - maxTokens - systemPromptTokens`, then automatically manages content to fit this constraint.

---

## Token Budgets: Resource Allocation

`TokenBudgetSettings` gives you deterministic control over the entire mainline flow.

```kotlin
val budget = TokenBudgetSettings(
    userPromptSize = 12000,           // Explicit limit for user input
    maxTokens = 20000,                // Output token allocation
    reasoningBudget = 8000,           // Thinking token allocation (DeepSeek/Claude)
    contextWindowSize = 32000,        // Total token budget
    allowUserPromptTruncation = true, // Truncation policy
    multiPageBudgetStrategy = MultiPageBudgetStrategy.DYNAMIC_FILL, // Multi-page allocation strategy
    pageWeights = mapOf("critical" to 2.0, "normal" to 1.0), // Optional page weights
    preserveTextMatches = true // Keep prompt-matching context/history when truncating
)
```

---

## Dynamic vs Explicit User Prompt Allocation

### Explicit User Prompt Allocation
TPipe reserves exactly the specified amount for user input. If the input exceeds this limit, it is either truncated or an error is thrown.
- **Use when**: You need predictable, deterministic behavior for production systems with known input patterns.

### Dynamic User Prompt Allocation
Set `userPromptSize = null`. TPipe counts the actual tokens in your user prompt and allocates exactly that amount, maximizing the space left for LoreBooks and history.
- **Use when**: Input sizes vary significantly (e.g., user-generated content or document processing).

---

## Truncation: Intelligent Content Reduction

When your reservoir (context) is too full, you need to decide what to drain:

*   **TruncateTop**: Removes the oldest messages first. Ideal for **Chatbots** where the latest context is most important.
*   **TruncateBottom**: Removes the newest content. Ideal for **Document Analysis** where the initial instructions or structure must be preserved.

```kotlin
pipe.setContextWindowSettings(ContextWindowSettings.TruncateTop)
```

> [!TIP]
> **Keyword Preservation**: Enable `preserveTextMatches = true` to ensure that context elements containing words from your current prompt are the last ones to be removed during truncation.

---

## Advanced Truncation Control

### Max Token Overflow
Normally, hitting the token limit is treated as a failure. You can choose to treat it as an intentional constraint instead.

```kotlin
pipe.setMaxTokens(100)           // Very short response limit
    .enableMaxTokenOverflow()    // Accept truncated responses as valid
```

### Multi-Page Strategies: DYNAMIC_FILL
When you have multiple pages of context (e.g., World Rules, Character Bio), simple splitting can waste space. **DYNAMIC_FILL** (now the default) redistributes unused budget from lean pages to heavy pages, ensuring maximum context utilization.

---

## Practical Implementation Patterns

### High-Context Creative Writing
Maintains story continuity by using `TruncateTop` and a large context window.
```kotlin
val budget = TokenBudgetSettings(
    userPromptSize = 15000,
    maxTokens = 20000,
    reasoningBudget = 5000,
    allowUserPromptTruncation = true,
    truncationMethod = ContextWindowSettings.TruncateTop
)
```

### Analytical Processing
Preserves initial instructions and document structure using `TruncateBottom` and a large reasoning allocation.
```kotlin
val budget = TokenBudgetSettings(
    userPromptSize = 8000,
    maxTokens = 15000,
    reasoningBudget = 10000,
    allowUserPromptTruncation = false,
    truncationMethod = ContextWindowSettings.TruncateBottom
)
```

---

## Error Prevention and Handling

TPipe validates your budget constraints before execution:
- System prompt cannot exceed total context window.
- `maxTokens` must fit after the system prompt.
- `reasoningBudget` must be less than `maxTokens`.

---

## Best Practices

*   **Set a Buffer**: Always set your `contextWindowSize` slightly lower (10-15%) than the model's absolute maximum.
*   **Match the Strategy**: Use `TruncateTop` for conversations and `TruncateBottom` for data processing.
*   **Monitor the Gauges**: Use `PipeTracer` to see exactly how many tokens were spent and which parts of your context were truncated.

---

## Next Steps

Now that you can manage the volume of your flow, learn about the advanced tuning options for token counting.

**→ [Token Counting, Truncation, and Tokenizer Tuning](token-counting-and-truncation.md)** - Advanced token handling.
