package com.TTT.Context.Persistence

import com.TTT.Context.ContextResourceKind
import com.TTT.Context.ContextResourceMetadata

/**
 * Optional persistence capability for ContextBank authorization sidecars.
 *
 * This is deliberately separate from [ContextPersistenceBackend] so existing
 * persistence implementations do not acquire new abstract methods.
 */
interface ContextResourceMetadataBackend
{
    /**
     * Retrieve metadata, or `null` when the resource is not enrolled.
     *
     * @param kind [ContextResourceKind] whose metadata is requested.
     * @param key ContextBank storage key.
     * @return Resource metadata, or `null` when no sidecar exists.
     */
    suspend fun getResourceMetadata(kind: ContextResourceKind, key: String): ContextResourceMetadata?

    /**
     * Persist metadata for a resource.
     *
     * @param kind Resource kind whose metadata is being written.
     * @param key ContextBank storage key.
     * @param metadata [ContextResourceMetadata] to persist.
     */
    suspend fun putResourceMetadata(kind: ContextResourceKind, key: String, metadata: ContextResourceMetadata)

    /**
     * Delete metadata, returning whether a sidecar existed.
     *
     * @param kind [ContextResourceKind] whose metadata is being deleted.
     * @param key ContextBank storage key.
     * @return `true` when a sidecar existed and was deleted.
     */
    suspend fun deleteResourceMetadata(kind: ContextResourceKind, key: String): Boolean

    /**
     * Report whether the enrolled resource currently has a value.
     *
     * This is an optional metadata-plane query used to distinguish CREATE from
     * WRITE without retrieving the protected value. Implementations that
     * cannot answer it return `null`; ContextBank then fails closed with a
     * metadata-unavailable denial.
     *
     * @param kind [ContextResourceKind] whose presence is being checked.
     * @param key ContextBank storage key.
     * @return `true` or `false` when presence is known, otherwise `null`.
     */
    suspend fun resourceExists(kind: ContextResourceKind, key: String): Boolean? = null
}
