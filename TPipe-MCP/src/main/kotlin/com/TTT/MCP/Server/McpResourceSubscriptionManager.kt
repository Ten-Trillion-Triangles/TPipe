package com.TTT.MCP.Server

import com.TTT.PipeContextProtocol.PcpContext
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.ResourceUpdatedNotification
import io.modelcontextprotocol.kotlin.sdk.types.ResourceUpdatedNotificationParams
import kotlinx.coroutines.runBlocking
import java.nio.file.FileSystems
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class McpResourceSubscriptionManager(
    private val server: Server,
    private val pcpContext: PcpContext
) {
    data class ResourceSubscription(
        val subscriptionId: String,
        val uri: String,
        val watchService: java.nio.file.WatchService? = null,
        val watchKey: java.nio.file.WatchKey? = null,
        val subscribedAt: Long = System.currentTimeMillis(),
        val parentPath: Path? = null
    )

    data class Subscription(
        val subscriptionId: String,
        val uri: String,
        val subscribedAt: Long
    )

    var onResourceChangedCallback: ((uri: String, eventType: String) -> Unit)? = null

    private val subscriptions = ConcurrentHashMap<String, ResourceSubscription>()
    private val executor: ExecutorService = Executors.newCachedThreadPool()
    private val isRunning = AtomicBoolean(true)
    private val eventProcessorStarted = AtomicBoolean(false)

    init {
        startEventProcessor()
    }

    @Synchronized
    private fun startEventProcessor() {
        if (eventProcessorStarted.compareAndSet(false, true)) {
            executor.submit { processWatchEvents() }
        }
    }

    private fun processWatchEvents() {
        while (isRunning.get()) {
            try {
                val watchKeysToProcess = mutableListOf<Pair<String, java.nio.file.WatchKey>>()

                subscriptions.values.forEach { subscription ->
                    subscription.watchKey?.let { key ->
                        if (key.isValid) {
                            watchKeysToProcess.add(subscription.subscriptionId to key)
                        }
                    }
                }

                watchKeysToProcess.forEach { (subscriptionId, watchKey) ->
                    val events = watchKey.pollEvents()
                    val subscription = subscriptions[subscriptionId]

                    events.forEach { event ->
                        val kind = event.kind()

                        if (kind == StandardWatchEventKinds.OVERFLOW) {
                            return@forEach
                        }

                        subscription?.let { sub ->
                            val eventType = when (kind) {
                                StandardWatchEventKinds.ENTRY_CREATE -> "CREATE"
                                StandardWatchEventKinds.ENTRY_MODIFY -> "MODIFY"
                                StandardWatchEventKinds.ENTRY_DELETE -> "DELETE"
                                else -> return@forEach
                            }

                            onResourceChangedCallback?.invoke(sub.uri, eventType)
                            sendResourceUpdatedNotification(sub.uri)
                        }
                    }

                    watchKey.reset()
                }

                Thread.sleep(50)
            } catch (e: Exception) {
                if (isRunning.get()) {
                    Thread.sleep(100)
                }
            }
        }
    }

    fun subscribeResource(uri: String): String {
        val subscriptionId = UUID.randomUUID().toString()
        val currentTime = System.currentTimeMillis()

        if (uri.startsWith("file://")) {
            val path = parseFilePath(uri)
            if (path != null) {
                try {
                    val watchService = FileSystems.getDefault().newWatchService()
                    val parentPath = path.parent
                    val fileName = path.fileName

                    val watchKey = parentPath.register(
                        watchService,
                        StandardWatchEventKinds.ENTRY_CREATE,
                        StandardWatchEventKinds.ENTRY_MODIFY,
                        StandardWatchEventKinds.ENTRY_DELETE
                    )

                    val subscription = ResourceSubscription(
                        subscriptionId = subscriptionId,
                        uri = uri,
                        watchService = watchService,
                        watchKey = watchKey,
                        subscribedAt = currentTime,
                        parentPath = parentPath
                    )
                    subscriptions[subscriptionId] = subscription
                    return subscriptionId
                } catch (e: Exception) {
                    val subscription = ResourceSubscription(
                        subscriptionId = subscriptionId,
                        uri = uri,
                        watchService = null,
                        watchKey = null,
                        subscribedAt = currentTime,
                        parentPath = null
                    )
                    subscriptions[subscriptionId] = subscription
                    return subscriptionId
                }
            }
        }

        val subscription = ResourceSubscription(
            subscriptionId = subscriptionId,
            uri = uri,
            watchService = null,
            watchKey = null,
            subscribedAt = currentTime,
            parentPath = null
        )
        subscriptions[subscriptionId] = subscription
        return subscriptionId
    }

    fun unsubscribeResource(subscriptionId: String): Boolean {
        val subscription = subscriptions.remove(subscriptionId) ?: return false

        try {
            subscription.watchKey?.cancel()
            subscription.watchService?.close()
        } catch (e: Exception) {
        }

        return true
    }

    fun listSubscriptions(): List<Subscription> {
        return subscriptions.values.map { sub ->
            Subscription(
                subscriptionId = sub.subscriptionId,
                uri = sub.uri,
                subscribedAt = sub.subscribedAt
            )
        }
    }

    fun setResourceChangedHandler(callback: (uri: String, eventType: String) -> Unit) {
        this.onResourceChangedCallback = callback
    }

    private fun parseFilePath(uri: String): Path? {
        return try {
            val pathString = uri.removePrefix("file://")
            Path.of(pathString)
        } catch (e: Exception) {
            null
        }
    }

    private fun sendResourceUpdatedNotification(uri: String) {
        try {
            runBlocking {
                server.sessions.forEach { (sessionId, _) ->
                    server.sendResourceUpdated(
                        sessionId = sessionId,
                        notification = ResourceUpdatedNotification(
                            ResourceUpdatedNotificationParams(uri = uri)
                        )
                    )
                }
            }
        } catch (e: Exception) {
        }
    }

    fun close() {
        isRunning.set(false)

        subscriptions.values.forEach { sub ->
            try {
                sub.watchKey?.cancel()
                sub.watchService?.close()
            } catch (e: Exception) {
            }
        }

        subscriptions.clear()
        executor.shutdown()
    }
}