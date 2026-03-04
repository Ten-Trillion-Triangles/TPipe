# Automatic Context Injection - Seamless Flow

In a complex municipal infrastructure, you don't want to manually haul water from the reservoir to every pipe. You want **Automatic Injection**—a system that seamlessly pumps relevant context, rules, and history into your AI model's prompt without you writing a single line of string concatenation.

TPipe handles the retrieval, formatting, and injection of context data, ensuring your AI always has the "background pressure" it needs to make smart decisions.

## How it Works: The Two-Part Connection

Automatic injection works by splitting context into two distinct fittings:

1.  **The Schema (System Prompt)**: TPipe injects a JSON schema into the system prompt. This teaches the model *how* to read the context it's about to receive.
2.  **The Data (User Prompt)**: The actual context data (LoreBook entries, history, etc.) is injected into the user prompt.

This separation ensures the model understands the structural "rules" while keeping the actual data flow clean and separated from its behavioral instructions.

---

## Context Sources: Where the Water Comes From

You can configure a Pipe to pull its context from several different Reservoirs:

### 1. The Central Reservoir (ContextBank)
Pulls context from the global repository, allowing you to share data across sessions or applications.

```kotlin
val pipe = BedrockPipe()
    .pullGlobalContext()
    .setPageKey("user_profile") // Pull a specific page from the bank
    .autoInjectContext("Use the provided user profile for personalization.")
```

### 2. The Mainline (Pipeline Context)
Pulls context from the parent pipeline. This is the standard way to share "memory" between different stages of a complex workflow.

```kotlin
val pipe = BedrockPipe()
    .pullPipelineContext() // Share memory with other pipes in this pipeline
    .autoInjectContext("Use the research gathered by the previous pipe.")
```

---

## The Injection Command (`autoInjectContext`)

The `autoInjectContext` method is the most important valve in this system. It does two things:
1.  **Enables Injection**: Signals TPipe to perform the automatic pump.
2.  **Sets Instructions**: You provide a natural language explanation of *what* the context is and *how* the model should use it.

```kotlin
pipe.autoInjectContext("""
    The user prompt will contain context data in JSON format:
    - loreBookKeys: Technical specifications for the hardware.
    - contextElements: Safety protocols and operating rules.

    Use this data to answer the user's question accurately.
""")
```

---

## Standard Flow Patterns

### The "Learn and Remember" Pattern
A common pattern is for one pipe to Learn something and store it in the pipeline context for the next pipe to use.

```kotlin
// Valve 1: Research and store
val researchPipe = BedrockPipe()
    .updatePipelineContextOnExit() // Push results to the mainline
    .setTransformationFunction { content ->
        content.context.addLoreBookEntry("facts", content.text)
        content
    }

// Valve 2: Read and generate
val writerPipe = BedrockPipe()
    .pullPipelineContext() // Pull research results
    .autoInjectContext("Use the facts gathered in the research phase.")
```

### Multi-Page Integration
For complex agents, you can draw from multiple specialized reservoirs simultaneously.

```kotlin
val agent = BedrockPipe()
    .setPageKey("legal_codes, case_history, client_info")
    .enableDynamicFill() // Maximize token usage across pages
    .autoInjectContext("You have access to legal codes, past history, and client details.")
```

---

## Best Practices for a Clean Flow

*   **Be Explicit in Instructions**: Don't just say "Use context." Tell the model exactly what the `loreBookKeys` or `contextElements` represent in this specific scenario.
*   **Use Truncation**: Always combine auto-injection with `autoTruncateContext()` to ensure you don't burst the model's token limit.
*   **Security First**: Use **ContextLock** to ensure that sensitive pages aren't accidentally "pumped" into an untrusted agent's context.

---

## Next Steps

Now that you understand how to automate the flow of memory, learn how to manage the global reservoirs.

**→ [ContextBank - Global Context Integration](context-bank-integration.md)** - Global context repository.
