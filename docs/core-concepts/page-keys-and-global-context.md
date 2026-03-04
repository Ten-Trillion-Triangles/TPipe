# Page Keys and Global Context

TPipe's page key system enables organized, targeted context retrieval from the global ContextBank. This allows pipes to access specific types of context while maintaining separation between different use cases and data types.

## Table of Contents
- [What are Page Keys?](#what-are-page-keys)
- [Enabling Global Context](#enabling-global-context)
- [Single Page Key Usage](#single-page-key-usage)
- [Multiple Page Keys](#multiple-page-keys)
- [Page Key vs Default Context](#page-key-vs-default-context)
- [Integration with Pipeline Context](#integration-with-pipeline-context)
- [Practical Examples](#practical-examples)
- [Best Practices](#best-practices)
- [Next Steps](#next-steps)

---

## What are Page Keys?

Page keys are named identifiers that organize context in the ContextBank:
- **Namespace separation**: Keep `user_123_data` separate from `global_security_rules`.
- **Targeted retrieval**: An agent can request exactly one page (e.g., `setPageKey("legal_codes")`).
- **Multi-page support**: An agent can "mix" several reservoirs into one flow (e.g., `setPageKey("user_profile, world_lore")`).

---

## Enabling Global Context

### Basic Global Context Access
```kotlin
val pipe = BedrockPipe()
    .pullGlobalContext()  // Enable global context retrieval
    .autoInjectContext("Use the context data provided in the user prompt.")
```

**What this does**:
- Enables the pipe to pull context from the global ContextBank.
- Uses the default banked context window (if no page key is set).
- Retrieves all available context types (LoreBook, context elements, conversation history).

---

## Single Page Key Usage

### Setting a Single Page Key
```kotlin
val pipe = BedrockPipe()
    .pullGlobalContext()
    .setPageKey("userSession")  // Access specific context page
    .autoInjectContext("Use the user session context from the user prompt.")
```

**What this does**:
- Retrieves context stored under the "userSession" key in ContextBank.
- Ignores other context pages, ensuring targeted, relevant background data.

---

## Multiple Page Keys

Sometimes an agent needs to be a polymath, drawing from multiple sources simultaneously. TPipe allows you to provide a comma-separated list of keys.

```kotlin
val agent = BedrockPipe()
    .pullGlobalContext()
    .setPageKey("user_preferences, hardware_specs, site_map")
    .autoInjectContext("You have access to user settings, hardware data, and the site map.")
```

> [!TIP]
> **Dynamic Fill**: When using multiple keys, always use `.enableDynamicFill()`. This ensures that if one page is small, its unused token budget is automatically transferred to larger pages, maximizing your context reservoir's efficiency.

---

## Page Key vs Default Context

*   **Default Context (No Page Key)**: If you call `pullGlobalContext()` without setting a page key, TPipe pulls from the general reservoir (`ContextBank.getBankedContextWindow()`). This is ideal for shared scratchpads or simple, single-context applications.
*   **Page Key Context**: Setting a key pulls from a specific, isolated vault. This is the industrial standard for production systems.

---

## Integration with Pipeline Context

Both global and pipeline context can be enabled simultaneously.

```kotlin
val pipe = BedrockPipe()
    .pullGlobalContext()      // Pulls from ContextBank
    .setPageKey("userData")   // Specific global context page
    .pullPipelineContext()    // Also pulls pipeline context - both are merged together
    .autoInjectContext("Use both pipeline and global context together.")
```

**Context Merging Priority**:
1.  **Pipeline context** provides the base from previous pipeline stages.
2.  **Global context** is merged in, adding persistent reference data.

---

## Practical Examples

### 1. Dynamic User Sessions
```kotlin
val userPage = "session_${currentUser.id}"
pipe.setPageKey(userPage)
```

### 2. The Shared Knowledge Reservoir
You can have a page that is updated by a "Senior Auditor" and read by "Junior Agents."

```kotlin
// Senior Auditor: Updates the manual
auditorPipe.setTransformationFunction { content ->
    val manual = ContextBank.getContextFromBank("global_manual")
    manual.contextElements.add("New Rule: ${content.text}")
    ContextBank.emplaceWithMutex("global_manual", manual)
    content
}
```

---

## Best Practices

*   **Be Descriptive**: Use names like `client_abc_history` instead of `data1`.
*   **Logical Grouping**: Group related data (e.g., `user_settings` and `user_history`) into a single page unless you have a reason to isolate them.
*   **Lifecycle Control**: Remember to clear or archive old page keys (reservoirs) to keep your storage performant.
*   **Conditional Selection**: Use `setPreValidationFunction` to dynamically decide which additional global pages to merge into your current context window based on the user's input.

---

## Next Steps

Now that you can organize your reservoirs, learn how to manage complex, multi-context scenarios using the MiniBank.

**→ [MiniBank and Multiple Page Keys](minibank-and-multiple-page-keys.md)** - Multi-context management.
