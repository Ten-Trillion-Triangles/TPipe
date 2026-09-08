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
import aws.sdk.kotlin.services.bedrockagentcorecontrol.model.DeleteCapacityProviderRequest
import aws.sdk.kotlin.services.bedrockagentcorecontrol.model.DeleteGatewayRateLimitRequest
import aws.sdk.kotlin.services.bedrockagentcorecontrol.model.DeleteGatewayRuleRequest
import aws.sdk.kotlin.services.bedrockagentcorecontrol.model.DeleteConsentPortalRequest
import aws.sdk.kotlin.services.bedrockagentcorecontrol.model.DeleteDatasetRequest
import aws.sdk.kotlin.services.bedrockagentcorecontrol.model.DeleteConfigurationBundleRequest
import aws.sdk.kotlin.services.bedrockagentcorecontrol.model.DeleteBrowserRequest
import aws.sdk.kotlin.services.bedrockagentcorecontrol.model.DeleteBrowserProfileRequest
import aws.sdk.kotlin.services.bedrockagentcorecontrol.model.DeleteCodeInterpreterRequest
import aws.sdk.kotlin.services.bedrockagentcorecontrol.model.DeletePaymentConnectorRequest
import aws.sdk.kotlin.services.bedrockagentcorecontrol.model.DeletePaymentManagerRequest
import com.TTT.AgentCore.AgentCoreClients
import com.TTT.AgentCore.AgentCoreConfig
import com.TTT.AgentCore.control.controlPlane
import com.TTT.AgentCore.evaluations.evaluationAdmin
import com.TTT.AgentCore.gateway.gatewayAdmin
import com.TTT.AgentCore.harness.harnessAdmin
import com.TTT.AgentCore.identity.identityAdmin
import com.TTT.AgentCore.payments.paymentAdmin
import com.TTT.AgentCore.policy.policyAdmin
import com.TTT.AgentCore.registry.AgentRegistryClients
import com.TTT.AgentCore.registry.agentCoreRegistryAdmin
import com.TTT.AgentCore.runtime.runtimeAdmin
import com.TTT.AgentCore.tools.browserAdmin
import com.TTT.AgentCore.tools.browserProfileAdmin
import com.TTT.AgentCore.tools.codeInterpreterAdmin

/** Result of an exact manifest cleanup attempt. */
data class SmokeCleanupResult(
    val status: SmokeStatus,
    val deleted: List<String> = emptyList(),
    val blocked: String? = null
)

/** Stable resource ordering used for dependency-aware exact teardown. */
object LiveSmokeCleanupOrdering
{
    private val dependencyRank = mapOf(
        "lambda-url" to 120,
        "ec2-instance" to 115,
        "gateway-target" to 110,
        "registry-record" to 110,
        "runtime-endpoint" to 105,
        "evaluator" to 100,
        "online-evaluation-config" to 100,
        "dataset-example" to 100,
        "dataset-version" to 100,
        "dataset" to 100,
        "configuration-bundle-version" to 100,
        "configuration-bundle" to 100,
        "oauth2-credential-provider" to 100,
        "api-key-credential-provider" to 100,
        "consent-portal" to 100,
        "secret" to 95,
        "lambda-function" to 95,
        "gateway" to 90,
        "runtime" to 90,
        "capacity-provider" to 90,
        "memory" to 90,
        "policy" to 90,
        "policy-engine" to 90,
        "workload-identity" to 90,
        "registry" to 90,
        "security-group" to 60,
        "subnet" to 55,
        "route-table" to 55,
        "internet-gateway" to 50,
        "instance-profile" to 45,
        "iam-role" to 40,
        "log-group" to 35,
        "vpc" to 10,
        "ecr-image" to 10,
        "ecr-repository" to 5,
        "cloudformation-stack" to 0
    )

    /** Sort exact manifest entries from dependents to dependencies. */
    fun reverseDependencyOrder(resources: List<OwnedResource>): List<OwnedResource> = resources
        .withIndex()
        .sortedWith(
            compareByDescending<IndexedValue<OwnedResource>> {
                dependencyRank[it.value.type] ?: 50
            }.thenByDescending { it.index }
        )
        .map { it.value }
}

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
            LiveSmokeCleanupOrdering.reverseDependencyOrder(manifest.resources()).forEach { resource ->
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
            "capacity-provider" -> clients.runtimeAdmin().deleteCapacityProvider(
                DeleteCapacityProviderRequest { capacityProviderId = requireId(resource) }
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
            "gateway-rate-limit" -> clients.gatewayAdmin().deleteRateLimit(
                DeleteGatewayRateLimitRequest {
                    gatewayIdentifier = requireParentId(resource)
                    rateLimitId = requireId(resource)
                }
            )
            "gateway-rule" -> clients.gatewayAdmin().deleteRule(
                DeleteGatewayRuleRequest {
                    gatewayIdentifier = requireParentId(resource)
                    ruleId = requireId(resource)
                }
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
            "dataset" -> clients.evaluationAdmin().deleteDataset(
                DeleteDatasetRequest { datasetId = requireId(resource) }
            )
            "configuration-bundle" -> clients.evaluationAdmin().deleteConfigurationBundle(
                DeleteConfigurationBundleRequest { bundleId = requireId(resource) }
            )
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
            "consent-portal" -> clients.identityAdmin().deleteConsentPortal(
                DeleteConsentPortalRequest { consentPortalIdentifier = requireId(resource) }
            )
            "browser-custom" -> clients.browserAdmin().delete(
                DeleteBrowserRequest { browserId = requireId(resource) }
            )
            "browser-profile" -> clients.browserProfileAdmin().delete(
                DeleteBrowserProfileRequest { profileId = requireId(resource) }
            )
            "code-interpreter-custom" -> clients.codeInterpreterAdmin().delete(
                DeleteCodeInterpreterRequest { codeInterpreterId = requireId(resource) }
            )
            "payment-connector" -> clients.paymentAdmin().deleteConnector(
                DeletePaymentConnectorRequest {
                    paymentManagerId = requireParentId(resource)
                    paymentConnectorId = requireId(resource)
                }
            )
            "payment-manager" -> clients.paymentAdmin().deleteManager(
                DeletePaymentManagerRequest { paymentManagerId = requireId(resource) }
            )
            "registry-record" -> AgentRegistryClients(AgentCoreConfig(resource.region)).use { registryClients ->
                registryClients.agentCoreRegistryAdmin().deleteRegistryRecord(
                    aws.sdk.kotlin.services.agentregistrycontrol.model.DeleteRegistryRecordRequest {
                        registryId = requireParentId(resource)
                        recordId = requireId(resource)
                    }
                )
            }
            "registry" -> AgentRegistryClients(AgentCoreConfig(resource.region)).use { registryClients ->
                registryClients.agentCoreRegistryAdmin().deleteRegistry(
                    aws.sdk.kotlin.services.agentregistrycontrol.model.DeleteRegistryRequest {
                        registryId = requireId(resource)
                    }
                )
            }
            "ecr-image", "ecr-repository", "iam-role", "log-group", "lambda-url",
            "lambda-function", "secret", "ec2-instance", "security-group", "subnet", "route-table",
            "internet-gateway", "vpc", "instance-profile", "dataset-example", "dataset-version",
            "configuration-bundle-version" -> deleteExternalResource(resource)
            else -> error("Unknown manifest resource type '${resource.type}'.")
        }
    }

    private fun requireId(resource: OwnedResource): String = resource.id
        ?: throw SmokeCleanupBlockedException("Manifest resource '${resource.name}' has no exact id.")

    private fun requireName(resource: OwnedResource): String = resource.name

    private fun requireParentId(resource: OwnedResource): String = resource.parentId
        ?: throw SmokeCleanupBlockedException("Manifest resource '${resource.name}' has no exact parent id.")
}
