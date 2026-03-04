# Page Keys and Global Context - Organizing the Reservoir

In a large-scale agentic infrastructure, you don't just have one massive pool of data; you have organized **Reservoirs**. **Page Keys** are the named identifiers that allow you to partition your global **ContextBank**, ensuring that agents only pull the specific data they need for their current task.

This prevents "Mainline Pollution," where an agent is overwhelmed by irrelevant information, and ensures that user data, session history, and domain knowledge are kept separate and secure.

## What are Page Keys?

Think of a Page Key as a Valve ID for a specific section of the ContextBank.
- **Namespace separation**: Keep `user_123_data` separate from `global_security_rules`.
- **Targeted retrieval**: An agent can request exactly one page (e.g., `setPageKey("legal_codes")`).
- **Multi-page support**: An agent can "mix" several reservoirs into one flow (e.g., `setPageKey("user_profile, world_lore")`).

---

## Accessing the Reservoir: Single Page Keys

The most common pattern is to give an agent access to a single, specialized page of knowledge.

```kotlin
val agent = BedrockPipe()
    .pullGlobalContext()
    .setPageKey("industrial_safety_manual")
    .autoInjectContext("Use the safety manual to validate the procedure.")
```

**What happens here?**
1.  TPipe goes to the `ContextBank`.
2.  It finds the page named `industrial_safety_manual`.
3.  It "pumps" the LoreBook and history from that page into the agent's prompt.

---

## Complex Flows: Multiple Page Keys

Sometimes an agent needs to be a "Polymath," drawing from multiple sources simultaneously. TPipe allows you to provide a comma-separated list of keys.

```kotlin
val agent = BedrockPipe()
    .pullGlobalContext()
    .setPageKey("user_preferences, hardware_specs, site_map")
    .autoInjectContext("You have access to user settings, hardware data, and the site map.")
```

> [!TIP]
> **Dynamic Fill**: When using multiple keys, always use `.enableDynamicFill()`. This ensures that if one page is small, its unused token budget is automatically transferred to larger pages, maximizing your context reservoir's efficiency.

---

## Page Key Management Patterns

### 1. Dynamic User Sessions
In a multi-user application, you can use unique page keys for every user to ensure their history never leaks to someone else.

```kotlin
val userPage = "session_${currentUser.id}"
pipe.setPageKey(userPage)
```

### 2. The "Shared Knowledge" Reservoir
You can have a page that is read-only for most agents but updated by a "Senior Auditor" agent.

```kotlin
// Senior Auditor: Updates the manual
auditorPipe.setTransformationFunction { content ->
    val manual = ContextBank.getContextFromBank("global_manual")
    manual.contextElements.add("New Rule: ${content.text}")
    ContextBank.emplaceWithMutex("global_manual", manual)
    content
}

// Junior Agent: Reads the manual
juniorPipe.setPageKey("global_manual").pullGlobalContext()
```

---

## Page Key vs. Default Context

*   **Default Context**: If you call `pullGlobalContext()` without setting a page key, TPipe pulls from the "General Reservoir" (`ContextBank.getBankedContextWindow()`). This is great for simple apps or shared scratchpads.
*   **Page Key Context**: Setting a key pulls from a specific, isolated vault. This is the industrial standard for production systems.

---

## Best Practices

*   **Be Descriptive**: Use names like `client_abc_history` instead of `data1`.
*   **Logical Grouping**: Group related data into a single page unless you have a reason to split them (e.g., separate `character_bio` from `world_rules`).
*   **Lifecycle Control**: Remember to clear or archive old page keys (reservoirs) to keep your storage "clean" and performant.

---

## Next Steps

Now that you can organize your reservoirs, learn how to manage complex, multi-context scenarios using the MiniBank.

**→ [MiniBank and Multiple Page Keys](minibank-and-multiple-page-keys.md)** - Multi-context management.
