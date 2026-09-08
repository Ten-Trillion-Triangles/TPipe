package com.TTT.AgentCore.LiveSmoke

/**
 * Canonical live-smoke scope.
 *
 * Payments are deliberately outside this run's scope. A2A remains visible as
 * an explicit unsupported result because TPipe does not expose that protocol.
 * Every other entry is a supported capability and must resolve to PASS or to
 * an honest BLOCKED/FAIL result when the run-owned fixture is incomplete.
 */
object LiveSmokeCaseCatalog
{
    /** Supported capability IDs exercised by the live harness. */
    val supportedCaseIds: Set<String> = linkedSetOf(
        "runtime.http",
        "runtime.streaming",
        "runtime.websocket",
        "runtime.sessions",
        "runtime.command",
        "runtime.shell",
        "runtime.capacity-provider",
        "runtime.capacity-provider-session-delete",
        "runtime.instances",
        "runtime.p2p-adapter",
        "runtime.agui",
        "mcp.pcp",
        "gateway.sigv4",
        "gateway.forwarding",
        "memory.exact",
        "memory.semantic",
        "memory.ingest",
        "gateway.rate-limit",
        "gateway.rule",
        "tools.browser",
        "tools.code-interpreter",
        "tools.custom-filesystem",
        "tools.browser-custom",
        "tools.browser-profile",
        "tools.code-interpreter-custom",
        "identity.workload-token",
        "identity.lifecycle",
        "identity.consent-portal",
        "harness.p2p",
        "model.bedrock",
        "evaluation.on-demand",
        "evaluation.batch",
        "evaluation.insights",
        "evaluation.online",
        "evaluation.dataset",
        "evaluation.configuration-bundle",
        "evaluation.recommendation",
        "evaluation.ab-test",
        "credentials.oauth-api-key",
        "policy.local-adapter",
        "policy.gateway",
        "policy.temporal",
        "registry.lifecycle",
        "observability.local-sink"
    )

    /** Capability explicitly unsupported by TPipe. */
    const val unsupportedA2aCaseId = "capability.a2a"

    /** Capability intentionally omitted by the user for this smoke run. */
    const val excludedPaymentsCasePrefix = "payments."

    /** Whether an ID is part of the supported, non-payment smoke contract. */
    fun isSupported(id: String): Boolean = id in supportedCaseIds

    /** Whether a case is intentionally omitted rather than failed or blocked. */
    fun isExplicitlyExcluded(id: String): Boolean = id.startsWith(excludedPaymentsCasePrefix)
}
