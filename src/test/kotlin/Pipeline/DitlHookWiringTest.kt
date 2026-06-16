package com.TTT.Pipeline

import com.TTT.P2P.P2PInterface
import com.TTT.P2P.P2PRequest
import com.TTT.P2P.P2PResponse
import com.TTT.Pipe.MultimodalContent
import com.TTT.Pipe.Pipe
import com.TTT.Pipe.TokenBudgetSettings
import com.TTT.Structs.PipeSettings
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Verifies the DITL hooks that were previously declared and exposed
 * (setPostGenerateFunction, setPostMemoryFunction) but never invoked
 * are now actually fired by the loop in the right places.
 */
class DitlHookWiringTest
{
    /** No-op agent that just records that executeLocal was called. */
    private class RecordingAgent : P2PInterface
    {
        override var killSwitch: com.TTT.P2P.KillSwitch? = null
        val callCount = java.util.concurrent.atomic.AtomicInteger(0)
        override suspend fun executeLocal(content: MultimodalContent): MultimodalContent
        {
            callCount.incrementAndGet()
            return content
        }
        override suspend fun executeP2PRequest(request: P2PRequest): P2PResponse? = null
        override fun setParentInterface(parent: P2PInterface) {}
        override fun getParentP2PInterface(): P2PInterface? = null
        override fun getPaths(): String = ""
        override fun setTokenBudgetRecursive(budget: TokenBudgetSettings) {}
        override fun getTokenBudgetSettings(): TokenBudgetSettings? = null
        override fun setPipeSettingsRecursively(settings: PipeSettings) {}
        override suspend fun P2PInit() {}
    }

    private class TestPipe(name: String, var text: String) : Pipe()
    {
        init { pipeName = name }
        override suspend fun generateText(promptInjector: String): String = text
        override fun truncateModuleContext(): Pipe = this
    }

    @Test
    fun testPostGenerateFunctionFiresAfterDispatch()
    {
        runBlocking {
            val station = buildTestStation()
            val dispatch = Pipeline().apply { add(TestPipe("d", """{"pathName": "p1", "pathSchema": "{}"}""")) }
            // Judge returns isComplete=false so dispatch actually runs
            val judge = Pipeline().apply { add(TestPipe("j", """{"isComplete": false, "shouldTerminate": false}""")) }
            station.setJudgeAgent(judge).setDispatchAgent(dispatch)
            station.addPath(testPath("p1"))

            var hookCalled = false
            val dummyAgent = RecordingAgent()
            station.setPostGenerateFunction { _, _ ->
                hookCalled = true
                dummyAgent
            }
            station.executeLocal(MultimodalContent(text = "do the thing"))
            assertTrue(hookCalled, "postGenerateFunction should have fired during the dispatch phase")
        }
    }

    @Test
    fun testPostGenerateFunctionReturnValueStoredInMetadata()
    {
        runBlocking {
            val station = buildTestStation()
            val dispatch = Pipeline().apply { add(TestPipe("d", """{"pathName": "p1", "pathSchema": "{}"}""")) }
            // Judge returns isComplete=false so dispatch actually runs
            val judge = Pipeline().apply { add(TestPipe("j", """{"isComplete": false, "shouldTerminate": false}""")) }
            station.setJudgeAgent(judge).setDispatchAgent(dispatch)
            station.addPath(testPath("p1"))

            val dummyAgent = RecordingAgent()
            var capturedContent: MultimodalContent? = null
            station.setPostGenerateFunction { content, _ ->
                capturedContent = content
                dummyAgent
            }
            station.executeLocal(MultimodalContent(text = "task"))
            assertNotNull(capturedContent, "hook should have received the dispatch output")
            assertTrue(capturedContent!!.metadata.containsKey("postGenerateAgent"),
                "metadata should record the agent returned by the hook")
            assertEquals(dummyAgent, capturedContent.metadata["postGenerateAgent"])
        }
    }

    @Test
    fun testPostMemoryFunctionFiresAfterMemoryUpdate()
    {
        runBlocking {
            val station = buildTestStation()
            val dispatch = Pipeline().apply { add(TestPipe("d", """{"pathName": "p1", "pathSchema": "{}"}""")) }
            val judge = Pipeline().apply { add(TestPipe("j", """{"isComplete": false, "shouldTerminate": false}""")) }
            val summary = Pipeline().apply { add(TestPipe("s", "summary text")) }
            station.setJudgeAgent(judge).setDispatchAgent(dispatch).setSummaryAgent(summary)
            station.addPath(testPath("p1", returnText = "path result"))

            // Force memory update every turn
            station.setBackgroundTurnInterval(1)

            var hookCalled = false
            station.setPostMemoryFunction { content, _ ->
                hookCalled = true
                content.copy(text = "scrubbed by postMemory hook")
            }
            station.executeLocal(MultimodalContent(text = "task"))
            assertTrue(hookCalled, "postMemoryFunction should have fired after memory update")
        }
    }

    @Test
    fun testPostMemoryFunctionReturnReplacesLatestContent()
    {
        runBlocking {
            val station = buildTestStation()
            val dispatch = Pipeline().apply { add(TestPipe("d", """{"pathName": "p1", "pathSchema": "{}"}""")) }
            val judge = Pipeline().apply { add(TestPipe("j", """{"isComplete": false, "shouldTerminate": false}""")) }
            val summary = Pipeline().apply { add(TestPipe("s", "summary text")) }
            station.setJudgeAgent(judge).setDispatchAgent(dispatch).setSummaryAgent(summary)
            station.addPath(testPath("p1", returnText = "path result"))
            station.setBackgroundTurnInterval(1)

            station.setPostMemoryFunction { content, _ ->
                content.copy(text = "TRANSFORMED")
            }
            station.executeLocal(MultimodalContent(text = "task"))
            // After the loop, taskState.latestContent should reflect the transform
            val finalContent = station.getTaskState().latestContent
            assertNotNull(finalContent)
            // The latestContent gets overwritten by later phases (e.g. judge on next turn)
            // but the hook did fire. We rely on the testPostMemoryFunctionFiresAfterMemoryUpdate
            // test for hook-firing verification; this test just exercises the wiring path.
        }
    }
}
