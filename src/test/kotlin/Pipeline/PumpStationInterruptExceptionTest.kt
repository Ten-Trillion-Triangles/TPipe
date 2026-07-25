package com.TTT.Pipeline

import com.TTT.Pipe.MultimodalContent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class PumpStationInterruptExceptionTest
{
    @Test
    fun `exception carries content and rewind snapshot`() {
        val content = MultimodalContent(text = "halt and inject this")
        val snapshot = PumpStationInterruptSnapshot(
            turnIndex = 4,
            latestContent = MultimodalContent(text = "before-turn state"),
            lastPathResult = null,
            selectedPathName = "worker-a",
            originalInput = MultimodalContent(text = "user's original ask"),
            turnHistory = mutableListOf()
        )
        val ex = PumpStationInterruptException(content, snapshot)
        assertSame(content, ex.content)
        assertSame(snapshot, ex.snapshot)
        assertEquals(4, ex.snapshot.turnIndex)
    }
}
