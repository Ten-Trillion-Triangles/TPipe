# TraceServer - Remote Trace Dashboard

## Table of Contents
- [Overview](#overview)
- [Architecture](#architecture)
- [v1 Hardening](#v1-hardening)
- [Getting Started](#getting-started)
- [Configuration](#configuration)
- [Pluggable Persistence (TraceStore)](#pluggable-persistence-tracestore)
- [Tenant Scoping](#tenant-scoping)
- [HTTPS / TLS](#https--tls)
- [CORS Allowlist](#cors-allowlist)
- [Dual Authentication Model](#dual-authentication-model)
- [REST API Endpoints](#rest-api-endpoints)
- [WebSocket Live Streaming](#websocket-live-streaming)
- [Error Envelope](#error-envelope)
- [Graceful Shutdown](#graceful-shutdown)
- [Connecting Agents with RemoteTraceDispatcher](#connecting-agents-with-remotetracedispatcher)
- [Dashboard](#dashboard)
- [Standalone Execution](#standalone-execution)
- [Complete Example](#complete-example)
- [Best Practices](#best-practices)

## Overview

TPipe-TraceServer is a standalone module that provides a real-time web dashboard for viewing trace reports dispatched from running TPipe agents. It receives trace data over HTTP, stores it in a pluggable `TraceStore`, and broadcasts updates to connected dashboard clients over WebSocket.

This is useful when you have one or more TPipe agents running in production or development and want a centralized place to inspect their execution traces without embedding trace output into each agent's own logs.

**Key capabilities:**
- REST API for submitting, listing, fetching, and deleting trace reports
- WebSocket endpoint for live trace streaming to connected browsers
- Pluggable persistence layer (`TraceStore`) with a file-backed default under `~/.TPipe-Debug/trace-server/`
- Optional tenant scoping via `X-Tenant` header or `?tenant=` query parameter
- HTTPS via the JDK KeyStore API (no BouncyCastle, native-image friendly)
- CORS allowlist (no wide-open by default)
- Dual authentication: separate auth flows for agents (submitting traces) and human clients (viewing the dashboard)
- Standardized error envelope for all non-2xx responses
- Built-in HTML dashboard with search and filtering

## Architecture

```
┌──────────────┐     POST /api/traces     ┌──────────────────────┐
│  TPipe Agent  │ ──────────────────────► │                      │
│  (Pipe/       │   (Agent Auth)          │    TraceServer       │
│   Pipeline)   │  X-Tenant: alpha        │                      │
└──────────────┘                          │  ┌────────────────┐  │
                                          │  │ TraceStore     │  │
┌──────────────┐     POST /api/traces     │  │  - FileBacked  │  │
│  TPipe Agent  │ ──────────────────────► │  │  - InMemory    │  │
│  (Manifold)   │   (Agent Auth)          │  │  - custom      │  │
└──────────────┘  X-Tenant: beta          │  └────────────────┘  │
                                          │                      │
┌──────────────┐   GET /api/traces        │                      │
│  Browser      │ ◄─────────────────────  │  ┌────────────────┐  │
│  Dashboard    │   WS /ws/traces         │  │ TLS Connector  │  │
│               │ ◄─────────────────────  │  │ (HTTPS, 8443)  │  │
└──────────────┘   (Client Auth)          │  └────────────────┘  │
                                          └──────────────────────┘
```

Agents authenticate with a bearer token when submitting traces. Dashboard users authenticate through a separate login flow and receive a session token for subsequent requests and WebSocket connections.

## v1 Hardening

The v1 release adds the following operational improvements on top of the original MVP:

| Area | What changed |
|------|--------------|
| HTTPS / TLS | New `TlsConfig`; Ktor `sslConnector` wired through `embeddedServer`; CLI flags `--tls-key-store`, `--tls-key-alias`, `--tls-port`, `--tls-mutual`, `--tls-trust-store`. Compatible with the `--enable-https` GraalVM build arg. |
| Pluggable `TraceStore` | `InMemoryTraceStore` (default for tests / `--no-persist`) and `FileBackedTraceStore` (default for production: JSONL append-only log + index snapshot, replay on startup, per-tenant `ReentrantReadWriteLock`). Custom implementations plug in via `TraceServerRegistry.configureStore(...)`. |
| Real timestamps | `TraceSummary.timestamp` is captured at `put()` time so the list is sorted by insertion order, not query time. |
| Tenant scoping | `X-Tenant` header → `?tenant=` query → `default`. Sessions, store buckets, and WebSocket broadcasts are all partitioned by tenant. |
| Robustness | `POST /api/traces` validates non-empty fields, caps `htmlContent` at `maxPayloadBytes` (default 5 MB). `StatusPages` plugin shapes every error response with `{ "error": code, "message": human }`. |
| New endpoints | `DELETE /api/traces/{id}` (tenant-scoped, 401/404 envelopes), `GET /api/health` (envelope, no auth), and a paginated `{ items, total, limit, offset }` response on `GET /api/traces` with `?status=`, `?q=`, `?since=`, `?limit=`, `?offset=`, `?tenant=` query params. |
| Structured shutdown | WebSocket broadcast is no longer fired from `GlobalScope`; the new `stopTraceServer(engine)` cancels the registry's `SupervisorJob`, closes the store, and stops the engine with a 5 s grace period. |
| CORS allowlist | New `CorsConfig`; `anyHost()` is no longer the default. Wide-open requires `allowedHosts = listOf("*")`. |

## v2 Hardening

The v2 release is a native-friendly expansion of the v1 surface. It is intentionally conservative about new dependencies: the only third-party runtime artifact added is Ktor Micrometer for Prometheus scraping. Every new code path is implemented with the JDK + Ktor + kotlinx-serialization, which keeps the door open for a future GraalVM native-image build of this module (see [Native compatibility notes](#native-compatibility-notes) at the bottom of this document).

| Area | What changed |
|------|--------------|
| Server-side password hashing | `auth/PasswordHasher.kt` interface + `Pbkdf2PasswordHasher.kt` default. PBKDF2-HMAC-SHA256 with 600 000 iterations and 256-bit derived keys via `SecretKeyFactory` (pure JDK, no BouncyCastle). `POST /api/auth/login` accepts a cleartext `password` field and verifies against `AuthConfig.expectedHash` with constant-time compare via `MessageDigest.isEqual`. The legacy `clientAuthMechanism` lambda is still honored when `expectedHash` is `null`. |
| Refresh tokens | `POST /api/auth/refresh` rotates a refresh token and revokes the paired access token in the same call. Access tokens default to 15 min, refresh tokens to 7 days. Stored as opaque random UUIDs in the same `clientSessions` map under a `Kind.ACCESS` / `Kind.REFRESH` discriminator. |
| Response compression | Ktor `Compression` plugin installed with gzip + deflate, 1024-byte minimum. Toggle with `--no-compression`. |
| Rate limiting | Ktor `RateLimit` plugin + a manual per-tenant sliding-window counter. Per-IP bucket (default 60 writes / minute) covers `POST /api/auth/login`, `POST /api/traces`, and `POST /api/traces/{id}/events`. Per-tenant bucket (default 600 writes / minute) prevents one noisy tenant from starving another. Toggle with `--rate-limit-ip`, `--rate-limit-tenant`. 429 responses use the v1 error envelope. |
| Per-event WebSocket streaming | The `/ws/traces` WebSocket now supports a sub-protocol with `subscribe` and `unsubscribe` ops. The legacy summary broadcast is unchanged. New `POST /api/traces/{id}/events` route accepts a `TraceEvent` JSON, persists it (file-backed store: per-pipeline JSONL log under `events/<id>.jsonl`), and fans it out to subscribed dashboards as `{"op":"event",...}` frames. Server-stamped `ts` for consistent timelines. |
| Prometheus `/metrics` | Ktor MicrometerMetrics plugin with the PrometheusMeterRegistry. Custom counters: `tpipe_traces_received_total{tenant=}`, `tpipe_traces_stored{tenant=}`, `tpipe_events_streamed_total{tenant=}`, `tpipe_auth_failures_total{reason=}`. Toggle with `--no-metrics` (still exposed on the same `/metrics` path). |
| TTL + per-tenant quota | `FileBackedTraceStore(directory, maxTraces, ttl, perTenantQuota)` evicts traces older than `ttl` (default 7 days) and caps each tenant at `perTenantQuota` (default 2000). Eviction happens on `put()` and on startup replay so a long-down server does not retain stale data. New CLI flags: `--store-ttl`, `--store-quota`. `/api/health` exposes the active TTL and quota. |
| Trace tags | `TracePayload` gains a `tags: Map<String, String>` field (default `emptyMap()`). Persisted by both stores. New `?tag=key:value` filter on `GET /api/traces`. Wire-compatible with v1: old clients that don't send the field get an empty map; v2 clients that receive a v1 payload get an empty map. |
| OpenAPI spec | Hand-authored YAML at `GET /api/openapi.yaml` documents every v1 and v2 route, including the WS sub-protocol. No codegen, no new dep. The dashboard can `fetch()` it to render interactive docs. |
| `Json` singleton | All route handlers now share one process-wide `TraceServerJson` configured with `ignoreUnknownKeys`, `isLenient`, `prettyPrint`, `encodeDefaults`, and the `op` class discriminator for the WS envelope. Removes the per-request allocation that triggered a compiler warning in v1. |
| Filter helper extracted | `store/TraceFilters.kt` is the single source of truth for the AND-combinator filter semantics shared by `InMemoryTraceStore` and `FileBackedTraceStore`. |

### Configuration: v2 additions

```kotlin
TraceServerConfig(
    auth = AuthConfig(
        passwordHasherEnabled = true,        // default
        accessTokenTtl = 15.minutes,         // default
        refreshTokenTtl = 7.days,            // default
        expectedHash = Pbkdf2PasswordHasher().hash("dashboard-password")
    ),
    rateLimit = RateLimitConfig(
        enabled = true,                      // default
        perIpWrites = 60,                    // default per 1.minutes
        perTenantWrites = 600                // default per 1.minutes
    ),
    compression = CompressionConfig(
        enabled = true,                      // default
        minSize = 1024,                      // bytes; below this, no compression
        gzip = true,
        deflate = true
    ),
    metrics = MetricsConfig(
        enabled = true,                      // default
        path = "/metrics"                    // default
    ),
    store = StoreConfig(
        type = StoreType.FILE_BACKED,        // default
        directory = ~/.TPipe-Debug/trace-server,
        maxTraces = 10_000,
        ttl = 7.days,                        // v2: evicts entries older than this
        perTenantQuota = 2_000               // v2: per-tenant cap
    )
)
```

### v2 CLI flags

```
--auth-hash-iterations N        # default 600000
--auth-access-ttl MINUTES       # default 15
--auth-refresh-ttl DAYS         # default 7
--rate-limit-ip N              # default 60
--rate-limit-tenant N          # default 600
--no-compression                # disable gzip/deflate
--no-metrics                    # disable /metrics
--store-ttl DURATION            # ISO-8601; default PT168H (7 days)
--store-quota N                 # default 2000
```

### Native compatibility notes

The v2 surface is shaped so a future GraalVM native-image build of `:TPipe-TraceServer` (a future PR on the `feature/graalvm-abi` branch) is not blocked. Concretely, every new code path is allowed by the following audit:

1. **No new reflective deps.** The only third-party runtime artifact added in v2 is `ktor-server-metrics-micrometer` (Ktor official, MIT-licensed, code-gen, no reflection). `micrometer-registry-prometheus:1.12.x` is the matching backend; pin in `build.gradle.kts`.
2. **No `sun.*` packages.** PBKDF2 is reached through `javax.crypto.SecretKeyFactory`, exported. The v1 HTTPS test's `keytool` usage is unchanged.
3. **No `ServiceLoader` additions.** v2 does not register new `META-INF/services/*` providers.
4. **All `kotlinx-serialization` annotations are `@Serializable` on `data class`es with stable JSON shape.** No polymorphic serializers were introduced.
5. **Ktor built-in plugins only** for cross-cutting concerns: `Compression`, `MicrometerMetrics`, `RateLimit`, `CallLogging`, `StatusPages`. No third-party middleware.
6. **No new JNI / native bindings.** File I/O uses `Files.newBufferedWriter` and `Files.writeString`, both of which are already covered by GraalVM's reachability metadata for the existing native build.
7. **`--enable-https`** is still the only GraalVM build arg the TraceServer module needs.

When the `feature/graalvm-abi` branch lands a `graalvmNative {}` block for `:TPipe-TraceServer`, the v2 deliverable should pass through without changes. The owner of that branch is expected to add the matching `reflect-config.json` and `resource-config.json` (none required for v2; this is provided for completeness).


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
    // Start on default port 8081, non-blocking, with file-backed persistence
    // under ~/.TPipe-Debug/trace-server/ (auto-created).
    startTraceServer()
    
    // Your application continues running...
}
```

Open `http://localhost:8081` in a browser to see the dashboard.

## Configuration

`TraceServerConfig` is now a `data class` that captures every option the server reads at boot. Construct one directly, then pass it to `startTraceServer(config)`. The historic mutable `TraceServerConfig.port` / `TraceServerConfig.host` shims are still honored for backward compatibility.

```kotlin
import com.TTT.TraceServer.*

val config = TraceServerConfig(
    port = 8081,
    host = "0.0.0.0",
    tlsPort = 8443,
    tls = TlsConfig(
        enabled = true,
        keyStorePath = "/etc/tpipe/keystore.p12",
        keyStorePassword = System.getenv("KEYSTORE_PASSWORD"),
        keyAlias = "tpipe-server",
        keyPassword = System.getenv("KEY_PASSWORD")
    ),
    cors = CorsConfig(
        allowedHosts = listOf("dashboard.example.com", "localhost"),
        allowedMethods = listOf("GET", "POST", "DELETE", "OPTIONS"),
        allowedHeaders = listOf("Authorization", "Content-Type", "X-Tenant")
    ),
    store = StoreConfig(
        type = StoreType.FILE_BACKED,
        directory = java.nio.file.Paths.get("/var/lib/tpipe/traces"),
        maxTraces = 10_000
    ),
    authMode = AuthMode.KEY,
    maxPayloadBytes = 5L * 1024L * 1024L
)

startTraceServer(config)
```

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `port` | `Int` | `8081` | HTTP port (plain text) the server binds to. |
| `host` | `String` | `"0.0.0.0"` | Bind address. |
| `tlsPort` | `Int` | `8443` | HTTPS port used when `tls.enabled = true`. |
| `tls` | `TlsConfig` | disabled | TLS configuration. See [HTTPS / TLS](#https--tls). |
| `cors` | `CorsConfig` | localhost | CORS allowlist. See [CORS Allowlist](#cors-allowlist). |
| `store` | `StoreConfig` | file-backed | Persistence configuration. See [Pluggable Persistence](#pluggable-persistence-tracestore). |
| `authMode` | `AuthMode` | `KEY` | Which auth flow the dashboard advertises. |
| `maxPayloadBytes` | `Long` | `5 MB` | Hard cap on `POST /api/traces` body size. |
| `version` | `String` | `"1.0.0"` | Identifier exposed by `/api/health`. |
| `defaultTenant` | `String` | `"default"` | Tenant key used when a request does not supply one. |

The CLI surface accepts the same options as flags. See [Standalone Execution](#standalone-execution).

## Pluggable Persistence (TraceStore)

The persistence layer is now behind the `TraceStore` interface, with a file-backed implementation as the production default.

```kotlin
interface TraceStore {
    fun put(payload: TracePayload, tenant: String = DEFAULT_TENANT)
    fun get(pipelineId: String, tenant: String = DEFAULT_TENANT): TracePayload?
    fun delete(pipelineId: String, tenant: String = DEFAULT_TENANT): Boolean
    fun listSummaries(filter: TraceFilter): TraceListResult
    fun count(tenant: String = DEFAULT_TENANT): Int
    fun close()
}
```

**Built-in implementations:**

- **`InMemoryTraceStore(maxTraces = 10_000)`** — volatile, bounded LRU per tenant. Suitable for tests and `--no-persist` runs.
- **`FileBackedTraceStore(directory, maxTraces = 10_000)`** — JSONL append-only log on `put` + an `trace_index.json` snapshot written on `close`. Replays the log on startup so a missing or stale index is recovered automatically. Uses JDK NIO only (no JDBC, no platform bindings), so it stays GraalVM native-image compatible.

**Swapping the store at boot:**

```kotlin
import com.TTT.TraceServer.TraceServerRegistry

// In-memory for tests / ephemeral runs
TraceServerRegistry.configureStore(InMemoryTraceStore(maxTraces = 1_000))

// File-backed in a custom location
TraceServerRegistry.configureStore(FileBackedTraceStore.at("/var/lib/tpipe/traces"))

// Or write your own TraceStore and plug it in
TraceServerRegistry.configureStore(MyRedisTraceStore(redisClient))
```

The store is closed automatically when the next `configureStore(...)` call replaces it or when `stopTraceServer(...)` tears the engine down.

## Tenant Scoping

The server partitions every payload, session, and WebSocket connection by tenant. Resolution order on every route and on the WebSocket upgrade is:

1. `X-Tenant` request header (preferred for service-to-service calls)
2. `?tenant=` query parameter (used by browser clients)
3. `default` (the v0 fallback for single-tenant integrations)

**Example: posting under a specific tenant.**

```bash
curl -X POST https://localhost:8443/api/traces \
  -H "Authorization: Bearer agent-token" \
  -H "X-Tenant: alpha" \
  -H "Content-Type: application/json" \
  -d '{"pipelineId":"run-1","htmlContent":"<html/>","name":"Run 1","status":"SUCCESS"}'
```

Agents and dashboards that don't supply a tenant still get the `default` partition, so existing single-tenant integrations keep working unchanged.

## HTTPS / TLS

TLS is configured via the `TlsConfig` data class. The server loads the keystore with `KeyStore.getInstance("JKS" or "PKCS12")` from the JDK KeyStore API — no BouncyCastle or other native dependency, so the configuration is compatible with GraalVM native-image (the worktree's `graalvmNative` block already passes `--enable-https` for other modules).

```kotlin
val tls = TlsConfig(
    enabled = true,
    keyStorePath = "/etc/tpipe/keystore.p12",
    keyStorePassword = "change-me",
    keyAlias = "tpipe-server",
    keyPassword = "change-me",
    trustStorePath = "/etc/tpipe/truststore.p12",  // optional, for mutual TLS
    trustStorePassword = "change-me",
    mutualTls = false,
    protocols = listOf("TLSv1.3", "TLSv1.2")
)
```

CLI flags: `--tls-key-store`, `--tls-key-store-password`, `--tls-key-alias`, `--tls-key-password`, `--tls-trust-store`, `--tls-trust-store-password`, `--tls-mutual`, `--tls-port`.

When `TlsConfig.enabled = true` the server exposes BOTH the plain HTTP port (8081 by default) and the HTTPS port (8443 by default). The plain port stays on for migration; the TLS port is the production target.

Generate a self-signed cert for development with the JDK's `keytool`:

```bash
keytool -genkeypair \
  -alias tpipe-server \
  -keyalg RSA -keysize 2048 -validity 365 \
  -keystore keystore.p12 -storetype PKCS12 \
  -storepass change-me -keypass change-me \
  -dname "CN=localhost,O=TPipe-Dev" \
  -ext "SAN=DNS:localhost,IP:127.0.0.1"
```

## CORS Allowlist

`CorsConfig` replaces the previous `anyHost()` default. The default allowlist is `localhost` + `127.0.0.1`; wide-open requires an explicit opt-in:

```kotlin
// Local development
val cors = CorsConfig(allowedHosts = listOf("localhost", "127.0.0.1"))

// Production — explicit domain
val cors = CorsConfig(allowedHosts = listOf("dashboard.example.com"))

// Wide-open — explicit opt-in only
val cors = CorsConfig(allowedHosts = listOf("*"))
```

`allowedMethods` and `allowedHeaders` default to the verbs the dashboard uses (`GET`, `POST`, `DELETE`, `OPTIONS`) and the headers the API needs (`Authorization`, `Content-Type`, `X-Tenant`).

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

Client auth protects the dashboard endpoints (`GET /api/traces`, `GET /api/traces/{id}`, `DELETE /api/traces/{id}`, `WS /ws/traces`). Clients authenticate via `POST /api/auth/login` and receive a session token valid for 24 hours.

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
| `DELETE /api/traces/{id}` | Client session | `Authorization: Bearer <session>` |
| `GET /api/health` | (none) | (always public) |
| `WS /ws/traces` | Client session | Query param `?token=<session>` |

## REST API Endpoints

### `GET /api/health`

Lightweight liveness probe. Returns:

```json
{
    "status": "ok",
    "uptimeMs": 12345,
    "traces": { "total": 42, "tenants": 3 },
    "version": "1.0.0"
}
```

No auth required. Use it for load-balancer health checks.

---

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

**Response (401) — error envelope:**
```json
{"error": "unauthorized", "message": "Invalid credentials"}
```

When no `clientAuthMechanism` is configured, returns an anonymous session token without validation.

---

### `POST /api/traces`

Submits a trace report from an agent. Requires agent authentication if configured. Tenant is taken from the `X-Tenant` header, `?tenant=` query, or `default`.

**Request:**
```json
{
    "pipelineId": "pipeline-abc-123",
    "htmlContent": "<html>...trace report...</html>",
    "name": "My Pipeline Run",
    "status": "SUCCESS"
}
```

**Validation (400 — error envelope):** `pipelineId`, `name`, and `status` must be non-empty.

**Size limit (413 — error envelope):** `htmlContent` and the overall body are capped at `TraceServerConfig.maxPayloadBytes` (default 5 MB).

**Response (200):** After storing the trace, the server broadcasts a summary to all connected WebSocket clients in the same tenant so the dashboard updates in real time.

---

### `GET /api/traces`

Returns a paginated list of trace summaries for the resolved tenant, sorted by `timestamp` descending. Requires client session if `clientAuthMechanism` is configured.

**Query parameters (all optional):**

| Name | Description |
|------|-------------|
| `status` | Filter by exact status (`SUCCESS`, `FAILURE`, `PENDING`, ...). Case-insensitive. |
| `q` | Case-insensitive substring match against `id` / `name` / `status`. |
| `since` | Lower bound for `timestamp` (epoch millis). |
| `limit` | Page size, clamped to `1..500`, default `100`. |
| `offset` | Number of summaries to skip, default `0`. |
| `tenant` | Override the resolved tenant for this call. |

**Response:**
```json
{
    "items": [
        { "id": "pipeline-abc-123", "timestamp": 1710600000000, "name": "My Pipeline Run", "status": "SUCCESS" }
    ],
    "total": 1,
    "limit": 100,
    "offset": 0
}
```

---

### `GET /api/traces/{id}`

Returns the full trace payload for a specific `(tenant, pipelineId)`, including the HTML report content.

**Response:**
```json
{
    "pipelineId": "pipeline-abc-123",
    "htmlContent": "<html>...full trace report...</html>",
    "name": "My Pipeline Run",
    "status": "SUCCESS"
}
```

**Response (404) — error envelope:**
```json
{"error": "not_found", "message": "Trace not found"}
```

---

### `DELETE /api/traces/{id}`

Removes the trace for the resolved tenant. Requires client session if `clientAuthMechanism` is configured.

- **204 No Content** on success.
- **401 Unauthorized** when client auth is enabled and the session is missing or expired.
- **404 Not Found** when no trace exists for `(tenant, pipelineId)`.

---

### `GET /api/auth/config`

Public. Returns the auth mode the dashboard should render:

```json
{"mode": "KEY"}
```

## WebSocket Live Streaming

### `WS /ws/traces`

Connect to receive real-time trace summary broadcasts as agents submit new traces.

**Connection:** `wss://localhost:8443/ws/traces?token=<session-token>&tenant=alpha`

Both `token` and `tenant` are query parameters. The `token` is required when `clientAuthMechanism` is configured; the `tenant` is optional and defaults to the resolved value on the WebSocket upgrade (header → query → `default`).

Each time an agent submits a trace via `POST /api/traces` under the same tenant, the WebSocket clients in that tenant receive a JSON summary:

```json
{
    "id": "pipeline-abc-123",
    "timestamp": 1710600000000,
    "name": "My Pipeline Run",
    "status": "SUCCESS"
}
```

Broadcasts are sent on a registry-owned `CoroutineScope(SupervisorJob() + Dispatchers.IO)`, not on `GlobalScope`. The scope is cancelled by `stopTraceServer(...)`.

The server sends periodic WebSocket pings (every 15 seconds) to keep connections alive.

## Error Envelope

Every non-2xx response uses the same envelope shape:

```json
{ "error": "<machine-code>", "message": "<human-readable>" }
```

| Code | HTTP | When |
|------|------|------|
| `bad_request` | 400 | Validation failed (missing field, malformed JSON). |
| `unauthorized` | 401 | Missing or expired session, or bad agent auth. |
| `not_found` | 404 | Trace not found, or static asset missing. |
| `payload_too_large` | 413 | Request body exceeded `maxPayloadBytes`. |
| `internal_error` | 500 | Uncaught exception (Ktor `StatusPages` plugin). |

The Ktor `StatusPages` plugin is used (no reflection) so the shape is preserved under GraalVM native-image.

## Graceful Shutdown

`stopTraceServer(engine)` is the structured counterpart to `startTraceServer(config)`:

```kotlin
val engine = startTraceServer(config, wait = false)
// ... at shutdown time ...
stopTraceServer(engine, graceMs = 5_000)
```

It:

1. Stops the Netty engine with the supplied grace period (default 5 seconds).
2. Cancels the broadcast `CoroutineScope` (replaces the previous `GlobalScope.launch`).
3. Closes the active `TraceStore` (flushes the index snapshot for `FileBackedTraceStore`).
4. Unregisters the engine from the registry.

Calling it twice or with a foreign engine is safe.

## Connecting Agents with RemoteTraceDispatcher

The easiest way to send traces to a TraceServer is through `RemoteTraceConfig` and `RemoteTraceDispatcher`. These live in TPipe's `Debug` package in the root project and handle serialization, HTTP transport, and authentication automatically.

### Automatic Dispatch

Configure `RemoteTraceConfig` so that every call to `PipeTracer.exportTrace()` automatically pushes the trace to your server:

```kotlin
import com.TTT.Debug.RemoteTraceConfig

RemoteTraceConfig.remoteServerUrl = "http://localhost:8081"
RemoteTraceConfig.dispatchAutomatically = true

// Optional: set auth header manually
RemoteTraceConfig.authHeader = "Bearer my-agent-secret"
```

To scope dispatched traces to a specific tenant, set the tenant header before each export (or have your agent write to `RemoteTraceConfig.tenant` if you've extended the dispatcher for it):

```kotlin
RemoteTraceConfig.tenant = "alpha"
PipeTracer.exportTrace(pipelineId, TraceFormat.HTML)
```

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
# Default: port 8081, host 0.0.0.0, file-backed store
java -jar TPipe-TraceServer.jar

# Custom HTTP port
java -jar TPipe-TraceServer.jar --port 9090

# In-memory store (no disk I/O)
java -jar TPipe-TraceServer.jar --no-persist

# HTTPS on a separate port
java -jar TPipe-TraceServer.jar \
  --tls-key-store /etc/tpipe/keystore.p12 \
  --tls-key-store-password $KEYSTORE_PASSWORD \
  --tls-key-alias tpipe-server \
  --tls-port 8443

# File-backed store in a custom directory
java -jar TPipe-TraceServer.jar --store-dir /var/lib/tpipe/traces --store-max 50000

# Locked-down CORS
java -jar TPipe-TraceServer.jar --cors-allow dashboard.example.com
```

All flags can be combined. The parser is exposed as `parseArgs(args, baseConfig)` and is unit-tested.

## Complete Example

This example starts a TraceServer with TLS, file-backed persistence, a custom tenant, and dispatches a trace from a pipeline:

```kotlin
import com.TTT.Debug.PipeTracer
import com.TTT.Debug.RemoteTraceConfig
import com.TTT.Debug.TraceConfig
import com.TTT.Debug.TraceFormat
import com.TTT.Pipeline.Pipeline
import com.TTT.TraceServer.*
import com.TTT.TraceServer.store.FileBackedTraceStore
import com.TTT.TraceServer.store.InMemoryTraceStore
import bedrockPipe.BedrockPipe
import kotlinx.coroutines.runBlocking

fun main() = runBlocking {
    // 1. Configure auth + persistence
    TraceServerRegistry.agentAuthMechanism = { auth ->
        auth == "Bearer agent-token-123"
    }
    TraceServerRegistry.clientAuthMechanism = { key ->
        key == "dashboard-pwd"
    }

    // 2. Build the config (TLS, file-backed, custom CORS)
    val config = TraceServerConfig(
        port = 8081,
        tlsPort = 8443,
        tls = TlsConfig(
            enabled = true,
            keyStorePath = "/etc/tpipe/keystore.p12",
            keyStorePassword = System.getenv("KEYSTORE_PASSWORD"),
            keyAlias = "tpipe-server"
        ),
        cors = CorsConfig(allowedHosts = listOf("dashboard.example.com", "localhost")),
        store = StoreConfig(
            type = StoreType.FILE_BACKED,
            directory = java.nio.file.Paths.get("/var/lib/tpipe/traces"),
            maxTraces = 10_000
        )
    )

    // 3. Start the server and capture the engine for clean shutdown
    val engine = startTraceServer(config)

    // 4. Configure remote dispatch (scoped to a specific tenant)
    RemoteTraceConfig.remoteServerUrl = "https://localhost:8443"
    RemoteTraceConfig.authHeader = "Bearer agent-token-123"
    RemoteTraceConfig.dispatchAutomatically = true

    // 5. Run a traced pipeline
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
    PipeTracer.exportTrace(pipeline.pipelineId, TraceFormat.HTML)

    // 6. Shutdown gracefully
    stopTraceServer(engine)
}
```

## Best Practices

- **Separate agent and client auth** — use strong tokens for agent auth and simpler credentials for dashboard login during development.
- **Always supply an explicit `TlsConfig`** in production — the file-backed store and the WebSocket endpoint should not be exposed on plain HTTP to the public internet.
- **Set the `X-Tenant` header from your agent** if you run multiple teams against the same TraceServer; the registry will partition sessions, store buckets, and WS broadcasts automatically.
- **Pick a `TraceStore` that fits the workload** — `InMemoryTraceStore` for tests and short-lived demos, `FileBackedTraceStore` for any process that should survive a restart. Implement a custom store (Redis, Postgres) for distributed deployments.
- **Bind the `cors` allowlist tightly** in production; use `listOf("*")` only for explicit opt-in.
- **Monitor `/api/health`** from your orchestrator; the envelope shape is stable and includes the trace count.
- **Always call `stopTraceServer(engine)`** at shutdown so the broadcast scope is cancelled and the file-backed store flushes its index snapshot.
- **Use `dispatchAutomatically = true`** during development so every trace export lands in the dashboard without extra code.
- **Use `AuthRegistry`** instead of hardcoding `RemoteTraceConfig.authHeader` when your agents already register tokens for other remote services.
- **Set `wait = false`** when embedding TraceServer inside a larger application so it doesn't block your main thread.

## Future Work (Deferred from v1)

The following are intentionally NOT in v1 and are documented here so we don't lose track:

- Per-event WebSocket streaming (today the WS only carries the trace summary, not every step).
- Response compression (gzip/br) on REST responses.
- Per-IP / per-tenant rate limiting.
- Refresh tokens for the dashboard session.
- Server-side password hashing for the `clientAuthMechanism` (callers must pre-hash today).
- Real credentials validation backed by a user store.
- GraalVM native-image target for `TPipe-TraceServer` (the API is shaped so a future native build only needs the standard Ktor/Netty reflection configs plus the `--enable-https` build arg already used elsewhere in the worktree).

---

**Related:**
- [Tracing and Debugging](../core-concepts/tracing-and-debugging.md) — Core tracing concepts and RemoteTraceConfig setup
- [Debug Package API](../api/debug-package.md) — PipeTracer, TraceEvent, and TraceConfig reference
- [TPipeConfig API](../api/tpipe-config.md) — AuthRegistry and `addRemoteAuth()` for unified authentication
## Next Steps

- [P2P Overview](p2p/p2p-overview.md) - Continue into agent-to-agent communication.
