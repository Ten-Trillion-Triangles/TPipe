package com.TTT.Enums

/**
 * Selects where prepared TPipe context is installed in the rebuilt system prompt.
 *
 * [Beginning] emphasizes primacy, [Middle] keeps context near structured prompt
 * requirements when a JSON output boundary exists (otherwise it falls back to
 * footer placement), and [Footer] emphasizes recency while preserving an
 * explicit developer footer as the final instruction block.
 */
@kotlinx.serialization.Serializable
enum class SystemContextInjectionPoint
{
    Beginning,
    Middle,
    Footer
}
