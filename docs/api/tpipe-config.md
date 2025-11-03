# TPipeConfig Object API

## Table of Contents
- [Overview](#overview)
- [Public Properties](#public-properties)
- [Public Functions](#public-functions)
- [Usage Examples](#usage-examples)
- [Integration Points](#integration-points)

## Overview

`TPipeConfig` is a singleton object that manages TPipe's file system configuration and directory structure. It provides centralized control over where TPipe stores persistent data and enables instance isolation to prevent conflicts when running multiple TPipe applications.

```kotlin
object TPipeConfig
```

**Key responsibilities:**
- Define the base configuration directory (default: `~/.tpipe`)
- Provide instance isolation through unique instance IDs
- Generate paths for different storage types (lorebooks, todo lists, traces)
- Enable customization of storage locations

## Public Properties

### `configDir`

```kotlin
var configDir: String = "${getHomeFolder()}/.tpipe"
```

The base directory where TPipe stores all persistent data.

**Default:** `~/.tpipe` (user's home directory)

**Purpose:** Root location for all TPipe storage including memory persistence, debug traces, and configuration files.

**Customization:**
```kotlin
// Change to custom location
TPipeConfig.configDir = "/opt/myapp/tpipe-data"
```

**When to customize:**
- Running TPipe in containerized environments
- Storing data on specific volumes or drives
- Implementing custom backup strategies
- Meeting organizational file system requirements

### `instanceID`

```kotlin
var instanceID: String = "TPipe-Default"
```

Unique identifier for this TPipe instance.

**Default:** `"TPipe-Default"`

**Purpose:** Prevents multiple TPipe applications from interfering with each other's stored data. Each instance gets its own subdirectory under `configDir`.

**Customization:**
```kotlin
// Set unique ID for this application
TPipeConfig.instanceID = "MyApp-Production"
```

**When to customize:**
- Running multiple TPipe applications simultaneously
- Separating development, staging, and production data
- Creating isolated test environments
- Multi-tenant applications

## Public Functions

### `getTPipeConfigDir(): String`

Returns the complete configuration directory path including instance ID.

**Returns:** `"${configDir}/${instanceID}"`

**Example:**
```kotlin
val configPath = TPipeConfig.getTPipeConfigDir()
// Returns: "/home/user/.tpipe/TPipe-Default"
```

**Purpose:** Provides the root directory for this specific TPipe instance. All instance-specific data is stored under this path.

---

### `getMemoryDir(): String`

Returns the directory where TPipe stores persistent memory data.

**Returns:** `"${getTPipeConfigDir()}/memory"`

**Example:**
```kotlin
val memoryPath = TPipeConfig.getMemoryDir()
// Returns: "/home/user/.tpipe/TPipe-Default/memory"
```

**Purpose:** Root directory for all memory persistence features including lorebooks and todo lists. Future memory storage systems will also use this directory.

---

### `getLorebookDir(): String`

Returns the directory where lorebook data is persisted.

**Returns:** `"${getMemoryDir()}/lorebook"`

**Example:**
```kotlin
val lorebookPath = TPipeConfig.getLorebookDir()
// Returns: "/home/user/.tpipe/TPipe-Default/memory/lorebook"
```

**Purpose:** Storage location for persisted lorebook entries. ContextBank uses this path when saving lorebooks to disk.

**File format:** Individual lorebook files are stored as `<key>.bank`

---

### `getTodoListDir(): String`

Returns the directory where todo lists are persisted.

**Returns:** `"${getMemoryDir()}/todo"`

**Example:**
```kotlin
val todoPath = TPipeConfig.getTodoListDir()
// Returns: "/home/user/.tpipe/TPipe-Default/memory/todo"
```

**Purpose:** Storage location for persisted todo lists. ContextBank uses this path when saving todo lists to disk.

**File format:** Individual todo list files are stored as `<key>.todo`

---

### `getDebugDir(): String`

Returns the directory where debug data is stored.

**Returns:** `"${configDir}/debug"`

**Example:**
```kotlin
val debugPath = TPipeConfig.getDebugDir()
// Returns: "/home/user/.tpipe/debug"
```

**Purpose:** Root directory for debug-related output. Note that debug data is stored at the `configDir` level, not per-instance, allowing debug data to be shared across instances if needed.

---

### `getTraceDir(): String`

Returns the directory where execution traces are stored.

**Returns:** `"${getDebugDir()}/trace"`

**Example:**
```kotlin
val tracePath = TPipeConfig.getTraceDir()
// Returns: "/home/user/.tpipe/debug/trace"
```

**Purpose:** Storage location for pipe and pipeline execution traces. Trace files are written here when tracing is enabled.

---

### `getTodoDir(): String`

Returns the directory where todo-related data is stored.

**Returns:** `"${getMemoryDir()}/todo"`

**Example:**
```kotlin
val todoPath = TPipeConfig.getTodoDir()
// Returns: "/home/user/.tpipe/TPipe-Default/memory/todo"
```

**Purpose:** Alias for `getTodoListDir()`. Provides the same path for todo list storage.

## Usage Examples

### Basic Configuration

Default configuration works out of the box:

```kotlin
// Uses default settings
val todoList = TodoList()
ContextBank.emplaceTodoList("my-tasks", todoList, writeToDisk = true)
// Saves to: ~/.tpipe/TPipe-Default/memory/todo/my-tasks.todo
```

### Custom Configuration Directory

Change where TPipe stores all data:

```kotlin
// Set custom base directory
TPipeConfig.configDir = "/var/lib/myapp/tpipe"

// Now all storage uses the new location
val todoList = TodoList()
ContextBank.emplaceTodoList("tasks", todoList, writeToDisk = true)
// Saves to: /var/lib/myapp/tpipe/TPipe-Default/memory/todo/tasks.todo
```

### Instance Isolation

Run multiple TPipe applications without conflicts:

```kotlin
// Application 1
TPipeConfig.instanceID = "WebService-API"
ContextBank.emplaceTodoList("tasks", todoList, writeToDisk = true)
// Saves to: ~/.tpipe/WebService-API/memory/todo/tasks.todo

// Application 2 (different process)
TPipeConfig.instanceID = "BackgroundWorker"
ContextBank.emplaceTodoList("tasks", todoList, writeToDisk = true)
// Saves to: ~/.tpipe/BackgroundWorker/memory/todo/tasks.todo

// No conflicts - each instance has its own directory
```

### Environment-Specific Configuration

Configure based on deployment environment:

```kotlin
val environment = System.getenv("ENVIRONMENT") ?: "development"

TPipeConfig.instanceID = when (environment) {
    "production" -> "MyApp-Prod"
    "staging" -> "MyApp-Stage"
    "development" -> "MyApp-Dev"
    else -> "MyApp-${environment}"
}

// Each environment gets isolated storage
```

### Custom Storage Location for Containers

Configure for containerized deployments:

```kotlin
// Use mounted volume in container
TPipeConfig.configDir = "/data/tpipe"
TPipeConfig.instanceID = System.getenv("POD_NAME") ?: "default"

// Data persists to mounted volume
// Each pod gets its own directory if POD_NAME is set
```

### Checking Storage Paths

Verify where data will be stored:

```kotlin
println("TPipe Configuration:")
println("  Base Dir: ${TPipeConfig.configDir}")
println("  Instance: ${TPipeConfig.instanceID}")
println("  Config Dir: ${TPipeConfig.getTPipeConfigDir()}")
println("  Memory Dir: ${TPipeConfig.getMemoryDir()}")
println("  Lorebook Dir: ${TPipeConfig.getLorebookDir()}")
println("  TodoList Dir: ${TPipeConfig.getTodoListDir()}")
println("  Trace Dir: ${TPipeConfig.getTraceDir()}")
```

### Creating Custom Directory Structure

Ensure directories exist before use:

```kotlin
import java.io.File

// Set custom configuration
TPipeConfig.configDir = "/opt/myapp/data"
TPipeConfig.instanceID = "production"

// Create directory structure
File(TPipeConfig.getMemoryDir()).mkdirs()
File(TPipeConfig.getLorebookDir()).mkdirs()
File(TPipeConfig.getTodoListDir()).mkdirs()
File(TPipeConfig.getTraceDir()).mkdirs()

// Now safe to use persistence features
```

## Integration Points

### ContextBank

ContextBank uses TPipeConfig for all disk persistence:

**Lorebook Storage:**
```kotlin
// ContextBank internally uses:
val path = "${TPipeConfig.getLorebookDir()}/${key}.bank"
```

**TodoList Storage:**
```kotlin
// ContextBank internally uses:
val path = "${TPipeConfig.getTodoListDir()}/${key}.todo"
```

See [ContextBank API](context-bank.md) for persistence methods.

### Debug and Tracing

Trace output uses TPipeConfig for file locations:

```kotlin
// Traces are written to:
val tracePath = TPipeConfig.getTraceDir()
```

See [Debug Package API](debug-package.md) for tracing details.

## Directory Structure

Default TPipe directory structure:

```
~/.tpipe/
├── TPipe-Default/              # Instance directory
│   └── memory/                 # Persistent memory storage
│       ├── lorebook/           # Lorebook persistence
│       │   ├── key1.bank
│       │   └── key2.bank
│       └── todo/               # TodoList persistence
│           ├── tasks1.todo
│           └── tasks2.todo
└── debug/                      # Debug output (shared)
    └── trace/                  # Execution traces
        ├── trace1.html
        └── trace2.json
```

With custom instance ID:

```
~/.tpipe/
├── MyApp-Production/           # Custom instance
│   └── memory/
│       ├── lorebook/
│       └── todo/
├── MyApp-Development/          # Another instance
│   └── memory/
│       ├── lorebook/
│       └── todo/
└── debug/
    └── trace/
```

## Best Practices

### Set Configuration Early

Configure TPipeConfig before using any persistence features:

```kotlin
fun main() {
    // Configure first
    TPipeConfig.configDir = "/opt/myapp/data"
    TPipeConfig.instanceID = "MyApp-${System.getenv("INSTANCE_ID")}"
    
    // Then use TPipe features
    val pipe = BedrockPipe()
    // ...
}
```

### Use Meaningful Instance IDs

Choose descriptive instance IDs:

```kotlin
// Good
TPipeConfig.instanceID = "CustomerService-API-v2"
TPipeConfig.instanceID = "DataProcessor-Worker-${workerId}"

// Less clear
TPipeConfig.instanceID = "app1"
TPipeConfig.instanceID = "instance"
```

### Validate Custom Paths

Ensure custom directories are writable:

```kotlin
val customDir = "/opt/myapp/tpipe"
val testFile = File(customDir)

if (!testFile.exists()) {
    testFile.mkdirs()
}

if (!testFile.canWrite()) {
    throw IllegalStateException("Cannot write to $customDir")
}

TPipeConfig.configDir = customDir
```

### Document Instance IDs

Keep track of instance IDs in multi-instance deployments:

```kotlin
// Log configuration on startup
logger.info("TPipe Instance: ${TPipeConfig.instanceID}")
logger.info("TPipe Config Dir: ${TPipeConfig.getTPipeConfigDir()}")
```

### Consider Backup Strategies

Plan for backing up persistent data:

```kotlin
// All persistent data is under:
val backupPath = TPipeConfig.getTPipeConfigDir()

// Backup this directory to preserve:
// - Lorebooks
// - TodoLists
// - Other memory persistence
```

### Clean Up Old Instances

Remove unused instance directories:

```kotlin
// List all instances
val tpipeDir = File(TPipeConfig.configDir)
val instances = tpipeDir.listFiles()?.filter { it.isDirectory }

// Clean up old instances as needed
instances?.forEach { instance ->
    if (shouldCleanup(instance)) {
        instance.deleteRecursively()
    }
}
```
