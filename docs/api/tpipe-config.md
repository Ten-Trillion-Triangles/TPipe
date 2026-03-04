# TPipeConfig Object API

`TPipeConfig` is the Infrastructure Manager—the central authority for the TPipe filesystem and directory structure. It provides unified control over where TPipe stores persistent data, manages instance isolation, and generates the paths needed for memory reservoirs, task ledgers, and telemetry logs.

```kotlin
object TPipeConfig
```

## Table of Contents
- [Public Properties](#public-properties)
- [Public Functions](#public-functions)
- [Directory Structure](#directory-structure)
- [Integration Points](#integration-points)

---

## Public Properties

### `configDir`
```kotlin
var configDir: String = "${getHomeFolder()}/.tpipe"
```
The root foundation for all TPipe data. This is where the entire system structure is built.
- **Default**: `~/.tpipe` (User home directory).
- **Customization**: Essential for containerized environments or when data must reside on specific high-performance volumes.

### `instanceID`
```kotlin
var instanceID: String = "TPipe-Default"
```
The unique identifier for a specific TPipe deployment. It prevents separate applications from "Leaking" into each other's data by creating isolated sub-directories.
- **Default**: `TPipe-Default`.
- **Customization**: Use unique IDs (e.g., `Site-A-Auditor`) to separate development, staging, and production mainlines.

---

## Public Functions: Path Generation

TPipe uses these functions to resolve the exact "Gauges" and "Reservoirs" on your disk.

#### `getTPipeConfigDir(): String`
Returns the complete path for the current instance: `${configDir}/${instanceID}`. All instance-specific data lives under this root.

#### `getMemoryDir(): String`
The path to the **Memory Reservoir** directory. This is the parent folder for LoreBooks and TodoLists.

#### `getLorebookDir(): String` / `getTodoListDir(): String`
Specific paths for Strategic Reserves and Project Ledgers.
- **File Format**: LoreBooks are saved as `.bank` files; TodoLists are saved as `.todo` files.

#### `getDebugDir(): String` / `getTraceDir(): String`
The path to the **Pressure Gauge** (telemetry) logs. Unlike memory, debug data is stored at the `configDir` root level by default, allowing you to centralize telemetry from multiple instances if desired.

---

## Directory Structure: The Facility Map

TPipe builds a standardized map on your filesystem to keep data organized:

```text
~/.tpipe/
├── TPipe-Default/              # The Instance Vault
│   └── memory/                 # The Memory Reservoirs
│       ├── lorebook/           # Strategic Reserves (.bank files)
│       └── todo/               # Project Ledgers (.todo files)
└── debug/                      # The Monitoring Center
    └── trace/                  # Telemetry Logs (JSON/HTML)
```

---

## Integration Points

### 1. ContextBank
The `ContextBank` relies entirely on `TPipeConfig` to resolve where to save and load data when a Pipe calls `pullGlobalContext()`.

### 2. High-Resolution Tracing
When `enableTracing()` is active, the engine automatically writes telemetry reports to the path resolved by `getTraceDir()`.

### 3. Remote Synchronization
If you are using a `MemoryServer` for remote knowledge distribution, the local instance of `TPipeConfig` manages the local cache and authentication tokens required to connect to the remote pumping station.

## Best Practices

*   **Initialize Early**: Set your `configDir` and `instanceID` at the very start of your application before any Pipes or Reservoirs are initialized.
*   **Isolation**: In a microservice architecture, give every service a unique `instanceID` to ensure there is no file-level contention for knowledge pages.
*   **Security**: Ensure the directory pointed to by `configDir` has appropriate OS-level permissions, as it contains your agent's memory and potentially sensitive task history.
