package com.TTT.AgentCore.runtime

import com.TTT.Pipe.MultimodalContent
import com.TTT.Util.deserialize
import com.TTT.Util.serialize
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * HTTP invocation body accepted by the AgentCore runtime host.
 *
 * [input] and [sessionId] are retained as source-compatible conveniences for
 * early TPipe-AgentCore callers. The wire codec emits the canonical AgentCore
 * `prompt`/`content` shape and accepts the old `input` shape on ingress.
 */
data class AgentCoreInvocationRequest(
    val input: String = "",
    val sessionId: String? = null,
    val stream: Boolean = false,
    val content: MultimodalContent? = null,
    val prompt: String? = null
)
{
    /** Resolve the content that should be passed to the TPipe P2P boundary. */
    fun effectiveContent(): MultimodalContent {
        require((prompt != null) xor (content != null) ||
            (prompt == null && content == null && input.isNotEmpty())) {
            "Exactly one of 'prompt' or 'content' is required."
        }
        return content ?: MultimodalContent(prompt ?: input)
    }
}

/**
 * HTTP invocation response returned by the runtime host.
 *
 * The public text [output] property is kept for source compatibility. The
 * canonical response codec writes a Core-serialized [outputContent] object.
 */
data class AgentCoreInvocationResponse(
    val output: String = "",
    val sessionId: String,
    val streamed: Boolean = false,
    val outputContent: MultimodalContent? = null
)

/** Structured error returned for a rejected or failed invocation. */
data class AgentCoreInvocationError(
    val error: String,
    val sessionId: String? = null,
    val errorType: String = "InvocationError",
    val retryable: Boolean = false
)

/** Minimal health response for `/ping`. */
data class AgentCorePingResponse(
    val status: String = "Healthy",
    val timeOfLastUpdate: Long
)

/** JSON codecs for the small runtime wire objects. */
object AgentCoreRuntimeJson {
    /** Decode a runtime invocation request. */
    fun decodeRequest(value: String): AgentCoreInvocationRequest {
        val json = Json.parseToJsonElement(value).jsonObject
        val prompt = json["prompt"]?.takeUnless { it == JsonNull }?.jsonPrimitive?.content
        val content = json["content"]?.takeUnless { it == JsonNull }?.let { element ->
            deserialize<MultimodalContent>(element.toString(), useRepair = false)
                ?: throw IllegalArgumentException("The invocation content is not valid MultimodalContent JSON.")
        }
        val legacyInput = json["input"]?.takeUnless { it == JsonNull }?.jsonPrimitive?.content
        require(
            ((prompt != null) xor (content != null)) && legacyInput == null ||
                (prompt == null && content == null && legacyInput != null)
        ) {
            "Exactly one of 'prompt' or 'content' is required."
        }
        return AgentCoreInvocationRequest(
            input = legacyInput ?: prompt ?: content?.text.orEmpty(),
            sessionId = json["sessionId"]?.jsonPrimitive?.content,
            stream = json["stream"]?.jsonPrimitive?.content?.toBoolean() ?: false,
            content = content,
            prompt = prompt
        )
    }

    /** Encode a runtime invocation request. */
    fun encodeRequest(value: AgentCoreInvocationRequest): String = buildJsonObject {
        require(
            (value.content != null && value.prompt == null && value.input.isEmpty()) ||
                (value.content == null && value.prompt != null && value.input.isEmpty()) ||
                (value.content == null && value.prompt == null && value.input.isNotEmpty())
        ) {
            "Exactly one of 'prompt' or 'content' is required."
        }
        when {
            value.content != null -> put("content", Json.parseToJsonElement(serialize(value.content)))
            value.prompt != null -> put("prompt", value.prompt)
            value.input.isNotEmpty() -> put("prompt", value.input)
            else -> error("Exactly one of 'prompt' or 'content' is required.")
        }
        put("stream", value.stream)
    }.toString()

    /**
     * Encode a runtime invocation response.
     *
     * @param value Response to encode.
     * @param includeSessionId Include the resolved session id in the JSON
     * response. WebSocket callers use this because they cannot receive the
     * HTTP session header.
     */
    fun encodeResponse(value: AgentCoreInvocationResponse, includeSessionId: Boolean = false): String = buildJsonObject {
        put(
            "output",
            Json.parseToJsonElement(
                serialize(value.outputContent ?: MultimodalContent(value.output))
            )
        )
        if(includeSessionId)
        {
            put("sessionId", value.sessionId)
        }
    }.toString()

    /** Decode a runtime invocation response. */
    fun decodeResponse(value: String): AgentCoreInvocationResponse {
        val json = Json.parseToJsonElement(value).jsonObject
        val outputElement: JsonElement? = json["output"]
        val outputContent = outputElement
            ?.takeUnless { it == JsonNull }
            ?.let { element ->
                if (element is kotlinx.serialization.json.JsonPrimitive) {
                    null
                }
                else {
                    deserialize<MultimodalContent>(element.toString(), useRepair = false)
                }
            }
        val outputText = outputContent?.text
            ?: outputElement?.jsonPrimitive?.content.orEmpty()
        return AgentCoreInvocationResponse(
            output = outputText,
            sessionId = json["sessionId"]?.jsonPrimitive?.content.orEmpty(),
            streamed = json["streamed"]?.jsonPrimitive?.content?.toBoolean() ?: false,
            outputContent = outputContent
        )
    }

    /** Encode a runtime error. */
    fun encodeError(value: AgentCoreInvocationError): String = buildJsonObject {
        put("type", "error")
        put("errorType", value.errorType)
        put("message", value.error)
        put("retryable", value.retryable)
        value.sessionId?.let { put("sessionId", it) }
    }.toString()

    /** Encode the health response. */
    fun encodePing(value: AgentCorePingResponse): String = buildJsonObject {
        put("status", value.status)
        put("time_of_last_update", value.timeOfLastUpdate)
    }.toString()

    /** Encode one streamed text event. */
    fun encodeStreamEvent(value: AgentCoreStreamEvent): String = buildJsonObject {
        put("type", if (value.error != null) "error" else if (value.done) "final" else "chunk")
        put("text", value.text)
        put("done", value.done)
        value.sessionId?.let { put("sessionId", it) }
        value.error?.let { put("error", it) }
    }.toString()

    /** Decode a streamed text event. */
    fun decodeStreamEvent(value: String): AgentCoreStreamEvent {
        val json = Json.parseToJsonElement(value).jsonObject
        val type = json["type"]?.jsonPrimitive?.content
        return AgentCoreStreamEvent(
            text = json["text"]?.jsonPrimitive?.content.orEmpty(),
            done = json["done"]?.jsonPrimitive?.content?.toBoolean() == true || type == "final",
            sessionId = json["sessionId"]?.jsonPrimitive?.content,
            error = json["error"]?.jsonPrimitive?.content
                ?: json["message"]?.jsonPrimitive?.content?.takeIf { type == "error" }
        )
    }
}
