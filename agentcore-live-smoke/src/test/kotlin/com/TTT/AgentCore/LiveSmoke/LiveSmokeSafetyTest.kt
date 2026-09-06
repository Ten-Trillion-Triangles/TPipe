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
