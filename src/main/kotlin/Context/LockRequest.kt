package com.TTT.Context

import kotlinx.serialization.Serializable

/**
 * Request object for remote locking operations.
 *
 * @param key The lorebook key or bundle identifier.
 * @param pageKeys Comma-separated list of page keys.
 * @param isPageKey True if locking a page rather than a lorebook entry.
 * @param lockState Whether to lock or unlock.
 */
@Serializable
data class LockRequest(
    val key: String,
    val pageKeys: String = "",
    val isPageKey: Boolean = false,
    val lockState: Boolean = true
)
