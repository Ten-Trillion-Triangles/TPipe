# Add Token Counting Support to Splitter and Manifold Tracing Systems

## Overview

This plan implements comprehensive token counting capabilities for Splitter and Manifold container classes, extending the existing Pipeline token counting functionality to provide unified token tracking across all TPipe execution contexts.

## Current State Analysis

### Existing Token Counting (Pipeline)
- `inputTokensSpent` and `outputTokensSpent` properties
- `getTokenCount()` method returning formatted string
- Direct integration with pipe execution

### Missing Capabilities
- **Splitter**: No token aggregation from parallel pipelines
- **Manifold**: Token budget management exists, but no cumulative tracking
- **TraceEvent**: No dedicated token fields in trace events
- **Container Integration**: No token propagation from contained pipelines

## Design Decisions

### Token Aggregation Strategy
- **Splitter**: Sum tokens from all parallel pipelines (thread-safe)
- **Manifold**: Track tokens across manager and worker agents separately
- **Inheritance**: Extend common token counting interface

### Tracing Integration
- Add token metadata to existing TraceEvent structure
- Create container-specific trace events for token milestones
- Maintain backward compatibility with existing tracing

### Thread Safety
- Use atomic operations for Splitter parallel execution
- Mutex protection for token updates in concurrent scenarios

## Implementation Plan

### Step 1: Create Token Counting Interface

**File**: `src/main/kotlin/Pipeline/TokenCountable.kt`

```kotlin
interface TokenCountable 
{
    var inputTokensSpent: Int
    var outputTokensSpent: Int
    
    fun getTokenCount(): String
    fun addInputTokens(tokens: Int)
    fun addOutputTokens(tokens: Int)
    fun getTotalTokens(): Int
    fun resetTokenCount()
}
```

**Validation**: Interface compiles and provides consistent API

### Step 2: Implement Token Counting Base Class

**File**: `src/main/kotlin/Pipeline/TokenCounter.kt`

```kotlin
open class TokenCounter : TokenCountable 
{
    override var inputTokensSpent: Int = 0
        protected set
    override var outputTokensSpent: Int = 0
        protected set
    
    private val tokenMutex = Mutex()
    
    override suspend fun addInputTokens(tokens: Int) 
    {
        tokenMutex.withLock {
            inputTokensSpent += tokens
        }
    }
    
    override suspend fun addOutputTokens(tokens: Int) 
    {
        tokenMutex.withLock {
            outputTokensSpent += tokens
        }
    }
    
    override fun getTokenCount(): String 
    {
        return "Input tokens: $inputTokensSpent \n Output Tokens: $outputTokensSpent"
    }
    
    override fun getTotalTokens(): Int = inputTokensSpent + outputTokensSpent
    
    override fun resetTokenCount() 
    {
        inputTokensSpent = 0
        outputTokensSpent = 0
    }
}
```

**Validation**: Thread-safe token operations work correctly

### Step 3: Update Pipeline Class

**File**: `src/main/kotlin/Pipeline/Pipeline.kt`

**Changes**:
- Make Pipeline extend TokenCounter instead of having direct properties
- Remove duplicate token counting code
- Ensure existing getTokenCount() method works unchanged

```kotlin
class Pipeline : P2PInterface, TokenCounter() {
    // Remove: var inputTokensSpent = 0
    // Remove: var outputTokensSpent = 0
    // Keep existing getTokenCount() method as override
}
```

**Validation**: Existing Pipeline token counting functionality unchanged

### Step 4: Add Token Counting to Splitter

**File**: `src/main/kotlin/Pipeline/Splitter.kt`

**Changes**:
- Extend TokenCounter
- Aggregate tokens from completed pipelines
- Add token tracking to trace events

```kotlin
class Splitter : TokenCounter() 
{
    // Add after existing properties
    private val pipelineTokens = ConcurrentHashMap<String, Pair<Int, Int>>()
    
    // Add to handlePipelineCompletion method
    private suspend fun collectTokensFromPipeline(pipeline: Pipeline, key: String) 
    {
        val pipelineInputTokens = pipeline.inputTokensSpent
        val pipelineOutputTokens = pipeline.outputTokensSpent
        
        pipelineTokens[key] = Pair(pipelineInputTokens, pipelineOutputTokens)
        
        addInputTokens(pipelineInputTokens)
        addOutputTokens(pipelineOutputTokens)
        
        if (tracingEnabled) {
            trace(TraceEventType.SPLITTER_PIPELINE_COMPLETION, TracePhase.POST_PROCESSING,
                  metadata = mapOf(
                      "pipelineTokens" to getTokenCount(),
                      "totalTokens" to getTotalTokens(),
                      "pipelineKey" to key
                  ))
        }
    }
    
    // Add method to get individual pipeline token counts
    fun getPipelineTokenCounts(): Map<String, Pair<Int, Int>> = pipelineTokens.toMap()
}
```

**Validation**: Token aggregation works correctly in parallel execution

### Step 5: Add Token Counting to Manifold

**File**: `src/main/kotlin/Pipeline/Manifold.kt`

**Changes**:
- Extend TokenCounter
- Track manager and worker tokens separately
- Integrate with existing token budget management

```kotlin
class Manifold : TokenCounter() 
{
    // Add after existing properties
    private var managerTokensSpent = Pair(0, 0)
    private val workerTokensMap = mutableMapOf<String, Pair<Int, Int>>()
    
    // Add to manager pipeline execution
    private suspend fun collectManagerTokens(managerPipeline: Pipeline) 
    {
        val inputTokens = managerPipeline.inputTokensSpent
        val outputTokens = managerPipeline.outputTokensSpent
        
        managerTokensSpent = Pair(inputTokens, outputTokens)
        addInputTokens(inputTokens)
        addOutputTokens(outputTokens)
        
        if (tracingEnabled) {
            trace(TraceEventType.MANAGER_DECISION, TracePhase.EXECUTION,
                  metadata = mapOf(
                      "managerTokens" to "Input: $inputTokens, Output: $outputTokens",
                      "totalTokens" to getTotalTokens()
                  ))
        }
    }
    
    // Add to worker agent execution
    private suspend fun collectWorkerTokens(agentName: String, pipeline: Pipeline) 
    {
        val inputTokens = pipeline.inputTokensSpent
        val outputTokens = pipeline.outputTokensSpent
        
        workerTokensMap[agentName] = Pair(inputTokens, outputTokens)
        addInputTokens(inputTokens)
        addOutputTokens(outputTokens)
        
        if (tracingEnabled) {
            trace(TraceEventType.AGENT_RESPONSE, TracePhase.EXECUTION,
                  metadata = mapOf(
                      "agentName" to agentName,
                      "agentTokens" to "Input: $inputTokens, Output: $outputTokens",
                      "totalTokens" to getTotalTokens()
                  ))
        }
    }
    
    // Add methods for detailed token breakdown
    fun getManagerTokens(): Pair<Int, Int> = managerTokensSpent
    fun getWorkerTokens(): Map<String, Pair<Int, Int>> = workerTokensMap.toMap()
    
    override fun getTokenCount(): String 
    {
        val base = super.getTokenCount()
        val managerInfo = "Manager tokens: ${managerTokensSpent.first} input, ${managerTokensSpent.second} output"
        val workerCount = workerTokensMap.size
        return "$base\n$managerInfo\nWorker agents: $workerCount"
    }
}
```

**Validation**: Token tracking works across manager and worker executions

### Step 6: Enhance TraceEvent for Token Data

**File**: `src/main/kotlin/Debug/TraceEvent.kt`

**Changes**:
- Add convenience methods for token metadata
- Maintain backward compatibility

```kotlin
data class TraceEvent(
    // ... existing fields ...
) {
    // Add convenience methods
    fun getInputTokens(): Int? = metadata["inputTokens"] as? Int
    fun getOutputTokens(): Int? = metadata["outputTokens"] as? Int
    fun getTotalTokens(): Int? = metadata["totalTokens"] as? Int
    
    companion object {
        // Add helper for creating token-aware events
        fun withTokens(
            timestamp: Long,
            pipeId: String,
            pipeName: String,
            eventType: TraceEventType,
            phase: TracePhase,
            content: MultimodalContent?,
            contextSnapshot: ContextWindow?,
            inputTokens: Int,
            outputTokens: Int,
            additionalMetadata: Map<String, Any> = emptyMap()
        ): TraceEvent {
            val tokenMetadata = mapOf(
                "inputTokens" to inputTokens,
                "outputTokens" to outputTokens,
                "totalTokens" to (inputTokens + outputTokens)
            ) + additionalMetadata
            
            return TraceEvent(
                timestamp = timestamp,
                pipeId = pipeId,
                pipeName = pipeName,
                eventType = eventType,
                phase = phase,
                content = content,
                contextSnapshot = contextSnapshot,
                metadata = tokenMetadata
            )
        }
    }
}
```

**Validation**: Token metadata accessible in trace events

### Step 7: Add New Trace Event Types

**File**: `src/main/kotlin/Debug/TraceEventType.kt`

**Changes**:
- Add container-specific token events

```kotlin
enum class TraceEventType {
    // ... existing events ...
    
    // Container Token Events
    SPLITTER_TOKEN_AGGREGATION,
    MANIFOLD_MANAGER_TOKENS,
    MANIFOLD_WORKER_TOKENS,
    CONTAINER_TOKEN_SUMMARY,
}
```

**Validation**: New event types integrate with existing tracing

### Step 8: Update TraceVisualizer for Token Display

**File**: `src/main/kotlin/Debug/TraceVisualizer.kt`

**Changes**:
- Add token information to HTML and console output
- Create token summary sections

```kotlin
class TraceVisualizer {
    // Add method for token summary
    private fun generateTokenSummary(trace: List<TraceEvent>): String {
        val tokenEvents = trace.filter { 
            it.metadata.containsKey("totalTokens") 
        }
        
        if (tokenEvents.isEmpty()) return ""
        
        val totalTokens = tokenEvents.lastOrNull()?.getTotalTokens() ?: 0
        val inputTokens = tokenEvents.lastOrNull()?.getInputTokens() ?: 0
        val outputTokens = tokenEvents.lastOrNull()?.getOutputTokens() ?: 0
        
        return """
        <div class="token-summary">
            <h3>Token Usage Summary</h3>
            <p>Total Tokens: $totalTokens</p>
            <p>Input Tokens: $inputTokens</p>
            <p>Output Tokens: $outputTokens</p>
        </div>
        """.trimIndent()
    }
    
    // Update generateHtmlReport to include token summary
    // Update generateConsoleOutput to show token info
}
```

**Validation**: Token information displays correctly in trace reports

### Step 9: Integration Points

**Files to Update**:

1. **Splitter.kt** - Add token collection calls:
   - In pipeline completion handlers
   - In callback execution
   - In result collection methods

2. **Manifold.kt** - Add token collection calls:
   - After manager pipeline execution
   - After worker agent responses
   - In loop iteration completions

3. **Pipeline.kt** - Ensure compatibility:
   - Verify existing token counting still works
   - Test with containers

**Validation**: All integration points work without breaking existing functionality

### Step 10: Testing and Validation

**Test Files to Create**:

1. `src/test/kotlin/Pipeline/SplitterTokenCountingTest.kt`
2. `src/test/kotlin/Pipeline/ManifoldTokenCountingTest.kt`
3. `src/test/kotlin/Debug/TokenTraceVisualizationTest.kt`

**Test Scenarios**:
- Parallel pipeline token aggregation in Splitter
- Manager/worker token tracking in Manifold
- Thread safety of token counting operations
- Trace event token metadata accuracy
- Token visualization in reports

## Edge Cases and Considerations

### Thread Safety
- All token operations must be atomic
- Concurrent pipeline completions in Splitter
- Manifold loop iterations with multiple agents

### Error Handling
- Pipeline failures should still report partial token counts
- Token counting failures shouldn't break execution
- Graceful degradation when token data unavailable

### Performance
- Minimal overhead for token tracking
- Efficient aggregation algorithms
- Memory management for large token datasets

### Backward Compatibility
- Existing Pipeline token counting unchanged
- Existing trace events still work
- Optional token metadata in traces

## Success Criteria

1. **Splitter**: Aggregates tokens from all parallel pipelines
2. **Manifold**: Tracks manager and worker tokens separately
3. **Tracing**: Token information included in trace events
4. **Visualization**: Token data displayed in HTML/console reports
5. **Thread Safety**: No race conditions in token counting
6. **Compatibility**: Existing functionality unaffected
7. **Performance**: Minimal execution overhead

## Implementation Order

1. Create TokenCountable interface and TokenCounter base class
2. Update Pipeline to use TokenCounter
3. Add token counting to Splitter with parallel aggregation
4. Add token counting to Manifold with manager/worker tracking
5. Enhance TraceEvent and visualization for token display
6. Add comprehensive tests for all scenarios
7. Validate performance and thread safety
8. Update documentation and examples

This plan provides comprehensive token counting capabilities across all TPipe container classes while maintaining backward compatibility and ensuring thread-safe operation.
