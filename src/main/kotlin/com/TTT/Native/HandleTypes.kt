package com.TTT.Native

/**
 * Handle type constants — values stored in high 8 bits of uint64_t handle.
 * These must match the handle type definitions in tpipe-abi.h.
 */
object HandleTypes {
    const val CONTENT = 1
    const val BINARY = 2
    const val PIPE = 3
    const val PIPELINE = 4
    const val CONTEXT = 5
    const val MINIBANK = 6
    const val LOREBOOK = 7
    const val CONVERSE_HISTORY = 8
    const val PCP = 9
    const val P2P = 10
    const val ASYNC = 11
    const val LIST = 12
    const val MAP = 13
    const val PIPE_SETTINGS = 14
    const val OPERATION = 15
    const val MANIFOLD = 16
    const val DISTRIBUTION_GRID = 17

    /** Base type for all handles (used in generic operations). */
    const val BASE = 0

    /** Total number of handle types. */
    const val TYPE_COUNT = 18
}