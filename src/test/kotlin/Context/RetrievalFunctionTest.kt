package com.TTT.Context

import com.TTT.Config.TPipeConfig
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.Before
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class RetrievalFunctionTest
{
    @Before
    fun setup() {
        ContextBank.clearRetrievalFunctions()
        ContextBank.evictAllFromMemory()
        TPipeConfig.remoteMemoryEnabled = false
        TPipeConfig.useRemoteMemoryGlobally = false
    }

    @Test
    fun testRetrievalFunctionPriority() {
        runBlocking {
            val key = "testKey"
            val localContext = ContextWindow().apply { addLoreBookEntry("local", "data") }
            val retrievedContext = ContextWindow().apply { addLoreBookEntry("retrieved", "data") }

            // 1. Setup local data
            ContextBank.emplace(key, localContext, StorageMode.MEMORY_ONLY)

            // Verify local data is returned normally
            val result1 = ContextBank.getContextFromBank(key)
            assertTrue(result1.loreBookKeys.containsKey("local"), "Should contain local entry")
            assertFalse(result1.loreBookKeys.containsKey("retrieved"), "Should not contain retrieved entry")

            // 2. Register retrieval function
            ContextBank.registerRetrievalFunction(key) { _ ->
                retrievedContext
            }

            // Verify key is in getPageKeys
            assertTrue(ContextBank.getPageKeys().contains(key), "Key should be in page keys")

            // Verify retrieval function overrides local data
            val result2 = ContextBank.getContextFromBank(key)
            assertFalse(result2.loreBookKeys.containsKey("local"), "Should not contain local entry after override")
            assertTrue(result2.loreBookKeys.containsKey("retrieved"), "Should contain retrieved entry after override")

            // 3. Remove retrieval function and verify it reverts
            ContextBank.removeRetrievalFunction(key)
            val result3 = ContextBank.getContextFromBank(key)
            assertTrue(result3.loreBookKeys.containsKey("local"), "Should contain local entry after removing override")
            assertFalse(result3.loreBookKeys.containsKey("retrieved"), "Should not contain retrieved entry after removing override")

            // Cleanup
            ContextBank.deletePersistingBankKey(key)
        }
    }

    @Test
    fun testRetrievalFunctionFailure() {
        runBlocking {
            val key = "failKey"

            ContextBank.registerRetrievalFunction(key) { _ ->
                null
            }

            assertFailsWith<IllegalStateException>("Should fail when retrieval function returns null") {
                ContextBank.getContextFromBank(key)
            }

            ContextBank.removeRetrievalFunction(key)
        }
    }
}
