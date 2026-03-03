# TPipeConfig API Reference

`TPipeConfig` is the global control panel for your TPipe environment. It defines where your data is stored on disk, how your memory cluster is configured, and how individual instances of TPipe identify themselves.

---

## Filesystem & Instance Control

### `instanceID`
A string that uniquely identifies this TPipe application.
- **Why it matters**: If you run two different TPipe apps on the same machine, they will overwrite each other's data unless they have different `instanceID`s.
- **Default**: `"TPipe-Default"`

### `configDir`
The root directory for all TPipe data.
- **Default**: `~/.tpipe`

---

## Distributed Memory Cluster Settings

These properties control how your agent interacts with a **[Remote Memory System](../advanced-concepts/remote-memory.md)**.

| Property | Default | Purpose |
|----------|---------|---------|
| `remoteMemoryEnabled` | `false` | Set to `true` to enable the networking code for memory sharing. |
| `remoteMemoryUrl` | `localhost:8080` | The location of your **Source of Truth** server. |
| `remoteMemoryAuthToken` | `""` | The shared secret used to keep your data private. |
| `useRemoteMemoryGlobally` | `false` | If true, your agent becomes "stateless"—it will never save to local disk, only to the server. |
| `enforceMemoryVersioning` | `false` | If true, the server will reject updates from agents who are working with outdated data. |

---

## Directory Path Helpers

While you can set these manually, TPipe provides helpers to ensure consistent file organization:

- `getMemoryDir()`: Base path for all context and tasks.
- `getLorebookDir()`: Where `.bank` files are saved.
- `getTodoListDir()`: Where `.todo` files are saved.
- `getTraceDir()`: Where execution logs and HTML traces are generated.

---

## See Also
- [Conceptual Guide: Command-Line Reference](../getting-started/cli-reference.md)
- [Conceptual Guide: Remote Memory System](../advanced-concepts/remote-memory.md)
