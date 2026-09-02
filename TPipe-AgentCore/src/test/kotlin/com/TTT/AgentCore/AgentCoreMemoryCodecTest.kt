package com.TTT.AgentCore

import com.TTT.AgentCore.memory.AgentCoreMemoryCodec
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AgentCoreMemoryCodecTest {
    @Test
    fun preservesExactSerializedTextAcrossCompressionAndChunking() {
        val serialized = "value-" + "x".repeat(40_000)
        val encoded = AgentCoreMemoryCodec.encode(serialized)

        assertEquals(serialized, AgentCoreMemoryCodec.decode(encoded))
        assertTrue(AgentCoreMemoryCodec.chunks(encoded).all { it.length <= AgentCoreMemoryCodec.MAX_PAYLOAD_CHARS })
        assertEquals(
            AgentCoreMemoryCodec.checksum(encoded),
            AgentCoreMemoryCodec.checksum(AgentCoreMemoryCodec.chunks(encoded).joinToString(""))
        )
    }
}
