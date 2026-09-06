package com.TTT.Pipeline

import com.TTT.Pipe.MultimodalContent
import com.TTT.P2P.KillSwitch
import com.TTT.P2P.P2PInterface
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private class SessionStreamingFake : P2PInterface
{
    override var killSwitch: KillSwitch? = null
    private val callbacks = java.util.concurrent.CopyOnWriteArrayList<suspend (String) -> Unit>()

    override fun setStreamingCallbackRecursive(callback: suspend (String) -> Unit)
    {
        if (callbacks.none { it === callback }) callbacks.add(callback)
    }

    override fun removeStreamingCallbackRecursive(callback: suspend (String) -> Unit)
    {
        callbacks.removeIf { it === callback }
    }

    override fun supportsStreamingCallbackRemoval(): Boolean = true

    suspend fun emit(chunk: String)
    {
        callbacks.forEach { it(chunk) }
    }
}

private class LegacyStreamingFake : P2PInterface
{
    override var killSwitch: KillSwitch? = null
    private val callbacks = java.util.concurrent.CopyOnWriteArrayList<suspend (String) -> Unit>()

    override fun setStreamingCallbackRecursive(callback: suspend (String) -> Unit)
    {
        callbacks.add(callback)
    }

    fun callbackCount(): Int = callbacks.size
}

private class AbortTrackingFake : P2PInterface
{
    override var killSwitch: KillSwitch? = null
    var abortCount = 0

    override suspend fun abortRecursive()
    {
        abortCount++
    }
}

class PumpStationControlTraversalTest
{
    @Test
    fun deepestActiveRouteFollowsEveryForegroundChildAndFallsBackAfterCleanup()
    {
        val root = PumpStation()
        val child = PumpStation()
        val grandchild = PumpStation()
        root.setRunIdForTest("root")
        child.setRunIdForTest("child")
        grandchild.setRunIdForTest("grandchild")

        root.activateForegroundChild("research", child)
        child.activateForegroundChild("browser", grandchild)

        assertEquals(
            PumpStationControlRoute("grandchild", 2, listOf("research", "browser")),
            root.getActiveControlRoute()
        )

        child.clearForegroundChild("browser", grandchild)
        assertEquals(
            PumpStationControlRoute("child", 1, listOf("research")),
            root.getActiveControlRoute()
        )
        root.clearForegroundChild("research", child)
        assertEquals(PumpStationControlRoute("root", 0, emptyList()), root.getActiveControlRoute())
    }

    @Test
    fun blockingPathExecutionTracksAndClearsItsActualPumpStationChild()
    {
        runBlocking {
            val entered = CompletableDeferred<Unit>()
            val child = PumpStation()
                .setDispatchAgent(Pipeline())
                .setPreInitFunction { content, _ ->
                    entered.complete(Unit)
                    CompletableDeferred<Unit>().await()
                    content
                }
            val root = PumpStation()
            root.setRunIdForTest("root")
            val path = PathObject().apply {
                pathName = "nested"
                setInternalAgent(child)
            }

            val execution = async {
                path.execute(MultimodalContent(text = "input"), root, null, "")
            }
            entered.await()
            assertEquals(child.taskState.runId, root.getActiveControlRoute().targetRunId)

            root.steer(PumpStationPausePhase.BeforeJudge, "child control")
            assertEquals(
                "child control",
                child.steeringService.drainForPhase(PumpStationPausePhase.BeforeJudge).single().text
            )

            root.interrupt(PumpStationPausePhase.BeforeExit, "child interrupt")
            assertEquals(
                "child interrupt",
                child.interruptService.drainAllForPhase(PumpStationPausePhase.BeforeExit).single().text
            )

            execution.cancel()
            execution.join()
            assertEquals(PumpStationControlRoute("root", 0, emptyList()), root.getActiveControlRoute())
        }
    }

    @Test
    fun builderCreatedPumpStationIsTrackedWhileItsPathIsExecuting()
    {
        runBlocking {
            val entered = CompletableDeferred<Unit>()
            val child = PumpStation()
                .setDispatchAgent(Pipeline())
                .setPreInitFunction { content, _ ->
                    entered.complete(Unit)
                    CompletableDeferred<Unit>().await()
                    content
                }
            val root = PumpStation()
            root.setRunIdForTest("builder-root")
            val path = PathObject().apply { pathName = "builder" }
            val builderField = PathObject::class.java.getDeclaredField("agentBuilderFunction")
            builderField.isAccessible = true
            builderField.set(path, suspend { _: MutableList<Any>? -> child })

            val execution = async {
                path.execute(MultimodalContent(text = "input"), root, null, "")
            }
            entered.await()
            assertEquals(child.taskState.runId, root.getActiveControlRoute().targetRunId)

            execution.cancel()
            execution.join()
            assertEquals(PumpStationControlRoute("builder-root", 0, emptyList()), root.getActiveControlRoute())
        }
    }

    @Test
    fun asyncPathExecutionDoesNotStealTheInteractiveTarget()
    {
        runBlocking {
            val entered = CompletableDeferred<Unit>()
            val child = PumpStation()
                .setDispatchAgent(Pipeline())
                .setPreInitFunction { content, _ ->
                    entered.complete(Unit)
                    CompletableDeferred<Unit>().await()
                    content
                }
            val root = PumpStation()
            root.setRunIdForTest("async-root")
            val path = PathObject().apply {
                pathName = "background"
                setInternalAgent(child)
            }

            val execution = async {
                root.invokePathInternal(
                    path,
                    MultimodalContent(text = "input"),
                    registerAsInteractiveControlPath = false
                )
            }
            entered.await()
            assertEquals(PumpStationControlRoute("async-root", 0, emptyList()), root.getActiveControlRoute())

            execution.cancel()
            execution.join()
        }
    }

    @Test
    fun localModeDoesNotDescendAndCycleDetectionStopsSafely()
    {
        val root = PumpStation()
        val child = PumpStation()
        root.setRunIdForTest("root")
        child.setRunIdForTest("child")
        root.activateForegroundChild("child", child)
        child.activateForegroundChild("cycle", root)

        assertTrue(root.getActiveControlRoute().cycleDetected)
        root.setSteeringTargetMode(PumpStationControlTargetMode.Local)
        root.setInterruptTargetMode(PumpStationControlTargetMode.Local)
        assertEquals(PumpStationControlTargetMode.Local, root.getSteeringTargetMode())
        assertEquals(PumpStationControlTargetMode.Local, root.getInterruptTargetMode())

        runBlocking {
            root.steer(PumpStationPausePhase.BeforeJudge, "local")
            assertEquals(
                "local",
                root.steeringService.drainForPhase(PumpStationPausePhase.BeforeJudge).single().text
            )
            assertTrue(child.steeringService.drainForPhase(PumpStationPausePhase.BeforeJudge).isEmpty())
        }
    }

    @Test
    fun sessionReceivesRootAndNestedEventsWithMonotonicSequences()
    {
        val root = PumpStation()
        val child = PumpStation()
        root.setRunIdForTest("root")
        child.setRunIdForTest("child")
        val session = root.openSession("session")
        try
        {
            root.emitEventInternal(HarnessStarted(runId = "root", turnIndex = 0, originalInput = MultimodalContent(text = "x")))
            root.activateForegroundChild("research", child)
            child.emitEventInternal(HarnessResumed(runId = "child", turnIndex = 1, phase = PumpStationPhase.Judge))

            runBlocking {
                val first = session.updates.receive()
                val second = session.updates.receive()
                assertTrue(first.sequence < second.sequence)
                assertEquals(0, first.source.depth)
                assertEquals(listOf("research"), second.source.pathChain)
            }
        }
        finally
        {
            session.close()
        }
    }

    @Test
    fun sessionAttributesNestedForegroundStreamUpdates()
    {
        val root = PumpStation()
        val child = PumpStation()
        val sink = SessionStreamingFake()
        val developerChunks = mutableListOf<String>()
        val developerCallback: suspend (String) -> Unit = { developerChunks += it }
        child.addPath(PathObject().apply {
            pathName = "browser"
            setInternalAgent(sink)
        })
        child.setStreamingCallbackRecursive(developerCallback)
        root.activateForegroundChild("research", child)
        val session = root.openSession("stream-session")
        try
        {
            runBlocking {
                sink.emit("nested chunk")
                val update = session.updates.receive() as PumpStationSessionStreamUpdate
                assertEquals("nested chunk", update.chunk)
                assertEquals(1, update.source.depth)
                assertEquals(listOf("research"), update.source.pathChain)
            }
        }
        finally
        {
            session.close()
        }

        runBlocking { sink.emit("developer callback remains") }
        assertEquals(listOf("nested chunk", "developer callback remains"), developerChunks)
    }

    @Test
    fun sessionInheritsStreamingIntoBuilderChildrenAndAttributesGrandchildRoute()
    {
        runBlocking {
            val entered = CompletableDeferred<Unit>()
            val sink = SessionStreamingFake()
            val grandchild = PumpStation()
            grandchild.addPath(PathObject().apply {
                pathName = "stream"
                setInternalAgent(sink)
            })
            val child = PumpStation()
                .setDispatchAgent(Pipeline())
                .setPreInitFunction { content, station ->
                    station.activateForegroundChild("nested", grandchild)
                    entered.complete(Unit)
                    CompletableDeferred<Unit>().await()
                    content
                }
            val root = PumpStation()
            root.setRunIdForTest("builder-session-root")
            val path = PathObject().apply { pathName = "builder" }
            val builderField = PathObject::class.java.getDeclaredField("agentBuilderFunction")
            builderField.isAccessible = true
            builderField.set(path, suspend { _: MutableList<Any>? -> child })
            root.addPath(path)

            val session = root.openSession("builder-session")
            try
            {
                val execution = async {
                    path.execute(MultimodalContent(text = "input"), root, null, "")
                }
                entered.await()
                sink.emit("builder grandchild chunk")

                val update = session.updates.receive() as PumpStationSessionStreamUpdate
                assertEquals("builder grandchild chunk", update.chunk)
                assertEquals(2, update.source.depth)
                assertEquals(listOf("builder", "nested"), update.source.pathChain)

                execution.cancel()
                execution.join()
            }
            finally
            {
                session.close()
            }
        }
    }

    @Test
    fun sessionDoesNotPropagateIntoLegacyP2PWithoutCallbackRemovalSupport()
    {
        val legacy = LegacyStreamingFake()
        val root = PumpStation()
        root.addPath(PathObject().apply {
            pathName = "legacy"
            setInternalAgent(legacy)
        })

        val session = root.openSession("legacy-session")
        try
        {
            assertEquals(0, legacy.callbackCount())
        }
        finally
        {
            session.close()
        }
    }

    @Test
    fun sessionControlsBypassTheExecutionMutexWhileExecutionIsBlocked()
    {
        runBlocking {
            val entered = CompletableDeferred<Unit>()
            val root = PumpStation()
                .setDispatchAgent(Pipeline())
                .setPreInitFunction { content, _ ->
                    entered.complete(Unit)
                    CompletableDeferred<Unit>().await()
                    content
                }
            root.setRunIdForTest("session-root")
            val session = root.openSession("control-session")
            try
            {
                val execution = async { session.execute(MultimodalContent(text = "input")) }
                entered.await()

                session.steerNow("while blocked")
                assertEquals(
                    "while blocked",
                    root.steeringService.drainForBoundary(PumpStationPausePhase.BeforeExit).single().text
                )

                execution.cancel()
                execution.join()
            }
            finally
            {
                session.close()
            }
        }
    }

    @Test
    fun sessionAbortCancelsBlockedRootExecution()
    {
        runBlocking {
            val entered = CompletableDeferred<Unit>()
            val root = PumpStation()
                .setDispatchAgent(Pipeline())
                .setPreInitFunction { content, _ ->
                    entered.complete(Unit)
                    CompletableDeferred<Unit>().await()
                    content
                }
            val session = root.openSession("abort-session")
            try
            {
                val execution = async { session.execute(MultimodalContent(text = "input")) }
                entered.await()

                session.abort()
                execution.join()

                assertTrue(execution.isCancelled)
            }
            finally
            {
                session.close()
            }
        }
    }

    @Test
    fun abortTraversesPathOwnedAgents()
    {
        val fake = AbortTrackingFake()
        val root = PumpStation()
        root.addPath(PathObject().apply {
            pathName = "abortable"
            setInternalAgent(fake)
        })

        runBlocking { root.abortRecursive() }

        assertEquals(1, fake.abortCount)
    }

    @Test
    fun dslTargetModesSurvivePathBuilderPromotion()
    {
        val station = pumpStation("routing-dsl") {
            dispatchAgent = Pipeline()
            steeringPolicy { targetMode = PumpStationControlTargetMode.Local }
            interruptPolicy { targetMode = PumpStationControlTargetMode.Local }
            path("noop") {
                setExecutionFunction { content, _, _, _ -> content }
            }
        }

        assertEquals(PumpStationControlTargetMode.Local, station.getSteeringTargetMode())
        assertEquals(PumpStationControlTargetMode.Local, station.getInterruptTargetMode())
    }
}
