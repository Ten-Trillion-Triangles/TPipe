package com.TTT.Pipeline

import com.TTT.Pipe.BinaryContent
import com.TTT.Pipe.MultimodalContent
import com.TTT.Pipe.TokenBudgetSettings
import com.TTT.Enums.PumpStationHistoryTransport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private const val ASYNC_OVERSIZED_PATH_NAME = "async-oversized-output"
private val ASYNC_OVERSIZED_OUTPUT = "async path output ".repeat(2_000)
private val ASYNC_OVERSIZED_BINARY = ByteArray(16 * 1024) { (it % 251).toByte() }

/** Verifies asynchronous MultiPath results are stashed before entering parent context. */
class PumpStationAsyncOversizedPathOutputTest
{
    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun oversizedMultiPathAsyncOutputIsStashedAndMergedOnce()
    {
        runBlocking {
            val station = PumpStation()
                .setDispatchAgent(Pipeline().apply {
                    add(ScriptedTestPipe(response = """{"paths":[{"pathName":"$ASYNC_OVERSIZED_PATH_NAME","inputData":{}}]}"""))
                })
                .setPathExecutionShape(PathExecutionShape.MultiPath)
                .setBlowoutThreshold(0.1)
                .setHistoryTransport(PumpStationHistoryTransport.ContextOnly)
            station.setTokenBudgetRecursive(TokenBudgetSettings(maxTokens = 1_000, contextWindowSize = 10_000))
            station.getTaskState().originalInput = MultimodalContent(text = "run the background path")
            val path = PathObject().apply {
                pathName = ASYNC_OVERSIZED_PATH_NAME
                pathDescription = "Returns a large native multimodal result."
                setRunsInBackground(true)
                setExecutionFunction { _, _, _, _ ->
                    MultimodalContent(text = ASYNC_OVERSIZED_OUTPUT).apply {
                        addBinary(byteArrayOf(4, 5, 6), "image/png", "async-result.png")
                        passPipeline = true
                        terminatePipeline = true
                    }
                }
            }
            station.addPath(path)
            station.P2PInit()

            station.runDispatchPhaseMulti()
            withTimeout(5_000) {
                while (station.pendingAsyncResultsInternal.isEmpty)
                {
                    delay(5)
                }
            }

            assertEquals(1, station.drainPendingAsyncResults())

            val stash = station.getStashManifest().single()
            assertEquals(ASYNC_OVERSIZED_PATH_NAME, stash.sourcePath)
            val stashed = station.retrieveStash(stash.id)
            assertNotNull(stashed)
            assertEquals(ASYNC_OVERSIZED_OUTPUT, stashed.content.text)
            val image = stashed.content.binaryContent.single() as BinaryContent.Bytes
            assertContentEquals(byteArrayOf(4, 5, 6), image.data)
            assertEquals("image/png", image.mimeType)
            assertEquals("async-result.png", image.filename)
            assertTrue(stashed.content.passPipeline)
            assertTrue(stashed.content.terminatePipeline)

            val compactHistoryEntries = station.turnHistory.history.filter {
                it.role == com.TTT.Context.ConverseRole.assistant &&
                    it.content.metadata["pathName"] == ASYNC_OVERSIZED_PATH_NAME
            }
            assertEquals(1, compactHistoryEntries.size, "The async result should be merged into history once.")
            assertEquals(stash.id, compactHistoryEntries.single().content.metadata["stashId"])
            assertTrue(compactHistoryEntries.single().content.text.length < 500)
            assertTrue(compactHistoryEntries.single().content.binaryContent.isEmpty())
            assertTrue(compactHistoryEntries.single().content.passPipeline)
            assertTrue(compactHistoryEntries.single().content.terminatePipeline)

            val rawHistoryEntry = station.rawTurnHistory.history.single {
                it.role == com.TTT.Context.ConverseRole.assistant &&
                    it.content.metadata["pathName"] == ASYNC_OVERSIZED_PATH_NAME
            }
            assertEquals(stash.id, rawHistoryEntry.content.metadata["stashId"])
            assertTrue(rawHistoryEntry.content.text.length < 500)
            assertTrue(rawHistoryEntry.content.binaryContent.isEmpty())
            assertTrue(rawHistoryEntry.content.passPipeline)
            assertTrue(rawHistoryEntry.content.terminatePipeline)

            val latest = station.getTaskState().latestContent
            assertNotNull(latest)
            assertEquals(stash.id, latest.metadata["stashId"])
            assertTrue(latest.text.length < 500)
            assertTrue(latest.binaryContent.isEmpty())
            assertTrue(latest.passPipeline)
            assertTrue(latest.terminatePipeline)

            val lastPathResult = station.getTaskState().lastPathResult
            assertNotNull(lastPathResult)
            assertEquals(stash.id, lastPathResult.metadata["stashId"])
            assertTrue(lastPathResult.text.length < 500)
            assertTrue(lastPathResult.binaryContent.isEmpty())
            assertTrue(lastPathResult.passPipeline)
            assertTrue(lastPathResult.terminatePipeline)
        }
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun oversizedMultiPathAsyncOutputIsStashedWhenHistoryAppendIsDisabled()
    {
        runBlocking {
            val station = PumpStation()
                .setDispatchAgent(Pipeline().apply {
                    add(ScriptedTestPipe(response = """{"paths":[{"pathName":"$ASYNC_OVERSIZED_PATH_NAME","inputData":{}}]}"""))
                })
                .setPathExecutionShape(PathExecutionShape.MultiPath)
                .setAsyncPathsAppendToTurnHistory(false)
                .setBlowoutThreshold(0.1)
                .setHistoryTransport(PumpStationHistoryTransport.ContextOnly)
            station.setTokenBudgetRecursive(TokenBudgetSettings(maxTokens = 1_000, contextWindowSize = 10_000))
            station.getTaskState().originalInput = MultimodalContent(text = "run the background path")
            val path = PathObject().apply {
                pathName = ASYNC_OVERSIZED_PATH_NAME
                pathDescription = "Returns a large native multimodal result."
                setRunsInBackground(true)
                setExecutionFunction { _, _, _, _ ->
                    MultimodalContent(text = ASYNC_OVERSIZED_OUTPUT).apply {
                        addBinary(byteArrayOf(4, 5, 6), "image/png", "async-result.png")
                        passPipeline = true
                        terminatePipeline = true
                    }
                }
            }
            station.addPath(path)
            station.P2PInit()

            station.runDispatchPhaseMulti()
            withTimeout(5_000) {
                while (station.pendingAsyncResultsInternal.isEmpty)
                {
                    delay(5)
                }
            }

            assertEquals(0, station.drainPendingAsyncResults(), "Disabled history append should not report a history merge.")

            val stash = station.getStashManifest().single()
            assertEquals(ASYNC_OVERSIZED_PATH_NAME, stash.sourcePath)
            val stashed = station.retrieveStash(stash.id)
            assertNotNull(stashed)
            assertEquals(ASYNC_OVERSIZED_OUTPUT, stashed.content.text)
            val image = stashed.content.binaryContent.single() as BinaryContent.Bytes
            assertContentEquals(byteArrayOf(4, 5, 6), image.data)
            assertEquals("image/png", image.mimeType)
            assertEquals("async-result.png", image.filename)
            assertTrue(stashed.content.passPipeline)
            assertTrue(stashed.content.terminatePipeline)

            assertTrue(station.turnHistory.history.none {
                it.content.metadata["pathName"] == ASYNC_OVERSIZED_PATH_NAME
            })
            assertTrue(station.rawTurnHistory.history.none {
                it.content.metadata["pathName"] == ASYNC_OVERSIZED_PATH_NAME
            })

            val latest = station.getTaskState().latestContent
            assertNotNull(latest)
            assertEquals(stash.id, latest.metadata["stashId"])
            assertTrue(latest.text.length < 500)
            assertTrue(latest.binaryContent.isEmpty())
            assertTrue(latest.passPipeline)
            assertTrue(latest.terminatePipeline)

            val lastPathResult = station.getTaskState().lastPathResult
            assertNotNull(lastPathResult)
            assertEquals(stash.id, lastPathResult.metadata["stashId"])
            assertTrue(lastPathResult.text.length < 500)
            assertTrue(lastPathResult.binaryContent.isEmpty())
            assertTrue(lastPathResult.passPipeline)
            assertTrue(lastPathResult.terminatePipeline)
        }
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun oversizedBinaryOnlyMultiPathAsyncOutputIsStashed()
    {
        runBlocking {
            val station = PumpStation()
                .setDispatchAgent(Pipeline().apply {
                    add(ScriptedTestPipe(response = """{"paths":[{"pathName":"$ASYNC_OVERSIZED_PATH_NAME","inputData":{}}]}"""))
                })
                .setPathExecutionShape(PathExecutionShape.MultiPath)
                .setBlowoutThreshold(0.1)
            station.setTokenBudgetRecursive(TokenBudgetSettings(maxTokens = 1_000, contextWindowSize = 10_000))
            val path = PathObject().apply {
                pathName = ASYNC_OVERSIZED_PATH_NAME
                pathDescription = "Returns a large binary-only result."
                setRunsInBackground(true)
                setExecutionFunction { _, _, _, _ ->
                    MultimodalContent().apply {
                        addBinary(ASYNC_OVERSIZED_BINARY, "application/octet-stream", "async-result.bin")
                    }
                }
            }
            station.addPath(path)
            station.P2PInit()

            station.runDispatchPhaseMulti()
            withTimeout(5_000) {
                while (station.pendingAsyncResultsInternal.isEmpty)
                {
                    delay(5)
                }
            }

            assertEquals(1, station.drainPendingAsyncResults())

            val stash = station.getStashManifest().single()
            assertTrue((stash.byteSize ?: 0L) >= ASYNC_OVERSIZED_BINARY.size.toLong())
            assertTrue((stash.tokenEstimate ?: 0) >= ASYNC_OVERSIZED_BINARY.size)
            val stashed = station.retrieveStash(stash.id)
            assertNotNull(stashed)
            val image = stashed.content.binaryContent.single() as BinaryContent.Bytes
            assertContentEquals(ASYNC_OVERSIZED_BINARY, image.data)
            assertEquals("application/octet-stream", image.mimeType)
            assertEquals("async-result.bin", image.filename)

            val latest = station.getTaskState().latestContent
            assertNotNull(latest)
            assertEquals(stash.id, latest.metadata["stashId"])
            assertTrue(latest.binaryContent.isEmpty())
        }
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun asyncPathCompletedDuringFinalizationIsIncludedInFinalOutput()
    {
        runBlocking {
            val station = PumpStation()
                .setDispatchAgent(Pipeline().apply {
                    add(ScriptedTestPipe(response = """{"paths":[{"pathName":"$ASYNC_OVERSIZED_PATH_NAME","inputData":{}}]}"""))
                })
                .setPathExecutionShape(PathExecutionShape.MultiPath)
                .setAsyncJobsScopedToStation(false)
                .setMemoryUpdateTimeoutMs(2_000)
            val pathRelease = CompletableDeferred<Unit>()
            val path = PathObject().apply {
                pathName = ASYNC_OVERSIZED_PATH_NAME
                pathDescription = "Completes while finalization waits for background work."
                setRunsInBackground(true)
                setExecutionFunction { _, _, _, _ ->
                    pathRelease.await()
                    MultimodalContent(text = "completed during finalization")
                }
            }
            station.addPath(path)
            station.P2PInit()
            station.runDispatchPhaseMulti()

            val releaseJob = CoroutineScope(Dispatchers.Default).launch {
                delay(400)
                pathRelease.complete(Unit)
                delay(100)
            }
            backgroundJobs += releaseJob
            assertTrue(station.pendingAsyncResultsInternal.isEmpty)

            val finalOutput = station.runFinalizationPhase()

            assertEquals("completed during finalization", finalOutput.text)
            assertEquals(ASYNC_OVERSIZED_PATH_NAME, finalOutput.metadata["pathName"])
            assertEquals("completed during finalization", station.getTaskState().lastPathResult?.text)
        }
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun canceledAsyncPathCannotEnqueueOutputAfterFiniteGraceExpires()
    {
        runBlocking {
            val station = PumpStation()
                .setDispatchAgent(Pipeline().apply {
                    add(ScriptedTestPipe(response = """{"paths":[{"pathName":"$ASYNC_OVERSIZED_PATH_NAME","inputData":{}}]}"""))
                })
                .setPathExecutionShape(PathExecutionShape.MultiPath)
                .setAsyncJobGracePeriodMs(50L)
            val started = CountDownLatch(1)
            val release = CountDownLatch(1)
            val path = PathObject().apply {
                pathName = ASYNC_OVERSIZED_PATH_NAME
                pathDescription = "Returns after the station's finite async grace period."
                setRunsInBackground(true)
                setExecutionFunction { _, _, _, _ ->
                    started.countDown()
                    release.await(5, TimeUnit.SECONDS)
                    MultimodalContent(text = ASYNC_OVERSIZED_OUTPUT)
                }
            }
            station.addPath(path)
            station.P2PInit()
            station.runDispatchPhaseMulti()
            assertTrue(started.await(2, TimeUnit.SECONDS))
            val scopeJob = station.asyncScope.coroutineContext[Job]
            assertNotNull(scopeJob)

            station.runFinalizationPhase()
            assertTrue(
                station.pendingAsyncResultsInternal.trySend(PendingTurnEntry(
                    seq = 999L,
                    turnIndex = 0,
                    pathName = ASYNC_OVERSIZED_PATH_NAME,
                    agentName = null,
                    source = "asyncPath",
                    result = MultimodalContent(text = "post-finalization probe")
                )).isFailure,
                "Finalization must seal the async result channel"
            )
            release.countDown()
            withTimeout(2_000) {
                while (scopeJob.children.any())
                {
                    delay(5)
                }
            }
            assertEquals(0, station.drainPendingAsyncResults())
            assertTrue(station.getStashManifest().isEmpty())
        }
    }

    @Test
    fun disablingOversizedOutputStashingKeepsAsyncContentInline()
    {
        runBlocking {
            val station = PumpStation()
                .setBlowoutThreshold(0.1)
            station.failurePolicy.stashOversizedOutputs = false
            station.setTokenBudgetRecursive(TokenBudgetSettings(maxTokens = 1_000, contextWindowSize = 10_000))
            val content = MultimodalContent(text = ASYNC_OVERSIZED_OUTPUT).apply {
                passPipeline = true
            }
            station.pendingAsyncResultsInternal.trySend(PendingTurnEntry(
                seq = 1,
                turnIndex = 0,
                pathName = ASYNC_OVERSIZED_PATH_NAME,
                agentName = null,
                source = "asyncPath",
                result = content,
                passPipeline = true
            ))

            assertEquals(1, station.drainPendingAsyncResults())

            assertTrue(station.getStashManifest().isEmpty())
            assertEquals(ASYNC_OVERSIZED_OUTPUT, station.getTaskState().latestContent?.text)
            assertEquals(ASYNC_OVERSIZED_OUTPUT, station.turnHistory.history.single().content.text)
        }
    }
}
