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
    /** Retrieve metadata, or null when the resource is not enrolled. */
    suspend fun getResourceMetadata(kind: ContextResourceKind, key: String): ContextResourceMetadata?

    /** Persist metadata for a resource. */
    suspend fun putResourceMetadata(kind: ContextResourceKind, key: String, metadata: ContextResourceMetadata)

    /** Delete metadata, returning whether a sidecar existed. */
    suspend fun deleteResourceMetadata(kind: ContextResourceKind, key: String): Boolean

    /**
     * Report whether the enrolled resource currently has a value.
     *
     * This is an optional metadata-plane query used to distinguish CREATE from
     * WRITE without retrieving the protected value. Implementations that
     * cannot answer it return `null`; ContextBank then fails closed with a
     * metadata-unavailable denial.
     */
    suspend fun resourceExists(kind: ContextResourceKind, key: String): Boolean? = null
}
