package com.TTT.AgentCore.gateway

import aws.sdk.kotlin.services.bedrockagentcorecontrol.BedrockAgentCoreControlClient
import aws.sdk.kotlin.services.bedrockagentcorecontrol.model.Action
import aws.sdk.kotlin.services.bedrockagentcorecontrol.model.CreateGatewayRateLimitRequest
import aws.sdk.kotlin.services.bedrockagentcorecontrol.model.CreateGatewayRateLimitResponse
import aws.sdk.kotlin.services.bedrockagentcorecontrol.model.CreateGatewayRuleRequest
import aws.sdk.kotlin.services.bedrockagentcorecontrol.model.CreateGatewayRuleResponse
import aws.sdk.kotlin.services.bedrockagentcorecontrol.model.LimitEntry
import aws.sdk.kotlin.services.bedrockagentcorecontrol.model.Period
import aws.sdk.kotlin.services.bedrockagentcorecontrol.model.RateConfig
import aws.sdk.kotlin.services.bedrockagentcorecontrol.model.RouteToTargetAction
import aws.sdk.kotlin.services.bedrockagentcorecontrol.model.TargetTrafficSplitEntry
import aws.sdk.kotlin.services.bedrockagentcorecontrol.model.TrafficSplitEntry
import aws.sdk.kotlin.services.bedrockagentcorecontrol.model.WeightedRoute
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AgentCoreGatewayAdminTest
{
    @Test
    fun rejectsInvalidRateValuesBeforeCallingTheControlPlane()
    {
        var calls = 0
        val client = proxyClient { calls++ }
        val request = CreateGatewayRateLimitRequest {
            gatewayIdentifier = "gateway"
            dimensionKeys = listOf("targetName")
            entries = listOf(
                LimitEntry {
                    dimensions = mapOf("targetName" to "acme")
                    requests = listOf(RateConfig {
                        period = Period.Second
                        rate = -1.0
                    })
                }
            )
        }

        assertFailsWith<IllegalArgumentException> {
            runBlocking { AgentCoreGatewayAdmin(client).createRateLimit(request) }
        }
        assertEquals(0, calls)
    }

    @Test
    fun validatesDimensionCardinalityAndUniqueness()
    {
        val admin = AgentCoreGatewayAdmin(proxyClient {})

        assertFailsWith<IllegalArgumentException> {
            admin.validateRateLimitDimensions(listOf("targetName", "targetName"))
        }
        assertFailsWith<IllegalArgumentException> {
            admin.validateRateLimitDimensions((1..11).map { "targetName$it" })
        }
        assertFailsWith<IllegalArgumentException> {
            admin.validateRateLimitDimensions(listOf("tenant"))
        }
    }

    @Test
    fun rejectsInvalidGatewayRateLimitClientTokens()
    {
        val admin = AgentCoreGatewayAdmin(proxyClient {})
        val request = CreateGatewayRateLimitRequest {
            gatewayIdentifier = "gateway"
            clientToken = "token_with_underscore"
            dimensionKeys = listOf("targetName")
            entries = listOf(
                LimitEntry {
                    dimensions = mapOf("targetName" to "*")
                    requests = listOf(RateConfig { period = Period.Second; rate = 1.0 })
                }
            )
        }

        assertFailsWith<IllegalArgumentException> { admin.validateRateLimit(request) }

        val shortTokenRequest = CreateGatewayRateLimitRequest {
            gatewayIdentifier = "gateway"
            clientToken = "a".repeat(32)
            dimensionKeys = listOf("targetName")
            entries = listOf(
                LimitEntry {
                    dimensions = mapOf("targetName" to "*")
                    requests = listOf(RateConfig { period = Period.Second; rate = 1.0 })
                }
            )
        }
        assertFailsWith<IllegalArgumentException> {
            admin.validateRateLimit(shortTokenRequest)
        }
    }

    @Test
    fun rejectsWeightedRoutesUnlessTheyHaveTwoBalancedVariants()
    {
        var calls = 0
        val client = proxyClient { calls++ }
        val request = CreateGatewayRuleRequest {
            gatewayIdentifier = "gateway"
            priority = 1
            actions = listOf(
                Action.RouteToTarget(
                    RouteToTargetAction.WeightedRoute(
                        WeightedRoute {
                            trafficSplit = listOf(
                                TargetTrafficSplitEntry {
                                    name = "blue"
                                    targetName = "blue"
                                    weight = 100
                                }
                            )
                        }
                    )
                )
            )
        }

        assertFailsWith<IllegalArgumentException> {
            runBlocking { AgentCoreGatewayAdmin(client).createRule(request) }
        }
        assertEquals(0, calls)
    }

    @Test
    fun rejectsMixedWeightedVariantModels()
    {
        val admin = AgentCoreGatewayAdmin(proxyClient {})

        assertFailsWith<IllegalArgumentException> {
            admin.validateWeightedVariants(
                listOf(
                    TargetTrafficSplitEntry { name = "blue"; targetName = "blue"; weight = 50 },
                    TrafficSplitEntry { name = "green"; weight = 50 }
                )
            )
        }
    }

    @Test
    fun delegatesTypedGatewayRuleCrudRequests()
    {
        val client = proxyClient { }
        val request = CreateGatewayRuleRequest {
            gatewayIdentifier = "gateway"
            priority = 1
            actions = listOf(Action.RouteToTarget(
                RouteToTargetAction.WeightedRoute(WeightedRoute {
                    trafficSplit = listOf(
                        TargetTrafficSplitEntry {
                            name = "blue"
                            targetName = "blue"
                            weight = 70
                        },
                        TargetTrafficSplitEntry {
                            name = "green"
                            targetName = "green"
                            weight = 30
                        }
                    )
                })
            ))
        }

        val response = runBlocking { AgentCoreGatewayAdmin(client).createRule(request) }

        assertEquals("rule", response.ruleId)
    }

    private fun proxyClient(onCall: () -> Unit): BedrockAgentCoreControlClient =
        Proxy.newProxyInstance(
            BedrockAgentCoreControlClient::class.java.classLoader,
            arrayOf(BedrockAgentCoreControlClient::class.java)
        ) { _, method, _ ->
            if(method.name != "createGatewayRateLimit" && method.name != "createGatewayRule")
            {
                error("Unexpected AgentCore control method: ${method.name}")
            }
            onCall()
            when(method.name)
            {
                "createGatewayRateLimit" -> CreateGatewayRateLimitResponse {}
                else -> CreateGatewayRuleResponse {
                    actions = emptyList()
                    conditions = emptyList()
                    createdAt = aws.smithy.kotlin.runtime.time.Instant(java.time.Instant.EPOCH)
                    gatewayArn = "arn:gateway"
                    priority = 1
                    ruleId = "rule"
                    status = aws.sdk.kotlin.services.bedrockagentcorecontrol.model.GatewayRuleStatus.Active
                }
            }
        } as BedrockAgentCoreControlClient
}
