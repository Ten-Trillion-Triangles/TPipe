package com.TTT.Enums

/**
 * Selects how PumpStation history is transported in [com.TTT.Pipe.MultimodalContent].
 *
 * [TextOnly] is the default because ordinary provider Pipes consume `content.text`.
 */
enum class PumpStationHistoryTransport
{
    /** Serialize history into provider-facing text only. */
    TextOnly,

    /** Attach history to structured context only. */
    ContextOnly,

    /** Preserve both text and structured-context representations. */
    TextAndContext
}
