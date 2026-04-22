package com.TTT.MCP.Models

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.Json

object JsonRpcErrorCode {
    const val PARSE_ERROR = -32700
    const val INVALID_REQUEST = -32600
    const val METHOD_NOT_FOUND = -32601
    const val INVALID_PARAMS = -32602
    const val INTERNAL_ERROR = -32603
    const val SERVER_ERROR_MIN = -32099
    const val SERVER_ERROR_MAX = -32000
}

@Serializable
data class JsonRpcError(
    val code: Int,
    val message: String,
    val data: JsonElement? = null
) {
    companion object {
        fun parseError(message: String, data: JsonElement? = null) =
            JsonRpcError(JsonRpcErrorCode.PARSE_ERROR, message, data)

        fun invalidRequest(message: String, data: JsonElement? = null) =
            JsonRpcError(JsonRpcErrorCode.INVALID_REQUEST, message, data)

        fun methodNotFound(message: String, data: JsonElement? = null) =
            JsonRpcError(JsonRpcErrorCode.METHOD_NOT_FOUND, message, data)

        fun invalidParams(message: String, data: JsonElement? = null) =
            JsonRpcError(JsonRpcErrorCode.INVALID_PARAMS, message, data)

        fun internalError(message: String, data: JsonElement? = null) =
            JsonRpcError(JsonRpcErrorCode.INTERNAL_ERROR, message, data)

        fun serverError(code: Int, message: String, data: JsonElement? = null): JsonRpcError {
            require(code in JsonRpcErrorCode.SERVER_ERROR_MIN..JsonRpcErrorCode.SERVER_ERROR_MAX) {
                "Server error code must be between -32099 and -32000"
            }
            return JsonRpcError(code, message, data)
        }
    }
}

@Serializable
data class JsonRpcRequest(
    val jsonrpc: String = "2.0",
    val id: JsonElement? = null,
    val method: String,
    val params: JsonObject? = null
) {
    val isNotification: Boolean get() = id == null || id is kotlinx.serialization.json.JsonNull

    companion object {
        private val JSON_RPC_VERSION = "2.0"
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        fun fromJson(jsonStr: String): JsonRpcRequest {
            val element = Json.parseToJsonElement(jsonStr)
            require(element is JsonObject) { "Request must be a JSON object" }
            return fromJsonElement(element)
        }

        fun fromJsonElement(element: JsonElement): JsonRpcRequest {
            require(element is JsonObject) { "Request must be a JSON object" }
            val jsonrpc = element["jsonrpc"]?.toString()?.removeSurrounding("\"")
                ?: throw IllegalArgumentException("Missing jsonrpc version")
            require(jsonrpc == JSON_RPC_VERSION) { "Invalid JSON-RPC version: $jsonrpc" }
            val method = element["method"]?.toString()?.removeSurrounding("\"")
                ?: throw IllegalArgumentException("Missing method")
            val paramsElement = element["params"]
            if (paramsElement != null && paramsElement !is JsonObject) {
                throw IllegalArgumentException("Params must be a JSON object if provided")
            }
            return JsonRpcRequest(
                id = element["id"],
                method = method,
                params = paramsElement as? JsonObject
            )
        }
    }
}

@Serializable
data class JsonRpcResponse(
    val jsonrpc: String = "2.0",
    val id: JsonElement?,
    val result: JsonObject? = null,
    val error: McpJsonRpcError? = null
) {
    val isError: Boolean get() = error != null
    val isSuccess: Boolean get() = error == null

    companion object {
        fun success(id: JsonElement, result: JsonObject? = null) =
            JsonRpcResponse(id = id, result = result, error = null)

        fun error(id: JsonElement?, error: McpJsonRpcError) =
            JsonRpcResponse(id = id, result = null, error = error)

        fun error(id: JsonElement?, jsonRpcError: JsonRpcError) =
            JsonRpcResponse(
                id = id,
                result = null,
                error = McpJsonRpcError(
                    code = jsonRpcError.code,
                    message = jsonRpcError.message,
                    data = jsonRpcError.data
                )
            )
    }
}

@Serializable
data class JsonRpcBatchRequest(
    val requests: List<JsonRpcRequest>
) {
    val isNotificationBatch: Boolean get() = requests.all { it.isNotification }

    companion object {
        fun fromJson(jsonStr: String): JsonRpcBatchRequest {
            val element = Json.parseToJsonElement(jsonStr)
            require(element is JsonArray) { "Batch must be a JSON array" }
            return JsonRpcBatchRequest(
                requests = element.jsonArray.map { JsonRpcRequest.fromJsonElement(it) }
            )
        }
    }
}

@Serializable
data class JsonRpcBatchResponse(
    val responses: List<JsonRpcResponse>
) {
    companion object {
        fun from(responses: List<JsonRpcResponse>) = JsonRpcBatchResponse(responses)
    }
}