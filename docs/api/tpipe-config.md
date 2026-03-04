# TPipeConfig Object API

`TPipeConfig` is the Infrastructure Manager—the central authority for the TPipe filesystem and directory structure. It provides centralized control over where TPipe stores persistent data and enables instance isolation to prevent conflicts when running multiple TPipe applications.

```kotlin
object TPipeConfig
```

## Table of Contents
- [Public Properties](#public-properties)
- [Public Functions](#public-functions)
- [Directory Structure](#directory-structure)
- [Usage Examples](#usage-examples)
- [Integration Points](#integration-points)
- [Best Practices](#best-practices)

---

## Public Properties

### `configDir`
```kotlin
var configDir: String = "${getHomeFolder()}/.tpipe"
```
The root foundation for all TPipe data including memory persistence, debug traces, and configuration.
- **Default**: `~/.tpipe` (User home directory).
- **Customization**: Essential for containerized environments or when data must reside on specific high-performance volumes or mounted drives.

### `instanceID`
```kotlin
var instanceID: String = "TPipe-Default"
```
The unique identifier for a specific TPipe instance. It prevents separate applications from interfering with each other's data by creating isolated sub-directories.
- **Default**: `TPipe-Default`.
- **Customization**: Use unique IDs (e.g., `MyApp-Staging`) to separate development, staging, and production data.

---

## Public Functions: Path Generation

TPipe uses these functions to resolve the exact reservoirs and gauges on your disk.

#### `getTPipeConfigDir(): String`
Returns the complete path for the current instance: `${configDir}/${instanceID}`. All instance-specific data lives under this root.

#### `getMemoryDir(): String`
The path to the **Memory Reservoir** root: `${getTPipeConfigDir()}/memory`. This is the parent folder for all memory persistence features.

#### `getLorebookDir(): String`
The specific path for Strategic Reserves: `${getMemoryDir()}/lorebook`.
- **File Format**: LoreBooks are saved as `<key>.bank` files.

#### `getTodoListDir(): String` (Alias: `getTodoDir()`)
The specific path for Project Ledgers: `${getMemoryDir()}/todo`.
- **File Format**: TodoLists are saved as `<key>.todo` files.

#### `getDebugDir(): String`
The root directory for debug-related output: `${configDir}/debug`. This is stored at the `configDir` level, not per-instance, allowing debug data to be shared if needed.

#### `getTraceDir(): String`
The specific path for execution telemetry: `${getDebugDir()}/trace`. Trace files (JSON/HTML) are written here when tracing is enabled.

---

## Directory Structure: The Facility Map

TPipe builds a standardized map on your filesystem:

```text
~/.tpipe/
├── TPipe-Default/              # The Instance Vault
│   └── memory/                 # The Memory Reservoirs
│       ├── lorebook/           # Strategic Reserves (.bank files)
│       └── todo/               # Project Ledgers (.todo files)
└── debug/                      # The Monitoring Center (Shared)
    └── trace/                  # Telemetry Logs (JSON/HTML)
```

---

## Usage Examples

### 1. Instance Isolation
Run multiple TPipe applications without data pollution:
```kotlin
// Application 1
TPipeConfig.instanceID = "Worker-Analyst"
// Data saves to ~/.tpipe/Worker-Analyst/...

// Application 2
TPipeConfig.instanceID = "Worker-Generator"
// Data saves to ~/.tpipe/Worker-Generator/...
```

### 2. Containerized Deployment
Configure for mounted volumes in a container:
```kotlin
TPipeConfig.configDir = "/data/tpipe"
TPipeConfig.instanceID = System.getenv("POD_NAME") ?: "default"
```

---

## Integration Points

### 1. ContextBank
The `ContextBank` relies entirely on `TPipeConfig` for disk persistence of LoreBooks and TodoLists.

### 2. High-Resolution Tracing
When `enableTracing()` is active, the engine automatically writes telemetry reports to the path resolved by `getTraceDir()`.

---

## Best Practices

*   **Initialize Early**: Configure `configDir` and `instanceID` at the very start of your application, before any Pipes or ContextBanks are initialized.
*   **Unique Instances**: In multi-tenant or microservice architectures, give every service a unique `instanceID` to prevent file-level contention.
*   **Validate Permissions**: Ensure the `configDir` is writable by the application process. TPipe will attempt to create the directory structure automatically if it has the necessary permissions.
*   **Backup Strategy**: All critical agent memory is stored under the `getTPipeConfigDir()` root. Include this directory in your backup policies to preserve state.
