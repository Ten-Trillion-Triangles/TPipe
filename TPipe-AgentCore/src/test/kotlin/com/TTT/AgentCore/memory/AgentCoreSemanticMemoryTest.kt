package com.TTT.AgentCore.memory

import aws.sdk.kotlin.services.bedrockagentcore.BedrockAgentCoreClient
import aws.sdk.kotlin.services.bedrockagentcore.model.IngestDataRequest
import aws.sdk.kotlin.services.bedrockagentcore.model.IngestDataResponse
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import kotlin.test.assertEquals

class AgentCoreSemanticMemoryTest
{
    @Test
    fun delegatesIngestDataWithoutChangingTheAwsRequest()
    {
        var received: IngestDataRequest? = null
        val client = proxyClient { request -> received = request }
        val request = IngestDataRequest {
            memoryId = "memory"
            actorId = "actor"
            sessionId = "session"
        }

        val response = runBlocking { AgentCoreSemanticMemory(client).ingestData(request) }

        assertEquals(request, received)
        assertEquals("ingested", response.sessionId)
    }

    @Test
    fun expandsAConfiguredSemanticNamespaceWithCustomVariables()
    {
        val configuration = AgentCoreMemoryNamespaceConfiguration(
            strategies = mapOf(
                "facts" to AgentCoreMemoryNamespaceTemplate(
                    template = "users/{tenant}/{actorId}/facts/",
                    variables = mapOf("tenant" to "acme", "actorId" to "actor-7")
                )
            )
        )

        assertEquals("users/acme/actor-7/facts/", configuration.expand("facts"))
    }

    private fun proxyClient(onIngest: (IngestDataRequest) -> Unit): BedrockAgentCoreClient =
        Proxy.newProxyInstance(
            BedrockAgentCoreClient::class.java.classLoader,
            arrayOf(BedrockAgentCoreClient::class.java)
        ) { _, method, arguments ->
            if(method.name != "ingestData")
            {
                error("Unexpected AgentCore client method: ${method.name}")
            }
            onIngest(arguments!![0] as IngestDataRequest)
            IngestDataResponse { sessionId = "ingested" }
        } as BedrockAgentCoreClient
}
