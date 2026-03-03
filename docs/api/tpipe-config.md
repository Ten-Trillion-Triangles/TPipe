# TPipeConfig API Reference

`TPipeConfig` is the global configuration singleton managing the TPipe environment's filesystem structure and networking behavior.

---

## Public Properties

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `configDir` | String | `~/.tpipe` | Base directory for all persistent TPipe data. |
| `instanceID`| String | `TPipe-Default` | Subdirectory identifier for instance isolation. |
| `remoteMemoryEnabled` | Boolean | `false` | Enables remote delegation features in ContextBank. |
| `remoteMemoryUrl` | String | `localhost:8080`| URL of the centralized Memory Server. |
| `remoteMemoryAuthToken`| String | `""` | Secret sent in headers for remote memory auth. |
| `useRemoteMemoryGlobally`| Boolean| `false` | If true, all memory operations bypass local disk. |
| `enforceMemoryVersioning`| Boolean| `false` | Enables version-based conflict resolution. |

---

## Directory Helpers

These functions return absolute paths based on the current `configDir` and `instanceID`.

- **`getTPipeConfigDir()`**: Root for this instance.
- **`getMemoryDir()`**: Base for all shared state.
- **`getLorebookDir()`**: Directory for `.bank` files.
- **`getTodoListDir()`**: Directory for `.todo` files.
- **`getTraceDir()`**: Where execution traces are generated.

---

## Best Practices

### Instance Isolation
Always set a unique `instanceID` when running multiple agents on the same host to prevent data corruption.
```kotlin
TPipeConfig.instanceID = "AnalysisAgent-04"
```

### Shared Clusters
In cloud environments, point multiple instances to the same `remoteMemoryUrl` and enable `enforceMemoryVersioning` to create a robust shared-state swarm.
