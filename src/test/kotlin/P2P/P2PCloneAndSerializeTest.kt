package com.TTT.P2P

import com.TTT.P2P.ContextProtocol
import com.TTT.P2P.P2PDescriptor
import com.TTT.P2P.P2PError
import com.TTT.P2P.P2PRejection
import com.TTT.P2P.P2PRequest
import com.TTT.P2P.P2PResponse
import com.TTT.P2P.P2PTransport
import com.TTT.P2P.SupportedContentTypes
import com.TTT.Pipe.MultimodalContent
import com.TTT.PipeContextProtocol.Transport
import com.TTT.Pipeline.DistributionGrid
import com.TTT.Pipeline.DistributionGridRoutingPolicy
import com.TTT.Pipeline.Pipeline
import com.TTT.Util.cloneInstance
import com.TTT.Util.deserialize
import com.TTT.Util.serialize
import com.TTT.testing.TestCapturingPipe
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

/**
 * Pins the P2P layer's wire-format and isolation contract for the Kotlin 2.3
 * readiness sweep. Covers three independent failure classes: (1) [cloneInstance]
 * preserving configuration across container types while resetting runtime state;
 * (2) [P2PDescriptor] / [P2PRequest] / [P2PResponse] / [P2PRejection] round-trip;
 * (3) the security-sensitive `transportAuthBody` and `authBody` fields
 * surviving serialize/deserialize together.
 */
class P2PCloneAndSerializeTest
{
    @Test
    fun `cloneInstance on a Pipeline preserves pipe configuration and produces a fresh Pipeline instance`() {
        val pipe = TestCapturingPipe().setPipeName("echo")
        val pipeline = Pipeline().apply { add(pipe) }
        val clone = cloneInstance(pipeline)
        assertNotSame(pipeline, clone)
        assertEquals(1, clone.getPipes().size)
        assertEquals("echo", clone.getPipes().first().pipeName)
    }

    @Test
    fun `cloneInstance on a DistributionGrid preserves routing policy configuration`() {
        val grid = DistributionGrid()
        grid.setRoutingPolicy(DistributionGridRoutingPolicy(maxHopCount = 7))
        val clone = cloneInstance(grid)
        assertEquals(7, clone.getRoutingPolicy().maxHopCount)
    }

    @Test
    fun `P2PDescriptor with requiresAuth=true and transportAuthBody round-trips`() {
        val descriptor = P2PDescriptor(
            agentName = "auth-agent", agentDescription = "d",
            transport = P2PTransport(Transport.Http, "https://x", transportAuthBody = "TA"),
            requiresAuth = true, usesConverse = false,
            allowsAgentDuplication = false, allowsCustomContext = false,
            allowsCustomAgentJson = false,
            recordsInteractionContext = false, recordsPromptContent = false,
            allowsExternalContext = false, contextProtocol = ContextProtocol.none
        )
        val json = serialize(descriptor, encodedefault = true)
        val back = deserialize<P2PDescriptor>(json)!!
        assertEquals(true, back.requiresAuth)
        assertEquals("TA", back.transport.transportAuthBody)
    }

    @Test
    fun `P2PDescriptor with requestTemplate authBody round-trips alongside transportAuthBody`() {
        val descriptor = P2PDescriptor(
            agentName = "auth-template-agent", agentDescription = "d",
            transport = P2PTransport(Transport.Http, "https://x", transportAuthBody = "TA"),
            requiresAuth = true, usesConverse = false,
            allowsAgentDuplication = false, allowsCustomContext = false,
            allowsCustomAgentJson = false,
            recordsInteractionContext = false, recordsPromptContent = false,
            allowsExternalContext = false, contextProtocol = ContextProtocol.none,
            requestTemplate = P2PRequest().apply { authBody = "AB" }
        )
        val json = serialize(descriptor, encodedefault = true)
        val back = deserialize<P2PDescriptor>(json)!!
        assertEquals("TA", back.transport.transportAuthBody)
        assertNotNull(back.requestTemplate)
        assertEquals("AB", back.requestTemplate!!.authBody)
    }

    @Test
    fun `P2PRequest with prompt and context round-trips`() {
        val request = P2PRequest().apply {
            prompt.addText("hello")
            authBody = "AB"
        }
        val json = serialize(request, encodedefault = true)
        val back = deserialize<P2PRequest>(json)!!
        assertEquals("hello", back.prompt.text)
        assertEquals("AB", back.authBody)
    }

    @Test
    fun `P2PResponse with MultimodalContent output round-trips`() {
        val response = P2PResponse(output = MultimodalContent("text"))
        val json = serialize(response, encodedefault = true)
        val back = deserialize<P2PResponse>(json)!!
        assertNotNull(back.output)
        assertEquals("text", back.output!!.text)
    }

    @Test
    fun `P2PRejection round-trips with P2PError and reason`() {
        val rejection = P2PRejection(errorType = P2PError.auth, reason = "nope")
        val json = serialize(rejection, encodedefault = true)
        val back = deserialize<P2PRejection>(json)!!
        assertEquals(P2PError.auth, back.errorType)
        assertEquals("nope", back.reason)
    }
}
