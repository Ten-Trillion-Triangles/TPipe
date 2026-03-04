# ContextBank - Global Context Integration

> 💡 **Tip:** The **ContextBank** is your city's main water supply. It is a thread-safe, global Reservoir that persists across multiple pipelines and agents.


## Table of Contents
- [What is ContextBank?](#what-is-contextbank)
- [Core ContextBank Operations](#core-contextbank-operations)
- [Common Usage Patterns](#common-usage-patterns)
- [Integration with Pipes](#integration-with-pipes)
- [Advanced Usage Patterns](#advanced-usage-patterns)
- [Best Practices](#best-practices)

ContextBank is TPipe's global context management system that enables context sharing across pipes, pipelines, and even separate applications. It acts as a centralized repository where context can be stored, retrieved, and shared between different processing stages.

## What is ContextBank?

ContextBank is a singleton object that provides:
- **Global context storage**: Share context across multiple pipes and pipelines
- **Named context pages**: Store different contexts with unique keys
- **Thread-safe operations**: Mutex-protected operations for concurrent access
- **Context persistence**: Maintain context across processing sessions

```kotlin
object ContextBank {
    // Global context storage with named keys
    private var bank = mutableMapOf<String, ContextWindow>()
    
    // Currently active context window
    private var bankedContextWindow = ContextWindow()
}
```

## Core ContextBank Operations

### Storing Context
```kotlin
// Store context with a specific key
val contextWindow = ContextWindow()
contextWindow.contextElements.add("Important information to share")

// Thread-safe storage (recommended for pipes)
ContextBank.emplaceWithMutex("sessionData", contextWindow)

// Direct storage (use only in single-threaded scenarios)
ContextBank.emplace("sessionData", contextWindow)
```

### Retrieving Context
```kotlin
// Get context by key (returns copy by default for safety)
val retrievedContext = ContextBank.getContextFromBank("sessionData")

// Get direct reference (faster but less safe)
val directReference = ContextBank.getContextFromBank("sessionData", copy = false)

// Get the currently active banked context
val activeContext = ContextBank.getBankedContextWindow()
val safeCopy = ContextBank.copyBankedContextWindow()
```

### Managing Active Context
```kotlin
// Swap active context to a different stored context
ContextBank.swapBank("sessionData")

// Thread-safe swapping (recommended for concurrent use)
ContextBank.swapBankWithMutex("sessionData")

// Update the active context
ContextBank.updateBankedContext(newContextWindow)
ContextBank.updateBankedContextWithMutex(newContextWindow)
```

## Common Usage Patterns

### Pattern 1: Temporary Context Storage
```kotlin
// Store intermediate results for later use
suspend fun storeChapterContent(content: MultimodalContent): MultimodalContent {
    val chapterWindow = ContextWindow()
    chapterWindow.contextElements.add(content.text)
    
    // Store for use by other pipes in the pipeline
    ContextBank.emplaceWithMutex("prevChapter", chapterWindow)
    return content
}
```

### Pattern 2: Context Retrieval and Processing
```kotlin
// Retrieve stored context for validation or processing
suspend fun validateWithStoredContext(content: MultimodalContent): Boolean {
    val prevChapter = ContextBank.getContextFromBank("prevChapter")
    
    // Use stored context for validation logic
    if (prevChapter.contextElements.isNotEmpty()) {
        val previousContent = prevChapter.contextElements[0]
        return validateConsistency(content.text, previousContent)
    }
    
    return true
}
```

### Pattern 3: Context Merging and Updates
```kotlin
// Merge contexts and update global state
suspend fun mergeAndUpdateContext(content: MultimodalContent): MultimodalContent {
    val prevChapter = ContextBank.getContextFromBank("prevChapter")
    val mainBank = ContextBank.getContextFromBank("main")
    
    // Merge previous chapter into main context
    mainBank.merge(prevChapter)
    ContextBank.emplaceWithMutex("main", mainBank)
    
    return content
}
```

### Pattern 4: Context Repair and Correction
```kotlin
// Update stored context with corrected content
suspend fun repairStoredContext(content: MultimodalContent): MultimodalContent {
    val prevChapter = ContextBank.getContextFromBank("prevChapter")
    
    try {
        // Update existing content
        prevChapter.contextElements[0] = content.text
    } catch (e: Exception) {
        // Add new content if index doesn't exist
        prevChapter.contextElements.add(content.text)
    }
    
    // Store updated context back
    ContextBank.emplaceWithMutex("prevChapter", prevChapter)
    return content
}
```

## Integration with Pipes

### Transformation Functions
```kotlin
val pipe = BedrockPipe()
    .setTransformationFunction { content ->
        // Store results in global context for other pipes
        val resultWindow = ContextWindow()
        resultWindow.contextElements.add(content.text)
        
        // Use coroutine-safe storage
        runBlocking {
            ContextBank.emplaceWithMutex("analysisResults", resultWindow)
        }
        
        content
    }
```

### Validation Functions
```kotlin
val pipe = BedrockPipe()
    .setValidatorFunction { content ->
        // Retrieve global context for validation
        val storedContext = ContextBank.getContextFromBank("previousResults")
        
        if (storedContext.contextElements.isNotEmpty()) {
            val previousResult = storedContext.contextElements[0]
            return@setValidatorFunction isConsistentWith(content.text, previousResult)
        }
        
        true
    }
```

### Pipeline Context Sharing
```kotlin
// Pipeline 1: Analysis pipeline
val analysisPipeline = Pipeline()
    .add(BedrockPipe()
        .setTransformationFunction { content ->
            // Store analysis results globally
            val analysisWindow = ContextWindow()
            analysisWindow.addLoreBookEntry("analysis", content.text, weight = 10)
            
            runBlocking {
                ContextBank.emplaceWithMutex("analysisData", analysisWindow)
            }
            content
        }
    )

// Pipeline 2: Generation pipeline (runs later)
val generationPipeline = Pipeline()
    .add(BedrockPipe()
        .setPreValidationFunction { contextWindow, content ->
            // Retrieve analysis results from global context
            val analysisData = ContextBank.getContextFromBank("analysisData")
            
            // Merge analysis context into current context
            contextWindow.merge(analysisData)
            contextWindow
        }
    )
```

## Advanced Usage Patterns

### Multi-Stage Processing
```kotlin
// Stage 1: Initial processing
suspend fun initialProcessing(content: MultimodalContent): MultimodalContent {
    val processedWindow = ContextWindow()
    processedWindow.contextElements.add("Stage 1: ${content.text}")
    ContextBank.emplaceWithMutex("stage1Results", processedWindow)
    return content
}

// Stage 2: Enhanced processing using Stage 1 results
suspend fun enhancedProcessing(content: MultimodalContent): MultimodalContent {
    val stage1Results = ContextBank.getContextFromBank("stage1Results")
    val enhancedWindow = ContextWindow()
    
    // Combine current content with previous stage results
    enhancedWindow.contextElements.addAll(stage1Results.contextElements)
    enhancedWindow.contextElements.add("Stage 2: ${content.text}")
    
    ContextBank.emplaceWithMutex("finalResults", enhancedWindow)
    return content
}
```

### Session Management
```kotlin
// Start new session
fun startSession(sessionId: String) {
    val sessionContext = ContextWindow()
    sessionContext.contextElements.add("Session started: $sessionId")
    ContextBank.emplace("session$sessionId", sessionContext)
    ContextBank.swapBank("session$sessionId")
}

// Add to current session
suspend fun addToSession(content: String, sessionId: String) {
    val sessionContext = ContextBank.getContextFromBank("session$sessionId")
    sessionContext.contextElements.add(content)
    ContextBank.emplaceWithMutex("session$sessionId", sessionContext)
}

// Switch between sessions
fun switchSession(sessionId: String) {
    ContextBank.swapBank("session$sessionId")
}
```

### Error Recovery with Context
```kotlin
suspend fun processWithRecovery(content: MultimodalContent): MultimodalContent {
    try {
        // Store original content for recovery
        val backupWindow = ContextWindow()
        backupWindow.contextElements.add(content.text)
        ContextBank.emplaceWithMutex("backup", backupWindow)
        
        // Process content
        val result = processContent(content)
        return result
        
    } catch (e: Exception) {
        // Recover from backup if processing fails
        val backup = ContextBank.getContextFromBank("backup")
        return MultimodalContent(text = backup.contextElements.firstOrNull() ?: "Recovery failed")
    }
}
```

## Best Practices

### 1. Always Use Mutex Operations in Pipes
```kotlin
// Good: Thread-safe operations
ContextBank.emplaceWithMutex("key", contextWindow)
ContextBank.swapBankWithMutex("key")

// Avoid: Direct operations in concurrent environments
ContextBank.emplace("key", contextWindow)  // Only safe in single-threaded code
```

### 2. Use Descriptive Keys
```kotlin
// Good: Clear, descriptive keys
ContextBank.emplaceWithMutex("userSessionData", contextWindow)
ContextBank.emplaceWithMutex("analysisResultsStage1", contextWindow)

// Avoid: Generic or unclear keys
ContextBank.emplaceWithMutex("data", contextWindow)
ContextBank.emplaceWithMutex("temp", contextWindow)
```

### 3. Handle Missing Context Gracefully
```kotlin
val context = ContextBank.getContextFromBank("optionalData")
if (context.isEmpty()) {
    // Handle case where context doesn't exist
    initializeDefaultContext()
} else {
    // Use existing context
    processWithContext(context)
}
```

### 4. Clean Up When Appropriate
```kotlin
// Clear context when session ends
fun endSession() {
    ContextBank.clearBankedContext()
    // Or remove specific keys if needed
}
```

ContextBank enables sophisticated context sharing patterns that allow TPipe applications to maintain state, share information between processing stages, and coordinate complex multi-pipeline workflows.

## Next Steps

Now that you understand global context management, learn about organized context retrieval:

**→ [Page Keys and Global Context](page-keys-and-global-context.md)** - Organized context retrieval
