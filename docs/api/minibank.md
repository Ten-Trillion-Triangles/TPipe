# MiniBank Class API

The `MiniBank` class is a Valve Cluster—a high-capacity container that holds multiple `ContextWindow` instances. It is the primary tool for managing complex infrastructure that draws from several specialized reservoirs simultaneously, keeping their data isolated yet unified for model processing.

```kotlin
@Serializable
data class MiniBank(
    var contextMap: MutableMap<String, ContextWindow> = mutableMapOf()
)
```

## Table of Contents
- [Public Properties](#public-properties)
- [Public Functions](#public-functions)
- [Key Operational Behaviors](#key-operational-behaviors)

## Public Properties

**`contextMap`**
```kotlin
var contextMap: MutableMap<String, ContextWindow> = mutableMapOf()
```
The central registry for the cluster. It maps a Page Key (e.g., `user_settings`) to its corresponding `ContextWindow`. This allows for industrial-grade organization where an agent can access `world_lore`, `character_stats`, and `recent_events` as distinct compartments of knowledge.

---

## Public Functions

### Context Management: Combining Flows

#### `merge(other: MiniBank, emplaceLorebookKeys: Boolean = true, appendKeys: Boolean = false)`
Merges another manifold cluster into this one. It iterates through the other cluster's keys and applies the following logic:

*   **Key Discovery**: If a key exists in the `other` cluster but not in this one, the entire reservoir is added directly.
*   **Key Collision**: If both clusters have the same key, TPipe delegates the work to `ContextWindow.merge()`, applying your specified merge strategy (Emplace, Preserve, or Append).

**Strategy Parameters:**
*   **`emplaceLorebookKeys`**: If true, colliding LoreBook entries are overwritten by the incoming data.
*   **`appendKeys`**: If true, the incoming value is appended to the existing value. This takes priority over the emplace setting.

### Utilities

#### `isEmpty(): Boolean`
Checks if the manifold contains any registered reservoirs. It returns true if the `contextMap` is empty, regardless of whether the individual windows inside have data.

---

## Key Operational Behaviors

### 1. Multi-Domain Isolation
MiniBank is designed to prevent "Context Pollution." By keeping different types of knowledge in separate compartments, you ensure that high-weight entries in `user_profile` don't accidentally crowd out critical instructions in `system_rules` during the selection process.

### 2. Standardized Injection
When a Pipe uses a MiniBank, TPipe injects the data into the User Prompt as a structured JSON object. This structure informs the AI of the "Source" of each piece of data, leading to better reasoning and adherence to role-specific knowledge.

### 3. Dynamic Scalability
Because it is backed by a `MutableMap`, a MiniBank can scale dynamically during a mainline execution. A `DITL Function` can add new specialized reservoirs on-the-fly based on the discoveries made in previous stages of the pipeline.

### 4. Direct Global Integration
MiniBank is the standard interface for "Multi-Page" calls to the **ContextBank**. When you call `.setPageKey("pageA, pageB")`, TPipe automatically constructs a MiniBank to manage the incoming flow from those global reservoirs.
