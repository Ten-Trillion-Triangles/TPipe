package com.TTT.Pipeline

import com.TTT.Pipe.MultimodalContent
import com.TTT.P2P.P2PInterface
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.IdentityHashMap
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/** Marks the callback owned by [PumpStationSession] for compatibility-aware propagation. */
internal interface SessionStreamingCallbackMarker

/**
 * Propagate a session callback only to implementations that expose the
 * callback-specific removal contract. Ordinary developer callbacks retain the
 * legacy propagation behaviour, including propagation to third-party P2P
 * implementations that predate the removal method.
 */
internal fun P2PInterface.setStreamingCallbackForSession(callback: suspend (String) -> Unit)
{
    if (
        callback !is SessionStreamingCallbackMarker ||
        supportsStreamingCallbackRemoval()
    )
    {
        setStreamingCallbackRecursive(callback)
    }
}

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
    private class StationStreamingCallback(
        private val session: PumpStationSession,
        private val station: PumpStation,
        private val pathChain: List<String>
    ) : suspend (String) -> Unit, SessionStreamingCallbackMarker
    {
        override suspend fun invoke(chunk: String)
        {
            session.publishStream(station, pathChain, chunk)
        }
    }

    private data class Attachment(
        val station: PumpStation,
        val pathChain: List<String>,
        val streamingCallback: suspend (String) -> Unit,
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

    private fun callbackFor(station: PumpStation, pathChain: List<String>): suspend (String) -> Unit =
        StationStreamingCallback(this, station, pathChain.toList())

    private fun publishStream(station: PumpStation, pathChain: List<String>, chunk: String)
    {
        if (!closed.get())
        {
            publish { nextSequence ->
                PumpStationSessionStreamUpdate(
                    sequence = nextSequence,
                    sessionId = sessionId,
                    source = PumpStationSessionSource(
                        runId = station.getTaskState().runId,
                        depth = pathChain.size,
                        pathChain = pathChain.toList()
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
        val rootCallback = callbackFor(root, emptyList())
        root.setStreamingCallbackRecursive(rootCallback)
        attachStation(root, emptyList(), streamingCallback = rootCallback)
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
                it.station.removeStreamingCallbackRecursive(it.streamingCallback)
            }
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
        expectedPathName: String? = null,
        streamingCallback: suspend (String) -> Unit = callbackFor(station, pathChain)
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
                    val childPathChain = pathChain + pathName
                    val childCallback = callbackFor(child, childPathChain)
                    val shouldAttach = synchronized(attachmentLock)
                    {
                        if (
                            closed.get() ||
                            !station.isActiveForegroundChild(pathName, child) ||
                            attachments.containsKey(child) ||
                            attachingStations.contains(child)
                        )
                        {
                            false
                        }
                        else
                        {
                            child.removeStreamingCallbackRecursive(streamingCallback)
                            child.setStreamingCallbackRecursive(childCallback)
                            true
                        }
                    }
                    if (shouldAttach)
                    {
                        synchronized(attachmentLock)
                        {
                            val childAttached = attachStation(
                                child,
                                childPathChain,
                                station,
                                pathName,
                                childCallback
                            )
                            if (!childAttached)
                            {
                                child.removeStreamingCallbackRecursive(childCallback)
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
                        streamingCallback = streamingCallback,
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
                it.station.removeStreamingCallbackRecursive(it.streamingCallback)
            }
        }
    }
}
