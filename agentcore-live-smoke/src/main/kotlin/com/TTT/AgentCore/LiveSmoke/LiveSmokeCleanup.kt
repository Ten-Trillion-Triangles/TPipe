package com.TTT.AgentCore.LiveSmoke

import aws.sdk.kotlin.services.bedrockagentcorecontrol.model.DeleteAgentRuntimeEndpointRequest
import aws.sdk.kotlin.services.bedrockagentcorecontrol.model.DeleteAgentRuntimeRequest
import aws.sdk.kotlin.services.bedrockagentcorecontrol.model.DeleteEvaluatorRequest
import aws.sdk.kotlin.services.bedrockagentcorecontrol.model.DeleteGatewayRequest
import aws.sdk.kotlin.services.bedrockagentcorecontrol.model.DeleteGatewayTargetRequest
import aws.sdk.kotlin.services.bedrockagentcorecontrol.model.DeleteHarnessEndpointRequest
import aws.sdk.kotlin.services.bedrockagentcorecontrol.model.DeleteHarnessRequest
import aws.sdk.kotlin.services.bedrockagentcorecontrol.model.DeleteMemoryRequest
import aws.sdk.kotlin.services.bedrockagentcorecontrol.model.DeleteOnlineEvaluationConfigRequest
import aws.sdk.kotlin.services.bedrockagentcorecontrol.model.DeleteApiKeyCredentialProviderRequest
import aws.sdk.kotlin.services.bedrockagentcorecontrol.model.DeleteOauth2CredentialProviderRequest
import aws.sdk.kotlin.services.bedrockagentcorecontrol.model.DeletePolicyEngineRequest
import aws.sdk.kotlin.services.bedrockagentcorecontrol.model.DeletePolicyRequest
import aws.sdk.kotlin.services.bedrockagentcorecontrol.model.DeleteWorkloadIdentityRequest
import aws.sdk.kotlin.services.bedrockagentcorecontrol.model.DeleteResourcePolicyRequest
import com.TTT.AgentCore.AgentCoreClients
import com.TTT.AgentCore.control.controlPlane
import com.TTT.AgentCore.evaluations.evaluationAdmin
import com.TTT.AgentCore.gateway.gatewayAdmin
import com.TTT.AgentCore.harness.harnessAdmin
import com.TTT.AgentCore.identity.identityAdmin
import com.TTT.AgentCore.policy.policyAdmin
import com.TTT.AgentCore.runtime.runtimeAdmin

/** Result of an exact manifest cleanup attempt. */
data class SmokeCleanupResult(
    val status: SmokeStatus,
    val deleted: List<String> = emptyList(),
    val blocked: String? = null
)

/**
 * Deletes only resources recorded in a run manifest.
 *
 * CloudFormation, ECR, IAM, and CloudWatch operations are supplied by the
 * controller because those calls are performed through the AWS MCP control
 * plane. AgentCore control-plane resources use the pinned SDK directly. The
 * callbacks receive exact recorded identities, never a discovered prefix.
 */
class AgentCoreLiveSmokeCleanup(
    private val clients: AgentCoreClients,
    private val deleteCloudFormationStack: suspend (OwnedResource) -> Unit = {},
    private val deleteExternalResource: suspend (OwnedResource) -> Unit = {},
    private val stopSessions: suspend () -> Unit = {}
)
{
    /** Stop sessions and delete every exact resource in reverse creation order. */
    suspend fun execute(manifest: ResourceManifest): SmokeCleanupResult
    {
        val deleted = mutableListOf<String>()
        return try
        {
            stopSessions()
            manifest.resources().asReversed().forEach { resource ->
                manifest.deleteOwned(resource) {
                    deleteResource(resource)
                    deleted += resource.arn ?: resource.id ?: resource.name
                }
            }
            SmokeCleanupResult(SmokeStatus.CLEAN, deleted)
        }
        catch(exception: SmokeCleanupBlockedException)
        {
            SmokeCleanupResult(SmokeStatus.BLOCKED, deleted, SmokeRedaction.text(exception.message.orEmpty()))
        }
        catch(exception: Exception)
        {
            SmokeCleanupResult(SmokeStatus.FAIL, deleted, SmokeRedaction.text(exception.message.orEmpty()))
        }
    }

    private suspend fun deleteResource(resource: OwnedResource)
    {
        when(resource.type)
        {
            "cloudformation-stack" -> deleteCloudFormationStack(resource)
            "runtime-endpoint" -> clients.runtimeAdmin().deleteEndpoint(
                DeleteAgentRuntimeEndpointRequest {
                    agentRuntimeId = requireId(resource)
                    endpointName = requireName(resource)
                }
            )
            "runtime" -> clients.runtimeAdmin().delete(
                DeleteAgentRuntimeRequest { agentRuntimeId = requireId(resource) }
            )
            "gateway-target" -> clients.gatewayAdmin().deleteTarget(
                DeleteGatewayTargetRequest {
                    gatewayIdentifier = requireParentId(resource)
                    targetId = requireId(resource)
                }
            )
            "gateway" -> clients.gatewayAdmin().delete(
                DeleteGatewayRequest { gatewayIdentifier = requireId(resource) }
            )
            "memory" -> clients.controlPlane().execute {
                deleteMemory(DeleteMemoryRequest { memoryId = requireId(resource) })
            }
            "policy" -> clients.policyAdmin().deletePolicy(
                DeletePolicyRequest {
                    policyEngineId = requireParentId(resource)
                    policyId = requireId(resource)
                }
            )
            "policy-engine" -> clients.policyAdmin().deleteEngine(
                DeletePolicyEngineRequest { policyEngineId = requireId(resource) }
            )
            "resource-policy" -> clients.controlPlane().execute {
                deleteResourcePolicy(
                    DeleteResourcePolicyRequest { resourceArn = resource.arn ?: requireId(resource) }
                )
            }
            "workload-identity" -> clients.identityAdmin().deleteWorkloadIdentity(
                DeleteWorkloadIdentityRequest { name = requireName(resource) }
            )
            "harness-endpoint" -> clients.harnessAdmin().deleteEndpoint(
                DeleteHarnessEndpointRequest {
                    harnessId = requireParentId(resource)
                    endpointName = requireName(resource)
                }
            )
            "harness" -> clients.harnessAdmin().delete(
                DeleteHarnessRequest { harnessId = requireId(resource) }
            )
            "evaluator" -> clients.evaluationAdmin().deleteEvaluator(
                DeleteEvaluatorRequest { evaluatorId = requireId(resource) }
            )
            "online-evaluation-config" -> clients.evaluationAdmin().execute {
                deleteOnlineEvaluationConfig(
                    DeleteOnlineEvaluationConfigRequest { onlineEvaluationConfigId = requireId(resource) }
                )
            }
            "api-key-credential-provider" -> clients.controlPlane().execute {
                deleteApiKeyCredentialProvider(
                    DeleteApiKeyCredentialProviderRequest { name = requireName(resource) }
                )
            }
            "oauth2-credential-provider" -> clients.controlPlane().execute {
                deleteOauth2CredentialProvider(
                    DeleteOauth2CredentialProviderRequest { name = requireName(resource) }
                )
            }
            "ecr-image", "ecr-repository", "iam-role", "log-group" -> deleteExternalResource(resource)
            else -> error("Unknown manifest resource type '${resource.type}'.")
        }
    }

    private fun requireId(resource: OwnedResource): String = resource.id
        ?: throw SmokeCleanupBlockedException("Manifest resource '${resource.name}' has no exact id.")

    private fun requireName(resource: OwnedResource): String = resource.name

    private fun requireParentId(resource: OwnedResource): String = resource.parentId
        ?: throw SmokeCleanupBlockedException("Manifest resource '${resource.name}' has no exact parent id.")
}
