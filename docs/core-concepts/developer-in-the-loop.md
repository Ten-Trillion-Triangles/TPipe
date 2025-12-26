# Developer-in-the-Loop Functions

## Table of Contents
- [Overview of DITL Functions](#overview-of-ditl-functions)
- [Execution Order](#execution-order)
- [Pre-Init Function](#pre-init-function)
- [Pre-Validation Function](#pre-validation-function)
- [Pre-Invoke Function](#pre-invoke-function)
- [Validator Function](#validator-function)
- [On-Failure Function](#on-failure-function)
- [Transformation Function - The Most Critical DITL Function](#transformation-function---the-most-critical-ditl-function)
- [Flow Control Methods](#flow-control-methods)

TPipe provides sophisticated developer-in-the-loop (DITL) capabilities through a series of intervention points in the pipe execution lifecycle. These functions allow developers to inject custom logic, validation, and error handling at critical stages of AI processing.

## Overview of DITL Functions

TPipe offers six key intervention points during pipe execution:

1. **Pre-Init Function**: Execute before any processing begins
2. **Pre-Validation Function**: Modify context after it's loaded but before AI call
3. **Pre-Invoke Function**: Final check before making the AI API call
4. **Transformation Function**: **Most Critical** - Transform AI output after generation
5. **Validator Function**: Validate AI output and control pipeline flow
6. **On-Failure Function**: Handle errors and provide recovery logic

## Execution Order

```
Input → Pre-Init → Context Loading → Pre-Validation → Pre-Invoke → AI Call → Transformation → Validator → On-Failure (if needed) → Output
```



## Pre-Init Function

**What it does**: Executes at the very beginning of pipe execution, before any context loading or processing begins. This is your first opportunity to modify or validate the input content.

**When to use**: Input sanitization, early validation checks, preprocessing raw input, or setting up metadata before any AI processing occurs.

```kotlin
pipe.setPreInitFunction { content ->
    // Clean and validate input before any processing
    if (content.text.isBlank()) {
        content.terminate()  // Stop processing empty input
        return@setPreInitFunction
    }
    
    // Sanitize and normalize input
    content.text = content.text.trim().replace(Regex("\\s+"), " ")
    content.metadata["originalLength"] = content.text.length.toString()
}
```

## Pre-Validation Function

**What it does**: Modifies the context window after it's been loaded but before the AI model is called. This function receives both the context window and the content, allowing you to filter, enhance, or completely replace the context based on the input.

**When to use**: Dynamic context selection, filtering irrelevant context, adding computed context elements, or customizing context based on input analysis.

```kotlin
pipe.setPreValidationFunction { contextWindow, content ->
    // Filter context to only include relevant entries
    val keywords = extractKeywords(content?.text ?: "")
    val filteredContext = ContextWindow()
    
    contextWindow.contextElements.forEach { element ->
        if (keywords.any { keyword -> element.contains(keyword, ignoreCase = true) }) {
            filteredContext.contextElements.add(element)
        }
    }
    
    filteredContext
}
```

## Pre-Invoke Function

**What it does**: Final checkpoint before making the AI API call. This function can conditionally skip the entire pipe by returning `true`, allowing you to provide alternative responses or route to different processing paths.

**When to use**: Caching mechanisms, conditional processing based on input complexity, early response generation for simple cases, or dynamic routing decisions.

```kotlin
pipe.setPreInvokeFunction { content ->
    // Check cache first to avoid unnecessary AI calls
    val cachedResult = responseCache.get(content.text.hashCode())
    if (cachedResult != null) {
        content.text = cachedResult
        return@setPreInvokeFunction true  // Skip AI processing
    }
    
    false  // Continue with AI call
}
```

## Validator Function

**What it does**: Validates the AI output and controls pipeline flow. Returns `true` to continue the pipeline, or `false` to trigger branch pipes or failure handling. Can also set flow control flags like `repeat()`, `jumpToPipe()`, or `terminate()`.

**When to use**: Quality control, format validation, retry logic, pipeline routing based on output quality, or early termination when tasks are complete.

```kotlin
pipe.setValidatorFunction { content ->
    val quality = assessOutputQuality(content.text)
    
    when {
        quality > 0.8 -> true  // High quality, continue pipeline
        quality > 0.5 -> {
            content.repeat()  // Medium quality, try again
            false
        }
        else -> {
            content.jumpToPipe("fallback-processor")  // Low quality, use fallback
            false
        }
    }
}
```

## On-Failure Function

**What it does**: Handles errors and failures when validation fails or exceptions occur during processing. Receives both the original input content and the processed content, allowing you to implement recovery strategies.

**When to use**: Error recovery, fallback content generation, logging failures, or providing graceful degradation when AI processing fails.

```kotlin
pipe.setOnFailure { originalContent, processedContent ->
    // Log the failure and provide fallback content
    logger.error("Processing failed for: ${originalContent.text.take(50)}")
    
    MultimodalContent(
        text = "I apologize, but I couldn't process your request: '${originalContent.text}'. Please try rephrasing."
    )
}
```

## Transformation Function - The Most Critical DITL Function

**What it does**: Processes and transforms the raw AI output immediately after generation, before any validation occurs. This is the **most important** DITL function because it's where you convert unstructured AI responses into structured, usable data.

**When to use**: Always - nearly every production pipe should have a transformation function. Use it for parsing JSON responses, extracting specific data from AI text, converting unstructured text to structured data, adding metadata, and preparing content for pipeline context sharing.


```kotlin
pipe.setTransformationFunction { content ->
    // Parse JSON response from AI and extract structured data
    val aiResponse = content.text
    val jsonMatch = extractJsonFromText(aiResponse)
    val parsedData = Json.decodeFromString<AnalysisResult>(jsonMatch)
    
    // Create transformed content with structured data
    val transformedContent = MultimodalContent()
    transformedContent.text = parsedData.summary
    transformedContent.metadata["confidence"] = parsedData.confidence.toString()
    
    // Store full result in context for next pipeline stage
    transformedContent.context.addContextElement("analysis_result", Json.encodeToString(parsedData))
    
    transformedContent
}
```

Developer-in-the-loop functions provide precise control over AI processing at key intervention points, enabling sophisticated validation, error handling, and adaptive processing logic that goes far beyond simple linear AI model calls.

## Flow Control Methods

Flow control methods can be called on the content object to adjust the execution direction of the pipeline:

### content.repeat()
Re-executes the current pipe with the updated content. Useful for iterative refinement or retry logic until desired quality is achieved.

### content.jumpToPipe("pipe-name")
Jumps to a specific named pipe in the pipeline, skipping intermediate pipes. Used for conditional routing and branching logic. Allows for jumping forward
or backwards. If the pipe jumps backwards, it will execute from where it's been jumped to and move forward one step at a time.

### content.terminate()
Immediately exits the pipeline and treats it as an error/failure. Content is cleared and empty result is returned.

### content.passPipeline = true
Immediately exits the pipeline and treats it as successful early completion. Current content is preserved and returned as the final result.

## Next Steps

Now that you understand code-based DITL functions, learn about AI-powered validation:

**→ [Developer-in-the-Loop Pipes](developer-in-the-loop-pipes.md)** - AI-powered validation and transformation
