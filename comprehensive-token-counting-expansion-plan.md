# Comprehensive Token Counting Expansion Plan

## Overview
Expand TPipe's token counting system to provide accurate, comprehensive tracking of all token consumption across parent and child pipes, with proper aggregation and pipeline-level reporting.

## Current State Analysis
- **Input counting**: Line 4001, called BEFORE truncation/budgeting
- **Output counting**: Line 4049, called after inference
- **Limitation**: No child pipe token tracking
- **Issue**: Input counting happens before actual content is finalized

## Implementation Plan

### Phase 1: Token Counting Data Structure

#### Step 1.1: Create TokenUsage Data Class
**File:** `/src/main/kotlin/Pipe/Pipe.kt`
**Location:** Add after existing data classes (around line 130)

```kotlin
/**
 * Comprehensive token usage tracking for pipes and their children.
 * Tracks both direct pipe usage and aggregated child pipe consumption.
 */
@kotlinx.serialization.Serializable
data class TokenUsage(
    var inputTokens: Int = 0,
    var outputTokens: Int = 0,
    var childPipeTokens: MutableMap<String, TokenUsage> = mutableMapOf(),
    var totalInputTokens: Int = 0,  // Includes all child pipes
    var totalOutputTokens: Int = 0  // Includes all child pipes
) {
    /**
     * Adds child pipe token usage and recalculates totals.
     */
    fun addChildUsage(pipeName: String, usage: TokenUsage) {
        childPipeTokens[pipeName] = usage
        recalculateTotals()
    }
    
    /**
     * Recalculates total tokens including all child pipe usage.
     */
    private fun recalculateTotals() {
        totalInputTokens = inputTokens + childPipeTokens.values.sumOf { it.totalInputTokens }
        totalOutputTokens = outputTokens + childPipeTokens.values.sumOf { it.totalOutputTokens }
    }
}
```

#### Step 1.2: Add Token Tracking Properties to Pipe Class
**File:** `/src/main/kotlin/Pipe/Pipe.kt`
**Location:** Add to pipe properties section (around line 800)

```kotlin
/**
 * Enables comprehensive token usage tracking for this pipe and all child pipes.
 * When disabled, only basic pipeline-level tracking is maintained for backward compatibility.
 */
@Serializable
protected var comprehensiveTokenTracking = false

/**
 * Comprehensive token usage tracking for this pipe and all child pipes.
 * Only populated when comprehensiveTokenTracking is enabled.
 */
@kotlinx.serialization.Transient
protected var tokenUsage = TokenUsage()
```

### Phase 2: Enable/Disable Setting and Method Chaining

#### Step 2.1: Add Method Chaining Function
**File:** `/src/main/kotlin/Pipe/Pipe.kt`
**Location:** Add after existing method chaining functions (around line 1650)

```kotlin
/**
 * Enables comprehensive token usage tracking for this pipe and all child pipes.
 * When enabled, provides detailed token consumption breakdown including child pipe usage.
 * When disabled, maintains only basic pipeline-level tracking for backward compatibility.
 * 
 * @return This Pipe object for method chaining
 */
fun enableComprehensiveTokenTracking(): Pipe {
    this.comprehensiveTokenTracking = true
    return this
}

/**
 * Disables comprehensive token usage tracking.
 * Only basic pipeline-level tracking will be maintained.
 * 
 * @return This Pipe object for method chaining
 */
fun disableComprehensiveTokenTracking(): Pipe {
    this.comprehensiveTokenTracking = false
    this.tokenUsage = TokenUsage() // Reset to clean state
    return this
}
```

### Phase 3: Move Input Token Counting After Truncation

#### Step 3.1: Remove Current Input Counting
**File:** `/src/main/kotlin/Pipe/Pipe.kt`
**Location:** Line 4001

**Current Code:**
```kotlin
//Count input tokens.
trace(TraceEventType.PIPE_START, TracePhase.EXECUTION,
    metadata = mapOf("tokenCount" to countTokens(true, processedContent)))
```

**Replace With:**
```kotlin
// Input token counting moved to after truncation for accuracy
trace(TraceEventType.PIPE_START, TracePhase.EXECUTION, processedContent)
```

#### Step 3.2: Add Accurate Input Counting After Truncation
**File:** `/src/main/kotlin/Pipe/Pipe.kt`
**Location:** After line 3902 (after `truncateToFitTokenBudget()` call)

**Add:**
```kotlin
// Count actual input tokens after all truncation and budgeting
if (comprehensiveTokenTracking) {
    val actualInputTokens = countActualInputTokens(baseContent)
    tokenUsage.inputTokens = actualInputTokens
    trace(TraceEventType.CONTEXT_PREPARED, TracePhase.EXECUTION,
        metadata = mapOf("actualInputTokens" to actualInputTokens))
}
```

#### Step 3.3: Create Accurate Input Token Counting Method
**File:** `/src/main/kotlin/Pipe/Pipe.kt`
**Location:** Add after `countTokens()` method (around line 4744)

```kotlin
/**
 * Counts actual input tokens after all truncation and budgeting is complete.
 * This provides accurate count of tokens actually sent to the inference provider.
 */
private fun countActualInputTokens(content: MultimodalContent): Int {
    val truncationSettings = getTruncationSettings()
    var totalTokens = 0
    
    // Count system prompt
    totalTokens += Dictionary.countTokens(systemPrompt, truncationSettings)
    
    // Count actual user prompt (after truncation)
    totalTokens += Dictionary.countTokens(content.text, truncationSettings)
    
    // Count context (after truncation)
    if (miniContextBank.isEmpty()) {
        val contextJson = serialize(contextWindow)
        totalTokens += Dictionary.countTokens(contextJson, truncationSettings)
    } else {
        val miniBankJson = serialize(miniContextBank)
        totalTokens += Dictionary.countTokens(miniBankJson, truncationSettings)
    }
    
    // Count binary content
    totalTokens += countBinaryTokens(content, truncationSettings)
    
    // Count any model reasoning injected
    totalTokens += Dictionary.countTokens(content.modelReasoning, truncationSettings)
    
    return totalTokens
}
```

### Phase 4: Child Pipe Token Tracking

#### Step 4.1: Modify Validator Pipe Token Tracking
**File:** `/src/main/kotlin/Pipe/Pipe.kt`
**Location:** Replace lines 4062-4070

**Current Code:**
```kotlin
if (tracingEnabled) {
    propagateTracingRecursively()
}
val validatorPipeResult : Deferred<MultimodalContent> = async {
    validatorPipe?.execute(generatedContent) ?: MultimodalContent()
}
validatorPipeContent = validatorPipeResult.await()
```

**Replace With:**
```kotlin
if (tracingEnabled) {
    propagateTracingRecursively()
}
val validatorPipeResult : Deferred<MultimodalContent> = async {
    validatorPipe?.execute(generatedContent) ?: MultimodalContent()
}
validatorPipeContent = validatorPipeResult.await()

// Aggregate validator pipe token usage if comprehensive tracking enabled
if (comprehensiveTokenTracking) {
    validatorPipe?.let { pipe ->
        tokenUsage.addChildUsage("validator-${pipe.pipeName}", pipe.tokenUsage)
    }
}
```

#### Step 4.2: Modify Transformation Pipe Token Tracking
**File:** `/src/main/kotlin/Pipe/Pipe.kt`
**Location:** Replace lines 4104-4112 and 4170-4178

**Add after each transformation pipe execution:**
```kotlin
// Aggregate transformation pipe token usage if comprehensive tracking enabled
if (comprehensiveTokenTracking) {
    transformationPipe?.let { pipe ->
        tokenUsage.addChildUsage("transformation-${pipe.pipeName}", pipe.tokenUsage)
    }
}
```

#### Step 4.3: Modify Branch Pipe Token Tracking
**File:** `/src/main/kotlin/Pipe/Pipe.kt`
**Location:** Replace lines 4221-4229

**Add after branch pipe execution:**
```kotlin
// Aggregate branch pipe token usage if comprehensive tracking enabled
if (comprehensiveTokenTracking) {
    branchPipe?.let { pipe ->
        tokenUsage.addChildUsage("branch-${pipe.pipeName}", pipe.tokenUsage)
    }
}
```

#### Step 4.4: Modify Reasoning Pipe Token Tracking
**File:** `/src/main/kotlin/Pipe/Pipe.kt**
**Location:** In `executeReasoningPipe()` method (around line 4720)

**Add at end of reasoning execution:**
```kotlin
// Aggregate reasoning pipe token usage if comprehensive tracking enabled
if (comprehensiveTokenTracking) {
    reasoningPipe?.let { pipe ->
        tokenUsage.addChildUsage("reasoning-${pipe.pipeName}", pipe.tokenUsage)
    }
}
```

### Phase 5: Update Output Token Counting

#### Step 5.1: Modify Output Token Counting
**File:** `/src/main/kotlin/Pipe/Pipe.kt`
**Location:** Replace line 4049

**Current Code:**
```kotlin
trace(TraceEventType.API_CALL_SUCCESS, TracePhase.EXECUTION, generatedContent,
    metadata = mapOf("tokenCount" to countTokens(false, generatedContent)))
```

**Replace With:**
```kotlin
// Count output tokens and update usage tracking
val outputTokens = countTokens(false, generatedContent)

if (comprehensiveTokenTracking) {
    tokenUsage.outputTokens = outputTokens
    tokenUsage.recalculateTotals()
    
    trace(TraceEventType.API_CALL_SUCCESS, TracePhase.EXECUTION, generatedContent,
        metadata = mapOf(
            "outputTokens" to outputTokens,
            "totalInputTokens" to tokenUsage.totalInputTokens,
            "totalOutputTokens" to tokenUsage.totalOutputTokens
        ))
} else {
    trace(TraceEventType.API_CALL_SUCCESS, TracePhase.EXECUTION, generatedContent,
        metadata = mapOf("tokenCount" to outputTokens))
}
```

### Phase 6: Pipeline Integration

#### Step 6.1: Add Token Usage Aggregation to Pipeline
**File:** `/src/main/kotlin/Pipeline/Pipeline.kt`
**Location:** Add to Pipeline class properties (around line 100)

```kotlin
/**
 * Aggregated token usage across all pipes in the pipeline.
 * Only populated when pipes have comprehensive token tracking enabled.
 */
@kotlinx.serialization.Transient
private var pipelineTokenUsage = TokenUsage()
```

#### Step 6.2: Aggregate Pipe Token Usage in Pipeline
**File:** `/src/main/kotlin/Pipeline/Pipeline.kt`
**Location:** After pipe execution in `executeMultimodal()` (around line 944)

**Add after `generatedContent = result.await()`:**
```kotlin
// Aggregate token usage from executed pipe if comprehensive tracking enabled
if (pipe.comprehensiveTokenTracking) {
    pipelineTokenUsage.addChildUsage("pipe-${currentPipeIndex}-${pipe.pipeName}", pipe.tokenUsage)
}

// Update pipeline-level tracking for backward compatibility
inputTokensSpent = if (pipe.comprehensiveTokenTracking) {
    pipelineTokenUsage.totalInputTokens
} else {
    inputTokensSpent + (pipe.pipelineRef?.inputTokensSpent ?: 0)
}
outputTokensSpent = if (pipe.comprehensiveTokenTracking) {
    pipelineTokenUsage.totalOutputTokens  
} else {
    outputTokensSpent + (pipe.pipelineRef?.outputTokensSpent ?: 0)
}
```

#### Step 6.3: Add Pipeline Token Usage Getter
**File:** `/src/main/kotlin/Pipeline/Pipeline.kt`
**Location:** Add after existing getters (around line 500)

```kotlin
/**
 * Gets comprehensive token usage for the entire pipeline including all pipes.
 */
fun getTokenUsage(): TokenUsage = pipelineTokenUsage

/**
 * Gets total input tokens spent across all pipes in the pipeline.
 */
fun getTotalInputTokens(): Int = pipelineTokenUsage.totalInputTokens

/**
 * Gets total output tokens spent across all pipes in the pipeline.
 */
fun getTotalOutputTokens(): Int = pipelineTokenUsage.totalOutputTokens
```

### Phase 7: Public API Methods

#### Step 7.1: Add Token Usage Getter to Pipe Class
**File:** `/src/main/kotlin/Pipe/Pipe.kt`
**Location:** Add after existing getters (around line 4800)

```kotlin
/**
 * Gets comprehensive token usage for this pipe including all child pipes.
 * Returns empty TokenUsage if comprehensive tracking is disabled.
 */
fun getTokenUsage(): TokenUsage = if (comprehensiveTokenTracking) tokenUsage else TokenUsage()

/**
 * Gets total input tokens for this pipe and all child pipes.
 * Returns 0 if comprehensive tracking is disabled.
 */
fun getTotalInputTokens(): Int = if (comprehensiveTokenTracking) tokenUsage.totalInputTokens else 0

/**
 * Gets total output tokens for this pipe and all child pipes.
 * Returns 0 if comprehensive tracking is disabled.
 */
fun getTotalOutputTokens(): Int = if (comprehensiveTokenTracking) tokenUsage.totalOutputTokens else 0

/**
 * Checks if comprehensive token tracking is enabled for this pipe.
 */
fun isComprehensiveTokenTrackingEnabled(): Boolean = comprehensiveTokenTracking
```

### Phase 8: Testing and Validation

#### Step 8.1: Create Token Usage Test
**File:** `/src/test/kotlin/TokenUsageTest.kt`

```kotlin
@Test
fun testComprehensiveTokenUsageEnabled() = runBlocking {
    val childReasoning = DummyPipe("Child-Reasoning")
        .enableComprehensiveTokenTracking()
    val parentReasoning = DummyPipe("Parent-Reasoning")
        .setReasoningPipe(childReasoning)
        .enableComprehensiveTokenTracking()
    
    val mainPipe = DummyPipe("Main-Pipe")
        .setReasoningPipe(parentReasoning)
        .setValidatorPipe(DummyPipe("Validator").enableComprehensiveTokenTracking())
        .enableComprehensiveTokenTracking()
    
    val pipeline = Pipeline()
        .add(mainPipe)
    
    val result = pipeline.execute(MultimodalContent("test input"))
    
    val tokenUsage = pipeline.getTokenUsage()
    
    assertTrue(tokenUsage.totalInputTokens > 0, "Should track input tokens")
    assertTrue(tokenUsage.totalOutputTokens > 0, "Should track output tokens")
    assertTrue(tokenUsage.childPipeTokens.isNotEmpty(), "Should track child pipe tokens")
    
    // Verify nested reasoning tokens are included
    val mainPipeUsage = tokenUsage.childPipeTokens["pipe-0-Main-Pipe"]
    assertNotNull(mainPipeUsage, "Should track main pipe usage")
    assertTrue(mainPipeUsage!!.childPipeTokens.containsKey("reasoning-Parent-Reasoning"))
}

@Test
fun testComprehensiveTokenUsageDisabled() = runBlocking {
    val mainPipe = DummyPipe("Main-Pipe")
        .setReasoningPipe(DummyPipe("Reasoning"))
        .setValidatorPipe(DummyPipe("Validator"))
        // Note: comprehensive tracking NOT enabled
    
    val pipeline = Pipeline()
        .add(mainPipe)
    
    val result = pipeline.execute(MultimodalContent("test input"))
    
    val tokenUsage = mainPipe.getTokenUsage()
    
    assertEquals(0, tokenUsage.totalInputTokens, "Should not track when disabled")
    assertEquals(0, tokenUsage.totalOutputTokens, "Should not track when disabled")
    assertTrue(tokenUsage.childPipeTokens.isEmpty(), "Should not track child pipes when disabled")
    
    // But basic pipeline tracking should still work
    assertTrue(pipeline.inputTokensSpent > 0, "Basic pipeline tracking should work")
    assertTrue(pipeline.outputTokensSpent > 0, "Basic pipeline tracking should work")
}
```

#### Step 8.2: Validate Token Accuracy
**File:** `/src/test/kotlin/TokenAccuracyTest.kt`

```kotlin
@Test
fun testTokenCountingAccuracyWhenEnabled() = runBlocking {
    val pipe = DummyPipe("Test-Pipe")
        .setTokenBudget(TokenBudgetSettings(
            contextWindowSize = 10000,
            userPromptSize = 2000,
            maxTokens = 1000
        ))
        .enableComprehensiveTokenTracking()
    
    val longInput = "test ".repeat(1000) // Create content that will be truncated
    val result = pipe.execute(MultimodalContent(longInput))
    
    val usage = pipe.getTokenUsage()
    
    // Input tokens should reflect actual truncated content, not original
    assertTrue(usage.inputTokens < Dictionary.countTokens(longInput, pipe.getTruncationSettings()))
    assertTrue(usage.inputTokens > 0)
    assertTrue(usage.outputTokens > 0)
}

@Test
fun testTokenCountingWhenDisabled() = runBlocking {
    val pipe = DummyPipe("Test-Pipe")
        .setTokenBudget(TokenBudgetSettings(
            contextWindowSize = 10000,
            userPromptSize = 2000,
            maxTokens = 1000
        ))
        // Note: comprehensive tracking NOT enabled
    
    val longInput = "test ".repeat(1000)
    val result = pipe.execute(MultimodalContent(longInput))
    
    val usage = pipe.getTokenUsage()
    
    // Should return empty usage when disabled
    assertEquals(0, usage.inputTokens)
    assertEquals(0, usage.outputTokens)
    assertTrue(usage.childPipeTokens.isEmpty())
    
    // But basic countTokens() should still work for backward compatibility
    assertTrue(pipe.countTokens(true, MultimodalContent(longInput)) > 0)
}
```

## Implementation Timeline

### Phase 1-2: Core Infrastructure and Settings (Week 1)
- TokenUsage data class
- Comprehensive token tracking setting and method chaining functions
- Enable/disable functionality

### Phase 3-4: Token Counting Integration (Week 2)
- Move input counting after truncation with setting checks
- Child pipe token tracking with conditional logic
- Output token counting updates

### Phase 5-6: Pipeline Integration and API (Week 3)
- Pipeline-level token aggregation with setting awareness
- Public API methods with setting checks
- Backward compatibility maintenance

### Phase 7-8: Testing and Validation (Week 4)
- Comprehensive test suite for enabled/disabled states
- Accuracy validation for both modes
- Performance impact assessment

## Success Criteria

### Functional Requirements
✅ Optional comprehensive token tracking via method chaining
✅ Accurate input token counting after truncation/budgeting (when enabled)
✅ Comprehensive child pipe token tracking (when enabled)
✅ Proper token aggregation from child to parent pipes (when enabled)
✅ Pipeline-level token usage reporting (when enabled)
✅ Backward compatibility with existing `inputTokensSpent`/`outputTokensSpent`
✅ Graceful degradation when comprehensive tracking is disabled

### Quality Requirements
✅ No performance degradation
✅ Accurate token counts match actual inference provider usage
✅ Comprehensive test coverage
✅ Clear API for accessing token usage data

## Risk Mitigation

### Performance Risk
- **Mitigation**: Lazy calculation of totals, efficient aggregation
- **Validation**: Performance benchmarks with nested pipe structures

### Accuracy Risk
- **Mitigation**: Count tokens after all transformations complete
- **Validation**: Compare with inference provider reported usage

### Complexity Risk
- **Mitigation**: Clear separation of concerns, comprehensive documentation
- **Validation**: Code review and integration testing

This plan ensures accurate, comprehensive token tracking across all TPipe operations while maintaining performance and backward compatibility.
