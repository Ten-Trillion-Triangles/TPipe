# Advanced Pipeline Flow Control

## Table of Contents
- [Flow Control Overview](#flow-control-overview)
- [Pipe Jumping](#pipe-jumping)
- [Pipe Repeating](#pipe-repeating)
- [Pipeline Termination](#pipeline-termination)
- [Complex Flow Control Patterns](#complex-flow-control-patterns)
- [Flow Control Best Practices](#flow-control-best-practices)
- [Monitoring Flow Control](#monitoring-flow-control)

TPipe provides sophisticated flow control mechanisms that allow pipes to dynamically alter pipeline execution through jumping, repeating, and termination. These features enable complex conditional logic and adaptive processing workflows.

## Flow Control Overview

### The Problem
Linear pipeline execution is often insufficient for:
- Conditional processing based on content analysis
- Error handling and retry logic
- Multi-step reasoning that requires iteration
- Early termination when tasks are complete

### The Solution
TPipe provides flow control mechanisms:
- **Pipe Jumping**: Skip to specific pipes or jump forward/backward
- **Pipe Repeating**: Execute the same pipe multiple times
- **Pipeline Termination**: Exit the pipeline early (success or failure)

All flow control is managed through the `MultimodalContent` object that flows between pipes.

## Pipe Jumping

### Basic Pipe Jumping
```kotlin
// Inside a pipe's processing logic or validation function
content.jumpToPipe("error-handler")  // Jump to named pipe
content.jumpToPipe("quality-check")  // Jump to specific processing stage
```

**How it works**: The pipeline checks for jump instructions after each pipe execution and redirects to the specified pipe instead of continuing sequentially.

### Conditional Jumping Example
```kotlin
val analysisPipe = BedrockPipe()
    .setPipeName("content-analyzer")
    .setValidatorFunction { content ->
        val analysisResult = analyzeContent(content.text)
        
        when {
            analysisResult.hasErrors -> {
                content.jumpToPipe("error-handler")
                false  // Validation failed, trigger jump
            }
            analysisResult.needsEnhancement -> {
                content.jumpToPipe("enhancement-stage")
                false  // Jump to enhancement
            }
            else -> true  // Continue normal flow
        }
    }

val errorHandler = BedrockPipe()
    .setPipeName("error-handler")
    .setSystemPrompt("Fix the identified errors in the content.")

val enhancer = BedrockPipe()
    .setPipeName("enhancement-stage")
    .setSystemPrompt("Enhance the content based on analysis results.")

pipeline.add(analysisPipe)
    .add(errorHandler)
    .add(enhancer)
```

## Pipe Repeating

### Basic Pipe Repeating
```kotlin
// Inside a pipe's processing logic
content.repeat()  // Execute this pipe again with the current output
```

**How it works**: After pipe execution, the pipeline checks the `repeatPipe` flag and re-executes the same pipe with the updated content until the flag is cleared.

### Iterative Refinement Example
```kotlin
val refinementPipe = BedrockPipe()
    .setPipeName("iterative-refiner")
    .setSystemPrompt("""
        Refine the provided content. If the content quality score is below 8/10,
        improve it and indicate that another iteration is needed.
        If quality is 8/10 or higher, finalize the content.
    """)
    .setValidatorFunction { content ->
        val qualityScore = assessQuality(content.text)
        
        if (qualityScore < 8.0 && getIterationCount(content) < 5) {
            content.repeat()  // Try again with improved content
            incrementIterationCount(content)
        }
        
        true
    }
```

### Custom Reasoning Loop
```kotlin
val reasoningPipe = BedrockPipe()
    .setPipeName("reasoning-loop")
    .setSystemPrompt("""
        Think through this problem step by step.
        If you need more thinking time, indicate "CONTINUE_REASONING".
        If you have a final answer, provide it clearly.
    """)
    .setValidatorFunction { content ->
        if (content.text.contains("CONTINUE_REASONING") && getReasoningSteps(content) < 10) {
            content.repeat()  // Continue reasoning
            incrementReasoningSteps(content)
        }
        
        true
    }
```

## Pipeline Termination

### Terminate vs Pass Pipeline

TPipe provides two ways to exit a pipeline early with different semantics:

#### Pass Pipeline (Success Exit)
```kotlin
content.passPipeline = true  // Exit pipeline early as successful completion
```

**What this does**:
- Stops pipeline execution immediately
- Preserves current content as the final result
- Treats the early exit as successful completion
- Returns content to caller as valid result

**Use cases**: Task completed early, no further processing needed, successful shortcut

#### Terminate Pipeline (Error Exit)  
```kotlin
content.terminate()  // Exit pipeline due to error or failure
```

**What this does**:
- Stops pipeline execution immediately  
- Clears content (sets to empty)
- Treats the exit as an error/failure condition
- Returns empty content to indicate failure

**Use cases**: Unrecoverable errors, invalid input detected, processing cannot continue

### Termination Examples

#### Successful Early Completion
```kotlin
val taskCompletionChecker = BedrockPipe()
    .setPipeName("completion-checker")
    .setSystemPrompt("Determine if the task is complete or needs further processing.")
    .setValidatorFunction { content ->
        val isComplete = checkTaskCompletion(content.text)
        
        if (isComplete) {
            content.passPipeline = true  // Task done successfully, exit with current result
        }
        
        true
    }
```

#### Error Exit
```kotlin
val inputValidator = BedrockPipe()
    .setPipeName("input-validator")
    .setValidatorFunction { content ->
        if (isInvalidInput(content.text)) {
            content.terminate()  // Invalid input, exit as error
            return false
        }
        
        true
    }
```

### Automatic Termination
```kotlin
// Pipeline automatically terminates if content becomes empty
if (content.isEmpty()) {
    // Pipeline terminates automatically (treated as failure)
}

// Check termination status
if (content.shouldTerminate()) {
    // Pipeline will terminate after this pipe
    // This checks both terminate() and empty content conditions
}
```

## Complex Flow Control Patterns

### Multi-Stage Validation with Retry
```kotlin
val processor = BedrockPipe()
    .setPipeName("main-processor")
    .setValidatorFunction { content ->
        val validationResult = validateOutput(content.text)
        
        when {
            validationResult.isValid -> true  // Continue to next pipe
            validationResult.canRetry && getRetryCount(content) < 3 -> {
                content.repeat()  // Try processing again
                incrementRetryCount(content)
                false
            }
            validationResult.isCriticalError -> {
                content.terminate()  // Unrecoverable error, fail pipeline
                false
            }
            else -> {
                content.jumpToPipe("fallback-processor")  // Jump to fallback
                false
            }
        }
    }

val fallbackProcessor = BedrockPipe()
    .setPipeName("fallback-processor")
    .setSystemPrompt("Process using alternative approach.")
```

### Conditional Pipeline Routing
```kotlin
val routingPipe = BedrockPipe()
    .setPipeName("content-router")
    .setSystemPrompt("Analyze content type and determine processing path.")
    .setValidatorFunction { content ->
        val contentType = determineContentType(content.text)
        
        when (contentType) {
            ContentType.TECHNICAL -> content.jumpToPipe("technical-processor")
            ContentType.CREATIVE -> content.jumpToPipe("creative-processor")
            ContentType.ANALYTICAL -> content.jumpToPipe("analytical-processor")
            ContentType.COMPLETE -> {
                content.passPipeline = true  // Already complete, successful exit
            }
            ContentType.INVALID -> {
                content.terminate()  // Invalid content, fail pipeline
            }
        }
        
        false  // Always jump, terminate, or fail - never continue sequentially
    }
```

### Quality Assurance Loop
```kotlin
val generator = BedrockPipe()
    .setPipeName("content-generator")

val qualityChecker = BedrockPipe()
    .setPipeName("quality-checker")
    .setValidatorFunction { content ->
        val quality = assessQuality(content.text)
        val attempts = getAttemptCount(content)
        
        when {
            quality.isAcceptable -> true  // Continue to next stage
            attempts < 3 -> {
                content.jumpToPipe("content-generator")  // Jump back to regenerate
                incrementAttemptCount(content)
                false
            }
            quality.isUnrecoverable -> {
                content.terminate()  // Cannot be fixed, fail pipeline
                false
            }
            else -> {
                content.jumpToPipe("manual-review")  // Escalate to manual review
                false
            }
        }
    }

val manualReview = BedrockPipe()
    .setPipeName("manual-review")
```

## Flow Control Best Practices

### 1. Prevent Infinite Loops
```kotlin
// Always include iteration limits
val maxIterations = 5
var currentIteration = getIterationCount(content)

if (needsRepeat && currentIteration < maxIterations) {
    content.repeat()
    incrementIterationCount(content)
} else if (currentIteration >= maxIterations) {
    content.terminate()  // Too many attempts, fail
}
```

### 2. Clear Jump Targets
```kotlin
// Use descriptive pipe names for jumping
pipe.setPipeName("error-recovery-stage")
pipe.setPipeName("quality-enhancement")
pipe.setPipeName("fallback-processor")

// Jump to clear, descriptive targets
content.jumpToPipe("error-recovery-stage")
```

### 3. Meaningful Final Content
```kotlin
// Before successful early exit, ensure content is meaningful
content.text = "Analysis complete: ${results.summary}"
content.passPipeline = true

// Error exits will clear content automatically
content.terminate()  // Content becomes empty
```

## Monitoring Flow Control

### Tracing Flow Changes
```kotlin
pipeline.enableTracing()  // Captures all jumps, repeats, and terminations

// After execution
val traceReport = pipeline.getTraceReport()
// Shows complete execution path including flow control decisions

val failureAnalysis = pipeline.getFailureAnalysis()
// Provides details on any failures or early exits
```

Advanced flow control enables sophisticated pipeline behaviors that adapt dynamically to content and processing requirements, providing both successful shortcuts and graceful failure handling for complex AI workflows.

## Next Steps

Now that you understand dynamic pipeline control, learn about monitoring and troubleshooting:

**→ [Tracing and Debugging](tracing-and-debugging.md)** - Monitoring and troubleshooting
