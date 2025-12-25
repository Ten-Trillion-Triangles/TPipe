# Pipeline Pause/Resume Implementation Plan

## Overview

This document provides a comprehensive implementation plan for adding pause/resume functionality to the TPipe Pipeline class, based on the existing Manifold implementation. The goal is to enable controlled suspension and resumption of pipeline execution at strategic points during pipe processing.

## Current State Analysis

### Pipeline Execution Model
- **Sequential Processing**: Executes pipes one by one using `while(currentPipeIndex < pipes.size)`
- **Async Support**: Uses coroutines with `async/await` for pipe execution
- **Flow Control**: Supports jump/skip operations via `getNextPipe()`
- **Single Cycle**: Linear execution from start to finish (unlike Manifold's continuous loop)

### Key Execution Points
1. Main execution loop in `executeMultimodal()`
2. Individual pipe execution with `pipe.execute(generatedContent)`
3. Repeat pipe logic for custom reasoning modes
4. Jump/skip operations via `getNextPipe()`
5. Pipeline completion callbacks

## Implementation Design

### 1. Core Components

#### State Management Properties
```kotlin
/**
 * Pause/resume state management for Pipeline execution control
 */
private var isPaused = false
private val resumeSignal = Channel<Unit>(Channel.RENDEZVOUS)

/**
 * Declarative pause point configuration - TPipe's declarative approach
 * Auto-enables pausing when any pause point is declared
 */
private var pauseBeforePipes = false
private var pauseAfterPipes = false
private var pauseBeforeJumps = false
private var pauseAfterRepeats = false
private var pauseOnCompletion = false
private var pausingEnabled = false  // Auto-set when any pause point is declared
```

### 2. API Methods

#### Declarative Pause Point Methods (TPipe Pattern)
```kotlin
/**
 * Declares that pipeline should pause before each pipe execution
 * Auto-enables pausing capability when declared
 */
fun pauseBeforePipes(): Pipeline {
    pauseBeforePipes = true
    pausingEnabled = true  // Auto-enable pausing
    return this
}

/**
 * Declares that pipeline should pause after each pipe execution
 */
fun pauseAfterPipes(): Pipeline {
    pauseAfterPipes = true
    pausingEnabled = true
    return this
}

/**
 * Declares that pipeline should pause before jump/skip operations
 */
fun pauseBeforeJumps(): Pipeline {
    pauseBeforeJumps = true
    pausingEnabled = true
    return this
}

/**
 * Declares that pipeline should pause after repeat pipe cycles
 */
fun pauseAfterRepeats(): Pipeline {
    pauseAfterRepeats = true
    pausingEnabled = true
    return this
}

/**
 * Declares that pipeline should pause when execution completes
 */
fun pauseOnCompletion(): Pipeline {
    pauseOnCompletion = true
    pausingEnabled = true
    return this
}

/**
 * Convenience method to enable common pause points
 */
fun enablePausePoints(): Pipeline {
    return pauseBeforePipes().pauseOnCompletion()
}
```

#### Core Pause/Resume Methods
```kotlin
/**
 * Pauses pipeline execution at the next declared pause point
 * Only works if pause points have been declared
 */
suspend fun pause() {
    if (!pausingEnabled) return  // No-op if no pause points declared
    
    isPaused = true
    resumeSignal.receive()
    isPaused = false
}

/**
 * Resumes paused pipeline execution
 */
suspend fun resume() {
    resumeSignal.trySend(Unit)
}

/**
 * Checks if pipeline is currently paused
 */
fun isPaused(): Boolean = isPaused

/**
 * Checks if pausing capability is enabled (any pause points declared)
 */
fun canPause(): Boolean = pausingEnabled
```

### 3. Integration Points

#### Primary Integration: Main Execution Loop
```kotlin
private suspend fun executeMultimodal(initialContent: MultimodalContent): MultimodalContent = coroutineScope {
    // ... existing initialization code ...
    
    while(currentPipeIndex < pipes.size) {
        // PAUSE POINT 1: Before pipe execution (if declared)
        if (pauseBeforePipes) {
            checkPausePoint()
        }
        
        val pipe = getNextPipe(generatedContent) ?: break
        generatedContent.clearJumpToPipe()
        
        // ... existing pipe setup code ...
        
        val result: Deferred<MultimodalContent> = async {
            pipe.execute(generatedContent)
        }
        
        generatedContent = result.await()
        
        // PAUSE POINT 2: After pipe execution (if declared)
        if (pauseAfterPipes) {
            checkPausePoint()
        }
        
        // ... existing converse history code ...
        
        // Handle repeat pipe logic with pause support
        while(generatedContent.repeatPipe) {
            var repeatPipeResult: Deferred<MultimodalContent> = async {
                pipe.execute(generatedContent)
            }
            
            generatedContent = repeatPipeResult.await()
            
            // PAUSE POINT 3: After repeat pipe (if declared)
            if (pauseAfterRepeats) {
                checkPausePoint()
            }
        }
        
        // ... existing termination/pass logic ...
        
        // PAUSE POINT 4: Before jump operations (if declared)
        if (pauseBeforeJumps && !generatedContent.getJumpToPipe().isEmpty()) {
            checkPausePoint()
        }
        
        pipeCompletionCallback?.invoke(pipe, generatedContent)
        currentPipeIndex++
    }
    
    // PAUSE POINT 5: On pipeline completion (if declared)
    if (pauseOnCompletion) {
        checkPausePoint()
    }
    
    // ... existing cleanup code ...
}
```

#### Helper Method
```kotlin
/**
 * Internal method to check and handle pause points
 * Only executes if pausing is enabled (auto-set by declaring pause points)
 */
private suspend fun checkPausePoint() {
    if (pausingEnabled && isPaused) {
        resumeSignal.receive()
        isPaused = false
    }
}
```

### 4. Advanced Features

#### Conditional Pause Points (Human-in-the-Loop Pattern)
```kotlin
/**
 * Declares conditional pausing using TPipe's human-in-the-loop pattern
 * Auto-enables pausing when declared
 * Takes suspend function to allow async decision logic
 */
fun pauseWhen(condition: suspend (Pipe, MultimodalContent) -> Boolean): Pipeline {
    conditionalPauseFunction = condition
    pausingEnabled = true  // Auto-enable pausing
    return this
}

private var conditionalPauseFunction: (suspend (Pipe, MultimodalContent) -> Boolean)? = null

private suspend fun checkConditionalPause(pipe: Pipe, content: MultimodalContent) {
    if (conditionalPauseFunction?.invoke(pipe, content) == true) {
        pause()
    }
}
```

#### Pause Callbacks (Human-in-the-Loop Integration)
```kotlin
/**
 * Declares callback for when pipeline pauses (human-in-the-loop pattern)
 */
fun onPause(callback: suspend (Pipe?, MultimodalContent) -> Unit): Pipeline {
    pauseCallback = callback
    return this
}

/**
 * Declares callback for when pipeline resumes (human-in-the-loop pattern)
 */
fun onResume(callback: suspend (Pipe?, MultimodalContent) -> Unit): Pipeline {
    resumeCallback = callback
    return this
}

private var pauseCallback: (suspend (Pipe?, MultimodalContent) -> Unit)? = null
private var resumeCallback: (suspend (Pipe?, MultimodalContent) -> Unit)? = null
```

### 5. Tracing Integration

#### Pause/Resume Events
```kotlin
// Add to TraceEventType enum
PIPELINE_PAUSE,
PIPELINE_RESUME,
PAUSE_POINT_CHECK,

// Tracing in pause/resume methods
private suspend fun pause() {
    if (tracingEnabled) {
        trace(TraceEventType.PIPELINE_PAUSE, TracePhase.ORCHESTRATION,
              metadata = mapOf("currentPipeIndex" to currentPipeIndex))
    }
    
    isPaused = true
    pauseCallback?.invoke(getCurrentPipe(), content)
    resumeSignal.receive()
    isPaused = false
    
    if (tracingEnabled) {
        trace(TraceEventType.PIPELINE_RESUME, TracePhase.ORCHESTRATION,
              metadata = mapOf("currentPipeIndex" to currentPipeIndex))
    }
    
    resumeCallback?.invoke(getCurrentPipe(), content)
}
```

## Implementation Steps

### Phase 1: Core Infrastructure
1. **Add pause/resume properties** to Pipeline class
2. **Implement basic pause/resume methods** using Channel synchronization
3. **Create PausePointConfig** data class
4. **Add configuration methods** for pause points

### Phase 2: Integration Points
1. **Modify executeMultimodal()** to include pause point checks
2. **Implement checkPausePoint()** helper method
3. **Add pause points** at strategic locations in execution flow
4. **Test basic pause/resume functionality**

### Phase 3: Advanced Features
1. **Add conditional pause support** with custom functions
2. **Implement pause/resume callbacks** for external notification
3. **Integrate with tracing system** for monitoring
4. **Add pipeline state inspection** methods

### Phase 4: Testing & Documentation
1. **Create unit tests** for all pause/resume scenarios
2. **Add integration tests** with real pipelines
3. **Update API documentation** with examples
4. **Create usage examples** and best practices guide

## Code Changes Required

### Pipeline.kt Modifications

#### Properties Section (around line 50)
```kotlin
/**
 * Pause/resume functionality using TPipe's declarative approach
 * Pausing auto-enabled when any pause point is declared
 */
private var isPaused = false
private val resumeSignal = Channel<Unit>(Channel.RENDEZVOUS)
private var pauseBeforePipes = false
private var pauseAfterPipes = false
private var pauseBeforeJumps = false
private var pauseAfterRepeats = false
private var pauseOnCompletion = false
private var pausingEnabled = false  // Auto-set when pause points declared
private var conditionalPauseFunction: (suspend (Pipe, MultimodalContent) -> Boolean)? = null
private var pauseCallback: (suspend (Pipe?, MultimodalContent) -> Unit)? = null
private var resumeCallback: (suspend (Pipe?, MultimodalContent) -> Unit)? = null
```

#### Constructor Section (around line 400)
```kotlin
/**
 * Declarative pause point methods following TPipe's pattern
 */
fun pauseBeforePipes(): Pipeline {
    pauseBeforePipes = true
    pausingEnabled = true
    return this
}

fun pauseAfterPipes(): Pipeline {
    pauseAfterPipes = true
    pausingEnabled = true
    return this
}

fun pauseBeforeJumps(): Pipeline {
    pauseBeforeJumps = true
    pausingEnabled = true
    return this
}

fun pauseAfterRepeats(): Pipeline {
    pauseAfterRepeats = true
    pausingEnabled = true
    return this
}

fun pauseOnCompletion(): Pipeline {
    pauseOnCompletion = true
    pausingEnabled = true
    return this
}

fun enablePausePoints(): Pipeline {
    return pauseBeforePipes().pauseOnCompletion()
}

fun pauseWhen(condition: suspend (Pipe, MultimodalContent) -> Boolean): Pipeline {
    conditionalPauseFunction = condition
    pausingEnabled = true
    return this
}

fun onPause(callback: suspend (Pipe?, MultimodalContent) -> Unit): Pipeline {
    pauseCallback = callback
    return this
}

fun onResume(callback: suspend (Pipe?, MultimodalContent) -> Unit): Pipeline {
    resumeCallback = callback
    return this
}

suspend fun pause() {
    if (!pausingEnabled) return  // No-op if no pause points declared
    
    if (tracingEnabled) {
        trace(TraceEventType.PIPELINE_PAUSE, TracePhase.ORCHESTRATION,
              metadata = mapOf("currentPipeIndex" to currentPipeIndex))
    }
    
    isPaused = true
    pauseCallback?.invoke(getCurrentPipe(), content)
    resumeSignal.receive()
    isPaused = false
    
    if (tracingEnabled) {
        trace(TraceEventType.PIPELINE_RESUME, TracePhase.ORCHESTRATION,
              metadata = mapOf("currentPipeIndex" to currentPipeIndex))
    }
    
    resumeCallback?.invoke(getCurrentPipe(), content)
}

suspend fun resume() {
    resumeSignal.trySend(Unit)
}

fun isPaused(): Boolean = isPaused

fun canPause(): Boolean = pausingEnabled
```

#### Helper Methods Section
```kotlin
private suspend fun checkPausePoint() {
    if (pausingEnabled && isPaused) {
        resumeSignal.receive()
        isPaused = false
    }
}

private fun getCurrentPipe(): Pipe? {
    return if (currentPipeIndex < pipes.size) pipes[currentPipeIndex] else null
}
```

### New Files Required

**No additional files needed** - The declarative approach eliminates the need for separate configuration classes, following TPipe's pattern of keeping everything in the main class with simple method chaining.

## Testing Strategy

### Unit Tests
1. **Basic pause/resume functionality**
2. **Pause point configuration**
3. **Conditional pause logic**
4. **Callback invocation**
5. **Tracing integration**

### Integration Tests
1. **Multi-pipe pipeline pause/resume**
2. **Jump/skip operations with pause**
3. **Repeat pipe scenarios**
4. **Error handling during pause**
5. **Concurrent pipeline execution**

### Performance Tests
1. **Overhead of pause point checks**
2. **Memory usage with Channel objects**
3. **Latency impact on pipeline execution**

## Usage Examples

### Basic Declarative Usage (TPipe Pattern)
```kotlin
val pipeline = Pipeline()
    .add(preprocessPipe)
    .add(mainProcessingPipe)
    .add(postprocessPipe)
    .pauseBeforePipes()        // Declare pause points
    .pauseOnCompletion()       // Auto-enables pausing

// Start pipeline in background
val job = launch {
    pipeline.execute(initialContent)
}

// Pause at next declared pause point
pipeline.pause()

// Resume after user input
getUserInput()
pipeline.resume()
```

### Advanced Declarative Usage
```kotlin
val pipeline = Pipeline()
    .add(dataPipe)
    .add(processingPipe)
    .add(validationPipe)
    .pauseAfterPipes()         // Pause after each pipe
    .pauseWhen { pipe, content ->
        // Suspend function allows async decision logic
        val validation = validateContentAsync(content)
        val userInput = getUserDecisionAsync("Pause at ${pipe.pipeName}?")
        validation.hasErrors || userInput
    }
    .onPause { pipe, content ->
        println("Paused at: ${pipe?.pipeName}")
        logPauseEvent(content)
    }
    .onResume { pipe, content ->
        println("Resuming from: ${pipe?.pipeName}")
    }
```

### Human-in-the-Loop Integration
```kotlin
val pipeline = Pipeline()
    .add(aiAnalysisPipe)
    .add(decisionPipe)
    .pauseAfterPipes()
    .onPause { pipe, content ->
        // Human-in-the-loop: Review AI decision
        val humanReview = getUserReview(content)
        content.addText("Human review: $humanReview")
    }
```

## TPipe Design Philosophy Integration

This implementation follows TPipe's core design principles:

### Declarative Configuration
- **Method Chaining**: `pipeline.pauseBeforePipes().pauseOnCompletion()`
- **Self-Documenting**: Method names clearly describe what they enable
- **Sensible Defaults**: Pausing auto-enabled when pause points are declared

### Human-in-the-Loop Integration
- **Pause Callbacks**: `onPause()` and `onResume()` for complex behavior
- **Conditional Pausing**: `pauseWhen()` for dynamic pause logic
- **Content Manipulation**: Callbacks receive content for modification

### Auto-Configuration
- **No Boilerplate**: No configuration objects or manual setup required
- **Smart Defaults**: `pausingEnabled` automatically set when pause points declared
- **Fail-Safe**: `pause()` is no-op if no pause points declared

### Example of TPipe's Declarative Pattern
```kotlin
// Describes WHAT the pipeline does, not HOW
val pipeline = Pipeline()
    .add(inputPipe)
    .add(processingPipe)
    .add(outputPipe)
    .useGlobalContext()        // Existing TPipe pattern
    .autoTruncateContext()     // Existing TPipe pattern
    .pauseBeforePipes()        // New pause pattern
    .pauseOnCompletion()       // New pause pattern
    .onPause { pipe, content -> // Human-in-the-loop
        handleUserInteraction(content)
    }
```

This maintains TPipe's philosophy where **configuration describes intent** and **human-in-the-loop functions provide the complex logic**.

## Considerations & Limitations
- Channel-based synchronization ensures thread safety
- Multiple coroutines can safely call pause/resume
- No additional locking mechanisms required

### Performance Impact
- Minimal overhead from pause point checks
- Channel operations are lightweight
- No impact when pause functionality is disabled

### Compatibility
- Maintains backward compatibility with existing Pipeline API
- Optional feature that doesn't affect existing code
- Integrates seamlessly with tracing and P2P systems

### Error Handling
- Graceful handling of pause/resume during pipe failures
- Proper cleanup of Channel resources
- Timeout mechanisms for long-running pauses

## Future Enhancements

1. **Timeout Support**: Automatic resume after specified duration
2. **Pause Scheduling**: Time-based or condition-based pause scheduling
3. **State Persistence**: Save/restore pipeline state across sessions
4. **UI Integration**: Visual indicators for pause/resume state
5. **Distributed Pause**: Coordinate pause/resume across multiple pipelines

---

This implementation plan provides a comprehensive approach to adding pause/resume functionality to the Pipeline class while maintaining compatibility with existing TPipe architecture and following established patterns from the Manifold implementation.
