package com.TTT.TraceServer

import com.TTT.TraceServer.store.DEFAULT_TENANT
import com.TTT.TraceServer.store.FileBackedTraceStore
import com.TTT.TraceServer.store.TraceStore
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Duration

/**
 * Persistence backend selected by [StoreConfig.type].
 */
enum class StoreType {
    /** Volatile, bounded LRU per tenant. */
    IN_MEMORY,

    /** JSONL append-only log + index snapshot, default for production-like runs. */
    FILE_BACKED
}

/**
 * Persistence configuration. The `directory` field is only used when
 * [type] is [StoreType.FILE_BACKED].
 *
 * v2 additions: [ttl] and [perTenantQuota] are honored only by the
 * file-backed store. Passing `null` for either disables the corresponding
 * policy; the in-memory store ignores them.
 */
data class StoreConfig(
    val type: StoreType = StoreType.FILE_BACKED,
    val directory: Path = Paths.get(System.getProperty("user.home"), ".TPipe-Debug", "trace-server"),
    val maxTraces: Int = 10_000,
    val ttl: Duration? = Duration.ofDays(7),
    val perTenantQuota: Int? = 2_000
)
{
    fun resolveStore(): TraceStore = when(type)
    {
        StoreType.IN_MEMORY -> com.TTT.TraceServer.store.InMemoryTraceStore(maxTraces)
        StoreType.FILE_BACKED -> FileBackedTraceStore(directory, maxTraces, ttl, perTenantQuota)
    }
}

/**
 * Top-level TraceServer configuration.
 *
 * The class is a `data class` so all options are immutable and explicit, but
 * the historic [port] and [host] vars are kept as a backward-compat shim: any
 * pre-existing code that mutated `TraceServerConfig.port = 9090` will still
 * work as long as no [TraceServerConfig] instance has been passed into
 * [startTraceServer] yet.
 *
 * @property port HTTP port (plain text) the server binds to. Default 8081.
 * @property host bind address. Default `0.0.0.0`.
 * @property tlsPort HTTPS port when [tls] is enabled. Default 8443.
 * @property tls TLS configuration. Default disabled.
 * @property cors CORS allowlist. Default localhost-only.
 * @property store persistence configuration. Default file-backed under `~/.TPipe-Debug/trace-server/`.
 * @property authMode which auth flow the dashboard advertises.
 * @property auth v2 auth configuration (password hashing + token TTLs).
 * @property rateLimit v2 rate-limit configuration.
 * @property compression v2 response-compression configuration.
 * @property metrics v2 Prometheus metrics configuration.
 * @property maxPayloadBytes maximum accepted size of the `htmlContent` field
 *  on `POST /api/traces`. Larger payloads get a 413.
 * @property version identifier exposed by `/api/health`.
 * @property defaultTenant tenant key used when a request does not supply one.
 */
data class TraceServerConfig(
    val port: Int = 8081,
    val host: String = "0.0.0.0",
    val tlsPort: Int = 8443,
    val tls: TlsConfig = TlsConfig(),
    val cors: CorsConfig = CorsConfig(),
    val store: StoreConfig = StoreConfig(),
    val authMode: AuthMode = AuthMode.KEY,
    val auth: AuthConfig = AuthConfig(),
    val rateLimit: RateLimitConfig = RateLimitConfig(),
    val compression: CompressionConfig = CompressionConfig(),
    val metrics: MetricsConfig = MetricsConfig(),
    val maxPayloadBytes: Long = 5L * 1024L * 1024L,
    val version: String = "1.0.0",
    val defaultTenant: String = DEFAULT_TENANT
)

/**
 * Backward-compat shim. The original API exposed `port` and `host` as `var`
 * properties of an `object`. We keep that surface by storing the legacy
 * mutable values here and falling back to them when no explicit config is
 * passed to [startTraceServer].
 */
object TraceServerConfigLegacy {
    @Volatile var port: Int = 8081
    @Volatile var host: String = "0.0.0.0"
}

/**
 * Returns the legacy mutable config, retained so existing call sites that
 * mutate `TraceServerConfig.port` / `TraceServerConfig.host` still see the
 * value. New code should construct a [TraceServerConfig] directly.
 */
object TraceServerConfigBridge {
    fun legacy(): TraceServerConfig = TraceServerConfig(
        port = TraceServerConfigLegacy.port,
        host = TraceServerConfigLegacy.host
    )
}
