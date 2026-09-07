package com.TTT.AgentCore.payments

import aws.sdk.kotlin.services.bedrockagentcore.BedrockAgentCoreClient
import aws.sdk.kotlin.services.bedrockagentcorecontrol.BedrockAgentCoreControlClient
import aws.sdk.kotlin.services.bedrockagentcorecontrol.model.CreatePaymentManagerRequest
import aws.sdk.kotlin.services.bedrockagentcorecontrol.model.CreatePaymentManagerResponse
import aws.sdk.kotlin.services.bedrockagentcorecontrol.model.PaymentsAuthorizerType
import aws.sdk.kotlin.services.bedrockagentcorecontrol.model.PaymentManagerStatus
import aws.sdk.kotlin.services.bedrockagentcore.model.ProcessPaymentRequest
import aws.sdk.kotlin.services.bedrockagentcore.model.ProcessPaymentResponse
import aws.sdk.kotlin.services.bedrockagentcore.model.PaymentStatus
import aws.sdk.kotlin.services.bedrockagentcore.model.PaymentType
import aws.smithy.kotlin.runtime.time.Instant
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class AgentCorePaymentsTest
{
    @Test
    fun parsesChallengeWithoutRetainingSignedProof()
    {
        val challenge = parseAgentCorePaymentChallenge(
            "scheme=x402, network=base, amount=1.25, currency=USD, proof=do-not-log"
        )

        assertEquals("x402", challenge.scheme)
        assertEquals("base", challenge.network)
        assertEquals("1.25", challenge.amount)
        assertEquals("USD", challenge.currency)
        assertFalse(challenge.toString().contains("do-not-log"))
    }

    @Test
    fun paymentProcessingIsAnExplicitSingleFacadeCall()
    {
        val calls = mutableListOf<String>()
        val client = proxyClient(BedrockAgentCoreClient::class.java) { method, _, _ ->
            calls += method.name
            when(method.name)
            {
                "processPayment" -> ProcessPaymentResponse {
                    createdAt = Instant(java.time.Instant.EPOCH)
                    paymentInstrumentId = "instrument"
                    paymentManagerArn = "arn:payment-manager"
                    paymentSessionId = "session"
                    paymentType = PaymentType.CryptoX402
                    processPaymentId = "process"
                    status = PaymentStatus.ProofGenerated
                    updatedAt = Instant(java.time.Instant.EPOCH)
                }
                else -> unsupportedReturn(method)
            }
        }

        runBlocking {
            AgentCorePaymentsClient(client).processPayment(ProcessPaymentRequest {})
        }

        assertEquals(listOf("processPayment"), calls)
    }

    @Test
    fun paymentAdministrationDelegatesToControlPlane()
    {
        val calls = mutableListOf<String>()
        val client = proxyClient(BedrockAgentCoreControlClient::class.java) { method, _, _ ->
            calls += method.name
            when(method.name)
            {
                "createPaymentManager" -> CreatePaymentManagerResponse {
                    authorizerType = PaymentsAuthorizerType.AwsIam
                    createdAt = Instant(java.time.Instant.EPOCH)
                    name = "manager"
                    paymentManagerArn = "arn:payment-manager"
                    paymentManagerId = "manager"
                    roleArn = "arn:role"
                    status = PaymentManagerStatus.Ready
                }
                else -> unsupportedReturn(method)
            }
        }

        runBlocking {
            AgentCorePaymentAdmin(client).createManager(CreatePaymentManagerRequest {})
        }

        assertEquals(listOf("createPaymentManager"), calls)
    }

    private fun <T> proxyClient(
        type: Class<T>,
        handler: (Method, Array<out Any?>?, Continuation<Any?>?) -> Any?
    ): T = Proxy.newProxyInstance(
        type.classLoader,
        arrayOf(type),
        InvocationHandler { proxy, method, args ->
            if(method.name == "toString") return@InvocationHandler "test-client"
            if(method.name == "hashCode") return@InvocationHandler System.identityHashCode(proxy)
            if(method.name == "equals") return@InvocationHandler proxy === args?.firstOrNull()
            val continuation = args?.lastOrNull() as? Continuation<Any?>
            handler(method, args, continuation)
        }
    ) as T

    private fun unsupportedReturn(method: Method): Any? = when(method.returnType) {
        java.lang.Boolean.TYPE -> false
        java.lang.Integer.TYPE -> 0
        java.lang.Long.TYPE -> 0L
        java.lang.Void.TYPE -> null
        else -> COROUTINE_SUSPENDED
    }
}
