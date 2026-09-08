package com.TTT.AgentCore.LiveSmoke

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LiveSmokeSafetyTest
{
    @Test
    fun manifestRejectsUnscopedEntriesAndOnlyDeletesExactRecordedResources()
    {
        val runId = "tpipe_smoke_test01"
        val path = Files.createTempDirectory("agentcore-smoke-").resolve("manifest.json")
        val manifest = ResourceManifest(runId, path)
        val owned = OwnedResource(
            type = "runtime",
            name = "runtime-$runId-http",
            arn = "arn:aws:bedrock-agentcore:us-east-1:123456789012:runtime/runtime-$runId-http",
            region = "us-east-1"
        )
        val unrelated = owned.copy(name = "runtime-other-run", arn = "arn:other")

        assertFailsWith<IllegalArgumentException> { manifest.record(unrelated) }
        manifest.record(owned)
        assertTrue(manifest.owns(owned))
        assertTrue(Json.parseToJsonElement(Files.readString(path)).toString().contains(runId))

        var deleted = false
        runBlocking {
            manifest.deleteOwned(owned) { deleted = true }
        }
        assertTrue(deleted)

        deleted = false
        assertFailsWith<IllegalStateException> {
            runBlocking { manifest.deleteOwned(owned.copy(region = "us-west-2")) { deleted = true } }
        }
        assertFalse(deleted)
    }

    @Test
    fun reportEncodingIsMachineReadableAndRedactsCredentials()
    {
        val report = SmokeReport(
            runId = "tpipe_smoke_test01",
            region = "us-east-1",
            startedAt = "2026-09-06T00:00:00Z",
            finishedAt = "2026-09-06T00:00:01Z",
            cleanupStatus = SmokeStatus.CLEAN,
            cases = listOf(
                SmokeCaseResult(
                    id = "runtime.http",
                    status = SmokeStatus.FAIL,
                    startedAt = "2026-09-06T00:00:00Z",
                    finishedAt = "2026-09-06T00:00:01Z",
                    evidence = mapOf("auth" to "Authorization: Bearer secret-evidence"),
                    message = "Authorization: Bearer secret-token"
                )
            )
        )

        val encoded = SmokeJson.encodeReport(report)
        Json.parseToJsonElement(encoded)
        assertFalse(encoded.contains("secret-token"))
        assertFalse(encoded.contains("secret-evidence"))
        assertTrue(encoded.contains("[REDACTED]"))
        assertEquals("CLEAN", Json.parseToJsonElement(encoded).jsonObject["cleanupStatus"]?.toString()?.trim('"'))
    }

    @Test
    fun redactsCredentialFieldsEvenWhenTheyAreNotBearerHeaders()
    {
        val redacted = SmokeRedaction.text(
            "client_secret=oauth-secret api_key=api-secret access_token=oauth-token password=hunter2"
        )

        assertFalse(redacted.contains("oauth-secret"))
        assertFalse(redacted.contains("api-secret"))
        assertFalse(redacted.contains("oauth-token"))
        assertFalse(redacted.contains("hunter2"))
    }

    @Test
    fun clientTokensNormalizeRunUnderscoresToTheAwsAcceptedAlphabet()
    {
        val token = SmokeClientTokens.forRun("tpipe_smoke_test01", "registry-create")

        assertEquals("tpipe-smoke-test01-registry-create", token)
    }

    @Test
    fun unverifiedStandaloneCleanupIsNotReportedAsFailure()
    {
        val report = SmokeReport(
            runId = "tpipe_smoke_test01",
            region = "us-east-1",
            startedAt = "2026-09-06T00:00:00Z",
            finishedAt = "2026-09-06T00:00:01Z",
            cleanupStatus = SmokeStatus.SKIPPED,
            cases = emptyList()
        )

        assertFalse(report.hasFailure())
    }

    @Test
    fun supportedBlockedCasesFailTheRunButExplicitUnsupportedDoesNot()
    {
        val base = SmokeReport(
            runId = "tpipe_smoke_test01",
            region = "us-east-1",
            startedAt = "2026-09-06T00:00:00Z",
            finishedAt = "2026-09-06T00:00:01Z",
            cleanupStatus = SmokeStatus.CLEAN,
            cases = emptyList()
        )

        assertTrue(base.copy(cases = listOf(caseResult("gateway.forwarding", SmokeStatus.BLOCKED))).hasFailure())
        assertFalse(base.copy(cases = listOf(caseResult("capability.a2a", SmokeStatus.UNSUPPORTED))).hasFailure())
    }

    @Test
    fun oauthCallbackParserChecksStateAndDoesNotExposeCallbackValues()
    {
        val callback = LiveSmokeAssertions.parseOAuthCallback(
            "http://127.0.0.1:43127/callback?code=disposable-code&state=state-1",
            expectedState = "state-1"
        )

        assertEquals("disposable-code", callback.code)
        assertEquals("state-1", callback.state)
        val redacted = LiveSmokeAssertions.redactOAuthCallbackUrl(
            "http://127.0.0.1:43127/callback?code=disposable-code&state=state-1"
        )
        assertFalse(redacted.contains("disposable-code"))
        assertFalse(redacted.contains("state-1"))
        assertTrue(redacted.contains("[REDACTED]"))
    }

    @Test
    fun capacityDeletionTreatsDeletingAndDeletedAsSafeTerminalEvidence()
    {
        assertTrue(LiveSmokeAssertions.isCapacitySessionDeletionTerminal(
            aws.sdk.kotlin.services.bedrockagentcore.model.CapacityProviderSessionStatus.Deleting
        ))
        assertTrue(LiveSmokeAssertions.isCapacitySessionDeletionTerminal(
            aws.sdk.kotlin.services.bedrockagentcore.model.CapacityProviderSessionStatus.Deleted
        ))
        assertFalse(LiveSmokeAssertions.isCapacitySessionDeletionTerminal(
            aws.sdk.kotlin.services.bedrockagentcore.model.CapacityProviderSessionStatus.Active
        ))
    }

    @Test
    fun registryUpdateMustBeReadyBeforeDependentCrud()
    {
        assertTrue(LiveSmokeAssertions.isRegistryReady("READY"))
        assertTrue(LiveSmokeAssertions.isRegistryReady("Ready"))
        assertFalse(LiveSmokeAssertions.isRegistryReady("UPDATING"))
        assertFalse(LiveSmokeAssertions.isRegistryReady(null))
    }

    @Test
    fun gatewaySynchronizationMustBeReadyBeforeToolDiscovery()
    {
        assertTrue(LiveSmokeAssertions.isGatewayTargetReady("READY"))
        assertTrue(LiveSmokeAssertions.isGatewayTargetReady("Ready"))
        assertFalse(LiveSmokeAssertions.isGatewayTargetReady("SYNCHRONIZING"))
        assertFalse(LiveSmokeAssertions.isGatewayTargetReady("SYNCHRONIZE_UNSUCCESSFUL"))
    }

    @Test
    fun gatewayPcpNameUsesTheDiscoveredToolName()
    {
        assertEquals(
            "gateway__target_smoke_echo",
            LiveSmokeAssertions.namespacedPcpFunctionName("gateway__", "target_smoke_echo")
        )
    }

    @Test
    fun registryRecordMustReturnToDraftBeforeApprovalMutation()
    {
        assertTrue(LiveSmokeAssertions.isRegistryRecordDraft("DRAFT"))
        assertTrue(LiveSmokeAssertions.isRegistryRecordDraft("Draft"))
        assertFalse(LiveSmokeAssertions.isRegistryRecordDraft("UPDATING"))
        assertFalse(LiveSmokeAssertions.isRegistryRecordDraft("APPROVED"))
    }

    @Test
    fun temporalPolicySessionHeaderIsTheAgentCoreHeader()
    {
        assertEquals(
            "x-amzn-bedrock-agentcore-policy-session-id",
            com.TTT.AgentCore.policy.AgentCoreTemporalPolicySession.HEADER_NAME
        )
    }

    @Test
    fun instancesInvocationUsesRuntimeArnAndEndpointQualifierSeparately()
    {
        val endpoint = LiveSmokeAssertions.runtimeInvocationEndpoint(
            "arn:aws:bedrock-agentcore:us-east-1:123456789012:runtime/example/runtime-endpoint/smoke"
        )
        assertEquals("arn:aws:bedrock-agentcore:us-east-1:123456789012:runtime/example", endpoint.runtimeArn)
        assertEquals("smoke", endpoint.qualifier)
    }

    private fun caseResult(id: String, status: SmokeStatus): SmokeCaseResult = SmokeCaseResult(
        id = id,
        status = status,
        startedAt = "2026-09-06T00:00:00Z",
        finishedAt = "2026-09-06T00:00:01Z"
    )

    @Test
    fun manifestAcceptsExplicitRunTagWhenGeneratedNamesCannotContainFullRunId()
    {
        val runId = "tpipe_smoke_test01"
        val path = Files.createTempDirectory("agentcore-smoke-").resolve("manifest.json")
        val manifest = ResourceManifest(runId, path)
        val resource = OwnedResource(
            type = "runtime",
            name = "generated-runtime-id",
            id = "generated-runtime-id-123",
            region = "us-east-1",
            runTag = runId
        )

        manifest.record(resource)
        assertTrue(manifest.owns(resource))
        assertTrue(Files.readString(path).contains("\"runTag\":\"$runId\""))
    }
}
