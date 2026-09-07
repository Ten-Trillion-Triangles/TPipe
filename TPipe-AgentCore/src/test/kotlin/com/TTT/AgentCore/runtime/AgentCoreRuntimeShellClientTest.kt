package com.TTT.AgentCore.runtime

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AgentCoreRuntimeShellClientTest
{
    @Test
    fun buildsEncodedShellHandshakeUrlWithRuntimeSessionAndQualifier()
    {
        val client = AgentCoreRuntimeShellClient(
            AgentCoreRuntimeClientConfig(
                endpoint = "https://runtime.example/",
                qualifier = "DEFAULT"
            )
        )
        try
        {
            assertEquals(
                "https://runtime.example/runtimes/arn%3Aaws%3Abedrock-agentcore%3Aus-east-1%3A123%3Aruntime%2Fdemo/ws/shells" +
                    "?runtimeSessionId=session-1&qualifier=DEFAULT&shellId=shell-1",
                client.buildShellUrl(
                    runtimeArn = "arn:aws:bedrock-agentcore:us-east-1:123:runtime/demo",
                    runtimeSessionId = "session-1",
                    shellId = "shell-1"
                )
            )
        }
        finally
        {
            client.close()
        }
    }

    @Test
    fun rejectsInvalidIdentifiersBeforeOpeningTheSocket()
    {
        val client = AgentCoreRuntimeShellClient(AgentCoreRuntimeClientConfig("http://runtime"))
        try
        {
            assertFailsWith<IllegalArgumentException> {
                client.buildShellUrl("arn:runtime", "session with spaces")
            }
            assertFailsWith<IllegalArgumentException> {
                client.buildShellUrl("arn:runtime", "session", "shell/with/slash")
            }
        }
        finally
        {
            client.close()
        }
    }

    @Test
    fun convertsOnlySecureHandshakeUrlsToWss()
    {
        val client = AgentCoreRuntimeShellClient(AgentCoreRuntimeClientConfig("https://runtime.example"))
        try
        {
            assertEquals(
                "wss://runtime.example/runtimes/runtime/ws/shells?runtimeSessionId=session-1",
                client.toWebSocketUrl("https://runtime.example/runtimes/runtime/ws/shells?runtimeSessionId=session-1")
            )
            assertFailsWith<IllegalArgumentException> {
                client.toWebSocketUrl("http://runtime.example/runtimes/runtime/ws/shells")
            }
        }
        finally
        {
            client.close()
        }
    }
}
