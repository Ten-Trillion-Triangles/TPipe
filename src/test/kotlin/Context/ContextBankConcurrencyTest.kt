package com.TTT.Context

import com.TTT.Config.TPipeConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ContextBankConcurrencyTest
{
    private val sharedContextKey = "concurrency-shared-context"
    private val sharedTodoKey = "concurrency-shared-todo"

    @Before
    fun setup()
    {
        cleanup()
    }

    @After
    fun tearDown()
    {
        cleanup()
    }

    private fun cleanup()
    {
        TPipeConfig.remoteMemoryEnabled = false
        TPipeConfig.useRemoteMemoryGlobally = false
        ContextBank.evictAllFromMemory()
        ContextBank.evictAllTodoListsFromMemory()
        ContextBank.deletePersistingBankKey(sharedContextKey)
        File("${TPipeConfig.getTodoListDir()}/${sharedTodoKey}.todo").delete()
    }

    @Test
    fun testConcurrentContextReadWriteSuspendApis() = kotlinx.coroutines.runBlocking {
        ContextBank.emplaceSuspend(sharedContextKey, ContextWindow(), StorageMode.MEMORY_AND_DISK)
        val failures = AtomicInteger(0)

        coroutineScope {
            val jobs = (1..12).map { workerIndex ->
                launch(Dispatchers.Default) {
                    repeat(75) { iteration ->
                        try
                        {
                            ContextBank.mutateContextWindowSuspend(sharedContextKey, mode = StorageMode.MEMORY_AND_DISK) { window ->
                                window.contextElements.add("worker-$workerIndex-$iteration")
                            }
                            ContextBank.getContextFromBankSuspend(sharedContextKey)
                        }
                        catch(e: Exception)
                        {
                            failures.incrementAndGet()
                        }
                    }
                }
            }
            jobs.joinAll()
        }

        val finalContext = ContextBank.getContextFromBankSuspend(sharedContextKey)
        assertEquals(0, failures.get(), "Concurrent context operations should not throw")
        assertTrue(finalContext.contextElements.isNotEmpty(), "Concurrent writes should leave context data behind")
    }

    @Test
    fun testConcurrentTodoReadWriteSuspendApis() = kotlinx.coroutines.runBlocking {
        ContextBank.emplaceTodoListSuspend(sharedTodoKey, TodoList(), StorageMode.MEMORY_AND_DISK, allowUpdatesOnly = false)
        val failures = AtomicInteger(0)

        coroutineScope {
            val jobs = (1..8).map { workerIndex ->
                launch(Dispatchers.Default) {
                    repeat(40) { iteration ->
                        try
                        {
                            val taskNumber = workerIndex * 1000 + iteration
                            val todoList = ContextBank.getPagedTodoListSuspend(sharedTodoKey)
                            todoList.tasks.tasks.add(TodoListTask(taskNumber, "Task $taskNumber", "Verify $taskNumber", false))
                            ContextBank.emplaceTodoListSuspend(sharedTodoKey, todoList, StorageMode.MEMORY_AND_DISK, allowUpdatesOnly = false)
                            ContextBank.getPagedTodoListSuspend(sharedTodoKey)
                        }
                        catch(e: Exception)
                        {
                            failures.incrementAndGet()
                        }
                    }
                }
            }
            jobs.joinAll()
        }

        val finalTodoList = ContextBank.getPagedTodoListSuspend(sharedTodoKey)
        assertEquals(0, failures.get(), "Concurrent todo operations should not throw")
        assertTrue(finalTodoList.tasks.tasks.isNotEmpty(), "Concurrent todo writes should persist tasks")
    }

    @Test
    fun testBankedContextGetterReturnsSnapshotByDefault() = kotlinx.coroutines.runBlocking {
        val originalContext = ContextWindow().apply {
            contextElements.add("original")
        }
        ContextBank.updateBankedContextSuspend(originalContext)

        val snapshot = ContextBank.getBankedContextWindowSuspend()
        snapshot.contextElements.add("mutated outside bank")

        val latestSnapshot = ContextBank.getBankedContextWindowSuspend()
        assertEquals(listOf("original"), latestSnapshot.contextElements, "Safe default getter should return a snapshot")
    }

    @Test
    fun testMixedMemoryStressSuspendApis() = kotlinx.coroutines.runBlocking {
        val failures = AtomicInteger(0)
        val operations = AtomicInteger(0)

        coroutineScope {
            val jobs = (1..10).map { workerIndex ->
                launch(Dispatchers.Default) {
                    repeat(60) { iteration ->
                        try
                        {
                            val key = if(iteration % 2 == 0) sharedContextKey else "$sharedContextKey-$workerIndex"
                            val window = ContextWindow().apply {
                                contextElements.add("$workerIndex-$iteration")
                            }
                            ContextBank.emplaceSuspend(key, window, StorageMode.MEMORY_ONLY)
                            ContextBank.getContextFromBankSuspend(key)
                            operations.incrementAndGet()
                        }
                        catch(e: Exception)
                        {
                            failures.incrementAndGet()
                        }
                    }
                }
            }
            jobs.joinAll()
        }

        assertEquals(0, failures.get(), "Stress run should complete without throwing")
        assertTrue(operations.get() > 0, "Stress run should execute operations")
    }
}
