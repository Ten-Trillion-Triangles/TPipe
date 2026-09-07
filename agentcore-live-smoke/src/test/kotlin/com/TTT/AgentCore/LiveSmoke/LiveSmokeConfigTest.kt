package com.TTT.AgentCore.LiveSmoke

import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LiveSmokeConfigTest
{
    @Test
    fun parsesExtendedCapabilityIdentifiersAndCaseFilter()
    {
        val config = LiveSmokeConfig.fromEnvironment(
            mapOf(
                "TPIPE_AGENTCORE_REGION" to "us-east-1",
                "TPIPE_AGENTCORE_RUN_ID" to "tpipe_smoke_config01",
                "TPIPE_AGENTCORE_INSTANCES_RUNTIME_ARN" to "arn:runtime:instances",
                "TPIPE_AGENTCORE_INSTANCES_SESSION_ID" to "instances-session",
                "TPIPE_AGENTCORE_SHELL_RUNTIME_SESSION_ID" to "shell-session",
                "TPIPE_AGENTCORE_SHELL_ID" to "shell-1",
                "TPIPE_AGENTCORE_GATEWAY_IDENTIFIER" to "gateway-1",
                "TPIPE_AGENTCORE_GATEWAY_RULE_ID" to "rule-1",
                "TPIPE_AGENTCORE_DATASET_ID" to "dataset-1",
                "TPIPE_AGENTCORE_REGISTRY_ID" to "registry-1",
                "TPIPE_AGENTCORE_REGISTRY_RECORD_ID" to "record-1",
                "TPIPE_AGENTCORE_PAYMENT_MANAGER_ID" to "manager-1",
                "TPIPE_AGENTCORE_PAYMENT_CONNECTOR_ID" to "connector-1",
                "TPIPE_AGENTCORE_CASES" to "runtime.command, registry.lifecycle, payments.lifecycle"
            )
        )

        assertEquals("arn:runtime:instances", config.instancesRuntimeArn)
        assertEquals("instances-session", config.instancesSessionId)
        assertEquals("shell-session", config.shellRuntimeSessionId)
        assertEquals("shell-1", config.shellId)
        assertEquals("gateway-1", config.gatewayIdentifier)
        assertEquals("rule-1", config.gatewayRuleId)
        assertEquals("dataset-1", config.evaluationDatasetId)
        assertEquals("registry-1", config.registryId)
        assertEquals("record-1", config.registryRecordId)
        assertEquals("manager-1", config.paymentManagerId)
        assertEquals("connector-1", config.paymentConnectorId)
        assertEquals(
            setOf("runtime.command", "registry.lifecycle", "payments.lifecycle"),
            config.caseFilter
        )
        assertTrue(config.outputPath is Path)
    }
}
