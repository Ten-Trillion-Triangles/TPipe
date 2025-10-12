package com.TTT.Debug

import com.TTT.Pipe.Pipe
import com.TTT.Pipeline.Pipeline

/**
 * Extension function to enable tracing on a Pipe with a fluent API.
 * @param config The tracing configuration to use
 * @return The pipe with tracing enabled
 */
fun Pipe.withTracing(config: TraceConfig = TraceConfig(enabled = true)): Pipe = this.enableTracing(config)

/**
 * Extension function to enable tracing on a Pipeline with a fluent API.
 * @param config The tracing configuration to use
 * @return The pipeline with tracing enabled
 */
fun Pipeline.withTracing(config: TraceConfig = TraceConfig(enabled = true)): Pipeline = this.enableTracing(config)