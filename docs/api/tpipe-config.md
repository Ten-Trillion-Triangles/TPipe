# TPipeConfig Object API

## Table of Contents
- [Overview](#overview)
- [Public Properties](#public-properties)
- [Remote Memory Settings](#remote-memory-settings)
- [Public Functions](#public-functions)
- [Usage Examples](#usage-examples)
- [Integration Points](#integration-points)
- [Best Practices](#best-practices)

## Overview

`TPipeConfig` is a singleton object that manages TPipe's file system configuration, directory structure, and remote memory settings. It provides centralized control over where TPipe stores persistent data and enables instance isolation to prevent conflicts when running multiple TPipe applications.

```kotlin
object TPipeConfig
```

**Key responsibilities:**
- Define the base configuration directory (default: `~/.tpipe`)
- Provide instance isolation through unique instance IDs
- Generate paths for different storage types (lorebooks, todo lists, traces)
- Configure remote memory hosting and access
- Control memory versioning and security policies

## Public Properties

### `configDir`
```kotlin
var configDir: String = "${getHomeFolder()}/.tpipe"
```
The base directory where TPipe stores all persistent data.

### `instanceID`
```kotlin
var instanceID: String = "TPipe-Default"
```
Unique identifier for this TPipe instance, used as a subdirectory under `configDir`. Prevents multiple TPipe applications from interfering with each other's stored data.

## Remote Memory Settings

These properties control how TPipe interacts with remote memory servers and handles distributed state.

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `remoteMemoryEnabled` | `Boolean` | `false` | Enables remote memory delegation logic. |
| `remoteMemoryUrl` | `String` | `"http://localhost:8080"` | Base URL of the remote memory server. |
| `remoteMemoryAuthToken` | `String` | `""` | Token sent in the `Authorization` header for remote requests. |
| `useRemoteMemoryGlobally` | `Boolean` | `false` | If true, all memory operations delegate to remote by default. |
| `enforceMemoryVersioning` | `Boolean` | `false` | If true, the server rejects writes with outdated versions. |

## Public Functions

### `getTPipeConfigDir(): String`
Returns the complete configuration directory path including instance ID.
**Example:** `~/.tpipe/TPipe-Default`

### `getMemoryDir(): String`
Returns the directory where TPipe stores persistent memory data.

### `getLorebookDir(): String`
Returns the directory where lorebook data (`.bank` files) is persisted.

### `getTodoListDir(): String`
Returns the directory where todo lists (`.todo` files) are persisted.

### `getDebugDir(): String`
Returns the directory where debug data is stored.

### `getTraceDir(): String`
Returns the directory where execution traces are stored.

## Usage Examples

### Custom Configuration Directory
Change where TPipe stores all data:
```kotlin
TPipeConfig.configDir = "/opt/myapp/tpipe-data"
```

### Instance Isolation
Run multiple TPipe applications simultaneously:
```kotlin
// Application 1
TPipeConfig.instanceID = "WebService-API"

// Application 2 (different process)
TPipeConfig.instanceID = "BackgroundWorker"
```

### Remote Memory Setup
```kotlin
TPipeConfig.remoteMemoryEnabled = true
TPipeConfig.remoteMemoryUrl = "https://memory-cluster.internal"
TPipeConfig.remoteMemoryAuthToken = "secret-token-xyz"
TPipeConfig.useRemoteMemoryGlobally = true
```

## Integration Points

### ContextBank
ContextBank uses `TPipeConfig` for all disk persistence and remote delegation. All lorebook and todo list paths are generated via this singleton.

### Debug and Tracing
Trace output uses `TPipeConfig` for file locations, typically storing them under `~/.tpipe/debug/trace`.

## Best Practices

### Set Configuration Early
Configure `TPipeConfig` before using any persistence features or starting pipes.

### Use Meaningful Instance IDs
Choose descriptive instance IDs (e.g., `Prod-Agent-01`) to make disk-based troubleshooting easier.

### Validate Custom Paths
Ensure custom directories are writable by the application process to avoid runtime exceptions during memory persistence.
