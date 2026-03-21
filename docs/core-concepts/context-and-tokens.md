# Context Window Size, Token Budgets, and Truncation

## Table of Contents
- [Context Window Size - Managing Total Token Capacity](#context-window-size---managing-total-token-capacity)
- [Token Budgets - Predictable Resource Allocation](#token-budgets---predictable-resource-allocation)
- [Truncation - Intelligent Content Reduction](#truncation---intelligent-content-reduction)
- [Advanced Truncation Control](#advanced-truncation-control)
- [Semantic Compression - Legend-Backed Prompt Reduction](#semantic-compression---legend-backed-prompt-reduction)
- [Practical Implementation Patterns](#practical-implementation-patterns)
- [Error Prevention and Handling](#error-prevention-and-handling)
- [Token Counting and Optimization](#token-counting-and-optimization)
- [Production Considerations](#production-considerations)

TPipe provides sophisticated token management to solve the fundamental problem of AI model context limits. These features prevent runtime failures, optimize token usage, and ensure predictable behavior when dealing with large inputs.

## Simple vs Advanced Truncation

**Simple truncation** (`autoTruncateContext()` only):
```kotlin
pipe.autoTruncateContext()  // Basic truncation, no multi-page budgeting
pipe.autoTruncateContext(fillMode = true)  // Basic truncation with select-and-fill mode
```
- Uses provider-specific truncation methods
- Cannot distribute budgets across multiple pages
- No multi-page budgeting strategies available

**Advanced truncation** (`TokenBudgetSettings`):
```kotlin
pipe.setTokenBudget(TokenBudgetSettings())  // Automatically enables advanced truncation
    
// With fill mode for prioritized lorebook selection
pipe.setTokenBudget(TokenBudgetSettings())
    .enableLoreBookFillMode()  // Optional: enable fill mode separately
```
- Enables multi-page budgeting strategies (DYNAMIC_FILL, DYNAMIC_SIZE_FILL, EQUAL_SPLIT, etc.)
- Precise token budget control across system/user/output/reasoning
- TokenBudgetSettings automatically handles all truncation internally
- Use `enableLoreBookFillMode()` separately if you need fill mode with token budgeting

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
    subtractReasoningFromInput = false, // True to subtract from input window instead of output
    contextWindowSize = 32000,        // Total token budget
    allowUserPromptTruncation = true, // Truncation policy
    multiPageBudgetStrategy = MultiPageBudgetStrategy.DYNAMIC_FILL, // Multi-page allocation strategy
    pageWeights = mapOf("critical" to 2.0, "normal" to 1.0), // Optional page weights
    preserveTextMatches = true // Keep prompt-matching context/history when truncating
)
```

> ⚠️ **Important:** Multi-page budgeting strategies (DYNAMIC_FILL, DYNAMIC_SIZE_FILL, EQUAL_SPLIT, etc.) **only work with TokenBudgetSettings**. Simple `autoTruncateContext()` without TokenBudgetSettings uses basic truncation and cannot distribute budgets across multiple pages.

**What this does**: 
- **userPromptSize**: Enforces maximum user input size, throwing errors or truncating when exceeded
- **reasoningBudget**: Reserves tokens for model reasoning, automatically subtracted from maxTokens
- **subtractReasoningFromInput**: By default `reasoningBudget` is subtracted from `maxTokens` (output budget). Set to `true` to instead subtract from the available context window size, reserving space for reasoning injected into system/user prompts or context.
- **allowUserPromptTruncation**: Controls whether oversized inputs are truncated or cause failures
- **multiPageBudgetStrategy**: Controls how token budget is distributed across multiple context pages
- **pageWeights**: Optional weights for WEIGHTED_SPLIT strategy (higher = more tokens)
- **preserveTextMatches**: When true, context elements and conversation history entries containing words from the latest prompt are preserved before the usual truncation ordering runs.

**Token allocation calculation**:
```
Total: 32,000 tokens
- System prompt: ~2,000 tokens (calculated)
- Output budget: 20,000 tokens
- Reasoning budget: 8,000 tokens (subtracted from output)
- Effective output: 12,000 tokens (20,000 - 8,000)
= Available for user input: 10,000 tokens
```

## Dynamic vs Explicit User Prompt Allocation

### Understanding User Prompt Size Behavior

The `userPromptSize` parameter in `TokenBudgetSettings` controls how TPipe allocates space for user input, and it behaves differently depending on whether you provide an explicit value or leave it as `null`.

### Explicit User Prompt Allocation
```kotlin
val budget = TokenBudgetSettings(
    userPromptSize = 12000,           // Explicit allocation
    contextWindowSize = 32000,
    maxTokens = 8000
)
```

**How it works:**
- TPipe reserves exactly 12,000 tokens for user input
- If user input exceeds this limit, behavior depends on `allowUserPromptTruncation`
- Context space is calculated as: `contextWindowSize - systemPrompt - userPromptSize - maxTokens`
- Predictable and deterministic allocation

**Use when:**
- You need predictable token allocation
- You want to enforce strict input size limits
- You're building applications with known input patterns
- You want to prevent unexpectedly large inputs from consuming context space

### Dynamic User Prompt Allocation
```kotlin
val budget = TokenBudgetSettings(
    userPromptSize = null,            // Dynamic allocation - TPipe calculates automatically
    contextWindowSize = 32000,
    maxTokens = 8000
)
```

**How it works:**
1. **Automatic Calculation**: TPipe counts the actual tokens in your user prompt and allocates exactly that amount
2. **Space Optimization**: Remaining space after system prompt, user prompt, and output allocation goes to context
3. **Overflow Handling**: If the calculated user prompt size would exceed available space, TPipe automatically reduces it
4. **Cleanup**: After processing, `userPromptSize` is reset to `null` to prevent issues in subsequent calls

**Detailed Dynamic Allocation Process:**
```
1. Calculate actual user prompt tokens: 8,500 tokens
2. Check available space: 32,000 - 2,000 (system) - 8,000 (output) = 22,000 tokens
3. Allocate user prompt space: 8,500 tokens
4. Remaining context space: 22,000 - 8,500 = 13,500 tokens

If user prompt was larger (e.g., 25,000 tokens):
1. Calculate user prompt tokens: 25,000 tokens  
2. Available space: 22,000 tokens (insufficient!)
3. Reduce user prompt allocation: 22,000 tokens (fits available space)
4. Remaining context space: 0 tokens
5. User prompt gets truncated to fit the reduced allocation
```

**Use when:**
- Input sizes vary significantly between requests
- You want to maximize context space utilization
- You prefer automatic space optimization over strict limits
- You're building flexible applications that handle diverse input types

## Semantic Compression - Legend-Backed Prompt Reduction

TPipe can reduce prompt token usage before truncation by semantically compressing natural-language text.
The compressor removes function words, common filler phrases, and repeated proper nouns while leaving quoted
spans untouched and returning a legend that maps the short codes back to their original values.

```kotlin
val compression = pipe.compressPrompt("""
    Alice Johnson and Alice Johnson are going to review the launch proposal in order to help the team.
    "Quoted text stays untouched."
""".trimIndent())

val promptForLLM = if(compression.legend.isNotEmpty())
{
    "${compression.legend}\n\n${compression.compressedText}"
}
else
{
    compression.compressedText
}
```

**When to use it**
- Natural-language prompts that repeat names, roles, or filler phrases
- Long system prompts that can be safely rewritten as plain English
- Prompt budgets where you want to preserve meaning before falling back to truncation

**When not to use it**
- JSON, XML, code blocks, schemas, or other machine-readable payloads
- Prompts where quoted text must be preserved exactly as written

**Behavior:** `TokenBudgetSettings.compressUserPrompt` triggers the same compressor in the user-prompt budget path.
If the compressed result still exceeds the budget, TPipe continues through the existing truncation or failure logic.

### Overflow Handling in Dynamic Allocation

When dynamic allocation encounters insufficient space, TPipe implements sophisticated overflow handling:

```kotlin
val budget = TokenBudgetSettings(
    userPromptSize = null,                    // Dynamic allocation
    allowUserPromptTruncation = true,         // Enable overflow handling
    contextWindowSize = 32000,
    maxTokens = 8000
)
```

**Overflow scenarios:**
1. **User prompt + system prompt + output > context window**: User prompt size is automatically reduced
2. **Binary content pushes total over limit**: User prompt space is further reduced to accommodate binary data
3. **Insufficient space even after reduction**: Exception thrown if `allowUserPromptTruncation` is false

**Example overflow handling:**
```
Context window: 32,000 tokens
System prompt: 2,000 tokens  
Output budget: 8,000 tokens
User prompt (actual): 25,000 tokens
Binary content: 3,000 tokens

Step 1: Available space = 32,000 - 2,000 - 8,000 = 22,000 tokens
Step 2: User prompt exceeds available space (25,000 > 22,000)
Step 3: Reduce user prompt to fit: 22,000 tokens
Step 4: Account for binary content: 22,000 - 3,000 = 19,000 tokens
Step 5: Final user prompt allocation: 19,000 tokens
Step 6: User prompt truncated to fit 19,000 token budget
```

### Choosing Between Dynamic and Explicit Allocation

**Choose Explicit Allocation when:**
- Building production systems requiring predictable behavior
- Implementing strict content policies or size limits
- Working with known, consistent input patterns
- Need to guarantee minimum context space availability

**Choose Dynamic Allocation when:**
- Handling variable input sizes (user-generated content, document processing)
- Want to maximize token utilization efficiency
- Building flexible, adaptive applications
- Prefer automatic optimization over manual tuning

**Hybrid approach:**
```kotlin
// Start with dynamic allocation for flexibility
val flexibleBudget = TokenBudgetSettings(
    userPromptSize = null,                // Dynamic
    allowUserPromptTruncation = true,     // Handle overflow gracefully
    contextWindowSize = 32000
)

// Switch to explicit allocation when patterns emerge
val optimizedBudget = TokenBudgetSettings(
    userPromptSize = 15000,               // Based on observed patterns
    allowUserPromptTruncation = false,    // Strict enforcement
    contextWindowSize = 32000
)
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

### Dynamic Allocation for Variable Input Processing
```kotlin
// Flexible document processing with dynamic user prompt allocation
val documentProcessor = BedrockPipe()
    .setContextWindowSize(128000)
    .setMaxTokens(8000)
    .truncateModuleContext()

val dynamicBudget = TokenBudgetSettings(
    userPromptSize = null,                    // Dynamic allocation based on actual content
    maxTokens = 8000,
    reasoningBudget = 4000,
    allowUserPromptTruncation = true,         // Handle overflow gracefully
    contextWindowSize = 128000,
    multiPageBudgetStrategy = MultiPageBudgetStrategy.DYNAMIC_FILL
)

// Usage with varying input sizes
val shortQuery = "Summarize this document"           // ~5 tokens -> 5 tokens allocated
val longQuery = "Analyze this document in detail..." // ~500 tokens -> 500 tokens allocated
val massiveQuery = "..." // 50,000 tokens -> Automatically reduced to fit available space
```

**Purpose**: Optimal space utilization for applications with highly variable input sizes. Automatically adapts to content while maximizing context space.

### Explicit Allocation for Predictable Systems
```kotlin
// Production system with strict input controls
val productionPipe = BedrockPipe()
    .setContextWindowSize(32000)
    .setMaxTokens(4000)
    .truncateModuleContext()

val explicitBudget = TokenBudgetSettings(
    userPromptSize = 8000,                    // Fixed allocation - predictable behavior
    maxTokens = 4000,
    reasoningBudget = 2000,
    allowUserPromptTruncation = false,        // Strict enforcement - fail if exceeded
    contextWindowSize = 32000,
    multiPageBudgetStrategy = MultiPageBudgetStrategy.EQUAL_SPLIT
)

// Guaranteed behavior: exactly 8000 tokens for user input, 18000 for context
// Throws exception if user input exceeds 8000 tokens
```

**Purpose**: Predictable, deterministic behavior for production systems. Guarantees minimum context space and enforces input size policies.

### Hybrid Approach - Adaptive Allocation
```kotlin
// Start with dynamic allocation, switch to explicit based on patterns
class AdaptiveTokenManager {
    private var observedSizes = mutableListOf<Int>()
    
    fun createBudget(isProduction: Boolean): TokenBudgetSettings {
        return if (isProduction && observedSizes.isNotEmpty()) {
            // Use observed patterns for explicit allocation
            val averageSize = observedSizes.average().toInt()
            val maxObserved = observedSizes.maxOrNull() ?: 0
            val safeAllocation = (maxObserved * 1.2).toInt() // 20% buffer
            
            TokenBudgetSettings(
                userPromptSize = safeAllocation,          // Based on observed patterns
                allowUserPromptTruncation = false,        // Strict in production
                contextWindowSize = 32000
            )
        } else {
            // Dynamic allocation for development/learning
            TokenBudgetSettings(
                userPromptSize = null,                    // Learn from actual usage
                allowUserPromptTruncation = true,         // Flexible during learning
                contextWindowSize = 32000
            )
        }
    }
    
    fun recordUsage(actualTokens: Int) {
        observedSizes.add(actualTokens)
        if (observedSizes.size > 100) {
            observedSizes.removeAt(0) // Keep recent history
        }
    }
}
```

**Purpose**: Combines the flexibility of dynamic allocation during development with the predictability of explicit allocation in production.

## Error Prevention and Handling

### Token Budget Validation
```kotlin
try {
    pipe.setTokenBudget(budget)
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
    val response = runBlocking { pipe.generateText(largeInput) }
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

### Size-Based Priority Allocation - DYNAMIC_SIZE_FILL Strategy
```kotlin
// Protect smaller contexts, truncate larger ones first
val sizePriorityPipe = BedrockPipe()
    .setPageKey("gameplayData, userPreferences, storyContent")
    .setTokenBudget(TokenBudgetSettings(
        contextWindowSize = 32000,
        maxTokens = 4000,
        multiPageBudgetStrategy = MultiPageBudgetStrategy.DYNAMIC_SIZE_FILL
    ))
    .enableLoreBookFillMode()  // Optional: enable fill mode for prioritized lorebook selection

// Alternative method chaining
pipe.enableDynamicSizeFill()
```

**How it works:**
- **Phase 1**: Calculate context sizes and sort by size (smallest first)
- **Phase 2**: Allocate full budget to smaller contexts before larger ones
- **Phase 3**: Redistribute unused budget from small contexts to larger ones
- **Result**: Critical small contexts (gameplay data, user preferences) are preserved intact, while expendable large contexts (story content) are truncated as needed

**Use cases:**
- Gaming applications where gameplay state must be preserved over story content
- Applications with critical small configuration data and large reference material
- Systems where smaller contexts contain essential instructions or state

**Purpose**: Intelligent context prioritization based on size, ensuring critical small data survives memory pressure while allowing large expendable content to be truncated.

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
