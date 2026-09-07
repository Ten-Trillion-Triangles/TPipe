package com.TTT.AgentCore.registry

import aws.sdk.kotlin.services.agentregistry.AgentRegistryClient
import aws.sdk.kotlin.services.agentregistry.model.*
import aws.smithy.kotlin.runtime.content.Document

/**
 * Data-plane access to approved Agent Registry records.
 *
 * Batch-get responses intentionally preserve the service's per-record errors;
 * a missing or unauthorized record is not promoted to a whole-request failure.
 */
class AgentRegistryDiscovery(private val client: AgentRegistryClient)
{
    /** Search approved records with a caller-built SDK request. */
    suspend fun search(request: SearchDiscoverableRegistryRecordsRequest): SearchDiscoverableRegistryRecordsResponse =
        client.searchDiscoverableRegistryRecords(request)

    /** Search one registry using a natural-language query. */
    suspend fun search(
        registryId: String,
        searchQuery: String,
        maxResults: Int? = null,
        filters: Document? = null
    ): SearchDiscoverableRegistryRecordsResponse
    {
        require(registryId.isNotBlank()) { "registryId must not be blank." }
        require(searchQuery.length in 1..256) { "searchQuery must contain 1 to 256 characters." }
        require(maxResults == null || maxResults in 1..20) { "maxResults must be between 1 and 20." }

        return search(
            SearchDiscoverableRegistryRecordsRequest {
                registryIds = listOf(registryId)
                this.searchQuery = searchQuery
                this.maxResults = maxResults
                this.filters = filters
            }
        )
    }

    /** List approved records with a caller-built SDK request. */
    suspend fun list(request: ListDiscoverableRegistryRecordsRequest): ListDiscoverableRegistryRecordsResponse =
        client.listDiscoverableRegistryRecords(request)

    /** List one registry's approved records with optional paging and filters. */
    suspend fun list(
        registryId: String,
        maxResults: Int? = null,
        nextToken: String? = null,
        filters: List<RegistryRecordFilter>? = null
    ): ListDiscoverableRegistryRecordsResponse
    {
        require(registryId.isNotBlank()) { "registryId must not be blank." }
        require(maxResults == null || maxResults in 1..100) { "maxResults must be between 1 and 100." }

        return list(
            ListDiscoverableRegistryRecordsRequest {
                this.registryId = registryId
                this.maxResults = maxResults
                this.nextToken = nextToken
                this.filters = filters
            }
        )
    }

    /** Batch-get approved records with a caller-built SDK request. */
    suspend fun batchGet(
        request: BatchGetDiscoverableRegistryRecordRequest
    ): BatchGetDiscoverableRegistryRecordResponse = client.batchGetDiscoverableRegistryRecord(request)

    /**
     * Batch-get approved records from one registry.
     *
     * The returned response may contain both records and per-record errors.
     * Callers should inspect [BatchGetDiscoverableRegistryRecordResponse.errors]
     * before treating the operation as complete.
     */
    suspend fun batchGet(
        registryId: String,
        recordIds: List<String>
    ): BatchGetDiscoverableRegistryRecordResponse
    {
        require(registryId.isNotBlank()) { "registryId must not be blank." }
        require(recordIds.isNotEmpty() && recordIds.size <= 100) {
            "recordIds must contain between 1 and 100 IDs."
        }
        require(recordIds.all { it.isNotBlank() }) { "recordIds must not contain blank IDs." }

        return batchGet(
            BatchGetDiscoverableRegistryRecordRequest {
                entries = listOf(
                    RegistryRecordsEntry {
                        this.registryId = registryId
                        this.recordIds = recordIds
                    }
                )
            }
        )
    }

    /** Batch-get records and expose successes and individual failures together. */
    suspend fun batchGetResult(
        registryId: String,
        recordIds: List<String>
    ): AgentRegistryBatchGetResult = batchGet(registryId, recordIds).asBatchGetResult()
}

/** A non-throwing view of the records and per-record errors in a batch-get response. */
data class AgentRegistryBatchGetResult(
    /** Records returned successfully. */
    val records: List<RegistryRecordSummary>,
    /** Records the service could not return. */
    val errors: List<BatchGetDiscoverableRegistryRecordError>
)
{
    /** Whether at least one requested record failed while another succeeded. */
    val isPartialFailure: Boolean
        get() = records.isNotEmpty() && errors.isNotEmpty()

    /** Whether any requested record failed. */
    val hasFailures: Boolean
        get() = errors.isNotEmpty()

    /** Whether every returned item is an error. */
    val isCompleteFailure: Boolean
        get() = records.isEmpty() && errors.isNotEmpty()
}

/** Whether this batch response contains both records and per-record failures. */
val BatchGetDiscoverableRegistryRecordResponse.isPartialFailure: Boolean
    get() = registryRecords.isNotEmpty() && errors.isNotEmpty()

/** Convert the SDK response without discarding partial failures. */
fun BatchGetDiscoverableRegistryRecordResponse.asBatchGetResult(): AgentRegistryBatchGetResult =
    AgentRegistryBatchGetResult(registryRecords, errors)

/** Build Agent Registry discovery access from the separate client bundle. */
fun AgentRegistryClients.registryDiscovery(): AgentRegistryDiscovery = AgentRegistryDiscovery(agentRegistry)
