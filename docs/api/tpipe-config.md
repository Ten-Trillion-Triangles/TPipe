# TPipeConfig Object API

## Table of Contents
- [Overview](#overview)
- [Public Properties](#public-properties)
- [Remote Memory Settings](#remote-memory-settings)
- [Public Functions](#public-functions)
- [Usage Examples](#usage-examples)
- [Integration Points](#integration-points)

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
Unique identifier for this TPipe instance, used as a subdirectory under `configDir`.

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

### Basic Configuration
```kotlin
TPipeConfig.configDir = "/opt/tpipe"
TPipeConfig.instanceID = "production-agent-1"
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
ContextBank uses `TPipeConfig` for all disk persistence and remote delegation. See [ContextBank API](context-bank.md).

### Debug and Tracing
Trace output uses `TPipeConfig` for file locations. See [Debug Package API](debug-package.md).

## Directory Structure
```
~/.tpipe/
├── TPipe-Default/              # Instance directory
│   └── memory/                 # Persistent memory storage
│       ├── lorebook/           # Lorebook persistence (.bank)
│       └── todo/               # TodoList persistence (.todo)
└── debug/                      # Debug output (shared)
    └── trace/                  # Execution traces
```
