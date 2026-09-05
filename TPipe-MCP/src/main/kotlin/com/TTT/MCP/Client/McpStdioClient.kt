package com.TTT.MCP.Client

import io.modelcontextprotocol.kotlin.sdk.ExperimentalMcpApi
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.ClientOptions
import io.modelcontextprotocol.kotlin.sdk.client.StdioClientTransport
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ListToolsRequest
import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import io.modelcontextprotocol.kotlin.sdk.types.PaginatedRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Configuration for a local MCP server launched through direct stdio pipes. */
data class McpStdioClientConfig(
    val command: List<String>,
    val workingDirectory: File? = null,
    val environment: Map<String, String> = emptyMap(),
    val clientName: String = "TPipe-MCP-Stdio-Client",
    val clientVersion: String = "1.0.0",
    val clientOptions: ClientOptions = ClientOptions(),
    val startupTimeoutMillis: Long = 10_000L,
    val shutdownTimeoutMillis: Long = 2_000L
)
{
    init
    {
        require(command.isNotEmpty()) { "MCP stdio command must not be empty." }
        require(command.all { it.isNotBlank() }) { "MCP stdio command arguments must not be blank." }
        require(startupTimeoutMillis > 0) { "MCP stdio startup timeout must be positive." }
        require(shutdownTimeoutMillis > 0) { "MCP stdio shutdown timeout must be positive." }
    }
}

/** Launches a local MCP process without shell interpretation. */
fun interface McpStdioProcessLauncher
{
    /** Starts the supplied direct-argv process builder. */
    fun start(builder: ProcessBuilder): Process
}

/** MCP client backed by one bounded, directly-launched local stdio process. */
@OptIn(ExperimentalMcpApi::class)
class McpStdioClient(
    private val config: McpStdioClientConfig,
    private val processLauncher: McpStdioProcessLauncher = McpStdioProcessLauncher { builder -> builder.start() }
) : AutoCloseable
{
    private val connectionMutex = Mutex()
    private val client = Client(
        Implementation(name = config.clientName, version = config.clientVersion),
        config.clientOptions
    )
    @Volatile
    private var connected = false
    private var process: Process? = null
    private var transport: StdioClientTransport? = null

    /** Connects to the local process once and retains its MCP session. */
    suspend fun connect()
    {
        connectionMutex.withLock {
            if(connected) return
            val builder = ProcessBuilder(config.command)
            config.workingDirectory?.let { builder.directory(it) }
            builder.environment().putAll(config.environment)
            val startedProcess = processLauncher.start(builder)
            process = startedProcess
            val startedTransport = StdioClientTransport(
                input = startedProcess.inputStream.asSource().buffered(),
                output = startedProcess.outputStream.asSink().buffered(),
                error = startedProcess.errorStream.asSource().buffered()
            )
            transport = startedTransport
            try
            {
                withTimeout(config.startupTimeoutMillis) { client.connect(startedTransport) }
                connected = true
            }
            catch(exception: Throwable)
            {
                closeProcess()
                throw exception
            }
        }
    }

    /** Lists every local MCP tool, following protocol pagination. */
    suspend fun listTools(): List<Tool>
    {
        connect()
        val tools = mutableListOf<Tool>()
        var cursor: String? = null
        do
        {
            val response = client.listTools(ListToolsRequest(PaginatedRequestParams(cursor = cursor)))
            tools += response.tools
            cursor = response.nextCursor
        } while(!cursor.isNullOrBlank())
        return tools
    }

    /** Calls one local MCP tool with native MCP argument values. */
    suspend fun callTool(name: String, arguments: Map<String, Any?> = emptyMap()): String
    {
        connect()
        return McpJson.encodeToString(client.callTool(name, arguments))
    }

    /** Closes the MCP session and terminates the owned local process. */
    suspend fun closeSuspend()
    {
        connectionMutex.withLock {
            if(connected)
            {
                client.close()
                connected = false
            }
            closeProcess()
        }
    }

    /** Blocking AutoCloseable bridge for JVM callers. */
    override fun close()
    {
        runBlocking { closeSuspend() }
    }

    private suspend fun closeProcess()
    {
        val ownedProcess = process ?: return
        process = null
        transport = null
        withContext(Dispatchers.IO)
        {
            if(ownedProcess.isAlive) ownedProcess.destroy()
            if(ownedProcess.isAlive && !ownedProcess.waitFor(config.shutdownTimeoutMillis, TimeUnit.MILLISECONDS))
            {
                ownedProcess.destroyForcibly()
                ownedProcess.waitFor(config.shutdownTimeoutMillis, TimeUnit.MILLISECONDS)
            }
        }
    }
}
