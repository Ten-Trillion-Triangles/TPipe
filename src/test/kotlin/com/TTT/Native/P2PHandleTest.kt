package com.TTT.Native

import com.TTT.P2P.AgentRequest
import com.TTT.P2P.ContextProtocol
import com.TTT.P2P.P2PConcurrencyMode
import com.TTT.P2P.P2PDescriptor
import com.TTT.P2P.P2PInterface
import com.TTT.P2P.P2PRequest
import com.TTT.P2P.P2PRequirements
import com.TTT.P2P.P2PResponse
import com.TTT.P2P.P2PTransport
import com.TTT.P2P.KillSwitch
import com.TTT.Pipe.MultimodalContent
import com.TTT.PipeContextProtocol.Transport
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * TDD tests for P2PHandle — the C ABI wrapper around TPipe's P2PRegistry
 * for distributed agent registration, discovery, and message routing.
 *
 * RED PHASE: These tests define expected behavior. Some may fail until the full
 * ABI implementation is complete. Following TDD: RED first, then GREEN.
 */
class P2PHandleTest {

    /**
     * Build a minimal P2PInterface stub that returns the supplied P2PRequest
     * (as a P2PResponse) when invoked. Used as a placeholder hosted agent.
     */
    private fun stubAgent(agentName: String, responseText: String = "ok"): P2PInterface {
        return object : P2PInterface {
            override var killSwitch: KillSwitch? = null

            override suspend fun executeP2PRequest(request: P2PRequest): P2PResponse {
                val response = P2PResponse()
                response.output = MultimodalContent(responseText)
                return response
            }
        }
    }

    private fun buildDescriptor(agentName: String): P2PDescriptor {
        return P2PDescriptor(
            agentName = agentName,
            agentDescription = "Test agent $agentName",
            transport = P2PTransport(
                transportMethod = Transport.Tpipe,
                transportAddress = agentName
            ),
            requiresAuth = false,
            usesConverse = false,
            allowsAgentDuplication = false,
            allowsCustomContext = false,
            allowsCustomAgentJson = false,
            recordsInteractionContext = false,
            recordsPromptContent = false,
            allowsExternalContext = false,
            contextProtocol = ContextProtocol.pcp
        )
    }

    //==========================================================================
    // Creation and Type Discriminator
    //==========================================================================

    @Test
    fun testCreate() {
        val handle = P2PHandle()
        assertNotNull(handle, "P2PHandle() default constructor should return a non-null handle")
    }

    @Test
    fun testTypeDiscriminator() {
        // P2P discriminator must match HandleTypes.P2P (=10)
        assertEquals(10, HandleTypes.P2P, "HandleTypes.P2P should be 10")
    }

    //==========================================================================
    // registerAgent / registerAgentWithFactory / unregisterAgent
    //==========================================================================

    @Test
    fun testRegisterAgent() {
        val handle = P2PHandle()
        val agent = stubAgent("p2p_test_register_a")
        val descriptor = buildDescriptor("p2p_test_register_a")
        val transport = descriptor.transport
        val requirements = P2PRequirements(allowExternalConnections = true)

        val registered = handle.registerAgent(
            agent = agent,
            transport = transport,
            descriptor = descriptor,
            requirements = requirements,
            concurrencyMode = P2PConcurrencyMode.SHARED
        )

        assertTrue(registered, "registerAgent should return true on success")
        assertTrue(handle.isRegistered(), "handle should report isRegistered=true after register")
        assertEquals("p2p_test_register_a", handle.getAgentId(),
            "agent id should match the registered descriptor's agentName")

        // cleanup
        handle.unregisterAgent(transport)
    }

    @Test
    fun testRegisterAgentWithFactory() {
        val handle = P2PHandle()
        val descriptor = buildDescriptor("p2p_test_register_b")
        val transport = descriptor.transport
        val requirements = P2PRequirements(allowExternalConnections = true)

        val registered = handle.registerAgentWithFactory(
            factory = { stubAgent("p2p_test_register_b", "factory-ok") },
            transport = transport,
            descriptor = descriptor,
            requirements = requirements
        )

        assertTrue(registered, "registerAgentWithFactory should return true on success")
        assertTrue(handle.isRegistered(),
            "handle should report isRegistered=true after factory register")

        // cleanup
        handle.unregisterAgent(transport)
    }

    @Test
    fun testUnregisterAgent() {
        val handle = P2PHandle()
        val descriptor = buildDescriptor("p2p_test_unregister")
        val transport = descriptor.transport
        val requirements = P2PRequirements(allowExternalConnections = true)

        handle.registerAgent(
            agent = stubAgent("p2p_test_unregister"),
            transport = transport,
            descriptor = descriptor,
            requirements = requirements
        )
        assertTrue(handle.isRegistered(), "should be registered before unregister")

        val removed = handle.unregisterAgent(transport)
        assertTrue(removed, "unregisterAgent should return true on success")
        assertFalse(handle.isRegistered(),
            "handle should report isRegistered=false after unregister")
    }

    //==========================================================================
    // getActiveAgents / getPeerCount / listRemoteAgents
    //==========================================================================

    @Test
    fun testGetActiveAgents() {
        val handle = P2PHandle()
        val descriptor = buildDescriptor("p2p_test_active_agents")
        val transport = descriptor.transport
        val requirements = P2PRequirements(allowExternalConnections = true)

        handle.registerAgent(
            agent = stubAgent("p2p_test_active_agents"),
            transport = transport,
            descriptor = descriptor,
            requirements = requirements
        )

        val active = handle.getActiveAgents()
        assertTrue("p2p_test_active_agents" in active,
            "active agents list should contain p2p_test_active_agents, got: $active")

        handle.unregisterAgent(transport)
    }

    @Test
    fun testGetPeerCount() {
        val handle = P2PHandle()
        val peerCount = handle.getPeerCount()
        assertTrue(peerCount >= 0, "getPeerCount should return non-negative value, got: $peerCount")
    }

    @Test
    fun testListRemoteAgents() {
        val handle = P2PHandle()
        val remote = handle.listRemoteAgents()
        assertNotNull(remote, "listRemoteAgents should return non-null list")
    }

    //==========================================================================
    // isRegistered / getAgentId
    //==========================================================================

    @Test
    fun testIsRegistered() {
        val handle = P2PHandle()
        // Default-constructed P2PHandle has no agentId.
        assertFalse(handle.isRegistered(),
            "freshly-constructed P2PHandle should report isRegistered=false")

        val descriptor = buildDescriptor("p2p_test_is_registered")
        val transport = descriptor.transport
        val requirements = P2PRequirements(allowExternalConnections = true)
        handle.registerAgent(
            agent = stubAgent("p2p_test_is_registered"),
            transport = transport,
            descriptor = descriptor,
            requirements = requirements
        )

        assertTrue(handle.isRegistered(),
            "P2PHandle should report isRegistered=true after registering an agent")

        handle.unregisterAgent(transport)
        assertFalse(handle.isRegistered(),
            "P2PHandle should report isRegistered=false after unregistering the agent")
    }

    @Test
    fun testGetAgentId() {
        val handle = P2PHandle()
        // Default-constructed P2PHandle has no agent id.
        assertEquals(null, handle.getAgentId(),
            "freshly-constructed P2PHandle should have null agentId")

        val descriptor = buildDescriptor("p2p_test_get_id")
        val transport = descriptor.transport
        val requirements = P2PRequirements(allowExternalConnections = true)
        handle.registerAgent(
            agent = stubAgent("p2p_test_get_id"),
            transport = transport,
            descriptor = descriptor,
            requirements = requirements
        )

        assertEquals("p2p_test_get_id", handle.getAgentId(),
            "getAgentId should return the registered agent's name")

        handle.unregisterAgent(transport)
    }

    //==========================================================================
    // HandleRegistry Integration
    //==========================================================================

    @Test
    fun testRefCounting() {
        val handle = P2PHandle()
        val handleId = HandleRegistry.allocate(HandleTypes.P2P, handle)
        assertTrue(handleId >= 0, "allocate() should return non-negative handle, got: $handleId")
        assertEquals(1, HandleRegistry.getRefCount(handleId),
            "newly allocated P2PHandle should have refCount=1")

        val addResult = HandleRegistry.addRef(handleId)
        assertEquals(0, addResult, "addRef should return 0 on success")
        assertEquals(2, HandleRegistry.getRefCount(handleId),
            "refCount should be 2 after addRef")

        HandleRegistry.release(handleId)
        assertEquals(1, HandleRegistry.getRefCount(handleId),
            "refCount should be 1 after one release")

        HandleRegistry.release(handleId)
        assertEquals(false, HandleRegistry.isValid(handleId),
            "handle should be invalid after final release")
    }
}
