# LoreBook Class API

The `LoreBook` class is the storage unit for the Strategic Reserves. It manages weighted key-value context pairs that are intelligently injected into the flow based on text matching and dependency relationships.

```kotlin
@Serializable
data class LoreBook(@Transient val cinit: Boolean = false)
```

## Table of Contents
- [Public Properties](#public-properties)
  - [Core Properties](#core-properties)
  - [Relationship Properties](#relationship-properties)
- [Public Functions](#public-functions)
- [Usage Patterns](#usage-patterns)

## Public Properties

### Core Properties

**`key`**
```kotlin
var key: String = ""
```
The primary identifier used for text matching. When this substring is found in the input text, the entry becomes a candidate for injection into the prompt.

**`value`**
```kotlin
var value: String = ""
```
The actual knowledge payload. This can contain descriptions of NPCs, technical specifications, world rules, or any other background information relevant to the model.

**`weight`**
```kotlin
var weight: Int = 0
```
Priority level for selection. In a high-pressure scenario where the token budget is limited, TPipe sorts candidates by weight and hit-count to decide which data is pumped into the context reservoir first.

### Relationship Properties: The Interconnects

**`linkedKeys`**
```kotlin
var linkedKeys: MutableList<String> = mutableListOf()
```
Defines cascading context activation. When this LoreBook entry is selected, TPipe automatically attempts to pull in the entries associated with these linked keys as well.

**`aliasKeys`**
```kotlin
var aliasKeys: MutableList<String> = mutableListOf()
```
Alternative trigger strings. Any text matching these aliases (e.g., synonyms or shortened names) counts as a hit for the primary entry.

**`requiredKeys`**
```kotlin
var requiredKeys: MutableList<String> = mutableListOf()
```
Hard dependencies. For this entry to be eligible for activation, every key in this list *must* also be present in the scanned input text. This allows for conditional knowledge that only appears in specific contexts.

---

## Public Functions

#### `combineValue(other: LoreBook)`
Merges content from another entry into this one.

*   **Logic**: It appends the `other.value` to this entry's value with a space separator and merges the `requiredKeys` lists while preventing duplicates.
*   **Result**: The primary key, weight, and aliases of the original object are preserved.

#### `toMap(): Map<String, LoreBook>`
A utility function that wraps this single entry into a Map, using its `key` as the map key. This is the standard format required by the `ContextWindow`.

---

## Usage Patterns

### Basic Entry
```kotlin
val agent = LoreBook().apply {
    key = "Main_Valve"
    value = "The Main Valve is located in Sector 7 and controls the primary flow."
    weight = 10
}
```

### Complex Relationships
```kotlin
val schema = LoreBook().apply {
    key = "Safety_Protocol"
    value = "Always shut the intake before opening the bypass."
    aliasKeys.add("emergency_procedure")
    requiredKeys.add("High_Pressure_Warning") // Only activates if pressure warning is also found
    linkedKeys.add("Technician_Contact")      // Automatically pulls contact info if this activates
}
```

## Integration with ContextWindow

LoreBook entries are typically managed through the `ContextWindow` reservoir:

```kotlin
val reservoir = ContextWindow()

// High-level helper for adding entries
reservoir.addLoreBookEntry(
    key = "Pump_A1",
    value = "Model v4, manufactured 2023.",
    weight = 5,
    aliasKeys = listOf("primary_pump", "intake_pump")
)
```
