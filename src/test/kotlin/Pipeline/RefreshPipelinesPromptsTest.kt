package com.TTT.Pipeline

import com.TTT.Pipe.DummyPipe
import com.TTT.Pipe.MultimodalContent
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertTrue

class RefreshPipelinesPromptsTest
{
    @Test
    fun testRefreshAppliesPromptsToJudgePipe()
    {
        val station = PumpStation()
            .setSystemTask("important task")
            .setEntryUserPrompt("do the thing")
        val judgePipe = DummyPipe()
        val judge = Pipeline().apply { add(judgePipe) }
        station.setJudgeAgent(judge)

        runBlocking { station.refreshPipelinesPrompts() }

        // The judge pipe's rawSystemPrompt should now contain the system task
        val prompt = judgePipe.getSystemPromptForTest()
        assertTrue(prompt.contains("important task"), "Judge pipe should have system task in prompt")
    }
}
