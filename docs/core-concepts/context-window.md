# ContextWindow - Memory Storage and Retrieval

The `ContextWindow` is the primary memory system of TPipe. It acts as a **Reservoir**—a centralized place to store and manage information before it is pumped into a model's limited context space. By intelligently filtering and weighting this data, the `ContextWindow` ensures that the AI always has the most relevant background knowledge without exceeding its token limits.

```kotlin
@Serializable
data class ContextWindow(
    var loreBookKeys: MutableMap<String, LoreBook> = mutableMapOf(),
    var contextElements: MutableList<String> = mutableListOf(),
    var converseHistory: ConverseHistory = ConverseHistory(),
    var contextSize: Int = 8000
)
```

## Table of Contents
- [The Reservoir Components](#the-reservoir-components)
- [1. LoreBook: Intelligent Data Selection](#1-lorebook-intelligent-data-selection)
- [2. Context Elements: Persistent Knowledge](#2-context-elements-persistent-knowledge)
- [3. Conversation History: Multi-Turn Logs](#3-conversation-history-multi-turn-logs)
- [How Context Selection Works](#how-context-selection-works)
- [Basic Operations](#basic-operations)
- [Merging Reservoirs](#merging-reservoirs)
- [Next Steps](#next-steps)

---

## The Reservoir Components

A `ContextWindow` is a structured container for three distinct types of data:

1.  **LoreBook Entries**: The **Strategic Reserves**. Key-value pairs that are only injected when relevant.
2.  **Context Elements**: The **Constant Flow**. Raw strings that are always present in the context.
3.  **Conversation History**: The **Stream Record**. A structured log of the interaction between roles.

---

## 1. LoreBook: Intelligent Data Selection

The LoreBook is TPipe's most advanced memory tool. It allows you to store massive amounts of information that is only "opened" and injected when relevant to the current conversation.

### How it Works
*   **Keys & Aliases**: You define a primary key (e.g., `valve_maintenance`) and multiple aliases (e.g., `wrench`, `gasket`).
*   **Triggering**: If the user's input contains any of these aliases, the entry is automatically added to the context.
*   **Weighting**: Higher-weighted entries receive priority when the token budget is limited.
*   **Linked Keys**: When one entry is selected, its linked keys are also attempted for inclusion.

```kotlin
contextWindow.addLoreBookEntry(
    key = "industrial_pump_v2",
    value = "The V2 Pump requires high-pressure valves and 480V power.",
    weight = 10,
    aliasKeys = listOf("pump", "v2", "hardware"),
    requiredKeys = listOf("police_department"), // Dependencies
    linkedKeys = listOf("sarah_jones", "case_files") // Related entries
)
```

---

## 2. Context Elements: Persistent Knowledge

Use `contextElements` for information that is critical to the agent's identity or the current environment. These strings are treated as Constant Flow and are included in every request unless the reservoir overflows.

```kotlin
contextWindow.contextElements.add("Always use metric units.")
contextWindow.contextElements.add("The operator on duty is: Sarah Jenkins.")
```

---

## 3. Conversation History: Multi-Turn Logs

This component stores the actual dialogue stream. It supports full multimodal data, maintaining the flow and context between the user and the assistant.

```kotlin
contextWindow.converseHistory.add(
    ConverseData(
        role = ConverseRole.user,
        content = MultimodalContent("Check the pressure on Valve 4.")
    )
)
```

---

## How Context Selection Works

### Automatic LoreBook Selection
When processing input text, the ContextWindow:
1.  **Scans for keywords**: Finds LoreBook keys and aliases that match the input.
2.  **Checks dependencies**: Ensures all `requiredKeys` are also present.
3.  **Applies weighting**: Prioritizes higher-weight entries for injection.
4.  **Manages budget**: Fits as many selected entries as possible within the available space.

### Token Budget Distribution
When multiple content types are present, TPipe automatically balances the flow:
- **LoreBook only**: Gets 100% of the token budget.
- **LoreBook + Elements**: 50/50 split.
- **All three types**: 33/33/33 split. Remaining tokens from unused allocations are redistributed to the LoreBook.

### Truncation Methods
*   **TruncateTop**: Removes the oldest messages in the stream first.
*   **TruncateBottom**: Removes the most recent content first.
*   **TruncateMiddle**: Preserves the start (instructions) and the end (latest context), dropping the middle.

```kotlin
contextWindow.selectAndTruncateContext(
    text = "Current input",
    totalTokenBudget = 4000,
    truncateSettings = ContextWindowSettings.TruncateTop
)
```

---

## Basic Operations

### Managing Context
```kotlin
val contextWindow = ContextWindow()

// Add LoreBook entry
contextWindow.addLoreBookEntry("character", "Description", weight = 10)

// Check if empty
if (contextWindow.isEmpty()) println("No context stored")

// Clear all context
contextWindow.clear()
```

### Context Retrieval
```kotlin
// Find matching keys for input
val matchingKeys = contextWindow.findMatchingLoreBookKeys("Tell me about John")

// Get truncated context
val result = contextWindow.selectAndTruncateContext(
    text = "User input",
    totalTokenBudget = 4000,
    truncateSettings = ContextWindowSettings.TruncateTop
)
```

---

## Merging Reservoirs

You can pump data from one reservoir into another using the `merge()` function.

```kotlin
// Advanced merge with conflict resolution
contextWindow1.merge(
    other = contextWindow2,
    emplaceLoreBookKeys = true,      // Replace existing entries
    appendKeys = false,              // Don't append to existing entries
    emplaceConverseHistory = true,   // Enable history merging
    onlyEmplaceIfNull = true        // Only copy history if target is empty
)
```

---

## Next Steps

Now that you understand the reservoir, learn how to manage the total volume of that data.

**→ [Context and Tokens - Token Management](context-and-tokens.md)** - Understanding token usage and limits.
