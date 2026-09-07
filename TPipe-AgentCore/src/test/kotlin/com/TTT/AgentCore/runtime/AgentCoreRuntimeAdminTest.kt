package com.TTT.AgentCore.runtime

import aws.sdk.kotlin.services.bedrockagentcorecontrol.BedrockAgentCoreControlClient
import aws.sdk.kotlin.services.bedrockagentcorecontrol.model.CapacityProviderConfiguration
import aws.sdk.kotlin.services.bedrockagentcorecontrol.model.CapacityProviderVolumeConfiguration
import aws.sdk.kotlin.services.bedrockagentcorecontrol.model.FilesystemConfiguration
import aws.sdk.kotlin.services.bedrockagentcorecontrol.model.UpdateAgentRuntimeRequest
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AgentCoreRuntimeAdminTest
{
    @Test
    fun allowsCapacityProviderVolumeUpdatesWhenComputeTypeIsOmitted()
    {
        var invoked = false
        val client = Proxy.newProxyInstance(
            BedrockAgentCoreControlClient::class.java.classLoader,
            arrayOf(BedrockAgentCoreControlClient::class.java),
            InvocationHandler { _, method, _ ->
                if(method.name == "updateAgentRuntime")
                {
                    invoked = true
                    throw RuntimeAdminSentinel()
                }
                null
            }
        ) as BedrockAgentCoreControlClient
        val admin = AgentCoreRuntimeAdmin(client)
        val request = UpdateAgentRuntimeRequest {
            agentRuntimeId = "runtime"
            filesystemConfigurations = listOf(
                FilesystemConfiguration.CapacityProviderVolume(
                    CapacityProviderVolumeConfiguration {
                        mountPath = "/mnt/data"
                        volumeName = "persistent"
                    }
                )
            )
        }

        assertFailsWith<RuntimeAdminSentinel> { runBlocking { admin.update(request) } }
        assertEquals(true, invoked)
    }

    @Test
    fun passesInstancesCapacityProviderConfigurationToTheSdk()
    {
        var invoked = false
        var capturedArn: String? = null
        val client = Proxy.newProxyInstance(
            BedrockAgentCoreControlClient::class.java.classLoader,
            arrayOf(BedrockAgentCoreControlClient::class.java),
            InvocationHandler { _, method, args ->
                if(method.name == "updateAgentRuntime")
                {
                    invoked = true
                    capturedArn = (args?.firstOrNull() as UpdateAgentRuntimeRequest)
                        .capacityProviderConfiguration?.capacityProviderArn
                    throw RuntimeAdminSentinel()
                }
                null
            }
        ) as BedrockAgentCoreControlClient
        val admin = AgentCoreRuntimeAdmin(client)

        val request = UpdateAgentRuntimeRequest {
            agentRuntimeId = "runtime"
            capacityProviderConfiguration = CapacityProviderConfiguration {
                capacityProviderArn = "arn:capacity-provider"
            }
        }

        assertFailsWith<RuntimeAdminSentinel> { runBlocking { admin.update(request) } }
        assertEquals(true, invoked)
        assertEquals("arn:capacity-provider", capturedArn)
    }

    private class RuntimeAdminSentinel : IllegalStateException()
}
