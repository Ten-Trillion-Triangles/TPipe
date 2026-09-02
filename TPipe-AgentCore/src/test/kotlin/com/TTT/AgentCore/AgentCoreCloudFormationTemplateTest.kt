package com.TTT.AgentCore

import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

class AgentCoreCloudFormationTemplateTest
{
    @Test
    fun wiresRuntimeProtocolToAgentCoreRuntime()
    {
        val template = requireNotNull(
            javaClass.getResource("/cloudformation/tpipe-agentcore.yaml")
        ).readText()

        assertTrue(template.contains("ProtocolConfiguration: !Sub '\${RuntimeProtocol}'"))
        assertTrue(template.contains("Type: AWS::BedrockAgentCore::RuntimeEndpoint"))
        assertTrue(template.contains("Type: AWS::BedrockAgentCore::GatewayTarget"))
        assertTrue(template.contains("Type: AWS::BedrockAgentCore::Policy"))
        assertTrue(template.contains("PolicyEngineConfiguration: !If"))
        assertTrue(template.contains("Mode: LOG_ONLY"))
        assertTrue(template.contains("Type: AWS::IAM::Role"))
        assertTrue(template.contains("- AGUI"))
        assertTrue(!template.contains("      - A2A"))
    }
}
