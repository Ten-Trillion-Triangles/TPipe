package com.TTT.AgentCore.identity

import aws.sdk.kotlin.services.bedrockagentcorecontrol.BedrockAgentCoreControlClient
import aws.sdk.kotlin.services.bedrockagentcorecontrol.model.*
import aws.sdk.kotlin.services.bedrockagentcorecontrol.model.PrivateKeySource.KmsKeySource
import aws.sdk.kotlin.services.bedrockagentcorecontrol.model.Oauth2ProviderConfigInput.CustomOauth2ProviderConfig
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import aws.sdk.kotlin.services.bedrockagentcorecontrol.model.ConsentPortalStatus
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AgentCoreIdentityTest
{
    @Test
    fun typedTokenScopesDoNotCollideWhenDimensionsContainDelimiters() = runBlocking {
        val loadedScopeKeys = mutableListOf<String>()
        val provider = AgentCoreTokenProvider(
            loader = { scopeKey ->
                loadedScopeKeys += scopeKey
                "token-${loadedScopeKeys.size}"
            },
            config = AgentCoreIdentityConfig(expirySkewMillis = 0L),
            now = { 100L },
            tokenLifetimeMillis = 1_000L
        )
        val first = AgentCoreTokenScope(
            principalKind = PrincipalKind.WORKLOAD,
            principalId = "principal:one",
            resourceId = "resource",
            scopes = setOf("read")
        )
        val second = AgentCoreTokenScope(
            principalKind = PrincipalKind.WORKLOAD,
            principalId = "principal",
            resourceId = "one:resource",
            scopes = setOf("read")
        )

        val firstHeader = provider.headers(first)["Authorization"]
        val secondHeader = provider.headers(second)["Authorization"]

        assertNotEquals(firstHeader, secondHeader)
        assertEquals(2, loadedScopeKeys.size)
    }

    @Test
    fun invalidTokenScopeConfigurationIsRejectedBeforeLoading() = runBlocking {
        assertFailsWith<IllegalArgumentException> {
            AgentCoreIdentityConfig(expirySkewMillis = -1L)
        }
        assertFailsWith<IllegalArgumentException> {
            AgentCoreTokenScope(
                principalKind = PrincipalKind.USER,
                principalId = "user",
                resourceId = "resource",
                scopes = setOf("")
            )
        }

        val provider = AgentCoreTokenProvider(loader = { "token" })
        assertFailsWith<IllegalArgumentException> {
            provider.headers("")
        }
    }

    @Test
    fun secretTokenRedactsItsDefaultRepresentationButBuildsExplicitHeaders()
    {
        val token = AgentCoreSecretToken.of("do-not-log-this")

        assertEquals("[REDACTED]", token.toString())
        assertEquals("[REDACTED]", token.redacted())
        assertFalse(token.toString().contains("do-not-log-this"))
        assertEquals("Bearer do-not-log-this", token.authorizationHeader())
        assertEquals("do-not-log-this", token.value())
    }

    @Test
    fun consentPortalPollerStopsAtActiveAndPropagatesCancellation() = runBlocking {
        val statuses = ArrayDeque<ConsentPortalStatus>(
            listOf(ConsentPortalStatus.Creating, ConsentPortalStatus.Active)
        )
        val active = AgentCoreIdentityPoller.awaitConsentPortalActive(
            read = { statuses.removeFirst() },
            status = { value: ConsentPortalStatus -> value },
            timeoutMillis = 1_000L,
            initialDelayMillis = 1L,
            maxDelayMillis = 2L
        )
        assertEquals(ConsentPortalStatus.Active, active)

        val job = launch {
            AgentCoreConsentPortalPoller.awaitActive(
                read = { ConsentPortalStatus.Creating },
                status = { value: ConsentPortalStatus -> value },
                timeoutMillis = 10_000L,
                initialDelayMillis = 1L,
                maxDelayMillis = 2L
            )
        }
        delay(10L)
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
    }

    @Test
    fun consentPortalPollerRejectsInvalidBounds()
    {
        assertFailsWith<IllegalArgumentException> {
            runBlocking {
                AgentCoreIdentityPoller.awaitConsentPortalActive(
                    read = { ConsentPortalStatus.Creating },
                    status = { it },
                    timeoutMillis = 0L
                )
            }
        }
        assertFailsWith<IllegalArgumentException> {
            runBlocking {
                AgentCoreIdentityPoller.awaitConsentPortalActive(
                    read = { ConsentPortalStatus.Creating },
                    status = { it },
                    initialDelayMillis = 5L,
                    maxDelayMillis = 1L
                )
            }
        }
    }

    @Test
    fun adminDelegatesIdentityLifecycleVaultPolicyAndConsentPortalOperations()
    {
        val operations = mutableListOf<String>()
        val admin = AgentCoreIdentityAdmin(controlClient { operation, _ ->
            operations += operation
            controlResponse(operation)
        })

        runBlocking {
            admin.createWorkloadIdentity(CreateWorkloadIdentityRequest { name = "workload" })
            admin.getWorkloadIdentity(GetWorkloadIdentityRequest { name = "workload" })
            admin.listWorkloadIdentities(ListWorkloadIdentitiesRequest {})
            admin.updateWorkloadIdentity(UpdateWorkloadIdentityRequest { name = "workload" })
            admin.deleteWorkloadIdentity(DeleteWorkloadIdentityRequest { name = "workload" })

            admin.createOauth2CredentialProvider(
                CreateOauth2CredentialProviderRequest {
                    name = "oauth"
                    credentialProviderVendor = CredentialProviderVendorType.CustomOauth2
                }
            )
            admin.getOauth2CredentialProvider(GetOauth2CredentialProviderRequest { name = "oauth" })
            admin.listOauth2CredentialProviders(ListOauth2CredentialProvidersRequest {})
            admin.updateOauth2CredentialProvider(UpdateOauth2CredentialProviderRequest { name = "oauth" })
            admin.deleteOauth2CredentialProvider(DeleteOauth2CredentialProviderRequest { name = "oauth" })

            admin.createApiKeyCredentialProvider(CreateApiKeyCredentialProviderRequest {
                name = "api-key"
                apiKey = "secret-api-key"
            })
            admin.getApiKeyCredentialProvider(GetApiKeyCredentialProviderRequest { name = "api-key" })
            admin.listApiKeyCredentialProviders(ListApiKeyCredentialProvidersRequest {})
            admin.updateApiKeyCredentialProvider(UpdateApiKeyCredentialProviderRequest { name = "api-key" })
            admin.deleteApiKeyCredentialProvider(DeleteApiKeyCredentialProviderRequest { name = "api-key" })

            admin.getTokenVault(GetTokenVaultRequest { tokenVaultId = "vault" })
            admin.setTokenVaultCmk(SetTokenVaultCmkRequest { tokenVaultId = "vault" })
            admin.getResourcePolicy(GetResourcePolicyRequest { resourceArn = "arn:resource" })
            admin.putResourcePolicy(PutResourcePolicyRequest {
                resourceArn = "arn:resource"
                policy = "{}"
            })
            admin.deleteResourcePolicy(DeleteResourcePolicyRequest { resourceArn = "arn:resource" })

            admin.createConsentPortal(CreateConsentPortalRequest { name = "portal" })
            admin.getConsentPortal(GetConsentPortalRequest { consentPortalIdentifier = "portal" })
            admin.listConsentPortals(ListConsentPortalsRequest {})
            admin.updateConsentPortal(UpdateConsentPortalRequest { consentPortalIdentifier = "portal" })
            admin.deleteConsentPortal(DeleteConsentPortalRequest { consentPortalIdentifier = "portal" })
        }

        assertEquals(
            listOf(
                "createWorkloadIdentity", "getWorkloadIdentity", "listWorkloadIdentities",
                "updateWorkloadIdentity", "deleteWorkloadIdentity",
                "createOauth2CredentialProvider", "getOauth2CredentialProvider",
                "listOauth2CredentialProviders", "updateOauth2CredentialProvider",
                "deleteOauth2CredentialProvider", "createApiKeyCredentialProvider",
                "getApiKeyCredentialProvider", "listApiKeyCredentialProviders",
                "updateApiKeyCredentialProvider", "deleteApiKeyCredentialProvider",
                "getTokenVault", "setTokenVaultCmk", "getResourcePolicy", "putResourcePolicy",
                "deleteResourcePolicy", "createConsentPortal", "getConsentPortal",
                "listConsentPortals", "updateConsentPortal", "deleteConsentPortal"
            ),
            operations
        )
    }

    @Test
    fun privateKeyJwtConfigurationIsPassedThroughWithoutPrivateKeyMaterial()
    {
        var captured: CreateOauth2CredentialProviderRequest? = null
        val admin = AgentCoreIdentityAdmin(controlClient { operation, args ->
            if(operation == "createOauth2CredentialProvider")
            {
                captured = args.first() as CreateOauth2CredentialProviderRequest
            }
            controlResponse(operation)
        })
        val privateKeyJwt = PrivateKeyJwtConfig {
            signingAlgorithm = SigningAlgorithm.Rs256
            privateKeySource = KmsKeySource(KmsKeySourceType { kmsKeyArn = "arn:kms:key" })
        }
        val oauthConfig = CustomOauth2ProviderConfigInput {
            clientAuthenticationMethod = ClientAuthenticationMethodType.PrivateKeyJwt
            clientId = "client-id"
            privateKeyJwtConfig = privateKeyJwt
        }
        val request = CreateOauth2CredentialProviderRequest {
            name = "private-key-jwt"
            credentialProviderVendor = CredentialProviderVendorType.CustomOauth2
            oauth2ProviderConfigInput = CustomOauth2ProviderConfig(oauthConfig)
        }

        runBlocking { admin.createOauth2CredentialProvider(request) }

        assertSame(request, captured)
        assertEquals(privateKeyJwt, captured!!.oauth2ProviderConfigInput!!.asCustomOauth2ProviderConfig().privateKeyJwtConfig)
        assertFalse(captured.toString().contains("private-key-bytes"))
    }

    @Test
    fun adminPreservesTypedAwsErrors()
    {
        val failure = ResourceNotFoundException { message = "missing" }
        val admin = AgentCoreIdentityAdmin(controlClient { _, _ -> throw failure })

        val actual = assertFailsWith<ResourceNotFoundException> {
            runBlocking {
                admin.getWorkloadIdentity(GetWorkloadIdentityRequest { name = "missing" })
            }
        }

        assertSame(failure, actual)
    }

    private fun controlClient(handler: (String, Array<Any?>) -> Any?): BedrockAgentCoreControlClient =
        Proxy.newProxyInstance(
            BedrockAgentCoreControlClient::class.java.classLoader,
            arrayOf(BedrockAgentCoreControlClient::class.java)
        ) { _, method, args ->
            when(method.name)
            {
                "toString" -> "identity-control-client"
                "hashCode" -> 0
                "equals" -> false
                "close" -> Unit
                else -> handler(method.name, args ?: emptyArray())
            }
        } as BedrockAgentCoreControlClient

    private fun controlResponse(operation: String): Any = when(operation)
    {
        "createWorkloadIdentity" -> CreateWorkloadIdentityResponse {
            name = "workload"
            workloadIdentityArn = "arn:workload"
        }
        "getWorkloadIdentity" -> GetWorkloadIdentityResponse {
            name = "workload"
            workloadIdentityArn = "arn:workload"
            createdTime = aws.smithy.kotlin.runtime.time.Instant(java.time.Instant.EPOCH)
            lastUpdatedTime = aws.smithy.kotlin.runtime.time.Instant(java.time.Instant.EPOCH)
        }
        "listWorkloadIdentities" -> ListWorkloadIdentitiesResponse { workloadIdentities = emptyList() }
        "updateWorkloadIdentity" -> UpdateWorkloadIdentityResponse {
            name = "workload"
            workloadIdentityArn = "arn:workload"
            createdTime = aws.smithy.kotlin.runtime.time.Instant(java.time.Instant.EPOCH)
            lastUpdatedTime = aws.smithy.kotlin.runtime.time.Instant(java.time.Instant.EPOCH)
        }
        "deleteWorkloadIdentity" -> DeleteWorkloadIdentityResponse {}
        "createOauth2CredentialProvider" -> CreateOauth2CredentialProviderResponse {
            name = "oauth"
            credentialProviderArn = "arn:oauth"
        }
        "getOauth2CredentialProvider" -> GetOauth2CredentialProviderResponse {
            name = "oauth"
            credentialProviderArn = "arn:oauth"
            credentialProviderVendor = CredentialProviderVendorType.CustomOauth2
            createdTime = aws.smithy.kotlin.runtime.time.Instant(java.time.Instant.EPOCH)
            lastUpdatedTime = aws.smithy.kotlin.runtime.time.Instant(java.time.Instant.EPOCH)
            status = Status.Ready
        }
        "listOauth2CredentialProviders" -> ListOauth2CredentialProvidersResponse { credentialProviders = emptyList() }
        "updateOauth2CredentialProvider" -> UpdateOauth2CredentialProviderResponse {
            name = "oauth"
            credentialProviderArn = "arn:oauth"
            credentialProviderVendor = CredentialProviderVendorType.CustomOauth2
            createdTime = aws.smithy.kotlin.runtime.time.Instant(java.time.Instant.EPOCH)
            lastUpdatedTime = aws.smithy.kotlin.runtime.time.Instant(java.time.Instant.EPOCH)
            status = Status.Ready
        }
        "deleteOauth2CredentialProvider" -> DeleteOauth2CredentialProviderResponse {}
        "createApiKeyCredentialProvider" -> CreateApiKeyCredentialProviderResponse {
            name = "api-key"
            credentialProviderArn = "arn:api-key"
        }
        "getApiKeyCredentialProvider" -> GetApiKeyCredentialProviderResponse {
            name = "api-key"
            credentialProviderArn = "arn:api-key"
            createdTime = aws.smithy.kotlin.runtime.time.Instant(java.time.Instant.EPOCH)
            lastUpdatedTime = aws.smithy.kotlin.runtime.time.Instant(java.time.Instant.EPOCH)
        }
        "listApiKeyCredentialProviders" -> ListApiKeyCredentialProvidersResponse { credentialProviders = emptyList() }
        "updateApiKeyCredentialProvider" -> UpdateApiKeyCredentialProviderResponse {
            name = "api-key"
            credentialProviderArn = "arn:api-key"
            createdTime = aws.smithy.kotlin.runtime.time.Instant(java.time.Instant.EPOCH)
            lastUpdatedTime = aws.smithy.kotlin.runtime.time.Instant(java.time.Instant.EPOCH)
        }
        "deleteApiKeyCredentialProvider" -> DeleteApiKeyCredentialProviderResponse {}
        "getTokenVault" -> GetTokenVaultResponse {
            tokenVaultId = "vault"
            lastModifiedDate = aws.smithy.kotlin.runtime.time.Instant(java.time.Instant.EPOCH)
        }
        "setTokenVaultCmk" -> SetTokenVaultCmkResponse {
            tokenVaultId = "vault"
            lastModifiedDate = aws.smithy.kotlin.runtime.time.Instant(java.time.Instant.EPOCH)
        }
        "getResourcePolicy" -> GetResourcePolicyResponse {}
        "putResourcePolicy" -> PutResourcePolicyResponse { policy = "{}" }
        "deleteResourcePolicy" -> DeleteResourcePolicyResponse {}
        "createConsentPortal" -> CreateConsentPortalResponse {
            consentPortalArn = "arn:portal"
            consentPortalId = "portal"
            createdAt = aws.smithy.kotlin.runtime.time.Instant(java.time.Instant.EPOCH)
            executionRoleArn = "arn:role"
            name = "portal"
            sources = emptyList()
            status = ConsentPortalStatus.Active
            updatedAt = aws.smithy.kotlin.runtime.time.Instant(java.time.Instant.EPOCH)
        }
        "getConsentPortal" -> GetConsentPortalResponse {
            consentPortalArn = "arn:portal"
            consentPortalId = "portal"
            createdAt = aws.smithy.kotlin.runtime.time.Instant(java.time.Instant.EPOCH)
            executionRoleArn = "arn:role"
            name = "portal"
            sources = emptyList()
            status = ConsentPortalStatus.Active
            updatedAt = aws.smithy.kotlin.runtime.time.Instant(java.time.Instant.EPOCH)
        }
        "listConsentPortals" -> ListConsentPortalsResponse { consentPortals = emptyList() }
        "updateConsentPortal" -> UpdateConsentPortalResponse {
            consentPortalArn = "arn:portal"
            consentPortalId = "portal"
            createdAt = aws.smithy.kotlin.runtime.time.Instant(java.time.Instant.EPOCH)
            executionRoleArn = "arn:role"
            name = "portal"
            sources = emptyList()
            status = ConsentPortalStatus.Active
            updatedAt = aws.smithy.kotlin.runtime.time.Instant(java.time.Instant.EPOCH)
        }
        "deleteConsentPortal" -> DeleteConsentPortalResponse {}
        else -> error("Unexpected control operation: $operation")
    }
}
