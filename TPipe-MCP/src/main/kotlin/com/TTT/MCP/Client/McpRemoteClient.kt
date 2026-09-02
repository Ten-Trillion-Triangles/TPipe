package com.TTT.MCP.Client

import com.TTT.PipeContextProtocol.DynamicFunctionHandler
import com.TTT.PipeContextProtocol.FunctionSignature
import com.TTT.PipeContextProtocol.ParamType
import com.TTT.PipeContextProtocol.ParameterInfo
import com.TTT.PipeContextProtocol.PcpContext
import com.TTT.PipeContextProtocol.ReturnTypeInfo
import com.TTT.PipeContextProtocol.bindDynamicFunction
import com.TTT.PipeContextProtocol.fromFunctionSignature
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.plugin
import io.ktor.client.plugins.pluginOrNull
import io.ktor.client.plugins.timeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.headers
import io.ktor.http.content.OutgoingContent
import io.modelcontextprotocol.kotlin.sdk.ExperimentalMcpApi
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.ClientOptions
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpClientTransport
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ListPromptsRequest
import io.modelcontextprotocol.kotlin.sdk.types.ListResourcesRequest
import io.modelcontextprotocol.kotlin.sdk.types.ListToolsRequest
import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import io.modelcontextprotocol.kotlin.sdk.types.PaginatedRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.Prompt
import io.modelcontextprotocol.kotlin.sdk.types.Resource
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * Supplies request headers for an MCP connection.
 *
 * The provider is evaluated for every HTTP request. This makes short-lived
 * bearer tokens and workload identity headers possible without copying a
 * credential into the client configuration.
 */
fun interface McpRemoteAuthProvider {
    /**
     * Return the headers that should be added to the next HTTP request.
     *
     * @return Headers for the next request.
     */
    suspend fun headers(): Map<String, String>
}

/** Signs one outgoing MCP HTTP request, including its exact request body. */
fun interface McpRemoteRequestSigner {
    /**
     * Return authentication headers for the supplied request.
     *
     * @param url Final request URL.
     * @param method HTTP method.
     * @param headers Request headers before signing.
     * @param body Exact request body bytes.
     * @return Headers to add to the request.
     */
    suspend fun sign(
        url: String,
        method: String,
        headers: Map<String, String>,
        body: ByteArray
    ): Map<String, String>
}

/**
 * Configuration for an MCP 2025-06-18 Streamable HTTP connection.
 *
 * @param endpoint MCP endpoint.
 * @param clientName Client name advertised during initialization.
 * @param clientVersion Client version advertised during initialization.
 * @param requestHeaders Static headers added to requests.
 * @param authProvider Dynamic request-header provider.
 * @param namespacePrefix Optional PCP function-name prefix.
 * @param clientOptions MCP SDK client options.
 * @param requestSigner Dynamic request signer.
 * @param requestTimeoutMillis Request timeout in milliseconds.
 * @param connectTimeoutMillis Connection timeout in milliseconds.
 * @param socketTimeoutMillis Socket timeout in milliseconds.
 */
data class McpRemoteClientConfig(
    val endpoint: String,
    val clientName: String = "TPipe-MCP-Remote-Client",
    val clientVersion: String = "1.0.0",
    val requestHeaders: Map<String, String> = emptyMap(),
    val authProvider: McpRemoteAuthProvider? = null,
    val namespacePrefix: String? = null,
    val clientOptions: ClientOptions = ClientOptions(),
    val requestSigner: McpRemoteRequestSigner? = null,
    val requestTimeoutMillis: Long? = 60_000L,
    val connectTimeoutMillis: Long? = 10_000L,
    val socketTimeoutMillis: Long? = 60_000L
)

/**
 * Generic Streamable HTTP MCP client used by TPipe integrations.
 *
 * This class intentionally exposes MCP tools, resources, and prompts rather
 * than inventing an AgentCore-specific transport. It also keeps the SDK
 * session alive until [close] so server-assigned MCP session ids are reused.
 *
 * @param config MCP endpoint, authentication, and timeout settings.
 * @param httpClient Optional injected HTTP client.
 */
@OptIn(ExperimentalMcpApi::class)
class McpRemoteClient(
    private val config: McpRemoteClientConfig,
    httpClient: HttpClient? = null
) : AutoCloseable
{

    private val ownsHttpClient = httpClient == null
    private val sourceHttpClient = httpClient ?: HttpClient {
        install(HttpTimeout) {
            requestTimeoutMillis = config.requestTimeoutMillis
            connectTimeoutMillis = config.connectTimeoutMillis
            socketTimeoutMillis = config.socketTimeoutMillis
        }
    }
    private val sourceHasHttpTimeoutPlugin = sourceHttpClient.pluginOrNull(HttpTimeout) != null
    private val httpClient = if(
        config.authProvider != null ||
        config.requestSigner != null ||
        (!ownsHttpClient && (
            config.requestTimeoutMillis != null ||
                config.connectTimeoutMillis != null ||
                config.socketTimeoutMillis != null
            ) && !sourceHasHttpTimeoutPlugin)
    )
    {
        sourceHttpClient.config {
            if(!ownsHttpClient && !sourceHasHttpTimeoutPlugin && (
                config.requestTimeoutMillis != null ||
                    config.connectTimeoutMillis != null ||
                    config.socketTimeoutMillis != null
                ))
            {
                install(HttpTimeout) {
                    requestTimeoutMillis = config.requestTimeoutMillis
                    connectTimeoutMillis = config.connectTimeoutMillis
                    socketTimeoutMillis = config.socketTimeoutMillis
                }
            }
        }
    }

    else
    {
        sourceHttpClient
    }
    private val configuredHttpClient = httpClient !== sourceHttpClient
    private val transport: StreamableHttpClientTransport =
        StreamableHttpClientTransport(
            client = this.httpClient,
            url = config.endpoint,
            requestBuilder = {
                addConfiguredHeaders(this)
                if(config.requestTimeoutMillis != null ||
                    config.connectTimeoutMillis != null ||
                    config.socketTimeoutMillis != null)
                {
                    timeout {
                        requestTimeoutMillis = config.requestTimeoutMillis
                        connectTimeoutMillis = config.connectTimeoutMillis
                        socketTimeoutMillis = config.socketTimeoutMillis
                    }
                }
            }
        )
    private val client = Client(
        Implementation(name = config.clientName, version = config.clientVersion),
        config.clientOptions
    )
    private val connectionMutex = Mutex()
    @Volatile
    private var connected = false
    private val boundFunctions = ConcurrentHashMap<String, FunctionSignature>()

    init {
        if(config.authProvider != null || config.requestSigner != null)
        {
            this.httpClient.plugin(HttpSend).intercept { request ->
                applyDynamicAuthentication(request)
                execute(request)
            }
        }
        require(config.requestTimeoutMillis == null || config.requestTimeoutMillis > 0) {
            "MCP request timeout must be positive or null."
        }
        require(config.connectTimeoutMillis == null || config.connectTimeoutMillis > 0) {
            "MCP connect timeout must be positive or null."
        }
        require(config.socketTimeoutMillis == null || config.socketTimeoutMillis > 0) {
            "MCP socket timeout must be positive or null."
        }
    }

    /**
     * Connect once and retain the negotiated MCP session for later calls.
     *
     * @return Nothing; completion indicates that the session is connected.
     */
    suspend fun connect() = connectionMutex.withLock {
        if(!connected)
        {
            client.connect(transport)
            connected = true
        }
    }

    /**
     * Return the underlying SDK client for capabilities not wrapped here.
     *
     * @return Underlying MCP SDK client.
     */
    fun sdkClient(): Client = client

    /**
     * Return the transport and its current negotiated session id.
     *
     * @return Current MCP session identifier, when negotiated.
     */
    fun sessionId(): String? = transport.sessionId

    /** List all tools, following MCP pagination cursors.
     *
     * @return All tools returned by the server.
     */
    suspend fun listTools(): List<Tool>
    {
        ensureConnected()
        val tools = mutableListOf<Tool>()
        var cursor: String? = null
        do {
            val toolsResponse = client.listTools(
                ListToolsRequest(PaginatedRequestParams(cursor = cursor))
            )
            tools += toolsResponse.tools
            cursor = toolsResponse.nextCursor
        } while(!cursor.isNullOrBlank())
        return tools
    }

    /** List all resources, following MCP pagination cursors.
     *
     * @return All resources returned by the server.
     */
    suspend fun listResources(): List<Resource>
    {
        ensureConnected()
        val resources = mutableListOf<Resource>()
        var cursor: String? = null
        do {
            val resourcesResponse = client.listResources(
                ListResourcesRequest(PaginatedRequestParams(cursor = cursor))
            )
            resources += resourcesResponse.resources
            cursor = resourcesResponse.nextCursor
        } while(!cursor.isNullOrBlank())
        return resources
    }

    /** List all prompts, following MCP pagination cursors.
     *
     * @return All prompts returned by the server.
     */
    suspend fun listPrompts(): List<Prompt>
    {
        ensureConnected()
        val prompts = mutableListOf<Prompt>()
        var cursor: String? = null
        do {
            val promptsResponse = client.listPrompts(
                ListPromptsRequest(PaginatedRequestParams(cursor = cursor))
            )
            prompts += promptsResponse.prompts
            cursor = promptsResponse.nextCursor
        } while(!cursor.isNullOrBlank())
        return prompts
    }

    /** Call a remote MCP tool and return its protocol JSON result.
     *
     * @param name Remote tool name.
     * @param arguments String-valued PCP-compatible arguments.
     * @return Serialized MCP tool result.
     */
    suspend fun callTool(name: String, arguments: Map<String, String>): String
    {
        return callToolValues(name, arguments)
    }

    private suspend fun callToolValues(name: String, arguments: Map<String, Any?>): String
    {
        ensureConnected()
        val toolResponse = client.callTool(name, arguments)
        return McpJson.encodeToString(toolResponse)
    }

    /**
     * Bind the currently advertised remote tools to PCP dynamic handlers.
     *
     * Parameter values remain strings at the PCP boundary; compatible scalar,
     * list, and map values are converted to native JSON values before the MCP
     * SDK call while preserving PCP validation and enum metadata.
     *
     * @param context Context receiving the bound functions.
     * @return The supplied context after binding.
     */
    suspend fun bindToolsToPcp(context: PcpContext): PcpContext
    {
        bindToolsToPcp(context, config.namespacePrefix)
        return context
    }

    /**
     * Bind tools under an optional namespace such as `gateway__`.
     *
     * A collision is rejected instead of silently replacing a function in the
     * process-wide PCP registry. Rebinding the same signature is idempotent.
     *
     * @param context Context receiving the bound functions.
     * @param namespacePrefix Optional prefix for exposed function names.
     * @return The supplied context after binding.
     */
    suspend fun bindToolsToPcp(context: PcpContext, namespacePrefix: String?): PcpContext
    {
        listTools().forEach { tool ->
            val exposedName = (namespacePrefix.orEmpty() + tool.name)
            val signature = tool.toFunctionSignature().copy(name = exposedName)
            val existing = com.TTT.PipeContextProtocol.FunctionRegistry.getSignature(exposedName)
            if(existing != null)
            {
                require(boundFunctions[exposedName] == signature && existing == signature) {
                    "MCP tool '$exposedName' is already registered by another binding."
                }
                context.addTPipeOption(com.TTT.PipeContextProtocol.TPipeContextOptions().fromFunctionSignature(signature))
                return@forEach
            }
            val handler: DynamicFunctionHandler = { arguments ->
                val typedArguments = signature.parameters.mapNotNull { parameter ->
                    arguments[parameter.name]?.let { value ->
                        parameter.name to value.toMcpArgument(parameter.type)
                    }
                }.toMap()
                callToolValues(tool.name, typedArguments)
            }
            context.bindDynamicFunction(exposedName, signature, handler)
            boundFunctions[exposedName] = signature
        }
        return context
    }

    /**
     * Create a new PCP context populated with this connection's remote tools.
     *
     * @param namespacePrefix Optional prefix for exposed function names.
     * @return New context containing the remote tools.
     */
    suspend fun toPcpContext(namespacePrefix: String? = config.namespacePrefix): PcpContext =
        bindToolsToPcp(PcpContext(), namespacePrefix)

    /**
     * Close the MCP session and the owned HTTP client, if any.
     *
     * @return Nothing; completion indicates that owned resources were closed.
     */
    suspend fun closeSuspend()
    {
        boundFunctions.forEach { (name, signature) ->
            com.TTT.PipeContextProtocol.FunctionRegistry.unregisterFunction(name, signature)
        }
        boundFunctions.clear()
        connectionMutex.withLock {
            if(connected)
            {
                client.close()
                connected = false
            }
        }
        if(configuredHttpClient)
        {
            httpClient.close()
        }
        if(ownsHttpClient)
        {
            sourceHttpClient.close()
        }
    }

    /** Blocking AutoCloseable bridge for JVM applications. */
    override fun close()
    {
        runBlocking { closeSuspend() }
    }

    private suspend fun ensureConnected()
    {
        connect()
    }

    private suspend fun applyDynamicAuthentication(request: HttpRequestBuilder)
    {
        config.authProvider?.headers()?.forEach { (name, value) ->
            request.headers.remove(name)
            request.headers.append(name, value)
        }
        config.requestSigner?.let { signer ->
            val body = (request.body as? OutgoingContent.ByteArrayContent)?.bytes() ?: ByteArray(0)
            val headers = request.headers.entries().associate { (name, values) ->
                name to values.joinToString(",")
            }
            signer.sign(
                url = request.url.buildString(),
                method = request.method.value,
                headers = headers,
                body = body
            ).forEach { (name, value) ->
                request.headers.remove(name)
                request.headers.append(name, value)
            }
        }
    }

    private fun addConfiguredHeaders(builder: HttpRequestBuilder)
    {
        builder.headers {
            config.requestHeaders.forEach { (name, value) -> append(name, value) }
        }
    }
}

private fun Tool.toFunctionSignature(): FunctionSignature
{
    val properties: JsonObject = inputSchema.properties ?: JsonObject(emptyMap())
    val parameters = properties.map { (name, definition) ->
        val objectDefinition = definition as? JsonObject
        val enumValues = objectDefinition?.get("enum")?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
        ParameterInfo(
            name = name,
            type = objectDefinition?.get("type").toPcpType(enumValues),
            kotlinType = objectDefinition?.get("type").toPcpKotlinType(enumValues),
            isOptional = name !in inputSchema.required.orEmpty(),
            enumValues = enumValues,
            description = objectDefinition?.get("description")?.jsonPrimitive?.content.orEmpty()
        )
    }
    return FunctionSignature(
        name = name,
        parameters = parameters,
        returnType = ReturnTypeInfo(ParamType.String, "kotlin.String", isNullable = false),
        description = description.orEmpty()
    )
}

private fun JsonElement?.schemaType(): String? = when(this)
{
    is JsonObject -> {
        when(val type = this["type"])
        {
            is JsonPrimitive -> type.content
            is kotlinx.serialization.json.JsonArray -> type
                .mapNotNull { (it as? JsonPrimitive)?.content }
                .filter { it != "null" }
                .singleOrNull()
            else -> null
        }
    }
    else -> null
}

private fun JsonElement?.toPcpType(enumValues: List<String>): ParamType = when {
    enumValues.isNotEmpty() -> ParamType.Enum
    schemaType() == "integer" -> ParamType.Int
    schemaType() == "number" -> ParamType.Float
    schemaType() == "boolean" -> ParamType.Bool
    schemaType() == "array" -> ParamType.List
    schemaType() == "object" -> ParamType.Map
    schemaType() == "string" -> ParamType.String
    else -> ParamType.Any
}

internal fun JsonElement?.toPcpKotlinType(enumValues: List<String>): String = when {
    enumValues.isNotEmpty() -> "kotlin.String"
    schemaType() == "integer" -> "kotlin.Int"
    schemaType() == "number" -> "kotlin.Double"
    schemaType() == "boolean" -> "kotlin.Boolean"
    schemaType() == "array" -> "kotlin.collections.List<kotlin.Any?>"
    schemaType() == "object" -> "kotlin.collections.Map<kotlin.String, kotlin.Any?>"
    schemaType() == "string" -> "kotlin.String"
    else -> "kotlin.Any"
}

/** Preserve the existing internal schema-helper call shape for module tests and adapters. */
internal fun String?.toPcpKotlinType(enumValues: List<String>): String = when {
    enumValues.isNotEmpty() -> "kotlin.String"
    this == "integer" -> "kotlin.Int"
    this == "number" -> "kotlin.Double"
    this == "boolean" -> "kotlin.Boolean"
    this == "array" -> "kotlin.collections.List<kotlin.Any?>"
    this == "object" -> "kotlin.collections.Map<kotlin.String, kotlin.Any?>"
    this == "string" -> "kotlin.String"
    else -> "kotlin.Any"
}

internal fun String.toMcpArgument(type: ParamType): Any? = when(type)
{
    ParamType.String, ParamType.Enum -> this
    ParamType.Int -> toIntOrNull()
        ?: throw IllegalArgumentException("Expected an integer MCP argument, got '$this'.")
    ParamType.Float -> toDoubleOrNull()
        ?: throw IllegalArgumentException("Expected a number MCP argument, got '$this'.")
    ParamType.Bool -> when(lowercase())
    {
        "true" -> true
        "false" -> false
        else -> throw IllegalArgumentException("Expected a boolean MCP argument, got '$this'.")
    }
    ParamType.List -> parseNativeJsonArgument(this) as? List<*>
        ?: throw IllegalArgumentException("Expected a JSON array MCP argument.")
    ParamType.Map, ParamType.Object -> parseNativeJsonArgument(this) as? Map<*, *>
        ?: throw IllegalArgumentException("Expected a JSON object MCP argument.")
    ParamType.Any -> runCatching { parseNativeJsonArgument(this) }.getOrElse { this }
}

private fun parseNativeJsonArgument(value: String): Any? =
    Json.parseToJsonElement(value).toNativeMcpValue()

private fun JsonElement.toNativeMcpValue(): Any? = when(this)
{
    JsonNull -> null
    is kotlinx.serialization.json.JsonObject -> mapValues { (_, value) -> value.toNativeMcpValue() }
    is kotlinx.serialization.json.JsonArray -> map { it.toNativeMcpValue() }
    is JsonPrimitive -> if(isString)
    {
        content
    }
    else
    {
        booleanOrNull ?: longOrNull ?: doubleOrNull ?: content
    }
}
