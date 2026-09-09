package com.TTT.Context.Persistence

import com.TTT.Context.ContextAccessDeniedException
import com.TTT.Context.ContextAccessDenialReason
import com.TTT.Context.ContextAccessOperation
import com.TTT.Context.ContextWindow
import com.TTT.Context.ContextResourceKind
import com.TTT.Context.ContextResourceMetadata
import com.TTT.Context.LockRequest
import com.TTT.Context.LoreBookQueryResult
import com.TTT.Context.MemoryClient
import com.TTT.Context.MemoryErrorType
import com.TTT.Context.MemoryOperationResult
import com.TTT.Context.MemoryRemoteException
import com.TTT.Context.TodoList
import com.TTT.Context.requireValue
import com.TTT.Context.requireSuccess

/**
 * Adapter that preserves the existing HTTP MemoryClient protocol behind the
 * provider-neutral persistence interfaces.
 */
class TPipeRemotePersistenceBackend :
    ContextPersistenceBackend,
    ContextLockBackend,
    ContextQueryBackend,
    ContextResourceMetadataBackend {

    override val id: String = "tpipe-remote-http"

    override suspend fun getContextWindow(key: String): ContextWindow? =
        when(val operationResult = MemoryClient.getContextWindow(key))
        {
            is MemoryOperationResult.Success -> operationResult.value
            is MemoryOperationResult.Failure -> operationResult.valueOrNullOrThrow("fetch remote context window '$key'")
        }

    override suspend fun putContextWindow(key: String, window: ContextWindow)
    {
        MemoryClient.emplaceContextWindow(key, window).requireValue("store remote context window '$key'")
    }

    override suspend fun deleteContextWindow(key: String): Boolean =
        when(val operationResult = MemoryClient.deleteContextWindow(key))
        {
            is MemoryOperationResult.Success -> true
            is MemoryOperationResult.Failure -> operationResult.booleanNotFoundOrThrow("delete remote context window '$key'")
        }

    override suspend fun listContextWindowKeys(): List<String> =
        MemoryClient.getPageKeys().requireValue("list remote context keys")

    override suspend fun getTodoList(key: String): TodoList? =
        when(val operationResult = MemoryClient.getTodoList(key))
        {
            is MemoryOperationResult.Success -> operationResult.value
            is MemoryOperationResult.Failure -> operationResult.valueOrNullOrThrow("fetch remote todo list '$key'")
        }

    override suspend fun putTodoList(key: String, todoList: TodoList)
    {
        MemoryClient.emplaceTodoList(key, todoList).requireValue("store remote todo list '$key'")
    }

    override suspend fun deleteTodoList(key: String): Boolean =
        when(val operationResult = MemoryClient.deleteTodoList(key))
        {
            is MemoryOperationResult.Success -> true
            is MemoryOperationResult.Failure -> operationResult.booleanNotFoundOrThrow("delete remote todo list '$key'")
        }

    override suspend fun listTodoListKeys(): List<String> =
        MemoryClient.getTodoListKeys().requireValue("list remote todo keys")

    /**
     * Retrieve authorization metadata from the remote memory server.
     *
     * @param kind [ContextResourceKind] whose metadata is requested.
     * @param key Remote ContextBank storage key.
     * @return Resource metadata, or `null` when the sidecar is absent.
     */
    override suspend fun getResourceMetadata(
        kind: ContextResourceKind,
        key: String
    ): ContextResourceMetadata? = when(val operationResult = MemoryClient.getResourceMetadata(kind, key))
    {
        is MemoryOperationResult.Success -> operationResult.value
        is MemoryOperationResult.Failure ->
        {
            if(operationResult.error.errorType == MemoryErrorType.notFound)
            {
                null
            }
            else
            {
                throw ContextAccessDeniedException(
                    ContextAccessOperation.READ,
                    kind,
                    key,
                    if(operationResult.error.errorType == MemoryErrorType.serialization)
                    {
                        ContextAccessDenialReason.INVALID_METADATA
                    }
                    else
                    {
                        ContextAccessDenialReason.METADATA_UNAVAILABLE
                    }
                )
            }
        }
    }

    /**
     * Persist authorization metadata through the remote memory server.
     *
     * @param kind [ContextResourceKind] whose metadata is being written.
     * @param key Remote ContextBank storage key.
     * @param metadata [ContextResourceMetadata] to persist.
     */
    override suspend fun putResourceMetadata(
        kind: ContextResourceKind,
        key: String,
        metadata: ContextResourceMetadata
    )
    {
        MemoryClient.putResourceMetadata(kind, key, metadata).requireSuccess("store remote resource metadata '$key'")
    }

    /**
     * Delete authorization metadata through the remote memory server.
     *
     * @param kind [ContextResourceKind] whose metadata is being deleted.
     * @param key Remote ContextBank storage key.
     * @return `true` when the sidecar existed and was deleted.
     */
    override suspend fun deleteResourceMetadata(kind: ContextResourceKind, key: String): Boolean =
        when(val operationResult = MemoryClient.deleteResourceMetadata(kind, key))
        {
            is MemoryOperationResult.Success -> true
            is MemoryOperationResult.Failure -> operationResult.booleanNotFoundOrThrow("delete remote resource metadata '$key'")
        }

    /**
     * Check remote resource presence without retrieving its protected value.
     *
     * @param kind [ContextResourceKind] whose presence is being checked.
     * @param key Remote ContextBank storage key.
     * @return `true` when the resource payload exists.
     */
    override suspend fun resourceExists(kind: ContextResourceKind, key: String): Boolean =
        when(val operationResult = MemoryClient.resourceExists(kind, key))
        {
            is MemoryOperationResult.Success -> operationResult.value
            is MemoryOperationResult.Failure -> throw ContextAccessDeniedException(
                ContextAccessOperation.READ,
                kind,
                key,
                if(operationResult.error.errorType == MemoryErrorType.serialization)
                {
                    ContextAccessDenialReason.INVALID_METADATA
                }
                else
                {
                    ContextAccessDenialReason.METADATA_UNAVAILABLE
                }
            )
        }

    override suspend fun getLockKeys(): Set<String> =
        MemoryClient.getLockKeys().requireValue("list remote lock keys")

    override suspend fun isKeyLocked(key: String): Boolean =
        MemoryClient.isKeyLocked(key).requireValue("check remote key lock '$key'")

    override suspend fun isPageLocked(pageKey: String): Boolean =
        MemoryClient.isPageLocked(pageKey).requireValue("check remote page lock '$pageKey'")

    override suspend fun addLock(request: LockRequest)
    {
        MemoryClient.addLock(request).requireSuccess("add remote lock '${request.key}'")
    }

    override suspend fun removeLock(key: String): Boolean =
        when(val operationResult = MemoryClient.removeLock(key))
        {
            is MemoryOperationResult.Success -> true
            is MemoryOperationResult.Failure -> operationResult.booleanNotFoundOrThrow("remove remote lock '$key'")
        }

    override suspend fun updateLockState(key: String, lockState: Boolean): Boolean =
        when(val operationResult = MemoryClient.updateLockState(key, lockState))
        {
            is MemoryOperationResult.Success -> true
            is MemoryOperationResult.Failure -> operationResult.booleanNotFoundOrThrow("update remote lock '$key'")
        }

    override suspend fun queryLorebook(
        key: String,
        query: String,
        minWeight: Int,
        requiredKeys: List<String>,
        aliasKeys: List<String>,
        extractRegex: String
    ): List<LoreBookQueryResult> = MemoryClient.queryLorebook(
        key,
        query,
        minWeight,
        requiredKeys,
        aliasKeys,
        extractRegex
    ).requireValue("query remote lorebook '$key'")

    override suspend fun simulateLorebookTrigger(key: String, text: String): List<String> =
        MemoryClient.simulateLorebookTrigger(key, text).requireValue("simulate remote lorebook trigger '$key'")

    private fun MemoryOperationResult.Failure.valueOrNullOrThrow(operation: String): Nothing?
    {
        if(error.errorType == MemoryErrorType.notFound) return null
        throw MemoryRemoteException(operation, this)
    }

    private fun MemoryOperationResult.Failure.booleanNotFoundOrThrow(operation: String): Boolean
    {
        if(error.errorType == MemoryErrorType.notFound) return false
        throw MemoryRemoteException(operation, this)
    }
}
