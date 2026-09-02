package com.TTT.AgentCore.Runtime.AgUi

import com.TTT.Context.ContextWindow
import com.TTT.P2P.P2PRequest
import com.TTT.Pipe.MultimodalContent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Minimal AG-UI input message used at the AgentCore-only boundary. */
data class RunAgentMessage(
    val role: String,
    val content: String
)

/** AG-UI run input with non-secret thread/run/session correlation. */
data class RunAgentInput(
    val threadId: String,
    val runId: String,
    val messages: List<RunAgentMessage>,
    val sessionId: String? = null,
    val toolDefinitions: List<String> = emptyList()
)

/** Result of mapping AG-UI input to an existing generic TPipe request. */
data class AgentCoreAgUiMappedRequest(
    val sessionId: String,
    val request: P2PRequest,
    val threadId: String,
    val runId: String
)

/** Configurable default mapper for AG-UI messages and prior conversation. */
class AgentCoreAgUiInputMapper(
    private val sessionIdResolver: (RunAgentInput) -> String = { input -> input.sessionId ?: input.threadId }
) {
    /** Parse the small canonical input envelope without making client tools executable. */
    fun decode(value: String): RunAgentInput {
        val json = Json.parseToJsonElement(value).jsonObject
        val messages = json["messages"]?.jsonArray.orEmpty().map { message ->
            val objectValue = message.jsonObject
            RunAgentMessage(
                role = objectValue["role"]?.jsonPrimitive?.content.orEmpty(),
                content = objectValue["content"]?.jsonPrimitive?.content.orEmpty()
            )
        }
        return RunAgentInput(
            threadId = json["threadId"]?.jsonPrimitive?.content.orEmpty(),
            runId = json["runId"]?.jsonPrimitive?.content.orEmpty(),
            messages = messages,
            sessionId = json["sessionId"]?.jsonPrimitive?.content,
            toolDefinitions = json["tools"]?.jsonArray?.map { it.toString() }.orEmpty()
        )
    }

    /** Map the latest user message to P2P and keep prior messages in request context. */
    fun map(input: RunAgentInput): AgentCoreAgUiMappedRequest {
        val latestUserIndex = input.messages.indexOfLast { it.role.equals("user", ignoreCase = true) }
        require(latestUserIndex >= 0) { "AG-UI input requires at least one user message." }
        val latestUserMessage = input.messages[latestUserIndex]
        require(latestUserMessage.content.isNotBlank()) { "AG-UI user message must not be blank." }
        val priorContext = input.messages.take(latestUserIndex).map { "${it.role}: ${it.content}" }
        val context = ContextWindow().apply { contextElements.addAll(priorContext) }
        return AgentCoreAgUiMappedRequest(
            sessionId = sessionIdResolver(input),
            request = P2PRequest(
                prompt = MultimodalContent(latestUserMessage.content),
                context = context.takeIf { priorContext.isNotEmpty() }
            ),
            threadId = input.threadId,
            runId = input.runId
        )
    }
}
