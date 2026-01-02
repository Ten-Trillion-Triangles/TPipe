# Pipeline Context Integration

## Table of Contents
- [What is Pipeline Context?](#what-is-pipeline-context)
- [Context Sources](#context-sources)
- [Pulling Pipeline Context](#pulling-pipeline-context)
- [Updating Pipeline Context](#updating-pipeline-context)
- [Context Flow Patterns](#context-flow-patterns)
- [Advanced Pipeline Context Patterns](#advanced-pipeline-context-patterns)
- [Practical Examples](#practical-examples)
- [Best Practices](#best-practices)
- [MiniBank Integration with Pipeline Context](#minibank-integration-with-pipeline-context)
- [Context Merge Settings](#context-merge-settings)
- [Pipeline Global Context Updates](#pipeline-global-context-updates)

TPipe enables pipes to share context within a pipeline through the pipeline's shared context window. This allows pipes to build upon each other's results and maintain context continuity throughout multi-stage processing.

## What is Pipeline Context?

Pipeline context is a shared ContextWindow that belongs to the pipeline itself:

```kotlin
class Pipeline {
    var context = ContextWindow()  // Shared across all pipes in pipeline
}
```

**Purpose**:
- **Context continuity**: Maintain context across pipeline stages
- **Result sharing**: Pass processed information between pipes
- **Cumulative learning**: Build context as pipeline progresses
- **Stage coordination**: Enable pipes to work together on complex tasks


### Pipeline Context
```kotlin
val pipe = BedrockPipe()
    .pullPipelineContext()  // Pulls from parent pipeline
```

**Characteristics**:
- Shared only within the current pipeline
- Exists for the duration of pipeline execution
- Accumulates results from previous pipes
- Good for stage-to-stage data flow

## Context Sources

TPipe supports multiple context sources that can be used together:

- **Pipeline context** - `pullPipelineContext()` - Shared context within the current pipeline
- **Global context** - `pullGlobalContext()` - Persistent context from ContextBank
- **Page-specific context** - `setPageKey()` - Organized global context retrieval

### Non-Exclusive Context Behavior

Both global and pipeline context can be enabled simultaneously. When both are active:

- **Pipeline context** provides the base context from previous pipeline stages
- **Global context** is merged with pipeline context, adding additional information
- **Page keys** organize global context into specific sections

```kotlin
val pipe = BedrockPipe()
    .pullGlobalContext()      // Pulls from ContextBank
    .setPageKey("userData")   // Specific global context page
    .pullPipelineContext()    // Also pulls pipeline context - both are used together
```

## Pulling Pipeline Context

### Basic Pipeline Context Access
```kotlin
val pipe = BedrockPipe()
    .pullPipelineContext()  // Enable pipeline context retrieval
    .autoInjectContext("Use the pipeline context data from previous processing stages.")
```

**What this does**:
- Retrieves the current pipeline's context window
- Includes all context accumulated by previous pipes
- Provides access to results from earlier pipeline stages

### Pipeline Context with Processing
```kotlin
val analysisPipe = BedrockPipe()
    .setSystemPrompt("Analyze the input and extract key information.")
    .setTransformationFunction { content ->
        // Store analysis results in pipe's context
        content.context.addLoreBookEntry("analysis", content.text, weight = 10)
        content.context.contextElements.add("Analysis completed at ${System.currentTimeMillis()}")
        content
    }
    .updatePipelineContextOnExit()  // Push context to pipeline

val generationPipe = BedrockPipe()
    .pullPipelineContext()  // Pull analysis results
    .autoInjectContext("Use the analysis results from the previous stage.")
    .setSystemPrompt("Generate content based on the analysis provided.")

val pipeline = Pipeline()
    .add(analysisPipe)    // Stage 1: Analysis
    .add(generationPipe)  // Stage 2: Generation (uses analysis results)
```

## Updating Pipeline Context

### Automatic Context Updates
```kotlin
val pipe = BedrockPipe()
    .pullPipelineContext()
    .updatePipelineContextOnExit()  // Automatically merge context back to pipeline
    .setTransformationFunction { content ->
        // Modify context during processing
        content.context.addLoreBookEntry("processedData", content.text, weight = 8)
        content.context.contextElements.add("Processing stage completed")
        content
    }
```

**What `updatePipelineContextOnExit()` does**:
- Merges the pipe's context back into the pipeline context
- Happens after successful pipe execution
- Includes any modifications made during processing
- Makes results available to subsequent pipes

### Manual Context Updates
```kotlin
val pipe = BedrockPipe()
    .pullPipelineContext()
    .setTransformationFunction { content ->
        // Manually update pipeline context
        val pipeline = getCurrentPipeline()  // Hypothetical method
        pipeline.context.merge(content.context)
        
        content
    }
```

## Context Flow Patterns

### Sequential Processing with Context Accumulation
```kotlin
// Stage 1: Input processing
val inputProcessor = BedrockPipe()
    .setSystemPrompt("Process and clean the input data.")
    .setTransformationFunction { content ->
        val cleanedData = cleanInput(content.text)
        content.context.addLoreBookEntry("cleanedInput", cleanedData, weight = 5)
        content.context.contextElements.add("Input processing completed")
        content.text = cleanedData
        content
    }
    .updatePipelineContextOnExit()

// Stage 2: Analysis using cleaned input
val analyzer = BedrockPipe()
    .pullPipelineContext()  // Gets cleaned input from stage 1
    .autoInjectContext("Use the cleaned input data for analysis.")
    .setSystemPrompt("Analyze the cleaned data and extract insights.")
    .setTransformationFunction { content ->
        val insights = extractInsights(content.text)
        content.context.addLoreBookEntry("insights", insights, weight = 8)
        content.context.contextElements.add("Analysis completed")
        content.text = insights
        content
    }
    .updatePipelineContextOnExit()

// Stage 3: Report generation using all previous results
val reportGenerator = BedrockPipe()
    .pullPipelineContext()  // Gets cleaned input + insights
    .autoInjectContext("Use all previous processing results to generate a comprehensive report.")
    .setSystemPrompt("Generate a detailed report based on the analysis.")

val pipeline = Pipeline()
    .add(inputProcessor)   // Adds cleaned input to context
    .add(analyzer)         // Adds insights to context  
    .add(reportGenerator)  // Uses all accumulated context
```

### Conditional Context Flow
```kotlin
val conditionalProcessor = BedrockPipe()
    .pullPipelineContext()
    .setPreValidationFunction { contextWindow, content ->
        // Modify context based on previous pipeline results
        val previousResults = contextWindow.loreBookKeys["analysis"]?.value ?: ""
        
        if (previousResults.contains("complex")) {
            // Add additional context for complex cases
            val additionalContext = ContextBank.getContextFromBank("complexProcessingRules")
            contextWindow.merge(additionalContext)
        }
        
        contextWindow
    }
    .autoInjectContext("Use pipeline context and any additional context based on complexity.")
```

### Context Validation and Correction
```kotlin
val validationPipe = BedrockPipe()
    .pullPipelineContext()
    .setValidatorFunction { content ->
        // Validate using pipeline context
        val previousAnalysis = content.context.loreBookKeys["analysis"]?.value ?: ""
        val currentResult = content.text
        
        val isConsistent = validateConsistency(previousAnalysis, currentResult)
        
        if (!isConsistent) {
            // Add correction context
            content.context.contextElements.add("Inconsistency detected, correction needed")
            content.repeat()  // Retry with correction context
            return@setValidatorFunction false
        }
        
        true
    }
    .updatePipelineContextOnExit()
```

## Advanced Pipeline Context Patterns

### Multi-Branch Context Merging
```kotlin
// Branch A: Technical analysis
val technicalAnalysis = BedrockPipe()
    .setSystemPrompt("Perform technical analysis.")
    .setTransformationFunction { content ->
        content.context.addLoreBookEntry("technicalAnalysis", content.text, weight = 7)
        content
    }
    .updatePipelineContextOnExit()

// Branch B: Business analysis  
val businessAnalysis = BedrockPipe()
    .setSystemPrompt("Perform business analysis.")
    .setTransformationFunction { content ->
        content.context.addLoreBookEntry("businessAnalysis", content.text, weight = 7)
        content
    }
    .updatePipelineContextOnExit()

// Merger: Combines both analyses
val analysisMerger = BedrockPipe()
    .pullPipelineContext()  // Gets both technical and business analysis
    .autoInjectContext("Combine the technical and business analyses.")
    .setSystemPrompt("Create a comprehensive analysis combining technical and business perspectives.")

// Note: This would require pipeline branching/merging capabilities
```

### Context-Driven Pipeline Routing
```kotlin
val routingPipe = BedrockPipe()
    .pullPipelineContext()
    .setValidatorFunction { content ->
        val pipelineContext = content.context
        val analysisType = pipelineContext.loreBookKeys["analysisType"]?.value ?: ""
        
        when (analysisType) {
            "technical" -> {
                content.jumpToPipe("technicalProcessor")
                false
            }
            "business" -> {
                content.jumpToPipe("businessProcessor")
                false
            }
            "comprehensive" -> {
                content.jumpToPipe("comprehensiveProcessor")
                false
            }
            else -> true  // Continue normal flow
        }
    }
```

### Context Cleanup and Optimization
```kotlin
val contextOptimizer = BedrockPipe()
    .pullPipelineContext()
    .setPreValidationFunction { contextWindow, content ->
        // Clean up and optimize context before final processing
        
        // Remove low-weight entries
        contextWindow.loreBookKeys.values.removeIf { it.weight < 5 }
        
        // Limit context elements to most recent
        if (contextWindow.contextElements.size > 10) {
            contextWindow.contextElements = contextWindow.contextElements.takeLast(10).toMutableList()
        }
        
        // Merge duplicate entries
        contextWindow.contextElements = contextWindow.contextElements.distinct().toMutableList()
        
        contextWindow
    }
    .autoInjectContext("Use the optimized pipeline context.")
```

## Practical Examples

### Document Processing Pipeline
```kotlin
val documentPipeline = Pipeline()
    .add(BedrockPipe()
        .setSystemPrompt("Extract document metadata and structure.")
        .setTransformationFunction { content ->
            val metadata = extractMetadata(content.text)
            content.context.addLoreBookEntry("documentMetadata", metadata, weight = 10)
            content.context.contextElements.add("Document type: ${getDocumentType(metadata)}")
            content
        }
        .updatePipelineContextOnExit()
    )
    .add(BedrockPipe()
        .pullPipelineContext()
        .autoInjectContext("Use document metadata for content analysis.")
        .setSystemPrompt("Analyze document content based on its type and structure.")
        .setTransformationFunction { content ->
            val analysis = analyzeContent(content.text)
            content.context.addLoreBookEntry("contentAnalysis", analysis, weight = 9)
            content
        }
        .updatePipelineContextOnExit()
    )
    .add(BedrockPipe()
        .pullPipelineContext()
        .autoInjectContext("Use metadata and analysis for summary generation.")
        .setSystemPrompt("Generate a comprehensive summary using all available information.")
    )
```

### Multi-Stage Validation Pipeline
```kotlin
val validationPipeline = Pipeline()
    .add(BedrockPipe()
        .setSystemPrompt("Perform initial content validation.")
        .setTransformationFunction { content ->
            val validationResults = performInitialValidation(content.text)
            content.context.addLoreBookEntry("initialValidation", validationResults, weight = 8)
            content.context.contextElements.add("Initial validation completed")
            content
        }
        .updatePipelineContextOnExit()
    )
    .add(BedrockPipe()
        .pullPipelineContext()
        .autoInjectContext("Use initial validation results for detailed analysis.")
        .setSystemPrompt("Perform detailed validation based on initial results.")
        .setValidatorFunction { content ->
            val initialResults = content.context.loreBookKeys["initialValidation"]?.value ?: ""
            val detailedResults = content.text
            
            validateConsistency(initialResults, detailedResults)
        }
        .updatePipelineContextOnExit()
    )
    .add(BedrockPipe()
        .pullPipelineContext()
        .autoInjectContext("Use all validation results for final assessment.")
        .setSystemPrompt("Provide final validation assessment and recommendations.")
    )
```

### Multi-Context Game Pipeline with MiniBank
```kotlin
val gamePipeline = Pipeline()
    .add(BedrockPipe()
        .setSystemPrompt("Process player action and update game state.")
        .pullPipelineContext()
        .setPageKey("playerStats, worldState, inventory, questLog")
        .setTransformationFunction { content ->
            val miniBank = content.workspaceContext
            val action = extractPlayerAction(content.text)
            
            // Update different context pages based on action
            when (action.type) {
                "move" -> {
                    miniBank.contextMap["playerStats"]?.contextElements?.add("Stamina: ${action.staminaCost}")
                    miniBank.contextMap["worldState"]?.addLoreBookEntry("playerLocation", action.newLocation, weight = 10)
                }
                "useItem" -> {
                    miniBank.contextMap["inventory"]?.loreBookKeys?.remove(action.itemUsed)
                    miniBank.contextMap["playerStats"]?.contextElements?.add("Item used: ${action.itemUsed}")
                }
                "questAction" -> {
                    miniBank.contextMap["questLog"]?.contextElements?.add("Quest progress: ${action.questUpdate}")
                    miniBank.contextMap["playerStats"]?.contextElements?.add("Experience: +${action.expGained}")
                }
            }
            
            content
        }
        .updatePipelineContextOnExit()  // All MiniBank updates pushed to pipeline
    )
    .add(BedrockPipe()
        .pullPipelineContext()  // Receives updated MiniBank with all game state changes
        .autoInjectContext("Use updated game context to generate appropriate response.")
        .setSystemPrompt("Generate game response based on current state and player action.")
        .setTransformationFunction { content ->
            val miniBank = content.workspaceContext
            
            // Add response metadata to appropriate context pages
            miniBank.contextMap["worldState"]?.contextElements?.add("Response generated: ${System.currentTimeMillis()}")
            miniBank.contextMap["playerStats"]?.contextElements?.add("Last interaction: ${content.text.take(50)}")
            
            content
        }
        .updatePipelineContextOnExit()  // Final state pushed back to pipeline
    )
```

## Best Practices

### 1. Setting Pipeline Context
```kotlin
val pipeline = Pipeline()

// Set context window
val contextWindow = ContextWindow()
contextWindow.addLoreBookEntry("gameState", "Current game status", 10)
pipeline.setContextWindow(contextWindow)

// Set mini bank for multi-page context
val miniBank = MiniBank()
miniBank.contextMap["worldState"] = ContextWindow()
miniBank.contextMap["playerData"] = ContextWindow()
pipeline.setMiniBank(miniBank)
```

### 2. Clear Context Updates
```kotlin
// Good: Descriptive context entries
.setTransformationFunction { content ->
    content.context.addLoreBookEntry("userAnalysis", analysisResult, weight = 8)
    content.context.contextElements.add("Analysis stage completed at ${timestamp}")
    content
}

// Good: Descriptive MiniBank updates
.setTransformationFunction { content ->
    val miniBank = content.workspaceContext
    miniBank.contextMap["userAnalysis"]?.addLoreBookEntry("result", analysisResult, weight = 8)
    miniBank.contextMap["processingLog"]?.contextElements?.add("Analysis completed at ${timestamp}")
    content
}

// Avoid: Generic or unclear context
.setTransformationFunction { content ->
    content.context.addLoreBookEntry("data", content.text, weight = 5)
    content
}
```

### 2. Appropriate Context Sharing
```kotlin
// Use pipeline context for stage-to-stage communication
.pullPipelineContext()
.updatePipelineContextOnExit()

// Use global context for cross-pipeline data
.pullGlobalContext()
.setPageKey("sharedKnowledge")

// Use MiniBank for organized multi-context scenarios
.pullPipelineContext()
.setPageKey("context1, context2, context3")  // Automatically uses MiniBank
.updatePipelineContextOnExit()  // MiniBank changes pushed to pipeline
```

### 3. Context Validation
```kotlin
// Single context validation
.setPreValidationFunction { contextWindow, content ->
    if (!contextWindow.loreBookKeys.containsKey("requiredData")) {
        throw IllegalStateException("Required pipeline context missing")
    }
    contextWindow
}

// MiniBank validation
.setPreValidationMiniBankFunction { miniBank, content ->
    val requiredPages = listOf("userSession", "gameState")
    requiredPages.forEach { pageKey ->
        if (!miniBank.contextMap.containsKey(pageKey)) {
            throw IllegalStateException("Required context page missing: $pageKey")
        }
    }
    miniBank
}
```

### 4. Context Cleanup
```kotlin
// Clean up single context
.setTransformationFunction { content ->
    content.context.contextElements.removeIf { it.startsWith("temp_") }
    content
}

// Clean up MiniBank contexts
.setTransformationFunction { content ->
    val miniBank = content.workspaceContext
    miniBank.contextMap.values.forEach { contextWindow ->
        contextWindow.contextElements.removeIf { it.startsWith("temp_") }
    }
    content
}
```

### 6. Appropriate Merge Settings
```kotlin
// Default emplacement: Good for most cases
val standardPipe = BedrockPipe()
    .pullPipelineContext()
    .updatePipelineContextOnExit()  // Uses default emplacement

// Append mode: Good for cumulative data
val storyPipe = BedrockPipe()
    .pullPipelineContext()
    .enableAppendLoreBookScheme()  // Accumulate story events
    .updatePipelineContextOnExit()

// Immutable mode: Good for protecting reference data
val analysisPipe = BedrockPipe()
    .pullPipelineContext()
    .enableImmutableLoreBook()  // Protect reference data
    .updatePipelineContextOnExit()

// Choose based on use case:
// - Emplacement: General data updates, replacing values
// - Append: Story writing, event logging, cumulative data
// - Immutable: Read-only scenarios, protecting critical context
```

Pipeline context integration enables sophisticated multi-stage processing where each pipe builds upon the work of previous stages, creating powerful workflows that maintain context continuity and enable complex AI processing patterns.

## MiniBank Integration with Pipeline Context

When pipes use multiple page keys, they work with MiniBank instead of a single ContextWindow. The MiniBank can be modified through developer-in-the-loop functions and automatically updates the pipeline context.

### Accessing MiniBank in DITL Functions
```kotlin
val pipe = BedrockPipe()
    .pullPipelineContext()
    .setPageKey("userProfile, gameState, inventory")  // Creates MiniBank
    .setTransformationFunction { content ->
        // Access MiniBank through content.workspaceContext
        val miniBank = content.workspaceContext
        
        // Modify specific context pages
        val userProfile = miniBank.contextMap["userProfile"]
        userProfile?.contextElements?.add("User action: ${extractAction(content.text)}")
        
        val gameState = miniBank.contextMap["gameState"]
        gameState?.addLoreBookEntry("currentAction", content.text, weight = 7)
        
        content
    }
    .updatePipelineContextOnExit()  // MiniBank automatically pushed to pipeline
```

### MiniBank vs ContextWindow in DITL Functions
```kotlin
// Single context (ContextWindow)
.setTransformationFunction { content ->
    // Access through content.context
    content.context.addLoreBookEntry("result", content.text, weight = 5)
    content
}

// Multiple contexts (MiniBank)  
.setTransformationFunction { content ->
    // Access through content.workspaceContext
    val miniBank = content.workspaceContext
    miniBank.contextMap["specificPage"]?.addLoreBookEntry("result", content.text, weight = 5)
    content
}
```

### Automatic MiniBank Pipeline Updates
```kotlin
val multiContextPipe = BedrockPipe()
    .pullPipelineContext()
    .setPageKey("analysis, generation, validation")
    .setTransformationFunction { content ->
        val miniBank = content.workspaceContext
        
        // Update analysis page
        miniBank.contextMap["analysis"]?.contextElements?.add("Analysis: ${content.text}")
        
        // Update generation page with results
        miniBank.contextMap["generation"]?.addLoreBookEntry("output", content.text, weight = 8)
        
        // Update validation page with quality metrics
        val quality = assessQuality(content.text)
        miniBank.contextMap["validation"]?.contextElements?.add("Quality: $quality")
        
        content
    }
    .updatePipelineContextOnExit()  // All MiniBank changes automatically pushed to pipeline

// Next pipe in pipeline automatically receives updated MiniBank
val nextPipe = BedrockPipe()
    .pullPipelineContext()  // Gets updated MiniBank with all changes
    .autoInjectContext("Use the updated multi-page context from previous processing.")
```

### MiniBank Pre-Validation Updates
```kotlin
val pipe = BedrockPipe()
    .pullPipelineContext()
    .setPageKey("userSession, gameData, preferences")
    .setPreValidationMiniBankFunction { miniBank, content ->
        // Modify MiniBank before AI processing
        val userSession = miniBank.contextMap["userSession"]
        val gameData = miniBank.contextMap["gameData"]
        val preferences = miniBank.contextMap["preferences"]
        
        // Update based on input analysis
        val inputType = analyzeInput(content?.text ?: "")
        when (inputType) {
            "gameAction" -> {
                gameData?.contextElements?.add("Pending action: ${content?.text}")
                userSession?.contextElements?.add("Last action type: game")
            }
            "preference" -> {
                val newPref = extractPreference(content?.text ?: "")
                preferences?.contextElements?.add("New preference: $newPref")
                userSession?.contextElements?.add("Preferences updated")
            }
        }
        
        miniBank  // Modified MiniBank returned and used for processing
    }
    .updatePipelineContextOnExit()  // Updated MiniBank pushed to pipeline
```

## Context Merge Settings

When pipes update pipeline context using `updatePipelineContextOnExit()`, TPipe provides merge settings that control how LoreBook entries are handled during the merge process.

### Default Merge Behavior (Emplacement)
```kotlin
val pipe = BedrockPipe()
    .pullPipelineContext()
    .updatePipelineContextOnExit()  // Uses default emplacement merge
    .setTransformationFunction { content ->
        // Add or update LoreBook entries
        content.context.addLoreBookEntry("userAction", "User clicked button", weight = 5)
        content.context.addLoreBookEntry("timestamp", System.currentTimeMillis().toString(), weight = 3)
        content
    }
```

**Default behavior (emplaceLorebook = true)**:
- **New keys**: Added to pipeline context
- **Existing keys**: Completely replaced with new values
- **Context elements**: Always merged (added to existing list)
- **Conversation history**: Always merged

### Append Mode Merge
```kotlin
val pipe = BedrockPipe()
    .pullPipelineContext()
    .enableAppendLoreBookScheme()  // Enable append mode
    .updatePipelineContextOnExit()
    .setTransformationFunction { content ->
        // LoreBook entries will be appended, not replaced
        content.context.addLoreBookEntry("storyEvents", "Character entered the room", weight = 7)
        content.context.addLoreBookEntry("storyEvents", "Character spoke to NPC", weight = 7)
        content
    }
```

**Append behavior (appendLoreBook = true)**:
- **New keys**: Added to pipeline context
- **Existing keys**: New content appended to existing values
- **Result**: `existingValue + " " + newValue`
- **Use case**: Creative writing, story generation, cumulative events

### Immutable LoreBook Mode
```kotlin
val pipe = BedrockPipe()
    .pullPipelineContext()
    .enableImmutableLoreBook()  // Disable all LoreBook updates
    .updatePipelineContextOnExit()
    .setTransformationFunction { content ->
        // LoreBook changes will be ignored during merge
        content.context.addLoreBookEntry("protectedData", "This won't update pipeline", weight = 10)
        
        // Context elements still merge normally
        content.context.contextElements.add("This will be added to pipeline")
        content
    }
```

**Immutable behavior (emplaceLorebook = false, appendLoreBook = false)**:
- **LoreBook entries**: No changes merged to pipeline
- **Context elements**: Still merged normally
- **Conversation history**: Still merged normally
- **Use case**: Protecting critical context from modification

### Merge Setting Combinations

#### Standard Emplacement (Default)
```kotlin
val pipe = BedrockPipe()
    .pullPipelineContext()
    .updatePipelineContextOnExit()  // emplaceLorebook=true, appendLoreBook=false
```

**Behavior**:
- New LoreBook keys: Added
- Existing LoreBook keys: Replaced
- Context elements: Merged
- Best for: Most general use cases

#### Append Mode
```kotlin
val pipe = BedrockPipe()
    .pullPipelineContext()
    .enableAppendLoreBookScheme()  // emplaceLorebook=false, appendLoreBook=true
    .updatePipelineContextOnExit()
```

**Behavior**:
- New LoreBook keys: Added
- Existing LoreBook keys: Content appended
- Context elements: Merged
- Best for: Creative writing, story generation, event logging

#### Immutable Mode
```kotlin
val pipe = BedrockPipe()
    .pullPipelineContext()
    .enableImmutableLoreBook()  // emplaceLorebook=false, appendLoreBook=false
    .updatePipelineContextOnExit()
```

**Behavior**:
- New LoreBook keys: Ignored
- Existing LoreBook keys: Unchanged
- Context elements: Merged
- Best for: Read-only context scenarios, protecting critical data

### MiniBank Merge Settings

MiniBank merge settings work the same way, applying to each ContextWindow within the MiniBank:

```kotlin
val pipe = BedrockPipe()
    .pullPipelineContext()
    .setPageKey("storyEvents, characterData, worldState")
    .enableAppendLoreBookScheme()  // Applies to all MiniBank pages
    .updatePipelineContextOnExit()
    .setTransformationFunction { content ->
        val miniBank = content.workspaceContext
        
        // All pages use append mode for LoreBook entries
        miniBank.contextMap["storyEvents"]?.addLoreBookEntry("events", "New event occurred", weight = 6)
        miniBank.contextMap["characterData"]?.addLoreBookEntry("personality", "Shows courage", weight = 8)
        miniBank.contextMap["worldState"]?.addLoreBookEntry("weather", "Storm approaching", weight = 4)
        
        content
    }
```

### Practical Merge Examples

#### Story Writing Pipeline with Append Mode
```kotlin
val storyPipeline = Pipeline()
    .add(BedrockPipe()
        .setSystemPrompt("Generate story events based on user input.")
        .pullPipelineContext()
        .enableAppendLoreBookScheme()  // Accumulate story events
        .updatePipelineContextOnExit()
        .setTransformationFunction { content ->
            val storyEvent = extractStoryEvent(content.text)
            content.context.addLoreBookEntry("storyEvents", storyEvent, weight = 7)
            content.context.addLoreBookEntry("timeline", "Event at ${System.currentTimeMillis()}", weight = 5)
            content
        }
    )
    .add(BedrockPipe()
        .pullPipelineContext()  // Gets accumulated story events
        .enableAppendLoreBookScheme()  // Continue accumulating
        .updatePipelineContextOnExit()
        .autoInjectContext("Use accumulated story events to maintain continuity.")
        .setSystemPrompt("Continue the story maintaining consistency with previous events.")
    )
```

#### Data Analysis with Immutable Reference Data
```kotlin
val analysisPipeline = Pipeline()
    .add(BedrockPipe()
        .setSystemPrompt("Load reference data for analysis.")
        .pullPipelineContext()
        .updatePipelineContextOnExit()
        .setTransformationFunction { content ->
            // Load reference data that shouldn't be modified
            content.context.addLoreBookEntry("referenceData", loadReferenceData(), weight = 10)
            content.context.addLoreBookEntry("analysisRules", loadAnalysisRules(), weight = 10)
            content
        }
    )
    .add(BedrockPipe()
        .pullPipelineContext()
        .enableImmutableLoreBook()  // Protect reference data from modification
        .updatePipelineContextOnExit()
        .autoInjectContext("Use reference data for analysis but do not modify it.")
        .setSystemPrompt("Analyze data using the provided reference information.")
        .setTransformationFunction { content ->
            // Analysis results added to context elements (still merged)
            content.context.contextElements.add("Analysis result: ${content.text}")
            
            // Attempts to modify LoreBook will be ignored during merge
            content.context.addLoreBookEntry("referenceData", "Modified data", weight = 5)  // Ignored
            content
        }
    )
```

#### Multi-Context Game with Mixed Merge Settings
```kotlin
val gamePipe = BedrockPipe()
    .pullPipelineContext()
    .setPageKey("playerStats, gameEvents, worldData")
    .setTransformationFunction { content ->
        val miniBank = content.workspaceContext
        
        // Different merge strategies for different context types
        val action = extractPlayerAction(content.text)
        
        when (action.type) {
            "levelUp" -> {
                // Replace player stats (emplacement)
                miniBank.contextMap["playerStats"]?.addLoreBookEntry("level", action.newLevel.toString(), weight = 10)
                miniBank.contextMap["playerStats"]?.addLoreBookEntry("experience", action.newExp.toString(), weight = 10)
            }
            "storyEvent" -> {
                // Accumulate game events (would use append if enabled)
                miniBank.contextMap["gameEvents"]?.addLoreBookEntry("events", action.eventDescription, weight = 8)
            }
            "worldChange" -> {
                // Update world state (emplacement)
                miniBank.contextMap["worldData"]?.addLoreBookEntry("currentState", action.newWorldState, weight = 9)
            }
        }
        
        content
    }

// Configure different merge settings for different scenarios
when (gameMode) {
    "story" -> gamePipe.enableAppendLoreBookScheme()  // Accumulate story events
    "competitive" -> gamePipe.enableImmutableLoreBook()  // Protect game rules
    else -> gamePipe  // Use default emplacement
}

gamePipe.updatePipelineContextOnExit()
```

## Pipeline Global Context Updates

Pipelines can automatically update the global ContextBank when they complete execution, making their accumulated context available to other pipelines and applications.

> **⚠️ Important Warning**: Pipeline global context updates perform a **full emplace** (complete replacement) of the target ContextBank page, not a merge. This means any existing context in the target page will be completely overwritten. Use with caution when multiple pipelines might be updating the same global context page.

### Basic Global Context Update
```kotlin
val pipeline = Pipeline()
    .useGlobalContext()  // Enable automatic global context updates
    .add(BedrockPipe()
        .setSystemPrompt("Analyze user input and extract insights.")
        .setTransformationFunction { content ->
            content.context.addLoreBookEntry("userInsights", content.text, weight = 8)
            content.context.contextElements.add("Analysis completed at ${System.currentTimeMillis()}")
            content
        }
        .updatePipelineContextOnExit()
    )
    .add(BedrockPipe()
        .pullPipelineContext()
        .setSystemPrompt("Generate recommendations based on insights.")
        .setTransformationFunction { content ->
            content.context.addLoreBookEntry("recommendations", content.text, weight = 7)
            content
        }
        .updatePipelineContextOnExit()
    )

// ⚠️ WARNING: When pipeline completes, it will REPLACE (not merge) the global context
// Any existing context in ContextBank will be completely overwritten

// When pipeline completes, accumulated context automatically pushed to ContextBank
```

**What happens**:
- Pipeline accumulates context from all pipes
- On completion, pipeline context is pushed to global ContextBank
- Other pipelines can access this context via `pullGlobalContext()`

### Global Context with Page Keys
```kotlin
val analysisPipeline = Pipeline()
    .useGlobalContext("analysisResults")  // Update specific global page
    .add(BedrockPipe()
        .setSystemPrompt("Perform data analysis.")
        .setTransformationFunction { content ->
            content.context.addLoreBookEntry("analysisData", content.text, weight = 9)
            content.context.contextElements.add("Analysis type: comprehensive")
            content
        }
        .updatePipelineContextOnExit()
    )

// Pipeline context stored in ContextBank under "analysisResults" page key

val reportPipeline = Pipeline()
    .add(BedrockPipe()
        .pullGlobalContext()
        .setPageKey("analysisResults")  // Access analysis results from previous pipeline
        .autoInjectContext("Use analysis results to generate report.")
        .setSystemPrompt("Generate report based on analysis data.")
    )
```

### Multi-Page Global Updates
```kotlin
val multiContextPipeline = Pipeline()
    .useGlobalContext("userSession, gameState, preferences")  // Multiple global pages
    .add(BedrockPipe()
        .setPageKey("userSession, gameState, preferences")  // Work with multiple contexts
        .setTransformationFunction { content ->
            val miniBank = content.workspaceContext
            
            // Update different context pages
            miniBank.contextMap["userSession"]?.addLoreBookEntry("lastAction", content.text, weight = 6)
            miniBank.contextMap["gameState"]?.contextElements?.add("State updated: ${System.currentTimeMillis()}")
            miniBank.contextMap["preferences"]?.addLoreBookEntry("recentChoice", extractChoice(content.text), weight = 5)
            
            content
        }
        .updatePipelineContextOnExit()
    )

// Each MiniBank page automatically updated in global ContextBank:
// - ContextBank["userSession"] gets updated userSession context
// - ContextBank["gameState"] gets updated gameState context  
// - ContextBank["preferences"] gets updated preferences context
```

### Cross-Pipeline Communication Pattern
```kotlin
// Pipeline 1: Data processing
val processingPipeline = Pipeline()
    .useGlobalContext("processedData")
    .add(BedrockPipe()
        .setSystemPrompt("Process and clean input data.")
        .setTransformationFunction { content ->
            val cleanedData = processData(content.text)
            content.context.addLoreBookEntry("cleanedData", cleanedData, weight = 10)
            content.context.contextElements.add("Processing completed")
            content.text = cleanedData
            content
        }
        .updatePipelineContextOnExit()
    )

// Pipeline 2: Analysis (runs after processing)
val analysisPipeline = Pipeline()
    .useGlobalContext("analysisResults")
    .add(BedrockPipe()
        .pullGlobalContext()
        .setPageKey("processedData")  // Use results from processing pipeline
        .autoInjectContext("Use processed data for analysis.")
        .setSystemPrompt("Analyze the processed data.")
        .setTransformationFunction { content ->
            val analysis = analyzeData(content.text)
            content.context.addLoreBookEntry("analysis", analysis, weight = 9)
            content
        }
        .updatePipelineContextOnExit()
    )

// Pipeline 3: Reporting (uses both previous results)
val reportPipeline = Pipeline()
    .add(BedrockPipe()
        .pullGlobalContext()
        .setPageKey("processedData, analysisResults")  // Use both previous results
        .autoInjectContext("Use processed data and analysis results for comprehensive reporting.")
        .setSystemPrompt("Generate comprehensive report.")
    )
```

### Global Context Update Behavior

#### Single Context Pipeline
```kotlin
val pipeline = Pipeline()
    .useGlobalContext()  // No page key specified
    .add(pipe1)
    .add(pipe2)

// Result: Pipeline context replaces default banked context in ContextBank
```

#### Page-Specific Pipeline
```kotlin
val pipeline = Pipeline()
    .useGlobalContext("specificPage")  // Page key specified
    .add(pipe1)
    .add(pipe2)

// Result: Pipeline context stored under "specificPage" in ContextBank
```

#### Multi-Page Pipeline
```kotlin
val pipeline = Pipeline()
    .useGlobalContext("page1, page2, page3")  // Multiple page keys
    .add(BedrockPipe().setPageKey("page1, page2, page3"))  // Pipe uses MiniBank

// Result: Each MiniBank page stored separately in ContextBank
// - ContextBank["page1"] = miniBank.contextMap["page1"]
// - ContextBank["page2"] = miniBank.contextMap["page2"]  
// - ContextBank["page3"] = miniBank.contextMap["page3"]
```

### Practical Global Context Examples

#### Session Management System
```kotlin
// User interaction pipeline
val userPipeline = Pipeline()
    .useGlobalContext("userSession${userId}")
    .add(BedrockPipe()
        .setSystemPrompt("Process user interaction and update session.")
        .setTransformationFunction { content ->
            content.context.addLoreBookEntry("lastInteraction", content.text, weight = 7)
            content.context.contextElements.add("Session updated: ${System.currentTimeMillis()}")
            content.context.contextElements.add("User ID: $userId")
            content
        }
        .updatePipelineContextOnExit()
    )

// Later, other pipelines can access user session
val recommendationPipeline = Pipeline()
    .add(BedrockPipe()
        .pullGlobalContext()
        .setPageKey("userSession${userId}")
        .autoInjectContext("Use user session data for personalized recommendations.")
        .setSystemPrompt("Generate personalized recommendations.")
    )
```

#### Multi-Stage Document Processing
```kotlin
// Stage 1: Document parsing
val parsingPipeline = Pipeline()
    .useGlobalContext("documentMetadata, documentContent")
    .add(BedrockPipe()
        .setPageKey("documentMetadata, documentContent")
        .setTransformationFunction { content ->
            val miniBank = content.workspaceContext
            val metadata = extractMetadata(content.text)
            val cleanContent = cleanDocument(content.text)
            
            miniBank.contextMap["documentMetadata"]?.addLoreBookEntry("metadata", metadata, weight = 10)
            miniBank.contextMap["documentContent"]?.addLoreBookEntry("content", cleanContent, weight = 10)
            
            content
        }
        .updatePipelineContextOnExit()
    )

// Stage 2: Document analysis (uses parsed results)
val analysisPipeline = Pipeline()
    .useGlobalContext("documentAnalysis")
    .add(BedrockPipe()
        .pullGlobalContext()
        .setPageKey("documentMetadata, documentContent")
        .autoInjectContext("Use document metadata and content for analysis.")
        .setSystemPrompt("Analyze document based on metadata and content.")
        .setTransformationFunction { content ->
            content.context.addLoreBookEntry("analysis", content.text, weight = 9)
            content
        }
        .updatePipelineContextOnExit()
    )

// Stage 3: Report generation (uses all previous results)
val reportPipeline = Pipeline()
    .add(BedrockPipe()
        .pullGlobalContext()
        .setPageKey("documentMetadata, documentContent, documentAnalysis")
        .autoInjectContext("Use all document processing results for comprehensive report.")
        .setSystemPrompt("Generate final document report.")
    )
```

#### Game State Management
```kotlin
// Player action pipeline
val actionPipeline = Pipeline()
    .useGlobalContext("gameState${gameId}")
    .add(BedrockPipe()
        .setSystemPrompt("Process player action and update game state.")
        .setTransformationFunction { content ->
            val action = processPlayerAction(content.text)
            content.context.addLoreBookEntry("lastAction", action.description, weight = 8)
            content.context.addLoreBookEntry("gameState", action.newGameState, weight = 10)
            content.context.contextElements.add("Turn: ${action.turnNumber}")
            content
        }
        .updatePipelineContextOnExit()
    )

// Game AI response pipeline (uses updated game state)
val aiResponsePipeline = Pipeline()
    .add(BedrockPipe()
        .pullGlobalContext()
        .setPageKey("gameState${gameId}")
        .autoInjectContext("Use current game state to generate AI response.")
        .setSystemPrompt("Generate appropriate AI response based on game state.")
    )
```

## Next Steps

Now that you understand context sharing within pipelines, learn about developer-in-the-loop processing:

**→ [Developer-in-the-Loop Functions](developer-in-the-loop.md)** - Code-based validation and transformation
