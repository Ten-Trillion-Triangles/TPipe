package com.TTT.TraceServer

import com.TTT.TraceServer.store.DEFAULT_TENANT
import com.TTT.TraceServer.store.FileBackedTraceStore
import com.TTT.TraceServer.store.InMemoryTraceStore
import com.TTT.TraceServer.store.TraceFilter
import com.TTT.TraceServer.store.TraceListResult
import com.TTT.TraceServer.store.TraceStore
import io.ktor.websocket.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.Serializable
import java.nio.file.Paths
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Lightweight summary of a single trace payload. The `timestamp` field is
 * captured at insertion time by the [TraceStore] (not at list time) so the
 * list is sorted by a stable, meaningful value.
 *
 * v2 wire: `kind` is an optional discriminator (`"pumpstation"`, etc.) so the
 * dashboard can group / color / sort traces by source component. Default
 * `null` keeps the wire shape backward compatible with v1 clients and servers
 * — when omitted from JSON, `kind` decodes to `null` and re-encodes are also
 * omitted thanks to `explicitNulls = false` on [TraceServerJson].
 */
@Serializable
data class TraceSummary(
    val id: String,
    val timestamp: Long,
    val name: String,
    val status: String,
    val kind: String? = null, // v2 wire; v1 clients/servers send no field
)
{
}

/**
 * Full trace payload as submitted by an agent. Tenant resolution happens at
 * the HTTP layer; the payload itself does not carry a tenant field to keep
 * the on-the-wire JSON shape stable for existing `RemoteTraceDispatcher`
 * clients.
 *
 * @property tags free-form `key -> value` map supplied by the agent for
 *  filterable search. The v2 server stores tags per-trace and exposes a
 *  `?tag=key:value` filter on `GET /api/traces`. The default empty map
 *  keeps the wire shape backward compatible with v1 clients and servers.
 *
 * @property kind optional discriminator (`"pumpstation"`, etc.) carrying the
 *  originating component through to the dashboard. Default `null` preserves
 *  the v1 wire shape — when omitted from JSON, `kind` decodes to `null` and
 *  re-encodes are also omitted thanks to `explicitNulls = false` on
 *  [TraceServerJson].
 */
@Serializable
data class TracePayload(
    val pipelineId: String,
    val htmlContent: String,
    val name: String,
    val status: String,
    val tags: Map<String, String> = emptyMap(),
    val kind: String? = null, // v2 wire
)
{
}

enum class AuthMode {
    KEY,
    CREDENTIALS,
    BOTH
}

@Serializable
data class AuthRequest(
    val key: String? = null,
    val username: String? = null,
    val password: String? = null
)
{
}

@Serializable
data class AuthResponse(val token: String, val refreshToken: String? = null, val expiresInMs: Long? = null)
{
}

@Serializable
data class RefreshRequest(val refreshToken: String? = null)
{
}

@Serializable
data class RefreshResponse(val token: String, val refreshToken: String, val expiresInMs: Long)
{
}

/**
 * Error envelope returned for every non-2xx response. `code` is a stable
 * machine identifier (e.g. `bad_request`, `unauthorized`, `payload_too_large`,
 * `internal_error`); `message` is a human-readable explanation safe to show to
 * dashboard users.
 */
@Serializable
data class ErrorEnvelope(val error: String, val message: String)
{
}

@Serializable
data class HealthTracesInfo(val total: Int, val tenants: Int)
{
}

/**
 * v2 health envelope. Adds the `store` and `metricsEnabled` blocks so
 * operators can confirm the persistence and observability configuration
 * from a single probe.
 */
@Serializable
data class HealthStoreInfo(
    val type: String,
    val directory: String,
    val maxTraces: Int,
    val ttlMs: Long? = null,
    val perTenantQuota: Int? = null
)
{
}

@Serializable
data class HealthEnvelope(
    val status: String,
    val uptimeMs: Long,
    val traces: HealthTracesInfo,
    val version: String,
    val store: HealthStoreInfo? = null,
    val metricsEnabled: Boolean = false
)
{
}

@Serializable
data class AuthConfigEnvelope(val mode: String)
{
}

@Serializable
data class TraceListEnvelope(
    val items: List<TraceSummary>,
    val total: Int,
    val limit: Int,
    val offset: Int
)
{
}

/**
 * Single source of truth for the server's pluggable collaborators and
 * tenant-scoped state.
 *
 * Backward-compat surface: `clientSessions`, `traces`, and `registerTrace(...)`
 * remain on the registry so the existing demo and tests keep working without
 * source changes. They now delegate to the pluggable [store] and tenant
 * partitions.
 */
object TraceServerRegistry {
    /**
     * Authentication mechanism for Agents (RemoteTraceDispatcher).
     * Validates the Authorization header on POST /api/traces.
     */
    var agentAuthMechanism: (suspend (authHeader: String?) -> Boolean)? = null

    /**
     * Configures which authentication UI is displayed by the client dashboard.
     */
    var authMode: AuthMode = AuthMode.KEY

    /**
     * Authentication mechanism for Human Clients (Dashboard).
     * Validates the login payload as a JSON string and returns true if authorized.
     * The lambda is invoked with the `key` field from the [AuthRequest] for
     * backward compat with v1 wiring. New deployments should prefer
     * [authConfig] + [authConfig]'s `expectedHash` so the password never
     * crosses the process boundary in cleartext form.
     */
    var clientAuthMechanism: (suspend (requestJson: String) -> Boolean)? = null

    /**
     * v2 auth configuration. When `authConfig.passwordHasherEnabled = true`
     * AND `authConfig.expectedHash != null`, `POST /api/auth/login` verifies
     * the `key` field with the configured [com.TTT.TraceServer.auth.PasswordHasher]
     * (default: PBKDF2-HMAC-SHA256). When `expectedHash` is `null` the
     * legacy [clientAuthMechanism] lambda path is used, regardless of the
     * `passwordHasherEnabled` flag.
     */
    var authConfig: AuthConfig = AuthConfig()

    /**
     * The active [com.TTT.TraceServer.auth.PasswordHasher]. Override to plug
     * a different KDF in. Set via [setPasswordHasher] rather than the public
     * `var` to make the configuration flow obvious to callers.
     */
    /**
     * The active [com.TTT.TraceServer.auth.PasswordHasher]. Override via
     * [setPasswordHasher] to plug a different KDF in. The default is a
     * PBKDF2-HMAC-SHA256 hasher with 600 000 iterations; this is what
     *  consults when [authConfig].expectedHash is set.
     */
    var passwordHasher: com.TTT.TraceServer.auth.PasswordHasher =
        com.TTT.TraceServer.auth.Pbkdf2PasswordHasher()
        private set

    /**
     * Installs a custom [com.TTT.TraceServer.auth.PasswordHasher]. The default
     * when [authConfig.expectedHash] is set is PBKDF2-HMAC-SHA256 with 600 000
     * iterations.
     */
    fun setPasswordHasher(hasher: com.TTT.TraceServer.auth.PasswordHasher)
    {
        passwordHasher = hasher
    }

    /**
     * Pluggable persistence layer. Initialized to a [FileBackedTraceStore]
     * under `~/.TPipe-Debug/trace-server/` by default. Integrators may swap
     * this before [startTraceServer] is called to inject a custom store.
     */
    var store: TraceStore = FileBackedTraceStore(
        Paths.get(System.getProperty("user.home"), ".TPipe-Debug", "trace-server")
    )
        private set

    /**
     * Active WebSocket connections, partitioned by tenant. The inner set's key
     * is the WebSocket session identity so duplicate registrations are
     * de-duplicated.
     */
    private val connectionsByTenant: MutableMap<String, MutableSet<WebSocketSession>> = ConcurrentHashMap()

    /**
     * Per-WebSocket pipeline subscriptions, partitioned by tenant. The set
     * members are the WebSocket sessions that have subscribed to a given
     * `(tenant, pipelineId)`. Used to fan out live events from
     * `POST /api/traces/{id}/events` to interested dashboards.
     */
    private val subscriptionsByTenant: MutableMap<String, MutableMap<String, MutableSet<WebSocketSession>>> =
        ConcurrentHashMap()

    /**
     * Active client sessions, partitioned by tenant. The default tenant
     * partition is used by the historical single-tenant integrations.
     */
    private val clientSessionsByTenant: MutableMap<String, MutableMap<String, ClientSession>> =
        ConcurrentHashMap()

    /**
     * Coroutine scope used to fan out WebSocket broadcasts off the request
     * thread. Replaces the previous `GlobalScope.launch` in the trace
     * broadcast path. The scope is cancelled by [stopTraceServer].
     */
    val broadcastScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Records of in-flight engine references so [stopTraceServer] can locate
     * the registry that owns the engine and tear it down in one call. We use
     * an `IdentityHashMap`-backed set so engine equality is by reference, not
     * by equals().
     */
    private val engines: MutableSet<io.ktor.server.engine.EmbeddedServer<*, *>> =
        java.util.Collections.newSetFromMap(java.util.IdentityHashMap())

    /**
     * v2 session entry. Carries the tenant, expiry, and (for refresh tokens)
     * the parent access token's id so a refresh rotation can invalidate the
     * old pair.
     */
    data class ClientSession(
        val tenant: String,
        val expiresAt: Long,
        val kind: Kind,
        val parentToken: String? = null
    )
    {
        enum class Kind { ACCESS, REFRESH }
    }

    /**
     * Configures the persistence backend. Call this before [startTraceServer]
     * to swap in a different store. Subsequent calls close the previous store
     * so the underlying file handles are released.
     */
    fun configureStore(newStore: TraceStore)
    {
        val previous = store
        store = newStore
        if(previous !== newStore)
        {
            runCatching { previous.close() }
        }
    }

    /**
     * Backward-compat alias for [configureStore] that constructs an
     * [InMemoryTraceStore]. Matches the spirit of the historical
     * `TraceServerRegistry.traces` access pattern.
     */
    fun useInMemoryStore(maxTraces: Int = 10_000)
    {
        configureStore(InMemoryTraceStore(maxTraces))
    }

    /**
     * Legacy alias for [store]. Mutating the returned map is not supported
     * and throws - the registry only exposes the pluggable [TraceStore]
     * surface now.
     */
    @Deprecated("Use the TraceStore API instead of the raw map.", ReplaceWith("store"))
    val traces: Unit
        get() = throw UnsupportedOperationException(
            "TraceServerRegistry.traces has been replaced by TraceStore. " +
                "Use TraceServerRegistry.store.put/get/listSummaries instead."
        )

    /**
     * Backward-compat: register a trace under the default tenant. Existing
     * demo code uses this; new code should call [store].put directly.
     */
    fun registerTrace(payload: TracePayload)
    {
        store.put(payload, DEFAULT_TENANT)
    }

    /**
     * Backward-compat: list summaries under the default tenant. Kept as a
     * thin wrapper over [store] for the demo and any pre-existing callers.
     */
    fun getAllSummaries(): List<TraceSummary>
    {
        return store.listSummaries(TraceFilter(tenant = DEFAULT_TENANT, limit = InMemoryTraceStore.MAX_LIMIT)).items
    }

    /**
     * Active client sessions under the default tenant, exposed as a
     * `MutableMap<String, Long>` (the v1 surface) for backward-compat with
     * the existing test suite. Mutations are forwarded to the new
     * [ClientSession]-typed map for the default tenant. New code should use
     * [sessionsFor] / [createSession] / [validateSession] instead.
     *
     * The wrapper allocates on every access; v1 code that does
     * `registry.clientSessions[token] = expiresAt` keeps working because
     * the wrapper delegates `put` to the underlying map.
     */
    val clientSessions: MutableMap<String, Long>
        get() = v1ClientSessionMap(DEFAULT_TENANT)

    private fun v1ClientSessionMap(tenant: String): MutableMap<String, Long> {
        val self = this
        return object : AbstractMutableMap<String, Long>() {
            private val backing: MutableMap<String, ClientSession> get() = self.sessionsFor(tenant)
            override val size: Int get() = backing.size
            override fun containsKey(key: String): Boolean = backing.containsKey(key)
            override fun containsValue(value: Long): Boolean = backing.values.any { it.expiresAt == value }
            override fun get(key: String): Long? = backing[key]?.expiresAt
            override val entries: MutableSet<MutableMap.MutableEntry<String, Long>>
                get() = backing.entries.map { (k, v) ->
                    object : MutableMap.MutableEntry<String, Long> {
                        override val key: String = k
                        override val value: Long = v.expiresAt
                        override fun setValue(newValue: Long): Long {
                            val previous = backing.put(k, ClientSession(tenant, newValue, ClientSession.Kind.ACCESS))
                            return previous?.expiresAt ?: newValue
                        }
                    }
                }.toMutableSet()
            override val keys: MutableSet<String> get() = backing.keys
            override val values: MutableCollection<Long> get() =
                backing.values.map { it.expiresAt }.toMutableList()
            override fun clear() { backing.clear() }
            override fun put(key: String, value: Long): Long? {
                val previous = backing.put(key, ClientSession(tenant, value, ClientSession.Kind.ACCESS))
                return previous?.expiresAt
            }
            override fun remove(key: String): Long? {
                val previous = backing.remove(key) ?: return null
                return previous.expiresAt
            }
        }
    }

    /**
     * Returns the session map for the resolved tenant. The map is created on
     * first access and lives for the lifetime of the registry.
     */
    fun sessionsFor(tenant: String): MutableMap<String, ClientSession> =
        clientSessionsByTenant.computeIfAbsent(tenant) { ConcurrentHashMap() }

    /**
     * Creates a new access-token session valid for [ttlMillis] under the
     * resolved tenant. v1 callers (which only know about access tokens)
     * continue to use this entry point.
     */
    fun createSession(tenant: String = DEFAULT_TENANT, ttlMillis: Long? = null): String
    {
        val token = UUID.randomUUID().toString()
        val ttl = ttlMillis ?: authConfig.accessTokenTtl.inWholeMilliseconds
        sessionsFor(tenant)[token] = ClientSession(tenant, System.currentTimeMillis() + ttl, ClientSession.Kind.ACCESS)
        return token
    }

    /**
     * Creates a paired access + refresh token under the resolved tenant.
     * Returns the (accessToken, refreshToken) pair. Refresh tokens are
     * tracked separately so they can be rotated without touching the
     * access-token map.
     */
    fun createSessionPair(tenant: String = DEFAULT_TENANT): Pair<String, String>
    {
        val access = createSession(tenant)
        val refresh = UUID.randomUUID().toString()
        val refreshTtl = authConfig.refreshTokenTtl.inWholeMilliseconds
        sessionsFor(tenant)[refresh] = ClientSession(
            tenant = tenant,
            expiresAt = System.currentTimeMillis() + refreshTtl,
            kind = ClientSession.Kind.REFRESH,
            parentToken = access
        )
        return access to refresh
    }

    /**
     * Validates the session token under the resolved tenant. Refresh tokens
     * are accepted by this method so the refresh route can identify them
     * before rotating; the route itself is responsible for rejecting
     * access tokens presented to the refresh endpoint.
     */
    fun validateSession(token: String?, tenant: String = DEFAULT_TENANT): Boolean
    {
        if(token.isNullOrBlank()) return false
        val session = sessionsFor(tenant)[token] ?: return false
        if(System.currentTimeMillis() > session.expiresAt)
        {
            sessionsFor(tenant).remove(token)
            return false
        }
        return true
    }

    /**
     * Returns the resolved [ClientSession] for [token], or `null` if absent.
     * Unlike [validateSession] this does not consume the entry; callers that
     * need to inspect the kind (access vs refresh) should use this directly.
     */
    fun lookupSession(token: String?, tenant: String = DEFAULT_TENANT): ClientSession?
    {
        if(token.isNullOrBlank()) return null
        return sessionsFor(tenant)[token]
    }

    /**
     * Rotates a refresh token. The previous refresh entry is removed; the
     * paired access token is also revoked so a leaked refresh token is
     * usable only once. Returns the new (access, refresh) pair.
     */
    fun rotateRefresh(refreshToken: String, tenant: String = DEFAULT_TENANT): Pair<String, String>?
    {
        val session = sessionsFor(tenant)[refreshToken] ?: return null
        if(System.currentTimeMillis() > session.expiresAt) return null
        if(session.kind != ClientSession.Kind.REFRESH) return null
        val bucket = sessionsFor(tenant)
        bucket.remove(refreshToken)
        session.parentToken?.let { bucket.remove(it) }
        return createSessionPair(tenant)
    }

    /**
     * Removes every expired session entry across all tenants. Called by the
     * background sweep; not part of the v1 surface.
     */
    fun purgeExpired()
    {
        val now = System.currentTimeMillis()
        for(bucket in clientSessionsByTenant.values)
        {
            val expired = bucket.entries.filter { it.value.expiresAt < now }
            for(entry in expired) bucket.remove(entry.key)
        }
    }

    /**
     * Registers an active WebSocket connection for tenant-scoped broadcasts.
     */
    fun addConnection(session: WebSocketSession, tenant: String = DEFAULT_TENANT)
    {
        connectionsByTenant.computeIfAbsent(tenant) { Collections.synchronizedSet(LinkedHashSet()) }.add(session)
    }

    /**
     * Removes a WebSocket connection from the tenant partition and drops any
     * pipeline subscriptions that referenced it.
     */
    fun removeConnection(session: WebSocketSession, tenant: String = DEFAULT_TENANT)
    {
        connectionsByTenant[tenant]?.remove(session)
        if(connectionsByTenant[tenant]?.isEmpty() == true)
        {
            connectionsByTenant.remove(tenant)
        }
        // Drop subscriptions that referenced this session.
        val perPipeline = subscriptionsByTenant[tenant]
        if(perPipeline != null)
        {
            for((pipelineId, subs) in perPipeline)
            {
                if(subs.remove(session) && subs.isEmpty())
                {
                    perPipeline.remove(pipelineId)
                }
            }
            if(perPipeline.isEmpty()) subscriptionsByTenant.remove(tenant)
        }
    }

    /**
     * Returns the active WebSocket sessions for the resolved tenant.
     */
    fun connectionsFor(tenant: String): Set<WebSocketSession> =
        connectionsByTenant[tenant] ?: emptySet()

    /**
     * Adds a subscription for [session] to the per-pipeline broadcast bucket
     * under [tenant]. Returns `true` if the subscription was newly added
     * (i.e. the session was not already subscribed).
     */
    fun subscribe(session: WebSocketSession, pipelineId: String, tenant: String): Boolean
    {
        val perPipeline = subscriptionsByTenant.computeIfAbsent(tenant) { ConcurrentHashMap() }
        val subs = perPipeline.computeIfAbsent(pipelineId) { Collections.synchronizedSet(LinkedHashSet()) }
        return subs.add(session)
    }

    /**
     * Removes a subscription. Returns `true` if the session was previously
     * subscribed.
     */
    fun unsubscribe(session: WebSocketSession, pipelineId: String, tenant: String): Boolean
    {
        val subs = subscriptionsByTenant[tenant]?.get(pipelineId) ?: return false
        val removed = subs.remove(session)
        if(removed && subs.isEmpty())
        {
            subscriptionsByTenant[tenant]?.remove(pipelineId)
        }
        return removed
    }

    /**
     * Returns the set of WebSocket sessions subscribed to `(tenant, pipelineId)`.
     */
    fun subscribersFor(tenant: String, pipelineId: String): Set<WebSocketSession> =
        subscriptionsByTenant[tenant]?.get(pipelineId) ?: emptySet()

    /**
     * Tracked engines for [stopTraceServer]. Engines are registered when
     * [startTraceServer] boots a server and removed on stop.
     */
    internal fun registerEngine(engine: io.ktor.server.engine.EmbeddedServer<*, *>)
    {
        engines.add(engine)
    }

    internal fun unregisterEngine(engine: io.ktor.server.engine.EmbeddedServer<*, *>)
    {
        engines.remove(engine)
    }

    /**
     * v2 per-tenant rate-limit buckets. Keyed on the tenant key (e.g.
     * ). Each value is a sliding window of recent write
     * timestamps in milliseconds. The bucket is bounded by the
     * [com.TTT.TraceServer.RateLimitConfig.perTenantWrites] cap.
     */
    private val tenantRateBuckets: MutableMap<String, MutableList<Long>> = ConcurrentHashMap()

    /**
     * Returns the per-tenant rate-limit bucket for the given key, creating
     * it on first access. Used by the v2 write route to enforce the
     * per-tenant limit (alongside the per-IP Ktor  bucket).
     */
    fun tenantRateBucket(tenantKey: String): MutableList<Long> =
        tenantRateBuckets.computeIfAbsent(tenantKey) { mutableListOf() }

    /**
     * Clears every per-tenant rate-limit bucket. Tests use this between
     * cases so buckets don't accumulate across runs.
     */
    fun clearTenantRateBuckets() {
        tenantRateBuckets.values.forEach { it.clear() }
    }

    /**
     * Closes the broadcast scope and releases the store. Idempotent; safe to
     * call multiple times. The default behavior is intentionally narrow so it
     * can be invoked from tests; engine lifecycle is managed by
     * [stopTraceServer].
     */
    fun shutdownRegistry()
    {
        broadcastScope.cancel()
        runCatching { store.close() }
    }
}
