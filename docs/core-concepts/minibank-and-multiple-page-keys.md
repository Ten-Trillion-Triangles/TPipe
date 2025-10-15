# MiniBank and Multiple Page Keys

## Table of Contents
- [What is MiniBank?](#what-is-minibank)
- [When MiniBank is Used](#when-minibank-is-used)
- [MiniBank Structure and Content](#minibank-structure-and-content)
- [MiniBank Operations](#minibank-operations)
- [Pre-Validation with MiniBank](#pre-validation-with-minibank)
- [Context Truncation with MiniBank](#context-truncation-with-minibank)
- [Practical Examples](#practical-examples)
- [Best Practices](#best-practices)

MiniBank is TPipe's solution for handling multiple page keys simultaneously. When a pipe needs to access multiple context pages from ContextBank, MiniBank provides organized storage and management of these separate context sources.

## What is MiniBank?

MiniBank is a container that holds multiple ContextWindows organized by page keys:

```kotlin
@Serializable
data class MiniBank(
    var contextMap: MutableMap<String, ContextWindow> = mutableMapOf()
)
```

**Purpose**:
- **Multiple context sources**: Store different context types separately
- **Organized access**: Keep context pages distinct while using them together
- **Context separation**: Maintain boundaries between different data types
- **Structured injection**: Provide organized context to AI models

## When MiniBank is Used

### Single Page Key (Uses ContextWindow)
```kotlin
val pipe = BedrockPipe()
    .pullGlobalContext()
    .setPageKey("userSession")  // Single page key
    .autoInjectContext("Use the user session context.")
```

**Result**: Uses standard ContextWindow, not MiniBank

### Multiple Page Keys (Uses MiniBank)
```kotlin
val pipe = BedrockPipe()
    .pullGlobalContext()
    .setPageKey("userProfile, gameState, inventory")  // Multiple page keys
    .autoInjectContext("Use all provided context types.")
```

**Result**: Automatically creates and uses MiniBank with separate context pages

## MiniBank Structure and Content

### Context Organization
When multiple page keys are used, MiniBank organizes context like this:

```json
{
  "contextMap": {
    "userProfile": {
      "loreBookKeys": {"userName": {"value": "John", "weight": 5}},
      "contextElements": ["User preferences: dark mode"],
      "converseHistory": {"history": []}
    },
    "gameState": {
      "loreBookKeys": {"currentLevel": {"value": "Level 5", "weight": 8}},
      "contextElements": ["Health: 100", "Score: 1500"],
      "converseHistory": {"history": []}
    },
    "inventory": {
      "loreBookKeys": {"sword": {"value": "Magic sword +5", "weight": 10}},
      "contextElements": ["Gold: 250", "Potions: 3"],
      "converseHistory": {"history": []}
    }
  }
}
```

### Schema vs Data Injection with MiniBank

**System Prompt (Schema)**:
```
Your system prompt here

The user prompt contains context data organized by page keys in this structure:

{
  "contextMap": {
    "pageKey1": {
      "loreBookKeys": {...},
      "contextElements": [...],
      "converseHistory": {...}
    },
    "pageKey2": {
      "loreBookKeys": {...},
      "contextElements": [...],
      "converseHistory": {...}
    }
  }
}
```

**User Prompt (Actual Data)**:
```
User's input text

{
  "contextMap": {
    "userProfile": {
      "loreBookKeys": {"userName": {"value": "John", "weight": 5}},
      "contextElements": ["Preference: dark mode"],
      "converseHistory": {"history": []}
    },
    "gameState": {
      "loreBookKeys": {"level": {"value": "Level 5", "weight": 8}},
      "contextElements": ["Health: 100"],
      "converseHistory": {"history": []}
    }
  }
}
```

## MiniBank Operations

### Automatic Population
```kotlin
// TPipe automatically populates MiniBank when multiple page keys are used
val pipe = BedrockPipe()
    .pullGlobalContext()
    .setPageKey("sessionData, userPrefs, gameState")
    .autoInjectContext("Context is organized by page keys in the user prompt.")

// Internally, TPipe does this:
// for (page in pageKeyList) {
//     val pagedContext = ContextBank.getContextFromBank(page)
//     miniContextBank.contextMap[page] = pagedContext
// }
```

### Manual MiniBank Operations
```kotlin
// Create and populate MiniBank manually
val miniBank = MiniBank()
miniBank.contextMap["userSession"] = ContextBank.getContextFromBank("userSession")
miniBank.contextMap["gameData"] = ContextBank.getContextFromBank("gameData")

// Merge MiniBank instances
val otherMiniBank = MiniBank()
otherMiniBank.contextMap["additionalData"] = someContextWindow
miniBank.merge(otherMiniBank)

// Check if empty
if (miniBank.isEmpty()) {
    println("No context pages loaded")
}
```

## Pre-Validation with MiniBank

### MiniBank-Specific Pre-Validation
```kotlin
val pipe = BedrockPipe()
    .pullGlobalContext()
    .setPageKey("userProfile, gameState, inventory")
    .setPreValidationMiniBankFunction { miniBank, content ->
        // Process each context page separately
        miniBank.contextMap.forEach { (pageKey, contextWindow) ->
            when (pageKey) {
                "userProfile" -> {
                    // Filter user profile context
                    contextWindow.contextElements.removeIf { it.contains("sensitive") }
                }
                "gameState" -> {
                    // Update game state based on input
                    val currentHealth = extractHealth(content?.text ?: "")
                    contextWindow.contextElements.add("Updated health: $currentHealth")
                }
                "inventory" -> {
                    // Validate inventory items
                    contextWindow.loreBookKeys.values.removeIf { it.weight < 5 }
                }
            }
        }
        
        miniBank
    }
    .autoInjectContext("Use the processed context data organized by page keys.")
```

### Regular Pre-Validation vs MiniBank Pre-Validation
```kotlin
// Regular pre-validation (single context)
.setPreValidationFunction { contextWindow, content ->
    // Modify single context window
    contextWindow
}

// MiniBank pre-validation (multiple contexts)
.setPreValidationMiniBankFunction { miniBank, content ->
    // Modify multiple context pages
    miniBank
}
```

## Context Truncation with MiniBank

### Automatic Truncation
```kotlin
val pipe = BedrockPipe()
    .pullGlobalContext()
    .setPageKey("largeContext1, largeContext2, largeContext3")
    .autoTruncateContext()  // Truncates each page separately
    .autoInjectContext("Use the truncated context data from all pages.")
```

**How truncation works with MiniBank**:
- Each context page in MiniBank is truncated separately
- Token budget is distributed across all pages
- Each page uses the same truncation settings
- Pages maintain their separation after truncation

### Token Counting with MiniBank
```kotlin
// TPipe automatically counts tokens for MiniBank
// If MiniBank is empty, uses ContextWindow
if (miniContextBank.isEmpty()) {
    val contextJson = serialize(contextWindow)
    val contextSize = Dictionary.countTokens(contextJson, truncationSettings)
} else {
    val miniBankJson = serialize(miniContextBank)
    val miniBankSize = Dictionary.countTokens(miniBankJson, truncationSettings)
}
```

## Practical Examples

### Multi-User Game System
```kotlin
val gameAIPipe = BedrockPipe()
    .pullGlobalContext()
    .setPageKey("playerStats, worldState, questData, inventory")
    .setPreValidationMiniBankFunction { miniBank, content ->
        // Update each context type based on current game state
        val playerContext = miniBank.contextMap["playerStats"]
        val worldContext = miniBank.contextMap["worldState"]
        val questContext = miniBank.contextMap["questData"]
        val inventoryContext = miniBank.contextMap["inventory"]
        
        // Process player actions
        val action = extractAction(content?.text ?: "")
        when (action) {
            "move" -> {
                worldContext?.contextElements?.add("Player moved to new location")
                playerContext?.contextElements?.add("Stamina decreased")
            }
            "useItem" -> {
                val item = extractItem(content?.text ?: "")
                inventoryContext?.loreBookKeys?.remove(item)
                playerContext?.contextElements?.add("Used item: $item")
            }
            "completeQuest" -> {
                questContext?.contextElements?.add("Quest completed")
                playerContext?.contextElements?.add("Experience gained")
            }
        }
        
        miniBank
    }
    .autoInjectContext("""
        The user prompt contains game context organized by type:
        - playerStats: Current player statistics and status
        - worldState: Current world and location information
        - questData: Active quests and objectives
        - inventory: Player's items and equipment
        
        Use all context types to provide appropriate game responses.
    """)
```

### Document Processing System
```kotlin
val documentProcessor = BedrockPipe()
    .pullGlobalContext()
    .setPageKey("domainKnowledge, processingRules, templates, examples")
    .setPreValidationMiniBankFunction { miniBank, content ->
        val documentType = analyzeDocumentType(content?.text ?: "")
        
        // Filter context based on document type
        miniBank.contextMap.forEach { (pageKey, contextWindow) ->
            when (pageKey) {
                "domainKnowledge" -> {
                    // Keep only relevant domain knowledge
                    contextWindow.selectLoreBookContext(documentType, 1000)
                }
                "processingRules" -> {
                    // Filter rules by document type
                    contextWindow.contextElements.removeIf { 
                        !it.contains(documentType, ignoreCase = true) 
                    }
                }
                "templates" -> {
                    // Select appropriate templates
                    contextWindow.loreBookKeys.values.removeIf { 
                        !it.key.contains(documentType, ignoreCase = true) 
                    }
                }
                "examples" -> {
                    // Keep relevant examples only
                    contextWindow.contextElements.retainAll { 
                        it.contains(documentType, ignoreCase = true) 
                    }
                }
            }
        }
        
        miniBank
    }
    .autoInjectContext("""
        Context is organized by processing type:
        - domainKnowledge: Relevant domain information
        - processingRules: Document processing guidelines
        - templates: Document structure templates
        - examples: Sample documents and outputs
    """)
```

### Multi-Tenant Application
```kotlin
val tenantProcessor = BedrockPipe()
    .pullGlobalContext()
    .setPageKey("tenantConfig, userPermissions, sharedResources, auditLog")
    .setPreValidationMiniBankFunction { miniBank, content ->
        val tenantId = extractTenantId(content?.text ?: "")
        val userId = extractUserId(content?.text ?: "")
        
        // Validate tenant access
        val tenantConfig = miniBank.contextMap["tenantConfig"]
        val userPerms = miniBank.contextMap["userPermissions"]
        
        // Filter based on tenant and user permissions
        tenantConfig?.loreBookKeys?.values?.removeIf { 
            !it.key.startsWith("tenant$tenantId") 
        }
        
        userPerms?.contextElements?.removeIf { element ->
            !element.contains("user$userId") || !element.contains("tenant$tenantId")
        }
        
        // Add audit entry
        val auditLog = miniBank.contextMap["auditLog"]
        auditLog?.contextElements?.add("Access: tenant$tenantId, user$userId, ${System.currentTimeMillis()}")
        
        miniBank
    }
    .autoInjectContext("""
        Multi-tenant context structure:
        - tenantConfig: Tenant-specific configuration
        - userPermissions: User access permissions
        - sharedResources: Available shared resources
        - auditLog: Access and activity logging
    """)
```

## Best Practices

### 1. Logical Page Organization
```kotlin
// Good: Related context grouped logically
.setPageKey("userProfile, userPreferences, userHistory")     // All user-related
.setPageKey("gameWorld, gameRules, gameEvents")             // All game-related
.setPageKey("documentRules, templates, examples")           // All processing-related

// Avoid: Unrelated context mixed together
.setPageKey("userProfile, gameRules, documentTemplates")    // Mixed concerns
```

### 2. Context Page Naming
```kotlin
// Good: Clear, descriptive page names
.setPageKey("playerInventory, worldState, questObjectives")

// Avoid: Generic or unclear names
.setPageKey("data1, data2, data3")
```

### 3. MiniBank Pre-Validation Usage
```kotlin
// Use MiniBank pre-validation when you need page-specific processing
.setPreValidationMiniBankFunction { miniBank, content ->
    // Process each page differently based on its purpose
    miniBank.contextMap.forEach { (pageKey, contextWindow) ->
        when (pageKey) {
            "sensitiveData" -> filterSensitiveContent(contextWindow)
            "publicData" -> enhancePublicContent(contextWindow)
            "temporaryData" -> cleanupExpiredContent(contextWindow)
        }
    }
    miniBank
}
```

### 4. Token Budget Management
```kotlin
// Consider token distribution across multiple pages
val pipe = BedrockPipe()
    .pullGlobalContext()
    .setPageKey("page1, page2, page3, page4")  // 4 pages
    .setContextWindowSize(4000)  // Total budget
    .autoTruncateContext()  // ~1000 tokens per page
```

MiniBank enables sophisticated multi-context applications while maintaining clear separation between different types of contextual information, allowing AI models to access organized, structured context data from multiple sources simultaneously.

## Next Steps

Now that you understand multi-context management, learn about context sharing within pipelines:

**→ [Pipeline Context Integration](pipeline-context-integration.md)** - Context sharing within pipelines
