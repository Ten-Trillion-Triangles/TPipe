# ConverseHistory Class API

## Table of Contents
- [Overview](#overview)
- [ConverseRole Enum](#converserole-enum)
- [ConverseData Class](#conversedata-class)
  - [Public Properties](#public-properties)
  - [Public Functions](#public-functions)
- [ConverseHistory Class](#conversehistory-class)
  - [Public Properties](#public-properties-1)
  - [Public Functions](#public-functions-1)

## Overview

The conversation system provides structured storage for multi-turn conversations between users and AI agents, maintaining role-based context and preventing duplicate entries through UUID tracking.

## ConverseRole Enum

```kotlin
enum class ConverseRole {
    developer,
    system,
    user,
    agent,
    assistant
}
```

Defines conversation participant roles for proper context attribution. LLM providers may handle different roles with varying behaviors and priorities.

## ConverseData Class

Represents a single conversation turn with role identification and content.

```kotlin
@Serializable
data class ConverseData(
    var role: ConverseRole,
    var content: MultimodalContent,
    private var uuid: String = ""
)
```

### Public Properties

**`role`**
```kotlin
var role: ConverseRole
```
Identifies the conversation participant type. Determines how LLMs interpret and respond to the content based on provider-specific role handling.

**`content`**
```kotlin
var content: MultimodalContent
```
The actual conversation content including text, binary data, and metadata. Contains the complete multimodal message from the specified role.

### Public Functions

#### `setUUID()`
Generates and assigns a unique identifier for this conversation turn.

**Behavior:** Creates new UUID and stores it internally. Required for duplicate detection in conversation history. Automatically called when adding to ConverseHistory.

#### `getUUID(): String`
Returns the unique identifier for this conversation turn.

**Behavior:** Provides access to the internal UUID used for equality comparison and duplicate prevention.

#### `equals(other: Any?): Boolean`
Compares ConverseData objects for equality based on UUID.

**Behavior:** Overrides default equality to use UUID comparison instead of content comparison. Two ConverseData objects are equal if they have the same UUID, regardless of content differences.

---

## ConverseHistory Class

Manages ordered collection of conversation turns with duplicate prevention.

```kotlin
@Serializable
data class ConverseHistory(
    val history: MutableList<ConverseData> = mutableListOf()
)
```

### Public Properties

**`history`**
```kotlin
val history: MutableList<ConverseData> = mutableListOf()
```
Ordered list of conversation turns. Maintains chronological sequence of interactions between different roles. Direct access allows for advanced manipulation and filtering operations.

### Public Functions

#### `add(role: ConverseRole, content: MultimodalContent)`
Adds new conversation turn by creating ConverseData from parameters.

**Behavior:** 
- Creates new ConverseData object with specified role and content
- Automatically generates UUID for the new entry
- Performs duplicate check using UUID comparison
- Ignores addition if identical UUID already exists in history
- Appends to history list if unique

#### `add(converseData: ConverseData)`
Adds existing ConverseData object to conversation history.

**Behavior:**
- Generates UUID if the ConverseData object doesn't have one
- Performs duplicate check using UUID comparison  
- Ignores addition if identical object already exists in history
- Appends to history list if unique
- Preserves existing UUID if already set

## Key Behaviors

### Duplicate Prevention
Both add methods implement duplicate prevention through UUID comparison. This ensures conversation history doesn't contain repeated entries even if the same content is added multiple times.

### UUID Management
UUIDs are automatically managed during the addition process. Manual UUID setting is only necessary for advanced scenarios where specific identification is required before adding to history.

### Role-Based Context
The role system enables LLMs to understand conversation context and respond appropriately based on who said what. Different providers may treat roles differently in terms of priority and behavior.

### Multimodal Support
Each conversation turn supports full multimodal content including text, images, documents, and metadata. This enables rich conversational experiences beyond simple text exchanges.

### Chronological Ordering
The history list maintains insertion order, preserving the chronological flow of conversation. This ordering is critical for maintaining conversational context and coherence.

### Direct Access
The public history property allows direct manipulation for advanced use cases like filtering, searching, or custom processing of conversation data while maintaining the underlying structure.
## Next Steps

- [TodoList API](todolist.md) - Continue into task management.
