# MiniBank and Multiple Page Keys

MiniBank is TPipe's solution for handling multiple page keys simultaneously. When a pipe needs to access multiple context pages from ContextBank, MiniBank provides organized storage and management of these separate context sources.

## Table of Contents
- [What is MiniBank?](#what-is-minibank)
- [When MiniBank is Used](#when-minibank-is-used)
- [Structure and Injection](#structure-and-injection)
- [MiniBank Operations](#minibank-operations)
- [Pre-Validation with MiniBank](#pre-validation-with-minibank)
- [Context Truncation with MiniBank](#context-truncation-with-minibank)
- [Practical Examples](#practical-examples)
- [Best Practices](#best-practices)
- [Next Steps](#next-steps)

---

## What is MiniBank?

MiniBank is a structured container that holds multiple `ContextWindow` instances, each keyed to a specific name. It acts as a valve cluster—a manifold that connects several separate tanks (e.g., User Data, World Rules, Inventory) into a single flow for the agent.

```kotlin
@Serializable
data class MiniBank(
    var contextMap: MutableMap<String, ContextWindow> = mutableMapOf()
)
```

---

## When MiniBank is Used

TPipe handles this automatically based on your page key configuration:
*   **Single Page Key**: If you call `.setPageKey("user_data")`, TPipe uses a standard `ContextWindow`.
*   **Multiple Page Keys**: If you call `.setPageKey("user_data, world_rules, inventory")`, TPipe automatically initializes a **MiniBank** to keep those reservoirs distinct.

---

## Structure and Injection

When you use MiniBank, data maintains its structure, helping the AI understand the category of information it is receiving.

**System Prompt (The Blueprint)**:
TPipe injects a JSON schema into the system prompt that explains the `contextMap` structure and identifies the available page keys.

**User Prompt (The Data Flow)**:
The actual data is injected as a structured JSON object:
```json
{
  "contextMap": {
    "userProfile": { "loreBookKeys": {...}, "contextElements": ["..."] },
    "gameState": { "loreBookKeys": {...}, "contextElements": ["..."] }
  }
}
```

---

## MiniBank Operations

### Automatic Population
TPipe automatically populates the MiniBank when multiple keys are used by retrieving each page from the `ContextBank`.

### Manual Operations
```kotlin
// Create and populate manually
val miniBank = MiniBank()
miniBank.contextMap["userSession"] = ContextBank.getContextFromBank("userSession")
miniBank.contextMap["gameData"] = ContextBank.getContextFromBank("gameData")

// Merge clusters
miniBank.merge(otherMiniBank, emplaceLorebookKeys = true)
```

---

## Pre-Validation with MiniBank

MiniBank provides a specialized hook, `setPreValidationMiniBankFunction`, allowing you to inspect and modify each page in the cluster before it reaches the AI.

```kotlin
val agent = BedrockPipe()
    .setPageKey("user_profile, safety_rules")
    .setPreValidationMiniBankFunction { miniBank, content ->
        miniBank.contextMap.forEach { (key, reservoir) ->
            if (key == "user_profile") {
                reservoir.contextElements.add("Last Login: ${now()}")
            }
        }
        miniBank
    }
```

---

## Context Truncation with MiniBank

When the cluster is fuller than the model can handle, the budget must be distributed across all pages.

> [!IMPORTANT]
> **Advanced Balancing**: To optimize tokens across multiple pages, you MUST use `TokenBudgetSettings`. Simple `autoTruncateContext()` will chop each page independently, potentially wasting space.

**Multi-Page Strategies** (Requires TokenBudgetSettings):
*   **DYNAMIC_FILL (Default)**: Redistributes unused tokens from lean pages to overflowing pages, maximizing information flow.
*   **EQUAL_SPLIT**: Divides the budget evenly across all pages.
*   **PRIORITY_FILL**: Fills pages sequentially in the order they were defined.

---

## Practical Examples

### Multi-User Game System
```kotlin
val gamePipe = BedrockPipe()
    .setPageKey("playerStats, worldState, questLog")
    .setPreValidationMiniBankFunction { miniBank, content ->
        val action = extractAction(content?.text ?: "")
        when (action) {
            "move" -> miniBank.contextMap["worldState"]?.contextElements?.add("Moved to Sector 4")
            "levelUp" -> miniBank.contextMap["playerStats"]?.addLoreBookEntry("Level", "5", weight = 10)
        }
        miniBank
    }
```

---

## Best Practices

*   **Group Logically**: Don't create dozens of tiny page keys. Group related info (e.g., `user_settings` and `user_history`) into one page unless they need different security locks.
*   **Clear Instructions**: When using `autoInjectContext()`, tell the model exactly what each page in the `contextMap` represents.
*   **Use Dynamic Fill**: In production, always use `DYNAMIC_FILL` to prevent token starvation in larger context pages.
*   **Check for Empty**: Use `miniBank.isEmpty()` to verify if any page keys were successfully loaded before processing.

---

## Next Steps

Now that you can manage complex clusters of memory, learn how to share that memory across an entire mainline.

**→ [Pipeline Context Integration](pipeline-context-integration.md)** - Context sharing within pipelines.
