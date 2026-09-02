package com.TTT.AgentCore.memory

import aws.sdk.kotlin.services.bedrockagentcore.model.BatchCreateMemoryRecordsRequest
import aws.sdk.kotlin.services.bedrockagentcore.model.BatchCreateMemoryRecordsResponse
import aws.sdk.kotlin.services.bedrockagentcore.model.BatchDeleteMemoryRecordsRequest
import aws.sdk.kotlin.services.bedrockagentcore.model.BatchDeleteMemoryRecordsResponse
import aws.sdk.kotlin.services.bedrockagentcore.model.GetMemoryRecordRequest
import aws.sdk.kotlin.services.bedrockagentcore.model.GetMemoryRecordResponse
import aws.sdk.kotlin.services.bedrockagentcore.model.ListMemoryRecordsRequest
import aws.sdk.kotlin.services.bedrockagentcore.model.ListMemoryRecordsResponse
import aws.sdk.kotlin.services.bedrockagentcore.model.MemoryRecordOutput
import aws.sdk.kotlin.services.bedrockagentcore.model.MemoryRecordStatus
import aws.sdk.kotlin.services.bedrockagentcore.model.MemoryRecordSummary
import com.TTT.Context.ContextWindow
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class AgentCoreMemoryBackendTest
{
    @Test
    fun newestGenerationWinsWhenReplacementHasLowerRevision()
    {
        runBlocking {
            val data = FakeAgentCoreMemoryDataClient()
            val backend = backend(data)

            backend.putContextWindow("key", ContextWindow().apply { version = 10 })
            data.failDeletes = true
            assertFailsWith<AgentCoreMemoryBackendException> {
                backend.putContextWindow("key", ContextWindow().apply {
                    contextElements += "new value"
                    version = 1
                })
            }
            data.failDeletes = false

            val newest = backend.getContextWindow("key") ?: error("new record was not readable")
            assertEquals(listOf("new value"), newest.contextElements.toList())
        }
    }

    @Test
    fun partialDeleteFailureIsSurfacedAndDoesNotReportSuccess()
    {
        runBlocking {
            val data = FakeAgentCoreMemoryDataClient()
            val backend = backend(data)
            backend.putContextWindow("key", ContextWindow().apply {
                contextElements += "value"
            })
            assertNotNull(backend.getContextWindow("key"))

            data.failDeletes = true
            assertFailsWith<AgentCoreMemoryBackendException> {
                backend.deleteContextWindow("key")
            }
            data.failDeletes = false

            assertNotNull(backend.getContextWindow("key"))
        }
    }

    @Test
    fun partialDeleteFailureRestoresCommittedRecordSet()
    {
        runBlocking {
            val data = FakeAgentCoreMemoryDataClient()
            val backend = backend(data)
            backend.putContextWindow("key", ContextWindow().apply {
                contextElements += "value"
            })

            data.deleteSomeRecordsThenFail = true
            assertFailsWith<AgentCoreMemoryBackendException> {
                backend.deleteContextWindow("key")
            }
            data.deleteSomeRecordsThenFail = false

            assertEquals(listOf("value"), backend.getContextWindow("key")?.contextElements?.toList())
        }
    }

    @Test
    fun newestIncompleteGenerationDoesNotHideOlderCompleteValue()
    {
        runBlocking {
            val data = FakeAgentCoreMemoryDataClient()
            val backend = backend(data)
            backend.putContextWindow("key", ContextWindow().apply {
                contextElements += "old value"
            })

            data.dropNextCreateRecord = true
            data.failDeletes = true
            assertFailsWith<AgentCoreMemoryBackendException> {
                backend.putContextWindow("key", ContextWindow().apply {
                    contextElements += "incomplete value"
                })
            }
            data.failDeletes = false

            val retained = backend.getContextWindow("key") ?: error("old record was not readable")
            assertEquals(listOf("old value"), retained.contextElements.toList())
        }
    }

    @Test
    fun namespacePrefixDoesNotCrossReadOrDeleteBoundaries()
    {
        runBlocking {
            val data = FakeAgentCoreMemoryDataClient()
            val backend = backend(data)
            backend.putContextWindow("ab", ContextWindow().apply {
                contextElements += "short key"
            })
            backend.putContextWindow("ab ", ContextWindow().apply {
                contextElements += "longer key"
            })

            assertEquals(listOf("short key"), backend.getContextWindow("ab")?.contextElements?.toList())
            assertEquals(listOf("longer key"), backend.getContextWindow("ab ")?.contextElements?.toList())

            assertEquals(true, backend.deleteContextWindow("ab"))
            assertNotNull(backend.getContextWindow("ab "))
        }
    }

    private fun backend(data: FakeAgentCoreMemoryDataClient): AgentCoreMemoryBackend =
        AgentCoreMemoryBackend(
            data,
            AgentCoreMemoryConfig(memoryId = "test-memory")
        )

    private class FakeAgentCoreMemoryDataClient : AgentCoreMemoryDataClient
    {
        private val records = linkedMapOf<String, MemoryRecordSummary>()
        private var nextId = 0
        var failDeletes = false
        var deleteSomeRecordsThenFail = false
        var dropNextCreateRecord = false

        override suspend fun batchCreateMemoryRecords(
            request: BatchCreateMemoryRecordsRequest
        ): BatchCreateMemoryRecordsResponse
        {
            val recordsToCreate = if(dropNextCreateRecord)
            {
                dropNextCreateRecord = false
                emptyList()
            }
            else
            {
                request.records.orEmpty()
            }
            recordsToCreate.forEach { input ->
                val id = "record-${nextId++}"
                records[id] = MemoryRecordSummary {
                    memoryRecordId = id
                    memoryStrategyId = "strategy"
                    content = input.content
                    namespaces = input.namespaces
                    createdAt = input.timestamp
                }
            }
            return BatchCreateMemoryRecordsResponse {
                failedRecords = emptyList()
                successfulRecords = emptyList()
            }
        }

        override suspend fun batchDeleteMemoryRecords(
            request: BatchDeleteMemoryRecordsRequest
        ): BatchDeleteMemoryRecordsResponse
        {
            if(deleteSomeRecordsThenFail)
            {
                val requested = request.records.orEmpty()
                requested.dropLast(1).forEach { input -> records.remove(input.memoryRecordId) }
                return BatchDeleteMemoryRecordsResponse {
                    failedRecords = requested.takeLast(1).map { input ->
                        MemoryRecordOutput {
                            memoryRecordId = input.memoryRecordId
                            status = MemoryRecordStatus.Failed
                            errorMessage = "delete failed after partial success"
                        }
                    }
                    successfulRecords = emptyList()
                }
            }
            if(failDeletes)
            {
                return BatchDeleteMemoryRecordsResponse {
                    failedRecords = request.records.orEmpty().map { input ->
                        MemoryRecordOutput {
                            memoryRecordId = input.memoryRecordId
                            status = MemoryRecordStatus.Failed
                            errorMessage = "delete failed"
                        }
                    }
                    successfulRecords = emptyList()
                }
            }

            request.records.orEmpty().forEach { input -> records.remove(input.memoryRecordId) }
            return BatchDeleteMemoryRecordsResponse {
                failedRecords = emptyList()
                successfulRecords = emptyList()
            }
        }

        override suspend fun getMemoryRecord(request: GetMemoryRecordRequest): GetMemoryRecordResponse =
            GetMemoryRecordResponse {}

        override suspend fun listMemoryRecords(request: ListMemoryRecordsRequest): ListMemoryRecordsResponse =
            ListMemoryRecordsResponse {
                memoryRecordSummaries = records.values.filter { summary ->
                    summary.namespaces.any { namespace -> namespace.startsWith(request.namespace.orEmpty()) }
                }
            }
    }
}
