package com.TTT.Debug

/**
 * Defines configuration settings for TPipe's tracing system.
 * @param enabled Whether tracing is enabled or not.
 * @param maxHistory The maximum number of traces to store in the history.
 * @param outputFormat The format in which traces should be outputted.
 * @param detailLevel The level of detail to include in the trace output.
 * @param autoExport Whether to automatically export traces to a file.
 * @param exportPath The path where traces should be exported.
 * @param includeContext Whether to include context information in the trace output.
 * @param includeMetadata Whether to include metadata information in the trace output.
 */
data class TraceConfig(
    val enabled: Boolean = false,
    val maxHistory: Int = 1000,
    val outputFormat: TraceFormat = TraceFormat.CONSOLE,
    val detailLevel: TraceDetailLevel = TraceDetailLevel.NORMAL,
    val autoExport: Boolean = false,
    val exportPath: String = "~/.TPipe-Debug/traces/",
    val includeContext: Boolean = true,
    val includeMetadata: Boolean = true
)