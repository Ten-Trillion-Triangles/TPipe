package com.TTT.TraceServer

/**
 * v2 Prometheus metrics configuration.
 *
 * @property enabled when `true` the Ktor `MicrometerMetrics` plugin is
 *  installed and the `GET [path]` route serves the Prometheus scrape
 *  payload in the text exposition format. Default `true`.
 * @property path the path the scrape endpoint is bound to. Default
 *  `/metrics` (the de-facto Prometheus convention). Operators that want
 *  to hide the endpoint behind a path prefix can override this.
 */
data class MetricsConfig(
    val enabled: Boolean = true,
    val path: String = "/metrics"
)
