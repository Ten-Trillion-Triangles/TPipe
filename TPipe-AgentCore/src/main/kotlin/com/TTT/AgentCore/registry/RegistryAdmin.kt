package com.TTT.AgentCore.registry

import aws.sdk.kotlin.services.agentregistrycontrol.AgentRegistryControlClient
import aws.sdk.kotlin.services.agentregistrycontrol.model.*

/** Typed control-plane operations for registry lifecycle management. */
class Registry(private val client: AgentRegistryControlClient)
{
    /** Create a registry. */
    suspend fun create(request: CreateRegistryRequest): CreateRegistryResponse = client.createRegistry(request)

    /** Get a registry by ID or ARN. */
    suspend fun get(request: GetRegistryRequest): GetRegistryResponse = client.getRegistry(request)

    /** List registries visible to the caller. */
    suspend fun list(request: ListRegistriesRequest = ListRegistriesRequest {}): ListRegistriesResponse =
        client.listRegistries(request)

    /** Update a registry using the SDK's patch semantics. */
    suspend fun update(request: UpdateRegistryRequest): UpdateRegistryResponse = client.updateRegistry(request)

    /** Delete a registry. */
    suspend fun delete(request: DeleteRegistryRequest): DeleteRegistryResponse = client.deleteRegistry(request)
}

/** Typed control-plane operations for registry-record lifecycle management. */
class RegistryRecord(private val client: AgentRegistryControlClient)
{
    /** Create a registry record. */
    suspend fun create(request: CreateRegistryRecordRequest): CreateRegistryRecordResponse =
        client.createRegistryRecord(request)

    /** Get a registry record by registry and record ID. */
    suspend fun get(request: GetRegistryRecordRequest): GetRegistryRecordResponse =
        client.getRegistryRecord(request)

    /** List records in a registry. */
    suspend fun list(request: ListRegistryRecordsRequest): ListRegistryRecordsResponse =
        client.listRegistryRecords(request)

    /** Update a registry record using the SDK's patch semantics. */
    suspend fun update(request: UpdateRegistryRecordRequest): UpdateRegistryRecordResponse =
        client.updateRegistryRecord(request)

    /** Delete a registry record. */
    suspend fun delete(request: DeleteRegistryRecordRequest): DeleteRegistryRecordResponse =
        client.deleteRegistryRecord(request)

    /** Submit a registry record for approval. */
    suspend fun submitForApproval(
        request: SubmitRegistryRecordForApprovalRequest
    ): SubmitRegistryRecordForApprovalResponse = client.submitRegistryRecordForApproval(request)

    /** Submit a registry record for approval by registry and record ID. */
    suspend fun submitForApproval(
        registryId: String,
        recordId: String
    ): SubmitRegistryRecordForApprovalResponse = submitForApproval(
        SubmitRegistryRecordForApprovalRequest {
            this.registryId = registryId
            this.recordId = recordId
        }
    )

    /** Update a registry record's curation status. */
    suspend fun updateStatus(
        request: UpdateRegistryRecordStatusRequest
    ): UpdateRegistryRecordStatusResponse = client.updateRegistryRecordStatus(request)

    /** Update a registry record's curation status by registry and record ID. */
    suspend fun updateStatus(
        registryId: String,
        recordId: String,
        status: RegistryRecordStatus,
        statusReason: String? = null
    ): UpdateRegistryRecordStatusResponse = updateStatus(
        UpdateRegistryRecordStatusRequest {
            this.registryId = registryId
            this.recordId = recordId
            this.status = status
            this.statusReason = statusReason
        }
    )
}

/**
 * Facade combining registry and registry-record administration.
 *
 * The nested [registry] and [registryRecord] facades keep the two resource
 * lifecycles distinct while the direct methods retain a compact compatibility
 * surface for callers that used a single administrator.
 */
class AgentRegistryAdmin(client: AgentRegistryControlClient)
{
    /** Registry lifecycle facade. */
    val registry = Registry(client)

    /** Registry-record lifecycle facade. */
    val registryRecord = RegistryRecord(client)

    /** Compatibility plural for the record facade. */
    val records: RegistryRecord
        get() = registryRecord

    /** Create a registry. */
    suspend fun createRegistry(request: CreateRegistryRequest): CreateRegistryResponse = registry.create(request)

    /** Get a registry. */
    suspend fun getRegistry(request: GetRegistryRequest): GetRegistryResponse = registry.get(request)

    /** List registries. */
    suspend fun listRegistries(request: ListRegistriesRequest = ListRegistriesRequest {}): ListRegistriesResponse =
        registry.list(request)

    /** Update a registry. */
    suspend fun updateRegistry(request: UpdateRegistryRequest): UpdateRegistryResponse = registry.update(request)

    /** Delete a registry. */
    suspend fun deleteRegistry(request: DeleteRegistryRequest): DeleteRegistryResponse = registry.delete(request)

    /** Create a registry record. */
    suspend fun createRegistryRecord(request: CreateRegistryRecordRequest): CreateRegistryRecordResponse =
        registryRecord.create(request)

    /** Get a registry record. */
    suspend fun getRegistryRecord(request: GetRegistryRecordRequest): GetRegistryRecordResponse =
        registryRecord.get(request)

    /** List registry records. */
    suspend fun listRegistryRecords(request: ListRegistryRecordsRequest): ListRegistryRecordsResponse =
        registryRecord.list(request)

    /** Update a registry record. */
    suspend fun updateRegistryRecord(request: UpdateRegistryRecordRequest): UpdateRegistryRecordResponse =
        registryRecord.update(request)

    /** Delete a registry record. */
    suspend fun deleteRegistryRecord(request: DeleteRegistryRecordRequest): DeleteRegistryRecordResponse =
        registryRecord.delete(request)

    /** Submit a registry record for approval. */
    suspend fun submitRegistryRecordForApproval(
        request: SubmitRegistryRecordForApprovalRequest
    ): SubmitRegistryRecordForApprovalResponse = registryRecord.submitForApproval(request)

    /** Submit a registry record for approval by registry and record ID. */
    suspend fun submitRegistryRecordForApproval(
        registryId: String,
        recordId: String
    ): SubmitRegistryRecordForApprovalResponse = registryRecord.submitForApproval(registryId, recordId)

    /** Update a registry record's curation status. */
    suspend fun updateRegistryRecordStatus(
        request: UpdateRegistryRecordStatusRequest
    ): UpdateRegistryRecordStatusResponse = registryRecord.updateStatus(request)

    /** Update a registry record's curation status by registry and record ID. */
    suspend fun updateRegistryRecordStatus(
        registryId: String,
        recordId: String,
        status: RegistryRecordStatus,
        statusReason: String? = null
    ): UpdateRegistryRecordStatusResponse =
        registryRecord.updateStatus(registryId, recordId, status, statusReason)
}

/** Compatibility name for the combined Agent Registry administrator. */
typealias RegistryAdmin = AgentRegistryAdmin

/** Build Agent Registry administration from the separate client bundle. */
fun AgentRegistryClients.registryAdmin(): AgentRegistryAdmin = AgentRegistryAdmin(agentRegistryControl)
