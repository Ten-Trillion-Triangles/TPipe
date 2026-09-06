package com.TTT.AgentCore.LiveSmoke

import java.time.Instant

/** One immutable runtime image and the protocol used by its CloudFormation stack. */
data class LiveSmokeImage(
    val protocol: String,
    val imageUri: String
)

/** A stack request sent to the AWS MCP control-plane adapter. */
data class LiveSmokeStackRequest(
    val stackName: String,
    val runtimeName: String,
    val runtimeEndpointName: String,
    val protocol: String,
    val imageUri: String,
    val templateBody: String,
    val parameters: Map<String, String>,
    val tags: Map<String, String>
)

/** Successful CloudFormation creation response reduced to safe metadata. */
data class LiveSmokeStackResult(
    val stackId: String,
    val requestId: String?,
    val outputs: Map<String, String>
)

/** Read-only preflight result returned by the AWS MCP control-plane adapter. */
data class LiveSmokePreflight(
    val accountId: String,
    val region: String,
    val existingNames: Set<String> = emptySet(),
    val accessDeniedChecks: Set<String> = emptySet(),
    val modelId: String? = null
)

/**
 * Narrow protocol implemented by the AWS MCP controller.
 *
 * The Kotlin harness deliberately does not receive AWS credentials. The
 * adapter is run through the AWS MCP `run_script` control plane and returns
 * only the metadata needed to continue the run.
 */
interface LiveSmokeAwsMcpControlPlane
{
    /** Read identity, region, quotas/model access, and collision metadata. */
    suspend fun preflight(config: LiveSmokeConfig, desiredNames: Set<String>): LiveSmokePreflight

    /** Create and wait for one run-owned CloudFormation stack. */
    suspend fun createStack(request: LiveSmokeStackRequest): LiveSmokeStackResult

    /** Delete exactly the recorded CloudFormation stack ID. */
    suspend fun deleteStack(stack: OwnedResource)

    /** Return run-owned resources still visible after cleanup. */
    suspend fun postCleanupScan(config: LiveSmokeConfig): Set<String>
}

/** Result of creating the three protocol-specific runtime stacks. */
data class LiveSmokeDeployment(
    val stacks: List<OwnedResource>,
    val endpoints: Map<String, String>
)

/**
 * Provisions the three protocol stacks and then drives exact manifest
 * teardown. All successful creates are recorded before the next operation.
 */
class LiveSmokeDeploymentController(
    private val config: LiveSmokeConfig,
    private val manifest: ResourceManifest,
    private val aws: LiveSmokeAwsMcpControlPlane,
    private val templateBody: String,
    private val cleanupAction: suspend () -> SmokeCleanupResult
)
{
    /**
     * Deploy, run the supplied smoke callback, and clean up even when deploy
     * or a smoke assertion fails after a partial create.
     */
    suspend fun execute(
        images: List<LiveSmokeImage>,
        runSmoke: suspend (LiveSmokeDeployment) -> SmokeReport
    ): SmokeReport
    {
        val startedAt = Instant.now().toString()
        val smokeReport = try
        {
            runSmoke(deploy(images))
        }
        catch(exception: Exception)
        {
            SmokeReport(
                runId = config.runId,
                region = config.region,
                startedAt = startedAt,
                finishedAt = Instant.now().toString(),
                cleanupStatus = SmokeStatus.BLOCKED,
                cases = listOf(
                    SmokeCaseResult(
                        id = "deployment",
                        status = SmokeStatus.FAIL,
                        startedAt = startedAt,
                        finishedAt = Instant.now().toString(),
                        message = SmokeRedaction.text(exception.message.orEmpty())
                    )
                ),
                notes = listOf("Deployment or smoke execution failed; cleanup was still attempted.")
            )
        }
        val cleanupResult = cleanup()
        val cleanupNote = cleanupResult.blocked?.let { "Cleanup blocked: $it" }
            ?: "Deleted ${cleanupResult.deleted.size} exact run-owned resources."
        return smokeReport.copy(
            finishedAt = Instant.now().toString(),
            cleanupStatus = cleanupResult.status,
            notes = smokeReport.notes + cleanupNote
        )
    }

    /** Validate the account and all desired names before any write. */
    suspend fun preflight(images: List<LiveSmokeImage>): LiveSmokePreflight
    {
        require(images.map { it.protocol }.toSet() == setOf("HTTP", "MCP", "AGUI")) {
            "Exactly one HTTP, MCP, and AGUI image is required."
        }
        require(images.all { it.imageUri.isNotBlank() }) { "Every runtime image URI is required." }
        val desiredNames = buildSet {
            images.forEach { image ->
                add(stackName(image.protocol))
                add(agentName(image.protocol))
                add(endpointName(image.protocol))
            }
        }
        val result = aws.preflight(config, desiredNames)
        check(result.region == config.region) {
            "AWS MCP preflight region '${result.region}' did not match '${config.region}'."
        }
        check(result.accessDeniedChecks.isEmpty()) {
            "AWS MCP preflight was incomplete due to AccessDenied: ${result.accessDeniedChecks.sorted()}"
        }
        val collisions = result.existingNames.filter { existing ->
            desiredNames.any { desired ->
                existing == desired || existing.startsWith(desired) || desired.startsWith(existing)
            }
        }.toSet()
        check(collisions.isEmpty()) {
            "Refusing deployment because exact or prefix-colliding names already exist: $collisions"
        }
        return result
    }

    /** Create all three isolated protocol stacks and record their outputs. */
    suspend fun deploy(images: List<LiveSmokeImage>): LiveSmokeDeployment
    {
        preflight(images)
        val stacks = mutableListOf<OwnedResource>()
        val endpoints = mutableMapOf<String, String>()
        images.sortedBy { it.protocol }.forEach { image ->
            val request = LiveSmokeStackRequest(
                stackName = stackName(image.protocol),
                runtimeName = agentName(image.protocol),
                runtimeEndpointName = endpointName(image.protocol),
                protocol = image.protocol,
                imageUri = image.imageUri,
                templateBody = templateBody,
                parameters = mapOf(
                    "RuntimeName" to agentName(image.protocol),
                    "RuntimeProtocol" to image.protocol,
                    "RuntimeImageUri" to image.imageUri,
                    "RuntimeEndpointName" to endpointName(image.protocol),
                    "RuntimeEndpointVersion" to "1"
                ),
                tags = mapOf("TPipeSmokeRun" to config.runId, "ManagedBy" to "TPipeAgentCoreLiveSmoke")
            )
            val created = aws.createStack(request)
            val stack = OwnedResource(
                type = "cloudformation-stack",
                name = request.stackName,
                stackId = created.stackId,
                region = config.region,
                requestId = created.requestId,
                createdAt = Instant.now().toString(),
                runTag = config.runId
            )
            manifest.record(stack)
            stacks += stack
            created.outputs.forEach { (key, value) ->
                endpoints["${image.protocol}.$key"] = value
            }
        }
        return LiveSmokeDeployment(stacks, endpoints)
    }

    /** Run cleanup and require a zero-resource post-cleanup scan for CLEAN. */
    suspend fun cleanup(): SmokeCleanupResult
    {
        val result = cleanupAction()
        if(result.status != SmokeStatus.CLEAN) return result
        return try
        {
            val remaining = aws.postCleanupScan(config)
            if(remaining.isEmpty()) result
            else SmokeCleanupResult(
                status = SmokeStatus.BLOCKED,
                deleted = result.deleted,
                blocked = "Post-cleanup scan found run-owned resources: ${remaining.sorted()}"
            )
        }
        catch(exception: Exception)
        {
            SmokeCleanupResult(
                status = SmokeStatus.BLOCKED,
                deleted = result.deleted,
                blocked = "Post-cleanup scan failed: ${SmokeRedaction.text(exception.message.orEmpty())}"
            )
        }
    }

    private fun stackName(protocol: String): String =
        "tpipe-smoke-${protocol.lowercase()}-${config.runId.replace('_', '-')}"

    private fun agentName(protocol: String): String = checkedName("tpipe_${protocol.lowercase()}_${config.runId}")

    private fun endpointName(protocol: String): String = checkedName("endpoint_${protocol.lowercase()}_${config.runId}")

    private fun checkedName(value: String): String = value.also {
        require(it.length <= 48) {
            "AgentCore name '$it' exceeds the 48-character smoke name limit. Use a shorter run ID."
        }
    }
}
