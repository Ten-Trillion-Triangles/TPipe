package com.TTT.TraceServer

/**
 * CORS allowlist configuration for the TraceServer HTTP surface.
 *
 * Wide-open CORS (`*`) is intentionally **not** the default: explicit
 * `allowedHosts` must be supplied to open the API to all origins. This avoids
 * surprising deployments where a developer forgets to lock the dashboard down.
 *
 * @property allowedHosts host strings the browser may embed the dashboard in.
 *  Use `listOf("*")` to explicitly opt into the wide-open behavior.
 * @property allowedMethods HTTP methods allowed for cross-origin requests.
 * @property allowedHeaders request headers allowed for cross-origin requests.
 * @property allowCredentials whether the browser may send credentials with the
 *  cross-origin request. The Ktor CORS plugin will reject `true` combined with
 *  a wildcard host list, mirroring browser behavior.
 */
data class CorsConfig(
    val allowedHosts: List<String> = listOf("localhost", "127.0.0.1"),
    val allowedMethods: List<String> = listOf("GET", "POST", "DELETE", "OPTIONS"),
    val allowedHeaders: List<String> = listOf("Authorization", "Content-Type", "X-Tenant"),
    val allowCredentials: Boolean = false
)
{
    /**
     * Returns `true` when the configuration opts into the wide-open wildcard
     * behavior. The HTTP layer will translate this into the Ktor CORS plugin's
     * `anyHost()` call.
     */
    fun isWildcard(): Boolean = allowedHosts.size == 1 && allowedHosts[0] == "*"
}
