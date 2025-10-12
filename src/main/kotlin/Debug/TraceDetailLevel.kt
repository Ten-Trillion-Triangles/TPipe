package com.TTT.Debug

enum class TraceDetailLevel {
    MINIMAL,    // Only failures and major events
    NORMAL,     // Standard tracing
    VERBOSE,    // All events including metadata
    DEBUG       // Everything including internal state
}