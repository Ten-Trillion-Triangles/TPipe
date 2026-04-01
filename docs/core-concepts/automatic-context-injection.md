# Automatic Context Injection

> 💡 **Tip:** Automatic injection ensures your Reservoirs stay topped up, seamlessly injecting global state into your LLM's context window before execution.


## Table of Contents
- [What is Automatic Context Injection?](#what-is-automatic-context-injection)
- [Context Sources](#context-sources)
- [Context Injection Methods](#context-injection-methods)
- [Context Flow Patterns](#context-flow-patterns)
- [Context Injection Timing](#context-injection-timing)
- [Practical Examples](#practical-examples)
- [Best Practices](#best-practices)

TPipe provides powerful automatic context injection capabilities that seamlessly integrate context data into AI model prompts. This system handles context retrieval, formatting, and injection without requiring manual prompt construction.

## What is Automatic Context Injection?

Automatic context injection is a system that:
- **Retrieves context** from global ContextBank or pipeline context
- **Injects context schema** into the system prompt to teach the AI the structure
- **Injects actual context data** into the user prompt as JSON
- **Manages context flow** between pipes and processing stages

The system prompt receives the schema and your instructions on how to use it, while the user prompt receives the actual context data. This separation ensures the AI understands the context structure while keeping the actual data with the user's input.

## Context Sources

### Global Context (ContextBank)
```kotlin
val pipe = BedrockPipe()
    .pullGlobalContext()  // Pull from ContextBank
    .autoInjectContext("Use the provided context to answer questions accurately.")
```

**What this does**:
- Retrieves context from the global ContextBank
- Automatically formats and injects it into the system prompt
- Makes global context available to the AI model

### Pipeline Context
```kotlin
val pipe = BedrockPipe()
    .pullPipelineContext()  // Pull from parent pipeline
    .autoInjectContext("Use the pipeline context for processing.")
```

**What this does**:
- Retrieves context from the parent pipeline's context window
- Merges with global context if both are enabled
- Shares context between pipes in the same pipeline

### Page-Specific Context
```kotlin
val pipe = BedrockPipe()
    .pullGlobalContext()
    .setPageKey("userSession")  // Pull specific context page
    .autoInjectContext("Use the user session context provided.")

// Multiple pages
val pipe = BedrockPipe()
    .pullGlobalContext()
    .setPageKey("userSession, gameState, preferences")  // Multiple pages
    .autoInjectContext("Use all provided context types.")
```

**What this does**:
- Retrieves context from specific named pages in ContextBank
- Allows targeted context retrieval for different use cases
- Supports multiple page keys for complex context scenarios

## Context Injection Methods

### Basic Auto-Injection
```kotlin
val pipe = BedrockPipe()
    .pullGlobalContext()
    .autoInjectContext("The user prompt will contain context data in the following JSON schema. Use this context to provide accurate responses.")
```

**System prompt result**:
```
Your original system prompt

The user prompt will contain context data in the following JSON schema. Use this context to provide accurate responses.

{
  "loreBookKeys": {...},
  "contextElements": [...],
  "converseHistory": {...}
}
```

**User prompt result**:
```
User's actual input text

{
  "loreBookKeys": {"characterName": {"value": "John is a detective...", "weight": 5}},
  "contextElements": ["Important rule: Always be helpful"],
  "converseHistory": {"history": [...]}
}
```

### Advanced Context Instructions
```kotlin
val pipe = BedrockPipe()
    .pullGlobalContext()
    .autoInjectContext("""
        The user prompt contains context data with this structure:
        - loreBookKeys: Character and world information with weights
        - contextElements: Important facts and rules as strings
        - converseHistory: Previous conversation history
        
        Use this context data from the user prompt to provide accurate, consistent responses.
    """)
```

### Context with Truncation
```kotlin
val pipe = BedrockPipe()
    .pullGlobalContext()
    .autoTruncateContext()  // Automatically truncate to fit token budget
    .autoInjectContext("Use the provided context, which has been optimized for relevance.")
```

## Context Flow Patterns

### Pipeline Context Sharing
```kotlin
// Pipe 1: Stores results in pipeline context
val analysisPipe = BedrockPipe()
    .setTransformationFunction { content ->
        // Store analysis in pipeline context
        content.context.addLoreBookEntry("analysis", content.text, weight = 10)
        content
    }
    .updatePipelineContextOnExit()  // Push context to pipeline

// Pipe 2: Uses pipeline context automatically
val generationPipe = BedrockPipe()
    .pullPipelineContext()  // Pull from pipeline
    .autoInjectContext("Use the analysis results to generate content.")

val pipeline = Pipeline()
    .add(analysisPipe)
    .add(generationPipe)  // Automatically receives analysis context
```

### Global Context Updates
```kotlin
val pipe = BedrockPipe()
    .pullGlobalContext()
    .setPageKey("sessionData")
    .autoInjectContext("Use session context for personalized responses.")
    .setTransformationFunction { content ->
        // Update global context with new information
        val sessionContext = ContextBank.getContextFromBank("sessionData")
        sessionContext.contextElements.add("User preference: ${extractPreference(content.text)}")
        
        runBlocking {
            ContextBank.emplaceWithMutex("sessionData", sessionContext)
        }
        
        content
    }
```

### Multi-Page Context Integration
```kotlin
val pipe = BedrockPipe()
    .pullGlobalContext()
    .setPageKey("userProfile, gameState, inventory")  // Multiple context sources
    .autoInjectContext("""
        You have access to:
        - User profile information
        - Current game state
        - Player inventory
        
        Use all context types to provide comprehensive responses.
    """)
```

## Context Injection Timing

### System Prompt Integration (Schema Only)
The context **schema** is automatically injected into the system prompt in this order:

1. **Original system prompt**
2. **Context instructions** (from `autoInjectContext()`)
3. **Context schema** (structure template, not actual data)
4. **Footer prompt** (if set)

```kotlin
val pipe = BedrockPipe()
    .setSystemPrompt("You are an automated security auditor responsible for identifying PII leakage in application logs.")
    .pullGlobalContext()
    .autoInjectContext("The user prompt contains context data in the following schema format.")
    .setFooterPrompt("Always be concise and accurate.")
```

**Final system prompt**:
```
You are an automated security auditor responsible for identifying PII leakage in application logs.

The user prompt contains context data in the following schema format.

{
  "loreBookKeys": {...},
  "contextElements": [...],
  "converseHistory": {...}
}

Always be concise and accurate.
```

### User Prompt Integration (Actual Data)
The actual context **data** is injected into the user prompt:

**User prompt with context data**:
```
User's original input text

{
  "loreBookKeys": {"johnSmith": {"value": "John Smith is a detective...", "weight": 10}},
  "contextElements": ["Always be helpful", "Current date: 2024-01-15"],
  "converseHistory": {"history": [{"role": "user", "content": "Hello"}]}
}
```

### Runtime Context Retrieval and Injection
Context injection happens in two parts during pipe execution:

1. **Pre-execution**: Context is pulled from specified sources
2. **Pre-validation**: Context can be modified by pre-validation functions
3. **Schema injection**: Context schema is injected into system prompt
4. **Data injection**: Actual context data is injected into user prompt
5. **AI call**: Model receives both schema (in system) and data (in user prompt)

## Practical Examples

### Chat Application with Session Context
```kotlin
val chatPipe = BedrockPipe()
    .pullGlobalContext()
    .setPageKey("userSession")
    .autoInjectContext("""
        The user prompt will contain session context data with this structure:
        - loreBookKeys: User preferences and profile information
        - contextElements: Session rules and current state
        - converseHistory: Previous conversation messages
        
        Use the context data from the user prompt to provide personalized responses.
    """)
    .setTransformationFunction { content ->
        // Update session context with new conversation
        val sessionContext = ContextBank.getContextFromBank("userSession")
        sessionContext.converseHistory.add(
            ConverseData(ConverseRole.assistant, content)
        )
        
        runBlocking {
            ContextBank.emplaceWithMutex("userSession", sessionContext)
        }
        
        content
    }
```

### Document Processing with Background Knowledge
```kotlin
val documentProcessor = BedrockPipe()
    .pullGlobalContext()
    .setPageKey("domainKnowledge, processingRules")
    .autoInjectContext("""
        The user prompt contains domain knowledge and processing rules in JSON format:
        - loreBookKeys: Domain-specific terminology and concepts
        - contextElements: Document processing guidelines and rules
        
        Apply the knowledge from the user prompt when analyzing the document.
    """)
    .autoTruncateContext()  // Ensure context fits within token limits
```

### Multi-Stage Pipeline with Context Flow
```kotlin
// Stage 1: Analysis with global context
val analysisPipe = BedrockPipe()
    .pullGlobalContext()
    .setPageKey("backgroundData")
    .autoInjectContext("Use background data for analysis.")
    .updatePipelineContextOnExit()

// Stage 2: Generation with both global and pipeline context
val generationPipe = BedrockPipe()
    .pullPipelineContext()  // Gets analysis results + original global context
    .autoInjectContext("Use analysis results and background data for generation.")

// Stage 3: Validation with fresh global context
val validationPipe = BedrockPipe()
    .pullGlobalContext()
    .setPageKey("validationRules")
    .autoInjectContext("Use validation rules to check the generated content.")

val pipeline = Pipeline()
    .add(analysisPipe)
    .add(generationPipe)
    .add(validationPipe)
```

### Dynamic Context Selection
```kotlin
val adaptivePipe = BedrockPipe()
    .pullGlobalContext()
    .setPreValidationFunction { contextWindow, content ->
        // Dynamically select relevant context based on input
        val inputType = analyzeInputType(content?.text ?: "")
        
        when (inputType) {
            "technical" -> {
                val techContext = ContextBank.getContextFromBank("technicalKnowledge")
                contextWindow.merge(techContext)
            }
            "creative" -> {
                val creativeContext = ContextBank.getContextFromBank("creativeExamples")
                contextWindow.merge(creativeContext)
            }
        }
        
        contextWindow
    }
    .autoInjectContext("Use the relevant context provided for your response type.")
```

## Best Practices

### 1. Clear Context Instructions
```kotlin
// Good: Explain where context data will be and how to use it
.autoInjectContext("""
    The user prompt contains context data in JSON format with:
    - loreBookKeys: Character information with weights
    - contextElements: Important facts and rules
    - converseHistory: Previous conversation history
    
    Use this context data to maintain consistency and accuracy.
""")

// Avoid: Vague instructions that don't explain the schema
.autoInjectContext("Use the context.")
```

### 2. Appropriate Context Sources
```kotlin
// Use pipeline context for related processing stages
.pullPipelineContext()

// Use global context for cross-pipeline information
.pullGlobalContext()
.setPageKey("sharedKnowledge")
```

### 3. Context Budget Management
```kotlin
// Always consider token limits with context injection
.autoTruncateContext()  // Automatically manage context size
.setContextWindowSize(4000)  // Set appropriate limits
```

### 4. Context Updates
```kotlin
// Update context when new information is learned
.setTransformationFunction { content ->
    // Extract and store new information
    val newInfo = extractImportantInfo(content.text)
    updateGlobalContext("sessionData", newInfo)
    content
}
```

Automatic context injection eliminates manual prompt construction while ensuring consistent, relevant context delivery to AI models, enabling sophisticated context-aware applications with minimal code complexity.

## Next Steps

Now that you understand seamless context integration, learn about global context management:

**→ [ContextBank - Global Context Integration](context-bank-integration.md)** - Global context repository
