package com.TTT.AgentCore.Payments

import com.TTT.AgentCore.AgentCoreClients
import com.TTT.AgentCore.payments.paymentAdmin as paymentAdminInternal
import com.TTT.AgentCore.payments.paymentsClient as paymentsClientInternal

/** Compatibility aliases for AgentCore Payments. */
typealias AgentCorePaymentAdmin = com.TTT.AgentCore.payments.AgentCorePaymentAdmin
typealias AgentCorePaymentsClient = com.TTT.AgentCore.payments.AgentCorePaymentsClient
typealias AgentCorePaymentChallenge = com.TTT.AgentCore.payments.AgentCorePaymentChallenge

/** Parse a non-secret x402/MPP challenge in the capitalized compatibility package. */
fun parseAgentCorePaymentChallenge(value: String): AgentCorePaymentChallenge =
    com.TTT.AgentCore.payments.parseAgentCorePaymentChallenge(value)

/** Build payment administration from shared AgentCore clients. */
fun AgentCoreClients.paymentAdmin(): AgentCorePaymentAdmin =
    this.paymentAdminInternal()

/** Build the explicit payments data-plane client from shared clients. */
fun AgentCoreClients.paymentsClient(): AgentCorePaymentsClient =
    this.paymentsClientInternal()
