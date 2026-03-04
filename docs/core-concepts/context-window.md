# ContextWindow - Memory Storage and Retrieval

> 💡 **Tip:** The **ContextWindow** is your local Reservoir. It temporarily holds the water (context and message history) flowing through a specific Pipe.


## Table of Contents
- [What is a ContextWindow?](#what-is-a-contextwindow)
- [Core Functions](#core-functions)
- [Three Types of Context Storage](#three-types-of-context-storage)
- [How Context Selection Works](#how-context-selection-works)
- [Basic Operations](#basic-operations)
- [Token Selection and Management](#token-selection-and-management)

The ContextWindow is TPipe's primary memory system for storing and retrieving contextual information across AI interactions. It acts as a structured container that holds different types of context data and automatically manages how that data is selected and injected into AI model calls.

## What is a ContextWindow?

The ContextWindow is a serializable data structure that stores three types of contextual information:

1. **LoreBook entries**: Weighted, key-based context with smart selection
2. **Context elements**: Raw string-based context data  
3. **Conversation history**: Structured conversation storage

```kotlin
@Serializable
data class ContextWindow(
    var loreBookKeys: MutableMap<String, LoreBook> = mutableMapOf(),
    var contextElements: MutableList<String> = mutableListOf(),
    var converseHistory: ConverseHistory = ConverseHistory(),
    var contextSize: Int = 8000
)
```

## Core Functions

### Memory Storage
The ContextWindow provides multiple ways to store contextual information that can be retrieved and used in future AI interactions.

### Intelligent Context Selection
When AI models have limited context windows, the ContextWindow automatically selects the most relevant information based on input text analysis and weighting systems.

### Token Budget Management
Automatically manages token allocation across different context types, ensuring the most important information fits within model limits.

## Three Types of Context Storage

### 1. LoreBook Entries (Smart Context)
**Purpose**: Stores key-value pairs with metadata for intelligent context selection.

**Features**:
- **Weighted selection**: Higher weight entries are prioritized
- **Key matching**: Automatically selected when input contains matching keywords
- **Alias support**: Multiple keywords can trigger the same entry
- **Dependencies**: Entries can require other entries to be present
- **Linked keys**: Related entries that should appear together

```kotlin
contextWindow.addLoreBookEntry(
    key = "john_smith",
    value = "John Smith is a 35-year-old detective with 10 years of experience.",
    weight = 5,
    aliasKeys = listOf("john", "detective", "smith"),
    requiredKeys = listOf("police_department"),
    linkedKeys = listOf("sarah_jones", "case_files")
)
```

### 2. Context Elements (Raw Context)
**Purpose**: Stores raw string data that should always be included in context.

**Features**:
- Simple string storage
- Always included unless truncated due to token limits
- Useful for instructions, rules, or static information

```kotlin
contextWindow.contextElements.add("Always respond in a professional tone.")
contextWindow.contextElements.add("The current date is ${getCurrentDate()}")
```

### 3. Conversation History (Structured Conversations)
**Purpose**: Stores structured conversation data between user and AI.

**Features**:
- Role-based message storage (user, assistant, system)
- Maintains conversation flow and context
- Automatic token management
- Supports multimodal content

```kotlin
contextWindow.converseHistory.add(
    ConverseData(
        role = ConverseRole.user,
        content = MultimodalContent("What's the weather like?")
    )
)
```

## How Context Selection Works

### Automatic LoreBook Selection
When processing input text, the ContextWindow:

1. **Scans for keywords**: Finds LoreBook keys that match text content
2. **Checks dependencies**: Ensures required keys are available
3. **Applies weighting**: Prioritizes higher-weight entries
4. **Manages token budget**: Fits selected entries within available space

```kotlin
// Input: "Tell me about John's latest case"
// Automatically selects: john_smith, case_files, police_department
val selectedContext = contextWindow.selectLoreBookContext(inputText, tokenBudget)
```

### Token Budget Distribution
When multiple context types are present, the ContextWindow automatically distributes available tokens:

- **LoreBook only**: Gets full token budget
- **LoreBook + Context Elements**: 50/50 split
- **All three types**: 33/33/33 split

### Truncation Strategies
When context exceeds available tokens, the ContextWindow applies truncation:

- **TruncateTop**: Removes oldest/earliest content first
- **TruncateBottom**: Removes newest/latest content first  
- **TruncateMiddle**: Removes middle content, preserving start and end

## Basic Operations

### Creating and Managing Context
```kotlin
val contextWindow = ContextWindow()

// Add LoreBook entry
contextWindow.addLoreBookEntry("character_name", "Character description", weight = 10)

// Add raw context
contextWindow.contextElements.add("Important rule or instruction")

// Check if empty
if (contextWindow.isEmpty()) {
    println("No context stored")
}

// Clear all context
contextWindow.clear()
```

### Context Retrieval and Selection
```kotlin
// Find matching LoreBook keys for input
val matchingKeys = contextWindow.findMatchingLoreBookKeys("Tell me about John")

// Get selected context within token budget
val selectedContext = contextWindow.selectAndTruncateContext(
    inputText = "User input here",
    maxTokens = 4000,
    truncateSettings = ContextWindowSettings.TruncateTop
)
```

### Merging Context Windows
```kotlin
// Basic merge - combines lorebook entries and context elements
val contextWindow1 = ContextWindow()
val contextWindow2 = ContextWindow()

contextWindow1.merge(contextWindow2)

// Advanced merge with conversation history
contextWindow1.merge(
    other = contextWindow2,
    emplaceLoreBookKeys = true,      // Replace existing lorebook entries
    appendKeys = false,              // Don't append to existing entries
    emplaceConverseHistory = true,   // Enable conversation history merging
    onlyEmplaceIfNull = true        // Only copy conversation if target is empty
)
```

**Merge behavior**:
- **LoreBook entries**: Three strategies - add new, append existing, or replace existing
- **Context elements**: Simple append with duplicate prevention
- **Conversation history**: Optional copying when target is empty or full replacement
- **Duplicate handling**: Prevents duplicate context elements and lorebook key lists

**Use cases**:
- Combining context from multiple sources
- Initializing new contexts with existing conversation state
- Context migration and branching scenarios

The ContextWindow is the foundation of TPipe's memory system, enabling AI applications to maintain context, learn from interactions, and provide more intelligent, context-aware responses over time.

## Token Selection and Management

The ContextWindow provides sophisticated token selection features that automatically choose the most relevant context based on input analysis and token budgets.

### Finding Matching LoreBook Keys
```kotlin
// Find LoreBook entries that match input text
val matchingKeys = contextWindow.findMatchingLoreBookKeys("Tell me about John Smith")
// Returns: ["john_smith", "detective_cases"] (matches main keys and aliases)

// Count and sort by frequency
val keyHits = contextWindow.countAndSortKeyHits(matchingKeys)
// Returns: [("john_smith", 2), ("detective_cases", 1)] (sorted by hit count)
```

**How key matching works**:
- Scans input text for LoreBook keys (case-insensitive)
- Matches both main keys and alias keys
- Returns all matching keys for further processing

### Automatic LoreBook Selection
```kotlin
// Select LoreBook entries within token budget
val selectedKeys = contextWindow.selectLoreBookContext(
    text = "Tell me about John's latest detective case",
    maxTokens = 2000,
    favorWholeWords = true,
    countSubWordsInFirstWord = true,
    nonWordSplitCount = 4
)
// Returns: ["john_smith", "detective_cases", "police_department"] (within budget)

// Use with TruncationSettings for convenience
val settings = TruncationSettings(favorWholeWords = true, nonWordSplitCount = 3)
val selectedKeys = contextWindow.selectLoreBookContextWithSettings(
    settings = settings,
    text = "Tell me about John",
    maxTokens = 1500
)
```

**Selection algorithm**:
1. **Find matches**: Locate all LoreBook keys that match input text
2. **Expand linked keys**: Include keys linked to matched keys
3. **Check dependencies**: Ensure required keys are available
4. **Apply weighting**: Prioritize higher-weight entries
5. **Fit budget**: Select entries that fit within token limit

### Complete Context Selection and Truncation
```kotlin
// Select and truncate all context types within budget
contextWindow.selectAndTruncateContext(
    text = "User input text here",
    totalTokenBudget = 4000,
    truncateSettings = ContextWindowSettings.TruncateTop
)

// With TruncationSettings object
val settings = TruncationSettings(
    multiplyWindowSizeBy = 1000,
    favorWholeWords = true,
    nonWordSplitCount = 4
)

contextWindow.selectAndTruncateContext(
    text = "User input text here",
    totalTokenBudget = 4,  // Will be 4 * 1000 = 4000 tokens
    truncateSettings = ContextWindowSettings.TruncateTop,
    settings = settings
)
```

**What selectAndTruncateContext does**:
- Automatically distributes token budget across LoreBook, context elements, and conversation history
- Selects most relevant LoreBook entries based on input text
- Truncates context elements and conversation history to fit budget
- Modifies the ContextWindow in-place with selected content

### Token Budget Distribution

The ContextWindow automatically distributes available tokens based on what content types are present:

#### LoreBook Only
```kotlin
// If only LoreBook entries exist
// LoreBook gets 100% of token budget
val selectedKeys = contextWindow.selectLoreBookContext(text, fullBudget)
```

#### LoreBook + Context Elements
```kotlin
// If LoreBook and context elements exist
// 50% budget for context elements (truncated)
// 50% budget for LoreBook (selected by relevance)
```

#### LoreBook + Conversation History
```kotlin
// If LoreBook and conversation history exist
// 50% budget for conversation history (truncated)
// 50% budget for LoreBook (selected by relevance)
```

#### All Three Types
```kotlin
// If LoreBook, context elements, and conversation history exist
// 33% budget for each type
// Remaining tokens from unused allocations go to LoreBook
```

### Advanced Selection Features

#### Dependency-Based Selection
```kotlin
// LoreBook entries with dependencies
contextWindow.addLoreBookEntry(
    key = "advanced_case",
    value = "Complex detective case requiring experience",
    weight = 10,
    requiredKeys = listOf("john_smith", "police_department")  // Dependencies
)

// Selection automatically includes dependencies
val selected = contextWindow.selectLoreBookContext("advanced case details", 3000)
// Returns: ["advanced_case", "john_smith", "police_department"] (includes dependencies)
```

#### Linked Key Expansion
```kotlin
// LoreBook entries with linked keys
contextWindow.addLoreBookEntry(
    key = "john_smith",
    value = "Detective information",
    linkedKeys = listOf("partner_sarah", "current_cases")  // Related entries
)

// Selection automatically includes linked keys
val selected = contextWindow.selectLoreBookContext("Tell me about John", 2000)
// Returns: ["john_smith", "partner_sarah", "current_cases"] (includes linked keys)
```

#### Weight-Based Prioritization
```kotlin
// Higher weight entries are prioritized when budget is limited
contextWindow.addLoreBookEntry("important_info", "Critical data", weight = 10)
contextWindow.addLoreBookEntry("minor_detail", "Less important", weight = 2)

// When token budget is tight, higher weight entries are selected first
val selected = contextWindow.selectLoreBookContext("info and detail", 500)
// Prioritizes "important_info" over "minor_detail" due to weight
```

### Conversation-Based Selection
```kotlin
// Select LoreBook entries based on conversation history
val selectedFromConversation = contextWindow.selectLoreBookContextFromConversation(
    maxTokens = 2000,
    favorWholeWords = true,
    nonWordSplitCount = 4
)
```

**Use case**: When you want to select relevant LoreBook entries based on the entire conversation history rather than just the current input.

### Practical Selection Examples

#### Dynamic Context for Chat Applications
```kotlin
// User asks about a character
val userInput = "What can you tell me about Detective Smith's current case?"

// Automatically select relevant context
contextWindow.selectAndTruncateContext(
    text = userInput,
    totalTokenBudget = 3000,
    truncateSettings = ContextWindowSettings.TruncateTop
)

// ContextWindow now contains only relevant entries:
// - john_smith (matched "Detective Smith")
// - current_cases (linked to john_smith)
// - police_department (dependency of current_cases)
// - Recent conversation history (truncated from top)
```

#### Content-Aware Document Processing
```kotlin
// Processing a document about specific topics
val documentText = "Analysis of financial trends in Q3 2024..."

// Select relevant background information
val relevantKeys = contextWindow.selectLoreBookContext(
    text = documentText,
    maxTokens = 4000,
    favorWholeWords = true
)

// Use selected context for enhanced processing
val enhancedContext = relevantKeys.mapNotNull { key ->
    contextWindow.loreBookKeys[key]?.value
}.joinToString("\n")
```

#### Budget-Constrained Selection
```kotlin
// When working with limited token budgets
val settings = TruncationSettings(
    multiplyWindowSizeBy = 1000,
    favorWholeWords = true,
    nonWordSplitCount = 3
)

// Efficiently use small token budget
contextWindow.selectAndTruncateContext(
    text = userInput,
    totalTokenBudget = 1,  // 1 * 1000 = 1000 tokens total
    truncateSettings = ContextWindowSettings.TruncateTop,
    settings = settings
)
// Automatically selects most relevant content within tight budget
```

These token selection features enable the ContextWindow to intelligently choose the most relevant contextual information for any given input, ensuring optimal use of available token budgets while maintaining context relevance.

## Context Access Control

### ContextLock Integration

The ContextWindow integrates with TPipe's ContextLock system to provide fine-grained access control over lorebook keys and context selection. This enables secure, conditional access to sensitive context information.

#### Lock-Aware Selection
When ContextLock is active, all lorebook selection methods automatically respect lock states:

```kotlin
// Lock sensitive information
ContextLock.addLock("api_credentials", "", false, true)
ContextLock.addLock("user_data", "", false, true)

// Selection automatically excludes locked keys
val selectedKeys = contextWindow.selectLoreBookContext(text, maxTokens)
// api_credentials and user_data will not appear in results
```

#### Checking Lock Status
```kotlin
// Check if context window is affected by locks
if (contextWindow.isContextLocked()) {
    val lockedKeys = contextWindow.getLockedKeys()
    println("Locked keys: ${lockedKeys.joinToString(", ")}")
}

// Check individual key accessibility
if (contextWindow.canSelectLoreBookKey("sensitive_data")) {
    // Key is available for selection
} else {
    // Key is locked or conditionally restricted
}
```

#### Conditional Access
ContextLock supports passthrough functions for dynamic access control:

```kotlin
// Time-based access control
ContextLock.addLock("business_hours_data", "", false, true) {
    val hour = LocalTime.now().hour
    hour in 9..17  // Only accessible during business hours
}

// Permission-based access control
ContextLock.addLock("admin_settings", "", false, true) {
    currentUser.hasRole("ADMIN")
}
```

The ContextWindow seamlessly integrates these access controls into all selection operations, ensuring security policies are automatically enforced without requiring manual checks in application code.

**→ [ContextLock API](../api/context-lock.md)** - Complete ContextLock documentation

## Next Steps

Now that you understand TPipe's memory system, learn about token management:

**→ [Context and Tokens - Token Management](context-and-tokens.md)** - Managing token usage and limits
