package com.TTT.AgentCore.memory

import aws.sdk.kotlin.services.bedrockagentcore.BedrockAgentCoreClient
import aws.sdk.kotlin.services.bedrockagentcore.model.CreateEventRequest
import aws.sdk.kotlin.services.bedrockagentcore.model.CreateEventResponse
import aws.sdk.kotlin.services.bedrockagentcore.model.GetMemoryRecordRequest
import aws.sdk.kotlin.services.bedrockagentcore.model.GetMemoryRecordResponse
import aws.sdk.kotlin.services.bedrockagentcore.model.IngestDataRequest
import aws.sdk.kotlin.services.bedrockagentcore.model.IngestDataResponse
import aws.sdk.kotlin.services.bedrockagentcore.model.ListMemoryRecordsRequest
import aws.sdk.kotlin.services.bedrockagentcore.model.ListMemoryRecordsResponse
import aws.sdk.kotlin.services.bedrockagentcore.model.RetrieveMemoryRecordsRequest
import aws.sdk.kotlin.services.bedrockagentcore.model.RetrieveMemoryRecordsResponse
import com.TTT.AgentCore.AgentCoreClients

/**
 * Explicit AgentCore semantic-memory API.
 *
 * This is separate from [AgentCoreMemoryBackend]: semantic retrieval is an
 * application feature, while ContextBank persistence must remain exact.
 *
 * @param client AgentCore data-plane client used for semantic operations.
 */
class AgentCoreSemanticMemory(private val client: BedrockAgentCoreClient)
{
    /** Ingest data directly into long-term semantic memory.
     *
     * This intentionally remains separate from [createEvent]: AgentCore's
     * ingestion operation has different long-term-memory semantics.
     *
     * @param request Ingestion request using the AWS Smithy document model.
     * @return The service response.
     */
    suspend fun ingestData(request: IngestDataRequest): IngestDataResponse = client.ingestData(request)

    /** Create an AgentCore Memory event for service-managed extraction.
     *
     * @param request Event request.
     * @return The service response.
     */
    suspend fun createEvent(request: CreateEventRequest): CreateEventResponse = client.createEvent(request)

    /** Retrieve semantically relevant memory records.
     *
     * @param request Retrieval request.
     * @return The service response.
     */
    suspend fun retrieveMemoryRecords(request: RetrieveMemoryRecordsRequest): RetrieveMemoryRecordsResponse =
        client.retrieveMemoryRecords(request)

    /** List records using AgentCore's exact list/pagination API.
     *
     * @param request List request.
     * @return The service response.
     */
    suspend fun listMemoryRecords(request: ListMemoryRecordsRequest): ListMemoryRecordsResponse =
        client.listMemoryRecords(request)

    /** Read one raw AgentCore Memory record.
     *
     * @param request Record request.
     * @return The service response.
     */
    suspend fun getMemoryRecord(request: GetMemoryRecordRequest): GetMemoryRecordResponse =
        client.getMemoryRecord(request)
}

/** Convenience factory for applications that already own [AgentCoreClients]. */
fun AgentCoreClients.semanticMemory(): AgentCoreSemanticMemory = AgentCoreSemanticMemory(data)
