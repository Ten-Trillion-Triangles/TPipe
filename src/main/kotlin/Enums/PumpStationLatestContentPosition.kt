package com.TTT.Enums

/**
 * Selects where PumpStation places the latest prior agent output in dispatch text.
 *
 * [Suffix] is the default because it preserves the stable prompt prefix.
 */
enum class PumpStationLatestContentPosition
{
    /** Place the latest-output block before all other dispatch text. */
    Prefix,

    /** Place the latest-output block immediately before the history block. */
    BeforeHistory,

    /** Place the latest-output block immediately after the history block. */
    AfterHistory,

    /** Place the latest-output block after all other dispatch text. */
    Suffix
}
