# Context Window Size, Token Budgets, and Truncation

## Table of Contents
- [Context Window Size - Managing Total Token Capacity](#context-window-size---managing-total-token-capacity)
- [Token Budgets - Predictable Resource Allocation](#token-budgets---predictable-resource-allocation)
- [Truncation - Intelligent Content Reduction](#truncation---intelligent-content-reduction)
- [Advanced Truncation Control](#advanced-truncation-control)
- [Practical Implementation Patterns](#practical-implementation-patterns)
- [Error Prevention and Handling](#error-prevention-and-handling)
- [Token Counting and Optimization](#token-counting-and-optimization)
- [Production Considerations](#production-considerations)

TPipe provides sophisticated token management to solve the fundamental problem of AI model context limits. These features prevent runtime failures, optimize token usage, and ensure predictable behavior when dealing with large inputs.

## Context Window Size - Managing Total Token Capacity

### The Problem
AI models have hard limits on total tokens they can process (input + output combined). Exceed this limit and your API call fails. Different models have different limits (8K, 32K, 128K, etc.).

### The Solution
```kotlin
pipe.setContextWindowSize(32000)  // Set total token budget
    .setMaxTokens(8000)          // Reserve tokens for output
```

**What this does**: Establishes a token budget that prevents API failures by ensuring total token usage stays within model limits.

**How it works**: TPipe calculates available input space as `contextWindowSize - maxTokens - systemPromptTokens`, then automatically manages content to fit this constraint.

## Token Budgets - Predictable Resource Allocation

### The Problem
Without explicit budgeting, you can't predict:
- Whether your input will fit
- How much space is left for AI output
- If reasoning models have enough "thinking" space
- When truncation will occur

### The Solution - TokenBudgetSettings
```kotlin
val budget = TokenBudgetSettings(
    userPromptSize = 12000,           // Explicit limit for user input
    maxTokens = 20000,                // Output token allocation
    reasoningBudget = 8000,           // Reasoning token allocation
    contextWindowSize = 32000,        // Total token budget
    allowUserPromptTruncation = true  // Truncation policy
)
```

**What this does**: 
- **userPromptSize**: Enforces maximum user input size, throwing errors or truncating when exceeded
- **reasoningBudget**: Reserves tokens for model reasoning, automatically subtracted from maxTokens
- **allowUserPromptTruncation**: Controls whether oversized inputs are truncated or cause failures

**Token allocation calculation**:
```
Total: 32,000 tokens
- System prompt: ~2,000 tokens (calculated)
- Output budget: 20,000 tokens
- Reasoning budget: 8,000 tokens (subtracted from output)
- Effective output: 12,000 tokens (20,000 - 8,000)
= Available for user input: 10,000 tokens
```

## Truncation - Intelligent Content Reduction

### The Problem
When content exceeds available token space, you need deterministic behavior instead of random failures. Different use cases require different truncation strategies.

### Truncation Methods
```kotlin
pipe.setContextWindowSettings(ContextWindowSettings.TruncateTop)    // Remove oldest content
pipe.setContextWindowSettings(ContextWindowSettings.TruncateBottom) // Remove newest content
```

**TruncateTop**: 
- **Use case**: Conversational AI, ongoing interactions
- **Behavior**: Preserves recent context, removes historical content
- **Why**: Recent context is more relevant for continuing conversations

**TruncateBottom**:
- **Use case**: Document analysis, reasoning tasks
- **Behavior**: Preserves initial context, removes recent additions
- **Why**: Initial context often contains critical instructions or document structure

### Automatic Truncation
```kotlin
pipe.truncateModuleContext()
```

**What this does**: Enables automatic context truncation when token limits are approached, preventing API failures.

**How it works**: 
1. Calculates total token usage (system prompt + user input + context)
2. If exceeds available space, truncates context according to selected method
3. Preserves system prompt and user input (unless explicitly allowed to truncate)

## Advanced Truncation Control

### User Input Truncation
```kotlin
val budget = TokenBudgetSettings(
    allowUserPromptTruncation = true,  // Enable user input truncation
    preserveJsonInUserPrompt = true    // Preserve JSON structures
)
```

**allowUserPromptTruncation**: 
- `false`: Throw exception if user input exceeds userPromptSize
- `true`: Automatically truncate user input to fit budget

**preserveJsonInUserPrompt**: Attempts to preserve JSON structure integrity during truncation.

### Max Token Overflow - Intentional Output Constraint
```kotlin
pipe.enableMaxTokenOverflow()
```

**What this does**: Treats max token limits as intentional constraints rather than error conditions.

**Use cases**:
- **Constraining long outputs**: Force models to be concise by setting low max tokens
- **Controlling reasoning**: Limit reasoning tokens to prevent excessive "thinking"
- **Response length management**: Ensure responses fit within UI constraints
- **Cost control**: Cap token usage for budget management

**Normal behavior**: Throws error if model hits max token limit (treats as failure)
**With overflow enabled**: Accepts truncated output as valid result (treats as intentional constraint)

**Example - Forcing concise responses**:
```kotlin
pipe.setMaxTokens(100)           // Very short response limit
    .enableMaxTokenOverflow()    // Accept truncated responses
// Result: Model forced to be extremely concise, partial responses accepted
```

## Practical Implementation Patterns

### High-Context Creative Writing
```kotlin
val writerPipe = BedrockPipe()
    .setContextWindowSize(100000)     // Large context for story continuity
    .setMaxTokens(20000)              // Long-form output capability
    .setContextWindowSettings(ContextWindowSettings.TruncateTop)
    .truncateModuleContext()

val budget = TokenBudgetSettings(
    userPromptSize = 15000,
    maxTokens = 20000,
    reasoningBudget = 5000,
    allowUserPromptTruncation = true,
    truncationMethod = ContextWindowSettings.TruncateTop
)
```

**Purpose**: Maintains story continuity while allowing long outputs. Recent story context takes priority over distant history.

### Analytical Processing
```kotlin
val analysisPipe = BedrockPipe()
    .setContextWindowSize(50000)
    .setMaxTokens(15000)
    .setContextWindowSettings(ContextWindowSettings.TruncateBottom)
    .truncateModuleContext()

val budget = TokenBudgetSettings(
    userPromptSize = 8000,
    maxTokens = 15000,
    reasoningBudget = 10000,          // Large reasoning allocation
    allowUserPromptTruncation = false, // Preserve analytical input integrity
    truncationMethod = ContextWindowSettings.TruncateBottom
)
```

**Purpose**: Preserves initial instructions and document structure. Large reasoning budget for complex analysis. Strict input preservation.

## Error Prevention and Handling

### Token Budget Validation
```kotlin
try {
    pipe.setTokenBudgetSettings(budget)
} catch (e: Exception) {
    // Budget validation failed - constraints impossible to satisfy
}
```

**Common validation failures**:
- System prompt exceeds total context window
- maxTokens exceeds available space after system prompt
- reasoningBudget exceeds maxTokens

### Runtime Token Management
```kotlin
try {
    val response = pipe.generateText(largeInput)
} catch (e: Exception) {
    if (e.message?.contains("Context window size is too small") == true) {
        // Input + context exceeds available space
        // Solution: Enable truncation or increase context window
    }
}
```

## Token Counting and Optimization

### Automatic Token Calculation
TPipe automatically counts tokens for:
- System prompts
- User input
- Context data
- Binary content (images, documents)
- Model reasoning output

### Optimization Strategies
```kotlin
// Conservative sizing (leave 10-15% buffer)
val modelLimit = 32000
val safeLimit = (modelLimit * 0.85).toInt()
pipe.setContextWindowSize(safeLimit)

// Reasoning model optimization
reasoningBudget = maxTokens / 2  // 50% allocation for reasoning

// Context-heavy applications
userPromptSize = contextWindowSize * 0.6  // 60% for rich context
```

## Production Considerations

### Model-Specific Configuration
```kotlin
// DeepSeek (high reasoning capability)
reasoningBudget = maxTokens * 0.6  // 60% for reasoning

// Claude (balanced performance)
allowUserPromptTruncation = true   // Flexible input handling

// GPT-OSS (analytical tasks)
allowUserPromptTruncation = false  // Preserve input integrity
truncationMethod = ContextWindowSettings.TruncateBottom
```

### Monitoring and Debugging
```kotlin
// Check actual token usage
val tokenCount = pipe.countAllTokens(content)
val settings = pipe.getTruncationSettings()
```

### Intentional Output Constraints
```kotlin
 // Accept partial responses as design choice
 pipe.setMaxTokens(2000)
    .enableMaxTokenOverflow()  
```

These token management features solve the core challenge of working with token-limited AI models: ensuring predictable behavior, preventing runtime failures, and optimizing resource utilization for different use cases. The key is matching your truncation strategy and token allocation to your specific application requirements.
## Next Steps

Now that you understand token management, learn about advanced token handling:

**→ [Token Counting, Truncation, and Tokenizer Tuning](token-counting-and-truncation.md)** - Advanced token handling
