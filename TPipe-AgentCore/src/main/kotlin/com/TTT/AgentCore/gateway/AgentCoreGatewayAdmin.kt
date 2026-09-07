package com.TTT.AgentCore.gateway

import aws.sdk.kotlin.services.bedrockagentcorecontrol.BedrockAgentCoreControlClient
import aws.sdk.kotlin.services.bedrockagentcorecontrol.model.*
import com.TTT.AgentCore.AgentCoreClients

/**
 * Typed control-plane convenience methods for Gateway lifecycle operations.
 *
 * @param client AgentCore control-plane client.
 */
class AgentCoreGatewayAdmin(private val client: BedrockAgentCoreControlClient)
{
    /** Create a Gateway.
     *
     * @param request Gateway creation request.
     * @return The service response.
     */
    suspend fun create(request: CreateGatewayRequest): CreateGatewayResponse = client.createGateway(request)

    /** Read a Gateway.
     *
     * @param request Gateway lookup request.
     * @return The service response.
     */
    suspend fun get(request: GetGatewayRequest): GetGatewayResponse = client.getGateway(request)

    /** Update a Gateway.
     *
     * @param request Gateway update request.
     * @return The service response.
     */
    suspend fun update(request: UpdateGatewayRequest): UpdateGatewayResponse = client.updateGateway(request)

    /** Delete a Gateway.
     *
     * @param request Gateway deletion request.
     * @return The service response.
     */
    suspend fun delete(request: DeleteGatewayRequest): DeleteGatewayResponse = client.deleteGateway(request)

    /** Create an external MCP target.
     *
     * @param request Target creation request.
     * @return The service response.
     */
    suspend fun createTarget(request: CreateGatewayTargetRequest): CreateGatewayTargetResponse =
        client.createGatewayTarget(request)

    /** Read an external MCP target.
     *
     * @param request Target lookup request.
     * @return The service response.
     */
    suspend fun getTarget(request: GetGatewayTargetRequest): GetGatewayTargetResponse = client.getGatewayTarget(request)

    /** Update an external MCP target.
     *
     * @param request Target update request.
     * @return The service response.
     */
    suspend fun updateTarget(request: UpdateGatewayTargetRequest): UpdateGatewayTargetResponse =
        client.updateGatewayTarget(request)

    /** Delete an external MCP target.
     *
     * @param request Target deletion request.
     * @return The service response.
     */
    suspend fun deleteTarget(request: DeleteGatewayTargetRequest): DeleteGatewayTargetResponse =
        client.deleteGatewayTarget(request)

    /** Start bounded Gateway target synchronization.
     *
     * @param request Synchronization request.
     * @return The service response.
     */
    suspend fun synchronize(request: SynchronizeGatewayTargetsRequest): SynchronizeGatewayTargetsResponse =
        client.synchronizeGatewayTargets(request)

    /** Create a Gateway rate limit. */
    suspend fun createRateLimit(request: CreateGatewayRateLimitRequest): CreateGatewayRateLimitResponse =
        request.also { validateRateLimit(it) }.let { client.createGatewayRateLimit(it) }

    /** Read a Gateway rate limit. */
    suspend fun getRateLimit(request: GetGatewayRateLimitRequest): GetGatewayRateLimitResponse =
        client.getGatewayRateLimit(request)

    /** List Gateway rate limits. */
    suspend fun listRateLimits(request: ListGatewayRateLimitsRequest): ListGatewayRateLimitsResponse =
        client.listGatewayRateLimits(request)

    /** Update a Gateway rate limit. */
    suspend fun updateRateLimit(request: UpdateGatewayRateLimitRequest): UpdateGatewayRateLimitResponse =
        request.also { validateRateLimitEntries(it.entries) }.let { client.updateGatewayRateLimit(it) }

    /** Delete a Gateway rate limit. */
    suspend fun deleteRateLimit(request: DeleteGatewayRateLimitRequest): DeleteGatewayRateLimitResponse =
        client.deleteGatewayRateLimit(request)

    /** Batch-put Gateway rate limits. AWS permits at most 50 rate-limit definitions. */
    suspend fun batchPutRateLimits(
        request: BatchPutGatewayRateLimitsRequest
    ): BatchPutGatewayRateLimitsResponse = request.also { validateBatchRateLimits(it) }
        .let { client.batchPutGatewayRateLimits(it) }

    /** Create a Gateway routing rule. */
    suspend fun createRule(request: CreateGatewayRuleRequest): CreateGatewayRuleResponse =
        request.also { validateRule(it.actions, it.conditions, it.priority) }
            .let { client.createGatewayRule(it) }

    /** Read a Gateway routing rule. */
    suspend fun getRule(request: GetGatewayRuleRequest): GetGatewayRuleResponse = client.getGatewayRule(request)

    /** List Gateway routing rules. */
    suspend fun listRules(request: ListGatewayRulesRequest): ListGatewayRulesResponse =
        client.listGatewayRules(request)

    /** Update a Gateway routing rule. */
    suspend fun updateRule(request: UpdateGatewayRuleRequest): UpdateGatewayRuleResponse =
        request.also { validateRule(it.actions, it.conditions, it.priority) }
            .let { client.updateGatewayRule(it) }

    /** Delete a Gateway routing rule. */
    suspend fun deleteRule(request: DeleteGatewayRuleRequest): DeleteGatewayRuleResponse =
        client.deleteGatewayRule(request)

    /** Validate caller-owned Gateway rate-limit dimensions before dispatch. */
    fun validateRateLimitDimensions(dimensions: Collection<String>)
    {
        require(dimensions.size in 1..10) { "Gateway rate limits require 1 to 10 dimensions." }
        require(dimensions.all { it.isNotBlank() }) { "Gateway rate-limit dimensions must not be blank." }
        require(dimensions.distinct().size == dimensions.size) {
            "Gateway rate-limit dimensions must be unique."
        }
        val wildcardIndex = dimensions.indexOf("*")
        require(wildcardIndex == -1 || wildcardIndex == dimensions.size - 1) {
            "A wildcard Gateway rate-limit dimension must be last."
        }
    }

    /** Validate a Gateway rate-limit definition before dispatch. */
    fun validateRateLimit(request: CreateGatewayRateLimitRequest)
    {
        validateRateLimitDimensions(request.dimensionKeys.orEmpty())
        validateRateLimitEntries(request.entries, request.dimensionKeys.orEmpty())
        validateOptionalIdentifier(request.rateLimitId, "rate-limit")
        validateOptionalDescription(request.description)
    }

    /** Validate the AWS exactly-two-variants weighted-routing constraint. */
    fun validateWeightedVariants(variants: Collection<*>)
    {
        require(variants.size == 2) { "Gateway weighted routing requires exactly two variants." }
        when
        {
            variants.all { it is TargetTrafficSplitEntry } ->
                validateWeightedVariantDetails(
                    variants.map { it as TargetTrafficSplitEntry }.map { it.name to it.weight }
                )
            variants.all { it is TrafficSplitEntry } ->
                validateWeightedVariantDetails(
                    variants.map { it as TrafficSplitEntry }.map { it.name to it.weight }
                )
            else -> require(false) {
                "Gateway weighted routing variants must use one supported variant model."
            }
        }
    }

    private fun validateBatchRateLimits(request: BatchPutGatewayRateLimitsRequest)
    {
        val rateLimits = request.rateLimits.orEmpty()
        require(rateLimits.size in 1..50) {
            "Gateway rate-limit batch requests require 1 to 50 definitions."
        }
        rateLimits.forEach { rateLimit ->
            validateRateLimitDimensions(rateLimit.dimensionKeys.orEmpty())
            validateRateLimitEntries(rateLimit.entries, rateLimit.dimensionKeys.orEmpty())
            validateOptionalIdentifier(rateLimit.rateLimitId, "rate-limit")
            validateOptionalDescription(rateLimit.description)
        }
    }

    private fun validateRateLimitEntries(
        entries: List<LimitEntry>?,
        dimensionKeys: List<String>? = null
    )
    {
        val values = entries.orEmpty()
        require(values.size in 1..1_000) {
            "Gateway rate limits require 1 to 1,000 limit entries."
        }
        val expectedKeys = dimensionKeys?.toSet()
        values.forEach { entry ->
            if(expectedKeys != null)
            {
                require(entry.dimensions?.keys == expectedKeys) {
                    "Each Gateway rate-limit entry must provide every dimension exactly once."
                }
            }
            entry.dimensions.orEmpty().forEach { (key, value) ->
                require(key.isNotBlank() && value.isNotBlank() && value.length <= 256) {
                    "Gateway rate-limit dimension names and values must be non-blank and values must be at most 256 characters."
                }
            }
            validateRateConfigs(entry.connections, "connections")
            validateRateConfigs(entry.requests, "requests")
            validateRateConfigs(entry.tokens, "tokens")
            require(!entry.connections.isNullOrEmpty() || !entry.requests.isNullOrEmpty() || !entry.tokens.isNullOrEmpty()) {
                "Each Gateway rate-limit entry must contain a connection, request, or token rate."
            }
        }
    }

    private fun validateRateConfigs(configs: List<RateConfig>?, metric: String)
    {
        val values = configs.orEmpty()
        require(values.size <= 2) { "A Gateway $metric rate may specify at most two periods." }
        require(values.map { it.period }.distinct().size == values.size) {
            "A Gateway $metric rate cannot repeat a period."
        }
        values.forEach { config ->
            require(config.period == Period.Second || config.period == Period.Minute) {
                "Gateway $metric rates must use SECOND or MINUTE periods."
            }
            if(metric == "connections")
            {
                require(config.period == Period.Second) {
                    "Gateway connection rates only support the SECOND period."
                }
            }
            require(config.rate in 0.0..10_000_000.0) {
                "Gateway rate values must be between 0 and 10,000,000."
            }
        }
    }

    private fun validateRule(
        actions: List<Action>?,
        conditions: List<Condition>?,
        priority: Int?
    )
    {
        require(actions.orEmpty().size in 1..2) { "Gateway rules require 1 to 2 actions." }
        require(conditions.orEmpty().size <= 2) { "Gateway rules support at most two conditions." }
        priority?.let { require(it in 1..1_000_000) { "Gateway rule priority must be between 1 and 1,000,000." } }
        actions.orEmpty().forEach { action ->
            action.asRouteToTargetOrNull()?.asWeightedRouteOrNull()?.trafficSplit?.let(::validateWeightedVariants)
            action.asConfigurationBundleOrNull()?.asWeightedOverrideOrNull()?.trafficSplit
                ?.let(::validateWeightedVariants)
        }
    }

    private fun validateWeightedVariantDetails(variants: List<Pair<String, Int>>)
    {
        require(variants.all { it.first.isNotBlank() }) { "Gateway weighted variant names must not be blank." }
        require(variants.map { it.first }.distinct().size == variants.size) {
            "Gateway weighted variant names must be unique."
        }
        require(variants.all { it.second in 1..99 }) {
            "Gateway weighted variant weights must be between 1 and 99."
        }
        require(variants.sumOf { it.second } == 100) {
            "Gateway weighted variant weights must sum to 100."
        }
    }

    private fun validateOptionalDescription(description: String?)
    {
        require(description == null || description.length <= 512) {
            "Gateway descriptions must be at most 512 characters."
        }
    }

    private fun validateOptionalIdentifier(identifier: String?, label: String)
    {
        require(identifier == null || identifier.length in 2..64) {
            "Gateway $label identifiers must be between 2 and 64 characters."
        }
    }
}

/** Build Gateway administration from the shared client bundle. */
fun AgentCoreClients.gatewayAdmin(): AgentCoreGatewayAdmin = AgentCoreGatewayAdmin(control)
