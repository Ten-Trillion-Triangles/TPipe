package com.TTT.Context

import com.TTT.Config.TPipeConfig
import com.TTT.Context.Persistence.ContextResourceMetadataBackend
import com.TTT.Util.deserialize
import com.TTT.Util.serialize
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.Serializable
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Versioned sidecar record describing the authorization identity of one
 * ContextBank resource. The ContextWindow/TodoList payload is not changed.
 *
 * @param resourceKind Kind of the protected ContextBank resource.
 * @param resourceId Opaque host-defined identity of the resource.
 * @param boundaryId Optional opaque boundary containing the resource.
 * @param schemaVersion Sidecar schema version used for strict decoding.
 */
@Serializable
data class ContextResourceMetadata(
    val resourceKind: ContextResourceKind,
    val resourceId: String,
    val boundaryId: String? = null,
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION
)
{
    init
    {
        require(resourceId.isNotBlank()) { "Context resource id must not be blank." }
        require(boundaryId == null || boundaryId.isNotBlank()) { "Context boundary id must not be blank." }
        require(schemaVersion > 0) { "Context resource metadata schema version must be positive." }
    }

    companion object
    {
        /** Current sidecar schema version. */
        const val CURRENT_SCHEMA_VERSION: Int = 1

        /**
         * Decode a metadata record without applying lenient default values.
         *
         * @param raw Serialized sidecar contents.
         * @return Decoded metadata, or `null` when the record is invalid.
         */
        internal fun decodeStrict(raw: String): ContextResourceMetadata?
        {
            return try
            {
                val jsonObject = Json.parseToJsonElement(raw).jsonObject
                val schemaVersionElement = jsonObject["schemaVersion"] ?: return null
                val schemaVersion = schemaVersionElement.jsonPrimitive
                    .takeUnless { it.isString }
                    ?.intOrNull
                    ?: return null
                if(schemaVersion <= 0) return null
                jsonObject["resourceKind"]?.jsonPrimitive?.contentOrNull ?: return null
                jsonObject["resourceId"]?.jsonPrimitive?.contentOrNull ?: return null
                deserialize<ContextResourceMetadata>(raw, useRepair = false)
            }
            catch(_: Exception)
            {
                null
            }
        }
    }
}

/**
 * Local per-resource sidecar implementation used by ContextBank.
 *
 * Sidecars are adjacent to the existing `.bank` and `.todo` files. Each key
 * has its own mutex, and MemoryPersistence supplies atomic replacement and
 * file locking for cross-process coordination.
 */
internal object LocalContextResourceMetadataStore : ContextResourceMetadataBackend
{
    private val mutexes = ConcurrentHashMap<String, Mutex>()

    /**
     * Read the sidecar for one resource.
     *
     * @param kind Resource kind whose sidecar should be read.
     * @param key ContextBank storage key.
     * @return The decoded metadata, or `null` when no sidecar exists.
     */
    override suspend fun getResourceMetadata(
        kind: ContextResourceKind,
        key: String
    ): ContextResourceMetadata?
    {
        return mutex(kind, key).withLock {
            val path = sidecarPath(kind, key)
            if(!File(path).exists()) return@withLock null
            val raw = MemoryPersistence.readMemoryFile(path)
            val metadata = ContextResourceMetadata.decodeStrict(raw) ?: throw invalidMetadata(kind, key)
            if(metadata.schemaVersion != ContextResourceMetadata.CURRENT_SCHEMA_VERSION || metadata.resourceKind != kind)
            {
                throw invalidMetadata(kind, key)
            }
            metadata
        }
    }

    /**
     * Atomically replace the sidecar for one resource.
     *
     * @param kind Resource kind whose sidecar should be written.
     * @param key ContextBank storage key.
     * @param metadata Metadata to persist.
     */
    override suspend fun putResourceMetadata(
        kind: ContextResourceKind,
        key: String,
        metadata: ContextResourceMetadata
    )
    {
        require(metadata.resourceKind == kind) { "Metadata resource kind must match the selected resource kind." }
        mutex(kind, key).withLock {
            MemoryPersistence.writeMemoryFile(sidecarPath(kind, key), serialize(metadata, encodedefault = true))
        }
    }

    /**
     * Delete the sidecar for one resource.
     *
     * @param kind Resource kind whose sidecar should be deleted.
     * @param key ContextBank storage key.
     * @return `true` when a sidecar was deleted.
     */
    override suspend fun deleteResourceMetadata(
        kind: ContextResourceKind,
        key: String
    ): Boolean
    {
        return mutex(kind, key).withLock {
            MemoryPersistence.deleteMemoryFile(sidecarPath(kind, key))
        }
    }

    /**
     * Resolve the sidecar path for tests and local administration.
     *
     * @param kind Resource kind whose sidecar path is requested.
     * @param key ContextBank storage key.
     * @return Absolute path of the resource sidecar.
     */
    internal fun sidecarPath(kind: ContextResourceKind, key: String): String
    {
        val basePath = when(kind)
        {
            ContextResourceKind.CONTEXT_WINDOW -> "${TPipeConfig.getLorebookDir()}/$key.bank"
            ContextResourceKind.TODO_LIST -> "${TPipeConfig.getTodoListDir()}/$key.todo"
            ContextResourceKind.BANKED_CONTEXT -> "${TPipeConfig.getLorebookDir()}/__banked_context.bank"
        }
        return "$basePath.access"
    }

    private fun mutex(kind: ContextResourceKind, key: String): Mutex
    {
        return mutexes.computeIfAbsent("${kind.name}:$key") { Mutex() }
    }

    private fun invalidMetadata(kind: ContextResourceKind, key: String): ContextAccessDeniedException
    {
        return ContextAccessDeniedException(
            ContextAccessOperation.READ,
            kind,
            key,
            ContextAccessDenialReason.INVALID_METADATA
        )
    }
}
