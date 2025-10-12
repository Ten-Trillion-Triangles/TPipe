package com.TTT.Debug

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