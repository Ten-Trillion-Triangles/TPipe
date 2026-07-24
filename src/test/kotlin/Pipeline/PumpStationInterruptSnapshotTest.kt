package com.TTT.Pipeline

import com.TTT.Context.ConverseData
import com.TTT.Context.ConverseRole
import com.TTT.Pipe.MultimodalContent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PumpStationInterruptSnapshotTest
{
    @Test
    fun `snapshot stores turnHistory copy and taskState fields`() {
        val originalHistory = mutableListOf<ConverseData>(
            ConverseData(role = ConverseRole.user, content = MultimodalContent(text = "ask")),
            ConverseData(role = ConverseRole.assistant, content = MultimodalContent(text = "answer"))
        )
        val snapshot = PumpStationInterruptSnapshot(
            turnIndex = 3,
            latestContent = MultimodalContent(text = "latest at BeforeJudge"),
            lastPathResult = MultimodalContent(text = "previous turn's path output"),
            selectedPathName = "worker-b",
            originalInput = MultimodalContent(text = "the original ask"),
            turnHistory = originalHistory
        )

        // Mutating the original list must NOT affect the snapshot's stored copy.
        originalHistory.clear()
        assertEquals(2, snapshot.turnHistoryCopy.size)
        assertEquals("ask", snapshot.turnHistoryCopy[0].content.text)
        assertEquals(3, snapshot.turnIndex)
    }
}
