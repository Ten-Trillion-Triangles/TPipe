# Page Keys and Global Context

## Table of Contents
- [What are Page Keys?](#what-are-page-keys)
- [Enabling Global Context](#enabling-global-context)
- [Single Page Key Usage](#single-page-key-usage)
- [Multiple Page Keys](#multiple-page-keys)
- [Page Key Management Patterns](#page-key-management-patterns)
- [Page Key vs Default Context](#page-key-vs-default-context)
- [Integration with Pipeline Context](#integration-with-pipeline-context)
- [Practical Examples](#practical-examples)
- [Best Practices](#best-practices)

TPipe's page key system enables organized, targeted context retrieval from the global ContextBank. This allows pipes to access specific types of context while maintaining separation between different use cases and data types.

## What are Page Keys?

Page keys are named identifiers that organize context in the ContextBank:
- **Namespace separation**: Different context types stored under different keys
- **Targeted retrieval**: Pipes can access specific context pages
- **Multi-page support**: Single pipe can access multiple context pages
- **Context organization**: Logical separation of user data, session data, knowledge bases, etc.

## Enabling Global Context

### Basic Global Context Access
```kotlin
val pipe = BedrockPipe()
    .pullGlobalContext()  // Enable global context retrieval
    .autoInjectContext("Use the context data provided in the user prompt.")
```

**What this does**:
- Enables the pipe to pull context from ContextBank
- Uses the default banked context window
- Retrieves all available context types (LoreBook, context elements, conversation history)

### Global Context with Automatic Injection
```kotlin
val pipe = BedrockPipe()
    .pullGlobalContext()
    .autoInjectContext("The user prompt contains relevant context data in JSON format.")
```

**Result**:
- Context schema injected into system prompt
- Actual context data injected into user prompt
- AI model receives both structure and data

## Single Page Key Usage

### Setting a Single Page Key
```kotlin
val pipe = BedrockPipe()
    .pullGlobalContext()
    .setPageKey("userSession")  // Access specific context page
    .autoInjectContext("Use the user session context from the user prompt.")
```

**What this does**:
- Retrieves context stored under the "userSession" key in ContextBank
- Ignores other context pages
- Provides targeted, relevant context for the specific use case

### Common Single Page Examples
```kotlin
// User-specific context
val userPipe = BedrockPipe()
    .pullGlobalContext()
    .setPageKey("userProfile")
    .autoInjectContext("Use the user profile data for personalization.")

// Game state context
val gamePipe = BedrockPipe()
    .pullGlobalContext()
    .setPageKey("gameState")
    .autoInjectContext("Use the current game state for decision making.")

// Knowledge base context
val knowledgePipe = BedrockPipe()
    .pullGlobalContext()
    .setPageKey("domainKnowledge")
    .autoInjectContext("Use the domain knowledge for accurate responses.")
```

## Multiple Page Keys

### Setting Multiple Page Keys
```kotlin
val pipe = BedrockPipe()
    .pullGlobalContext()
    .setPageKey("userProfile, gameState, inventory")  // Multiple pages
    .autoInjectContext("Use all provided context types from the user prompt.")
```

**What this does**:
- Retrieves context from multiple named pages
- Combines all specified context pages into a single context window
- Provides comprehensive context from multiple sources

### Multi-Page Use Cases
```kotlin
// Comprehensive user context
val comprehensivePipe = BedrockPipe()
    .pullGlobalContext()
    .setPageKey("userProfile, preferences, sessionHistory")
    .autoInjectContext("""
        The user prompt contains combined context data:
        - User profile information
        - User preferences and settings  
        - Session interaction history
    """)

// Game AI with full context
val gameAIPipe = BedrockPipe()
    .pullGlobalContext()
    .setPageKey("gameState, playerStats, worldKnowledge, questData")
    .autoInjectContext("Use all game context for intelligent NPC responses.")

// Document analysis with background
val analysisPipe = BedrockPipe()
    .pullGlobalContext()
    .setPageKey("domainKnowledge, processingRules, examples")
    .autoInjectContext("Apply domain knowledge, rules, and examples to document analysis.")
```

## Page Key Management Patterns

### Context Storage for Page Keys
```kotlin
// Store context under specific page keys
suspend fun storeUserSession(userId: String, sessionData: ContextWindow) {
    ContextBank.emplaceWithMutex("userSession$userId", sessionData)
}

suspend fun storeGameState(gameId: String, gameData: ContextWindow) {
    ContextBank.emplaceWithMutex("gameState$gameId", gameData)
}

// Use dynamic page keys
val pipe = BedrockPipe()
    .pullGlobalContext()
    .setPageKey("userSession$userId")  // Dynamic page key
    .autoInjectContext("Use the user's session context.")
```

### Context Updates with Page Keys
```kotlin
val pipe = BedrockPipe()
    .pullGlobalContext()
    .setPageKey("userPreferences")
    .autoInjectContext("Use user preferences for personalized responses.")
    .setTransformationFunction { content ->
        // Update user preferences based on interaction
        val preferences = ContextBank.getContextFromBank("userPreferences")
        val newPreference = extractPreference(content.text)
        
        preferences.contextElements.add("Preference: $newPreference")
        
        runBlocking {
            ContextBank.emplaceWithMutex("userPreferences", preferences)
        }
        
        content
    }
```

### Conditional Page Key Usage
```kotlin
val adaptivePipe = BedrockPipe()
    .pullGlobalContext()
    .setPreValidationFunction { contextWindow, content ->
        // Dynamically determine which page keys to use
        val inputType = analyzeInput(content?.text ?: "")
        
        val additionalContext = when (inputType) {
            "technical" -> ContextBank.getContextFromBank("technicalKnowledge")
            "creative" -> ContextBank.getContextFromBank("creativeExamples")
            "support" -> ContextBank.getContextFromBank("supportGuidelines")
            else -> ContextWindow()
        }
        
        contextWindow.merge(additionalContext)
        contextWindow
    }
    .autoInjectContext("Use the relevant context provided for your response type.")
```

## Page Key vs Default Context

### Default Context (No Page Key)
```kotlin
val pipe = BedrockPipe()
    .pullGlobalContext()  // No page key = uses default banked context
    .autoInjectContext("Use the default global context.")
```

**Behavior**:
- Uses `ContextBank.getBankedContextWindow()`
- Accesses the currently active context window
- Good for simple, single-context applications
- Good for shared space where many pipelines are reading and writing to in tandem.

### Page Key Context
```kotlin
val pipe = BedrockPipe()
    .pullGlobalContext()
    .setPageKey("specificContext")  // Uses named page
    .autoInjectContext("Use the specific context page.")
```

**Behavior**:
- Uses `ContextBank.getContextFromBank("specificContext")`
- Accesses specific named context page
- Good for multi-context, organized applications

## Integration with Pipeline Context

### Pipeline Context Override
```kotlin
val pipe = BedrockPipe()
    .pullGlobalContext()      // This will be ignored
    .setPageKey("userData")   // This will be ignored
    .pullPipelineContext()    // This overrides global context
    .autoInjectContext("Use pipeline context instead of global context.")
```

**Priority order**:
1. **Pipeline context** (highest priority)
2. **Global context with page keys**
3. **Default global context**
4. **No context** (lowest priority)

### Combining Pipeline and Global Context
```kotlin
val pipe = BedrockPipe()
    .pullPipelineContext()  // Get pipeline context first
    .setPreValidationFunction { contextWindow, content ->
        // Add specific global context to pipeline context
        val additionalContext = ContextBank.getContextFromBank("staticKnowledge")
        contextWindow.merge(additionalContext)
        contextWindow
    }
    .autoInjectContext("Use both pipeline and global context data.")
```

## Practical Examples

### Multi-User Chat Application
```kotlin
// User-specific pipe
fun createUserPipe(userId: String) = BedrockPipe()
    .pullGlobalContext()
    .setPageKey("userProfile$userId, chatHistory$userId")
    .autoInjectContext("Use user profile and chat history for personalized responses.")

// Admin pipe with broader context
val adminPipe = BedrockPipe()
    .pullGlobalContext()
    .setPageKey("userProfiles, systemRules, moderationGuidelines")
    .autoInjectContext("Use all system context for administrative responses.")
```

### Game Development
```kotlin
// Player interaction pipe
val playerPipe = BedrockPipe()
    .pullGlobalContext()
    .setPageKey("playerStats, currentLocation, inventory, questLog")
    .autoInjectContext("Use complete player context for game responses.")

// NPC behavior pipe
val npcPipe = BedrockPipe()
    .pullGlobalContext()
    .setPageKey("worldLore, npcPersonality, currentEvents")
    .autoInjectContext("Use world and character context for NPC dialogue.")

// Combat system pipe
val combatPipe = BedrockPipe()
    .pullGlobalContext()
    .setPageKey("combatRules, playerStats, enemyData")
    .autoInjectContext("Use combat rules and stats for battle calculations.")
```

### Document Processing System
```kotlin
// Analysis pipe
val analysisPipe = BedrockPipe()
    .pullGlobalContext()
    .setPageKey("domainKnowledge, analysisRules")
    .autoInjectContext("Use domain knowledge and analysis rules.")

// Generation pipe
val generationPipe = BedrockPipe()
    .pullGlobalContext()
    .setPageKey("templates, styleGuides, examples")
    .autoInjectContext("Use templates and style guides for generation.")

// Validation pipe
val validationPipe = BedrockPipe()
    .pullGlobalContext()
    .setPageKey("validationRules, qualityStandards")
    .autoInjectContext("Use validation rules and quality standards.")
```

## Best Practices

### 1. Descriptive Page Key Names
```kotlin
// Good: Clear, descriptive names
.setPageKey("userSessionData")
.setPageKey("gameWorldKnowledge")
.setPageKey("documentProcessingRules")

// Avoid: Generic or unclear names
.setPageKey("data")
.setPageKey("temp")
.setPageKey("stuff")
```

### 2. Logical Context Grouping
```kotlin
// Group related context types
.setPageKey("userProfile, userPreferences, userHistory")  // All user-related
.setPageKey("gameRules, gameState, gameEvents")          // All game-related
.setPageKey("domainKnowledge, processingRules")          // All processing-related
```

### 3. Context Lifecycle Management
```kotlin
// Initialize context pages when needed
suspend fun initializeUserContext(userId: String) {
    val userContext = ContextWindow()
    userContext.contextElements.add("User initialized: $userId")
    ContextBank.emplaceWithMutex("userSession$userId", userContext)
}

// Clean up context pages when done
suspend fun cleanupUserContext(userId: String) {
    ContextBank.emplaceWithMutex("userSession$userId", ContextWindow())
}
```

### 4. Context Validation
```kotlin
val pipe = BedrockPipe()
    .pullGlobalContext()
    .setPageKey("requiredContext")
    .setPreValidationFunction { contextWindow, content ->
        // Validate that required context exists
        if (contextWindow.isEmpty()) {
            throw IllegalStateException("Required context not available")
        }
        contextWindow
    }
    .autoInjectContext("Use the validated context data.")
```

Page keys provide powerful organization and targeting capabilities for global context, enabling sophisticated context management in complex applications while maintaining clean separation between different data types and use cases.

## Next Steps

Now that you understand organized context retrieval, learn about multi-context management:

**→ [MiniBank and Multiple Page Keys](minibank-and-multiple-page-keys.md)** - Multi-context management
