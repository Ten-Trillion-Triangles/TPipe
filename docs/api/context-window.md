# ContextWindow Class API

The `ContextWindow` class is the **Memory Reservoir**. It acts as the primary memory system for an agent, holding the "state" (LoreBooks, raw context, and conversation history) and intelligently managing how that data is filtered and "pumped" into the model's limited context space.

```kotlin
@Serializable
data class ContextWindow(@Transient val cinit: Boolean = false)
```

## Table of Contents
- [Public Properties](#public-properties)
- [Public Functions](#public-functions)
  - [LoreBook Management](#lorebook-management)
  - [Context Selection](#context-selection)
  - [Context Merging](#context-merging)
  - [Truncation](#truncation)
  - [Conversation Management](#conversation-management)
  - [Access Control](#access-control)
  - [Utilities](#utilities)

## Public Properties

**`loreBookKeys`**
```kotlin
var loreBookKeys: MutableMap<String, LoreBook> = mutableMapOf()
```
The **Strategic Reserves**. Key-based information that is only pumped into the flow when it's relevant.

**`contextElements`**
```kotlin
var contextElements: MutableList<String> = mutableListOf()
```
The **Constant Flow**. Raw context strings that are always included in the prompt until truncated.

**`converseHistory`**
```kotlin
var converseHistory: ConverseHistory = ConverseHistory()
```
The **Stream Record**. A structured log of the conversation between the user and the agent.

**`contextSize`**
```kotlin
var contextSize: Int = 8000
```
The total capacity (in tokens) of this reservoir.

## Public Functions

### LoreBook Management: Building the Reserves

#### `addLoreBookEntry(key: String, value: String, weight: Int = 0, linkedKeys: List<String> = listOf(), aliasKeys: List<String> = listOf(), requiredKeys: List<String> = listOf())`
Adds a new entry to the LoreBook with comprehensive metadata for smart selection.

#### `findLoreBookEntry(key: String): LoreBook?`
Finds an entry by its key or any of its aliases (case-insensitive).

#### `cleanLorebook(bannedChars: String = "", replaceBannedCharWith: String = "")`
Scours the LoreBook keys to remove or replace "illegal" characters, ensuring they don't break downstream logic.

---

### Context Selection: Pumping Relevant Data

#### `findMatchingLoreBookKeys(text: String): List<String>`
Scans the input text for any matches against LoreBook keys or aliases.

#### `selectLoreBookContext(text: String, maxTokens: Int, ...): List<String>`
The core selection algorithm. It identifies relevant keys, expands linked keys, checks dependencies, and selects the best entries that fit within the token budget.

#### `selectAndFillLoreBookContext(text: String, maxTokens: Int, ...): List<String>`
Similar to the above, but adds a **Fill Phase** to ensure every available token in the budget is used by adding non-matching entries (sorted by weight) after the priority matches are in.

---

### Context Merging: Combining Reservoirs

#### `merge(other: ContextWindow, emplaceLoreBookKeys: Boolean = true, appendKeys: Boolean = false)`
Merges another reservoir into this one. Supports replacing existing entries (`emplace`), appending new text to existing entries (`appendKeys`), or just adding new keys.

---

### Truncation: Managing Volume

#### `truncateContextElements(maxTokens: Int, ...)`
Trims raw context elements to fit the budget using the specified strategy (Top, Bottom, Middle).

#### `selectAndTruncateContext(text: String, totalTokenBudget: Int, ...)`
The **Master Controller**. Automatically distributes the token budget across the LoreBook, conversation history, and raw context, then truncates everything to fit.

> [!TIP]
> **Priority Preservation**: Set `preserveTextMatches = true` to ensure that context elements containing keywords from the current prompt are the last to be truncated.

#### `combineAndTruncateAsString(text: String, totalTokenBudget: Int, ...): String`
Aggressively flattens the LoreBook and raw context into a single string and truncates the result. Use this for models that don't support structured context well.

---

### Access Control: The Safety Locks

TPipe integrates with the **ContextLock** system to ensure sensitive memory isn't leaked.

#### `isContextLocked(): Boolean`
Returns `true` if this reservoir has active security locks.

#### `canSelectLoreBookKey(key: String): Boolean`
Checks if a specific key is accessible under the current security context.

#### `getLockedKeys(): Set<String>`
Lists all keys that are currently "valved off" (locked) and cannot be selected.

---

### Utilities

#### `isEmpty(): Boolean`
Returns `true` if the reservoir is completely empty (no LoreBook, no text, no history).

#### `clear()`
Drains the reservoir, deleting all stored context.
