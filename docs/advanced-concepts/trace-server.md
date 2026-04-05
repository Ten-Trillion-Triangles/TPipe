# TraceServer - Remote Trace Dashboard

## Table of Contents
- [Overview](#overview)
- [Architecture](#architecture)
- [Getting Started](#getting-started)
- [Configuration](#configuration)
- [Dual Authentication Model](#dual-authentication-model)
- [REST API Endpoints](#rest-api-endpoints)
- [WebSocket Live Streaming](#websocket-live-streaming)
- [Connecting Agents with RemoteTraceDispatcher](#connecting-agents-with-remotetracedispatcher)
- [Dashboard](#dashboard)
- [Standalone Execution](#standalone-execution)
- [Complete Example](#complete-example)
- [Best Practices](#best-practices)

## Overview

TPipe-TraceServer is a standalone module that provides a real-time web dashboard for viewing trace reports dispatched from running TPipe agents. It receives trace data over HTTP, stores it in memory, and broadcasts updates to connected dashboard clients over WebSocket.

This is useful when you have one or more TPipe agents running in production or development and want a centralized place to inspect their execution traces without embedding trace output into each agent's own logs.

**Key capabilities:**
- REST API for submitting and retrieving trace reports
- WebSocket endpoint for live trace streaming to connected browsers
- Dual authentication: separate auth flows for agents (submitting traces) and human clients (viewing the dashboard)
- Built-in HTML dashboard with search and filtering
- Configurable port and host binding

## Architecture

```
┌──────────────┐     POST /api/traces     ┌──────────────────────┐
│  TPipe Agent  │ ──────────────────────► │                      │
│  (Pipe/       │   (Agent Auth)          │    TraceServer       │
│   Pipeline)   │                         │                      │
└──────────────┘                          │  ┌────────────────┐  │
                                          │  │ TraceServer     │  │
┌──────────────┐     POST /api/traces     │  │ Registry        │  │
│  TPipe Agent  │ ──────────────────────► │  │  - traces       │  │
│  (Manifold)   │   (Agent Auth)          │  │  - sessions     │  │
└──────────────┘                          │  │  - connections  │  │
                                          │  └────────────────┘  │
                                          │                      │
┌──────────────┐   GET /api/traces        │                      │
│  Browser      │ ◄─────────────────────  │                      │
│  Dashboard    │   WS /ws/traces         │                      │
│               │ ◄─────────────────────  │                      │
└──────────────┘   (Client Auth)          └──────────────────────┘
```

Agents authenticate with a bearer token when submitting traces. Dashboard users authenticate through a separate login flow and receive a session token for subsequent requests and WebSocket connections.

## Getting Started

### Dependency

Add the TraceServer module to your project:

```kotlin
dependencies {
    implementation("com.TTT:TPipe-TraceServer:1.0.0")
}
```

### Minimal Startup

```kotlin
import com.TTT.TraceServer.startTraceServer

fun main()
{
    // Start on default port 8081, non-blocking
    startTraceServer()
    
    // Your application continues running...
}
```

Open `http://localhost:8081` in a browser to see the dashboard.

## Configuration

### TraceServerConfig

`TraceServerConfig` controls the server's network binding:

```kotlin
import com.TTT.TraceServer.TraceServerConfig

// Change port (default: 8081)
TraceServerConfig.port = 9090

// Change host binding (default: "0.0.0.0")
TraceServerConfig.host = "127.0.0.1"

// Then start the server
startTraceServer()
```

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `port` | `Int` | `8081` | Port the server listens on |
| `host` | `String` | `"0.0.0.0"` | Host address to bind to |

You can also pass `port` and `host` directly to `startTraceServer()`:

```kotlin
startTraceServer(port = 9090, host = "127.0.0.1", wait = true)
```

The `wait` parameter controls whether the call blocks the current thread. Set `wait = true` when TraceServer is your main application entry point, or `false` (default) when embedding it alongside other services.

## Dual Authentication Model

TraceServer uses two independent authentication mechanisms — one for agents submitting traces and one for human clients viewing the dashboard. Both are optional and disabled by default.

### Agent Authentication

Agent auth validates the `Authorization` header on `POST /api/traces`. This is the path used by `RemoteTraceDispatcher` when agents push trace data to the server.

```kotlin
import com.TTT.TraceServer.TraceServerRegistry

// Validate agent bearer tokens
TraceServerRegistry.agentAuthMechanism = { authHeader ->
    authHeader == "Bearer my-agent-secret"
}
```

When no `agentAuthMechanism` is set, TraceServer falls back to `P2PRegistry.globalAuthMechanism` if one is configured. If neither is set, agent submissions are accepted without authentication.

### Client Authentication

Client auth protects the dashboard endpoints (`GET /api/traces`, `GET /api/traces/{id}`, `WS /ws/traces`). Clients authenticate via `POST /api/auth/login` and receive a session token valid for 24 hours.

```kotlin
import com.TTT.TraceServer.TraceServerRegistry

// Validate dashboard login credentials
TraceServerRegistry.clientAuthMechanism = { key ->
    key == "my-dashboard-password"
}
```

When `clientAuthMechanism` is `null`, all dashboard endpoints are open without authentication.

### Auth Flow Summary

| Endpoint | Auth Type | Header/Param |
|----------|-----------|-------------|
| `POST /api/traces` | Agent auth | `Authorization: Bearer <token>` |
| `POST /api/auth/login` | Client login | JSON body `{"key": "..."}` |
| `GET /api/traces` | Client session | `Authorization: Bearer <session>` |
| `GET /api/traces/{id}` | Client session | `Authorization: Bearer <session>` |
| `WS /ws/traces` | Client session | Query param `?token=<session>` |

## REST API Endpoints

### `POST /api/auth/login`

Authenticates a dashboard client and returns a session token.

**Request:**
```json
{"key": "my-dashboard-password"}
```

**Response (200):**
```json
{"token": "uuid-session-token"}
```

**Response (401):** `"Invalid credentials"`

When no `clientAuthMechanism` is configured, returns an anonymous session token without validation.

---

### `POST /api/traces`

Submits a trace report from an agent. Requires agent authentication if configured.

**Request:**
```json
{
    "pipelineId": "pipeline-abc-123",
    "htmlContent": "<html>...trace report...</html>",
    "name": "My Pipeline Run",
    "status": "SUCCESS"
}
```

**Response:** `200 OK`

After storing the trace, the server broadcasts a summary to all connected WebSocket clients so the dashboard updates in real time.

---

### `GET /api/traces`

Returns a list of all stored trace summaries, sorted by most recent first. Requires client session if `clientAuthMechanism` is configured.

**Response:**
```json
[
    {
        "id": "pipeline-abc-123",
        "timestamp": 1710600000000,
        "name": "My Pipeline Run",
        "status": "SUCCESS"
    }
]
```

---

### `GET /api/traces/{id}`

Returns the full trace payload for a specific pipeline ID, including the HTML report content.

**Response:**
```json
{
    "pipelineId": "pipeline-abc-123",
    "htmlContent": "<html>...full trace report...</html>",
    "name": "My Pipeline Run",
    "status": "SUCCESS"
}
```

**Response (404):** `"Trace not found"`

## WebSocket Live Streaming

### `WS /ws/traces`

Connect to receive real-time trace summary broadcasts as agents submit new traces.

**Connection:** `ws://localhost:8081/ws/traces?token=<session-token>`

The `token` query parameter is required when `clientAuthMechanism` is configured. Each time an agent submits a trace via `POST /api/traces`, all connected WebSocket clients receive a JSON summary:

```json
{
    "id": "pipeline-abc-123",
    "timestamp": 1710600000000,
    "name": "My Pipeline Run",
    "status": "SUCCESS"
}
```

The server sends periodic WebSocket pings (every 15 seconds) to keep connections alive.

## Connecting Agents with RemoteTraceDispatcher

The easiest way to send traces to a TraceServer is through `RemoteTraceConfig` and `RemoteTraceDispatcher`. These live in TPipe-Core's Debug package and handle serialization, HTTP transport, and authentication automatically.

### Automatic Dispatch

Configure `RemoteTraceConfig` so that every call to `PipeTracer.exportTrace()` automatically pushes the trace to your server:

```kotlin
import com.TTT.Debug.RemoteTraceConfig

RemoteTraceConfig.remoteServerUrl = "http://localhost:8081"
RemoteTraceConfig.dispatchAutomatically = true

// Optional: set auth header manually
RemoteTraceConfig.authHeader = "Bearer my-agent-secret"
```

With `dispatchAutomatically = true`, any call to `PipeTracer.exportTrace(pipelineId, format)` will also fire off an async HTTP POST to the TraceServer with the HTML report.

### AuthRegistry Integration

If you use `AuthRegistry` (or `TPipeConfig.addRemoteAuth()`) to register tokens for your services, `RemoteTraceDispatcher` will automatically resolve the token for the TraceServer URL when `RemoteTraceConfig.authHeader` is not explicitly set:

```kotlin
import com.TTT.Config.TPipeConfig

// Register auth for the trace server URL
TPipeConfig.addRemoteAuth("http://localhost:8081", "my-agent-secret")

// RemoteTraceDispatcher will resolve "Bearer my-agent-secret" automatically
RemoteTraceConfig.remoteServerUrl = "http://localhost:8081"
RemoteTraceConfig.dispatchAutomatically = true
```

### Manual Dispatch

You can also dispatch traces manually at any point:

```kotlin
import com.TTT.Debug.RemoteTraceDispatcher

// Dispatch a specific pipeline's trace
RemoteTraceDispatcher.dispatchTrace(
    pipelineId = "my-pipeline-run",
    name = "Research Task",
    status = "SUCCESS"
)
```

The dispatcher exports the trace as HTML from `PipeTracer`, wraps it in a `TracePayload`, and POSTs it to `RemoteTraceConfig.remoteServerUrl`. The HTTP call runs on `Dispatchers.IO` and does not block the calling coroutine.

## Dashboard

The built-in dashboard is served at the root URL (`/`) and provides:

- **Trace list view** showing all submitted traces with pipeline ID, name, status, and timestamp
- **Search and filtering** to find specific traces by name or status
- **Detail view** rendering the full HTML trace report inline
- **Live updates** via WebSocket — new traces appear automatically without page refresh

The dashboard is a single-page HTML application bundled as static resources inside the TraceServer module.

## Standalone Execution

TraceServer can run as a standalone application from the command line:

```bash
# Default: port 8081, host 0.0.0.0
java -jar TPipe-TraceServer.jar

# Custom port and host
java -jar TPipe-TraceServer.jar --port 9090 --host 127.0.0.1
```

## Complete Example

This example starts a TraceServer, configures agent auth, runs a pipeline with tracing enabled, and dispatches the trace to the server:

```kotlin
import com.TTT.Debug.RemoteTraceConfig
import com.TTT.Debug.TraceConfig
import com.TTT.Debug.TraceFormat
import com.TTT.Debug.PipeTracer
import com.TTT.TraceServer.TraceServerRegistry
import com.TTT.TraceServer.startTraceServer
import bedrockPipe.BedrockPipe
import com.TTT.Pipeline.Pipeline
import kotlinx.coroutines.runBlocking

fun main() = runBlocking {
    // 1. Configure and start TraceServer
    TraceServerRegistry.agentAuthMechanism = { auth ->
        auth == "Bearer agent-token-123"
    }
    startTraceServer(port = 8081)

    // 2. Configure remote dispatch
    RemoteTraceConfig.remoteServerUrl = "http://localhost:8081"
    RemoteTraceConfig.authHeader = "Bearer agent-token-123"
    RemoteTraceConfig.dispatchAutomatically = true

    // 3. Build a traced pipeline
    val pipeline = Pipeline()
        .enableTracing(TraceConfig(enabled = true))
        .add(BedrockPipe()
            .setPipeName("analyzer")
            .setSystemPrompt("Analyze the input.")
            .setModel("anthropic.claude-3-sonnet-20240229-v1:0")
            .setRegion("us-east-1")
        )

    pipeline.init()
    val result = pipeline.execute("Explain quantum computing")

    // 4. Export trace — automatically dispatched to TraceServer
    PipeTracer.exportTrace(pipeline.pipelineId, TraceFormat.HTML)

    // Dashboard at http://localhost:8081 now shows the trace
    println("Trace dispatched. View at http://localhost:8081")
}
```

## Best Practices

- **Separate agent and client auth** — use strong tokens for agent auth and simpler credentials for dashboard login during development
- **Use `dispatchAutomatically = true`** during development so every trace export lands in the dashboard without extra code
- **Use `AuthRegistry`** instead of hardcoding `RemoteTraceConfig.authHeader` when your agents already register tokens for other remote services
- **Run TraceServer on a separate port** from your MemoryServer or other Ktor services to avoid routing conflicts
- **Set `wait = false`** when embedding TraceServer inside a larger application so it doesn't block your main thread
- **Monitor WebSocket connections** — the server cleans up closed connections automatically, but long-running dashboards should handle reconnection on the client side

---

**Related:**
- [Tracing and Debugging](../core-concepts/tracing-and-debugging.md) — Core tracing concepts and RemoteTraceConfig setup
- [Debug Package API](../api/debug-package.md) — PipeTracer, TraceConfig, and TraceEvent reference
- [TPipeConfig API](../api/tpipe-config.md) — AuthRegistry and `addRemoteAuth()` for unified authentication
## Next Steps

- [P2P Overview](p2p/p2p-overview.md) - Continue into agent-to-agent communication.
