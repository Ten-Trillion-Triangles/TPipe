package com.TTT.AgentCore.payments

import aws.sdk.kotlin.services.bedrockagentcore.BedrockAgentCoreClient
import aws.sdk.kotlin.services.bedrockagentcore.model.*
import com.TTT.AgentCore.AgentCoreClients

/** Explicit AgentCore Payments data-plane operations.
 *
 * This client never retries or automatically executes a payment after a
 * challenge or HTTP 402. Callers must make the spending decision themselves.
 */
class AgentCorePaymentsClient(private val client: BedrockAgentCoreClient)
{
    /** Create a payment instrument. */
    suspend fun createInstrument(request: CreatePaymentInstrumentRequest): CreatePaymentInstrumentResponse =
        client.createPaymentInstrument(request)
    /** Get a payment instrument. */
    suspend fun getInstrument(request: GetPaymentInstrumentRequest): GetPaymentInstrumentResponse =
        client.getPaymentInstrument(request)
    /** List payment instruments. */
    suspend fun listInstruments(request: ListPaymentInstrumentsRequest): ListPaymentInstrumentsResponse =
        client.listPaymentInstruments(request)
    /** Delete a payment instrument. */
    suspend fun deleteInstrument(request: DeletePaymentInstrumentRequest): DeletePaymentInstrumentResponse =
        client.deletePaymentInstrument(request)
    /** Get a payment instrument balance. */
    suspend fun getInstrumentBalance(
        request: GetPaymentInstrumentBalanceRequest
    ): GetPaymentInstrumentBalanceResponse = client.getPaymentInstrumentBalance(request)
    /** Create a payment session. */
    suspend fun createSession(request: CreatePaymentSessionRequest): CreatePaymentSessionResponse =
        client.createPaymentSession(request)
    /** Get a payment session. */
    suspend fun getSession(request: GetPaymentSessionRequest): GetPaymentSessionResponse = client.getPaymentSession(request)
    /** List payment sessions. */
    suspend fun listSessions(request: ListPaymentSessionsRequest): ListPaymentSessionsResponse =
        client.listPaymentSessions(request)
    /** Delete a payment session. */
    suspend fun deleteSession(request: DeletePaymentSessionRequest): DeletePaymentSessionResponse =
        client.deletePaymentSession(request)
    /** Explicitly process a payment selected by the caller. */
    suspend fun processPayment(request: ProcessPaymentRequest): ProcessPaymentResponse = client.processPayment(request)
    /** Resolve a resource payment token. */
    suspend fun getResourcePaymentToken(
        request: GetResourcePaymentTokenRequest
    ): GetResourcePaymentTokenResponse = client.getResourcePaymentToken(request)
}

/** Build an explicit payments data-plane client from shared clients. */
fun AgentCoreClients.paymentsClient(): AgentCorePaymentsClient = AgentCorePaymentsClient(data)

/** Non-secret fields parsed from a payment challenge. */
data class AgentCorePaymentChallenge(
    val scheme: String,
    val network: String? = null,
    val amount: String? = null,
    val currency: String? = null
)

/** Parse a simple x402/MPP challenge without retaining signed proof material. */
fun parseAgentCorePaymentChallenge(value: String): AgentCorePaymentChallenge
{
    val fields = value.split(',')
        .mapNotNull { part -> part.trim().split('=', limit = 2).takeIf { it.size == 2 } }
        .associate { it[0].trim().lowercase() to it[1].trim().trim('"') }
    return AgentCorePaymentChallenge(
        scheme = fields["scheme"] ?: fields["type"] ?: "unknown",
        network = fields["network"],
        amount = fields["amount"],
        currency = fields["currency"]
    )
}
