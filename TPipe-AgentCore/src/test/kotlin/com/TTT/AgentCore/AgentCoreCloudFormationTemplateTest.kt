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
        assertTrue(!template.contains("CapacityProviderConfiguration:"))
        assertTrue(template.contains("EnableRuntimeLifecycle:"))
    }

    @Test
    fun shipsCurrentCapabilityCompanionTemplatesWithoutSecretOutputs()
    {
        val templates = listOf(
            "tpipe-agentcore-runtime-instances.yaml" to listOf("AWS::BedrockAgentCore::CapacityProvider"),
            "tpipe-agentcore-identity.yaml" to listOf(
                "AWS::BedrockAgentCore::WorkloadIdentity",
                "AWS::BedrockAgentCore::OAuth2CredentialProvider",
                "AWS::BedrockAgentCore::ApiKeyCredentialProvider"
            ),
            "tpipe-agentcore-evaluations.yaml" to listOf(
                "AWS::BedrockAgentCore::Dataset",
                "AWS::BedrockAgentCore::Evaluator",
                "AWS::BedrockAgentCore::OnlineEvaluationConfig",
                "AWS::BedrockAgentCore::ConfigurationBundle"
            ),
            "tpipe-agentcore-tools.yaml" to listOf(
                "AWS::BedrockAgentCore::BrowserCustom",
                "AWS::BedrockAgentCore::BrowserProfile",
                "AWS::BedrockAgentCore::CodeInterpreterCustom"
            ),
            "tpipe-agentcore-payments.yaml" to listOf(
                "AWS::BedrockAgentCore::PaymentManager",
                "AWS::BedrockAgentCore::PaymentConnector"
            ),
            "tpipe-agent-registry.yaml" to listOf(
                "AWS::AgentRegistry::Registry",
                "AWS::AgentRegistry::RegistryRecord"
            )
        )

        templates.forEach { (name, resourceTypes) ->
            val template = requireNotNull(javaClass.getResource("/cloudformation/$name")).readText()
            resourceTypes.forEach { resourceType -> assertTrue(template.contains("Type: $resourceType")) }
            assertTrue(!template.contains("      - A2A"), name)
            val outputs = template.substringAfter("Outputs:", missingDelimiterValue = "")
            assertTrue(!outputs.contains("Secret", ignoreCase = true), name)
            assertTrue(
                !Regex("^\\s+(Password|ClientSecret|ApiKey):", RegexOption.MULTILINE).containsMatchIn(template),
                name
            )

            if (name == "tpipe-agentcore-tools.yaml")
            {
                assertTrue(template.contains("VpcConfig: !If"), name)
                assertTrue(
                    !Regex("^        (SecurityGroups|Subnets):", RegexOption.MULTILINE).containsMatchIn(template),
                    name
                )
            }
        }
    }
}
