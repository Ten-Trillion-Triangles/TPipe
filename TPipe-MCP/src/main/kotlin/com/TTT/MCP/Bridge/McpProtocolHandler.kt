package com.TTT.MCP.Bridge

import com.TTT.MCP.Models.JsonRpcError
import com.TTT.MCP.Models.JsonRpcRequest
import com.TTT.MCP.Models.JsonRpcResponse
import com.TTT.MCP.Models.McpJsonRpcError
import com.TTT.MCP.Server.McpPromptProvider
import com.TTT.MCP.Server.McpResourceProvider
import com.TTT.MCP.Server.McpToolRegistry
import com.TTT.PipeContextProtocol.PcpContext
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.encodeToString

class McpProtocolHandler(
    private val pcpContext: PcpContext,
    private val toolRegistry: McpToolRegistry,
    private val resourceProvider: McpResourceProvider,
    private val promptProvider: McpPromptProvider,
    private val serverInfo: Implementation = Implementation(name = "tpipe", version = "1.0.0"),
    private val capabilities: ServerCapabilities = ServerCapabilities(
        tools = ServerCapabilities.Tools(listChanged = true),
        resources = ServerCapabilities.Resources(subscribe = null, listChanged = true),
        prompts = ServerCapabilities.Prompts(listChanged = true)
    )
) {
    companion object {
        const val METHOD_INITIALIZE = "initialize"
        const val METHOD_TOOLS_LIST = "tools/list"
        const val METHOD_TOOLS_CALL = "tools/call"
        const val METHOD_RESOURCES_LIST = "resources/list"
        const val METHOD_RESOURCES_READ = "resources/read"
        const val METHOD_PROMPTS_LIST = "prompts/list"
        const val METHOD_PROMPTS_GET = "prompts/get"
        const val METHOD_SHUTDOWN = "shutdown"
        const val METHOD_NOTIFICATIONS_INITIALIZED = "notifications/initialized"

        const val PROTOCOL_VERSION = "2024-11-05"

        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        // TODO: Consider implementing rate limiting for incoming JSON-RPC requests
        // Rate limiting should track request frequency per connection/IP and apply
        // throttling or rejection when thresholds are exceeded. This protects against
        // DoS attacks and resource exhaustion scenarios.
        // Implementation options: token bucket, sliding window, or fixed window algorithms.
    }

    enum class ServerState {
        INITIALIZING,
        READY,
        SHUTTING_DOWN
    }

    private var serverState: ServerState = ServerState.INITIALIZING

    private inline fun <reified T> serializeToJson(obj: T): JsonElement {
        return Json.parseToJsonElement(json.encodeToString(obj))
    }

    private fun validateRequest(request: JsonRpcRequest): JsonRpcResponse? {
        if (request.jsonrpc != "2.0") {
            return JsonRpcResponse.error(
                id = request.id,
                error = McpJsonRpcError(
                    code = JsonRpcError.invalidRequest("Invalid JSON-RPC version").code,
                    message = "Invalid JSON-RPC version: ${request.jsonrpc}"
                )
            )
        }

        if (request.method.isBlank()) {
            return JsonRpcResponse.error(
                id = request.id,
                error = McpJsonRpcError(
                    code = JsonRpcError.invalidRequest("Method name cannot be empty").code,
                    message = "Method name cannot be empty"
                )
            )
        }

        if (!isValidMethodName(request.method)) {
            return JsonRpcResponse.error(
                id = request.id,
                error = McpJsonRpcError(
                    code = JsonRpcError.invalidRequest("Invalid method name format").code,
                    message = "Method name contains invalid characters: ${request.method}"
                )
            )
        }

        return null
    }

    private fun isValidMethodName(method: String): Boolean {
        if (method.isBlank()) return false
        val validMethodPattern = Regex("^[a-zA-Z_][a-zA-Z0-9_]*(/[a-zA-Z_][a-zA-Z0-9_]*)*$")
        return validMethodPattern.matches(method)
    }

    fun getServerState(): ServerState = serverState

    fun route(request: JsonRpcRequest): JsonRpcResponse {
        return try {
            validateRequest(request)?.let { return it }

            when (request.method) {
                METHOD_INITIALIZE -> handleInitialize(request)
                METHOD_SHUTDOWN -> handleShutdown(request)
                METHOD_NOTIFICATIONS_INITIALIZED -> handleNotificationsInitialized(request)
                METHOD_TOOLS_LIST,
                METHOD_TOOLS_CALL,
                METHOD_RESOURCES_LIST,
                METHOD_RESOURCES_READ,
                METHOD_PROMPTS_LIST,
                METHOD_PROMPTS_GET -> {
                    if (serverState != ServerState.READY) {
                        return notInitializedResponse(request)
                    }
                    when (request.method) {
                        METHOD_TOOLS_LIST -> handleToolsList(request)
                        METHOD_TOOLS_CALL -> handleToolsCall(request)
                        METHOD_RESOURCES_LIST -> handleResourcesList(request)
                        METHOD_RESOURCES_READ -> handleResourcesRead(request)
                        METHOD_PROMPTS_LIST -> handlePromptsList(request)
                        METHOD_PROMPTS_GET -> handlePromptsGet(request)
                        else -> JsonRpcResponse.error(
                            id = request.id,
                            error = McpJsonRpcError(
                                code = JsonRpcError.methodNotFound("Method not found: ${request.method}").code,
                                message = "Method not found: ${request.method}"
                            )
                        )
                    }
                }
                else -> JsonRpcResponse.error(
                    id = request.id,
                    error = McpJsonRpcError(
                        code = JsonRpcError.methodNotFound("Method not found: ${request.method}").code,
                        message = "Method not found: ${request.method}"
                    )
                )
            }
        } catch (e: IllegalArgumentException) {
            JsonRpcResponse.error(
                id = request.id,
                error = McpJsonRpcError(
                    code = JsonRpcError.invalidRequest("Invalid Request: ${e.message}").code,
                    message = "Invalid Request: ${e.message}"
                )
            )
        } catch (e: Exception) {
            JsonRpcResponse.error(
                id = request.id,
                error = McpJsonRpcError(
                    code = JsonRpcError.internalError("Internal error: ${e.message}").code,
                    message = "Internal error: ${e.message}"
                )
            )
        }
    }

    private fun notInitializedResponse(request: JsonRpcRequest): JsonRpcResponse {
        return JsonRpcResponse.error(
            id = request.id,
            error = McpJsonRpcError(
                code = JsonRpcError.serverError(-32000, "Server not initialized").code,
                message = "Server not initialized"
            )
        )
    }

    private fun handleInitialize(request: JsonRpcRequest): JsonRpcResponse {
        val serverInfoObj = buildJsonObject {
            put("name", serverInfo.name)
            put("version", serverInfo.version)
        }
        val capabilitiesObj = buildJsonObject {
            capabilities.tools?.let {
                put("tools", buildJsonObject {
                    put("listChanged", true)
                })
            }
            capabilities.resources?.let {
                put("resources", buildJsonObject {
                    put("listChanged", true)
                })
            }
            capabilities.prompts?.let {
                put("prompts", buildJsonObject {
                    put("listChanged", true)
                })
            }
        }
        val result = buildJsonObject {
            put("protocolVersion", PROTOCOL_VERSION)
            put("capabilities", capabilitiesObj)
            put("serverInfo", serverInfoObj)
        }
        serverState = ServerState.READY
        return JsonRpcResponse.success(id = request.id ?: JsonPrimitive(0), result = result)
    }

    private fun handleShutdown(request: JsonRpcRequest): JsonRpcResponse {
        serverState = ServerState.SHUTTING_DOWN
        return JsonRpcResponse.success(
            id = request.id ?: JsonPrimitive(0),
            result = buildJsonObject { put("shutdown", true) }
        )
    }

    private fun handleNotificationsInitialized(request: JsonRpcRequest): JsonRpcResponse {
        return JsonRpcResponse.success(
            id = request.id ?: JsonPrimitive(0),
            result = buildJsonObject { }
        )
    }

    private fun handleToolsList(request: JsonRpcRequest): JsonRpcResponse {
        val tools = toolRegistry.listTools()
        val result = buildJsonObject {
            put("tools", serializeToJson(tools))
        }
        return JsonRpcResponse.success(id = request.id ?: JsonPrimitive(0), result = result)
    }

    private fun handleToolsCall(request: JsonRpcRequest): JsonRpcResponse {
        val params = request.params
            ?: return JsonRpcResponse.error(
                id = request.id,
                error = McpJsonRpcError(
                    code = JsonRpcError.invalidParams("Missing params").code,
                    message = "Missing params"
                )
            )

        val name = (params["name"] as? JsonPrimitive)?.content
            ?: return JsonRpcResponse.error(
                id = request.id,
                error = McpJsonRpcError(
                    code = JsonRpcError.invalidParams("Missing tool name").code,
                    message = "Missing tool name"
                )
            )

        if (!isValidMethodName(name)) {
            return JsonRpcResponse.error(
                id = request.id,
                error = McpJsonRpcError(
                    code = JsonRpcError.invalidParams("Invalid tool name format").code,
                    message = "Tool name contains invalid characters: $name"
                )
            )
        }

        val argumentsElement = params["arguments"]
        if (argumentsElement != null && argumentsElement !is JsonObject) {
            return JsonRpcResponse.error(
                id = request.id,
                error = McpJsonRpcError(
                    code = JsonRpcError.invalidParams("Invalid arguments format").code,
                    message = "Arguments must be a JSON object if provided"
                )
            )
        }

        val arguments = mutableMapOf<String, String>()
        (argumentsElement as? JsonObject)?.forEach { (key, value) ->
            if (!isValidArgumentKey(key)) {
                return JsonRpcResponse.error(
                    id = request.id,
                    error = McpJsonRpcError(
                        code = JsonRpcError.invalidParams("Invalid argument key format").code,
                        message = "Argument key contains invalid characters: $key"
                    )
                )
            }
            arguments[key] = value.toString()
        }

        val result = toolRegistry.callTool(name, arguments)
        val resultJson = buildJsonObject {
            put("content", serializeToJson(result.content))
            put("isError", result.isError)
        }
        return JsonRpcResponse.success(id = request.id ?: JsonPrimitive(0), result = resultJson)
    }

    private fun isValidArgumentKey(key: String): Boolean {
        if (key.isBlank()) return false
        val validKeyPattern = Regex("^[a-zA-Z_][a-zA-Z0-9_]*$")
        return validKeyPattern.matches(key)
    }

    private fun handleResourcesList(request: JsonRpcRequest): JsonRpcResponse {
        val resources = resourceProvider.listResources()
        val result = buildJsonObject {
            put("resources", serializeToJson(resources))
        }
        return JsonRpcResponse.success(id = request.id ?: JsonPrimitive(0), result = result)
    }

    private fun handleResourcesRead(request: JsonRpcRequest): JsonRpcResponse {
        val params = request.params
            ?: return JsonRpcResponse.error(
                id = request.id,
                error = McpJsonRpcError(
                    code = JsonRpcError.invalidParams("Missing params").code,
                    message = "Missing params"
                )
            )

        val uri = (params["uri"] as? JsonPrimitive)?.content
            ?: return JsonRpcResponse.error(
                id = request.id,
                error = McpJsonRpcError(
                    code = JsonRpcError.invalidParams("Missing URI").code,
                    message = "Missing URI"
                )
            )

        val result = resourceProvider.readResource(uri)
        val resultJson = buildJsonObject {
            put("contents", serializeToJson(result.contents))
        }
        return JsonRpcResponse.success(id = request.id ?: JsonPrimitive(0), result = resultJson)
    }

    private fun handlePromptsList(request: JsonRpcRequest): JsonRpcResponse {
        val prompts = promptProvider.listPrompts()
        val result = buildJsonObject {
            put("prompts", serializeToJson(prompts))
        }
        return JsonRpcResponse.success(id = request.id ?: JsonPrimitive(0), result = result)
    }

    private fun handlePromptsGet(request: JsonRpcRequest): JsonRpcResponse {
        val params = request.params
            ?: return JsonRpcResponse.error(
                id = request.id,
                error = McpJsonRpcError(
                    code = JsonRpcError.invalidParams("Missing params").code,
                    message = "Missing params"
                )
            )

        val name = (params["name"] as? JsonPrimitive)?.content
            ?: return JsonRpcResponse.error(
                id = request.id,
                error = McpJsonRpcError(
                    code = JsonRpcError.invalidParams("Missing prompt name").code,
                    message = "Missing prompt name"
                )
            )

        val arguments = mutableMapOf<String, String>()
        (params["arguments"] as? JsonObject)?.forEach { (key, value) ->
            arguments[key] = value.toString()
        }

        val result = promptProvider.getPrompt(name, arguments)
        val resultJson = buildJsonObject {
            put("messages", serializeToJson(result.messages))
        }
        return JsonRpcResponse.success(id = request.id ?: JsonPrimitive(0), result = resultJson)
    }
}