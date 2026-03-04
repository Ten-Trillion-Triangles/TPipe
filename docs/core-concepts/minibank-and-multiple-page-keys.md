# MiniBank and Multiple Page Keys - The Valve Cluster

In a basic setup, an agent might draw from a single reservoir of memory. But as your infrastructure grows, your agents will need to become specialized polymaths—drawing from a **Valve Cluster** of different specialized reservoirs simultaneously. **MiniBank** is the container that allows TPipe to manage multiple **Page Keys** at once, keeping your data organized, isolated, and efficiently "pumped" into the model.

Think of MiniBank as the manifold that connects several separate tanks (User Data, World Rules, Inventory) into a single flow for the agent.

## What is MiniBank?

MiniBank is a structured container that holds multiple `ContextWindow` instances, each keyed to a specific name.

```kotlin
@Serializable
data class MiniBank(
    var contextMap: MutableMap<String, ContextWindow> = mutableMapOf()
)
```

---

## When is MiniBank Used?

TPipe is smart enough to handle this automatically:
*   **Single Page Key**: If you call `.setPageKey("user_data")`, TPipe uses a standard `ContextWindow`.
*   **Multiple Page Keys**: If you call `.setPageKey("user_data, world_rules, inventory")`, TPipe automatically initializes a **MiniBank** to keep those reservoirs distinct.

---

## Structure and Injection: How the Flow Looks

When you use MiniBank, the data isn't just flattened into a single list. It maintains its structure, which helps the AI understand the "Category" of information it's receiving.

### The User Prompt (The Data Flow)
The actual data is injected into the user prompt as a structured JSON object:

```json
{
  "contextMap": {
    "userProfile": { "loreBookKeys": {...}, "contextElements": ["..."] },
    "gameState": { "loreBookKeys": {...}, "contextElements": ["..."] }
  }
}
```

### The System Prompt (The Manifold Blueprint)
TPipe automatically tells the AI *how* to read this manifold by injecting the schema and instructions into the system prompt.

---

## Pre-Validation: Scrubbing Individual Lines

Sometimes you need to clean one reservoir but not another. MiniBank provides a specialized hook, `setPreValidationMiniBankFunction`, which allows you to inspect and modify each page in the cluster before it reaches the AI.

```kotlin
val agent = BedrockPipe()
    .setPageKey("user_profile, safety_rules")
    .setPreValidationMiniBankFunction { miniBank, content ->
        // Process each line separately
        miniBank.contextMap.forEach { (key, reservoir) ->
            if (key == "user_profile") {
                reservoir.contextElements.add("Current Time: ${now()}")
            }
        }
        miniBank
    }
```

---

## Token Truncation: Balancing the Pressure

When your cluster of reservoirs is fuller than the model can handle, you need a way to Drain them fairly.

> [!IMPORTANT]
> **Advanced Balancing**: To optimize tokens across multiple pages, you MUST use `TokenBudgetSettings`. Simple auto-truncation will just chop each page independently, potentially wasting space.

### Multi-Page Strategies:
*   **DYNAMIC_FILL (Default)**: The smartest strategy. It sees which pages are "half empty" and gives their unused token budget to the "overflowing" pages, ensuring the most information possible gets through.
*   **EQUAL_SPLIT**: Divides the budget exactly evenly across all pages (e.g., 4 pages get 25% each).

---

## Best Practices

*   **Group Logically**: Don't create 50 separate page keys for tiny bits of data. Group related info (e.g., `user_settings` and `user_history`) into one page unless they need different security locks or truncation rules.
*   **Clear Instructions**: When using `autoInjectContext()`, tell the model exactly what each page in the `contextMap` represents.
*   **Use Dynamic Fill**: In production, always use `DYNAMIC_FILL` to prevent "token starvation" in your larger reservoirs.

---

## Next Steps

Now that you can manage complex clusters of memory, learn how to share that memory across an entire mainline.

**→ [Pipeline Context Integration](pipeline-context-integration.md)** - Context sharing within pipelines.
