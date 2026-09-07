package com.TTT.AgentCore.registry

import aws.sdk.kotlin.services.agentregistry.AgentRegistryClient
import aws.sdk.kotlin.services.agentregistry.model.BatchGetDiscoverableRegistryRecordError
import aws.sdk.kotlin.services.agentregistry.model.BatchGetDiscoverableRegistryRecordErrorCode
import aws.sdk.kotlin.services.agentregistry.model.BatchGetDiscoverableRegistryRecordResponse
import aws.sdk.kotlin.services.agentregistry.model.ListDiscoverableRegistryRecordsResponse
import aws.sdk.kotlin.services.agentregistry.model.RegistryRecordFilter
import aws.sdk.kotlin.services.agentregistry.model.RegistryRecordFilterName
import aws.sdk.kotlin.services.agentregistry.model.RegistryRecordSummary
import aws.sdk.kotlin.services.agentregistry.model.RecordType as DiscoveryRecordType
import aws.sdk.kotlin.services.agentregistry.model.SearchDiscoverableRegistryRecordsRequest
import aws.sdk.kotlin.services.agentregistry.model.SearchDiscoverableRegistryRecordsResponse
import aws.sdk.kotlin.services.agentregistrycontrol.AgentRegistryControlClient
import aws.sdk.kotlin.services.agentregistrycontrol.model.CreateRegistryRecordRequest
import aws.sdk.kotlin.services.agentregistrycontrol.model.CreateRegistryRecordResponse
import aws.sdk.kotlin.services.agentregistrycontrol.model.CreateRegistryRequest
import aws.sdk.kotlin.services.agentregistrycontrol.model.CreateRegistryResponse
import aws.sdk.kotlin.services.agentregistrycontrol.model.DeleteRegistryRecordRequest
import aws.sdk.kotlin.services.agentregistrycontrol.model.DeleteRegistryRecordResponse
import aws.sdk.kotlin.services.agentregistrycontrol.model.DeleteRegistryRequest
import aws.sdk.kotlin.services.agentregistrycontrol.model.DeleteRegistryResponse
import aws.sdk.kotlin.services.agentregistrycontrol.model.GetRegistryRecordRequest
import aws.sdk.kotlin.services.agentregistrycontrol.model.GetRegistryRecordResponse
import aws.sdk.kotlin.services.agentregistrycontrol.model.GetRegistryRequest
import aws.sdk.kotlin.services.agentregistrycontrol.model.GetRegistryResponse
import aws.sdk.kotlin.services.agentregistrycontrol.model.ListRegistryRecordsRequest
import aws.sdk.kotlin.services.agentregistrycontrol.model.ListRegistryRecordsResponse
import aws.sdk.kotlin.services.agentregistrycontrol.model.ListRegistriesRequest
import aws.sdk.kotlin.services.agentregistrycontrol.model.ListRegistriesResponse
import aws.sdk.kotlin.services.agentregistrycontrol.model.RecordType
import aws.sdk.kotlin.services.agentregistrycontrol.model.RegistryRecordStatus
import aws.sdk.kotlin.services.agentregistrycontrol.model.SubmitRegistryRecordForApprovalRequest
import aws.sdk.kotlin.services.agentregistrycontrol.model.SubmitRegistryRecordForApprovalResponse
import aws.sdk.kotlin.services.agentregistrycontrol.model.UpdateRegistryRecordRequest
import aws.sdk.kotlin.services.agentregistrycontrol.model.UpdateRegistryRecordResponse
import aws.sdk.kotlin.services.agentregistrycontrol.model.UpdateRegistryRecordStatusRequest
import aws.sdk.kotlin.services.agentregistrycontrol.model.UpdateRegistryRecordStatusResponse
import aws.sdk.kotlin.services.agentregistrycontrol.model.UpdateRegistryRequest
import aws.sdk.kotlin.services.agentregistrycontrol.model.UpdateRegistryResponse
import com.TTT.AgentCore.AgentCoreConfig
import aws.smithy.kotlin.runtime.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.resume
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AgentRegistryTest
{
    @Test
    fun keepsDiscoveryAndControlClientsSeparateAndDoesNotCloseInjectedClients()
    {
        val discoveryClosed = booleanArrayOf(false)
        val controlClosed = booleanArrayOf(false)
        val discoveryClient = proxyClient<AgentRegistryClient>(discoveryClosed)
        val controlClient = proxyClient<AgentRegistryControlClient>(controlClosed)

        AgentRegistryClients(
            config = AgentCoreConfig("us-east-1"),
            agentRegistryClient = discoveryClient,
            agentRegistryControlClient = controlClient
        ).use { clients ->
            assertSame(discoveryClient, clients.agentRegistry)
            assertSame(controlClient, clients.agentRegistryControl)
            assertSame(discoveryClient, clients.agentregistry)
            assertSame(controlClient, clients.agentregistrycontrol)
        }

        assertFalse(discoveryClosed[0])
        assertFalse(controlClosed[0])
    }

    @Test
    fun delegatesRegistryAndRecordCrudAndApprovalOperations()
    {
        val responses = mapOf<String, Any>(
            "createRegistry" to CreateRegistryResponse { registryArn = "registry-arn" },
            "getRegistry" to GetRegistryResponse {
                registryId = "registry-id"
                name = "catalog"
                registryArn = "arn:registry"
                createdAt = Instant(java.time.Instant.EPOCH)
                status = aws.sdk.kotlin.services.agentregistrycontrol.model.RegistryStatus.Ready
                updatedAt = Instant(java.time.Instant.EPOCH)
            },
            "listRegistries" to ListRegistriesResponse { registries = emptyList() },
            "updateRegistry" to UpdateRegistryResponse {
                registryArn = "registry-arn"
                registryId = "registry-id"
                name = "catalog"
                createdAt = Instant(java.time.Instant.EPOCH)
                updatedAt = Instant(java.time.Instant.EPOCH)
                status = aws.sdk.kotlin.services.agentregistrycontrol.model.RegistryStatus.Ready
            },
            "deleteRegistry" to DeleteRegistryResponse { status = aws.sdk.kotlin.services.agentregistrycontrol.model.RegistryStatus.Deleting },
            "createRegistryRecord" to CreateRegistryRecordResponse {
                recordArn = "arn:record"
                status = RegistryRecordStatus.Draft
            },
            "getRegistryRecord" to GetRegistryRecordResponse {
                recordId = "record-id"
                name = "record-id"
                recordArn = "arn:record"
                registryArn = "arn:registry"
                recordType = RecordType.Mcp
                recordVersion = "1"
                status = RegistryRecordStatus.Draft
                createdAt = Instant(java.time.Instant.EPOCH)
                updatedAt = Instant(java.time.Instant.EPOCH)
            },
            "listRegistryRecords" to ListRegistryRecordsResponse { registryRecords = emptyList() },
            "updateRegistryRecord" to UpdateRegistryRecordResponse {
                recordId = "record-id"
                name = "record-id"
                recordArn = "arn:record"
                registryArn = "arn:registry"
                recordType = RecordType.Mcp
                recordVersion = "1"
                status = RegistryRecordStatus.Draft
                createdAt = Instant(java.time.Instant.EPOCH)
                updatedAt = Instant(java.time.Instant.EPOCH)
            },
            "deleteRegistryRecord" to DeleteRegistryRecordResponse {},
            "submitRegistryRecordForApproval" to SubmitRegistryRecordForApprovalResponse {
                recordId = "record-id"
                recordArn = "arn:record"
                registryArn = "arn:registry"
                status = RegistryRecordStatus.PendingApproval
                updatedAt = Instant(java.time.Instant.EPOCH)
            },
            "updateRegistryRecordStatus" to UpdateRegistryRecordStatusResponse {
                recordId = "record-id"
                recordArn = "arn:record"
                registryArn = "arn:registry"
                status = RegistryRecordStatus.Approved
                statusReason = "reviewed"
                updatedAt = Instant(java.time.Instant.EPOCH)
            }
        )
        val handler = RecordingHandler(responses)
        val client = proxyClient<AgentRegistryControlClient>(handler)
        val admin = AgentRegistryAdmin(client)

        runBlocking {
            assertEquals("registry-arn", admin.registry.create(CreateRegistryRequest { name = "catalog" }).registryArn)
            assertEquals("registry-id", admin.registry.get(GetRegistryRequest { registryId = "registry-id" }).registryId)
            admin.registry.list(ListRegistriesRequest {})
            admin.registry.update(UpdateRegistryRequest { registryId = "registry-id" })
            admin.registry.delete(DeleteRegistryRequest { registryId = "registry-id" })

            assertEquals(
                RegistryRecordStatus.Draft,
                admin.registryRecord.create(CreateRegistryRecordRequest { registryId = "registry-id" }).status
            )
            admin.registryRecord.get(GetRegistryRecordRequest {
                registryId = "registry-id"
                recordId = "record-id"
            })
            admin.registryRecord.list(ListRegistryRecordsRequest { registryId = "registry-id" })
            admin.registryRecord.update(UpdateRegistryRecordRequest {
                registryId = "registry-id"
                recordId = "record-id"
            })
            admin.registryRecord.delete(DeleteRegistryRecordRequest {
                registryId = "registry-id"
                recordId = "record-id"
            })
            admin.registryRecord.submitForApproval("registry-id", "record-id")
            admin.registryRecord.updateStatus(
                registryId = "registry-id",
                recordId = "record-id",
                status = RegistryRecordStatus.Approved,
                statusReason = "reviewed"
            )
        }

        assertTrue(handler.requests.any { it is SubmitRegistryRecordForApprovalRequest && it.recordId == "record-id" })
        assertTrue(
            handler.requests.any {
                it is UpdateRegistryRecordStatusRequest &&
                    it.status == RegistryRecordStatus.Approved &&
                    it.statusReason == "reviewed"
            }
        )
    }

    @Test
    fun buildsDiscoverableSearchListAndBatchGetRequests()
    {
        val records = listOf(RegistryRecordSummary {
            recordId = "record-1"
            name = "record-1"
            recordArn = "arn:record-1"
            registryArn = "arn:registry"
            createdAt = Instant(java.time.Instant.EPOCH)
            recordType = DiscoveryRecordType.Mcp
            recordVersion = "1"
            status = aws.sdk.kotlin.services.agentregistry.model.RegistryRecordStatus.Approved
            updatedAt = Instant(java.time.Instant.EPOCH)
        })
        val batchResponse = BatchGetDiscoverableRegistryRecordResponse {
            registryRecords = records
            errors = listOf(
                BatchGetDiscoverableRegistryRecordError {
                    registryId = "registry-id"
                    recordId = "missing"
                    errorCode = BatchGetDiscoverableRegistryRecordErrorCode.ResourceNotFound
                    message = "missing record"
                }
            )
        }
        val responses = mapOf<String, Any>(
            "searchDiscoverableRegistryRecords" to SearchDiscoverableRegistryRecordsResponse {
                registryRecords = records
            },
            "listDiscoverableRegistryRecords" to ListDiscoverableRegistryRecordsResponse {
                nextToken = "next"
                registryRecords = emptyList()
            },
            "batchGetDiscoverableRegistryRecord" to batchResponse
        )
        val handler = RecordingHandler(responses)
        val discovery = AgentRegistryDiscovery(proxyClient<AgentRegistryClient>(handler))

        runBlocking {
            discovery.search("registry-id", "weather", maxResults = 7)
            discovery.list(
                registryId = "registry-id",
                maxResults = 11,
                nextToken = "page-2",
                filters = listOf(RegistryRecordFilter {
                    name = RegistryRecordFilterName.RecordType
                    values = listOf(RecordType.Mcp.value)
                })
            )
            val response = discovery.batchGet("registry-id", listOf("record-1", "missing"))
            assertSame(batchResponse, response)
            assertTrue(response.isPartialFailure)
            assertEquals(1, discovery.batchGetResult("registry-id", listOf("record-1", "missing")).errors.size)
        }

        val searchRequest = handler.requests[0] as SearchDiscoverableRegistryRecordsRequest
        assertEquals(listOf("registry-id"), searchRequest.registryIds)
        assertEquals("weather", searchRequest.searchQuery)
        assertEquals(7, searchRequest.maxResults)

        val listRequest = handler.requests[1] as aws.sdk.kotlin.services.agentregistry.model.ListDiscoverableRegistryRecordsRequest
        assertEquals("page-2", listRequest.nextToken)
        assertEquals(11, listRequest.maxResults)
        assertEquals(RegistryRecordFilterName.RecordType, listRequest.filters!!.single().name)

        val batchRequest = handler.requests[2] as aws.sdk.kotlin.services.agentregistry.model.BatchGetDiscoverableRegistryRecordRequest
        assertEquals(listOf("record-1", "missing"), batchRequest.entries!!.single().recordIds)
        assertEquals("registry-id", batchRequest.entries!!.single().registryId)
    }

    @Test
    fun treatsOnlyDeprecatedAsTerminalAndOnlyApprovedAsDiscoverable()
    {
        assertFalse(RegistryRecordStatus.Draft.isTerminal)
        assertFalse(RegistryRecordStatus.PendingApproval.isTerminal)
        assertFalse(RegistryRecordStatus.Approved.isTerminal)
        assertFalse(RegistryRecordStatus.Rejected.isTerminal)
        assertTrue(RegistryRecordStatus.Deprecated.isTerminal)

        assertTrue(RegistryRecordStatus.Approved.isDiscoverable)
        assertFalse(RegistryRecordStatus.Deprecated.isDiscoverable)
    }

    private inline fun <reified T> proxyClient(closed: BooleanArray): T =
        proxyClient<T>(RecordingHandler(emptyMap(), closed))

    @Suppress("UNCHECKED_CAST")
    private inline fun <reified T> proxyClient(handler: InvocationHandler): T = Proxy.newProxyInstance(
        T::class.java.classLoader,
        arrayOf(T::class.java),
        handler
    ) as T

    private class RecordingHandler(
        private val responses: Map<String, Any>,
        private val closed: BooleanArray = booleanArrayOf(false)
    ) : InvocationHandler
    {
        val requests = mutableListOf<Any>()

        override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any?
        {
            if(method.name == "close")
            {
                closed[0] = true
                return null
            }

            if(method.name == "getConfig")
            {
                return null
            }

            if(method.name == "toString")
            {
                return "recording-client"
            }

            args?.firstOrNull()?.let { requests += it }
            val continuation = args?.lastOrNull() as? Continuation<Any?> ?: return null
            continuation.resume(responses[method.name])
            return COROUTINE_SUSPENDED
        }
    }
}
