package com.TTT.Enums

/**
 * Identifies which harness memory subsystem a proposed write targets.
 *
 * @see com.TTT.Pipeline.MemoryWriteProposal
 */
enum class MemoryWriteKind
{
    /** A proposed mutation of the harness lorebook map. */
    LOREBOOK,

    /** A proposed replacement of the harness turn summary. */
    SUMMARY
}
