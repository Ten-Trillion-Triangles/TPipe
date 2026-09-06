package com.TTT.AgentCore.LiveSmoke

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LiveSmokeDeploymentTest
{
    @Test
    fun preflightStopsOnAccessDeniedOrCollisionBeforeCreate()
    {
        val config = LiveSmokeConfig(
            region = "us-east-1",
            runId = "tpipe_smoke_test01",
            outputPath = Files.createTempDirectory("agentcore-smoke-").resolve("report.json")
        )
        val aws = FakeAws(
            preflight = LiveSmokePreflight(
                accountId = "123456789012",
                region = config.region,
                existingNames = setOf("tpipe-smoke-http-${config.runId.replace('_', '-')}")
            )
        )
        val controller = controller(config, aws)
        assertFailsWith<IllegalStateException> {
            runBlocking { controller.preflight(images()) }
        }
        assertEquals(0, aws.created)
    }

    @Test
    fun deploymentRecordsEachStackBeforeReturningAndCleanupNeedsCleanScan()
    {
        val config = LiveSmokeConfig(
            region = "us-east-1",
            runId = "tpipe_smoke_test01",
            outputPath = Files.createTempDirectory("agentcore-smoke-").resolve("report.json")
        )
        val aws = FakeAws(
            preflight = LiveSmokePreflight("123456789012", config.region)
        )
        val manifest = ResourceManifest(config.runId, config.outputPath.resolveSibling("manifest.json"))
        val controller = LiveSmokeDeploymentController(
            config = config,
            manifest = manifest,
            aws = aws,
            templateBody = "template",
            cleanupAction = { SmokeCleanupResult(SmokeStatus.CLEAN) }
        )
        val deployment = runBlocking { controller.deploy(images()) }
        assertEquals(3, deployment.stacks.size)
        assertEquals(3, manifest.resources().size)
        assertTrue(aws.created == 3)
    }

    @Test
    fun executeAttemptsCleanupAfterSmokeFailure()
    {
        val config = LiveSmokeConfig(
            region = "us-east-1",
            runId = "tpipe_smoke_test01",
            outputPath = Files.createTempDirectory("agentcore-smoke-").resolve("report.json")
        )
        val aws = FakeAws(LiveSmokePreflight("123456789012", config.region))
        var cleanupCalls = 0
        val controller = LiveSmokeDeploymentController(
            config = config,
            manifest = ResourceManifest(config.runId, config.outputPath.resolveSibling("manifest.json")),
            aws = aws,
            templateBody = "template",
            cleanupAction = {
                cleanupCalls++
                SmokeCleanupResult(SmokeStatus.CLEAN)
            }
        )
        val report = runBlocking {
            controller.execute(images()) {
                error("smoke assertion failed")
            }
        }
        assertEquals(1, cleanupCalls)
        assertEquals(SmokeStatus.CLEAN, report.cleanupStatus)
        assertEquals(SmokeStatus.FAIL, report.cases.single().status)
    }

    private fun controller(config: LiveSmokeConfig, aws: FakeAws): LiveSmokeDeploymentController =
        LiveSmokeDeploymentController(
            config = config,
            manifest = ResourceManifest(config.runId, config.outputPath.resolveSibling("manifest.json")),
            aws = aws,
            templateBody = "template",
            cleanupAction = { SmokeCleanupResult(SmokeStatus.CLEAN) }
        )

    private fun images() = listOf(
        LiveSmokeImage("HTTP", "123456789012.dkr.ecr.us-east-1.amazonaws.com/tpipe/http:smoke"),
        LiveSmokeImage("MCP", "123456789012.dkr.ecr.us-east-1.amazonaws.com/tpipe/mcp:smoke"),
        LiveSmokeImage("AGUI", "123456789012.dkr.ecr.us-east-1.amazonaws.com/tpipe/agui:smoke")
    )

    private class FakeAws(
        private val preflight: LiveSmokePreflight,
        var created: Int = 0,
        val deleted: MutableList<String> = mutableListOf()
    ) : LiveSmokeAwsMcpControlPlane
    {
        override suspend fun preflight(config: LiveSmokeConfig, desiredNames: Set<String>) = preflight

        override suspend fun createStack(request: LiveSmokeStackRequest): LiveSmokeStackResult
        {
            created++
            return LiveSmokeStackResult(
                stackId = "arn:aws:cloudformation:us-east-1:123456789012:stack/${request.stackName}/id",
                requestId = "request-$created",
                outputs = mapOf("RuntimeEndpointArn" to "arn:${request.protocol}")
            )
        }

        override suspend fun deleteStack(stack: OwnedResource)
        {
            deleted += requireNotNull(stack.stackId)
        }

        override suspend fun postCleanupScan(config: LiveSmokeConfig) = emptySet<String>()
    }
}
