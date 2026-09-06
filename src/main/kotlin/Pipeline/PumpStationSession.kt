package com.TTT.Pipeline

import com.TTT.Pipe.MultimodalContent
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.IdentityHashMap
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/**
 * Retained UI/runtime facade for one PumpStation execution.
 *
 * Execution is serialized per session, while controls intentionally bypass the
 * execution mutex so a caller can steer or interrupt a blocked execution.
 */
class PumpStationSession internal constructor(
    private val root: PumpStation,
    val sessionId: String = UUID.randomUUID().toString()
) : AutoCloseable
{
    private data class Attachment(
        val station: PumpStation,
        val pathChain: List<String>,
        val eventSubscription: AutoCloseable,
        val childSubscription: AutoCloseable
    )

    private val updateChannel = Channel<PumpStationSessionUpdate>(Channel.UNLIMITED)
    private val executionMutex = Mutex()
    private val sequence = AtomicLong(0L)
    private val updatePublicationLock = Any()
    private val attachmentLock = Any()
    private val attachments = IdentityHashMap<PumpStation, Attachment>()
    private val attachingStations = java.util.Collections.newSetFromMap(
        IdentityHashMap<PumpStation, Boolean>()
    )
    private val closed = java.util.concurrent.atomic.AtomicBoolean(false)

    private val streamingCallback: suspend (String) -> Unit = { chunk ->
        if (!closed.get())
        {
            val route = root.getActiveControlRoute()
            publish { nextSequence ->
                PumpStationSessionStreamUpdate(
                    sequence = nextSequence,
                    sessionId = sessionId,
                    source = PumpStationSessionSource(
                        runId = route.targetRunId,
                        depth = route.depth,
                        pathChain = route.pathChain.toList()
                    ),
                    chunk = chunk
                )
            }
        }
    }

    /** Non-blocking producer/consumer boundary for session updates. */
    val updates: ReceiveChannel<PumpStationSessionUpdate> get() = updateChannel

    init
    {
        root.setStreamingCallbackRecursive(streamingCallback)
        attachStation(root, emptyList())
    }

    /** Execute content while preserving one in-flight execution per session. */
    suspend fun execute(content: MultimodalContent): MultimodalContent =
        executionMutex.withLock { root.executeLocal(content) }

    /** Route an explicit-phase steer through the root's configured target mode. */
    suspend fun steer(phase: PumpStationPausePhase, content: MultimodalContent)
    {
        root.steer(phase, content)
    }

    /** Route a text explicit-phase steer through the root. */
    suspend fun steer(phase: PumpStationPausePhase, text: String)
    {
        root.steer(phase, text)
    }

    /** Route a next-boundary steer through the root. */
    suspend fun steerNow(content: MultimodalContent)
    {
        root.steerNow(content)
    }

    /** Route a text next-boundary steer through the root. */
    suspend fun steerNow(text: String)
    {
        root.steerNow(text)
    }

    /** Route an explicit-phase interrupt through the root's configured target mode. */
    suspend fun interrupt(phase: PumpStationPausePhase, content: MultimodalContent)
    {
        root.interrupt(phase, content)
    }

    /** Route a text explicit-phase interrupt through the root. */
    suspend fun interrupt(phase: PumpStationPausePhase, text: String)
    {
        root.interrupt(phase, text)
    }

    /** Route a next-boundary interrupt through the root. */
    suspend fun interruptNow(content: MultimodalContent)
    {
        root.interruptNow(content)
    }

    /** Route a text next-boundary interrupt through the root. */
    suspend fun interruptNow(text: String)
    {
        root.interruptNow(text)
    }

    /** Return the current value-only foreground route. */
    fun currentControlRoute(): PumpStationControlRoute = root.getActiveControlRoute()

    /** Abort the root and every owned descendant without taking the execution mutex. */
    suspend fun abort()
    {
        root.abortRecursive()
    }

    /**
     * Detach only this session's observers and callback. Closing does not abort
     * or cancel PumpStation execution.
     */
    override fun close()
    {
        if (!closed.compareAndSet(false, true)) return
        synchronized(attachmentLock)
        {
            val current = attachments.values.toList()
            attachments.clear()
            current.forEach {
                it.eventSubscription.close()
                it.childSubscription.close()
                it.station.removeStreamingCallbackRecursive(streamingCallback)
            }
            root.removeStreamingCallbackRecursive(streamingCallback)
        }
        updateChannel.close()
    }

    private fun publish(factory: (Long) -> PumpStationSessionUpdate)
    {
        synchronized(updatePublicationLock)
        {
            if (!closed.get())
            {
                updateChannel.trySend(factory(sequence.incrementAndGet()))
            }
        }
    }

    private fun attachStation(
        station: PumpStation,
        pathChain: List<String>,
        expectedParent: PumpStation? = null,
        expectedPathName: String? = null
    ): Boolean
    {
        val reserved = synchronized(attachmentLock)
        {
            if (
                closed.get() ||
                attachments.containsKey(station) ||
                attachingStations.contains(station) ||
                !isExpectedActive(expectedParent, expectedPathName, station)
            )
            {
                false
            }
            else
            {
                attachingStations.add(station)
                true
            }
        }
        if (!reserved) return false

        var eventSubscription: AutoCloseable? = null
        var childSubscription: AutoCloseable? = null
        var attached = false
        try
        {
            eventSubscription = station.addEventObserver { event ->
                val source = PumpStationSessionSource(
                    runId = event.runId,
                    depth = pathChain.size,
                    pathChain = pathChain.toList()
                )
                publish { nextSequence ->
                    PumpStationSessionEventUpdate(
                        sequence = nextSequence,
                        sessionId = sessionId,
                        source = source,
                        event = event
                    )
                }
            }
            childSubscription = station.addActiveChildListener { pathName, child ->
                if (child != null)
                {
                    val shouldAttach = synchronized(attachmentLock)
                    {
                        if (closed.get() || !station.isActiveForegroundChild(pathName, child))
                        {
                            false
                        }
                        else
                        {
                            child.setStreamingCallbackRecursive(streamingCallback)
                            true
                        }
                    }
                    if (shouldAttach)
                    {
                        synchronized(attachmentLock)
                        {
                            val childAttached = attachStation(child, pathChain + pathName, station, pathName)
                            if (
                                !childAttached &&
                                (closed.get() || !station.isActiveForegroundChild(pathName, child))
                            )
                            {
                                child.removeStreamingCallbackRecursive(streamingCallback)
                            }
                        }
                    }
                }
                else
                {
                    detachChildren(pathChain + pathName)
                }
            }
            synchronized(attachmentLock)
            {
                attachingStations.remove(station)
                if (
                    !closed.get() &&
                    !attachments.containsKey(station) &&
                    isExpectedActive(expectedParent, expectedPathName, station)
                )
                {
                    attachments[station] = Attachment(
                        station = station,
                        pathChain = pathChain,
                        eventSubscription = requireNotNull(eventSubscription),
                        childSubscription = requireNotNull(childSubscription)
                    )
                    attached = true
                }
            }
            return attached
        }
        finally
        {
            synchronized(attachmentLock) { attachingStations.remove(station) }
            if (!attached)
            {
                eventSubscription?.close()
                childSubscription?.close()
            }
        }
    }

    private fun isExpectedActive(
        expectedParent: PumpStation?,
        expectedPathName: String?,
        station: PumpStation
    ): Boolean = expectedParent == null ||
        (expectedPathName != null && expectedParent.isActiveForegroundChild(expectedPathName, station))

    private fun detachChildren(prefix: List<String>)
    {
        synchronized(attachmentLock)
        {
            val matches = attachments.values.filter {
                it.pathChain.size >= prefix.size && it.pathChain.take(prefix.size) == prefix
            }
            matches.forEach { attachments.remove(it.station) }
            matches.forEach {
                it.eventSubscription.close()
                it.childSubscription.close()
                it.station.removeStreamingCallbackRecursive(streamingCallback)
            }
        }
    }
}
