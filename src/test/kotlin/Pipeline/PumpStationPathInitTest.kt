package com.TTT.Pipeline

import com.TTT.P2P.KillSwitch
import com.TTT.P2P.P2PInterface
import com.TTT.Pipe.MultimodalContent
import com.TTT.PipeContextProtocol.FunctionRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Mock agent that tracks whether P2PInit was called.
 */
class MockAgentWithInitCallTracker : P2PInterface
{
    var initCalled = false
    override var killSwitch: KillSwitch? = null

    override suspend fun P2PInit()
    {
        initCalled = true
    }

    override suspend fun executeLocal(content: MultimodalContent): MultimodalContent = content
    override fun setParentInterface(parent: P2PInterface) {}
    override fun getParentP2PInterface(): P2PInterface? = null
}

/**
 * Mock agent that tracks nothing (no init call tracking needed).
 */
class MockPathAgent : P2PInterface
{
    override var killSwitch: KillSwitch? = null

    override suspend fun P2PInit()
    {
        // no-op
    }

    override suspend fun executeLocal(content: MultimodalContent): MultimodalContent = content
    override fun setParentInterface(parent: P2PInterface) {}
    override fun getParentP2PInterface(): P2PInterface? = null
}

/**
 * Unit test for PathObject.init() suspend function.
 * Verifies that init() validates configuration, builds PathDescriptionData,
 * calls P2PInit() on internal agent, and returns the PathDescriptionData.
 */
class PumpStationPathInitTest
{

    @Test
    fun testInitReturnsPathDescriptionData()
    {
        runBlocking {
            val path = PathObject()
            path.pathName = "test_path"
            path.pathDescription = "A test path"
            path.pathSchema = "{\"type\": \"object\"}"
            path.setExecutionFunction { content, station, history, summary -> content }

            val result = path.init()

            assertNotNull(result, "init() must return a PathDescriptionData, not null")
            assertEquals("test_path", result.name)
            assertEquals("A test path", result.description)
            assertEquals("{\"type\": \"object\"}", result.inputSchema)
        }
    }

    @Test
    fun testInitCallsP2PInitOnInternalAgent()
    {
        runBlocking {
            val mockAgent = MockAgentWithInitCallTracker()
            val path = PathObject()
            path.pathName = "agent_path"
            path.pathDescription = "A path with an internal agent"
            path.setInternalAgent(mockAgent)

            assertFalse(mockAgent.initCalled, "P2PInit should not be called before init()")
            path.init()
            assertTrue(mockAgent.initCalled, "P2PInit() must be called on the internal agent during init()")
        }
    }

    @Test
    fun testInitDoesNotFailWhenNoInternalAgent()
    {
        runBlocking {
            val path = PathObject()
            path.pathName = "no_agent_path"
            path.pathDescription = "A path with no internal agent"
            path.setExecutionFunction { content, station, history, summary -> content }

            // Must not throw — internal agent is optional
            val result = path.init()
            assertNotNull(result)
            assertFalse(result.hasInternalAgent)
            assertTrue(result.hasExecutionFunction)
        }
    }

    @Test
    fun testInitThrowsWhenNoExecutionMechanism()
    {
        runBlocking {
            val path = PathObject()
            path.pathName = "exec_path"
            // Deliberately not setting any execution mechanism (no executionFunction, no agent, no PCP)

            var threw = false
            try
            {
                path.init()
            }
            catch(e: Exception)
            {
                // require() throws when no execution mechanism is configured
                threw = true
            }
            assertTrue(threw, "init() must throw when no execution mechanism is configured")
        }
    }

    @Test
    fun testInitPopulatesHasExecutionFunctionCorrectly()
    {
        runBlocking {
            val path = PathObject()
            path.pathName = "exec_path"
            path.setExecutionFunction { content, station, history, summary -> content }

            // Bind execution function
            val result = path.init()
            assertTrue(result.hasExecutionFunction, "hasExecutionFunction should be true when function is bound")
        }
    }

    @Test
    fun testInitSetsAgentTypeNameWhenInternalAgentPresent()
    {
        runBlocking {
            val mockAgent = MockPathAgent()
            val path = PathObject()
            path.pathName = "typed_agent_path"
            path.setInternalAgent(mockAgent)

            val result = path.init()
            assertNotNull(result.agentTypeName, "agentTypeName must not be null when internal agent is set")
            assertEquals("MockPathAgent", result.agentTypeName)
        }
    }

    @Test
    fun testInitSetsAgentTypeNameNullWhenNoInternalAgent()
    {
        runBlocking {
            val path = PathObject()
            path.pathName = "no_agent_name_path"
            path.setExecutionFunction { content, station, history, summary -> content }

            val result = path.init()
            assertEquals(null, result.agentTypeName, "agentTypeName must be null when no internal agent")
        }
    }

    @Test
    fun testInitPopulatesPcpSchemaInReturn()
    {
        runBlocking {
            FunctionRegistry.clear()

            val path = PathObject()
            path.pathName = "pcp_path"
            path.pathDescription = "A path with PCP schema"
            path.bindFunction("validateUser", ::validateUserForInitTest)

            val result = path.init()
            assertNotNull(result.pcpSchema, "pcpSchema must not be null when PCP function is bound")
            assertTrue(result.pcpSchema!!.tpipeOptions.isNotEmpty(), "pcpSchema must have tpipeOptions")
        }
    }

    // Test function used in testPopulatesPcpSchemaInReturn
    fun validateUserForInitTest(userId: String, role: String): String
    {
        return "User: $userId, Role: $role"
    }
}