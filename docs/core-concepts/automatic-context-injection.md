# Automatic Context Injection

TPipe provides powerful automatic context injection capabilities that seamlessly integrate background knowledge into AI model prompts. This system handles context retrieval, formatting, and injection without requiring manual string manipulation, ensuring your AI always has the background data it needs to make accurate decisions.

## Table of Contents
- [What is Automatic Context Injection?](#what-is-automatic-context-injection)
- [Context Sources](#context-sources)
- [Context Injection Methods](#context-injection-methods)
- [Operational Timing](#operational-timing)
- [Context Flow Patterns](#context-flow-patterns)
- [Practical Examples](#practical-examples)
- [Best Practices](#best-practices)
- [Next Steps](#next-steps)

---

## What is Automatic Context Injection?

Automatic context injection is a system that:
- **Retrieves context** from the global ContextBank or pipeline context.
- **Injects context schema** into the system prompt to teach the AI the expected structure.
- **Injects actual context data** into the user prompt as a JSON object.
- **Manages context flow** between pipes and processing stages.

The system prompt receives the schema and instructions on how to use it, while the user prompt receives the actual context data. This separation ensures the AI understands the context structure while keeping the data flow clean and separated from behavioral instructions.

---

## Context Sources

You can configure a Pipe to pull its context from several different sources:

### 1. Global Context (ContextBank)
Pulls context from the global repository, allowing you to share data across separate sessions or applications.

```kotlin
val pipe = BedrockPipe()
    .pullGlobalContext()
    .setPageKey("user_profile") // Pull a specific page from the bank
    .autoInjectContext("Use the provided user profile for personalization.")
```

### 2. Pipeline Context
Pulls context from the shared memory of the parent pipeline. This is the standard way to share discoveries between different stages of a complex workflow.

```kotlin
val pipe = BedrockPipe()
    .pullPipelineContext() // Share memory with other pipes in this mainline
    .autoInjectContext("Use the research gathered by the previous pipe.")
```

### 3. Page-Specific Context
Supports multiple page keys for complex context scenarios where an agent acts as a polymath.

```kotlin
val pipe = BedrockPipe()
    .pullGlobalContext()
    .setPageKey("user_session, game_state, preferences")
    .autoInjectContext("Use all provided context types.")
```

---

## Context Injection Methods

### Basic Auto-Injection
```kotlin
val pipe = BedrockPipe()
    .pullGlobalContext()
    .autoInjectContext("The user prompt will contain context data in the following JSON schema. Use this context to provide accurate responses.")
```

**System Prompt Result (Schema Only)**:
```text
[Your original system prompt]

The user prompt will contain context data in the following JSON schema. Use this context to provide accurate responses.

{
  "loreBookKeys": {...},
  "contextElements": [...],
  "converseHistory": {...}
}
```

**User Prompt Result (Actual Data)**:
```text
[User's actual input text]

{
  "loreBookKeys": {"characterName": {"value": "John is a detective...", "weight": 5}},
  "contextElements": ["Important rule: Always be helpful"],
  "converseHistory": {"history": [...]}
}
```

### Advanced Context Instructions
You can provide detailed instructions to the AI on how to weigh and interpret the incoming context.
```kotlin
val pipe = BedrockPipe()
    .pullGlobalContext()
    .autoInjectContext("""
        The user prompt contains context data with this structure:
        - loreBookKeys: Character and world information with weights.
        - contextElements: Important facts and rules as strings.
        - converseHistory: Previous conversation history.

        Use this context data to provide accurate, consistent responses.
    """)
```

---

## Operational Timing

Context injection happens in a specific sequence during pipe execution:
1.  **Pre-execution**: Context is pulled from the specified sources (Bank or Pipeline).
2.  **Pre-validation**: Context can be modified by `setPreValidationFunction` hooks.
3.  **Schema injection**: The structural blueprint is added to the system prompt.
4.  **Data injection**: Actual context data is appended to the user's input.
5.  **AI call**: The model receives both the blueprint and the data in a single turn.

---

## Context Flow Patterns

### Sequential Learning
One pipe can learn something and store it in the pipeline context for the next pipe to use.

```kotlin
// Valve 1: Research and store
val researchPipe = BedrockPipe()
    .updatePipelineContextOnExit()
    .setTransformationFunction { content ->
        content.context.addLoreBookEntry("findings", content.text)
        content
    }

// Valve 2: Read and generate
val writerPipe = BedrockPipe()
    .pullPipelineContext()
    .autoInjectContext("Use the findings gathered in the research phase.")
```

### Global Context Updates
```kotlin
val pipe = BedrockPipe()
    .pullGlobalContext()
    .setPageKey("sessionData")
    .setTransformationFunction { content ->
        val sessionContext = ContextBank.getContextFromBank("sessionData")
        sessionContext.contextElements.add("User preference: ${extract(content.text)}")
        runBlocking { ContextBank.emplaceWithMutex("sessionData", sessionContext) }
        content
    }
```

---

## Practical Examples

### Chat Application with Session Context
```kotlin
val chatPipe = BedrockPipe()
    .pullGlobalContext()
    .setPageKey("userSession")
    .autoInjectContext("""
        The user prompt contains session context:
        - loreBookKeys: User preferences and profile.
        - contextElements: Session rules and current state.
        - converseHistory: Previous messages.
    """)
    .setTransformationFunction { content ->
        val sessionContext = ContextBank.getContextFromBank("userSession")
        sessionContext.converseHistory.add(ConverseRole.assistant, content)
        runBlocking { ContextBank.emplaceWithMutex("userSession", sessionContext) }
        content
    }
```

### Dynamic Context Selection
```kotlin
val adaptivePipe = BedrockPipe()
    .pullGlobalContext()
    .setPreValidationFunction { contextWindow, content ->
        val inputType = analyzeInput(content?.text ?: "")
        when (inputType) {
            "technical" -> contextWindow.merge(ContextBank.getContextFromBank("technical_manual"))
            "creative" -> contextWindow.merge(ContextBank.getContextFromBank("style_guide"))
        }
        contextWindow
    }
```

---

## Best Practices

*   **Be Explicit**: Clearly explain where the context data will be (the user prompt) and how the model should interpret the `loreBookKeys` vs `contextElements`.
*   **Use Truncation**: Always combine auto-injection with `autoTruncateContext()` to ensure context doesn't exceed the model's token capacity.
*   **Security First**: Use **ContextLock** to ensure that sensitive pages aren't accidentally pumped into an untrusted agent's context.
*   **Context Budget Management**: Set appropriate `setContextWindowSize()` limits when using large reservoirs.

---

## Next Steps

Now that you understand how to automate the flow of memory, learn how to manage the global reservoirs.

**→ [ContextBank - Global Context Integration](context-bank-integration.md)** - Global context repository.
