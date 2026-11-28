package Context

import com.TTT.Context.Persistence.LocalContextPersistence
import com.TTT.Context.Persistence.SavedPageMutationResult
import com.TTT.Context.Persistence.SavedPageReadResult
import com.TTT.Context.TodoList
import com.TTT.Context.TodoListTask
import com.TTT.Enums.TodoInjectionPoint
import com.TTT.Pipe.Pipe
import com.TTT.Pipeline.Pipeline
import com.TTT.Pipeline.PumpStation
import com.TTT.Util.deserialize
import com.TTT.Util.serialize
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class TodoPersistenceAndPumpStationTest
{
    private class TestPipe : Pipe()
    {
        override suspend fun generateText(promptInjector: String): String = ""
        override fun truncateModuleContext(): Pipe = this
        fun promptText(): String = systemPrompt
    }

    @Test
    fun `TodoListTask deserializes legacy JSON without id or status and preserves new fields`()
    {
        val legacyJson = """{"taskNumber":1,"task":"Legacy task","completionRequirements":"Check legacy","isComplete":true}"""
        val legacyDecoded = deserialize<TodoListTask>(legacyJson, useRepair = false)
        assertNotNull(legacyDecoded)
        assertEquals("", legacyDecoded.id)
        assertEquals("", legacyDecoded.status)
        assertEquals("Completed", legacyDecoded.effectiveStatus())

        val richTask = TodoListTask(
            taskNumber = 2,
            task = "Rich task",
            completionRequirements = "Verify",
            isComplete = false,
            id = "run-1-task-2",
            status = "InProgress"
        )
        val roundTripped = deserialize<TodoListTask>(serialize(richTask), useRepair = false)
        assertNotNull(roundTripped)
        assertEquals("run-1-task-2", roundTripped.id)
        assertEquals("InProgress", roundTripped.status)
        assertEquals("InProgress", roundTripped.effectiveStatus())
    }

    @Test
    fun `LocalContextPersistence with todo extension isolates todo files from bank files`() = runBlocking {
        val tempDir = Files.createTempDirectory("tpipe-todo-persistence-test")
        try
        {
            Files.writeString(tempDir.resolve("ignored.bank"), """{"contextElements":[]}""")
            val todoFile = tempDir.resolve("run-123.todo")
            val initial = TodoList().apply {
                tasks.tasks += TodoListTask(
                    taskNumber = 1,
                    task = "First task",
                    completionRequirements = "Details",
                    isComplete = false,
                    id = "run-123-task-1",
                    status = "Pending"
                )
            }
            Files.writeString(todoFile, serialize(initial))

            val persistence = LocalContextPersistence(tempDir, fileExtension = ".todo")
            val pages = persistence.listSavedPages()
            assertEquals(listOf("run-123.todo"), pages.map { it.relativePath })

            val readResult = assertIs<SavedPageReadResult.Success>(persistence.readSavedPage("run-123.todo"))
            assertTrue(readResult.document.rawJson.contains("First task"))

            val updatedJson = serialize(initial.copy().apply {
                tasks.tasks[0].isComplete = true
                tasks.tasks[0].status = "Completed"
            })
            val replaceResult = assertIs<SavedPageMutationResult.Success>(
                persistence.replaceSavedPage("run-123.todo", readResult.document.revision, updatedJson)
            )
            assertNotNull(replaceResult.document)

            val deleteResult = assertIs<SavedPageMutationResult.Success>(
                persistence.deleteSavedPage("run-123.todo", replaceResult.document!!.revision)
            )
            assertTrue(persistence.listSavedPages().isEmpty())
            assertTrue(Files.exists(tempDir.resolve("ignored.bank")))
        }
        finally
        {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `PumpStation propagates todo configuration to dispatch pipes without mutating system prompt`()
    {
        val dispatchPipe = TestPipe().apply { setSystemPrompt("Stable dispatch system prompt.") }
        val dispatchPipeline = Pipeline().add(dispatchPipe)
        val station = PumpStation()
            .setTodoInjectionPoint(TodoInjectionPoint.AfterUserPromptWithConverse)
            .setTodoListInstructions("Follow these steps.")
            .setTodoListPageKey("apex-todo-v1-run-abc")
            .setDispatchAgent(dispatchPipeline)

        assertEquals("apex-todo-v1-run-abc", dispatchPipe.todoPageKey)
        assertTrue(dispatchPipe.injectTodoList)
        assertEquals(TodoInjectionPoint.AfterUserPromptWithConverse, dispatchPipe.todoInjectionPoint)
        assertEquals("Follow these steps.", dispatchPipe.todoListInstructions)
        assertEquals("Stable dispatch system prompt.", dispatchPipe.promptText())
        assertNotNull(station.getDispatchAgent())
    }

    @Test
    fun `PumpStation propagates todo configuration to judge goal and summary pipes and survives hot-swap`()
    {
        val dispatchPipe = TestPipe().apply { setSystemPrompt("Dispatch sys.") }
        val judgePipe = TestPipe().apply { setSystemPrompt("Judge sys.") }
        val goalPipe = TestPipe().apply { setSystemPrompt("Goal sys.") }
        val summaryPipe = TestPipe().apply { setSystemPrompt("Summary sys.") }

        val station = PumpStation()
            .setTodoInjectionPoint(TodoInjectionPoint.AfterUserPrompt)
            .setTodoListInstructions("Dispatch todo instructions.")
            .setJudgeTodoListInstructions("Judge todo instructions.")
            .setGoalTodoListInstructions("Goal todo instructions.")
            .setSummaryTodoListInstructions("Summary todo instructions.")
            .setTodoListPageKey("apex-todo-v1-run-multi-role")
            .setDispatchAgent(Pipeline().add(dispatchPipe))
            .setJudgeAgent(Pipeline().add(judgePipe))
            .setGoalAgent(Pipeline().add(goalPipe))
            .setSummaryAgent(Pipeline().add(summaryPipe))

        assertEquals("apex-todo-v1-run-multi-role", dispatchPipe.todoPageKey)
        assertEquals("Dispatch todo instructions.", dispatchPipe.todoListInstructions)
        assertEquals("apex-todo-v1-run-multi-role", judgePipe.todoPageKey)
        assertEquals("Judge todo instructions.", judgePipe.todoListInstructions)
        assertEquals("apex-todo-v1-run-multi-role", goalPipe.todoPageKey)
        assertEquals("Goal todo instructions.", goalPipe.todoListInstructions)
        assertEquals("apex-todo-v1-run-multi-role", summaryPipe.todoPageKey)
        assertEquals("Summary todo instructions.", summaryPipe.todoListInstructions)
        assertEquals(TodoInjectionPoint.AfterUserPrompt, judgePipe.todoInjectionPoint)
        assertEquals("Judge sys.", judgePipe.promptText())
        assertEquals("Goal sys.", goalPipe.promptText())
        assertEquals("Summary sys.", summaryPipe.promptText())

        // Hot-swap pipelines and verify todo settings remain intact.
        val newJudgePipe = TestPipe().apply { setSystemPrompt("New judge sys.") }
        val newGoalPipe = TestPipe().apply { setSystemPrompt("New goal sys.") }
        val newSummaryPipe = TestPipe().apply { setSystemPrompt("New summary sys.") }
        station.setJudgeAgent(Pipeline().add(newJudgePipe))
        station.setGoalAgent(Pipeline().add(newGoalPipe))
        station.setSummaryAgent(Pipeline().add(newSummaryPipe))

        assertEquals("apex-todo-v1-run-multi-role", newJudgePipe.todoPageKey)
        assertEquals("Judge todo instructions.", newJudgePipe.todoListInstructions)
        assertEquals("apex-todo-v1-run-multi-role", newGoalPipe.todoPageKey)
        assertEquals("Goal todo instructions.", newGoalPipe.todoListInstructions)
        assertEquals("apex-todo-v1-run-multi-role", newSummaryPipe.todoPageKey)
        assertEquals("Summary todo instructions.", newSummaryPipe.todoListInstructions)
    }
}
