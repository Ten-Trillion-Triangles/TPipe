# Memory Introspection

## Table of Contents
- [Overview](#overview)
- [The Leash Concept](#the-leash-concept)
- [MemoryIntrospectionConfig](#memoryintrospectionconfig)
- [Scoped Execution](#scoped-execution)
- [Permission Model](#permission-model)
- [MemoryIntrospectionTools](#memoryintrospectiontools)
- [Integration with ContextLock](#integration-with-contextlock)
- [Complete Examples](#complete-examples)
- [Security Best Practices](#security-best-practices)

## Overview

Memory introspection allows autonomous agents to query and manipulate TPipe's memory system (ContextBank, lorebooks, TodoLists) through a controlled, permission-based interface. This system provides a "leash" that restricts what memory an agent can access, preventing unauthorized data exposure or modification.

**Key Features:**
- Scoped permission model for agent memory access
- Page-key-level access control
- Separate read/write permissions
- Integration with ContextLock for layered security
- PCP-callable tools for agent use
- Thread-safe and coroutine-safe scoping

**Use Cases:**
- Autonomous agents with limited memory access
- Multi-agent systems with isolated memory spaces
- Sandboxed AI assistants
- Hierarchical agent architectures with different privilege levels

## The Leash Concept

The "leash" is a security boundary that defines what an agent can see and do within the memory system. Without a leash, agents would have unrestricted access to all memory, creating security and privacy risks.

```
┌─────────────────────────────────────────────┐
│           ContextBank (All Memory)          │
│  ┌──────┐  ┌──────┐  ┌──────┐  ┌──────┐   │
│  │ Page │  │ Page │  │ Page │  │ Page │   │
│  │  A   │  │  B   │  │  C   │  │  D   │   │
│  └──────┘  └──────┘  └──────┘  └──────┘   │
└─────────────────────────────────────────────┘
                    │
                    │ Leash restricts access
                    ▼
         ┌──────────────────────┐
         │  Agent with Leash    │
         │  Allowed: [A, B]     │
         │  Read: ✓  Write: ✗   │
         └──────────────────────┘
                    │
                    ▼
         Can only see Pages A and B
         Cannot modify anything
```

## MemoryIntrospectionConfig

The configuration object that defines an agent's memory permissions.

### Structure

```kotlin
data class MemoryIntrospectionConfig(
    var allowedPageKeys: MutableSet<String> = mutableSetOf(),
    var allowPageCreation: Boolean = false,
    var allowRead: Boolean = true,
    var allowWrite: Boolean = false
)
```

### Fields

**`allowedPageKeys`**
- Set of page keys the agent can access
- Use `"*"` wildcard to allow all pages (subject to ContextLock)
- Empty set = no access

**`allowPageCreation`**
- Whether agent can create new page keys in ContextBank
- Default: `false`

**`allowRead`**
- Whether agent has read access to allowed pages
- Default: `true`

**`allowWrite`**
- Whether agent has write access (add/update/delete) to allowed pages
- Default: `false`

### Examples

```kotlin
import com.TTT.Context.MemoryIntrospectionConfig

// Read-only access to specific pages
val readOnlyConfig = MemoryIntrospectionConfig(
    allowedPageKeys = mutableSetOf("public-data", "shared-knowledge"),
    allowRead = true,
    allowWrite = false
)

// Full access to all pages
val adminConfig = MemoryIntrospectionConfig(
    allowedPageKeys = mutableSetOf("*"),
    allowPageCreation = true,
    allowRead = true,
    allowWrite = true
)

// Write access to agent's own memory
val agentConfig = MemoryIntrospectionConfig(
    allowedPageKeys = mutableSetOf("agent-1-memory"),
    allowPageCreation = false,
    allowRead = true,
    allowWrite = true
)

// No access (deny-all)
val deniedConfig = MemoryIntrospectionConfig(
    allowRead = false,
    allowWrite = false
)
```

## Scoped Execution

Execute code within a specific introspection scope using `withScope()` or `withCoroutineScope()`.

### Synchronous Scoping

```kotlin
import com.TTT.Context.MemoryIntrospection
import com.TTT.Context.MemoryIntrospectionConfig

fun synchronousExample()
{
    val config = MemoryIntrospectionConfig(
        allowedPageKeys = mutableSetOf("public-data"),
        allowRead = true,
        allowWrite = false
    )
    
    val result = MemoryIntrospection.withScope(config) {
        // All memory operations here respect the config
        MemoryIntrospectionTools.listPageKeys()
    }
    
    println("Accessible pages: $result")
}
```

### Asynchronous Scoping

```kotlin
import com.TTT.Context.MemoryIntrospection
import com.TTT.Context.MemoryIntrospectionConfig
import com.TTT.Context.MemoryIntrospectionTools

suspend fun asynchronousExample()
{
    val config = MemoryIntrospectionConfig(
        allowedPageKeys = mutableSetOf("agent-memory"),
        allowRead = true,
        allowWrite = true
    )
    
    val results = MemoryIntrospection.withCoroutineScope(config) {
        // Coroutine-safe scoping
        MemoryIntrospectionTools.queryLorebook(
            pageKey = "agent-memory",
            query = "important"
        )
    }
    
    results.forEach { result ->
        println("Found: ${result.entry.key}")
    }
}
```

### Nested Scopes

```kotlin
suspend fun nestedScopes()
{
    val outerConfig = MemoryIntrospectionConfig(
        allowedPageKeys = mutableSetOf("*"),
        allowRead = true,
        allowWrite = true
    )
    
    MemoryIntrospection.withCoroutineScope(outerConfig) {
        // Outer scope: full access
        val allPages = MemoryIntrospectionTools.listPageKeys()
        
        val innerConfig = MemoryIntrospectionConfig(
            allowedPageKeys = mutableSetOf("restricted"),
            allowRead = true,
            allowWrite = false
        )
        
        MemoryIntrospection.withCoroutineScope(innerConfig) {
            // Inner scope: restricted access
            val restrictedPages = MemoryIntrospectionTools.listPageKeys()
            // Only returns ["restricted"]
        }
        
        // Back to outer scope: full access restored
    }
}
```

## Permission Model

### Read Permissions

When `allowRead = true`, agents can:
- List accessible page keys
- Retrieve lorebook entries
- Query lorebooks
- Simulate lorebook triggers
- Search memory
- Retrieve todo lists

### Write Permissions

When `allowWrite = true`, agents can:
- Update lorebook entries
- Delete lorebook entries
- Update todo lists
- Create new pages (if `allowPageCreation = true`)

### Permission Checks

```kotlin
import com.TTT.Context.MemoryIntrospection

// Check if agent can read a page
val canRead = MemoryIntrospection.canRead("page-key")

// Check if agent can write to a page
val canWrite = MemoryIntrospection.canWrite("page-key")

// Check if specific page is allowed
val isAllowed = MemoryIntrospection.isPageAllowed("page-key")

// Get current config
val currentConfig = MemoryIntrospection.getCurrentConfig()
```

## MemoryIntrospectionTools

Collection of PCP-callable tools that respect the introspection leash.

### Available Tools

**Read Operations:**
- `listPageKeys()` - List all accessible page keys
- `getLorebookEntry(pageKey, key)` - Get specific lorebook entry
- `getLorebook(pageKey)` - Get entire lorebook for a page
- `queryLorebook(pageKey, query, minWeight, ...)` - Structured lorebook search
- `simulateLorebookTrigger(pageKey, text)` - Test what entries would trigger
- `searchMemory(pageKey, query, extractRegex)` - Deep search across lorebook and context
- `getTodoList(pageKey)` - Get todo list for a page

**Write Operations:**
- `updateLorebookEntry(pageKey, entry)` - Add or update lorebook entry
- `deleteLorebookEntry(pageKey, key)` - Delete lorebook entry
- `updateTodoList(pageKey, todoList)` - Update todo list

### Registering Tools with PCP

```kotlin
import com.TTT.PipeContextProtocol.PcpContext
import com.TTT.Context.MemoryIntrospectionTools

val pcpContext = PcpContext()

// Register all introspection tools
MemoryIntrospectionTools.registerAndEnable(pcpContext)

// Now agents can call these tools via PCP
```

### Using Tools Directly

```kotlin
import com.TTT.Context.MemoryIntrospectionTools
import com.TTT.Context.MemoryIntrospectionConfig
import com.TTT.Context.MemoryIntrospection

suspend fun useTools()
{
    val config = MemoryIntrospectionConfig(
        allowedPageKeys = mutableSetOf("research-data"),
        allowRead = true,
        allowWrite = false
    )
    
    MemoryIntrospection.withCoroutineScope(config) {
        // Query lorebook
        val results = MemoryIntrospectionTools.queryLorebook(
            pageKey = "research-data",
            query = "machine learning",
            minWeight = 50
        )
        
        // Simulate triggers
        val triggered = MemoryIntrospectionTools.simulateLorebookTrigger(
            pageKey = "research-data",
            text = "We need to train a neural network"
        )
        
        // Search memory
        val searchResults = MemoryIntrospectionTools.searchMemory(
            pageKey = "research-data",
            query = "tensorflow",
            extractRegex = "version \\d+\\.\\d+"
        )
    }
}
```

## Integration with ContextLock

Memory introspection respects ContextLock restrictions, providing layered security.

### How It Works

1. **Introspection leash** - First layer: defines allowed pages
2. **ContextLock** - Second layer: enforces locks on keys and pages

Even if a page is in `allowedPageKeys`, locked pages/keys are inaccessible.

### Example

```kotlin
import com.TTT.Context.ContextLock
import com.TTT.Context.MemoryIntrospection
import com.TTT.Context.MemoryIntrospectionConfig
import com.TTT.Context.MemoryIntrospectionTools

suspend fun layeredSecurity()
{
    // Lock a page
    ContextLock.lockPage("sensitive-data")
    
    // Agent has permission to access it
    val config = MemoryIntrospectionConfig(
        allowedPageKeys = mutableSetOf("sensitive-data"),
        allowRead = true
    )
    
    MemoryIntrospection.withCoroutineScope(config) {
        // But ContextLock prevents access
        val pages = MemoryIntrospectionTools.listPageKeys()
        // Returns empty list - page is locked
        
        val lorebook = MemoryIntrospectionTools.getLorebook("sensitive-data")
        // Returns empty map - page is locked
    }
    
    // Unlock the page
    ContextLock.unlockPage("sensitive-data")
    
    MemoryIntrospection.withCoroutineScope(config) {
        // Now access is granted
        val pages = MemoryIntrospectionTools.listPageKeys()
        // Returns ["sensitive-data"]
    }
}
```

## Complete Examples

### Sandboxed Agent with Limited Memory

```kotlin
import com.TTT.Context.*
import com.TTT.PipeContextProtocol.PcpContext
import bedrockPipe.BedrockPipe

class SandboxedAgent(private val agentId: String)
{
    private val pipe = BedrockPipe()
        .setRegion("us-east-1")
        .setModel("anthropic.claude-3-sonnet-20240229-v1:0")
    
    private val pcpContext = PcpContext()
    
    init
    {
        // Register introspection tools
        MemoryIntrospectionTools.registerAndEnable(pcpContext)
        
        // Apply PCP context to pipe
        pipe.setPcpContext(pcpContext)
    }
    
    suspend fun executeTask(task: String): String
    {
        // Define agent's memory permissions
        val config = MemoryIntrospectionConfig(
            allowedPageKeys = mutableSetOf("$agentId-memory", "shared-knowledge"),
            allowPageCreation = false,
            allowRead = true,
            allowWrite = true  // Can write to own memory
        )
        
        return MemoryIntrospection.withCoroutineScope(config) {
            pipe.setSystemPrompt("""
                You are a sandboxed AI agent with limited memory access.
                You can read from: ${config.allowedPageKeys.joinToString(", ")}
                You can write to your own memory: $agentId-memory
                
                Use the memory introspection tools to access information.
            """.trimIndent())
            
            pipe.setUserPrompt(task)
            
            val result = pipe.execute("")
            result.text
        }
    }
}

// Usage
suspend fun main()
{
    val agent = SandboxedAgent("agent-1")
    
    val result = agent.executeTask("""
        Search the shared-knowledge for information about Kotlin.
        Store what you learn in your own memory.
    """.trimIndent())
    
    println(result)
}
```

### Multi-Agent with Different Permission Levels

```kotlin
import com.TTT.Context.*

enum class AgentRole
{
    ADMIN,
    WORKER,
    OBSERVER
}

class HierarchicalAgent(
    private val agentId: String,
    private val role: AgentRole
)
{
    private fun getConfig(): MemoryIntrospectionConfig
    {
        return when (role)
        {
            AgentRole.ADMIN -> MemoryIntrospectionConfig(
                allowedPageKeys = mutableSetOf("*"),
                allowPageCreation = true,
                allowRead = true,
                allowWrite = true
            )
            
            AgentRole.WORKER -> MemoryIntrospectionConfig(
                allowedPageKeys = mutableSetOf("$agentId-memory", "shared-workspace"),
                allowPageCreation = false,
                allowRead = true,
                allowWrite = true
            )
            
            AgentRole.OBSERVER -> MemoryIntrospectionConfig(
                allowedPageKeys = mutableSetOf("public-data"),
                allowPageCreation = false,
                allowRead = true,
                allowWrite = false
            )
        }
    }
    
    suspend fun accessMemory()
    {
        val config = getConfig()
        
        MemoryIntrospection.withCoroutineScope(config) {
            val pages = MemoryIntrospectionTools.listPageKeys()
            println("$agentId ($role) can access: $pages")
            
            when (role)
            {
                AgentRole.ADMIN -> {
                    // Admin can do anything
                    val allPages = MemoryIntrospectionTools.listPageKeys()
                    println("Admin sees all pages: $allPages")
                }
                
                AgentRole.WORKER -> {
                    // Worker can read and write to workspace
                    val results = MemoryIntrospectionTools.queryLorebook(
                        pageKey = "shared-workspace",
                        query = "task"
                    )
                    println("Worker found ${results.size} tasks")
                }
                
                AgentRole.OBSERVER -> {
                    // Observer can only read public data
                    val publicData = MemoryIntrospectionTools.getLorebook("public-data")
                    println("Observer sees ${publicData.size} public entries")
                }
            }
        }
    }
}

// Usage
suspend fun main()
{
    val admin = HierarchicalAgent("admin-1", AgentRole.ADMIN)
    val worker = HierarchicalAgent("worker-1", AgentRole.WORKER)
    val observer = HierarchicalAgent("observer-1", AgentRole.OBSERVER)
    
    admin.accessMemory()
    worker.accessMemory()
    observer.accessMemory()
}
```

## Security Best Practices

### 1. Principle of Least Privilege

Grant agents only the minimum permissions needed:

```kotlin
// ✓ Good: Specific pages, read-only
val config = MemoryIntrospectionConfig(
    allowedPageKeys = mutableSetOf("agent-workspace"),
    allowRead = true,
    allowWrite = false
)

// ✗ Bad: Wildcard with write access
val badConfig = MemoryIntrospectionConfig(
    allowedPageKeys = mutableSetOf("*"),
    allowRead = true,
    allowWrite = true
)
```

### 2. Layer Security with ContextLock

Use both introspection leash and ContextLock:

```kotlin
// Lock sensitive pages
ContextLock.lockPage("credentials")
ContextLock.lockPage("api-keys")

// Even if agent config allows access, locks prevent it
val config = MemoryIntrospectionConfig(
    allowedPageKeys = mutableSetOf("*"),
    allowRead = true
)
```

### 3. Audit Agent Actions

Log memory access for security monitoring:

```kotlin
suspend fun auditedAccess(agentId: String, config: MemoryIntrospectionConfig)
{
    println("[$agentId] Starting memory access with config: $config")
    
    MemoryIntrospection.withCoroutineScope(config) {
        val pages = MemoryIntrospectionTools.listPageKeys()
        println("[$agentId] Accessed pages: $pages")
        
        // Perform operations
    }
    
    println("[$agentId] Completed memory access")
}
```

### 4. Separate Agent Memory Spaces

Give each agent its own isolated memory:

```kotlin
fun createAgentConfig(agentId: String) = MemoryIntrospectionConfig(
    allowedPageKeys = mutableSetOf("$agentId-memory"),
    allowPageCreation = false,
    allowRead = true,
    allowWrite = true
)
```

### 5. Validate Agent Modifications

Review changes made by agents with write access:

```kotlin
suspend fun validateAgentWrite(agentId: String, pageKey: String)
{
    val before = ContextBank.getContextFromBank(pageKey)
    
    // Agent makes changes
    val config = MemoryIntrospectionConfig(
        allowedPageKeys = mutableSetOf(pageKey),
        allowWrite = true
    )
    
    MemoryIntrospection.withCoroutineScope(config) {
        // Agent operations
    }
    
    val after = ContextBank.getContextFromBank(pageKey)
    
    // Compare and log changes
    if (before.version != after.version)
    {
        println("[$agentId] Modified $pageKey: v${before.version} -> v${after.version}")
    }
}
```

## See Also

- [Remote Memory](remote-memory.md) - Distributed memory system
- [ContextBank API](../api/context-bank.md) - Complete ContextBank reference
- [ContextLock API](../api/context-lock.md) - Lock management and security
- [Pipe Context Protocol](pipe-context-protocol.md) - Tool execution framework
## Next Steps

- [TraceServer - Remote Trace Dashboard](trace-server.md) - Continue into remote trace viewing.
