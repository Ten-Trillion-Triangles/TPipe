# LoreBook Class API

## Table of Contents
- [Overview](#overview)
- [Public Properties](#public-properties)
  - [Core Properties](#core-properties)
  - [Relationship Properties](#relationship-properties)
- [Public Functions](#public-functions)
  - [Content Management](#content-management)
  - [Utilities](#utilities)

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
