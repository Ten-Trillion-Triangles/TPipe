package com.TTT.AgentCore.LiveSmoke

import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LiveSmokeFixtureTemplateTest
{
    @Test
    fun fixtureTemplateUsesGeneratedSecretsAndOwnedEndpointsWithoutLiteralCredentials()
    {
        val template = checkNotNull(
            javaClass.getResourceAsStream(
                "/cloudformation/tpipe-agentcore-live-fixtures.yaml"
            )
        ).bufferedReader().use { it.readText() }

        assertTrue(template.contains("AWS::SecretsManager::Secret"))
        assertTrue(template.contains("GenerateSecretString"))
        assertTrue(template.contains("AWS::Lambda::Url"))
        assertTrue(template.contains("/.well-known/openid-configuration"))
        assertTrue(template.contains("AWS::EC2::VPC"))
        assertTrue(template.contains("AWS::IAM::InstanceProfile"))
        assertTrue(template.contains("bedrock-agentcore:GetGateway"))
        assertTrue(template.contains("bedrock-agentcore:GetGatewayTarget"))
        assertTrue(template.contains("bedrock-agentcore:ListGatewayTargets"))
        assertFalse(template.contains("fixture-access-token"))
        assertFalse(template.contains("fixture-client-secret"))
        assertFalse(template.contains("fixture-api-key"))
    }
}
