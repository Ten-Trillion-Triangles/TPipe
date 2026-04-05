# Remote Memory System

## Table of Contents
- [Overview](#overview)
- [Architecture](#architecture)
- [When to Use Remote Memory](#when-to-use-remote-memory)
- [Server Setup](#server-setup)
- [Client Configuration](#client-configuration)
- [Operations](#operations)
- [Versioning and Conflict Resolution](#versioning-and-conflict-resolution)
- [Security](#security)
- [Performance Considerations](#performance-considerations)
- [Complete Example](#complete-example)

## Overview

TPipe's remote memory system enables distributed agents to share context windows, todo lists, and locks across network boundaries. The system consists of two components:

- **MemoryServer**: REST API server exposing ContextBank operations
- **MemoryClient**: Client library for accessing remote memory

This architecture allows multiple TPipe instances to coordinate through a centralized memory service, enabling multi-agent systems, distributed workflows, and persistent shared state.

## Architecture

```
┌─────────────┐         ┌─────────────┐         ┌─────────────┐
│   Agent A   │         │   Agent B   │         │   Agent C   │
│ (TPipe)     │         │ (TPipe)     │         │ (TPipe)     │
└──────┬──────┘         └──────┬──────┘         └──────┬──────┘
       │                       │                       │
       │    MemoryClient       │    MemoryClient       │
       └───────────┬───────────┴───────────┬───────────┘
                   │                       │
                   ▼                       ▼
            ┌──────────────────────────────────┐
            │      MemoryServer (REST API)     │
            │  ┌────────────────────────────┐  │
            │  │      ContextBank           │  │
            │  │  - Context Windows         │  │
            │  │  - TodoLists               │  │
            │  │  - ContextLocks            │  │
            │  └────────────────────────────┘  │
            └──────────────────────────────────┘
```

**Key Features:**
- RESTful HTTP API for all ContextBank operations
- Automatic retry logic with exponential backoff
- Lock state caching to reduce network overhead
- Version-based conflict detection
- Authentication via bearer tokens
- Introspection tools for remote lorebook queries

## When to Use Remote Memory

**Use Remote Memory When:**
- Multiple agents need to share context across processes or machines
- Building distributed AI systems with coordinated state
- Implementing persistent memory that survives process restarts
- Coordinating parallel agents working on shared tasks
- Centralizing memory management for monitoring and control

**Use Local Memory When:**
- Single-agent systems with no coordination needs
- Performance-critical applications (local is faster)
- Offline or air-gapped environments
- Simple prototypes and development

## Server Setup

### Configuration

Configure the memory server in your TPipe application:

```kotlin
import com.TTT.Config.TPipeConfig
import com.TTT.Context.MemoryServer
import com.TTT.P2P.P2PRegistry
import io.ktor.server.application.*
import io.ktor.server.routing.*

fun Application.configureMemoryServer()
{
    // Set authentication mechanism
    P2PRegistry.globalAuthMechanism = { authHeader ->
        val expectedToken = "Bearer ${System.getenv("MEMORY_AUTH_TOKEN")}"
        authHeader == expectedToken
    }
    
    // Enable versioning for conflict detection
    TPipeConfig.enforceMemoryVersioning = true
    
    // Configure routing
    routing {
        MemoryServer.configureMemoryRouting(this)
    }
}
```

### Endpoints

The MemoryServer exposes the following REST endpoints:

**Context Bank:**
- `GET /context/bank/keys` - List all context window keys
- `GET /context/bank/{key}` - Retrieve context window
- `POST /context/bank/{key}` - Store/update context window
- `DELETE /context/bank/{key}` - Delete context window
- `GET /context/bank/{key}/query` - Query lorebook entries
- `GET /context/bank/{key}/simulate` - Simulate lorebook triggers

**TodoList:**
- `GET /context/todo/keys` - List all todo list keys
- `GET /context/todo/{key}` - Retrieve todo list
- `POST /context/todo/{key}` - Store/update todo list

**ContextLock:**
- `GET /context/lock/keys` - List all lock keys
- `GET /context/lock/{key}/state` - Check if key is locked
- `GET /context/lock/page/{pageKey}/state` - Check if page is locked
- `POST /context/lock/` - Add lock
- `DELETE /context/lock/` - Remove lock

## Client Configuration

### Basic Setup

Configure clients to connect to the remote memory server:

```kotlin
import com.TTT.Config.TPipeConfig

// Set remote memory URL
TPipeConfig.remoteMemoryUrl = "http://memory-server:8080"

// Set authentication token
TPipeConfig.remoteMemoryAuthToken = "Bearer your-secret-token"
```

### Environment Variables

```bash
export TPIPE_REMOTE_MEMORY_URL="http://memory-server:8080"
export MEMORY_AUTH_TOKEN="your-secret-token"
```

## Operations

### Context Window Operations

```kotlin
import com.TTT.Context.MemoryClient
import com.TTT.Context.ContextWindow

suspend fun contextOperations()
{
    // List all keys
    val keys = MemoryClient.getPageKeys()
    println("Available keys: $keys")
    
    // Retrieve context window
    val context = MemoryClient.getContextWindow("agent-memory")
    
    // Modify and store
    if (context != null)
    {
        context.addText("New information discovered")
        MemoryClient.emplaceContextWindow("agent-memory", context)
    }
    
    // Delete context
    MemoryClient.deleteContextWindow("old-key")
}
```

### TodoList Operations

```kotlin
import com.TTT.Context.MemoryClient
import com.TTT.Context.TodoList

suspend fun todoOperations()
{
    // Retrieve todo list
    val todo = MemoryClient.getTodoList("project-tasks") ?: TodoList()
    
    // Add task
    todo.addTask("Implement feature X", "Details about feature X")
    
    // Store updated list
    MemoryClient.emplaceTodoList("project-tasks", todo)
    
    // List all todo keys
    val todoKeys = MemoryClient.getTodoListKeys()
}
```

### Lock Operations

```kotlin
import com.TTT.Context.MemoryClient
import com.TTT.Context.LockRequest

suspend fun lockOperations()
{
    // Check lock state
    val isLocked = MemoryClient.isKeyLocked("critical-key")
    
    if (!isLocked)
    {
        // Acquire lock
        val lockRequest = LockRequest(
            key = "critical-key",
            lockState = true,
            isPageKey = false
        )
        MemoryClient.addLock(lockRequest)
        
        // Perform critical operation
        // ...
        
        // Release lock
        lockRequest.lockState = false
        MemoryClient.addLock(lockRequest)
    }
}
```

### Remote Lorebook Queries

```kotlin
import com.TTT.Context.MemoryClient

suspend fun lorebookOperations()
{
    // Query lorebook entries
    val results = MemoryClient.queryLorebook(
        key = "agent-memory",
        query = "database",
        minWeight = 50,
        requiredKeys = listOf("technical"),
        aliasKeys = listOf("db", "sql")
    )
    
    results.forEach { result ->
        println("Entry: ${result.entry.text}")
        println("Weight: ${result.weight}")
    }
    
    // Simulate triggers
    val triggered = MemoryClient.simulateLorebookTrigger(
        key = "agent-memory",
        text = "We need to query the database"
    )
    
    println("Triggered entries: $triggered")
}
```

## Versioning and Conflict Resolution

The remote memory system uses version numbers to detect conflicts when multiple clients modify the same resource.

### How Versioning Works

1. Each `ContextWindow` and `TodoList` has a `version` field
2. Server increments version on every write
3. Client writes with older versions are rejected
4. Clients must fetch latest version, merge changes, and retry

### Handling Conflicts

```kotlin
import com.TTT.Context.MemoryClient
import com.TTT.Context.ContextWindow

suspend fun handleConflict()
{
    var success = false
    var retries = 0
    val maxRetries = 3
    
    while (!success && retries < maxRetries)
    {
        // Fetch latest version
        val context = MemoryClient.getContextWindow("shared-memory") ?: ContextWindow()
        
        // Make modifications
        context.addText("Agent contribution")
        
        // Attempt to save
        success = MemoryClient.emplaceContextWindow("shared-memory", context)
        
        if (!success)
        {
            retries++
            kotlinx.coroutines.delay(100L * retries)
        }
    }
}
```

### Configuration

```kotlin
import com.TTT.Config.TPipeConfig

// Enable version enforcement (recommended for production)
TPipeConfig.enforceMemoryVersioning = true

// Disable for development (allows overwrites)
TPipeConfig.enforceMemoryVersioning = false
```

## Security

### Authentication

The memory server uses bearer token authentication:

```kotlin
// Server-side: Set auth mechanism
P2PRegistry.globalAuthMechanism = { authHeader ->
    val expectedToken = "Bearer ${System.getenv("MEMORY_AUTH_TOKEN")}"
    authHeader == expectedToken
}

// Client-side: Set auth token
TPipeConfig.remoteMemoryAuthToken = "Bearer your-secret-token"
```

### Best Practices

1. **Use HTTPS in production** - Never send tokens over unencrypted connections
2. **Rotate tokens regularly** - Implement token rotation policies
3. **Network isolation** - Run memory server in private network
4. **Rate limiting** - Implement rate limits at reverse proxy level
5. **Audit logging** - Log all memory access for security monitoring
6. **Least privilege** - Use different tokens for different agent roles

### ContextLock Integration

Remote memory respects ContextLock restrictions:

```kotlin
import com.TTT.Context.ContextLock
import com.TTT.Context.MemoryClient

suspend fun secureAccess()
{
    // Lock a key remotely
    val lockRequest = LockRequest(
        key = "sensitive-data",
        lockState = true,
        isPageKey = false
    )
    MemoryClient.addLock(lockRequest)
    
    // Attempts to access locked keys will fail
    val context = MemoryClient.getContextWindow("sensitive-data")
    // Returns null if locked
}
```

## Performance Considerations

### Caching

MemoryClient implements caching for lock states:

- **Cache TTL**: 1 second (configurable)
- **Reduces network calls** during lorebook selection
- **Automatic invalidation** on write operations

### Retry Logic

Built-in retry mechanism for transient failures:

- **Max retries**: 3 attempts
- **Backoff**: 100ms * retry_count
- **Handles**: Network timeouts, temporary server unavailability

### Optimization Tips

1. **Batch operations** - Group multiple reads/writes when possible
2. **Use skipRemote flag** - Server-side operations use `skipRemote=true` to avoid loops
3. **Cache locally** - Keep frequently accessed context in local memory
4. **Minimize writes** - Only write when context actually changes
5. **Use appropriate storage modes** - Configure `StorageMode` based on persistence needs

## Complete Example

### Multi-Agent Coordination System

```kotlin
import com.TTT.Config.TPipeConfig
import com.TTT.Context.MemoryClient
import com.TTT.Context.ContextWindow
import com.TTT.Context.TodoList
import bedrockPipe.BedrockPipe

// Agent configuration
data class AgentConfig(
    val agentId: String,
    val memoryKey: String,
    val todoKey: String
)

class DistributedAgent(private val config: AgentConfig)
{
    private val pipe = BedrockPipe()
        .setRegion("us-east-1")
        .setModel("anthropic.claude-3-sonnet-20240229-v1:0")
        .setSystemPrompt("You are agent ${config.agentId}")
    
    suspend fun initialize()
    {
        // Configure remote memory
        TPipeConfig.remoteMemoryUrl = System.getenv("MEMORY_SERVER_URL")
        TPipeConfig.remoteMemoryAuthToken = "Bearer ${System.getenv("MEMORY_TOKEN")}"
        TPipeConfig.enforceMemoryVersioning = true
    }
    
    suspend fun processTask()
    {
        // Fetch shared context
        val sharedContext = MemoryClient.getContextWindow("shared-knowledge") 
            ?: ContextWindow()
        
        // Fetch agent-specific context
        val agentContext = MemoryClient.getContextWindow(config.memoryKey) 
            ?: ContextWindow()
        
        // Fetch tasks
        val tasks = MemoryClient.getTodoList(config.todoKey) ?: TodoList()
        
        if (tasks.tasks.isNotEmpty())
        {
            val task = tasks.tasks.first { !it.completed }
            
            // Process with AI
            pipe.setUserPrompt("""
                Shared Knowledge: ${sharedContext.text}
                Your Memory: ${agentContext.text}
                Task: ${task.description}
            """.trimIndent())
            
            val result = pipe.execute("")
            
            // Update agent memory
            agentContext.addText("Completed: ${task.description}\nResult: ${result.text}")
            MemoryClient.emplaceContextWindow(config.memoryKey, agentContext)
            
            // Update shared knowledge
            sharedContext.addText("${config.agentId}: ${result.text}")
            MemoryClient.emplaceContextWindow("shared-knowledge", sharedContext)
            
            // Mark the task as completed in local state and persist to remote memory
            task.completed = true
            MemoryClient.emplaceTodoList(config.todoKey, tasks)
        }
    }
}

// Usage
suspend fun main()
{
    val agent = DistributedAgent(
        AgentConfig(
            agentId = "agent-1",
            memoryKey = "agent-1-memory",
            todoKey = "team-tasks"
        )
    )
    
    agent.initialize()
    
    while (true)
    {
        agent.processTask()
        kotlinx.coroutines.delay(5000)
    }
}
```

## See Also

- [ContextBank API](../api/context-bank.md) - Complete ContextBank API reference
- [ContextLock API](../api/context-lock.md) - Lock management and security
- [Memory Introspection](memory-introspection.md) - Agent memory access control
- [P2P Overview](p2p/p2p-overview.md) - Agent-to-agent communication
## Next Steps

- [Memory Introspection](memory-introspection.md) - Continue with memory inspection and editing tools.
