package com.TTT.MCP.Client

import com.TTT.PipeContextProtocol.FunctionInvoker
import com.TTT.PipeContextProtocol.FunctionRegistry
import com.TTT.PipeContextProtocol.PcpContext
import com.TTT.PipeContextProtocol.ParamType
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.http.content.OutgoingContent
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.Test
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

class McpRemoteArgumentConversionTest
{
    @Test
    fun schemaTypesUseCompatiblePcpKotlinTypes()
    {
        assertEquals("kotlin.Int", "integer".toPcpKotlinType(emptyList()))
        assertEquals("kotlin.Double", "number".toPcpKotlinType(emptyList()))
        assertEquals("kotlin.Boolean", "boolean".toPcpKotlinType(emptyList()))
        assertEquals("kotlin.collections.List<kotlin.Any?>", "array".toPcpKotlinType(emptyList()))
        assertEquals("kotlin.collections.Map<kotlin.String, kotlin.Any?>", "object".toPcpKotlinType(emptyList()))
        assertEquals("kotlin.String", "string".toPcpKotlinType(emptyList()))
        assertEquals("kotlin.String", "string".toPcpKotlinType(listOf("one", "two")))
    }

    @Test
    fun convertsPcpStringsToNativeMcpValues()
    {
        assertEquals(7, "7".toMcpArgument(ParamType.Int))
        assertEquals(2.5, "2.5".toMcpArgument(ParamType.Float))
        assertEquals(true, "true".toMcpArgument(ParamType.Bool))
        assertEquals("value", "value".toMcpArgument(ParamType.String))
        assertEquals(
            listOf("one", 2L, false),
            "[\"one\", 2, false]".toMcpArgument(ParamType.List)
        )
        assertEquals(
            mapOf("count" to 2L, "enabled" to true),
            "{\"count\": 2, \"enabled\": true}".toMcpArgument(ParamType.Map)
        )
    }

    @Test
    fun rejectsInvalidTypedValues()
    {
        assertFailsWith<IllegalArgumentException> {
            "not-a-number".toMcpArgument(ParamType.Int)
        }
        assertFailsWith<IllegalArgumentException> {
            "not-json".toMcpArgument(ParamType.Map)
        }
    }

    @Test
    fun concurrentConnectNegotiatesOneMcpSession()
    {
        runBlocking {
            val initializeCalls = AtomicInteger()
            val engine = MockEngine(MockEngineConfig().apply {
                reuseHandlers = true
                addHandler { request ->
                    val requestJson = Json.parseToJsonElement(
                        (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
                    ).jsonObject
                    val id = requestJson["id"] ?: JsonNull
                    when (requestJson["method"]?.jsonPrimitive?.content) {
                        "initialize" -> {
                            initializeCalls.incrementAndGet()
                            respond(
                                buildJsonObject {
                                    put("jsonrpc", "2.0")
                                    put("id", id)
                                    put("result", buildJsonObject {
                                        put("protocolVersion", "2025-06-18")
                                        put("capabilities", buildJsonObject {})
                                        put("serverInfo", buildJsonObject {
                                            put("name", "fake-server")
                                            put("version", "1.0")
                                        })
                                    })
                                }.toString(),
                                HttpStatusCode.OK,
                                headersOf("Content-Type", ContentType.Application.Json.toString())
                            )
                        }
                        else -> respond(
                            content = "",
                            status = HttpStatusCode.OK,
                            headers = headersOf("Content-Type", ContentType.Application.Json.toString())
                        )
                    }
                }
            })
            val httpClient = HttpClient(engine)
            val remote = McpRemoteClient(McpRemoteClientConfig("http://fake-mcp"), httpClient)
            try {
                listOf(async { remote.connect() }, async { remote.connect() }).awaitAll()
                assertEquals(1, initializeCalls.get())
            }
            finally {
                remote.closeSuspend()
                httpClient.close()
            }
        }
    }

    @Test
    fun dynamicAuthenticationRunsForEveryOutgoingRequest()
    {
        runBlocking {
            val authCalls = AtomicInteger()
            val requestCalls = AtomicInteger()
            val engine = MockEngine(MockEngineConfig().apply {
                reuseHandlers = true
                addHandler { request ->
                    requestCalls.incrementAndGet()
                    assertTrue(request.headers["Authorization"]?.startsWith("Bearer token-") == true)
                    val requestJson = Json.parseToJsonElement(
                        (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
                    ).jsonObject
                    val id: JsonElement = requestJson["id"] ?: JsonNull
                    val response = when (requestJson["method"]?.jsonPrimitive?.content) {
                        "initialize" -> buildJsonObject {
                            put("jsonrpc", "2.0")
                            put("id", id)
                            put("result", buildJsonObject {
                                put("protocolVersion", "2025-06-18")
                                put("capabilities", buildJsonObject {})
                                put("serverInfo", buildJsonObject {
                                    put("name", "auth-server")
                                    put("version", "1.0")
                                })
                            })
                        }
                        else -> null
                    }
                    respond(
                        response?.toString().orEmpty(),
                        HttpStatusCode.OK,
                        headersOf("Content-Type", ContentType.Application.Json.toString())
                    )
                }
            })
            val httpClient = HttpClient(engine)
            val remote = McpRemoteClient(
                McpRemoteClientConfig(
                    endpoint = "http://fake-mcp",
                    authProvider = McpRemoteAuthProvider {
                        mapOf("Authorization" to "Bearer token-${authCalls.incrementAndGet()}")
                    }
                ),
                httpClient
            )
            try {
                remote.connect()
            }
            finally {
                remote.closeSuspend()
                httpClient.close()
            }
            assertTrue(requestCalls.get() > 0)
            assertEquals(requestCalls.get(), authCalls.get())
        }
    }

    @Test
    fun configuredTimeoutAppliesToAnInjectedHttpClient()
    {
        runBlocking {
            val engine = MockEngine {
                delay(100)
                respond(
                    content = "",
                    status = HttpStatusCode.OK,
                    headers = headersOf("Content-Type", ContentType.Application.Json.toString())
                )
            }
            val sourceClient = HttpClient(engine) {
                install(HttpTimeout) { requestTimeoutMillis = 1_000 }
            }
            val remote = McpRemoteClient(
                McpRemoteClientConfig(
                    endpoint = "http://fake-mcp",
                    requestTimeoutMillis = 10,
                    connectTimeoutMillis = null,
                    socketTimeoutMillis = null
                ),
                sourceClient
            )
            try
            {
                val failure = assertFailsWith<Exception> { remote.connect() }
                assertTrue(generateSequence<Throwable>(failure) { it.cause }
                    .any { it is HttpRequestTimeoutException })
            }
            finally
            {
                remote.closeSuspend()
                sourceClient.close()
            }
        }
    }

    @Test
    fun boundToolSendsNativeJsonValuesToTheMcpServer()
    {
        runBlocking {
            val toolName = "typed-${UUID.randomUUID()}"
            var receivedArguments: kotlinx.serialization.json.JsonObject? = null
            val engine = MockEngine(MockEngineConfig().apply {
                reuseHandlers = true
                addHandler { request ->
                    val requestJson = Json.parseToJsonElement(
                        (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
                    ).jsonObject
                    val id: JsonElement = requestJson["id"] ?: JsonNull
                    val response = when (requestJson["method"]?.jsonPrimitive?.content) {
                        "initialize" -> buildJsonObject {
                            put("jsonrpc", "2.0")
                            put("id", id)
                            put("result", buildJsonObject {
                                put("protocolVersion", "2025-06-18")
                                put("capabilities", buildJsonObject {
                                    put("tools", buildJsonObject {})
                                })
                                put("serverInfo", buildJsonObject {
                                    put("name", "fake-server")
                                    put("version", "1.0")
                                })
                            })
                        }
                        "notifications/initialized" -> null
                        "tools/list" -> buildJsonObject {
                            put("jsonrpc", "2.0")
                            put("id", id)
                            put("result", buildJsonObject {
                                put("tools", buildJsonArray {
                                    add(buildJsonObject {
                                        put("name", toolName)
                                        put("description", "typed test tool")
                                        put("inputSchema", buildJsonObject {
                                            put("type", "object")
                                            put("properties", buildJsonObject {
                                                put("count", buildJsonObject { put("type", "integer") })
                                                put("enabled", buildJsonObject { put("type", "boolean") })
                                                put("items", buildJsonObject { put("type", "array") })
                                                put("options", buildJsonObject { put("type", "object") })
                                                put("mode", buildJsonObject {
                                                    put("type", "string")
                                                    put("enum", buildJsonArray {
                                                        add(JsonPrimitive("fast"))
                                                        add(JsonPrimitive("safe"))
                                                    })
                                                })
                                                put("union", buildJsonObject {
                                                    put("type", buildJsonArray {
                                                        add(JsonPrimitive("string"))
                                                        add(JsonPrimitive("null"))
                                                    })
                                                })
                                                put("anyValue", true)
                                            })
                                            put("required", buildJsonArray {
                                                add(JsonPrimitive("count"))
                                                add(JsonPrimitive("enabled"))
                                                add(JsonPrimitive("items"))
                                                add(JsonPrimitive("options"))
                                            })
                                        })
                                    })
                                })
                            })
                        }
                        "tools/call" -> {
                            receivedArguments = requestJson["params"]?.jsonObject?.get("arguments")?.jsonObject
                            buildJsonObject {
                                put("jsonrpc", "2.0")
                                put("id", id)
                                put("result", buildJsonObject {
                                    put("content", buildJsonArray {
                                        add(buildJsonObject {
                                            put("type", "text")
                                            put("text", "accepted")
                                        })
                                    })
                                })
                            }
                        }
                        else -> error("Unexpected MCP request: $requestJson")
                    }
                    if (response == null) {
                        respond(
                            content = "",
                            status = HttpStatusCode.OK,
                            headers = headersOf(
                                "Content-Type" to listOf(ContentType.Application.Json.toString())
                            )
                        )
                    } else {
                        respond(
                            content = response.toString(),
                            status = HttpStatusCode.OK,
                            headers = headersOf(
                                "Content-Type" to listOf(ContentType.Application.Json.toString()),
                                "mcp-session-id" to listOf("fake-session")
                            )
                        )
                    }
                }
            })
            val httpClient = HttpClient(engine)
            val remote = McpRemoteClient(
                McpRemoteClientConfig("http://fake-mcp"),
                httpClient
            )
            try {
                remote.bindToolsToPcp(PcpContext())
                val result = FunctionInvoker().invoke(
                    toolName,
                    mapOf(
                        "count" to "7",
                        "enabled" to "true",
                        "items" to "[\"one\", 2, false]",
                        "options" to "{\"limit\": 2, \"verbose\": true}",
                        "mode" to "fast",
                        "union" to "value",
                        "anyValue" to "7"
                    )
                )

                assertTrue(result.success, result.error.orEmpty())
                assertEquals(7L, receivedArguments?.get("count")?.jsonPrimitive?.long)
                assertEquals(true, receivedArguments?.get("enabled")?.jsonPrimitive?.boolean)
                assertEquals("one", receivedArguments?.get("items")?.jsonArray?.get(0)?.jsonPrimitive?.content)
                assertEquals(2L, receivedArguments?.get("options")?.jsonObject?.get("limit")?.jsonPrimitive?.long)
                assertEquals(true, receivedArguments?.get("options")?.jsonObject?.get("verbose")?.jsonPrimitive?.boolean)
                assertEquals("fast", receivedArguments?.get("mode")?.jsonPrimitive?.content)
            } finally {
                remote.closeSuspend()
                httpClient.close()
            }
            assertEquals(null, FunctionRegistry.getSignature(toolName))
        }
    }
}
