# LoreBook Class API

## Table of Contents
- [Overview](#overview)
- [Public Properties](#public-properties)
  - [Core Properties](#core-properties)
  - [Relationship Properties](#relationship-properties)
- [Public Functions](#public-functions)
  - [Content Management](#content-management)
  - [Utilities](#utilities)
- [Scan Surface](#scan-surface)

## Overview

The `LoreBook` class stores weighted key-value context pairs compatible with NovelAI's Lorebook system. It provides intelligent context injection based on text matching, weights, and dependency relationships.

```kotlin
@Serializable
data class LoreBook(@Transient val cinit: Boolean = false)
```

## Public Properties

### Core Properties

**`key`**
```kotlin
var key: String = ""
```
The lorebook key used for text matching. When this substring is found in scanned text, the associated value is considered for context injection based on token budget and weight priority.

**`value`**
```kotlin
var value: String = ""
```
Context content associated with the key. Can contain any information desired for the LLM - typically events, locations, NPCs, or world-building concepts that provide relevant background context.

**`weight`**
```kotlin
var weight: Int = 0
```
Priority weight for context selection. Higher weights receive priority over lower weights when token budget is limited. Used by context selection algorithms to determine inclusion order.

### Relationship Properties

**`linkedKeys`**
```kotlin
var linkedKeys: MutableList<String> = mutableListOf()
```
Additional lorebook keys automatically activated when this entry is selected. When this lorebook is included in context, linked keys are also attempted for inclusion, creating cascading context activation.

**`aliasKeys`**
```kotlin
var aliasKeys: MutableList<String> = mutableListOf()
```
Alternative key strings that trigger this lorebook entry. Any text matching these aliases counts as a hit for the main key, enabling flexible matching patterns and synonyms.

**`requiredKeys`**
```kotlin
var requiredKeys: MutableList<String> = mutableListOf()
```
Dependency keys that must ALL be present in input text for this entry to be eligible for activation. Empty list means no dependencies (always eligible). Enables conditional context activation based on multiple triggers.

## Public Functions

### Content Management

#### `combineValue(other: LoreBook)`
Merges content from another LoreBook into this one.

**Behavior:** 
- **Value merging**: Appends other's value to this value with space separator
- **Required keys merging**: Adds other's required keys to this entry, avoiding duplicates
- **Other properties**: Unchanged (key, weight, linkedKeys, aliasKeys remain as-is)

Useful for consolidating related lorebook entries or accumulating context from multiple sources.

---

### Utilities

#### `toMap(): Map<String, LoreBook>`
Converts this LoreBook to a single-entry map.

**Behavior:** Returns `Map<String, LoreBook>` with this lorebook's key as the map key and this object as the value. Convenient for integration with ContextWindow's `loreBookKeys` map structure.

## Scan Surface

The "scan surface" is the text that the lorebook matcher runs against when deciding which entries to inject. The default is the user prompt only — that contract is preserved for any caller that has not opted in.

### Default Scan Surface

By default, the lorebook matcher scans **only the user prompt** (`content.text`). Neither `ContextWindow.contextElements` nor `ContextWindow.converseHistory` are included in the scan. This is the historical behavior and remains the default so existing pipelines keep their selection results.

If your lorebook keys are designed to fire on the user's most recent message alone, leave the scan surface alone. If your lorebook keys reference concepts that live in earlier context (recent turns of conversation, system-injected context elements, prior assistant replies), the default surface can miss them.

### Expanding the Scan Surface

To opt in to a larger scan surface, call `buildLorebookScanText(userPrompt, useEntireContext)` on the `ContextWindow` you are about to run selection against. The helper is the canonical way to build the scan text for any of the five lorebook selection/truncation call sites in `Pipe.kt`.

```kotlin
fun ContextWindow.buildLorebookScanText(
    userPrompt: String,
    useEntireContext: Boolean
): String
```

**Parameters:**
- **`userPrompt`**: The user prompt text the caller is about to feed to selection.
- **`useEntireContext`**: When `true`, expand the scan surface to the entire window. When `false`, the helper returns `userPrompt` unchanged.

**Returns:** The text to feed into `selectLoreBookContext` / `selectAndTruncateContext`.

### Behavior Contract

- **When `useEntireContext` is `false`**: returns `userPrompt` verbatim, regardless of what `contextElements` and `converseHistory` contain. This is the default and preserves the historical "scan only the user prompt" contract.
- **When `useEntireContext` is `true`**: returns the user prompt concatenated with `contextElements` (newline-separated) and `converseHistory.history[*].content.text` (newline-separated). Empty sources are skipped — there is no trailing newline if either side is empty.

The concatenation order is: `userPrompt`, then `contextElements` (if non-empty), then `converseHistory.history[*].content.text` (if non-empty). Each component is joined with `'\n'`. The user prompt is always present and always first.

### Example

```kotlin
import com.TTT.Context.ContextWindow
import com.TTT.Context.ConverseHistory
import com.TTT.Context.ConverseRole
import com.TTT.Pipe.MultimodalContent
import com.TTT.Context.buildLorebookScanText

val contextWindow = ContextWindow()
contextWindow.contextElements.add(
    "Lyra first visited the Silverbrook archives on the eve of the autumn equinox."
)
contextWindow.converseHistory.add(
    ConverseRole.user,
    MultimodalContent("Tell me about the last time you were in Silverbrook.")
)
contextWindow.converseHistory.add(
    ConverseRole.agent,
    MultimodalContent("I remember the archives well — the dust on the southern stacks was almost gold in the lamplight.")
)

val userPrompt = "What did Lyra find in the restricted wing?"
val scanText = contextWindow.buildLorebookScanText(userPrompt, true)
// scanText:
// "What did Lyra find in the restricted wing?
// Lyra first visited the Silverbrook archives on the eve of the autumn equinox.
// Tell me about the last time you were in Silverbrook.
// I remember the archives well — the dust on the southern stacks was almost gold in the lamplight."

// With useEntireContext = false, the same call returns userPrompt unchanged:
// "What did Lyra find in the restricted wing?"
```

### Enabling on a Pipe

The Pipe-level DSL flag is what flips `useEntireContext` to `true` for every selection/truncation call site in the pipe's execution path:

```kotlin
val pipe = BedrockPipe()
    .setModel("anthropic.claude-3-sonnet-20240229-v1:0")
    .useEntireContextForLoreSelection()
```

See [Pipe: useEntireContextForLoreSelection()](pipe.md#useentirecontextforloreselection-pipe) for the full Pipe-side contract.

### MiniBank Per-Page Behavior

In multi-page contexts (`MiniBank`), each page's matcher uses the shared user prompt plus **that page's own** `contextElements` and `converseHistory` — not the main window's. The per-page isolation is preserved even when `useEntireContext = true`. The helper is called per page against each page's `ContextWindow`, so each page's lorebook selection sees a scan surface scoped to its own context.

## Usage Patterns

### Basic Lorebook Entry
```kotlin
val character = LoreBook().apply {
    key = "Alice"
    value = "Alice is a skilled mage who specializes in fire magic. She has red hair and wears blue robes."
    weight = 10
}
```

### Entry with Aliases
```kotlin
val location = LoreBook().apply {
    key = "Silverbrook"
    value = "A peaceful village nestled in the mountains, known for its crystal-clear streams."
    weight = 5
    aliasKeys.addAll(listOf("Silver Brook", "the village", "mountain village"))
}
```

### Entry with Dependencies
```kotlin
val event = LoreBook().apply {
    key = "dragon attack"
    value = "The ancient red dragon Pyraxis attacked the village last winter, destroying the eastern district."
    weight = 15
    requiredKeys.addAll(listOf("Pyraxis", "village"))
    linkedKeys.add("Pyraxis")
}
```

### Entry with Linked Context
```kotlin
val character = LoreBook().apply {
    key = "King Marcus"
    value = "The wise ruler of the northern kingdom, known for his just laws."
    weight = 12
    linkedKeys.addAll(listOf("northern kingdom", "royal court"))
    aliasKeys.addAll(listOf("the king", "His Majesty", "Marcus"))
}
```

### Combining Entries
```kotlin
val baseEntry = LoreBook().apply {
    key = "magic system"
    value = "Magic requires focus and energy."
    weight = 8
}

val additionalInfo = LoreBook().apply {
    value = "Advanced mages can cast without verbal components."
    requiredKeys.add("advanced magic")
}

baseEntry.combineValue(additionalInfo)
// Result: "Magic requires focus and energy. Advanced mages can cast without verbal components."
```

## Integration with ContextWindow

LoreBook entries are typically managed through ContextWindow:

```kotlin
val contextWindow = ContextWindow()

// Add entry directly
contextWindow.addLoreBookEntry(
    key = "important location",
    value = "The Tower of Wisdom stands at the city center.",
    weight = 10,
    aliasKeys = listOf("tower", "wisdom tower"),
    linkedKeys = listOf("city center"),
    requiredKeys = listOf("city")
)

// Add using LoreBook object
val entry = LoreBook().apply {
    key = "character"
    value = "A mysterious figure in dark robes."
    weight = 7
}
contextWindow.addLoreBookEntryWithObject(entry)
```
## Next Steps

- [Debug Package API](debug-package.md) - Continue into tracing and monitoring helpers.
