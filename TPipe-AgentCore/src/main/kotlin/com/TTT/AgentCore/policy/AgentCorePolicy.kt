package com.TTT.AgentCore.policy

import aws.sdk.kotlin.services.bedrockagentcorecontrol.BedrockAgentCoreControlClient
import aws.sdk.kotlin.services.bedrockagentcorecontrol.model.*
import com.TTT.AgentCore.AgentCoreClients

/** First-phase policy behavior; enforcement can be enabled explicitly later. */
enum class AgentCorePolicyMode { LOG_ONLY, ENFORCE }

/**
 * Policy rollout settings; LOG_ONLY is intentionally the safe default.
 *
 * @param mode Policy evaluation mode.
 * @param pollIntervalMillis Polling interval for asynchronous policy work.
 * @param timeoutMillis Maximum wait for asynchronous policy work.
 */
data class AgentCorePolicyConfig(
    val mode: AgentCorePolicyMode = AgentCorePolicyMode.LOG_ONLY,
    val pollIntervalMillis: Long = 500L,
    val timeoutMillis: Long = 30_000L
)

/**
 * A policy decision recorded by the local adapter.
 *
 * @param action Evaluated action name.
 * @param allowed Whether the action is allowed.
 * @param reason Explanation associated with the decision.
 */
data class AgentCorePolicyDecision(
    val action: String,
    val allowed: Boolean,
    val reason: String
)

/**
 * Local policy seam for Gateway and PCP integrations.
 *
 * LOG_ONLY is the default and deliberately does not translate PCP schemas to
 * Cedar. ENFORCE accepts a caller-supplied evaluator so policy semantics stay
 * owned by the application or AgentCore control plane.
 *
 * @param mode Policy evaluation mode.
 * @param evaluator Evaluator used when enforcement is enabled.
 * @param logger Consumer for evaluated decisions.
 */
class AgentCorePolicyEvaluator(
    private val mode: AgentCorePolicyMode = AgentCorePolicyMode.LOG_ONLY,
    private val evaluator: (String, Map<String, String>) -> AgentCorePolicyDecision =
        { action, _ -> AgentCorePolicyDecision(action, allowed = true, reason = "log-only") },
    private val logger: (AgentCorePolicyDecision) -> Unit = {}
)
{
    /**
     * Evaluate one action and emit the decision to the configured logger.
     *
     * @param action Action name to evaluate.
     * @param arguments String arguments supplied to the evaluator.
     * @return Whether the action is allowed.
     */
    fun evaluate(action: String, arguments: Map<String, String> = emptyMap()): Boolean
    {
        val decision = when(mode)
        {
            AgentCorePolicyMode.LOG_ONLY -> AgentCorePolicyDecision(action, true, "log-only")
            AgentCorePolicyMode.ENFORCE -> evaluator(action, arguments)
        }

        logger(decision)
        return decision.allowed
    }
}

/**
 * Typed Policy Engine and Cedar policy lifecycle access.
 *
 * @param client AgentCore control-plane client.
 */
class AgentCorePolicyAdmin(private val client: BedrockAgentCoreControlClient)
{
    /** Create a Policy Engine.
     *
     * @param request Policy Engine request.
     * @return The service response.
     */
    suspend fun createEngine(request: CreatePolicyEngineRequest): CreatePolicyEngineResponse =
        client.createPolicyEngine(request)

    /** Read a Policy Engine.
     *
     * @param request Policy Engine lookup request.
     * @return The service response.
     */
    suspend fun getEngine(request: GetPolicyEngineRequest): GetPolicyEngineResponse =
        client.getPolicyEngine(request)

    /** Update a Policy Engine.
     *
     * @param request Policy Engine update request.
     * @return The service response.
     */
    suspend fun updateEngine(request: UpdatePolicyEngineRequest): UpdatePolicyEngineResponse =
        client.updatePolicyEngine(request)

    /** Delete a Policy Engine.
     *
     * @param request Policy Engine delete request.
     * @return The service response.
     */
    suspend fun deleteEngine(request: DeletePolicyEngineRequest): DeletePolicyEngineResponse =
        client.deletePolicyEngine(request)

    /** Create a Cedar policy.
     *
     * @param request Policy creation request.
     * @return The service response.
     */
    suspend fun createPolicy(request: CreatePolicyRequest): CreatePolicyResponse = client.createPolicy(request)

    /** Read a Cedar policy.
     *
     * @param request Policy lookup request.
     * @return The service response.
     */
    suspend fun getPolicy(request: GetPolicyRequest): GetPolicyResponse = client.getPolicy(request)

    /** Update a Cedar policy.
     *
     * @param request Policy update request.
     * @return The service response.
     */
    suspend fun updatePolicy(request: UpdatePolicyRequest): UpdatePolicyResponse = client.updatePolicy(request)

    /** Delete a Cedar policy.
     *
     * @param request Policy delete request.
     * @return The service response.
     */
    suspend fun deletePolicy(request: DeletePolicyRequest): DeletePolicyResponse = client.deletePolicy(request)

    /** Start asynchronous policy generation.
     *
     * @param request Policy-generation request.
     * @return The service response.
     */
    suspend fun startGeneration(request: StartPolicyGenerationRequest): StartPolicyGenerationResponse =
        client.startPolicyGeneration(request)

    /** Read asynchronous policy-generation status.
     *
     * @param request Policy-generation lookup request.
     * @return The service response.
     */
    suspend fun getGeneration(request: GetPolicyGenerationRequest): GetPolicyGenerationResponse =
        client.getPolicyGeneration(request)

    /**
     * Attach a Policy Engine to a Gateway using the control-plane update
     * operation. The SDK models this association as Gateway configuration,
     * rather than as a separate association API.
     *
     * @param binding Gateway-to-policy binding.
     * @return The updated Gateway response.
     */
    suspend fun bindGateway(binding: AgentCoreGatewayPolicyBinding): UpdateGatewayResponse =
        client.getGateway(
            GetGatewayRequest { gatewayIdentifier = binding.gatewayIdentifier }
        ).let { gateway ->
            client.updateGateway(
                UpdateGatewayRequest {
                    gatewayIdentifier = binding.gatewayIdentifier
                    name = gateway.name
                    description = gateway.description
                    roleArn = requireNotNull(gateway.roleArn) {
                        "Gateway '${binding.gatewayIdentifier}' has no execution role ARN."
                    }
                    authorizerType = gateway.authorizerType
                    authorizerConfiguration = gateway.authorizerConfiguration
                    protocolConfiguration = gateway.protocolConfiguration
                    customTransformConfiguration = gateway.customTransformConfiguration
                    interceptorConfigurations = gateway.interceptorConfigurations
                    kmsKeyArn = gateway.kmsKeyArn
                    exceptionLevel = gateway.exceptionLevel
                    wafConfiguration = gateway.wafConfiguration
                    policyEngineConfiguration {
                        arn = binding.policyEngineIdentifier
                        mode = when(binding.mode)
                        {
                            AgentCorePolicyMode.LOG_ONLY -> GatewayPolicyEngineMode.LogOnly
                            AgentCorePolicyMode.ENFORCE -> GatewayPolicyEngineMode.Enforce
                        }
                    }
                }
            )
        }
}

/** Build Policy administration from shared clients. */
fun AgentCoreClients.policyAdmin(): AgentCorePolicyAdmin = AgentCorePolicyAdmin(control)

/**
 * Describes an explicit Gateway-to-Policy Engine association.
 *
 * @param gatewayIdentifier Gateway identifier.
 * @param policyEngineIdentifier Policy Engine identifier.
 * @param mode Policy enforcement mode.
 */
data class AgentCoreGatewayPolicyBinding(
    val gatewayIdentifier: String,
    val policyEngineIdentifier: String,
    val mode: AgentCorePolicyMode = AgentCorePolicyMode.LOG_ONLY
)
