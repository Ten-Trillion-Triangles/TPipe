package com.TTT.AgentCore.LiveSmoke

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.util.UUID

/** Result state for one live smoke case. */
enum class SmokeStatus
{
    PASS,
    FAIL,
    BLOCKED,
    CLEAN,
    SKIPPED,
    UNSUPPORTED
}

/** Failure taxonomy kept separate so protocol and cleanup regressions are not conflated. */
enum class SmokeFailureClass
{
    DEPLOYMENT,
    PROTOCOL,
    STREAMING,
    SESSION,
    AUTHENTICATION,
    MEMORY,
    POLICY,
    TOOL,
    MODEL,
    EVALUATION,
    OBSERVABILITY,
    CLEANUP
}

/** Machine-readable result for one capability assertion. */
data class SmokeCaseResult(
    val id: String,
    val status: SmokeStatus,
    val startedAt: String,
    val finishedAt: String,
    val evidence: Map<String, String> = emptyMap(),
    val requestIds: List<String> = emptyList(),
    val traceIds: List<String> = emptyList(),
    val message: String? = null,
    val failureClass: SmokeFailureClass? = null
)

/** Run-level smoke report written even when setup or cleanup fails. */
data class SmokeReport(
    val runId: String,
    val region: String,
    val startedAt: String,
    val finishedAt: String,
    val cleanupStatus: SmokeStatus,
    val cases: List<SmokeCaseResult>,
    val notes: List<String> = emptyList()
)
{
    /** Whether any case failed or cleanup was not proven clean. */
    fun hasFailure(): Boolean = cleanupStatus == SmokeStatus.FAIL ||
        cleanupStatus == SmokeStatus.BLOCKED ||
        cases.any { it.status == SmokeStatus.FAIL || it.status == SmokeStatus.BLOCKED }
}

/** Exact resource identity recorded immediately after a successful create. */
data class OwnedResource(
    val type: String,
    val name: String,
    val id: String? = null,
    val parentId: String? = null,
    val arn: String? = null,
    val stackId: String? = null,
    val region: String,
    val requestId: String? = null,
    val createdAt: String = Instant.now().toString(),
    val runTag: String? = null
)

/** Internal marker for a cleanup safety violation, distinct from AWS API failure. */
class SmokeCleanupBlockedException(message: String) : IllegalStateException(message)

/** Append-safe manifest used to constrain cleanup to resources this run owns. */
class ResourceManifest(
    val runId: String,
    private val path: Path
)
{
    private val resources = mutableListOf<OwnedResource>()

    init
    {
        if(Files.exists(path))
        {
            val existing = JsonParser.parseManifest(Files.readString(path))
            require(existing.all { isRunOwned(it) && it.region.isNotBlank() }) {
                "Existing manifest contains a resource outside run '$runId'."
            }
            resources += existing
        }
    }

    /** Return a snapshot of resources recorded for this run. */
    fun resources(): List<OwnedResource> = resources.toList()

    /** Record one resource and persist the manifest immediately. */
    @Synchronized
    fun record(resource: OwnedResource)
    {
        require(isRunOwned(resource)) {
            "Refusing to record a resource outside run '$runId': ${resource.name}"
        }
        require(resource.region.isNotBlank()) { "Owned resource region must not be blank." }
        if(resources.none { it == resource })
        {
            resources += resource
            write()
        }
    }

    /** Return true only for an exact manifest entry with the same run identity. */
    fun owns(resource: OwnedResource): Boolean = resources.any {
        it == resource && isRunOwned(resource) && it.region == resource.region
    }

    /** Require exact ownership before executing a destructive cleanup action. */
    suspend fun deleteOwned(resource: OwnedResource, delete: suspend (OwnedResource) -> Unit)
    {
        if(!owns(resource))
        {
            throw SmokeCleanupBlockedException(
                "Refusing cleanup for an unrecorded or mismatched resource: ${resource.name}"
            )
        }
        delete(resource)
    }

    /** Persist the current resource list as JSON. */
    @Synchronized
    fun write()
    {
        path.parent?.let { Files.createDirectories(it) }
        Files.writeString(
            path,
            SmokeJson.encodeManifest(resources),
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE
        )
    }

    private fun ownsRunIdentity(name: String): Boolean =
        name.contains(runId) || name.contains(runId.replace('_', '-'))

    private fun isRunOwned(resource: OwnedResource): Boolean =
        resource.runTag == null && ownsRunIdentity(resource.name) || resource.runTag == runId
}

/** Stable, dependency-light JSON encoding for smoke reports and manifests. */
object SmokeJson
{
    /** Encode the exact manifest entries without serializing credentials. */
    fun encodeManifest(resources: List<OwnedResource>): String = JsonArray(
        resources.map { resource -> resourceJson(resource) }
    ).toString()

    /** Encode a complete smoke report for CI and post-run auditing. */
    fun encodeReport(report: SmokeReport): String = buildJsonObject {
        put("runId", report.runId)
        put("region", report.region)
        put("startedAt", report.startedAt)
        put("finishedAt", report.finishedAt)
        put("cleanupStatus", report.cleanupStatus.name)
        put("cases", JsonArray(report.cases.map { case -> caseJson(case) }))
        put("notes", JsonArray(report.notes.map { JsonPrimitive(SmokeRedaction.text(it)) }))
    }.toString()

    private fun resourceJson(resource: OwnedResource): JsonObject = buildJsonObject {
        put("type", resource.type)
        put("name", resource.name)
        resource.id?.let { put("id", it) }
        resource.parentId?.let { put("parentId", it) }
        resource.arn?.let { put("arn", it) }
        resource.stackId?.let { put("stackId", it) }
        put("region", resource.region)
        resource.requestId?.let { put("requestId", it) }
        put("createdAt", resource.createdAt)
        resource.runTag?.let { put("runTag", it) }
    }

    private fun caseJson(case: SmokeCaseResult): JsonObject = buildJsonObject {
        put("id", case.id)
        put("status", case.status.name)
        put("startedAt", case.startedAt)
        put("finishedAt", case.finishedAt)
        put("evidence", buildJsonObject {
            case.evidence.forEach { (key, value) -> put(key, SmokeRedaction.text(value)) }
        })
        put("requestIds", JsonArray(case.requestIds.map(::JsonPrimitive)))
        put("traceIds", JsonArray(case.traceIds.map(::JsonPrimitive)))
        case.message?.let { put("message", SmokeRedaction.text(it)) }
        case.failureClass?.let { put("failureClass", it.name) }
    }
}

private object JsonParser
{
    fun parseManifest(value: String): List<OwnedResource>
    {
        return kotlinx.serialization.json.Json.parseToJsonElement(value).jsonArray.map { element ->
            val json = element.jsonObject
            OwnedResource(
                type = json["type"]?.jsonPrimitive?.content.orEmpty(),
                name = json["name"]?.jsonPrimitive?.content.orEmpty(),
                id = json["id"]?.jsonPrimitive?.content,
                parentId = json["parentId"]?.jsonPrimitive?.content,
                arn = json["arn"]?.jsonPrimitive?.content,
                stackId = json["stackId"]?.jsonPrimitive?.content,
                region = json["region"]?.jsonPrimitive?.content.orEmpty(),
                requestId = json["requestId"]?.jsonPrimitive?.content,
                createdAt = json["createdAt"]?.jsonPrimitive?.content ?: Instant.now().toString(),
                runTag = json["runTag"]?.jsonPrimitive?.content
            )
        }
    }

}

/** Runtime and service identifiers supplied by the explicit live-test command. */
data class LiveSmokeConfig(
    val region: String,
    val runId: String,
    val outputPath: Path,
    val httpEndpoint: String? = null,
    val mcpEndpoint: String? = null,
    val aguiEndpoint: String? = null,
    val runtimeArn: String? = null,
    val httpRuntimeArn: String? = null,
    val mcpRuntimeArn: String? = null,
    val aguiRuntimeArn: String? = null,
    val instancesRuntimeArn: String? = null,
    val instancesEndpoint: String? = null,
    val instancesSessionId: String? = null,
    val shellRuntimeSessionId: String? = null,
    val shellId: String? = null,
    val capacityProviderId: String? = null,
    val gatewayEndpoint: String? = null,
    val gatewayIdentifier: String? = null,
    val gatewayTargetId: String? = null,
    val gatewayRuleId: String? = null,
    val gatewayRateLimitId: String? = null,
    val memoryId: String? = null,
    val memoryNamespace: String? = null,
    val browserIdentifier: String? = null,
    val browserCustomIdentifier: String? = null,
    val browserProfileIdentifier: String? = null,
    val codeInterpreterIdentifier: String? = null,
    val codeInterpreterCustomIdentifier: String? = null,
    val workloadName: String? = null,
    val oauth2CredentialProviderName: String? = null,
    val consentOauth2CredentialProviderName: String? = null,
    val apiKeyCredentialProviderName: String? = null,
    val apiKeySecretArn: String? = null,
    val resourceCredentialProviderName: String? = null,
    val identityVerificationEndpoint: String? = null,
    val consentPortalId: String? = null,
    val consentCallbackUrl: String? = null,
    val consentUserId: String = "tpipe-live-smoke-user",
    val harnessArn: String? = null,
    val modelId: String? = null,
    val evaluationEvaluatorId: String? = null,
    val evaluationDatasetId: String? = null,
    val evaluationConfigurationBundleId: String? = null,
    val evaluationRecommendationId: String? = null,
    val evaluationAbTestId: String? = null,
    val evaluationTraceId: String? = null,
    val evaluationBatchLogGroup: String? = null,
    val evaluationServiceName: String? = null,
    val evaluationInsightsLogGroup: String? = null,
    val onlineEvaluationConfigId: String? = null,
    val policyGatewayIdentifier: String? = null,
    val policyEngineId: String? = null,
    val policySessionId: String? = null,
    val registryId: String? = null,
    val registryRecordId: String? = null,
    val paymentManagerId: String? = null,
    val paymentConnectorId: String? = null,
    val paymentSessionId: String? = null,
    val browserStableUrl: String? = null,
    val caseFilter: Set<String>? = null,
    val manifestPath: Path? = null
)
{
    companion object
    {
        /** Build configuration from environment without inventing resource IDs. */
        fun fromEnvironment(environment: Map<String, String> = System.getenv()): LiveSmokeConfig
        {
            val region = environment["TPIPE_AGENTCORE_REGION"]
                ?: environment["AWS_REGION"]
                ?: environment["AWS_DEFAULT_REGION"]
                ?: error("TPIPE_AGENTCORE_REGION or AWS_REGION is required.")
            val runId = environment["TPIPE_AGENTCORE_RUN_ID"] ?: generatedRunId()
            require(runId.matches(Regex("[A-Za-z][A-Za-z0-9_]{0,47}"))) {
                "TPIPE_AGENTCORE_RUN_ID must be 1-48 characters, start with a letter, and use only letters, digits, or underscores."
            }
            return LiveSmokeConfig(
                region = region,
                runId = runId,
                outputPath = Path.of(environment["TPIPE_AGENTCORE_REPORT"] ?: "build/agentcore-live-smoke/report.json"),
                httpEndpoint = environment["TPIPE_AGENTCORE_HTTP_ENDPOINT"],
                mcpEndpoint = environment["TPIPE_AGENTCORE_MCP_ENDPOINT"],
                aguiEndpoint = environment["TPIPE_AGENTCORE_AGUI_ENDPOINT"],
                runtimeArn = environment["TPIPE_AGENTCORE_RUNTIME_ARN"],
                httpRuntimeArn = environment["TPIPE_AGENTCORE_HTTP_RUNTIME_ARN"],
                mcpRuntimeArn = environment["TPIPE_AGENTCORE_MCP_RUNTIME_ARN"],
                aguiRuntimeArn = environment["TPIPE_AGENTCORE_AGUI_RUNTIME_ARN"],
                instancesRuntimeArn = environment["TPIPE_AGENTCORE_INSTANCES_RUNTIME_ARN"],
                instancesEndpoint = environment["TPIPE_AGENTCORE_INSTANCES_ENDPOINT"],
                instancesSessionId = environment["TPIPE_AGENTCORE_INSTANCES_SESSION_ID"],
                shellRuntimeSessionId = environment["TPIPE_AGENTCORE_SHELL_RUNTIME_SESSION_ID"],
                shellId = environment["TPIPE_AGENTCORE_SHELL_ID"],
                capacityProviderId = environment["TPIPE_AGENTCORE_CAPACITY_PROVIDER_ID"],
                gatewayEndpoint = environment["TPIPE_AGENTCORE_GATEWAY_ENDPOINT"],
                gatewayIdentifier = environment["TPIPE_AGENTCORE_GATEWAY_IDENTIFIER"],
                gatewayTargetId = environment["TPIPE_AGENTCORE_GATEWAY_TARGET_ID"],
                gatewayRuleId = environment["TPIPE_AGENTCORE_GATEWAY_RULE_ID"],
                gatewayRateLimitId = environment["TPIPE_AGENTCORE_GATEWAY_RATE_LIMIT_ID"],
                memoryId = environment["TPIPE_AGENTCORE_MEMORY_ID"],
                memoryNamespace = environment["TPIPE_AGENTCORE_MEMORY_NAMESPACE"],
                browserIdentifier = environment["TPIPE_AGENTCORE_BROWSER_IDENTIFIER"],
                browserCustomIdentifier = environment["TPIPE_AGENTCORE_BROWSER_CUSTOM_IDENTIFIER"],
                browserProfileIdentifier = environment["TPIPE_AGENTCORE_BROWSER_PROFILE_IDENTIFIER"],
                codeInterpreterIdentifier = environment["TPIPE_AGENTCORE_CODE_INTERPRETER_IDENTIFIER"],
                codeInterpreterCustomIdentifier = environment["TPIPE_AGENTCORE_CODE_INTERPRETER_CUSTOM_IDENTIFIER"],
                workloadName = environment["TPIPE_AGENTCORE_WORKLOAD_NAME"],
                oauth2CredentialProviderName = environment["TPIPE_AGENTCORE_OAUTH2_CREDENTIAL_PROVIDER_NAME"],
                consentOauth2CredentialProviderName = environment["TPIPE_AGENTCORE_CONSENT_OAUTH2_PROVIDER_NAME"],
                apiKeyCredentialProviderName = environment["TPIPE_AGENTCORE_API_KEY_CREDENTIAL_PROVIDER_NAME"],
                apiKeySecretArn = environment["TPIPE_AGENTCORE_API_KEY_SECRET_ARN"],
                resourceCredentialProviderName = environment["TPIPE_AGENTCORE_RESOURCE_CREDENTIAL_PROVIDER_NAME"],
                identityVerificationEndpoint = environment["TPIPE_AGENTCORE_IDENTITY_VERIFY_ENDPOINT"],
                consentPortalId = environment["TPIPE_AGENTCORE_CONSENT_PORTAL_ID"],
                consentCallbackUrl = environment["TPIPE_AGENTCORE_CONSENT_CALLBACK_URL"]
                    ?: "http://127.0.0.1:43127/callback",
                consentUserId = environment["TPIPE_AGENTCORE_CONSENT_USER_ID"] ?: "tpipe-live-smoke-user",
                harnessArn = environment["TPIPE_AGENTCORE_HARNESS_ARN"],
                modelId = environment["TPIPE_AGENTCORE_MODEL_ID"],
                evaluationEvaluatorId = environment["TPIPE_AGENTCORE_EVALUATOR_ID"],
                evaluationDatasetId = environment["TPIPE_AGENTCORE_DATASET_ID"],
                evaluationConfigurationBundleId = environment["TPIPE_AGENTCORE_CONFIGURATION_BUNDLE_ID"],
                evaluationRecommendationId = environment["TPIPE_AGENTCORE_RECOMMENDATION_ID"],
                evaluationAbTestId = environment["TPIPE_AGENTCORE_AB_TEST_ID"],
                evaluationTraceId = environment["TPIPE_AGENTCORE_EVALUATION_TRACE_ID"],
                evaluationBatchLogGroup = environment["TPIPE_AGENTCORE_EVALUATION_LOG_GROUP"],
                evaluationServiceName = environment["TPIPE_AGENTCORE_EVALUATION_SERVICE_NAME"],
                evaluationInsightsLogGroup = environment["TPIPE_AGENTCORE_EVALUATION_INSIGHTS_LOG_GROUP"],
                onlineEvaluationConfigId = environment["TPIPE_AGENTCORE_ONLINE_EVALUATION_CONFIG_ID"],
                policyGatewayIdentifier = environment["TPIPE_AGENTCORE_POLICY_GATEWAY_ID"],
                policyEngineId = environment["TPIPE_AGENTCORE_POLICY_ENGINE_ID"],
                policySessionId = environment["TPIPE_AGENTCORE_POLICY_SESSION_ID"],
                registryId = environment["TPIPE_AGENTCORE_REGISTRY_ID"],
                registryRecordId = environment["TPIPE_AGENTCORE_REGISTRY_RECORD_ID"],
                paymentManagerId = environment["TPIPE_AGENTCORE_PAYMENT_MANAGER_ID"],
                paymentConnectorId = environment["TPIPE_AGENTCORE_PAYMENT_CONNECTOR_ID"],
                paymentSessionId = environment["TPIPE_AGENTCORE_PAYMENT_SESSION_ID"],
                browserStableUrl = environment["TPIPE_AGENTCORE_BROWSER_STABLE_URL"],
                caseFilter = environment["TPIPE_AGENTCORE_CASES"]
                    ?.split(',')
                    ?.map(String::trim)
                    ?.filter(String::isNotBlank)
                    ?.toSet()
                    ?.takeIf { it.isNotEmpty() },
                manifestPath = environment["TPIPE_AGENTCORE_MANIFEST"]?.let(Path::of)
            )
        }

        private fun generatedRunId(): String = "tpipe_smoke_${UUID.randomUUID().toString().replace("-", "").take(12)}"
    }
}

/** Redaction used for report messages and evidence fields. */
object SmokeRedaction
{
    private val bearer = Regex("(?i)(bearer\\s+)[^\\s,]+")
    private val accessKey = Regex("(?i)(AKIA|ASIA)[A-Z0-9]{16}")
    private val authorization = Regex("(?i)(authorization\\s*[:=]\\s*)[^,\\s]+")
    private val secretField = Regex(
        "(?i)((?:client[_-]?secret|api[_-]?key|access[_-]?token|refresh[_-]?token|password|secret[_-]?access[_-]?key)\\s*[:=]\\s*)[\\\"']?[^,\\s}\\\"']+"
    )

    /** Remove common AWS credential and bearer-token forms from diagnostic text. */
    fun text(value: String): String = value
        .replace(bearer, "$1[REDACTED]")
        .replace(accessKey, "[REDACTED_AWS_KEY]")
        .replace(authorization, "$1[REDACTED]")
        .replace(secretField, "$1[REDACTED]")
        .take(4_096)
}

/** Build an AWS-compatible idempotency token from a run id. */
object SmokeClientTokens
{
    /** Return a deterministic token using only the service-accepted alphabet. */
    fun forRun(runId: String, operation: String): String
    {
        val token = "$runId-$operation".replace('_', '-')
        require(token.matches(Regex("[A-Za-z0-9](?:-*[A-Za-z0-9]){0,256}"))) {
            "Smoke client token contains an unsupported character."
        }
        return token
    }
}
