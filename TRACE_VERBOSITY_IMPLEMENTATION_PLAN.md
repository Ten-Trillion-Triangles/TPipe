# TPipe Trace Verbosity Implementation Plan

## Overview
The TraceDetailLevel verbosity setting is currently defined but not implemented. This plan outlines the exact changes needed to make verbosity filtering functional.

## Current State
- `TraceDetailLevel` enum exists with 4 levels (MINIMAL, NORMAL, VERBOSE, DEBUG)
- Configuration system stores `detailLevel` but doesn't use it
- All events are traced regardless of verbosity setting
- No filtering logic exists in the tracing pipeline

## Verbosity Level Definitions

### MINIMAL
- Only critical failures and pipeline termination
- Events: `PIPE_FAILURE`, `API_CALL_FAILURE`, `PIPELINE_TERMINATION`
- No metadata or context included

### NORMAL (Default)
- Standard pipeline flow and major operations
- Events: All MINIMAL events plus `PIPE_START`, `PIPE_SUCCESS`, `API_CALL_START`, `API_CALL_SUCCESS`
- Basic metadata only (model, provider, error messages)

### VERBOSE
- Detailed operation tracking including validation and transformation
- Events: All NORMAL events plus `VALIDATION_START`, `VALIDATION_SUCCESS`, `VALIDATION_FAILURE`, `TRANSFORMATION_START`, `TRANSFORMATION_SUCCESS`, `TRANSFORMATION_FAILURE`, `CONTEXT_PULL`, `CONTEXT_TRUNCATE`
- Full metadata and context snapshots

### DEBUG
- Everything including internal state and branch operations
- Events: All events (complete TraceEventType enum)
- Full metadata, context, and enhanced debugging information
- **Model reasoning visibility**: Raw reasoning content from models that support it (Claude, GPT-OSS, etc.)

## Implementation Changes

### 1. Create Event Priority Classification
**File**: `TPipe/src/main/kotlin/Debug/TraceEventPriority.kt` (NEW FILE)

```kotlin
package com.TTT.Debug

enum class TraceEventPriority {
    CRITICAL,    // MINIMAL level events
    STANDARD,    // NORMAL level events  
    DETAILED,    // VERBOSE level events
    INTERNAL     // DEBUG level events
}

object EventPriorityMapper {
    fun getPriority(eventType: TraceEventType): TraceEventPriority {
        return when (eventType) {
            TraceEventType.PIPE_FAILURE,
            TraceEventType.API_CALL_FAILURE,
            TraceEventType.PIPELINE_TERMINATION -> TraceEventPriority.CRITICAL
            
            TraceEventType.PIPE_START,
            TraceEventType.PIPE_SUCCESS,
            TraceEventType.API_CALL_START,
            TraceEventType.API_CALL_SUCCESS -> TraceEventPriority.STANDARD
            
            TraceEventType.VALIDATION_START,
            TraceEventType.VALIDATION_SUCCESS,
            TraceEventType.VALIDATION_FAILURE,
            TraceEventType.TRANSFORMATION_START,
            TraceEventType.TRANSFORMATION_SUCCESS,
            TraceEventType.TRANSFORMATION_FAILURE,
            TraceEventType.CONTEXT_PULL,
            TraceEventType.CONTEXT_TRUNCATE -> TraceEventPriority.DETAILED
            
            TraceEventType.PIPE_END,
            TraceEventType.BRANCH_PIPE_TRIGGERED -> TraceEventPriority.INTERNAL
        }
    }
    
    fun shouldTrace(eventType: TraceEventType, detailLevel: TraceDetailLevel): Boolean {
        val priority = getPriority(eventType)
        return when (detailLevel) {
            TraceDetailLevel.MINIMAL -> priority == TraceEventPriority.CRITICAL
            TraceDetailLevel.NORMAL -> priority in listOf(TraceEventPriority.CRITICAL, TraceEventPriority.STANDARD)
            TraceDetailLevel.VERBOSE -> priority in listOf(TraceEventPriority.CRITICAL, TraceEventPriority.STANDARD, TraceEventPriority.DETAILED)
            TraceDetailLevel.DEBUG -> true // All events
        }
    }
}
```

### 2. Modify Pipe.trace() Method
**File**: `TPipe/src/main/kotlin/Pipe/Pipe.kt` (MODIFY EXISTING)

**Change the trace() method from:**
```kotlin
protected fun trace(eventType: TraceEventType, phase: TracePhase, content: MultimodalContent? = null, metadata: Map<String, Any> = emptyMap(), error: Throwable? = null)
{
    if (!tracingEnabled) return
```

**To:**
```kotlin
protected fun trace(eventType: TraceEventType, phase: TracePhase, content: MultimodalContent? = null, metadata: Map<String, Any> = emptyMap(), error: Throwable? = null)
{
    if (!tracingEnabled) return
    
    // Check if this event should be traced based on detail level
    if (!EventPriorityMapper.shouldTrace(eventType, traceConfig.detailLevel)) return
```

**Add import at top of file:**
```kotlin
import com.TTT.Debug.EventPriorityMapper
```

### 3. Implement Metadata Filtering
**File**: `TPipe/src/main/kotlin/Pipe/Pipe.kt` (MODIFY EXISTING)

**Replace the metadata section in trace() method:**
```kotlin
// Enhanced metadata with bound function information
val enhancedMetadata = metadata.toMutableMap().apply {
    // Add pipe class and model information
    put("pipeClass", this@Pipe::class.qualifiedName ?: "UnknownPipe")
    put("model", model.ifEmpty { "not_set" })
    put("provider", provider.name)
    
    // Add bound function names for better debugging
    put("validatorFunction", validatorFunction?.toString() ?: "null")
    put("transformationFunction", transformationFunction?.toString() ?: "null")
    put("preValidationFunction", preValidationFunction?.toString() ?: "null")
    put("onFailure", onFailure?.toString() ?: "null")
    put("validatorPipe", if (validatorPipe != null) validatorPipe!!::class.simpleName ?: "UnknownPipe" else "null")
    put("transformationPipe", if (transformationPipe != null) transformationPipe!!::class.simpleName ?: "UnknownPipe" else "null")
    put("branchPipe", if (branchPipe != null) branchPipe!!::class.simpleName ?: "UnknownPipe" else "null")
}
```

**With:**
```kotlin
// Build metadata based on detail level
val enhancedMetadata = buildMetadataForLevel(metadata, traceConfig.detailLevel, eventType, error)
```

**Add new method to Pipe class:**
```kotlin
private fun buildMetadataForLevel(
    baseMetadata: Map<String, Any>, 
    detailLevel: TraceDetailLevel, 
    eventType: TraceEventType,
    error: Throwable?
): Map<String, Any> {
    val metadata = baseMetadata.toMutableMap()
    
    when (detailLevel) {
        TraceDetailLevel.MINIMAL -> {
            // Only error information for failures
            if (error != null) {
                metadata["error"] = error.message ?: "Unknown error"
            }
        }
        
        TraceDetailLevel.NORMAL -> {
            // Basic pipe information
            metadata["model"] = model.ifEmpty { "not_set" }
            metadata["provider"] = provider.name
            if (error != null) {
                metadata["error"] = error.message ?: "Unknown error"
                metadata["errorType"] = error::class.simpleName ?: "Unknown"
            }
        }
        
        TraceDetailLevel.VERBOSE -> {
            // Standard metadata plus function bindings
            metadata["pipeClass"] = this::class.qualifiedName ?: "UnknownPipe"
            metadata["model"] = model.ifEmpty { "not_set" }
            metadata["provider"] = provider.name
            metadata["hasValidatorFunction"] = (validatorFunction != null).toString()
            metadata["hasTransformationFunction"] = (transformationFunction != null).toString()
            metadata["hasPreValidationFunction"] = (preValidationFunction != null).toString()
            if (error != null) {
                metadata["error"] = error.message ?: "Unknown error"
                metadata["errorType"] = error::class.simpleName ?: "Unknown"
            }
        }
        
        TraceDetailLevel.DEBUG -> {
            // Everything including function details and model reasoning
            metadata["pipeClass"] = this::class.qualifiedName ?: "UnknownPipe"
            metadata["model"] = model.ifEmpty { "not_set" }
            metadata["provider"] = provider.name
            metadata["useModelReasoning"] = useModelReasoning.toString()
            metadata["validatorFunction"] = validatorFunction?.toString() ?: "null"
            metadata["transformationFunction"] = transformationFunction?.toString() ?: "null"
            metadata["preValidationFunction"] = preValidationFunction?.toString() ?: "null"
            metadata["onFailure"] = onFailure?.toString() ?: "null"
            metadata["validatorPipe"] = if (validatorPipe != null) validatorPipe!!::class.simpleName ?: "UnknownPipe" else "null"
            metadata["transformationPipe"] = if (transformationPipe != null) transformationPipe!!::class.simpleName ?: "UnknownPipe" else "null"
            metadata["branchPipe"] = if (branchPipe != null) branchPipe!!::class.simpleName ?: "UnknownPipe" else "null"
            if (error != null) {
                metadata["error"] = error.message ?: "Unknown error"
                metadata["errorType"] = error::class.simpleName ?: "Unknown"
                metadata["stackTrace"] = error.stackTraceToString()
            }
        }
    }
    
    return metadata
}
```

### 4. Implement Context Filtering
**File**: `TPipe/src/main/kotlin/Pipe/Pipe.kt` (MODIFY EXISTING)

**Replace the TraceEvent creation:**
```kotlin
val event = TraceEvent(
    timestamp = System.currentTimeMillis(),
    pipeId = pipeId,
    pipeName = if (pipeName.isNotEmpty()) pipeName else (this::class.simpleName ?: "UnknownPipe"),
    eventType = eventType,
    phase = phase,
    content = if (traceConfig.includeContext) content else null,
    contextSnapshot = if (traceConfig.includeContext) contextWindow else null,
    metadata = if (traceConfig.includeMetadata) enhancedMetadata else emptyMap(),
    error = error
)
```

**With:**
```kotlin
val event = TraceEvent(
    timestamp = System.currentTimeMillis(),
    pipeId = pipeId,
    pipeName = if (pipeName.isNotEmpty()) pipeName else (this::class.simpleName ?: "UnknownPipe"),
    eventType = eventType,
    phase = phase,
    content = if (shouldIncludeContent(traceConfig.detailLevel)) content else null,
    contextSnapshot = if (shouldIncludeContext(traceConfig.detailLevel)) contextWindow else null,
    metadata = if (traceConfig.includeMetadata) enhancedMetadata else emptyMap(),
    error = error
)
```

**Add helper methods to Pipe class:**
```kotlin
private fun shouldIncludeContent(detailLevel: TraceDetailLevel): Boolean {
    return when (detailLevel) {
        TraceDetailLevel.MINIMAL -> false
        TraceDetailLevel.NORMAL -> false
        TraceDetailLevel.VERBOSE -> traceConfig.includeContext
        TraceDetailLevel.DEBUG -> traceConfig.includeContext
    }
}

private fun shouldIncludeContext(detailLevel: TraceDetailLevel): Boolean {
    return when (detailLevel) {
        TraceDetailLevel.MINIMAL -> false
        TraceDetailLevel.NORMAL -> false
        TraceDetailLevel.VERBOSE -> traceConfig.includeContext
        TraceDetailLevel.DEBUG -> traceConfig.includeContext
    }
}
```

### 5. Update Provider-Specific Pipes
**Files**: All provider pipes (BedrockPipe.kt, OllamaPipe.kt, etc.) (MODIFY EXISTING)

**Add import to each provider pipe:**
```kotlin
import com.TTT.Debug.EventPriorityMapper
```

#### 5.1 BedrockPipe Model Reasoning Support
**File**: `TPipe-Bedrock/src/main/kotlin/bedrockPipe/BedrockPipe.kt` (MODIFY EXISTING)

**Add reasoning tracing to generateText() method after response extraction:**
```kotlin
// Extract text using model-specific response parsing
val extractedText = extractTextFromResponse(responseBody, modelId)

// DEBUG level: Trace raw reasoning content for supported models
if (tracingEnabled && traceConfig.detailLevel == TraceDetailLevel.DEBUG) {
    val reasoningContent = extractReasoningContent(responseBody, modelId)
    if (reasoningContent.isNotEmpty()) {
        trace(TraceEventType.API_CALL_SUCCESS, TracePhase.EXECUTION,
              metadata = mapOf(
                  "reasoningContent" to reasoningContent,
                  "modelSupportsReasoning" to true,
                  "reasoningEnabled" to useModelReasoning
              ))
    }
}
```

**Add new method to BedrockPipe:**
```kotlin
protected fun extractReasoningContent(responseBody: String, modelId: String): String {
    return try {
        val json = Json.parseToJsonElement(responseBody).jsonObject
        when {
            modelId.contains("openai.gpt-oss") -> {
                // Extract reasoning from GPT-OSS response
                json["choices"]?.jsonArray?.firstOrNull()?.jsonObject
                    ?.get("message")?.jsonObject
                    ?.get("reasoning")?.jsonPrimitive?.content ?: ""
            }
            modelId.contains("anthropic.claude") -> {
                // Extract thinking/reasoning from Claude response if present
                json["content"]?.jsonArray?.mapNotNull { contentItem ->
                    val contentObj = contentItem.jsonObject
                    when {
                        contentObj.containsKey("type") && 
                        contentObj["type"]?.jsonPrimitive?.content == "thinking" -> 
                            contentObj["text"]?.jsonPrimitive?.content
                        else -> null
                    }
                }?.joinToString("\n") ?: ""
            }
            modelId.contains("deepseek") -> {
                // Extract reasoning from DeepSeek response
                if (useConverseApi) {
                    val message = json["output"]?.jsonObject?.get("message")?.jsonObject
                    val content = message?.get("content")?.jsonArray
                    content?.mapNotNull { contentItem ->
                        val contentObj = contentItem.jsonObject
                        if (contentObj.containsKey("reasoningContent")) {
                            contentObj["reasoningContent"]?.jsonObject
                                ?.get("reasoningText")?.jsonPrimitive?.content
                        } else null
                    }?.joinToString("\n") ?: ""
                } else {
                    json["reasoning"]?.jsonPrimitive?.content ?: ""
                }
            }
            else -> ""
        }
    } catch (e: Exception) {
        ""
    }
}
```

#### 5.2 OllamaPipe Model Reasoning Support
**File**: `TPipe-Ollama/src/main/kotlin/ollamaPipe/OllamaPipe.kt` (MODIFY EXISTING)

**Add reasoning tracing to generateText() method:**
```kotlin
// Parse response and extract text
val result = parseOllamaResponse(response)

// DEBUG level: Trace reasoning if model supports it
if (tracingEnabled && traceConfig.detailLevel == TraceDetailLevel.DEBUG) {
    val reasoningContent = extractOllamaReasoning(response, model)
    if (reasoningContent.isNotEmpty()) {
        trace(TraceEventType.API_CALL_SUCCESS, TracePhase.EXECUTION,
              metadata = mapOf(
                  "reasoningContent" to reasoningContent,
                  "modelSupportsReasoning" to modelSupportsReasoning(model)
              ))
    }
}
```

**Add new methods to OllamaPipe:**
```kotlin
protected fun extractOllamaReasoning(response: String, modelName: String): String {
    return try {
        val json = Json.parseToJsonElement(response).jsonObject
        // Extract reasoning/thinking content if present in Ollama response
        json["reasoning"]?.jsonPrimitive?.content ?: 
        json["thinking"]?.jsonPrimitive?.content ?: 
        json["chain_of_thought"]?.jsonPrimitive?.content ?: ""
    } catch (e: Exception) {
        ""
    }
}

protected fun modelSupportsReasoning(modelName: String): Boolean {
    return modelName.lowercase().contains("reasoning") || 
           modelName.lowercase().contains("cot") ||
           modelName.lowercase().contains("think")
}
```

### 6. Update Unit Tests for Reasoning
**File**: `TPipe/src/test/kotlin/Debug/TraceVerbosityTest.kt` (MODIFY EXISTING)

**Add reasoning test:**
```kotlin
@Test
fun testDebugLevelReasoningCapture() {
    // Test that DEBUG level captures reasoning content
    assertTrue(EventPriorityMapper.shouldTrace(TraceEventType.API_CALL_SUCCESS, TraceDetailLevel.DEBUG))
    
    // Verify reasoning metadata is included at DEBUG level
    val debugMetadata = mapOf(
        "reasoningContent" to "Step 1: Analyze the problem...",
        "modelSupportsReasoning" to true
    )
    
    assertNotNull(debugMetadata["reasoningContent"])
    assertTrue(debugMetadata["modelSupportsReasoning"] as Boolean)
}
```

### 7. Create Unit Tests
**File**: `TPipe/src/test/kotlin/Debug/TraceVerbosityTest.kt` (NEW FILE)

```kotlin
package com.TTT.Debug

import com.TTT.Pipe.MultimodalContent
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Assertions.*

class TraceVerbosityTest {
    
    @BeforeEach
    fun setup() {
        PipeTracer.enable()
    }
    
    @Test
    fun testMinimalLevelFiltering() {
        val pipelineId = "test-minimal"
        PipeTracer.startTrace(pipelineId)
        
        // Should be traced (CRITICAL)
        val failureEvent = TraceEvent(
            timestamp = System.currentTimeMillis(),
            pipeId = "test-pipe",
            pipeName = "TestPipe",
            eventType = TraceEventType.PIPE_FAILURE,
            phase = TracePhase.EXECUTION,
            content = null,
            contextSnapshot = null
        )
        
        // Should NOT be traced (STANDARD)
        val startEvent = TraceEvent(
            timestamp = System.currentTimeMillis(),
            pipeId = "test-pipe",
            pipeName = "TestPipe",
            eventType = TraceEventType.PIPE_START,
            phase = TracePhase.INITIALIZATION,
            content = null,
            contextSnapshot = null
        )
        
        assertTrue(EventPriorityMapper.shouldTrace(TraceEventType.PIPE_FAILURE, TraceDetailLevel.MINIMAL))
        assertFalse(EventPriorityMapper.shouldTrace(TraceEventType.PIPE_START, TraceDetailLevel.MINIMAL))
    }
    
    @Test
    fun testEventPriorityMapping() {
        assertEquals(TraceEventPriority.CRITICAL, EventPriorityMapper.getPriority(TraceEventType.PIPE_FAILURE))
        assertEquals(TraceEventPriority.STANDARD, EventPriorityMapper.getPriority(TraceEventType.PIPE_START))
        assertEquals(TraceEventPriority.DETAILED, EventPriorityMapper.getPriority(TraceEventType.VALIDATION_START))
        assertEquals(TraceEventPriority.INTERNAL, EventPriorityMapper.getPriority(TraceEventType.BRANCH_PIPE_TRIGGERED))
    }
    
    @Test
    fun testVerbosityLevelInclusion() {
        // MINIMAL should only include CRITICAL
        assertTrue(EventPriorityMapper.shouldTrace(TraceEventType.PIPE_FAILURE, TraceDetailLevel.MINIMAL))
        assertFalse(EventPriorityMapper.shouldTrace(TraceEventType.PIPE_START, TraceDetailLevel.MINIMAL))
        
        // NORMAL should include CRITICAL + STANDARD
        assertTrue(EventPriorityMapper.shouldTrace(TraceEventType.PIPE_FAILURE, TraceDetailLevel.NORMAL))
        assertTrue(EventPriorityMapper.shouldTrace(TraceEventType.PIPE_START, TraceDetailLevel.NORMAL))
        assertFalse(EventPriorityMapper.shouldTrace(TraceEventType.VALIDATION_START, TraceDetailLevel.NORMAL))
        
        // VERBOSE should include CRITICAL + STANDARD + DETAILED
        assertTrue(EventPriorityMapper.shouldTrace(TraceEventType.PIPE_FAILURE, TraceDetailLevel.VERBOSE))
        assertTrue(EventPriorityMapper.shouldTrace(TraceEventType.PIPE_START, TraceDetailLevel.VERBOSE))
        assertTrue(EventPriorityMapper.shouldTrace(TraceEventType.VALIDATION_START, TraceDetailLevel.VERBOSE))
        assertFalse(EventPriorityMapper.shouldTrace(TraceEventType.BRANCH_PIPE_TRIGGERED, TraceDetailLevel.VERBOSE))
        
        // DEBUG should include everything
        assertTrue(EventPriorityMapper.shouldTrace(TraceEventType.PIPE_FAILURE, TraceDetailLevel.DEBUG))
        assertTrue(EventPriorityMapper.shouldTrace(TraceEventType.PIPE_START, TraceDetailLevel.DEBUG))
        assertTrue(EventPriorityMapper.shouldTrace(TraceEventType.VALIDATION_START, TraceDetailLevel.DEBUG))
        assertTrue(EventPriorityMapper.shouldTrace(TraceEventType.BRANCH_PIPE_TRIGGERED, TraceDetailLevel.DEBUG))
    }
}
```

### 7. Update Documentation
**File**: `TPipe/src/main/kotlin/Debug/TracingExample.kt` (MODIFY EXISTING)

**Add example demonstrating verbosity levels:**
```kotlin
private suspend fun demonstrateVerbosityLevels() {
    println("\n=== Verbosity Levels Example ===")
    
    // MINIMAL - Only failures
    val minimalConfig = TracingBuilder()
        .enabled()
        .detailLevel(TraceDetailLevel.MINIMAL)
        .outputFormat(TraceFormat.CONSOLE)
        .build()
    
    // VERBOSE - Detailed tracking
    val verboseConfig = TracingBuilder()
        .enabled()
        .detailLevel(TraceDetailLevel.VERBOSE)
        .outputFormat(TraceFormat.CONSOLE)
        .includeContext(true)
        .includeMetadata(true)
        .build()
    
    println("Minimal tracing captures only critical failures")
    println("Verbose tracing captures validation, transformation, and context operations")
}
```

## Implementation Priority

### Phase 1 (Critical)
1. Create `TraceEventPriority.kt` with event classification
2. Modify `Pipe.trace()` method with filtering logic
3. Add metadata and context filtering methods

### Phase 2 (Important)
1. Create comprehensive unit tests
2. Update documentation and examples
3. Test with all provider pipes

### Phase 3 (Validation)
1. Integration testing with real pipelines
2. Performance impact assessment
3. Documentation updates

## Expected Behavior After Implementation

### MINIMAL Level
- Only traces failures and termination
- Minimal metadata (error messages only)
- No context or content included
- Fastest performance, smallest trace files

### NORMAL Level (Default)
- Traces pipeline flow and API calls
- Basic metadata (model, provider, errors)
- No context snapshots
- Balanced performance and information

### VERBOSE Level
- Detailed operation tracking
- Full metadata and context snapshots
- Validation and transformation events
- Comprehensive debugging information

### DEBUG Level
- Everything including internal operations
- Full function binding details
- Stack traces for errors
- **Model reasoning content**: Raw reasoning/thinking from Claude, GPT-OSS, DeepSeek, and other reasoning-capable models
- Maximum information, highest overhead

## File Summary

### New Files Created
- `TPipe/src/main/kotlin/Debug/TraceEventPriority.kt`
- `TPipe/src/test/kotlin/Debug/TraceVerbosityTest.kt`

### Files Modified
- `TPipe/src/main/kotlin/Pipe/Pipe.kt` (major changes to trace() method)
- `TPipe/src/main/kotlin/Debug/TracingExample.kt` (add verbosity examples)
- `TPipe-Bedrock/src/main/kotlin/bedrockPipe/BedrockPipe.kt` (add reasoning extraction)
- `TPipe-Ollama/src/main/kotlin/ollamaPipe/OllamaPipe.kt` (add reasoning extraction)
- Other provider pipe files (add import only)

### Files Unchanged
- `TraceDetailLevel.kt` (already correctly defined)
- `TraceConfig.kt` (already stores detailLevel)
- `TracingBuilder.kt` (already has detailLevel method)
- `PipeTracer.kt` (no changes needed)

This implementation will make the verbosity setting fully functional with proper event filtering, metadata control, and performance optimization based on the selected detail level.