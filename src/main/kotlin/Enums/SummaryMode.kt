package com.TTT.Enums

/**
 * Controls how the Manifold's summary pipeline output is accumulated across loop iterations.
 */
enum class SummaryMode {
    /**
     * The summary pipeline outputs the latest event content only.
     * The Manifold appends this output to the running summary string.
     */
    APPEND,

    /**
     * The summary pipeline receives both the prior summary and the latest event.
     * The Manifold replaces the running summary with the pipeline's output.
     * This enables condensation-style summarization where each iteration
     * produces a fresh consolidated summary from the prior summary + new event.
     */
    REGENERATE
}
