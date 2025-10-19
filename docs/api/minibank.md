# MiniBank Class API

## Table of Contents
- [Overview](#overview)
- [Public Properties](#public-properties)
- [Public Functions](#public-functions)
  - [Context Management](#context-management)
  - [Utilities](#utilities)

## Overview

The `MiniBank` class provides multi-page context storage for scenarios requiring separation of multiple context sources while maintaining organized access to different context domains.

```kotlin
@Serializable
data class MiniBank(
    var contextMap: MutableMap<String, ContextWindow> = mutableMapOf()
)
```

## Public Properties

**`contextMap`**
```kotlin
var contextMap: MutableMap<String, ContextWindow> = mutableMapOf()
```
Map storing multiple ContextWindow objects keyed by page identifiers. Enables organized separation of different context types such as story chapters, character information, world-building details, or any other domain-specific context that needs to be kept distinct yet accessible.

## Public Functions

### Context Management

#### `merge(other: MiniBank, emplaceLorebookKeys: Boolean = true, appendKeys: Boolean = false)`
Merges another MiniBank into this one, combining context windows by matching keys.

**Behavior:** 

**Key Matching Logic:**
- **Existing keys**: Merges ContextWindows using ContextWindow.merge() with specified parameters
- **New keys**: Adds ContextWindow directly to this MiniBank's contextMap
- **Empty other**: No operation performed if other MiniBank is empty

**Merge Strategy Parameters:**
- **`emplaceLorebookKeys = true`**: Existing lorebook entries are replaced with new values during ContextWindow merge
- **`emplaceLorebookKeys = false`**: Existing lorebook entries are preserved, new entries only added if keys don't exist
- **`appendKeys = true`**: Lorebook values are appended to existing entries rather than replaced (overrides emplaceLorebookKeys behavior)
- **`appendKeys = false`**: Uses emplaceLorebookKeys setting for merge behavior

**Context Window Merge Delegation:**
Each matched ContextWindow pair is merged using ContextWindow's own merge logic, which handles:
- Lorebook entry merging based on parameters
- Context element list combination
- Conversation history merging
- Metadata preservation and combination

---

### Utilities

#### `isEmpty(): Boolean`
Checks if the MiniBank contains any context data.

**Behavior:** Returns true if contextMap is empty (no page keys stored), false if any ContextWindow pages exist. Does not check whether individual ContextWindows contain data - only verifies presence of page keys.

## Key Behaviors

### Multi-Domain Context Organization
MiniBank enables separation of context into logical domains while maintaining unified access. Common use cases include:
- **Story Writing**: Separate pages for different chapters, character sheets, world-building
- **Documentation**: Different pages for API references, tutorials, examples
- **Conversations**: Separate pages for different conversation threads or participants
- **Project Context**: Different pages for requirements, design docs, implementation notes

### Page Key Management
Page keys are arbitrary strings that identify context domains. No restrictions on key format, enabling flexible organization schemes:
- Hierarchical keys: "chapter1", "chapter2", "characters"
- Descriptive keys: "main_story", "character_backgrounds", "world_lore"
- Functional keys: "system_context", "user_preferences", "session_data"

### Context Isolation and Sharing
Each page maintains independent context that can be:
- **Isolated**: Accessed individually without interference from other pages
- **Combined**: Merged selectively based on application needs
- **Shared**: Multiple components can access the same page keys

### Merge Strategy Flexibility
The merge function provides multiple strategies for handling conflicting data:
- **Replace Strategy**: New data overwrites existing data (emplaceLorebookKeys = true)
- **Preserve Strategy**: Existing data is kept, new data only added if no conflicts (emplaceLorebookKeys = false)
- **Append Strategy**: New data is appended to existing data (appendKeys = true)

### Integration with Global Context
MiniBank works seamlessly with TPipe's global context system:
- Can be populated from multiple ContextBank pages
- Supports complex context scenarios requiring multiple data sources
- Enables fine-grained control over context composition and priority

### Memory Efficiency
As a data class, MiniBank supports efficient copying and serialization. The map-based storage provides O(1) access to individual context pages while maintaining reasonable memory usage for typical context scenarios.
