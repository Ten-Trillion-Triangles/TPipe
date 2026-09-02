package com.TTT.Context

import com.TTT.Config.TPipeConfig
import com.TTT.Context.Persistence.ContextPersistenceBackend
import com.TTT.Context.Persistence.ContextLockBackend
import com.TTT.Context.Persistence.ContextQueryBackend
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ContextPersistenceCompatibilityTest
{
    private val contextKey = "persistence-compat-context-${System.nanoTime()}"
    private val todoKey = "persistence-compat-todo-${System.nanoTime()}"
    private val globallyRemoteKey = "persistence-compat-global-${System.nanoTime()}"
    private val localPageLockKey = "persistence-compat-local-page-lock-${System.nanoTime()}"
    private val remotePageKey = "persistence-compat-remote-page-${System.nanoTime()}"
    private val remoteLorebookLockKey = "persistence-compat-remote-lorebook-${System.nanoTime()}"
    private val originalBackend = ContextBank.getRemotePersistenceBackend()
    private val originalRemoteMemoryEnabled = TPipeConfig.remoteMemoryEnabled
    private val originalUseRemoteMemoryGlobally = TPipeConfig.useRemoteMemoryGlobally

    @AfterEach
    fun cleanup()
    {
        runBlocking {
            ContextBank.deleteContextWindowSuspend(contextKey, skipRemote = true)
            ContextBank.deleteContextWindowSuspend(globallyRemoteKey, skipRemote = true)
            ContextBank.deleteTodoListSuspend(todoKey, skipRemote = true)
            ContextBank.deleteContextWindowSuspend(remotePageKey, skipRemote = true)
            ContextLock.removeLockSuspend(localPageLockKey, skipRemote = true)
            ContextLock.removeLockSuspend(remoteLorebookLockKey, skipRemote = true)
        }
        ContextBank.clearRemotePersistenceBackend()
        originalBackend?.let { ContextBank.setRemotePersistenceBackend(it) }
        TPipeConfig.remoteMemoryEnabled = originalRemoteMemoryEnabled
        TPipeConfig.useRemoteMemoryGlobally = originalUseRemoteMemoryGlobally
    }

    /** A registered backend must not reroute local queries or existence checks. */
    @Test
    fun registeredBackendDoesNotHijackLocalPages()
    {
        runBlocking {
            val backend = FakePersistenceBackend()
            val localEntry = LoreBook().apply {
                key = "local-lore"
                value = "local value"
            }
            val localWindow = ContextWindow().apply {
                loreBookKeys[localEntry.key] = localEntry
            }
            ContextBank.emplaceSuspend(
                contextKey,
                localWindow,
                StorageMode.MEMORY_ONLY,
                skipRemote = true,
                useWriteBack = false
            )
            ContextBank.emplaceTodoListSuspend(
                todoKey,
                TodoList(),
                StorageMode.MEMORY_ONLY,
                skipRemote = true
            )
            ContextBank.setRemotePersistenceBackend(backend)
            TPipeConfig.remoteMemoryEnabled = false
            TPipeConfig.useRemoteMemoryGlobally = false

            val readConfig = MemoryIntrospectionConfig(
                allowedPageKeys = mutableSetOf(contextKey),
                allowRead = true
            )
            val queryResults = MemoryIntrospection.withCoroutineScope(readConfig) {
                MemoryIntrospectionTools.queryLorebook(contextKey, query = "local")
            }
            val triggeredKeys = MemoryIntrospection.withCoroutineScope(readConfig) {
                MemoryIntrospectionTools.simulateLorebookTrigger(contextKey, "local-lore")
            }

            assertEquals(listOf("local-lore"), queryResults.map { it.entry.key })
            assertEquals(listOf("local-lore"), triggeredKeys)
            assertTrue(ContextBank.contextWindowExistsSuspend(contextKey))
            assertTrue(ContextBank.todoListExistsSuspend(todoKey))
            assertEquals(0, backend.queryCallCount)
            assertEquals(0, backend.simulateCallCount)
            assertEquals(0, backend.getContextCallCount)
            assertEquals(0, backend.getTodoCallCount)
            assertEquals(0, backend.lockPageCallCount)
            assertEquals(0, backend.listContextCallCount)
            assertEquals(0, backend.listTodoCallCount)
            assertTrue(ContextBank.getPageKeysSuspend().contains(contextKey))
            assertTrue(ContextBank.getTodoListKeysSuspend().contains(todoKey))

            ContextLock.addLockSuspend(localPageLockKey, "", isPageKey = true)
            assertTrue(ContextLock.isPageLockedSuspend(localPageLockKey))
            assertEquals(0, backend.addLockCallCount)
        }
    }

    /** Aggregate listings use explicit remote storage metadata rather than a legacy flag alone. */
    @Test
    fun remoteStorageMetadataListsRemoteKeys()
    {
        runBlocking {
            val backend = FakePersistenceBackend()
            backend.contextWindows[contextKey] = ContextWindow()
            backend.todoLists[todoKey] = TodoList()
            ContextBank.setRemotePersistenceBackend(backend)
            ContextBank.setStorageMode(contextKey, StorageMode.REMOTE)
            ContextBank.setStorageMode(todoKey, StorageMode.REMOTE)
            TPipeConfig.remoteMemoryEnabled = true
            TPipeConfig.useRemoteMemoryGlobally = false

            assertTrue(ContextBank.getPageKeysSuspend().contains(contextKey))
            assertTrue(ContextBank.getTodoListKeysSuspend().contains(todoKey))
            assertEquals(1, backend.listContextCallCount)
            assertEquals(1, backend.listTodoCallCount)
        }
    }

    /** Remote-mode pages must continue using the registered backend for queries and existence checks. */
    @Test
    fun remotePagesUseRegisteredBackend()
    {
        runBlocking {
            val backend = FakePersistenceBackend()
            backend.contextWindows[contextKey] = ContextWindow()
            backend.todoLists[todoKey] = TodoList()
            ContextBank.setRemotePersistenceBackend(backend)
            ContextBank.setStorageMode(contextKey, StorageMode.REMOTE)
            ContextBank.setStorageMode(todoKey, StorageMode.REMOTE)

            val readConfig = MemoryIntrospectionConfig(
                allowedPageKeys = mutableSetOf(contextKey),
                allowRead = true
            )
            val queryResults = MemoryIntrospection.withCoroutineScope(readConfig) {
                MemoryIntrospectionTools.queryLorebook(contextKey)
            }
            val triggeredKeys = MemoryIntrospection.withCoroutineScope(readConfig) {
                MemoryIntrospectionTools.simulateLorebookTrigger(contextKey, "remote text")
            }

            assertEquals(listOf("backend-lore"), queryResults.map { it.entry.key })
            assertEquals(listOf("backend-trigger"), triggeredKeys)
            assertTrue(ContextBank.contextWindowExistsSuspend(contextKey))
            assertTrue(ContextBank.todoListExistsSuspend(todoKey))
            assertEquals(1, backend.queryCallCount)
            assertEquals(1, backend.simulateCallCount)
            assertTrue(backend.getContextCallCount >= 1)
            assertTrue(backend.getTodoCallCount >= 1)
            assertTrue(backend.lockPageCallCount >= 1)
            assertTrue(ContextBank.getPageKeysSuspend().contains(contextKey))
            assertTrue(ContextBank.getTodoListKeysSuspend().contains(todoKey))

            backend.contextWindows[globallyRemoteKey] = ContextWindow()
            TPipeConfig.useRemoteMemoryGlobally = true
            val globalReadConfig = MemoryIntrospectionConfig(
                allowedPageKeys = mutableSetOf(globallyRemoteKey),
                allowRead = true
            )
            val globalQueryResults = MemoryIntrospection.withCoroutineScope(globalReadConfig) {
                MemoryIntrospectionTools.queryLorebook(globallyRemoteKey)
            }

            assertEquals(listOf("backend-lore"), globalQueryResults.map { it.entry.key })
            assertTrue(ContextBank.contextWindowExistsSuspend(globallyRemoteKey))
            assertEquals(2, backend.queryCallCount)
            assertTrue(backend.getContextCallCount >= 2)

            val writeConfig = MemoryIntrospectionConfig(
                allowedPageKeys = mutableSetOf(contextKey),
                allowRead = true,
                allowWrite = true
            )
            val remoteEntry = LoreBook().apply {
                key = "remote-written"
                value = "written remotely"
            }
            assertTrue(
                MemoryIntrospection.withCoroutineScope(writeConfig) {
                    MemoryIntrospectionTools.updateLorebookEntry(contextKey, remoteEntry)
                }
            )
            assertEquals(
                "written remotely",
                backend.contextWindows[contextKey]?.loreBookKeys?.get("remote-written")?.value
            )

            val remoteTodo = TodoList().apply {
                tasks.tasks += TodoListTask(1, "remote task", "persist it", false)
            }
            assertTrue(
                MemoryIntrospection.withCoroutineScope(writeConfig) {
                    MemoryIntrospectionTools.updateTodoList(contextKey, remoteTodo)
                }
            )
            assertEquals(remoteTodo, backend.todoLists[contextKey])
        }
    }

    /** Per-page lorebook locks route through the backend when the page is remote. */
    @Test
    fun remotePageLorebookLocksUseRegisteredBackend()
    {
        runBlocking {
            val backend = FakePersistenceBackend()
            ContextBank.setRemotePersistenceBackend(backend)
            ContextBank.setStorageMode(remotePageKey, StorageMode.REMOTE)

            ContextLock.addLockSuspend(
                key = remoteLorebookLockKey,
                pageKeys = remotePageKey,
                isPageKey = false
            )
            ContextLock.lockKeyBundleSuspend(remoteLorebookLockKey)
            assertEquals(1, backend.addLockCallCount)
            assertEquals(1, backend.updateLockCallCount)

            backend.lockKeys += remoteLorebookLockKey
            assertTrue(ContextLock.getLockKeysSuspend().contains(remoteLorebookLockKey))

            ContextLock.removeLockSuspend(remoteLorebookLockKey)
            assertEquals(1, backend.removeLockCallCount)
        }
    }

    /** A failed initial merge-save write retains the legacy false-return contract. */
    @Test
    fun mergeSaveReturnsFalseWhenInitialWriteFails()
    {
        runBlocking {
            val backend = FakePersistenceBackend().apply {
                writeFailure = IllegalStateException("write failed")
            }
            ContextBank.setRemotePersistenceBackend(backend)

            assertFalse(ContextBank.fetchMergeSaveRemoteContext(contextKey, ContextWindow()))
            assertEquals(1, backend.putCallCount)
        }
    }

    /** A failed merge write retains the merged versioning behavior and returns false. */
    @Test
    fun mergeSaveReturnsFalseWhenMergedWriteFails()
    {
        runBlocking {
            val backend = FakePersistenceBackend().apply {
                contextWindows[contextKey] = ContextWindow().apply { version = 7 }
                writeFailure = IllegalStateException("write failed")
            }
            ContextBank.setRemotePersistenceBackend(backend)
            val localWindow = ContextWindow().apply { version = 2 }

            assertFalse(ContextBank.fetchMergeSaveRemoteContext(contextKey, localWindow))
            assertEquals(8, localWindow.version)
            assertEquals(1, backend.putCallCount)
        }
    }

    /** Cancellation from a backend write must not be converted into a normal write failure. */
    @Test
    fun mergeSaveRethrowsCancellation()
    {
        runBlocking {
            val backend = FakePersistenceBackend().apply {
                writeFailure = CancellationException("cancelled")
            }
            ContextBank.setRemotePersistenceBackend(backend)

            assertFailsWith<CancellationException> {
                ContextBank.fetchMergeSaveRemoteContext(contextKey, ContextWindow())
            }
        }
    }

    private class FakePersistenceBackend : ContextPersistenceBackend, ContextQueryBackend, ContextLockBackend
    {
        override val id: String = "compatibility-fake"
        val contextWindows = mutableMapOf<String, ContextWindow>()
        val todoLists = mutableMapOf<String, TodoList>()
        var queryCallCount = 0
        var simulateCallCount = 0
        var getContextCallCount = 0
        var getTodoCallCount = 0
        var listContextCallCount = 0
        var listTodoCallCount = 0
        var lockPageCallCount = 0
        var addLockCallCount = 0
        var removeLockCallCount = 0
        var updateLockCallCount = 0
        val lockKeys = mutableSetOf<String>()
        var putCallCount = 0
        var writeFailure: Exception? = null

        override suspend fun getContextWindow(key: String): ContextWindow?
        {
            getContextCallCount++
            return contextWindows[key]
        }

        override suspend fun putContextWindow(key: String, window: ContextWindow)
        {
            putCallCount++
            writeFailure?.let { throw it }
            contextWindows[key] = window
        }

        override suspend fun deleteContextWindow(key: String): Boolean = contextWindows.remove(key) != null

        override suspend fun listContextWindowKeys(): List<String>
        {
            listContextCallCount++
            return contextWindows.keys.toList()
        }

        override suspend fun getTodoList(key: String): TodoList?
        {
            getTodoCallCount++
            return todoLists[key]
        }

        override suspend fun putTodoList(key: String, todoList: TodoList)
        {
            todoLists[key] = todoList
        }

        override suspend fun deleteTodoList(key: String): Boolean = todoLists.remove(key) != null

        override suspend fun listTodoListKeys(): List<String>
        {
            listTodoCallCount++
            return todoLists.keys.toList()
        }

        override suspend fun queryLorebook(
            key: String,
            query: String,
            minWeight: Int,
            requiredKeys: List<String>,
            aliasKeys: List<String>,
            extractRegex: String
        ): List<LoreBookQueryResult>
        {
            queryCallCount++
            return listOf(
                LoreBookQueryResult(
                    LoreBook().apply {
                        this.key = "backend-lore"
                        value = "backend value"
                    }
                )
            )
        }

        override suspend fun simulateLorebookTrigger(key: String, text: String): List<String>
        {
            simulateCallCount++
            return listOf("backend-trigger")
        }

        override suspend fun getLockKeys(): Set<String> = lockKeys

        override suspend fun isKeyLocked(key: String): Boolean = false

        override suspend fun isPageLocked(pageKey: String): Boolean
        {
            lockPageCallCount++
            return false
        }

        override suspend fun addLock(request: LockRequest)
        {
            addLockCallCount++
            lockKeys += request.key
        }

        override suspend fun removeLock(key: String): Boolean
        {
            removeLockCallCount++
            lockKeys.remove(key)
            return true
        }

        override suspend fun updateLockState(key: String, lockState: Boolean): Boolean
        {
            updateLockCallCount++
            return true
        }
    }
}
