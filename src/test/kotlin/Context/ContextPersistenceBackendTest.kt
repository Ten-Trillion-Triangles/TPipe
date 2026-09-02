package com.TTT.Context

import com.TTT.Context.Persistence.ContextPersistenceBackend
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ContextPersistenceBackendTest
{
    private val key = "generic-backend-${System.nanoTime()}"

    @AfterEach
    fun cleanup()
    {
        ContextBank.clearRemotePersistenceBackend()
        runBlocking { ContextBank.deleteContextWindowSuspend(key, skipRemote = true) }
    }

    @Test
    fun remoteStorageModeUsesProviderNeutralBackend() = runBlocking {
        val backend = FakeContextPersistenceBackend()
        ContextBank.setRemotePersistenceBackend(backend)
        ContextBank.setStorageMode(key, StorageMode.REMOTE)

        val window = ContextWindow().apply {
            contextElements += "exact value"
            version = 7
        }
        ContextBank.emplaceSuspend(key, window, StorageMode.REMOTE)

        assertEquals(window.contextElements, backend.windows[key]?.contextElements)
        assertEquals(7, ContextBank.getContextFromBankSuspend(key).version)
        assertTrue(ContextBank.deleteContextWindowSuspend(key))
        assertFalse(backend.windows.containsKey(key))
        assertFalse(ContextBank.deleteContextWindowSuspend(key))
    }

    private class FakeContextPersistenceBackend : ContextPersistenceBackend
    {
        override val id: String = "fake"
        val windows = mutableMapOf<String, ContextWindow>()
        private val todos = mutableMapOf<String, TodoList>()

        override suspend fun getContextWindow(key: String): ContextWindow? = windows[key]
        override suspend fun putContextWindow(key: String, window: ContextWindow)
        {
            windows[key] = window
        }
        override suspend fun deleteContextWindow(key: String): Boolean = windows.remove(key) != null
        override suspend fun listContextWindowKeys(): List<String> = windows.keys.toList()
        override suspend fun getTodoList(key: String): TodoList? = todos[key]
        override suspend fun putTodoList(key: String, todoList: TodoList)
        {
            todos[key] = todoList
        }
        override suspend fun deleteTodoList(key: String): Boolean = todos.remove(key) != null
        override suspend fun listTodoListKeys(): List<String> = todos.keys.toList()
    }
}
