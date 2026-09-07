package com.TTT.AgentCore.runtime

import aws.sdk.kotlin.services.bedrockagentcore.model.CommandExecutionStatus
import aws.sdk.kotlin.services.bedrockagentcore.model.InvokeAgentRuntimeCommandRequest
import aws.sdk.kotlin.services.bedrockagentcore.model.InvokeAgentRuntimeResponse
import aws.sdk.kotlin.services.bedrockagentcore.model.InvokeAgentRuntimeRequest
import aws.sdk.kotlin.services.bedrockagentcore.model.StopRuntimeSessionRequest
import aws.sdk.kotlin.services.bedrockagentcore.model.StopRuntimeSessionResponse
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.TimeoutCancellationException
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AgentCoreRuntimeCommandTest
{
    @Test
    fun forwardsCommandAndPreservesOutputEventOrder()
    {
        val events = mutableListOf<AgentCoreRuntimeCommandEvent>()
        val data = object : AgentCoreRuntimeDataClient
        {
            override suspend fun invoke(request: InvokeAgentRuntimeRequest): InvokeAgentRuntimeResponse =
                error("not used")

            override suspend fun stop(request: StopRuntimeSessionRequest): StopRuntimeSessionResponse =
                error("not used")

            override suspend fun invokeCommand(
                request: InvokeAgentRuntimeCommandRequest,
                onEvent: suspend (AgentCoreRuntimeCommandEvent) -> Unit
            ): AgentCoreRuntimeCommandResult
            {
                assertEquals("printf smoke", request.body?.command)
                assertEquals(12, request.body?.timeout)
                onEvent(AgentCoreRuntimeCommandEvent.Stdout("out-1"))
                onEvent(AgentCoreRuntimeCommandEvent.Stderr("err-1"))
                onEvent(AgentCoreRuntimeCommandEvent.Stdout("out-2"))
                onEvent(AgentCoreRuntimeCommandEvent.Terminal(0, CommandExecutionStatus.Completed))
                return AgentCoreRuntimeCommandResult(
                    stdout = "out-1out-2",
                    stderr = "err-1",
                    exitCode = 0,
                    status = CommandExecutionStatus.Completed,
                    runtimeSessionId = "session"
                )
            }
        }

        val client = AgentCoreRuntimeClient(
            AgentCoreRuntimeClientConfig("http://runtime"),
            dataClient = data
        )
        try
        {
            val result = runBlocking {
                client.executeCommand(
                    InvokeAgentRuntimeCommandRequest {
                        agentRuntimeArn = "arn:runtime"
                        runtimeSessionId = "session"
                        body {
                            command = "printf smoke"
                            timeout = 12
                        }
                    }
                ) { event -> events += event }
            }

            assertEquals(
                listOf(
                    AgentCoreRuntimeCommandEvent.Stdout("out-1"),
                    AgentCoreRuntimeCommandEvent.Stderr("err-1"),
                    AgentCoreRuntimeCommandEvent.Stdout("out-2"),
                    AgentCoreRuntimeCommandEvent.Terminal(0, CommandExecutionStatus.Completed)
                ),
                events
            )
            assertEquals("out-1out-2", result.stdout)
            assertEquals("err-1", result.stderr)
            assertEquals("session", result.runtimeSessionId)
        }
        finally
        {
            client.close()
        }
    }

    @Test
    fun requestTimeoutCancelsAStalledCommand()
    {
        val data = object : AgentCoreRuntimeDataClient
        {
            override suspend fun invoke(request: InvokeAgentRuntimeRequest): InvokeAgentRuntimeResponse =
                error("not used")

            override suspend fun stop(request: StopRuntimeSessionRequest): StopRuntimeSessionResponse =
                error("not used")

            override suspend fun invokeCommand(
                request: InvokeAgentRuntimeCommandRequest,
                onEvent: suspend (AgentCoreRuntimeCommandEvent) -> Unit
            ): AgentCoreRuntimeCommandResult
            {
                awaitCancellation()
            }
        }

        val client = AgentCoreRuntimeClient(
            AgentCoreRuntimeClientConfig("http://runtime"),
            dataClient = data
        )
        try
        {
            assertFailsWith<TimeoutCancellationException> {
                runBlocking {
                    client.executeCommand(
                        InvokeAgentRuntimeCommandRequest {
                            body {
                                command = "sleep"
                                timeout = 1
                            }
                        }
                    )
                }
            }
        }
        finally
        {
            client.close()
        }
    }
}
