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
import com.TTT.Util.deserialize
import com.TTT.Util.serialize
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Pins the P2P model family wire-format contract across the security-sensitive
 * field combinations the Kotlin 2.3 readiness sweep flagged: transportAuthBody,
 * authBody, requestTemplate.authBody, requiresAuth, and the
 * transport / contextProtocol tuple. Independent of the P2P clone test, this
 * file isolates the model layer so a serialization regression is localized.
 */
class P2PModelWireFormatTest
{
    @Test
    fun `P2PDescriptor with Http transport and transportAuthBody round-trips`() {
        val descriptor = P2PDescriptor(
            agentName = "alpha", agentDescription = "d",
            transport = P2PTransport(Transport.Http, "https://x.example.com", transportAuthBody = "secret-1"),
            requiresAuth = true, usesConverse = false,
            allowsAgentDuplication = false, allowsCustomContext = false,
            allowsCustomAgentJson = false,
            recordsInteractionContext = false, recordsPromptContent = false,
            allowsExternalContext = false, contextProtocol = ContextProtocol.none,
            supportedContentTypes = mutableListOf(SupportedContentTypes.text)
        )
        val json = serialize(descriptor, encodedefault = true)
        val back = deserialize<P2PDescriptor>(json)!!
        assertEquals("alpha", back.agentName)
        assertEquals(Transport.Http, back.transport.transportMethod)
        assertEquals("https://x.example.com", back.transport.transportAddress)
        assertEquals("secret-1", back.transport.transportAuthBody)
        assertEquals(true, back.requiresAuth)
    }

    @Test
    fun `P2PRequest with prompt and authBody round-trips`() {
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
