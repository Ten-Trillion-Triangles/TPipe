package com.TTT.Context

import com.TTT.Config.TPipeConfig
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ContextBankStorageModeTest
{
    private val testKey = "test-storage-mode"
    private val testTodoKey = "test-todo-storage"

    @Before
    fun setup()
    {
        cleanup()
    }

    @After
    fun cleanup()
    {
        ContextBank.evictFromMemory(testKey)
        ContextBank.evictTodoListFromMemory(testTodoKey)
        ContextBank.deletePersistingBankKey(testKey)
        File("${TPipeConfig.getTodoListDir()}/${testTodoKey}.todo").delete()
    }

    @Test
    fun testMemoryOnlyMode()
    {
        val window = ContextWindow()
        window.contextElements.add("memory only test")

        ContextBank.emplace(testKey, window, StorageMode.MEMORY_ONLY)

        val diskPath = "${TPipeConfig.getLorebookDir()}/${testKey}.bank"
        assertFalse(File(diskPath).exists(), "MEMORY_ONLY should not create disk file")

        val loaded = ContextBank.getContextFromBank(testKey)
        assertEquals("memory only test", loaded.contextElements[0])
    }

    @Test
    fun testMemoryAndDiskMode()
    {
        val window = ContextWindow()
        window.contextElements.add("memory and disk test")

        ContextBank.emplace(testKey, window, StorageMode.MEMORY_AND_DISK)

        val diskPath = "${TPipeConfig.getLorebookDir()}/${testKey}.bank"
        assertTrue(File(diskPath).exists(), "MEMORY_AND_DISK should create disk file")

        ContextBank.evictFromMemory(testKey)

        val loaded = ContextBank.getContextFromBank(testKey)
        assertEquals("memory and disk test", loaded.contextElements[0])
    }

    @Test
    fun testDiskOnlyMode()
    {
        val window = ContextWindow()
        window.contextElements.add("disk only test")

        ContextBank.emplace(testKey, window, StorageMode.DISK_ONLY)

        val diskPath = "${TPipeConfig.getLorebookDir()}/${testKey}.bank"
        assertTrue(File(diskPath).exists(), "DISK_ONLY should create disk file")

        val pageKeys = ContextBank.getPageKeys()
        assertFalse(pageKeys.contains(testKey), "DISK_ONLY should not cache in memory")

        val loaded = ContextBank.getContextFromBank(testKey)
        assertEquals("disk only test", loaded.contextElements[0])

        val pageKeysAfterLoad = ContextBank.getPageKeys()
        assertFalse(pageKeysAfterLoad.contains(testKey), "DISK_ONLY should not cache after load")
    }

    @Test
    fun testDiskWithCacheMode()
    {
        val window = ContextWindow()
        window.contextElements.add("disk with cache test")

        ContextBank.emplace(testKey, window, StorageMode.DISK_WITH_CACHE)

        val diskPath = "${TPipeConfig.getLorebookDir()}/${testKey}.bank"
        assertTrue(File(diskPath).exists(), "DISK_WITH_CACHE should create disk file")

        val pageKeys = ContextBank.getPageKeys()
        assertTrue(pageKeys.contains(testKey), "DISK_WITH_CACHE should cache in memory")

        ContextBank.evictFromMemory(testKey)

        val loaded = ContextBank.getContextFromBank(testKey)
        assertEquals("disk with cache test", loaded.contextElements[0])

        val pageKeysAfterLoad = ContextBank.getPageKeys()
        assertTrue(pageKeysAfterLoad.contains(testKey), "DISK_WITH_CACHE should cache after load")
    }

    @Test
    fun testBackwardCompatibility()
    {
        runBlocking {
            val window = ContextWindow()
            window.contextElements.add("backward compat test")

            ContextBank.emplaceWithMutex(testKey, window, true)

            val diskPath = "${TPipeConfig.getLorebookDir()}/${testKey}.bank"
            assertTrue(File(diskPath).exists(), "persistToDisk=true should create disk file")

            val loaded = ContextBank.getContextFromBank(testKey)
            assertEquals("backward compat test", loaded.contextElements[0])
        }
    }

    @Test
    fun testEvictionMethods()
    {
        val window = ContextWindow()
        window.contextElements.add("eviction test")

        ContextBank.emplace(testKey, window, StorageMode.MEMORY_AND_DISK)

        assertTrue(ContextBank.getPageKeys().contains(testKey))

        val evicted = ContextBank.evictFromMemory(testKey)
        assertTrue(evicted, "Should return true when key was evicted")

        assertFalse(ContextBank.getPageKeys().contains(testKey))

        val diskPath = "${TPipeConfig.getLorebookDir()}/${testKey}.bank"
        assertTrue(File(diskPath).exists(), "Disk file should still exist after eviction")
    }

    @Test
    fun testCacheConfiguration()
    {
        val config = CacheConfig(
            maxMemoryBytes = 1024,
            maxEntries = 10,
            evictionPolicy = EvictionPolicy.LRU
        )

        ContextBank.configureCachePolicy(config)

        val stats = ContextBank.getCacheStatistics()
        assertTrue(stats.memoryEntries >= 0)
        assertTrue(stats.totalMemoryBytes >= 0)
    }

    @Test
    fun testStorageModeGettersSetters()
    {
        ContextBank.setStorageMode(testKey, StorageMode.DISK_ONLY)

        val mode = ContextBank.getStorageMode(testKey)
        assertEquals(StorageMode.DISK_ONLY, mode)
    }

    @Test
    fun testTodoListDiskOnlyMode()
    {
        val todo = TodoList()
        todo.tasks.tasks.add(TodoListTask(1, "Test task", "Complete it", false))

        ContextBank.emplaceTodoList(testTodoKey, todo, StorageMode.DISK_ONLY, false, false)

        val diskPath = "${TPipeConfig.getTodoListDir()}/${testTodoKey}.todo"
        assertTrue(File(diskPath).exists(), "TodoList DISK_ONLY should create disk file")

        val loaded = ContextBank.getPagedTodoList(testTodoKey)
        assertEquals("Test task", loaded.tasks.tasks[0].task)
    }

    @Test
    fun testClearCache()
    {
        val window = ContextWindow()
        window.contextElements.add("cache clear test")

        ContextBank.emplace(testKey, window, StorageMode.DISK_WITH_CACHE)

        assertTrue(ContextBank.getPageKeys().contains(testKey))

        ContextBank.clearCache()

        assertFalse(ContextBank.getPageKeys().contains(testKey))
    }
}
