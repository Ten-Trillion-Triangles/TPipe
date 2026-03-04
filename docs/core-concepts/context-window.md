# ContextWindow - The Memory Reservoir

The `ContextWindow` is the primary memory system of TPipe. It acts as a **Reservoir**—a centralized place to store and manage information before it is pumped into a model's limited context space. By intelligently filtering and weighting this data, the `ContextWindow` ensures that the AI always has the most relevant background knowledge without exceeding its token limits.

## The Reservoir Components

A `ContextWindow` is a structured container for three distinct types of data:

1.  **LoreBook Entries**: The **Strategic Reserves**. These are key-value pairs that are only injected into the flow when the model's input matches specific keywords or aliases.
2.  **Context Elements**: The **Constant Flow**. These are raw strings (rules, instructions, environment data) that are always present in the context until the reservoir reaches capacity.
3.  **Conversation History**: The **Stream Record**. A structured, chronologically ordered log of the interaction between roles (User, Assistant, Agent, etc.).

```kotlin
@Serializable
data class ContextWindow(
    var loreBookKeys: MutableMap<String, LoreBook> = mutableMapOf(),
    var contextElements: MutableList<String> = mutableListOf(),
    var converseHistory: ConverseHistory = ConverseHistory(),
    var contextSize: Int = 8000 // Total capacity in tokens
)
```

---

## 1. LoreBook: Intelligent Data Selection

The LoreBook is TPipe's most advanced memory tool. It allows you to store massive amounts of information that is only "opened" and injected when relevant to the current conversation.

### How it Works
*   **Keys & Aliases**: You define a primary key (e.g., `valve_maintenance`) and multiple aliases (e.g., `wrench`, `gasket`, `tighten`).
*   **Triggering**: If the user's input contains any of these aliases, the associated value is automatically added to the model's context.
*   **Weighting**: Entries are assigned weights. If the token budget is tight, higher-weighted entries are prioritized for injection.
*   **Linked Keys**: You can link entries together. If the `Pump` entry is triggered, the `Power Supply` entry can be automatically pulled in as well.

```kotlin
contextWindow.addLoreBookEntry(
    key = "industrial_pump_v2",
    value = "The V2 Pump requires high-pressure valves and 480V power.",
    weight = 10,
    aliasKeys = listOf("pump", "v2", "hardware")
)
```

---

## 2. Context Elements: Persistent Knowledge

Use `contextElements` for information that is critical to the agent's identity or the current environment. These strings are treated as Constant Flow and are included in every request unless the reservoir overflows.

```kotlin
contextWindow.contextElements.add("The current facility status is: Operational.")
contextWindow.contextElements.add("Safety protocols: Standard Industrial (OSHA).")
```

---

## 3. Conversation History: Multi-Turn Logs

This component stores the actual dialogue stream. It supports full multimodal data, meaning it can store not just text, but also images and document references that were previously processed in the mainline.

---

## Managing the Flow: Selection & Truncation

Modern AI models have a fixed intake capacity (the context window). If your reservoir is fuller than the model can handle, TPipe uses a deterministic **Selection and Truncation** process to manage the volume.

### Automatic Budgeting
When you call `selectAndTruncateContext()`, TPipe balances the token budget based on the content type:
*   **LoreBook** entries that matched keywords are prioritized.
*   **Conversation History** is preserved starting from the most recent turns.
*   **Context Elements** are allocated guaranteed space.

### Truncation Methods
*   **TruncateTop**: Removes the oldest messages in the stream first.
*   **TruncateBottom**: Removes the most recent content first (rarely used).
*   **TruncateMiddle**: Preserves the instructions at the start and the most recent context at the end, dropping the data in the middle.

```kotlin
contextWindow.selectAndTruncateContext(
    text = "Current input to analyze",
    totalTokenBudget = 4000,
    truncateSettings = ContextWindowSettings.TruncateTop
)
```

---

## Synchronizing Reservoirs: Merging

You can pump data from one reservoir into another using the `merge()` function. This is vital for migrating state between different stages of a pipeline or initializing a new session from a pre-configured knowledge template.

```kotlin
// Merge a specialized knowledge base into the active reservoir
activeContext.merge(
    other = knowledgeBase,
    emplaceLoreBookKeys = true,
    appendKeys = true
)
```

---

## Next Steps

Now that you can store data, learn how to manage the total volume of that data to avoid bursting your model's limits.

**→ [Context and Tokens - Token Management](context-and-tokens.md)** - Understanding token usage and limits.
