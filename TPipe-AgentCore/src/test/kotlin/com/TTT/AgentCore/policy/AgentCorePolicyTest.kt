package com.TTT.AgentCore.policy

import aws.sdk.kotlin.services.bedrockagentcorecontrol.BedrockAgentCoreControlClient
import aws.sdk.kotlin.services.bedrockagentcorecontrol.model.CedarPolicy
import aws.sdk.kotlin.services.bedrockagentcorecontrol.model.CreatePolicyRequest
import aws.sdk.kotlin.services.bedrockagentcorecontrol.model.CreatePolicyResponse
import aws.sdk.kotlin.services.bedrockagentcorecontrol.model.EnforcementMode
import aws.sdk.kotlin.services.bedrockagentcorecontrol.model.GetGatewayResponse
import aws.sdk.kotlin.services.bedrockagentcorecontrol.model.GatewayPolicyEngineConfiguration
import aws.sdk.kotlin.services.bedrockagentcorecontrol.model.GatewayPolicyEngineMode
import aws.sdk.kotlin.services.bedrockagentcorecontrol.model.GatewayProtocolConfiguration
import aws.sdk.kotlin.services.bedrockagentcorecontrol.model.GatewayProtocolType
import aws.sdk.kotlin.services.bedrockagentcorecontrol.model.McpGatewayConfiguration
import aws.sdk.kotlin.services.bedrockagentcorecontrol.model.PolicyDefinition
import aws.sdk.kotlin.services.bedrockagentcorecontrol.model.PolicyStatement
import aws.sdk.kotlin.services.bedrockagentcorecontrol.model.UpdateGatewayResponse
import aws.sdk.kotlin.services.bedrockagentcorecontrol.model.UpdatePolicyRequest
import aws.sdk.kotlin.services.bedrockagentcorecontrol.model.UpdatePolicyResponse
import com.TTT.MCP.Client.McpRemoteClientConfig
import com.TTT.AgentCore.gateway.AgentCoreGatewayConfig
import com.TTT.AgentCore.gateway.toMcpRemoteClientConfig
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import aws.smithy.kotlin.runtime.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class AgentCorePolicyTest
{
    @Test
    fun defaultsGeneralPolicyCreateAndUpdateToLogOnlyForCedarAndTemporalDefinitions()
    {
        var createRequest: CreatePolicyRequest? = null
        var updateRequest: UpdatePolicyRequest? = null
        val client = proxyClient(
            onCreatePolicy = { createRequest = it },
            onUpdatePolicy = { updateRequest = it }
        )
        val cedar = PolicyDefinition.Cedar(CedarPolicy { statement = "permit(principal, action, resource);" })
        val temporal = PolicyDefinition.Policy(PolicyStatement { statement = "permit(principal, action, resource);" })

        runBlocking {
            AgentCorePolicyAdmin(client).createPolicy(CreatePolicyRequest {
                name = "cedar-policy"
                policyEngineId = "engine"
                definition = cedar
            })
            AgentCorePolicyAdmin(client).updatePolicy(UpdatePolicyRequest {
                policyId = "policy"
                policyEngineId = "engine"
                definition = temporal
            })
        }

        assertEquals(EnforcementMode.LogOnly, createRequest?.enforcementMode)
        assertEquals(cedar, createRequest?.definition)
        assertEquals(EnforcementMode.LogOnly, updateRequest?.enforcementMode)
        assertEquals(temporal, updateRequest?.definition)
    }

    @Test
    fun bindGatewayPreservesAllMutableGatewayConfigurationFields()
    {
        var updateRequest: aws.sdk.kotlin.services.bedrockagentcorecontrol.model.UpdateGatewayRequest? = null
        val gateway = GetGatewayResponse {
            createdAt = Instant(java.time.Instant.EPOCH)
            status = aws.sdk.kotlin.services.bedrockagentcorecontrol.model.GatewayStatus.Ready
            updatedAt = Instant(java.time.Instant.EPOCH)
            gatewayId = "gateway"
            gatewayArn = "arn:gateway"
            gatewayUrl = "https://gateway.example"
            name = "gateway-name"
            description = "gateway-description"
            roleArn = "arn:role"
            authorizerType = aws.sdk.kotlin.services.bedrockagentcorecontrol.model.AuthorizerType.None
            protocolType = GatewayProtocolType.Mcp
            protocolConfiguration = GatewayProtocolConfiguration.Mcp(McpGatewayConfiguration {
                instructions = "instructions"
                supportedVersions = listOf("2025-03-26")
            })
            customTransformConfiguration = aws.sdk.kotlin.services.bedrockagentcorecontrol.model.CustomTransformConfiguration {
                lambda { arn = "arn:lambda" }
            }
            interceptorConfigurations = emptyList()
            kmsKeyArn = "arn:kms"
            exceptionLevel = aws.sdk.kotlin.services.bedrockagentcorecontrol.model.ExceptionLevel.Debug
            wafConfiguration = aws.sdk.kotlin.services.bedrockagentcorecontrol.model.WafConfiguration {
                failureMode = aws.sdk.kotlin.services.bedrockagentcorecontrol.model.WafFailureMode.FailOpen
            }
            policyEngineConfiguration = GatewayPolicyEngineConfiguration {
                arn = "arn:old-policy"
                mode = GatewayPolicyEngineMode.LogOnly
            }
        }
        val client = proxyClient(
            onGetGateway = { gateway },
            onUpdateGateway = { updateRequest = it }
        )

        runBlocking {
            AgentCorePolicyAdmin(client).bindGateway(
                AgentCoreGatewayPolicyBinding("gateway", "arn:new-policy", AgentCorePolicyMode.ENFORCE)
            )
        }

        val updated = assertNotNull(updateRequest)
        assertEquals(gateway.name, updated.name)
        assertEquals(gateway.description, updated.description)
        assertEquals(gateway.roleArn, updated.roleArn)
        assertEquals(gateway.authorizerType, updated.authorizerType)
        assertEquals(gateway.authorizerConfiguration, updated.authorizerConfiguration)
        assertEquals(gateway.protocolConfiguration, updated.protocolConfiguration)
        assertEquals(gateway.protocolType, updated.protocolType)
        assertEquals(gateway.customTransformConfiguration, updated.customTransformConfiguration)
        assertEquals(gateway.interceptorConfigurations, updated.interceptorConfigurations)
        assertEquals(gateway.kmsKeyArn, updated.kmsKeyArn)
        assertEquals(gateway.exceptionLevel, updated.exceptionLevel)
        assertEquals(gateway.wafConfiguration, updated.wafConfiguration)
        assertEquals("arn:new-policy", updated.policyEngineConfiguration?.arn)
    }

    @Test
    fun temporalPolicySessionIsRepresentedByAnAgentCoreOnlyHeader()
    {
        val session = AgentCoreTemporalPolicySession("session-42")
        val config = AgentCoreGatewayConfig(
            endpoint = "https://gateway.example",
            mcp = McpRemoteClientConfig(
                endpoint = "https://placeholder.example",
                requestHeaders = mapOf("x-existing" to "value")
            ),
            temporalPolicySession = session
        )

        assertEquals("x-amzn-bedrock-agentcore-policy-session-id", session.headerName)
        assertEquals(mapOf(session.headerName to "session-42"), session.asHeader())
        assertEquals(
            mapOf("x-existing" to "value", session.headerName to "session-42"),
            config.toMcpRemoteClientConfig().requestHeaders
        )
    }

    private fun proxyClient(
        onCreatePolicy: (CreatePolicyRequest) -> Unit = {},
        onUpdatePolicy: (UpdatePolicyRequest) -> Unit = {},
        onGetGateway: () -> GetGatewayResponse = { error("Unexpected getGateway call") },
        onUpdateGateway: (aws.sdk.kotlin.services.bedrockagentcorecontrol.model.UpdateGatewayRequest) -> Unit = {}
    ): BedrockAgentCoreControlClient =
        Proxy.newProxyInstance(
            BedrockAgentCoreControlClient::class.java.classLoader,
            arrayOf(BedrockAgentCoreControlClient::class.java)
        ) { _, method, arguments ->
            when(method.name)
            {
                "createPolicy" -> {
                    onCreatePolicy(arguments!![0] as CreatePolicyRequest)
                    CreatePolicyResponse {
                        createdAt = Instant(java.time.Instant.EPOCH)
                        updatedAt = Instant(java.time.Instant.EPOCH)
                        policyArn = "arn:policy"
                        policyEngineId = "engine"
                        policyId = "policy"
                        name = "policy"
                        statusReasons = emptyList()
                        status = aws.sdk.kotlin.services.bedrockagentcorecontrol.model.PolicyStatus.Active
                    }
                }
                "updatePolicy" -> {
                    onUpdatePolicy(arguments!![0] as UpdatePolicyRequest)
                    UpdatePolicyResponse {
                        createdAt = Instant(java.time.Instant.EPOCH)
                        updatedAt = Instant(java.time.Instant.EPOCH)
                        policyArn = "arn:policy"
                        policyEngineId = "engine"
                        policyId = "policy"
                        name = "policy"
                        statusReasons = emptyList()
                        status = aws.sdk.kotlin.services.bedrockagentcorecontrol.model.PolicyStatus.Active
                    }
                }
                "getGateway" -> onGetGateway()
                "updateGateway" -> {
                    onUpdateGateway(arguments!![0] as aws.sdk.kotlin.services.bedrockagentcorecontrol.model.UpdateGatewayRequest)
                    UpdateGatewayResponse {
                        authorizerType = aws.sdk.kotlin.services.bedrockagentcorecontrol.model.AuthorizerType.None
                        createdAt = Instant(java.time.Instant.EPOCH)
                        gatewayArn = "arn:gateway"
                        gatewayId = "gateway"
                        gatewayUrl = "https://gateway.example"
                        name = "gateway-name"
                        protocolType = GatewayProtocolType.Mcp
                        roleArn = "arn:role"
                        status = aws.sdk.kotlin.services.bedrockagentcorecontrol.model.GatewayStatus.Ready
                        updatedAt = Instant(java.time.Instant.EPOCH)
                    }
                }
                else -> error("Unexpected AgentCore control method: ${method.name}")
            }
        } as BedrockAgentCoreControlClient
}
