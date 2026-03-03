# TPipeConfig API Reference

`TPipeConfig` is the global configuration singleton for the TPipe environment.

---

## Public Properties

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `configDir` | String | `~/.tpipe` | Root directory for all persistent data. |
| `instanceID`| String | `TPipe-Default` | Unique subdirectory for instance isolation. |
| `remoteMemoryEnabled` | Boolean | `false` | Enables networking code for Remote Memory. |
| `remoteMemoryUrl` | String | `localhost:8080` | URL of the Memory Server. |
| `remoteMemoryAuthToken`| String | `""` | Key for `globalAuthMechanism` on remote servers. |
| `useRemoteMemoryGlobally`| Boolean| `false` | Forces all memory ops to delegate to remote server. |
| `enforceMemoryVersioning`| Boolean| `false` | Enables conflict resolution for concurrent writes. |

---

## Directory Helpers

Use these to ensure your app follows the standard TPipe directory structure:

- `getTPipeConfigDir()`: Returns `configDir/instanceID`.
- `getMemoryDir()`: Base path for context and tasks.
- `getLorebookDir()`: Where `.bank` files are stored.
- `getTodoListDir()`: Where `.todo` files are stored.
- `getTraceDir()`: Where HTML execution traces are saved.

---

## Best Practices

### Multi-Instance Apps
When running multiple TPipe instances on the same machine, **always** set a unique `instanceID`:
```kotlin
TPipeConfig.instanceID = "WebAgent-01"
```

### Remote Hosting
If your agent runs in a serverless function (like AWS Lambda), set `useRemoteMemoryGlobally = true` so that state is persisted to a central cluster instead of local ephemeral storage.
