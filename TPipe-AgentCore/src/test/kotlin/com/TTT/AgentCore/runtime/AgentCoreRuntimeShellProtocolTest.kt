package com.TTT.AgentCore.runtime

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AgentCoreRuntimeShellProtocolTest
{
    @Test
    fun roundTripsKnownAndFutureChannelFrames()
    {
        val known = AgentCoreShellFrame(AgentCoreShellChannel.Stdout, "hello".toByteArray())
        assertEquals(known, AgentCoreShellFrame.decode(known.encode()))

        val future = AgentCoreShellFrame(AgentCoreShellChannel.Unknown(0x7E), byteArrayOf(1, 2))
        assertEquals(future, AgentCoreShellFrame.decode(future.encode()))
        assertContentEquals(byteArrayOf(0x7E, 1, 2), future.encode())
    }

    @Test
    fun enforcesPayloadLimitAndParsesTerminalStatus()
    {
        assertFailsWith<IllegalArgumentException> {
            AgentCoreShellFrame(AgentCoreShellChannel.Stdin, ByteArray(AgentCoreShellFrame.MAX_PAYLOAD_BYTES + 1))
        }
        assertEquals(
            AgentCoreShellStatus("COMPLETED", exitCode = 7, message = "done"),
            AgentCoreShellStatus.decode("{\"status\":\"COMPLETED\",\"exit_code\":7,\"message\":\"done\"}".toByteArray())
        )
    }

    @Test
    fun validatesReconnectAndShellIdentifiers()
    {
        assertFailsWith<IllegalArgumentException> { AgentCoreShellReconnectConfig(maxAttempts = 11) }
        assertFailsWith<IllegalArgumentException> { validateAgentCoreRuntimeSessionId("bad\nvalue") }
        assertFailsWith<IllegalArgumentException> { validateAgentCoreShellId("bad shell") }
    }
}
