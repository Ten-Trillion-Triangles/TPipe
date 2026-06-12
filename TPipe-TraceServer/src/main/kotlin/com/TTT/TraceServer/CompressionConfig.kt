package com.TTT.TraceServer

/**
 * v2 response-compression configuration.
 *
 * @property enabled when `false` the Ktor `Compression` plugin is not
 *  installed. Default `true`.
 * @property minSize minimum response body size in bytes before compression
 *  is applied. Below this threshold the response is sent uncompressed.
 *  Default 1024 bytes (matches Ktor's default).
 * @property gzip when `true` `Content-Encoding: gzip` is offered to clients.
 *  Default `true`.
 * @property deflate when `true` `Content-Encoding: deflate` is offered.
 *  Default `true`.
 */
data class CompressionConfig(
    val enabled: Boolean = true,
    val minSize: Long = 1024L,
    val gzip: Boolean = true,
    val deflate: Boolean = true
)
