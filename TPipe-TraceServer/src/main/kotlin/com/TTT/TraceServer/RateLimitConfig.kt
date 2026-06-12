package com.TTT.TraceServer

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * Rate-limit configuration for the v2 TraceServer.
 *
 * Two independent buckets are applied at the route level: per-IP for the
 * write paths (`POST /api/traces`, `POST /api/auth/login`,
 * `POST /api/traces/{id}/events`) and per-tenant for the same write paths
 * (so one noisy tenant cannot starve another). Read paths are not rate
 * limited in v2; the dashboard is expected to be a trusted caller.
 *
 * @property enabled when `false` the Ktor `RateLimit` plugin is not
 *  installed. Default `true`.
 * @property perIpWrites max number of write requests per IP per [window].
 *  Default 60.
 * @property perTenantWrites max number of write requests per tenant per
 *  [window]. Default 600.
 * @property window the rolling time window. Default 1 minute.
 */
data class RateLimitConfig(
    val enabled: Boolean = true,
    val perIpWrites: Int = 60,
    val perTenantWrites: Int = 600,
    val window: Duration = 1.minutes
)
