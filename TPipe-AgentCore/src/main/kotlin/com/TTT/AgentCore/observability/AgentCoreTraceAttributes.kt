package com.TTT.AgentCore.observability

/** Stable OTEL attribute keys owned by the AgentCore integration. */
object AgentCoreTraceAttributes
{
    /** Stable provider attribute used by TPipe trace consumers. */
    const val PROVIDER = "tpipe.provider"

    const val runtimeArn = "agentcore.runtime.arn"
    const val runtimeId = "agentcore.runtime.id"
    const val runtimeQualifier = "agentcore.runtime.qualifier"
    const val runtimeSessionId = "agentcore.runtime.session_id"
    const val capacityProvider = "agentcore.runtime.capacity_provider"
    const val computeType = "agentcore.runtime.compute_type"
    const val commandRequestId = "agentcore.runtime.command_request_id"
    const val shellRequestId = "agentcore.runtime.shell_request_id"
    const val gatewayId = "agentcore.gateway.id"
    const val gatewayTargetId = "agentcore.gateway.target_id"
    const val gatewayRuleId = "agentcore.gateway.rule_id"
    const val gatewayRateLimitId = "agentcore.gateway.rate_limit_id"
    const val evaluatorId = "agentcore.evaluation.evaluator_id"
    const val onlineEvaluationConfig = "agentcore.evaluation.online_config"
    const val batchEvaluationId = "agentcore.evaluation.batch_id"
    const val insightType = "agentcore.evaluation.insight_type"
    const val datasetId = "agentcore.evaluation.dataset_id"
    const val datasetVersion = "agentcore.evaluation.dataset_version"
    const val recommendationId = "agentcore.evaluation.recommendation_id"
    const val configurationBundleId = "agentcore.evaluation.configuration_bundle_id"
    const val configurationBundleVersion = "agentcore.evaluation.configuration_bundle_version"
    const val abTestId = "agentcore.evaluation.ab_test_id"
    const val policyEngineId = "agentcore.policy.engine_id"
    const val policyId = "agentcore.policy.policy_id"
    const val policySessionId = "agentcore.policy.session_id"
    const val temporalEvaluation = "agentcore.policy.temporal"
    const val registryId = "agentcore.registry.id"
    const val registryRecordId = "agentcore.registry.record_id"
    const val registryRecordType = "agentcore.registry.record_type"
    const val registryRecordStatus = "agentcore.registry.record_status"
    const val paymentManagerId = "agentcore.payments.manager_id"
    const val paymentConnectorId = "agentcore.payments.connector_id"
    const val paymentSessionId = "agentcore.payments.session_id"
    const val paymentInstrumentId = "agentcore.payments.instrument_id"

    private val sensitiveFragments = setOf(
        "prompt", "context", "content", "inputtext", "outputtext", "reasoning", "oauth", "jwt",
        "token", "credential", "api_key", "apikey", "secret", "private_key", "assertion", "command",
        "shell", "stdin", "stdout", "stderr", "payment_proof", "paymentproof", "authorization"
    )

    /** Whether a metadata key is unsafe to export by default. */
    fun isSensitiveKey(key: String): Boolean = sensitiveFragments.any { fragment ->
        key.lowercase().contains(fragment)
    }

    /** Compatibility alias for callers using the shorter privacy predicate. */
    fun isSensitive(key: String): Boolean = isSensitiveKey(key)

    /** Remove metadata that is private by default without copying its values. */
    fun filterMetadata(metadata: Map<String, *>): Map<String, *> =
        metadata.filterKeys { key -> !isSensitiveKey(key) }
}
