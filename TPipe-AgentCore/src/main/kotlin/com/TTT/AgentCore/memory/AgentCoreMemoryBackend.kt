package com.TTT.AgentCore.memory

import aws.sdk.kotlin.services.bedrockagentcore.model.BatchCreateMemoryRecordsRequest
import aws.sdk.kotlin.services.bedrockagentcore.model.BatchCreateMemoryRecordsResponse
import aws.sdk.kotlin.services.bedrockagentcore.model.BatchDeleteMemoryRecordsRequest
import aws.sdk.kotlin.services.bedrockagentcore.model.BatchDeleteMemoryRecordsResponse
import aws.sdk.kotlin.services.bedrockagentcore.model.GetMemoryRecordResponse
import aws.sdk.kotlin.services.bedrockagentcore.model.ListMemoryRecordsRequest
import aws.sdk.kotlin.services.bedrockagentcore.model.ListMemoryRecordsResponse
import aws.sdk.kotlin.services.bedrockagentcore.model.MemoryContent
import aws.sdk.kotlin.services.bedrockagentcore.model.MemoryRecordCreateInput
import aws.sdk.kotlin.services.bedrockagentcore.model.MemoryRecordDeleteInput
import aws.sdk.kotlin.services.bedrockagentcore.model.MemoryRecordSummary
import aws.sdk.kotlin.services.bedrockagentcore.model.GetMemoryRecordRequest
import com.TTT.AgentCore.AgentCoreClients
import com.TTT.Context.ContextWindow
import com.TTT.Context.Persistence.ContextPersistenceBackend
import com.TTT.Context.TodoList
import com.TTT.Util.serialize
import aws.smithy.kotlin.runtime.time.Instant
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.int
import kotlinx.serialization.json.long

/** AgentCore Memory identifiers and namespace layout for exact TPipe values. */
data class AgentCoreMemoryConfig(
    val memoryId: String,
    val instanceId: String = memoryId,
    val contextNamespace: String = "/tpipe/$instanceId/context",
    val todoNamespace: String = "/tpipe/$instanceId/todo",
    val contextIndexNamespace: String = "/tpipe/$instanceId/index/context",
    val todoIndexNamespace: String = "/tpipe/$instanceId/index/todo"
)

/**
 * Exact ContextBank persistence backed by AgentCore Memory records.
 *
 * TPipe values are serialized, gzip-compressed, base64-encoded, checksummed,
 * and stored as opaque records. This adapter never asks AgentCore Memory to
 * summarize or semantically reinterpret a ContextWindow or TodoList.
 */
class AgentCoreMemoryBackend internal constructor(
    private val data: AgentCoreMemoryDataClient,
    private val config: AgentCoreMemoryConfig
) : ContextPersistenceBackend {
    /** Create an AgentCore Memory backend using the configured AWS data client. */
    constructor(clients: AgentCoreClients, config: AgentCoreMemoryConfig) : this(
        AwsAgentCoreMemoryDataClient(clients.data),
        config
    )

    override val id: String = "agentcore-memory:${config.memoryId}"
    private val keyLocks = ConcurrentHashMap<String, Mutex>()
    private val generationSequence = AtomicLong(0L)
    private val writerId = UUID.randomUUID().toString()

    override suspend fun getContextWindow(key: String): ContextWindow? =
        readExact(key, config.contextNamespace, ContextWindow.serializer())

    override suspend fun putContextWindow(key: String, window: ContextWindow) {
        writeExact(key, config.contextNamespace, RecordKind.CONTEXT, window.version, serialize(window))
        writeIndex(key, config.contextIndexNamespace, RecordKind.CONTEXT)
    }

    override suspend fun deleteContextWindow(key: String): Boolean =
        deleteExact(key, config.contextNamespace, config.contextIndexNamespace)

    override suspend fun listContextWindowKeys(): List<String> =
        listIndexKeys(config.contextIndexNamespace, RecordKind.CONTEXT)

    override suspend fun getTodoList(key: String): TodoList? =
        readExact(key, config.todoNamespace, TodoList.serializer())

    override suspend fun putTodoList(key: String, todoList: TodoList) {
        writeExact(key, config.todoNamespace, RecordKind.TODO, todoList.version, serialize(todoList))
        writeIndex(key, config.todoIndexNamespace, RecordKind.TODO)
    }

    override suspend fun deleteTodoList(key: String): Boolean =
        deleteExact(key, config.todoNamespace, config.todoIndexNamespace)

    override suspend fun listTodoListKeys(): List<String> =
        listIndexKeys(config.todoIndexNamespace, RecordKind.TODO)

    private suspend fun <T> readExact(
        key: String,
        namespace: String,
        serializer: kotlinx.serialization.KSerializer<T>
    ): T? {
        val records = recordsForKey(namespace, key)
        if (records.isEmpty()) return null

        val envelopes = records.mapNotNull { record ->
            readRecordText(record)?.let { text ->
                runCatching { decodeEnvelope(text) }.getOrNull()?.let { envelope ->
                    StoredEnvelope(
                        envelope = envelope,
                        serviceCreatedAt = record.createdAt.toString(),
                        recordId = record.memoryRecordId.orEmpty()
                    )
                }
            }
        }
        val manifests = envelopes
            .filter { it.envelope.kind == RecordKind.MANIFEST }
            .sortedWith(
                // Generation is the monotonic write ordering and must take
                // precedence over the caller's mutable value revision. The
                // service timestamp and generation id only break ties between
                // replicas that selected the same logical generation.
                compareByDescending<StoredEnvelope> { it.envelope.generation }
                    .thenByDescending { it.serviceCreatedAt }
                    .thenByDescending { it.envelope.generationId }
                    .thenByDescending { it.envelope.revision }
                    .thenByDescending { it.recordId }
            )
        if(manifests.isEmpty()) return null

        return manifests.firstNotNullOfOrNull { manifestRecord ->
            val manifest = manifestRecord.envelope
            val chunks = envelopes
                .filter {
                it.envelope.kind == RecordKind.CHUNK &&
                        it.envelope.generation == manifest.generation &&
                        it.envelope.generationId == manifest.generationId
                }
                .distinctBy { it.envelope.chunkIndex }
                .sortedBy { it.envelope.chunkIndex }
            val expectedChunkIndexes = if(manifest.chunkCount >= 0)
            {
                (0 until manifest.chunkCount).toList()
            }
            else
            {
                emptyList()
            }
            if(chunks.size != manifest.chunkCount || chunks.map { it.envelope.chunkIndex } != expectedChunkIndexes)
            {
                return@firstNotNullOfOrNull null
            }
            val encodedPayload = chunks.joinToString(separator = "") { it.envelope.payload }
            val serializedPayload = runCatching { AgentCoreMemoryCodec.decode(encodedPayload) }
                .getOrNull()
                ?: return@firstNotNullOfOrNull null
            val checksumMatches = AgentCoreMemoryCodec.checksum(serializedPayload) == manifest.checksum ||
                // The first adapter release hashed the encoded payload. Keep
                // those records readable while new records use the canonical
                // checksum of the serialized TPipe value.
                (manifest.codec == LEGACY_CODEC &&
                    AgentCoreMemoryCodec.checksum(encodedPayload) == manifest.checksum)
            if(!checksumMatches)
            {
                return@firstNotNullOfOrNull null
            }
            // Treat a payload that cannot be decoded by the Core serializer as
            // incomplete too, so a prior committed generation remains a valid
            // fallback after a corrupt or incompatible newest write.
            runCatching { AgentCoreMemoryJson.decode(serializedPayload, serializer) }.getOrNull()
        }
    }

    private suspend fun writeExact(
        key: String,
        namespace: String,
        kind: RecordKind,
        revision: Long,
        serialized: String
    ) {
        keyLocks.computeIfAbsent(key) { Mutex() }.withLock {
            val recordNamespace = namespaceFor(namespace, key)
            val previousRecords = recordsForKey(namespace, key)
            val encodedPayload = AgentCoreMemoryCodec.encode(serialized)
            val chunkPayloads = AgentCoreMemoryCodec.chunks(encodedPayload)
            require(chunkPayloads.size <= MAX_CHUNKS) {
                "AgentCore Memory value for '$key' exceeds the $MAX_CHUNKS chunk limit."
            }
            val checksum = AgentCoreMemoryCodec.checksum(serialized)
            val latestStoredGeneration = previousRecords.maxOfOrNull { record ->
                readRecordText(record)?.let { text -> runCatching { decodeEnvelope(text).generation }.getOrNull() } ?: 0L
            } ?: 0L
            val generation = nextGeneration(latestStoredGeneration)
            val generationId = nextGenerationId()
            val chunks = chunkPayloads.mapIndexed { index, payload ->
                envelopeRecord(
                    namespace = recordNamespace,
                    envelope = AgentCoreMemoryRecordEnvelope(
                        key = key,
                        kind = RecordKind.CHUNK,
                        revision = revision,
                        generation = generation,
                        generationId = generationId,
                        chunkIndex = index,
                        chunkCount = chunkPayloads.size,
                        checksum = checksum,
                        payload = payload
                    )
                )
            }
            // The manifest is the commit marker. A failed chunk batch leaves
            // the previous manifest valid and therefore still readable.
            createRecords(chunks)
            createRecords(
                listOf(
                    envelopeRecord(
                        namespace = recordNamespace,
                        envelope = AgentCoreMemoryRecordEnvelope(
                            key = key,
                            kind = RecordKind.MANIFEST,
                            revision = revision,
                            generation = generation,
                            generationId = generationId,
                            chunkIndex = -1,
                            chunkCount = chunkPayloads.size,
                            checksum = checksum,
                            payload = ""
                        )
                    )
                )
            )
            deleteRecords(previousRecords)
        }
    }

    private suspend fun writeIndex(key: String, namespace: String, kind: RecordKind) {
        val existing = listRecords(namespace).filter { record ->
            readRecordText(record)?.let { text ->
                runCatching { decodeEnvelope(text).key == key }.getOrDefault(false)
            } == true
        }
        val latestStoredGeneration = existing.maxOfOrNull { record ->
            readRecordText(record)?.let { text -> runCatching { decodeEnvelope(text).generation }.getOrNull() } ?: 0L
        } ?: 0L
        val generation = nextGeneration(latestStoredGeneration)
        val generationId = nextGenerationId()
        createRecords(
            listOf(
                envelopeRecord(
                    namespace = namespace,
                    envelope = AgentCoreMemoryRecordEnvelope(
                        key = key,
                        kind = kind,
                        revision = 0L,
                        generation = generation,
                        generationId = generationId,
                        chunkIndex = 0,
                        chunkCount = 0,
                        checksum = "",
                        payload = ""
                    )
                )
            )
        )
        deleteRecords(existing)
    }

    private suspend fun deleteExact(key: String, namespace: String, indexNamespace: String): Boolean {
        return keyLocks.computeIfAbsent(key) { Mutex() }.withLock {
            val records = recordsForKey(namespace, key)
            val indexRecords = listOf(indexNamespace, historicalIndexNamespace(indexNamespace))
                .distinct()
                .flatMap { candidateNamespace -> listRecords(candidateNamespace) }
                .filter { record ->
                    readRecordText(record)?.let { text ->
                        runCatching { decodeEnvelope(text).key == key }.getOrDefault(false)
                    } == true
                }
            val recordsToDelete = (records + indexRecords).distinctBy { it.memoryRecordId }
            if (recordsToDelete.isEmpty()) return@withLock false
            deleteRecords(recordsToDelete)
            true
        }
    }

    private suspend fun listIndexKeys(namespace: String, kind: RecordKind): List<String> {
        val namespaces = listOf(namespace, historicalIndexNamespace(namespace)).distinct()
        return namespaces.flatMap { indexNamespace -> listRecords(indexNamespace) }.mapNotNull { record ->
            readRecordText(record)?.let { text -> runCatching { decodeEnvelope(text) }.getOrNull()?.let { envelope ->
                envelope.takeIf { it.kind == kind }?.key
            }
            }
        }.distinct()
    }

    private suspend fun listRecords(namespace: String): List<MemoryRecordSummary> {
        val records = mutableListOf<MemoryRecordSummary>()
        var nextToken: String? = null
        do {
            val response = data.listMemoryRecords(
                ListMemoryRecordsRequest {
                    memoryId = config.memoryId
                    this.namespace = namespace
                    maxResults = PAGE_SIZE
                    this.nextToken = nextToken
                }
            )
            records += response.memoryRecordSummaries
            nextToken = response.nextToken
        } while (!nextToken.isNullOrBlank())
        return records
    }

    /**
     * List only records whose envelope belongs to [key].
     *
     * AgentCore Memory treats a namespace filter as a prefix. The trailing
     * separator on new namespaces prevents most collisions, while the
     * envelope check also protects reads and deletes of legacy records written
     * before that separator was introduced.
     */
    private suspend fun recordsForKey(namespace: String, key: String): List<MemoryRecordSummary> {
        val records = mutableListOf<MemoryRecordSummary>()
        listOf(
            namespaceFor(namespace, key),
            legacyNamespaceFor(namespace, key),
            historicalNamespaceFor(namespace, key),
            historicalNamespaceFor(namespace, key)?.let { "$it/" }
        )
            .distinct()
            .filterNotNull()
            .forEach { candidateNamespace -> records += listRecords(candidateNamespace) }
        return records
            .distinctBy { it.memoryRecordId }
            .filter { record ->
                readRecordText(record)?.let { text ->
                    runCatching { decodeEnvelope(text).key == key }.getOrDefault(false)
                } == true
            }
    }

    private suspend fun readRecordText(record: MemoryRecordSummary): String? {
        record.content?.asTextOrNull()?.let { return it }
        val recordId = record.memoryRecordId
        val memoryRecord = data.getMemoryRecord(
            GetMemoryRecordRequest {
                memoryId = config.memoryId
                memoryRecordId = recordId
            }
        ).memoryRecord ?: return null
        return memoryRecord.content?.asTextOrNull()
    }

    private suspend fun createRecords(records: List<MemoryRecordCreateInput>) {
        records.chunked(BATCH_SIZE).forEach { batch ->
            val response = data.batchCreateMemoryRecords(
                BatchCreateMemoryRecordsRequest {
                    memoryId = config.memoryId
                    this.records = batch
                    clientToken = UUID.randomUUID().toString()
                }
            )
            if(response.failedRecords.isNotEmpty()) {
                throw AgentCoreMemoryBackendException(
                    operation = "batch create",
                    failures = response.failedRecords.map { failed ->
                        AgentCoreMemoryRecordFailure(
                            recordId = failed.memoryRecordId,
                            status = failed.status.toString(),
                            message = failed.errorMessage
                        )
                    }
                )
            }
        }
    }

    private suspend fun deleteRecords(summaries: List<MemoryRecordSummary>) {
        val snapshots = summaries.mapNotNull { snapshotRecord(it) }
        val ids = summaries.mapNotNull { it.memoryRecordId }.distinct()
        if(ids.isEmpty()) return

        try
        {
            ids.chunked(BATCH_SIZE).forEach { batch ->
                val response = data.batchDeleteMemoryRecords(
                    BatchDeleteMemoryRecordsRequest {
                        memoryId = config.memoryId
                        records = batch.map { id -> MemoryRecordDeleteInput { memoryRecordId = id } }
                    }
                )
                if(response.failedRecords.isNotEmpty()) {
                    throw AgentCoreMemoryBackendException(
                        operation = "batch delete",
                        failures = response.failedRecords.map { failed ->
                            AgentCoreMemoryRecordFailure(
                                recordId = failed.memoryRecordId,
                                status = failed.status.toString(),
                                message = failed.errorMessage
                            )
                        }
                    )
                }
            }
        }
        catch(exception: CancellationException)
        {
            throw exception
        }
        catch(exception: Exception)
        {
            // AgentCore batch deletes are not transactional. Restore the
            // deleted record snapshots so a failed cleanup cannot turn the
            // last committed generation into an unreadable partial set.
            runCatching { createRecords(snapshots) }
                .exceptionOrNull()
                ?.let { restoreFailure -> exception.addSuppressed(restoreFailure) }
            throw exception
        }
    }

    private suspend fun snapshotRecord(record: MemoryRecordSummary): MemoryRecordCreateInput? {
        val contentText = readRecordText(record) ?: return null
        return MemoryRecordCreateInput {
            content = MemoryContent.Text(contentText)
            memoryStrategyId = record.memoryStrategyId
            metadata = record.metadata
            namespaces = record.namespaces
            requestIdentifier = UUID.randomUUID().toString()
            timestamp = record.createdAt
        }
    }

    private fun envelopeRecord(namespace: String, envelope: AgentCoreMemoryRecordEnvelope): MemoryRecordCreateInput =
        MemoryRecordCreateInput {
            val contentText = buildJsonObject {
                put("schemaVersion", envelope.schemaVersion)
                put("key", envelope.key)
                put("kind", envelope.kind.name)
                put("revision", envelope.revision)
                put("generation", envelope.generation)
                put("generationId", envelope.generationId)
                put("chunkIndex", envelope.chunkIndex)
                put("chunkCount", envelope.chunkCount)
                put("checksum", envelope.checksum)
                put("codec", envelope.codec)
                put("createdAt", envelope.createdAt)
                put("payload", envelope.payload)
            }.toString()
            require(contentText.length <= MAX_RECORD_CHARS) {
                "AgentCore Memory record exceeds the $MAX_RECORD_CHARS character limit."
            }
            content = MemoryContent.Text(contentText)
            namespaces = listOf(namespace)
            requestIdentifier = UUID.randomUUID().toString()
            timestamp = Instant(java.time.Instant.now())
        }

    private fun namespaceFor(namespace: String, key: String): String =
        "${namespace.trimEnd('/')}/${keyHash(key)}/"

    private fun legacyNamespaceFor(namespace: String, key: String): String =
        "$namespace/${Base64.getUrlEncoder().withoutPadding().encodeToString(key.toByteArray())}"

    private fun historicalNamespaceFor(namespace: String, key: String): String? {
        val historicalRoot = when (namespace) {
            "/tpipe/${config.instanceId}/context" -> "tpipe/context"
            "/tpipe/${config.instanceId}/todo" -> "tpipe/todo"
            else -> null
        }
        return historicalRoot?.let {
            "$it/${Base64.getUrlEncoder().withoutPadding().encodeToString(key.toByteArray())}"
        }
    }

    private fun historicalIndexNamespace(namespace: String): String = when (namespace) {
        "/tpipe/${config.instanceId}/index/context" -> "tpipe/index/context"
        "/tpipe/${config.instanceId}/index/todo" -> "tpipe/index/todo"
        else -> namespace
    }

    private fun keyHash(key: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(
            MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
        )

    private fun decodeEnvelope(text: String): AgentCoreMemoryRecordEnvelope {
        val json = Json.parseToJsonElement(text).jsonObject
        return AgentCoreMemoryRecordEnvelope(
            schemaVersion = json["schemaVersion"]?.jsonPrimitive?.int ?: 1,
            key = json["key"]?.jsonPrimitive?.content ?: error("AgentCore Memory record has no key."),
            kind = RecordKind.valueOf(json["kind"]?.jsonPrimitive?.content ?: error("Record has no kind.")),
            revision = json["revision"]?.jsonPrimitive?.long ?: 0L,
            generation = json["generation"]?.jsonPrimitive?.long ?: 0L,
            generationId = json["generationId"]?.jsonPrimitive?.content.orEmpty(),
            chunkIndex = json["chunkIndex"]?.jsonPrimitive?.int ?: 0,
            chunkCount = json["chunkCount"]?.jsonPrimitive?.int ?: 0,
            checksum = json["checksum"]?.jsonPrimitive?.content.orEmpty(),
            codec = json["codec"]?.jsonPrimitive?.content ?: LEGACY_CODEC,
            createdAt = json["createdAt"]?.jsonPrimitive?.content.orEmpty(),
            payload = json["payload"]?.jsonPrimitive?.content.orEmpty()
        )
    }

    private enum class RecordKind {
        CONTEXT,
        TODO,
        MANIFEST,
        CHUNK
    }

    private data class AgentCoreMemoryRecordEnvelope(
        val schemaVersion: Int = 1,
        val key: String,
        val kind: RecordKind,
        val revision: Long,
        val generation: Long = 0L,
        val generationId: String = "",
        val chunkIndex: Int,
        val chunkCount: Int,
        val checksum: String,
        val codec: String = CODEC,
        val createdAt: String = java.time.Instant.now().toString(),
        val payload: String
    )

    private fun nextGeneration(minimumPreviousGeneration: Long = 0L): Long =
        generationSequence.updateAndGet { current ->
            maxOf(current, minimumPreviousGeneration, System.currentTimeMillis()) + 1L
        }

    private fun nextGenerationId(): String = "$writerId-${UUID.randomUUID()}"

    private companion object {
        const val CODEC = "gzip+base64+sha256-serialized"
        const val LEGACY_CODEC = "gzip+base64"
        const val PAGE_SIZE = 100
        const val BATCH_SIZE = 100
        const val MAX_RECORD_CHARS = 16_000
        const val MAX_CHUNKS = 12_000
    }
    private data class StoredEnvelope(
        val envelope: AgentCoreMemoryRecordEnvelope,
        val serviceCreatedAt: String,
        val recordId: String
    )
}

/** Narrow AgentCore data-plane seam used by the exact-memory adapter and fakes. */
internal interface AgentCoreMemoryDataClient {
    suspend fun batchCreateMemoryRecords(
        request: BatchCreateMemoryRecordsRequest
    ): BatchCreateMemoryRecordsResponse

    suspend fun batchDeleteMemoryRecords(
        request: BatchDeleteMemoryRecordsRequest
    ): BatchDeleteMemoryRecordsResponse

    suspend fun getMemoryRecord(request: aws.sdk.kotlin.services.bedrockagentcore.model.GetMemoryRecordRequest): GetMemoryRecordResponse

    suspend fun listMemoryRecords(request: ListMemoryRecordsRequest): ListMemoryRecordsResponse
}

private class AwsAgentCoreMemoryDataClient(
    private val delegate: aws.sdk.kotlin.services.bedrockagentcore.BedrockAgentCoreClient
) : AgentCoreMemoryDataClient {
    override suspend fun batchCreateMemoryRecords(
        request: BatchCreateMemoryRecordsRequest
    ): BatchCreateMemoryRecordsResponse = delegate.batchCreateMemoryRecords(request)

    override suspend fun batchDeleteMemoryRecords(
        request: BatchDeleteMemoryRecordsRequest
    ): BatchDeleteMemoryRecordsResponse = delegate.batchDeleteMemoryRecords(request)

    override suspend fun getMemoryRecord(
        request: aws.sdk.kotlin.services.bedrockagentcore.model.GetMemoryRecordRequest
    ): GetMemoryRecordResponse = delegate.getMemoryRecord(request)

    override suspend fun listMemoryRecords(
        request: ListMemoryRecordsRequest
    ): ListMemoryRecordsResponse = delegate.listMemoryRecords(request)
}
