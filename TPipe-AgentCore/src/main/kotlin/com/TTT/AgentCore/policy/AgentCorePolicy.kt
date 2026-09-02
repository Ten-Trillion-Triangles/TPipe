package com.TTT.AgentCore.policy

import aws.sdk.kotlin.services.bedrockagentcorecontrol.BedrockAgentCoreControlClient
import aws.sdk.kotlin.services.bedrockagentcorecontrol.model.*
import com.TTT.AgentCore.AgentCoreClients

/** First-phase policy behavior; enforcement can be enabled explicitly later. */
enum class AgentCorePolicyMode { LOG_ONLY, ENFORCE }

/** Policy rollout settings; LOG_ONLY is intentionally the safe default. */
data class AgentCorePolicyConfig(
    val mode: AgentCorePolicyMode = AgentCorePolicyMode.LOG_ONLY,
    val pollIntervalMillis: Long = 500L,
    val timeoutMillis: Long = 30_000L
)

/** A policy decision recorded by the local adapter. */
data class AgentCorePolicyDecision(val action: String, val allowed: Boolean, val reason: String)

/**
 * Local policy seam for Gateway and PCP integrations.
 *
 * LOG_ONLY is the default and deliberately does not translate PCP schemas to
 * Cedar. ENFORCE accepts a caller-supplied evaluator so policy semantics stay
 * owned by the application or AgentCore control plane.
 */
class AgentCorePolicyEvaluator(
    private val mode: AgentCorePolicyMode = AgentCorePolicyMode.LOG_ONLY,
    private val evaluator: (String, Map<String, String>) -> AgentCorePolicyDecision =
        { action, _ -> AgentCorePolicyDecision(action, allowed = true, reason = "log-only") },
    private val logger: (AgentCorePolicyDecision) -> Unit = {}
) {
    /** Evaluate one action and emit the decision to the configured logger. */
    fun evaluate(action: String, arguments: Map<String, String> = emptyMap()): Boolean {
        val decision = when (mode) {
            AgentCorePolicyMode.LOG_ONLY -> AgentCorePolicyDecision(action, true, "log-only")
            AgentCorePolicyMode.ENFORCE -> evaluator(action, arguments)
        }
        logger(decision)
        return decision.allowed
    }
}

/** Typed Policy Engine and Cedar policy lifecycle access. */
class AgentCorePolicyAdmin(private val client: BedrockAgentCoreControlClient) {
    /** Create a Policy Engine. */
    suspend fun createEngine(request: CreatePolicyEngineRequest): CreatePolicyEngineResponse =
        client.createPolicyEngine(request)

    /** Read a Policy Engine. */
    suspend fun getEngine(request: GetPolicyEngineRequest): GetPolicyEngineResponse =
        client.getPolicyEngine(request)

    /** Update a Policy Engine. */
    suspend fun updateEngine(request: UpdatePolicyEngineRequest): UpdatePolicyEngineResponse =
        client.updatePolicyEngine(request)

    /** Delete a Policy Engine. */
    suspend fun deleteEngine(request: DeletePolicyEngineRequest): DeletePolicyEngineResponse =
        client.deletePolicyEngine(request)

    /** Create a Cedar policy. */
    suspend fun createPolicy(request: CreatePolicyRequest): CreatePolicyResponse = client.createPolicy(request)

    /** Read a Cedar policy. */
    suspend fun getPolicy(request: GetPolicyRequest): GetPolicyResponse = client.getPolicy(request)

    /** Update a Cedar policy. */
    suspend fun updatePolicy(request: UpdatePolicyRequest): UpdatePolicyResponse = client.updatePolicy(request)

    /** Delete a Cedar policy. */
    suspend fun deletePolicy(request: DeletePolicyRequest): DeletePolicyResponse = client.deletePolicy(request)

    /** Start asynchronous policy generation. */
    suspend fun startGeneration(request: StartPolicyGenerationRequest): StartPolicyGenerationResponse =
        client.startPolicyGeneration(request)

    /** Read asynchronous policy-generation status. */
    suspend fun getGeneration(request: GetPolicyGenerationRequest): GetPolicyGenerationResponse =
        client.getPolicyGeneration(request)

    /**
     * Attach a Policy Engine to a Gateway using the control-plane update
     * operation. The SDK models this association as Gateway configuration,
     * rather than as a separate association API.
     */
    suspend fun bindGateway(binding: AgentCoreGatewayPolicyBinding): UpdateGatewayResponse =
        client.updateGateway(
            UpdateGatewayRequest {
                gatewayIdentifier = binding.gatewayIdentifier
                policyEngineConfiguration {
                    arn = binding.policyEngineIdentifier
                    mode = when (binding.mode) {
                        AgentCorePolicyMode.LOG_ONLY -> GatewayPolicyEngineMode.LogOnly
                        AgentCorePolicyMode.ENFORCE -> GatewayPolicyEngineMode.Enforce
                    }
                }
            }
        )
}

/** Build Policy administration from shared clients. */
fun AgentCoreClients.policyAdmin(): AgentCorePolicyAdmin = AgentCorePolicyAdmin(control)

/** Describes an explicit Gateway-to-Policy Engine association. */
data class AgentCoreGatewayPolicyBinding(
    val gatewayIdentifier: String,
    val policyEngineIdentifier: String,
    val mode: AgentCorePolicyMode = AgentCorePolicyMode.LOG_ONLY
)
