package com.TTT.Context

import com.TTT.Config.TPipeConfig
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.Before
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertEquals

class WriteBackFunctionTest
{
    @Before
    fun setup() {
        ContextBank.clearWriteBackFunctions()
        ContextBank.evictAllFromMemory()
        TPipeConfig.remoteMemoryEnabled = false
        TPipeConfig.useRemoteMemoryGlobally = false
    }

    @Test
    fun testWriteBackFunctionReplacesEmplaceWithMutex() {
        runBlocking {
            val key = "writeBackKey"
            var customWriteCalled = false
            var writtenWindow: ContextWindow? = null

            // 1. Register a write back function
            ContextBank.registerWriteBackFunction(key) { capturedKey, window ->
                customWriteCalled = true
                writtenWindow = window
                assertEquals(key, capturedKey)
                true // success
            }

            // 2. Try to emplace data via the mutex (which triggers WriteBackFunctions)
            val windowToSave = ContextWindow().apply { addLoreBookEntry("custom_entry", "some_data") }
            ContextBank.emplaceWithMutex(key, windowToSave, StorageMode.MEMORY_ONLY)

            // 3. Verify custom write logic was called
            assertTrue(customWriteCalled, "Custom write back logic should have been called")
            assertTrue(writtenWindow?.loreBookKeys?.containsKey("custom_entry") == true, "Custom data should be in window")

            // 4. Verify it REPLACED standard persistence (ContextBank memory should not contain this window)
            // It will only not contain it if we clear our context memory or just test that an empty key is not present.
            // Wait, getContextFromBank will throw IllegalStateException if it is empty, or just return empty.
            // Actually, we can check getPageKeys to see if it was persisted in MEMORY_ONLY
            assertFalse(ContextBank.getPageKeys().contains(key), "Key should not be stored in standard ContextBank memory")

            // Cleanup
            ContextBank.removeWriteBackFunction(key)
        }
    }

    @Test
    fun testWriteBackFunctionIgnoredForSyncEmplace() {
        runBlocking {
            val key = "syncKey"
            var customWriteCalled = false

            ContextBank.registerWriteBackFunction(key) { _, _ ->
                customWriteCalled = true
                true
            }

            // Call standard non-suspending emplace, which should ignore WriteBackFunctions
            val windowToSave = ContextWindow().apply { addLoreBookEntry("sync_entry", "sync_data") }
            ContextBank.emplace(key, windowToSave, StorageMode.MEMORY_ONLY)

            // Verify
            assertFalse(customWriteCalled, "Write back function should NOT be called for synchronous emplace")
            assertTrue(ContextBank.getPageKeys().contains(key), "Key should be normally stored via sync emplace")

            // Cleanup
            ContextBank.removeWriteBackFunction(key)
            ContextBank.deletePersistingBankKey(key)
        }
    }
}
