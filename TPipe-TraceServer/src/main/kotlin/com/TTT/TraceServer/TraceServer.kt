package com.TTT.TraceServer

import com.TTT.P2P.P2PRegistry
import com.TTT.TraceServer.store.DEFAULT_TENANT
import com.TTT.TraceServer.store.InMemoryTraceStore
import com.TTT.TraceServer.store.TraceFilter
import com.TTT.TraceServer.store.TraceListResult
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.metrics.micrometer.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.compression.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.plugins.ratelimit.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import io.micrometer.core.instrument.Counter
import io.micrometer.prometheus.PrometheusConfig
import io.micrometer.prometheus.PrometheusMeterRegistry
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.launch
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonClassDiscriminator
import kotlinx.serialization.json.JsonElement
import java.io.FileInputStream
import java.security.KeyStore
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.KeyManagerFactory
import kotlin.time.Duration.Companion.seconds

private val SERVER_STARTED_AT: AtomicLong = AtomicLong(System.currentTimeMillis())

/**
 * Process-wide [Json] instance used for all serialization on the server side.
 *
 * The v1 code created a fresh `Json { ... }` per request; the kotlin compiler
 * surfaced that as a `Redundant creation of Json format` warning and the
 * allocation pressure was visible on hot paths. v2 hoists the configuration
 * here so all routes share one format. The configuration is intentionally
 * permissive: unknown keys are ignored (forward compatibility with future
 * payload fields) and the lenient mode accepts unquoted JSON values that
 * some `RemoteTraceDispatcher` versions emit.
 */
internal val TraceServerJson: Json = Json {
    prettyPrint = true
    isLenient = true
    ignoreUnknownKeys = true
    encodeDefaults = true
    classDiscriminator = "op"
    explicitNulls = false
}

/**
 * Lazy global meter registry. Created on first install so tests that don't
 * install the metrics plugin don't pay the boot cost.
 */
internal val TraceServerMetrics: PrometheusMeterRegistry by lazy {
    PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
}

internal fun tracesReceivedCounter(tenant: String): Counter =
    Counter.builder("tpipe_traces_received_total")
        .description("Total number of traces submitted via POST /api/traces, partitioned by tenant")
        .tag("tenant", tenant)
        .register(TraceServerMetrics)

internal fun tracesStoredCounter(tenant: String): Counter =
    Counter.builder("tpipe_traces_stored")
        .description("Total number of traces persisted to the active store, partitioned by tenant")
        .tag("tenant", tenant)
        .register(TraceServerMetrics)

internal fun wsConnectionsGauge(tenant: String): AtomicLong = WS_CONNECTION_GAUGE.computeIfAbsent(tenant) { AtomicLong(0L) }

private val WS_CONNECTION_GAUGE: MutableMap<String, AtomicLong> = java.util.concurrent.ConcurrentHashMap()

internal fun eventsStreamedCounter(tenant: String): Counter =
    Counter.builder("tpipe_events_streamed_total")
        .description("Total number of trace events streamed via WebSocket, partitioned by tenant")
        .tag("tenant", tenant)
        .register(TraceServerMetrics)

internal fun authFailuresCounter(reason: String): Counter =
    Counter.builder("tpipe_auth_failures_total")
        .description("Total number of authentication failures, partitioned by reason")
        .tag("reason", reason)
        .register(TraceServerMetrics)

/**
 * Main entry point for standalone execution.
 *
 * Accepts the following CLI flags in addition to the historic `--port` and
 * `--host`. New flags are additive; the v1 surface is preserved.
 *
 *   --tls-port            HTTPS port (only used when TLS is enabled)
 *   --tls-key-store       Path to JKS or PKCS12 keystore
 *   --tls-key-store-password   Keystore password
 *   --tls-key-alias       Alias of the private key
 *   --tls-key-password    Private-key password (defaults to keystore password)
 *   --tls-trust-store     Optional truststore for mutual TLS
 *   --tls-mutual          Enable mutual TLS (client cert validation)
 *   --store-dir           Override the file-backed store directory
 *   --store-max           Override the per-tenant cap
 *   --store-ttl           Override the file-backed TTL (Duration, e.g. PT24H)
 *   --store-quota         Override the per-tenant quota
 *   --no-persist          Use the in-memory store (skips disk I/O)
 *   --cors-allow <host>   Repeatable: add an allowed CORS origin
 *   --auth-hash-iterations    PBKDF2 iteration count (default 600000)
 *   --auth-access-ttl         Access-token TTL in minutes (default 15)
 *   --auth-refresh-ttl        Refresh-token TTL in days (default 7)
 *   --rate-limit-ip           Per-IP writes per window (default 60)
 *   --rate-limit-tenant       Per-tenant writes per window (default 600)
 *   --no-compression          Disable response compression
 *   --no-metrics              Disable the /metrics endpoint
 */
fun main(args: Array<String>)
{
    val config = parseArgs(args, TraceServerConfigBridge.legacy())
    startTraceServer(config, wait = true)
}

/**
 * Parses CLI arguments into a [TraceServerConfig]. Pure function, exposed for
 * tests and the demo.
 */
fun parseArgs(args: Array<String>, base: TraceServerConfig): TraceServerConfig
{
    var port: Int = base.port
    var host: String = base.host
    var tlsPort: Int = base.tlsPort
    var tls: TlsConfig = base.tls
    var store = base.store
    var cors = base.cors
    var auth = base.auth
    var rateLimit = base.rateLimit
    var compression = base.compression
    var metrics = base.metrics
    val extraCors = mutableListOf<String>()

    var i = 0
    while(i < args.size)
    {
        val arg = args[i]
        when(arg)
        {
            "--port" -> { port = args.getOrNull(++i)?.toIntOrNull() ?: port }
            "--host" -> { host = args.getOrNull(++i) ?: host }
            "--tls-port" -> { tlsPort = args.getOrNull(++i)?.toIntOrNull() ?: tlsPort }
            "--tls-key-store" -> { tls = tls.copy(keyStorePath = args.getOrNull(++i), enabled = true) }
            "--tls-key-store-password" -> { tls = tls.copy(keyStorePassword = args.getOrNull(++i), enabled = true) }
            "--tls-key-alias" -> { tls = tls.copy(keyAlias = args.getOrNull(++i), enabled = true) }
            "--tls-key-password" -> { tls = tls.copy(keyPassword = args.getOrNull(++i), enabled = true) }
            "--tls-trust-store" -> { tls = tls.copy(trustStorePath = args.getOrNull(++i), enabled = true) }
            "--tls-mutual" -> { tls = tls.copy(mutualTls = true, enabled = true) }
            "--store-dir" -> { store = store.copy(directory = java.nio.file.Paths.get(args.getOrNull(++i) ?: store.directory.toString())) }
            "--store-max" -> { store = store.copy(maxTraces = args.getOrNull(++i)?.toIntOrNull() ?: store.maxTraces) }
            "--store-ttl" -> { store = store.copy(ttl = parseDurationOrNull(args.getOrNull(++i)) ?: store.ttl) }
            "--store-quota" -> { store = store.copy(perTenantQuota = args.getOrNull(++i)?.toIntOrNull() ?: store.perTenantQuota) }
            "--no-persist" -> { store = store.copy(type = StoreType.IN_MEMORY) }
            "--cors-allow" -> { extraCors.add(args.getOrNull(++i) ?: continue) }
            "--auth-hash-iterations" -> {
                val iters = args.getOrNull(++i)?.toIntOrNull()
                if(iters != null) auth = auth.copy(passwordHasherEnabled = true)
                val safeIters = iters ?: (auth.accessTokenTtl.inWholeMinutes.coerceAtLeast(1L) * 60_000L).toInt()
                TraceServerRegistry.setPasswordHasher(
                    com.TTT.TraceServer.auth.Pbkdf2PasswordHasher(iterations = safeIters)
                )
            }
            "--auth-access-ttl" -> {
                val minutes = args.getOrNull(++i)?.toLongOrNull()
                if(minutes != null) auth = auth.copy(accessTokenTtl = kotlin.time.Duration.parse("${minutes}m"))
            }
            "--auth-refresh-ttl" -> {
                val days = args.getOrNull(++i)?.toLongOrNull()
                if(days != null) auth = auth.copy(refreshTokenTtl = kotlin.time.Duration.parse("${days}d"))
            }
            "--rate-limit-ip" -> { rateLimit = rateLimit.copy(perIpWrites = args.getOrNull(++i)?.toIntOrNull() ?: rateLimit.perIpWrites) }
            "--rate-limit-tenant" -> { rateLimit = rateLimit.copy(perTenantWrites = args.getOrNull(++i)?.toIntOrNull() ?: rateLimit.perTenantWrites) }
            "--no-compression" -> { compression = compression.copy(enabled = false) }
            "--no-metrics" -> { metrics = metrics.copy(enabled = false) }
        }
        i++
    }
    if(extraCors.isNotEmpty())
    {
        cors = cors.copy(allowedHosts = (cors.allowedHosts + extraCors).distinct())
    }
    return base.copy(
        port = port,
        host = host,
        tlsPort = tlsPort,
        tls = tls,
        store = store,
        cors = cors,
        auth = auth,
        rateLimit = rateLimit,
        compression = compression,
        metrics = metrics
    )
}

private fun parseDurationOrNull(raw: String?): java.time.Duration?
{
    if(raw.isNullOrBlank()) return null
    return try { java.time.Duration.parse(raw) } catch (e: Exception) { null }
}

/**
 * Starts the TraceServer programmatically.
 *
 * @param port HTTP port to bind to. Defaults to [TraceServerConfig.port].
 * @param host Host to bind to. Defaults to [TraceServerConfig.host].
 * @param wait Whether to block the thread waiting for the server to stop. Default is `false`.
 */
fun startTraceServer(
    port: Int = TraceServerConfigBridge.legacy().port,
    host: String = TraceServerConfigBridge.legacy().host,
    wait: Boolean = false
): EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>
{
    val config = TraceServerConfigBridge.legacy().copy(port = port, host = host)
    return startTraceServer(config, wait)
}

/**
 * Starts the TraceServer with the given [config]. Returns the running engine
 * so callers can pass it to [stopTraceServer] for a graceful shutdown.
 */
fun startTraceServer(
    config: TraceServerConfig,
    wait: Boolean = false
): EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>
{
    // Keep the legacy mutable config in sync so any pre-existing code paths
    // that read TraceServerConfig.port / host still see the active value.
    TraceServerConfigLegacy.port = config.port
    TraceServerConfigLegacy.host = config.host
    TraceServerRegistry.authMode = config.authMode
    TraceServerRegistry.authConfig = config.auth
    if(config.tls.isValid())
    {
        val existing = TraceServerRegistry.store
        val resolved = config.store.resolveStore()
        if(existing !== resolved)
        {
            TraceServerRegistry.configureStore(resolved)
        }
    } else {
        // Misconfigured TLS shouldn't fail boot silently.
        if(config.tls.enabled)
        {
            throw IllegalArgumentException(
                "TlsConfig is enabled but missing required fields (keyStorePath, keyAlias)."
            )
        }
    }

    val env = applicationEnvironment { }
    val engine: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration> = embeddedServer(
        Netty,
        environment = env,
        configure = {
            connector {
                port = config.port
                host = config.host
            }
            if(config.tls.enabled)
            {
                val keyStore = buildKeyStore(config.tls)
                val alias = config.tls.keyAlias ?: error("TLS enabled but keyAlias is null")
                sslConnector(
                    keyStore = keyStore,
                    keyAlias = alias,
                    keyStorePassword = { config.tls.keyStorePassword?.toCharArray() ?: charArrayOf() },
                    privateKeyPassword = { (config.tls.keyPassword ?: config.tls.keyStorePassword)?.toCharArray() ?: charArrayOf() }
                ) {
                    port = config.tlsPort
                    host = config.host
                    if(config.tls.mutualTls)
                    {
                        trustStore = buildTrustStore(config.tls)
                    }
                    enabledProtocols = config.tls.protocols
                }
            }
        },
        module = { traceServerModule(config) }
    )
    TraceServerRegistry.registerEngine(engine)
    engine.start(wait = wait)
    return engine
}

/**
 * Stops the server previously started by [startTraceServer] with a 5-second
 * grace period, cancels the broadcast scope, and closes the persistence
 * store. Safe to call multiple times; safe to call with a foreign engine that
 * was not registered with the registry.
 */
fun stopTraceServer(
    engine: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>,
    graceMs: Long = 5_000
)
{
    try
    {
        engine.stop(graceMs, graceMs, java.util.concurrent.TimeUnit.MILLISECONDS)
    } finally {
        TraceServerRegistry.unregisterEngine(engine)
        TraceServerRegistry.shutdownRegistry()
    }
}

/**
 * Builds the JDK [KeyStore] the SSL connector consumes. Tries PKCS12 first
 * (the default for `keytool -genkey` since JDK 9), then JKS. The keystore
 * path is required; missing files fail fast with an [IllegalStateException]
 * that the boot code can surface as a clear CLI error.
 */
private fun buildKeyStore(tls: TlsConfig): KeyStore
{
    val path = tls.keyStorePath ?: error("TLS enabled but keyStorePath is null")
    val ks: KeyStore = try
    {
        KeyStore.getInstance("PKCS12").also { it.load(FileInputStream(path), tls.keyStorePassword?.toCharArray()) }
    } catch (e: Exception)
    {
        try
        {
            KeyStore.getInstance("JKS").also { it.load(FileInputStream(path), tls.keyStorePassword?.toCharArray()) }
        } catch (inner: Exception)
        {
            throw IllegalStateException("Could not load keystore at $path: ${e.message} / ${inner.message}", e)
        }
    }
    return ks
}

/**
 * Builds the truststore used by the SSL connector for mutual TLS. When
 * [TlsConfig.trustStorePath] is `null`, an empty default-type KeyStore is
 * returned; Netty will fall back to the JVM default truststore in that case.
 */
private fun buildTrustStore(tls: TlsConfig): KeyStore
{
    val trustStorePath = tls.trustStorePath
    if(trustStorePath == null)
    {
        return KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }
    }
    val ks: KeyStore = try
    {
        KeyStore.getInstance("PKCS12").also { it.load(FileInputStream(trustStorePath), tls.trustStorePassword?.toCharArray()) }
    } catch (e: Exception)
    {
        KeyStore.getInstance("JKS").also { it.load(FileInputStream(trustStorePath), tls.trustStorePassword?.toCharArray()) }
    }
    return ks
}

/**
 * Resolves the tenant for an incoming request. Order matches the documented
 * behavior: explicit `X-Tenant` header wins, then `?tenant=` query, then the
 * configured default.
 */
private fun ApplicationCall.resolveTenant(default: String): String
{
    val header = request.headers["X-Tenant"]
    if(!header.isNullOrBlank()) return header
    val query = request.queryParameters["tenant"]
    if(!query.isNullOrBlank()) return query
    return default
}

/**
 * Configures the Ktor application with WebSockets, JSON content negotiation,
 * CORS, status pages, compression, rate-limiting, and metrics. The full
 * [config] is captured in the closure so the route handlers can read the
 * active values (CORS allowlist, max payload size, version, rate limits)
 * without consulting mutable globals.
 */
fun Application.traceServerModule(config: TraceServerConfig = TraceServerConfigBridge.legacy())
{
    install(WebSockets) {
        pingPeriod = 15.seconds
        timeout = 15.seconds
        maxFrameSize = Long.MAX_VALUE
        masking = false
    }

    install(ContentNegotiation) {
        json(TraceServerJson)
    }

    install(CORS) {
        val cors = config.cors
        if(cors.isWildcard())
        {
            anyHost()
        } else
        {
            for(host in cors.allowedHosts) allowHost(host)
        }
        for(method in cors.allowedMethods) allowMethod(HttpMethod.parse(method.uppercase()))
        for(header in cors.allowedHeaders) allowHeader(header)
        if(cors.allowCredentials && !cors.isWildcard())
        {
            allowCredentials = true
        }
    }

    install(StatusPages) {
        exception<Throwable> { call, cause ->
            call.respond(
                HttpStatusCode.InternalServerError,
                ErrorEnvelope(error = "internal_error", message = cause.message ?: "Internal server error")
            )
        }
        status(HttpStatusCode.BadRequest) { call, status ->
            val body = ErrorEnvelope(error = "bad_request", message = status.description)
            call.respond(status, body)
        }
        status(HttpStatusCode.Unauthorized) { call, status ->
            val body = ErrorEnvelope(error = "unauthorized", message = status.description)
            call.respond(status, body)
        }
        status(HttpStatusCode.NotFound) { call, status ->
            val body = ErrorEnvelope(error = "not_found", message = status.description)
            call.respond(status, body)
        }
        status(HttpStatusCode.PayloadTooLarge) { call, status ->
            val body = ErrorEnvelope(error = "payload_too_large", message = status.description)
            call.respond(status, body)
        }
        status(HttpStatusCode.TooManyRequests) { call, status ->
            val body = ErrorEnvelope(error = "rate_limited", message = status.description)
            call.respond(status, body)
        }
    }

    if(config.compression.enabled)
    {
        install(Compression) {
            minimumSize(config.compression.minSize)
            if(config.compression.gzip) gzip()
            if(config.compression.deflate) deflate()
        }
    }

    if(config.metrics.enabled)
    {
        install(MicrometerMetrics) {
            registry = TraceServerMetrics
            // Bind JVM + HTTP metrics. We do NOT use meterBinders to avoid
            // pulling in extra artifacts; the JVM and HTTP binders come
            // transitively with the plugin.
        }
    }

    // Always install the RateLimit plugin with the per-IP writes bucket.
    // When the v2 rate limit is disabled we use a very high capacity so the
    // limit is effectively a no-op; this keeps the route shape uniform (the
    // write posts are always wrapped in a named limit) so the
    // rate-limit-disabled code path is just a different capacity.
    install(RateLimit) {
        val effectiveLimit = if(config.rateLimit.enabled) config.rateLimit.perIpWrites else Int.MAX_VALUE
        register(RateLimitName("writes-per-ip")) {
            requestKey { call -> call.request.local.remoteHost }
            rateLimiter(limit = effectiveLimit, refillPeriod = config.rateLimit.window)
        }
    }

    val maxPayloadBytes = config.maxPayloadBytes
    val version = config.version
    val defaultTenant = config.defaultTenant
    val authMode = config.authMode
    val rateLimitConfig = config.rateLimit
    val metricsConfig = config.metrics

    routing {
        get("/") {
            val html = object {}.javaClass.classLoader.getResource("static/index.html")?.readText()
            if(html != null)
            {
                call.respondText(html, ContentType.Text.Html)
            } else {
                call.respond(
                    HttpStatusCode.NotFound,
                    ErrorEnvelope(error = "not_found", message = "Dashboard not found")
                )
            }
        }

        get("/dashboard.js") {
            val js = object {}.javaClass.classLoader.getResource("static/dashboard.js")?.readText()
            if(js != null)
            {
                call.respondText(js, ContentType.Application.JavaScript)
            } else {
                call.respond(
                    HttpStatusCode.NotFound,
                    ErrorEnvelope(error = "not_found", message = "Script not found")
                )
            }
        }

        get("/api/openapi.yaml") {
            val text = object {}.javaClass.classLoader.getResource("static/openapi.yaml")?.readText()
            if(text != null)
            {
                call.respondText(text, ContentType("application", "yaml"))
            } else {
                call.respond(
                    HttpStatusCode.NotFound,
                    ErrorEnvelope(error = "not_found", message = "OpenAPI spec not found")
                )
            }
        }

        if(metricsConfig.enabled)
        {
            get(metricsConfig.path) {
                call.respondText(
                    TraceServerMetrics.scrape(),
                    ContentType.parse("text/plain; version=0.0.4; charset=utf-8")
                )
            }
        }

        get("/api/health") {
            val store = TraceServerRegistry.store
            val tenant = call.resolveTenant(defaultTenant)
            val total = store.count(tenant)
            val totalTenants = store.tenantNames().size
            val storeInfo = HealthStoreInfo(
                type = when(store) {
                    is com.TTT.TraceServer.store.FileBackedTraceStore -> "FILE_BACKED"
                    is InMemoryTraceStore -> "IN_MEMORY"
                    else -> "CUSTOM"
                },
                directory = (store as? com.TTT.TraceServer.store.FileBackedTraceStore)?.let { it.directoryPath() } ?: "",
                maxTraces = (store as? com.TTT.TraceServer.store.FileBackedTraceStore)?.let { it.maxTracesValue() }
                    ?: (store as? InMemoryTraceStore)?.let { it.maxTracesValue() }
                    ?: 0,
                ttlMs = (store as? com.TTT.TraceServer.store.FileBackedTraceStore)?.ttlMs(),
                perTenantQuota = (store as? com.TTT.TraceServer.store.FileBackedTraceStore)?.perTenantQuotaValue()
            )
            call.respond(
                HealthEnvelope(
                    status = "ok",
                    uptimeMs = System.currentTimeMillis() - SERVER_STARTED_AT.get(),
                    traces = HealthTracesInfo(total = total, tenants = totalTenants),
                    version = version,
                    store = storeInfo,
                    metricsEnabled = metricsConfig.enabled
                )
            )
        }

        get("/api/auth/config") {
            call.respond(AuthConfigEnvelope(mode = authMode.name))
        }

        rateLimit(RateLimitName("writes-per-ip")) {
        post("/api/auth/login") {

            // Two paths: (1) hashed password via expectedHash, (2) legacy lambda.
            // Both produce a paired access + refresh token.
            val req = try
            {
                call.receive<AuthRequest>()
            } catch (e: Exception)
            {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorEnvelope(error = "bad_request", message = "Invalid request format")
                )
                return@post
            }

            val tenant = call.resolveTenant(defaultTenant)
            val expected = TraceServerRegistry.authConfig.expectedHash

            val ok: Boolean = if(TraceServerRegistry.authConfig.passwordHasherEnabled && expected != null && req.password != null)
            {
                TraceServerRegistry.passwordHasher.verify(req.password, expected)
            } else
            {
                val raw = req.key ?: ""
                TraceServerRegistry.clientAuthMechanism?.invoke(raw) ?: false
            }

            if(!ok)
            {
                authFailuresCounter("login").increment()
                call.respond(
                    HttpStatusCode.Unauthorized,
                    ErrorEnvelope(error = "unauthorized", message = "Invalid credentials")
                )
                return@post
            }

            val (access, refresh) = TraceServerRegistry.createSessionPair(tenant)
            call.respond(
                AuthResponse(
                    token = access,
                    refreshToken = refresh,
                    expiresInMs = TraceServerRegistry.authConfig.accessTokenTtl.inWholeMilliseconds
                )
            )
        }

        }

        post("/api/auth/refresh") {
            val req = try
            {
                call.receive<RefreshRequest>()
            } catch (e: Exception)
            {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorEnvelope(error = "bad_request", message = "Invalid request format")
                )
                return@post
            }
            val token = req.refreshToken
            if(token.isNullOrBlank())
            {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorEnvelope(error = "bad_request", message = "refreshToken is required")
                )
                return@post
            }
            val tenant = call.resolveTenant(defaultTenant)
            val rotated = TraceServerRegistry.rotateRefresh(token, tenant)
            if(rotated == null)
            {
                authFailuresCounter("refresh").increment()
                call.respond(
                    HttpStatusCode.Unauthorized,
                    ErrorEnvelope(error = "unauthorized", message = "Refresh token invalid or expired")
                )
                return@post
            }
            val (newAccess, newRefresh) = rotated
            call.respond(
                RefreshResponse(
                    token = newAccess,
                    refreshToken = newRefresh,
                    expiresInMs = TraceServerRegistry.authConfig.accessTokenTtl.inWholeMilliseconds
                )
            )
        }

        get("/api/traces") {
            val token = call.request.headers["Authorization"]?.removePrefix("Bearer ")
            val tenant = call.resolveTenant(defaultTenant)

            if(TraceServerRegistry.clientAuthMechanism != null && !TraceServerRegistry.validateSession(token, tenant))
            {
                call.respond(
                    HttpStatusCode.Unauthorized,
                    ErrorEnvelope(error = "unauthorized", message = "Session expired or unauthorized")
                )
                return@get
            }

            val filter = TraceFilter(
                tenant = tenant,
                status = call.request.queryParameters["status"],
                query = call.request.queryParameters["q"],
                tag = call.request.queryParameters["tag"],
                since = call.request.queryParameters["since"]?.toLongOrNull(),
                limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 100,
                offset = call.request.queryParameters["offset"]?.toIntOrNull() ?: 0
            )
            val result: TraceListResult = TraceServerRegistry.store.listSummaries(filter)
            call.respond(
                TraceListEnvelope(
                    items = result.items,
                    total = result.total,
                    limit = result.limit,
                    offset = result.offset
                )
            )
        }

        get("/api/traces/{id}") {
            val token = call.request.headers["Authorization"]?.removePrefix("Bearer ")
            val tenant = call.resolveTenant(defaultTenant)
            if(TraceServerRegistry.clientAuthMechanism != null && !TraceServerRegistry.validateSession(token, tenant))
            {
                call.respond(
                    HttpStatusCode.Unauthorized,
                    ErrorEnvelope(error = "unauthorized", message = "Session expired or unauthorized")
                )
                return@get
            }

            val id = call.parameters["id"]
            val trace = TraceServerRegistry.store.get(id ?: "", tenant)
            if(trace != null)
            {
                call.respond(trace)
            } else
            {
                call.respond(
                    HttpStatusCode.NotFound,
                    ErrorEnvelope(error = "not_found", message = "Trace not found")
                )
            }
        }

        get("/api/traces/{id}/events") {
            val token = call.request.headers["Authorization"]?.removePrefix("Bearer ")
            val tenant = call.resolveTenant(defaultTenant)
            if(TraceServerRegistry.clientAuthMechanism != null && !TraceServerRegistry.validateSession(token, tenant))
            {
                call.respond(
                    HttpStatusCode.Unauthorized,
                    ErrorEnvelope(error = "unauthorized", message = "Session expired or unauthorized")
                )
                return@get
            }
            val id = call.parameters["id"] ?: ""
            val events = TraceServerRegistry.store.getEvents(id, tenant)
            call.respond(mapOf("events" to events))
        }

        delete("/api/traces/{id}") {
            val token = call.request.headers["Authorization"]?.removePrefix("Bearer ")
            val tenant = call.resolveTenant(defaultTenant)
            if(TraceServerRegistry.clientAuthMechanism != null && !TraceServerRegistry.validateSession(token, tenant))
            {
                call.respond(
                    HttpStatusCode.Unauthorized,
                    ErrorEnvelope(error = "unauthorized", message = "Session expired or unauthorized")
                )
                return@delete
            }

            val id = call.parameters["id"]
            val removed = TraceServerRegistry.store.delete(id ?: "", tenant)
            if(removed)
            {
                call.respond(HttpStatusCode.NoContent)
            } else
            {
                call.respond(
                    HttpStatusCode.NotFound,
                    ErrorEnvelope(error = "not_found", message = "Trace not found")
                )
            }
        }

        rateLimit(RateLimitName("writes-per-ip")) {
        post("/api/traces") {

            // Check Agent auth mechanism for submitting traces
            val auth = call.request.headers["Authorization"]
            val isAuthorized = TraceServerRegistry.agentAuthMechanism?.invoke(auth)
                ?: P2PRegistry.globalAuthMechanism?.invoke(auth ?: "")
                ?: true

            if(!isAuthorized)
            {
                authFailuresCounter("agent").increment()
                call.respond(
                    HttpStatusCode.Unauthorized,
                    ErrorEnvelope(error = "unauthorized", message = "Unauthorized Agent")
                )
                return@post
            }

            val tenant = call.resolveTenant(defaultTenant)
            if(rateLimitConfig.enabled) {
                // Per-tenant write bucket: applied as a second guard after the
                // per-IP check so noisy tenants cannot starve each other.
                val tenantKey = "tenant:" + tenant
                try {
                    call.enforceTenantRateLimit(tenantKey, rateLimitConfig.perTenantWrites)
                } catch (e: RateLimitExceededException) {
                    call.respond(
                        HttpStatusCode.TooManyRequests,
                        ErrorEnvelope(error = "rate_limited", message = "Tenant write rate limit exceeded; retry later.")
                    )
                    return@post
                }
            }

            val raw = try
            {
                call.receiveText()
            } catch (e: Exception)
            {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorEnvelope(error = "bad_request", message = "Could not read request body")
                )
                return@post
            }

            if(raw.length > maxPayloadBytes)
            {
                call.respond(
                    HttpStatusCode.PayloadTooLarge,
                    ErrorEnvelope(error = "payload_too_large", message = "Request body exceeds $maxPayloadBytes bytes")
                )
                return@post
            }

            val payload = try
            {
                TraceServerJson.decodeFromString(TracePayload.serializer(), raw)
            } catch (e: SerializationException)
            {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorEnvelope(error = "bad_request", message = "Invalid JSON payload: ${e.message}")
                )
                return@post
            }

            if(payload.pipelineId.isBlank() || payload.name.isBlank() || payload.status.isBlank())
            {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorEnvelope(error = "bad_request", message = "pipelineId, name, and status must be non-empty")
                )
                return@post
            }

            TraceServerRegistry.store.put(payload, tenant)
            tracesReceivedCounter(tenant).increment()
            tracesStoredCounter(tenant).increment()

            // Build a summary anchored to the timestamp the store actually used.
            // For simplicity we use `System.currentTimeMillis()` here as well;
            // the store keeps the authoritative value but the WS message only
            // needs to be a rough ordering hint.
            val summary = TraceSummary(payload.pipelineId, System.currentTimeMillis(), payload.name, payload.status)
            // Legacy v1 wire format (no `op` discriminator). v2 dashboards
            // that want the new envelope should subscribe via the WS.
            val jsonSummary = Json.encodeToString(TraceSummary.serializer(), summary)

            val connections = TraceServerRegistry.connectionsFor(tenant)
            for(session in connections)
            {
                TraceServerRegistry.broadcastScope.launch {
                    try
                    {
                        session.send(Frame.Text(jsonSummary))
                    } catch (e: Exception)
                    {
                        runCatching { session.close() }
                    }
                }
            }

            call.respond(HttpStatusCode.OK)
        }

        }

        rateLimit(RateLimitName("writes-per-ip")) {
        post("/api/traces/{id}/events") {
            val auth = call.request.headers["Authorization"]
            val isAuthorized = TraceServerRegistry.agentAuthMechanism?.invoke(auth)
                ?: P2PRegistry.globalAuthMechanism?.invoke(auth ?: "")
                ?: true
            if(!isAuthorized)
            {
                authFailuresCounter("agent").increment()
                call.respond(
                    HttpStatusCode.Unauthorized,
                    ErrorEnvelope(error = "unauthorized", message = "Unauthorized Agent")
                )
                return@post
            }
            val tenant = call.resolveTenant(defaultTenant)
            if(rateLimitConfig.enabled) {
                try {
                    call.enforceTenantRateLimit("tenant:" + tenant, rateLimitConfig.perTenantWrites)
                } catch (e: RateLimitExceededException) {
                    call.respond(
                        HttpStatusCode.TooManyRequests,
                        ErrorEnvelope(error = "rate_limited", message = "Tenant write rate limit exceeded; retry later.")
                    )
                    return@post
                }
            }
            val id = call.parameters["id"] ?: ""
            val raw = try { call.receiveText() } catch (e: Exception)
            {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorEnvelope(error = "bad_request", message = "Could not read request body")
                )
                return@post
            }
            if(raw.length > maxPayloadBytes)
            {
                call.respond(
                    HttpStatusCode.PayloadTooLarge,
                    ErrorEnvelope(error = "payload_too_large", message = "Request body exceeds $maxPayloadBytes bytes")
                )
                return@post
            }
            val event = try
            {
                val parsed = TraceServerJson.parseToJsonElement(raw)
                val ts = System.currentTimeMillis()
                // We accept either {type, payload} (minimal) or the full
                // TraceEvent shape (with eventId + ts). Stamp ts always; the
                // eventId defaults to a fresh UUID if the client omitted it.
                val typeNode = (parsed as? kotlinx.serialization.json.JsonObject)?.get("type")
                val payloadNode = (parsed as? kotlinx.serialization.json.JsonObject)?.get("payload") ?: parsed
                val eventIdNode = (parsed as? kotlinx.serialization.json.JsonObject)?.get("eventId")?.toString()?.trim('"')
                    ?: java.util.UUID.randomUUID().toString()
                val tsNode = (parsed as? kotlinx.serialization.json.JsonObject)?.get("ts")?.toString()?.toLongOrNull() ?: ts
                val type = (typeNode?.toString()?.trim('"')) ?: "generic"
                TraceEvent(eventId = eventIdNode, ts = tsNode, type = type, payload = payloadNode)
            } catch (e: Exception)
            {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorEnvelope(error = "bad_request", message = "Invalid event JSON: ${e.message}")
                )
                return@post
            }
            // Stamped `ts` always reflects the server clock to keep the
            // dashboard timeline consistent across agents.
            val stamped = event.copy(ts = System.currentTimeMillis())
            TraceServerRegistry.store.appendEvent(id, stamped, tenant)
            eventsStreamedCounter(tenant).increment()
            val envelope = WebSocketEnvelope.Event(
                pipelineId = id,
                eventId = stamped.eventId,
                ts = stamped.ts,
                type = stamped.type,
                payload = stamped.payload
            )
            val jsonFrame = Json.encodeToString(WebSocketEnvelope.serializer(), envelope)
            for(session in TraceServerRegistry.subscribersFor(tenant, id))
            {
                TraceServerRegistry.broadcastScope.launch {
                    try
                    {
                        session.send(Frame.Text(jsonFrame))
                    } catch (e: Exception)
                    {
                        runCatching { session.close() }
                    }
                }
            }
            call.respond(HttpStatusCode.Accepted)
        }

        }

        webSocket("/ws/traces") {
            // Validate connection query parameter for session token if auth is enabled
            val token = call.request.queryParameters["token"]
            val tenant = call.request.queryParameters["tenant"] ?: call.request.headers["X-Tenant"] ?: defaultTenant
            if(TraceServerRegistry.clientAuthMechanism != null && !TraceServerRegistry.validateSession(token, tenant))
            {
                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Unauthorized"))
                return@webSocket
            }

            TraceServerRegistry.addConnection(this, tenant)
            wsConnectionsGauge(tenant).incrementAndGet()
            try
            {
                for(frame in incoming)
                {
                    if(frame !is Frame.Text) continue
                    val text = frame.readText()
                    val envelope = try
                    {
                        Json.decodeFromString(WebSocketEnvelope.serializer(), text)
                    } catch (e: Exception)
                    {
                        // Unknown / malformed frame; send an error envelope
                        // back so the client can log the bad op.
                        val err = WebSocketEnvelope.ErrorMsg("malformed envelope: ${e.message}")
                        send(Frame.Text(Json.encodeToString(WebSocketEnvelope.serializer(), err)))
                        continue
                    }
                    when(envelope)
                    {
                        is WebSocketEnvelope.Subscribe ->
                        {
                            TraceServerRegistry.subscribe(this, envelope.pipelineId, tenant)
                            val ack = WebSocketEnvelope.Ack(op = "subscribe", pipelineId = envelope.pipelineId)
                            send(Frame.Text(Json.encodeToString(WebSocketEnvelope.serializer(), ack)))
                        }
                        is WebSocketEnvelope.Unsubscribe ->
                        {
                            TraceServerRegistry.unsubscribe(this, envelope.pipelineId, tenant)
                            val ack = WebSocketEnvelope.Ack(op = "unsubscribe", pipelineId = envelope.pipelineId)
                            send(Frame.Text(Json.encodeToString(WebSocketEnvelope.serializer(), ack)))
                        }
                        is WebSocketEnvelope.Summary,
                        is WebSocketEnvelope.Event,
                        is WebSocketEnvelope.Ack,
                        is WebSocketEnvelope.ErrorMsg -> {
                            // Server -> client only; ignore.
                        }
                    }
                }
            } catch (e: ClosedReceiveChannelException)
            {
                // Connection closed
            } catch (e: Throwable)
            {
                e.printStackTrace()
            } finally
            {
                wsConnectionsGauge(tenant).decrementAndGet()
                TraceServerRegistry.removeConnection(this, tenant)
            }
        }
    }
}

/**
 * Best-effort per-tenant rate limit. The Ktor `RateLimit` plugin is keyed on
 * the request, so we approximate the per-tenant bucket with a simple sliding
 * counter kept on the registry. The window is the same as the per-IP window
 * so operators get consistent backpressure across both buckets.
 */
private fun ApplicationCall.enforceTenantRateLimit(tenantKey: String, capacity: Int)
{
    val now = System.currentTimeMillis()
    val windowMs = TraceServerRegistry.authConfig.accessTokenTtl.inWholeMilliseconds.coerceAtLeast(60_000L)
    val bucket = TraceServerRegistry.tenantRateBucket(tenantKey)
    synchronized(bucket) {
        while(bucket.isNotEmpty() && now - bucket.first() > windowMs) bucket.removeAt(0)
        if(bucket.size >= capacity)
        {
            throw RateLimitExceededException(tenantKey)
        }
        bucket.add(now)
    }
}

/**
 * Thrown by [enforceTenantRateLimit]. Caught by the route handler to emit
 * a 429 with the v2 error envelope.
 */
class RateLimitExceededException(val bucket: String) : RuntimeException("rate limit exceeded for $bucket")

/**
 * Total count across every tenant partition. Used by `/api/health` legacy
 * path. v2 `/api/health` uses [TraceStore.tenantNames] for a real count.
 */
private fun tenantTotalCount(store: com.TTT.TraceServer.store.TraceStore): Int
{
    return store.count(DEFAULT_TENANT)
}
