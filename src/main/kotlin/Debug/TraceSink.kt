package com.TTT.Debug

/**
 * Fast, non-suspending observer for TPipe trace events.
 *
 * Sinks must perform only bounded work in this callback. Asynchronous
 * exporters should enqueue the event and return immediately.
 */
fun interface TraceSink {
    /** Receive one event after it has been added to the local trace history. */
    fun onEvent(traceId: String, event: TraceEvent)
}
