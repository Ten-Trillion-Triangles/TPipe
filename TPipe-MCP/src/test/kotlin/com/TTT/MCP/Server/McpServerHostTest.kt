package com.TTT.MCP.Server

import com.TTT.PipeContextProtocol.PcpContext
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import kotlin.test.Test
import kotlin.test.assertNotNull

class McpServerHostTest
{
    private fun createDefaultServerHost(): McpServerHost {
        val pcpContext = PcpContext()
        val capabilities = ServerCapabilities(
            tools = ServerCapabilities.Tools(),
            resources = ServerCapabilities.Resources(),
            prompts = ServerCapabilities.Prompts()
        )
        return McpServerHost(pcpContext, capabilities)
    }

    @Test
    fun testGetServerReturnsUnderlyingServerInstance()
    {
        val serverHost = createDefaultServerHost()

        val server = serverHost.getServer()

        assertNotNull(server)
    }

    @Test
    fun testShutdownClosesServerCleanly()
    {
        val serverHost = createDefaultServerHost()

        serverHost.shutdown()

        assertNotNull(serverHost.getServer())
    }

    @Test
    fun testMultipleShutdownCallsAreIdempotent()
    {
        val serverHost = createDefaultServerHost()

        serverHost.shutdown()
        serverHost.shutdown()
        serverHost.shutdown()
    }

    @Test
    fun testConstructionWithCustomCapabilities()
    {
        val pcpContext = PcpContext()
        val customCapabilities = ServerCapabilities(
            tools = ServerCapabilities.Tools(
                listChanged = true
            ),
            resources = ServerCapabilities.Resources(
                subscribe = true,
                listChanged = true
            ),
            prompts = ServerCapabilities.Prompts(
                listChanged = false
            )
        )

        val serverHost = McpServerHost(pcpContext, customCapabilities)

        assertNotNull(serverHost.getServer())
    }

    @Test
    fun testConstructionWithCustomServerInfo()
    {
        val pcpContext = PcpContext()
        val capabilities = ServerCapabilities(
            tools = ServerCapabilities.Tools(),
            resources = ServerCapabilities.Resources(),
            prompts = ServerCapabilities.Prompts()
        )
        val customServerInfo = Implementation(
            name = "custom-test-server",
            version = "2.0.0"
        )

        val serverHost = McpServerHost(pcpContext, capabilities, customServerInfo)

        assertNotNull(serverHost.getServer())
    }
}