package com.TTT.Context

import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicInteger

class MetadataBankTests
{
    @Before
    fun setup()
    {
        MetadataBank.clear()
    }

    @After
    fun tearDown()
    {
        MetadataBank.clear()
    }

    @Test
    fun testSetMetaThenGetMetaReturnsSameMap()
    {
        val map = mapOf<Any, Any>("alpha" to 1, "beta" to "two")
        MetadataBank.setMeta("test-key", map)

        val retrieved = MetadataBank.getMeta("test-key")
        assertNotNull(retrieved)
        assertEquals(1, retrieved["alpha"])
        assertEquals("two", retrieved["beta"])
    }

    @Test
    fun testSuspendSetAndGetThroughRunBlockingMatchesBlockingShape()
    {
        kotlinx.coroutines.runBlocking {
            val v = mapOf<Any, Any>("k" to 42)
            MetadataBank.setMetaSuspend("sx", v)
            val out = MetadataBank.getMetaSuspend("sx")
            assertEquals(42, out?.get("k"))
        }
    }

    @Test
    fun testEmplaceOnMissingKeyCreatesPage()
    {
        MetadataBank.emplace("emerge", mapOf<Any, Any>("a" to 1))
        val out = MetadataBank.getMeta("emerge")
        assertEquals(1, out?.get("a"))
    }

    @Test
    fun testEmplaceOnExistingKeyMergesAndPreservesOriginals()
    {
        MetadataBank.setMeta("merge-source", mapOf<Any, Any>("a" to 1, "b" to 2))
        MetadataBank.emplace("merge-source", mapOf<Any, Any>("c" to 3, "b" to "overridden"))
        val out = MetadataBank.getMeta("merge-source")
        assertEquals(1, out?.get("a"))
        assertEquals("overridden", out?.get("b"))
        assertEquals(3, out?.get("c"))
    }

    @Test
    fun testDeleteRemovesPage()
    {
        MetadataBank.setMeta("gone", mapOf<Any, Any>("x" to 1))
        assertNotNull(MetadataBank.getMeta("gone"))
        val result = MetadataBank.delete("gone")
        assertEquals(true, result)
        assertNull(MetadataBank.getMeta("gone"))
    }

    @Test
    fun testDeleteMissingReturnsFalse()
    {
        assertEquals(false, MetadataBank.delete("never-was"))
    }

    @Test
    fun testExistsCorrectForPresentAndMissing()
    {
        MetadataBank.setMeta("present", mapOf<Any, Any>("x" to 1))
        assertEquals(true, MetadataBank.exists("present"))
        assertEquals(false, MetadataBank.exists("absent"))
    }

    @Test
    fun testSwapMetaPromotesPageToActive()
    {
        MetadataBank.setMeta("p1", mapOf<Any, Any>("a" to 1))
        MetadataBank.swapMeta("p1")
        val active = MetadataBank.getActiveMeta()
        assertEquals(1, active?.get("a"))
    }

    @Test
    fun testSwapMetaOnUnknownKeyLeavesActiveNullIfNeverSet()
    {
        MetadataBank.swapMeta("nope")
        assertNull(MetadataBank.getActiveMeta())
    }

    @Test
    fun testSwapMetaOverwritesPreviousActive()
    {
        MetadataBank.setMeta("alpha", mapOf<Any, Any>("a" to 1))
        MetadataBank.setMeta("beta", mapOf<Any, Any>("b" to 2))
        MetadataBank.swapMeta("alpha")
        MetadataBank.swapMeta("beta")
        assertEquals(2, MetadataBank.getActiveMeta()?.get("b"))
    }

    @Test
    fun testPullGluedStringParsesCommaSpaceSeparatedKeys()
    {
        MetadataBank.setMeta("k1", mapOf<Any, Any>("a" to 1))
        MetadataBank.setMeta("k2", mapOf<Any, Any>("b" to 2))
        MetadataBank.setMeta("k3", mapOf<Any, Any>("c" to 3))

        val target = mutableMapOf<Any, Any>()
        MetadataBank.pullMetaPageKeysInto(target, "k1, k2, k3")

        assertEquals(1, target["a"])
        assertEquals(2, target["b"])
        assertEquals(3, target["c"])
    }

    @Test
    fun testPullTolerantOfExtraWhitespaceAndEmptyEntries()
    {
        MetadataBank.setMeta("real", mapOf<Any, Any>("x" to 7))
        val target = mutableMapOf<Any, Any>()
        MetadataBank.pullMetaPageKeysInto(target, "  , real, , ,")
        assertEquals(7, target["x"])
    }

    @Test
    fun testPullLastWriteWinsOnKeyCollision()
    {
        MetadataBank.setMeta("pageA", mapOf<Any, Any>("k" to "fromA"))
        MetadataBank.setMeta("pageB", mapOf<Any, Any>("k" to "fromB"))
        val target = mutableMapOf<Any, Any>()
        MetadataBank.pullMetaPageKeysInto(target, "pageA, pageB")
        assertEquals("fromB", target["k"])
    }

    @Test
    fun testPullSkipsMissingKeys()
    {
        MetadataBank.setMeta("present", mapOf<Any, Any>("x" to 1))
        val target = mutableMapOf<Any, Any>()
        MetadataBank.pullMetaPageKeysInto(target, "missing, present, also-missing")
        assertEquals(1, target["x"])
        assertEquals(1, target.size)
    }

    @Test
    fun testKeysReturnsAllLivePageKeys()
    {
        MetadataBank.setMeta("one", mapOf<Any, Any>("x" to 1))
        MetadataBank.setMeta("two", mapOf<Any, Any>("y" to 2))
        val allKeys = MetadataBank.keys()
        assertEquals(setOf("one", "two"), allKeys)
    }

    @Test
    fun testDebugSnapshotStringifiesValues()
    {
        MetadataBank.setMeta("probe", mapOf<Any, Any>("a" to 1, "b" to "text"))
        val snapshot = MetadataBank.debugSnapshot()
        assertNotNull(snapshot["probe"])
        assertTrue(snapshot["probe"]!!.contains("a=1"), "snapshot must contain 'a=1'")
        assertTrue(snapshot["probe"]!!.contains("b=text"), "snapshot must contain 'b=text'")
    }

    @Test
    fun testConcurrentEmplaceOnSameKeyPreservesAllEntries()
    {
        val sharedKey = "race-target"
        val failures = AtomicInteger(0)
        val workerCount = 16
        val perWorker = 50

        runBlocking {
            coroutineScope {
                val jobs = (1..workerCount).map { workerIndex ->
                    launch(Dispatchers.Default) {
                        repeat(perWorker) { iter ->
                            try
                            {
                                MetadataBank.emplaceSuspend(
                                    sharedKey,
                                    mapOf<Any, Any>("w$workerIndex-$iter" to iter)
                                )
                            }
                            catch (e: Exception)
                            {
                                failures.incrementAndGet()
                            }
                        }
                    }
                }
                jobs.joinAll()
            }
        }

        assertEquals(0, failures.get(), "Concurrent emplace must not throw")
        val final = MetadataBank.getMeta(sharedKey)
        assertNotNull(final)
        assertEquals(
            workerCount * perWorker,
            final!!.size,
            "Concurrent emplace must accumulate every entry without loss"
        )
    }

    @Test
    fun testConcurrentSetMetaOnDifferentKeysIsIndependentlyAtomic()
    {
        val failures = AtomicInteger(0)
        val writerCount = 32

        runBlocking {
            coroutineScope {
                val jobs = (1..writerCount).map { idx ->
                    launch(Dispatchers.Default) {
                        repeat(20) {
                            try
                            {
                                MetadataBank.setMetaSuspend(
                                    "writer-$idx",
                                    mapOf<Any, Any>("count" to it)
                                )
                                MetadataBank.getMetaSuspend("writer-$idx")
                            }
                            catch (e: Exception)
                            {
                                failures.incrementAndGet()
                            }
                        }
                    }
                }
                jobs.joinAll()
            }
        }

        assertEquals(0, failures.get(), "Concurrent set/get must not throw")
        for (i in 1..writerCount)
        {
            val v = MetadataBank.getMeta("writer-$i")
            assertNotNull(v, "writer-$i page must exist")
            assertEquals(19, v!!["count"], "writer-$i's last write was 19")
        }
    }
}
