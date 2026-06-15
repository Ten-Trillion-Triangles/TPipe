package com.TTT.Pipeline

import com.TTT.Context.ConverseData
import com.TTT.Context.ConverseRole
import com.TTT.P2P.KillSwitch
import com.TTT.P2P.P2PInterface
import com.TTT.P2P.P2PRequest
import com.TTT.P2P.P2PResponse
import com.TTT.Pipe.MultimodalContent
import com.TTT.Pipe.TokenBudgetSettings
import com.TTT.Structs.PipeSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Test
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for the PumpStation async substrate:
 * - [PendingTurnEntry] / [AsyncTurnAppended] event plumbing
 * - PathObject.suppressHistoryEmit
 * - HarnessAgentSlot.appendsToTurnHistory
 * - PumpStation.appendTurnEntryAsync thread-safety under concurrent producers
 * - drainPendingAsyncResults seq-ordering
 * - cancelAsyncJobs grace period (null by default = unbounded)
 */
class PumpStationAsyncSubstrateTest
{
    //=====================================PendingTurnEntry / AsyncTurnAppended====================================

    @Test
    fun testPendingTurnEntryCarriesSeq()
    {
        val entry = PendingTurnEntry(
            seq = 42L,
            turnIndex = 1,
            pathName = "p",
            agentName = null,
            source = "asyncPath",
            result = MultimodalContent(text = "hello"),
            passPipeline = false,
            terminatePipeline = false
        )
        assertEquals(42L, entry.seq)
        assertEquals("p", entry.pathName)
        assertNull(entry.agentName)
        assertFalse(entry.passPipeline)
    }

    @Test
    fun testAsyncTurnAppendedEventCarriesSeq()
    {
        val event = AsyncTurnAppended(
            runId = "r",
            turnIndex = 0,
            source = "asyncPath",
            pathName = "p",
            agentName = null,
            seq = 7L,
            content = MultimodalContent(text = "hi")
        )
        assertEquals(7L, event.seq)
        assertEquals("asyncPath", event.source)
        assertEquals("p", event.pathName)
    }

    //=====================================PathObject.suppressHistoryEmit===========================================

    @Test
    fun testPathObjectSuppressHistoryEmitDefaultIsFalse()
    {
        val path = testPath("p")
        assertFalse(path.isSuppressHistoryEmit)
    }

    @Test
    fun testPathObjectSuppressHistoryEmitSetter()
    {
        val path = testPath("p")
        path.setSuppressHistoryEmit(true)
        assertTrue(path.isSuppressHistoryEmit)
        path.setSuppressHistoryEmit(false)
        assertFalse(path.isSuppressHistoryEmit)
    }

    //=====================================HarnessAgentSlot.appendsToTurnHistory==================================

    @Test
    fun testHarnessAgentSlotAppendsToTurnHistoryDefaultIsFalse()
    {
        val slot = HarnessAgentSlot(agent = StubAgent(), concurrency = PumpStationConcurrencyMode.Async)
        assertFalse(slot.appendsToTurnHistory)
    }

    @Test
    fun testHarnessAgentSlotAppendsToTurnHistoryCanBeSet()
    {
        val slot = HarnessAgentSlot(
            agent = StubAgent(),
            concurrency = PumpStationConcurrencyMode.Async,
            appendsToTurnHistory = true
        )
        assertTrue(slot.appendsToTurnHistory)
    }

    //=====================================appendTurnEntryAsync thread safety=====================================

    @Test
    fun testAppendTurnEntryAsyncIsThreadSafeUnderConcurrentProducers() = runBlocking {
        val station = buildTestStation()
        val producerCount = 50
        val jobs = (0 until producerCount).map { idx ->
            async(Dispatchers.Default) {
                val entry = ConverseData(
                    role = ConverseRole.assistant,
                    content = MultimodalContent(text = "entry-$idx-${UUID.randomUUID()}")
                )
                station.appendTurnEntryAsync(entry, source = "test")
            }
        }
        jobs.awaitAll()
        assertEquals(producerCount, station.turnHistory.history.size)
        assertEquals(producerCount, station.rawTurnHistory.history.size)
    }

    @Test
    fun testAppendTurnEntryAsyncEmitsEvent()
    {
        val station = buildTestStation()
        val events = mutableListOf<PumpStationEvent>()
        station.setEventObserver(events::add)
        runBlocking {
            station.appendTurnEntryAsync(
                ConverseData(role = ConverseRole.assistant, content = MultimodalContent(text = "x")),
                source = "test"
            )
        }
        assertTrue(events.any { it is AsyncTurnAppended && it.source == "test" })
    }

    @Test
    fun testAppendTurnEntriesAsyncBatchedUnderSingleLock() = runBlocking {
        val station = buildTestStation()
        val entries = (0 until 10).map {
            ConverseData(role = ConverseRole.assistant, content = MultimodalContent(text = "e-$it"))
        }
        station.appendTurnEntriesAsync(entries, source = "batch")
        assertEquals(10, station.turnHistory.history.size)
    }

    //=====================================drainPendingAsyncResults seq ordering==================================

    @Test
    fun testDrainOrdersBySeqWhenChannelServedOutOfOrder() = runBlocking {
        val station = buildTestStation().setAsyncPathsAppendToTurnHistory(true)
        val e1 = makePending(seq = 1, pathName = "a", text = "alpha")
        val e2 = makePending(seq = 2, pathName = "b", text = "bravo")
        val e3 = makePending(seq = 3, pathName = "c", text = "charlie")
        station.pendingAsyncResultsInternal.trySend(e3)
        station.pendingAsyncResultsInternal.trySend(e1)
        station.pendingAsyncResultsInternal.trySend(e2)
        val merged = station.drainPendingAsyncResults()
        assertEquals(3, merged)
        val history = station.turnHistory.history
        assertEquals("alpha", history[0].content.text)
        assertEquals("bravo", history[1].content.text)
        assertEquals("charlie", history[2].content.text)
    }

    @Test
    fun testDrainRespectsSuppressHistoryEmit() = runBlocking {
        val station = buildTestStation()
        val quietPath = testPath("quiet")
        quietPath.setSuppressHistoryEmit(true)
        station.addPath(quietPath)
        val noisyPath = testPath("noisy")
        station.addPath(noisyPath)

        station.pendingAsyncResultsInternal.trySend(makePending(seq = 1, pathName = "quiet", text = "skip"))
        station.pendingAsyncResultsInternal.trySend(makePending(seq = 2, pathName = "noisy", text = "keep"))
        val merged = station.drainPendingAsyncResults()
        assertEquals(1, merged)
        val texts = station.turnHistory.history.map { it.content.text }
        assertEquals(listOf("keep"), texts)
    }

    @Test
    fun testDrainReturnsZeroWhenAsyncPathsAppendDisabled() = runBlocking {
        val station = buildTestStation().setAsyncPathsAppendToTurnHistory(false)
        station.pendingAsyncResultsInternal.trySend(makePending(seq = 1, pathName = "p", text = "t"))
        assertEquals(0, station.drainPendingAsyncResults())
        assertEquals(0, station.turnHistory.history.size)
    }

    //=====================================cancelAsyncJobs==========================================================

    @Test
    fun testCancelAsyncJobsDeactivatesScope()
    {
        val station = buildTestStation()
        assertTrue(station.isAsyncScopeActive())
        // Pass an explicit grace period; the default is null (unbounded).
        station.cancelAsyncJobs(gracePeriodMs = 50L)
        assertFalse(station.isAsyncScopeActive())
    }

    @Test
    fun testCancelAsyncJobsWithoutGracePeriodStillCancels()
    {
        // With the default null grace period the cancel is unbounded — it
        // still cancels the scope, just without an enforced timeout. Long
        // running work is interrupted by the cancel, not by a clock.
        val station = buildTestStation()
        assertTrue(station.isAsyncScopeActive())
        station.cancelAsyncJobs()
        assertFalse(station.isAsyncScopeActive())
    }

    @Test
    fun testCancelAsyncJobsCancelsInflightCoroutines() = runBlocking {
        val station = buildTestStation()
        val started = AtomicInteger(0)
        val completed = AtomicInteger(0)
        station.asyncScope.launch {
            try
            {
                started.incrementAndGet()
                delay(5_000)
                completed.incrementAndGet()
            }
            catch (e: kotlinx.coroutines.CancellationException)
            {
                throw e
            }
        }
        withTimeout(2_000) { while (started.get() == 0) delay(5) }
        station.cancelAsyncJobs(gracePeriodMs = 50L)
        withTimeoutOrNull(2_000) {
            while (station.isAsyncScopeActive()) delay(5)
        }
        assertFalse(station.isAsyncScopeActive())
        assertEquals(0, completed.get())
    }

    //=====================================station defaults========================================================

    @Test
    fun testAsyncDefaultsMatchPlan()
    {
        val station = buildTestStation()
        assertTrue(station.isAsyncPathsAppendToTurnHistory())
        assertFalse(station.isAsyncAgentsAppendToTurnHistory())
        assertTrue(station.isAsyncJobsScopedToStation())
        // The grace period is OFF by default — TPipe does not impose an
        // arbitrary timeout on user work. Developers opt in by setting a value.
        assertNull(station.getAsyncJobGracePeriodMs())
    }

    @Test
    fun testSettersFlipDefaults()
    {
        val station = buildTestStation()
            .setAsyncPathsAppendToTurnHistory(false)
            .setAsyncAgentsAppendToTurnHistory(true)
            .setAsyncJobsScopedToStation(false)
            .setAsyncJobGracePeriodMs(1_800_000L) // 30 minutes, a realistic grace period
        assertFalse(station.isAsyncPathsAppendToTurnHistory())
        assertTrue(station.isAsyncAgentsAppendToTurnHistory())
        assertFalse(station.isAsyncJobsScopedToStation())
        assertEquals(1_800_000L, station.getAsyncJobGracePeriodMs())
        // Setting back to null disables the timeout entirely.
        station.setAsyncJobGracePeriodMs(null)
        assertNull(station.getAsyncJobGracePeriodMs())
    }

    @Test
    fun testDslNullableGracePeriod()
    {
        // Use the builder factory directly to avoid the dispatchAgent
        // validation that pumpStation { ... } enforces. We are only testing
        // the asyncJobGracePeriodMs DSL knob round-trip.
        val builder = pumpStationBuilder("grace-test")
        assertNull(builder.asyncJobGracePeriodMs)
        builder.asyncJobGracePeriodMs = 30 * 60 * 1000L
        assertEquals(30 * 60 * 1000L, builder.asyncJobGracePeriodMs)
        val builder2 = pumpStationBuilder("grace-test-2")
        builder2.asyncJobGracePeriodMs = null
        assertNull(builder2.asyncJobGracePeriodMs)
    }

    //=====================================helpers================================================================

    private fun makePending(
        seq: Long,
        pathName: String,
        text: String,
        source: String = "asyncPath"
    ): PendingTurnEntry = PendingTurnEntry(
        seq = seq,
        turnIndex = 0,
        pathName = pathName,
        agentName = null,
        source = source,
        result = MultimodalContent(text = text)
    )

    private class StubAgent : P2PInterface
    {
        override var killSwitch: KillSwitch? = null
        override suspend fun P2PInit() {}
        override suspend fun executeLocal(content: MultimodalContent): MultimodalContent = content
        override suspend fun executeP2PRequest(request: P2PRequest): P2PResponse? = null
        override fun setParentInterface(parent: P2PInterface) {}
        override fun getParentP2PInterface(): P2PInterface? = null
        override fun getPaths(): String = ""
        override fun setTokenBudgetRecursive(budget: TokenBudgetSettings) {}
        override fun getTokenBudgetSettings(): TokenBudgetSettings? = null
        override fun setPipeSettingsRecursively(settings: PipeSettings) {}
    }
}
