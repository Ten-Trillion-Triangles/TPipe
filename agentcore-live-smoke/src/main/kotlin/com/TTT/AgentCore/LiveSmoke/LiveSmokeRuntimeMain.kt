package com.TTT.AgentCore.LiveSmoke

import com.TTT.AgentCore.Runtime.AgUi.AgentCoreAgUiRuntimeHost
import com.TTT.AgentCore.runtime.AgentCoreMcpRuntimeHost
import com.TTT.AgentCore.runtime.AgentCoreMcpRuntimeHostConfig
import com.TTT.AgentCore.runtime.AgentCoreRuntimeHost
import com.TTT.AgentCore.runtime.AgentCoreRuntimeHostConfig
import com.TTT.P2P.P2PInterface
import com.TTT.P2P.P2PRequest
import com.TTT.P2P.P2PResponse
import com.TTT.Pipe.MultimodalContent
import com.TTT.PipeContextProtocol.DynamicFunctionHandler
import com.TTT.PipeContextProtocol.FunctionRegistry
import com.TTT.PipeContextProtocol.FunctionSignature
import com.TTT.PipeContextProtocol.ParamType
import com.TTT.PipeContextProtocol.ParameterInfo
import com.TTT.PipeContextProtocol.ReturnTypeInfo
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.util.concurrent.atomic.AtomicInteger

/** Entrypoint for the disposable deterministic AgentCore runtime image. */
fun main()
{
    when(System.getenv("TPIPE_AGENTCORE_PROTOCOL").orEmpty().uppercase())
    {
        "HTTP", "" -> runHttp()
        "MCP" -> runMcp()
        "AGUI", "AG_UI" -> runAgUi()
        else -> error("TPIPE_AGENTCORE_PROTOCOL must be HTTP, MCP, or AGUI.")
    }
}

private fun runHttp()
{
    val host = AgentCoreRuntimeHost(
        config = AgentCoreRuntimeHostConfig(
            bindAddress = System.getenv("TPIPE_AGENTCORE_BIND_ADDRESS") ?: "0.0.0.0",
            port = System.getenv("TPIPE_AGENTCORE_PORT")?.toIntOrNull() ?: 8080
        ),
        factory = { DeterministicSmokeAgent() }
    )
    Runtime.getRuntime().addShutdownHook(Thread { host.close() })
    host.start(wait = true)
}

private fun runAgUi()
{
    val host = AgentCoreAgUiRuntimeHost(
        config = AgentCoreRuntimeHostConfig(
            bindAddress = System.getenv("TPIPE_AGENTCORE_BIND_ADDRESS") ?: "0.0.0.0",
            port = System.getenv("TPIPE_AGENTCORE_PORT")?.toIntOrNull() ?: 8080
        ),
        factory = { DeterministicSmokeAgent() }
    )
    Runtime.getRuntime().addShutdownHook(Thread { host.close() })
    host.start(wait = true)
}

private fun runMcp()
{
    registerSmokeMcpFunctions()
    System.setProperty("TPIPE_MCP_JSON", System.getenv("TPIPE_MCP_JSON") ?: smokeMcpJson())
    AgentCoreMcpRuntimeHost.run(
        AgentCoreMcpRuntimeHostConfig(
            bindAddress = System.getenv("TPIPE_AGENTCORE_BIND_ADDRESS") ?: "0.0.0.0",
            port = System.getenv("TPIPE_AGENTCORE_PORT")?.toIntOrNull() ?: 8000
        )
    )
}

/** Deterministic P2P root used to prove service/session/stream behavior. */
private class DeterministicSmokeAgent : P2PInterface
{
    private val invocationCount = AtomicInteger()
    private var callback: (suspend (String) -> Unit)? = null

    override fun setStreamingCallbackRecursive(callback: suspend (String) -> Unit)
    {
        this.callback = callback
    }

    override fun clearStreamingCallbacksRecursive()
    {
        callback = null
    }

    override suspend fun executeP2PRequest(request: P2PRequest): P2PResponse
    {
        val count = invocationCount.incrementAndGet()
        if(request.prompt.text.contains("stream", ignoreCase = true))
        {
            callback?.invoke("SMOKE_CHUNK_1|")
            callback?.invoke("SMOKE_CHUNK_2|")
        }
        return P2PResponse(
            output = MultimodalContent(
                text = "SMOKE_OK|session_count=$count|prompt_present=${request.prompt.text.isNotBlank()}"
            )
        )
    }

    override var killSwitch: com.TTT.P2P.KillSwitch? = null
}

private fun registerSmokeMcpFunctions()
{
    val signature = FunctionSignature(
        name = "smoke_echo",
        parameters = listOf(
            ParameterInfo(
                name = "message",
                type = ParamType.String,
                kotlinType = "kotlin.String",
                description = "Message to echo."
            )
        ),
        returnType = ReturnTypeInfo(ParamType.String, "kotlin.String"),
        description = "Returns a deterministic TPipe AgentCore smoke marker."
    )
    val handler: DynamicFunctionHandler = { arguments ->
        "SMOKE_ECHO:${arguments["message"].orEmpty()}"
    }
    FunctionRegistry.registerDynamicFunction("smoke_echo", signature, handler)

    val forbiddenSignature = FunctionSignature(
        name = "smoke_forbidden",
        parameters = emptyList(),
        returnType = ReturnTypeInfo(ParamType.String, "kotlin.String"),
        description = "Tool used only for policy denial assertions."
    )
    FunctionRegistry.registerDynamicFunction(
        "smoke_forbidden",
        forbiddenSignature
    ) { "SMOKE_FORBIDDEN" }
}

private fun smokeMcpJson(): String = Json.parseToJsonElement(
    """
    {
      "tools": [
        {
          "name": "smoke_echo",
          "description": "Returns a deterministic smoke marker.",
          "inputSchema": {
            "type": "object",
            "properties": {"message": {"type": "string"}},
            "required": ["message"]
          }
        },
        {
          "name": "smoke_forbidden",
          "description": "Used for policy denial assertions.",
          "inputSchema": {"type": "object", "properties": {}}
        }
      ],
      "resources": [{
        "uri": "tpipe://agentcore-smoke/resource",
        "name": "smoke_resource",
        "description": "A run-local smoke resource."
      }],
      "prompts": [{
        "name": "smoke_prompt",
        "description": "A run-local smoke prompt."
      }]
    }
    """.trimIndent()
).toString()
