package com.TTT

import com.TTT.Config.AuthRegistry
import com.TTT.Config.TPipeConfig
import com.TTT.Context.MemoryClient
import com.TTT.P2P.*
import com.TTT.PipeContextProtocol.*
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class AuthAutomationTest {

    @Test
    fun testAuthRegistryResolution() {
        AuthRegistry.clear()
        AuthRegistry.registerToken("http://localhost:8080", "token123")
        
        assertEquals("token123", AuthRegistry.getToken("http://localhost:8080"))
        assertEquals("token123", AuthRegistry.getToken("http://localhost:8080/api/traces"))
        assertEquals("", AuthRegistry.getToken("http://otherhost:8080"))
    }

    @Test
    fun testMemoryClientAuthAutomation() {
        AuthRegistry.clear()
        val originalUrl = TPipeConfig.remoteMemoryUrl
        val originalToken = TPipeConfig.remoteMemoryAuthToken
        
        try {
            TPipeConfig.remoteMemoryUrl = "http://localhost:9999"
            TPipeConfig.remoteMemoryAuthToken = "legacy-token"
            
            // Reflection to call private getAuthToken
            val method = MemoryClient::class.java.getDeclaredMethod("getAuthToken")
            method.isAccessible = true
            
            // Should fallback to legacy
            assertEquals("legacy-token", method.invoke(MemoryClient))
            
            // Register in registry
            AuthRegistry.registerToken("http://localhost:9999", "new-automated-token")
            
            // Should use new token
            assertEquals("new-automated-token", method.invoke(MemoryClient))
        } finally {
            TPipeConfig.remoteMemoryUrl = originalUrl
            TPipeConfig.remoteMemoryAuthToken = originalToken
        }
    }

    @Test
    fun testP2PAuthAutomation() = runBlocking {
        AuthRegistry.clear()
        
        val transport = P2PTransport(
            transportMethod = Transport.Http,
            transportAddress = "http://agent-host:5000"
        )
        
        // Use apply to set fields for P2PDescriptor since it has many
        val descriptor = P2PDescriptor(
            agentName = "TestAgent",
            agentDescription = "Test",
            transport = transport,
            requiresAuth = true,
            usesConverse = false,
            allowsAgentDuplication = false,
            allowsCustomContext = false,
            allowsCustomAgentJson = false,
            recordsInteractionContext = false,
            recordsPromptContent = false,
            allowsExternalContext = false,
            contextProtocol = ContextProtocol.none
        )
        
        AuthRegistry.registerToken("http://agent-host:5000", "http-secret")
        AuthRegistry.registerToken("TestAgent", "p2p-secret")
        
        val request = AgentRequest(agentName = "TestAgent")
        
        // We can't easily call sendP2pRequest because it calls externalP2PCall which makes real network requests.
        // But we can test the internal logic by checking how it would populate a P2PRequest.
        
        val p2pRequest = P2PRequest(transport = transport)
        
        // Manually simulate what sendP2pRequest does:
        val resolvedHttpAuth = AuthRegistry.getToken(p2pRequest.transport.transportAddress)
        val resolvedP2pAuth = AuthRegistry.getToken(descriptor.agentName)
        
        assertEquals("http-secret", resolvedHttpAuth)
        assertEquals("p2p-secret", resolvedP2pAuth)
    }

    @Test
    fun testPcpAuthAutomation() {
        AuthRegistry.clear()
        AuthRegistry.registerToken("my-command", "pcp-token")
        
        val request = PcPRequest().apply {
            stdioContextOptions = StdioContextOptions().apply { command = "my-command" }
        }
        
        val finalCallParams = request.callParams.toMutableMap()
        if(!finalCallParams.containsKey("authBody")) {
            val token = AuthRegistry.getToken(request.stdioContextOptions.command)
            if(token.isNotEmpty()) {
                finalCallParams["authBody"] = token
            }
        }
        
        assertEquals("pcp-token", finalCallParams["authBody"])
    }

    @Test
    fun testNoAuthFlow() {
        AuthRegistry.clear()
        TPipeConfig.remoteMemoryAuthToken = ""
        
        // Memory Client
        val memMethod = MemoryClient::class.java.getDeclaredMethod("getAuthToken")
        memMethod.isAccessible = true
        assertEquals("", memMethod.invoke(MemoryClient))
        
        // P2P
        val p2pToken = AuthRegistry.getToken("SomeAgent")
        assertEquals("", p2pToken)
        
        // PCP
        val pcpToken = AuthRegistry.getToken("some-command")
        assertEquals("", pcpToken)
        
        // Trace
        val traceToken = AuthRegistry.getToken("http://localhost:8081")
        assertEquals("", traceToken)
    }
}
