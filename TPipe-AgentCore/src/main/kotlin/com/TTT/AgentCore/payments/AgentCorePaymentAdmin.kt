package com.TTT.AgentCore.payments

import aws.sdk.kotlin.services.bedrockagentcorecontrol.BedrockAgentCoreControlClient
import aws.sdk.kotlin.services.bedrockagentcorecontrol.model.*
import com.TTT.AgentCore.AgentCoreClients

/** Explicit control-plane administration for AgentCore Payments resources. */
class AgentCorePaymentAdmin(private val client: BedrockAgentCoreControlClient)
{
    /** PaymentManager lifecycle. */
    suspend fun createManager(request: CreatePaymentManagerRequest): CreatePaymentManagerResponse =
        client.createPaymentManager(request)
    suspend fun getManager(request: GetPaymentManagerRequest): GetPaymentManagerResponse = client.getPaymentManager(request)
    suspend fun listManagers(request: ListPaymentManagersRequest): ListPaymentManagersResponse =
        client.listPaymentManagers(request)
    suspend fun updateManager(request: UpdatePaymentManagerRequest): UpdatePaymentManagerResponse =
        client.updatePaymentManager(request)
    suspend fun deleteManager(request: DeletePaymentManagerRequest): DeletePaymentManagerResponse =
        client.deletePaymentManager(request)

    /** PaymentConnector lifecycle. */
    suspend fun createConnector(request: CreatePaymentConnectorRequest): CreatePaymentConnectorResponse =
        client.createPaymentConnector(request)
    suspend fun getConnector(request: GetPaymentConnectorRequest): GetPaymentConnectorResponse =
        client.getPaymentConnector(request)
    suspend fun listConnectors(request: ListPaymentConnectorsRequest): ListPaymentConnectorsResponse =
        client.listPaymentConnectors(request)
    suspend fun updateConnector(request: UpdatePaymentConnectorRequest): UpdatePaymentConnectorResponse =
        client.updatePaymentConnector(request)
    suspend fun deleteConnector(request: DeletePaymentConnectorRequest): DeletePaymentConnectorResponse =
        client.deletePaymentConnector(request)

    /** Payment credential-provider lifecycle. */
    suspend fun createCredentialProvider(
        request: CreatePaymentCredentialProviderRequest
    ): CreatePaymentCredentialProviderResponse = client.createPaymentCredentialProvider(request)
    suspend fun getCredentialProvider(
        request: GetPaymentCredentialProviderRequest
    ): GetPaymentCredentialProviderResponse = client.getPaymentCredentialProvider(request)
    suspend fun listCredentialProviders(
        request: ListPaymentCredentialProvidersRequest
    ): ListPaymentCredentialProvidersResponse = client.listPaymentCredentialProviders(request)
    suspend fun updateCredentialProvider(
        request: UpdatePaymentCredentialProviderRequest
    ): UpdatePaymentCredentialProviderResponse = client.updatePaymentCredentialProvider(request)
    suspend fun deleteCredentialProvider(
        request: DeletePaymentCredentialProviderRequest
    ): DeletePaymentCredentialProviderResponse = client.deletePaymentCredentialProvider(request)
}

/** Build payment administration from the shared AgentCore clients. */
fun AgentCoreClients.paymentAdmin(): AgentCorePaymentAdmin = AgentCorePaymentAdmin(control)
