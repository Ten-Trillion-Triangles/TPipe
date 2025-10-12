package com.TTT.Enums

/**
 * Denotes how context window truncation is handled.
 */
enum class ContextWindowSettings {

    /** Chop off any tokens from the beginning of the prompt */
    TruncateTop,

    /** Chop off any tokens from the end of the prompt */
    TruncateBottom,

    /** Chop off tokens from the bottom and top of the prompt divided evenly */
    TruncateMiddle
}