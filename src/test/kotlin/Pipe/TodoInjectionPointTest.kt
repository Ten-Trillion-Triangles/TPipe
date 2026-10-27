package com.TTT.Pipe

import com.TTT.Context.ContextBank
import com.TTT.Context.ContextWindow
import com.TTT.Context.ConverseHistory
import com.TTT.Context.ConverseRole
import com.TTT.Context.MiniBank
import com.TTT.Context.StorageMode
import com.TTT.Context.TodoList
import com.TTT.Context.TodoListTask
import com.TTT.Enums.TodoInjectionPoint
import com.TTT.Util.deserialize
import com.TTT.Util.serializeConverseHistory
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Placement-target tests for [Pipe.setTodoInjectionPoint].
 *
 * Each test seeds a unique ContextBank page so the global singleton is never shared
 * between assertions. No live LLM calls: injection is pure string/JSON manipulation.
 */
class TodoInjectionPointTest
{
    private class TestPipe : Pipe()
    {
        override suspend fun generateText(promptInjector: String): String
        {
            return ""
        }

        override fun truncateModuleContext(): Pipe
        {
            return this
        }

        fun getPrompt(): String = systemPrompt

        fun contextWindowForTest(): ContextWindow = contextWindow

        fun miniContextBankForTest(): MiniBank = miniContextBank
    }

    /**
     * Seed a two-task todo page in ContextBank memory storage.
     *
     * @param key Unique page key for this test.
     */
    private fun seedTodoPage(key: String)
    {
        ContextBank.emplaceTodoList(
            key,
            TodoList().apply {
                tasks.tasks.add(TodoListTask(taskNumber = 1, task = "Build the first thing"))
                tasks.tasks.add(TodoListTask(taskNumber = 2, task = "Build the second thing"))
            },
            mode = StorageMode.MEMORY_ONLY
        )
    }

    @Test
    fun defaultTargetIsSystemPrompt()
    {
        val pipe = TestPipe()
        assertEquals(TodoInjectionPoint.SystemPrompt, pipe.todoInjectionPoint)
    }

    @Test
    fun systemPromptTargetComposesIntoPrompt()
    {
        val key = "todo-placement-system-target"
        seedTodoPage(key)
        val pipe = TestPipe().apply {
            setSystemPrompt("Base system prompt.")
            setTodoListPageKey(key)
            applySystemPrompt()
        }
        val prompt = pipe.getPrompt()
        assertTrue(prompt.contains("Build the first thing"))
        assertTrue(prompt.contains("todo list"))
    }

    @Test
    fun nonSystemTargetSkipsSystemComposition()
    {
        val key = "todo-placement-skip-system"
        seedTodoPage(key)
        val pipe = TestPipe().apply {
            setSystemPrompt("Base system prompt.")
            setTodoListPageKey(key)
            setTodoInjectionPoint(TodoInjectionPoint.AfterUserPrompt)
            applySystemPrompt()
        }
        val prompt = pipe.getPrompt()
        assertFalse(prompt.contains("Build the first thing"))
        assertFalse(prompt.contains("todo list"))
    }

    @Test
    fun beforeUserPromptTargetPrependsPayloadToUserContent() = runBlocking {
        val key = "todo-placement-before-user"
        seedTodoPage(key)
        val pipe = TestPipe().apply {
            setTodoListPageKey(key)
            setTodoInjectionPoint(TodoInjectionPoint.BeforeUserPrompt)
            applySystemPrompt()
        }
        val content = MultimodalContent(text = "The user ask.")
        pipe.injectTPipeTodo(content)
        assertTrue(content.text.contains("Build the first thing"))
        assertTrue(content.text.indexOf("Build the first thing") < content.text.indexOf("The user ask."))
        assertFalse(pipe.getPrompt().contains("Build the first thing"))
    }

    @Test
    fun afterUserPromptTargetAppendsPayloadToUserContent() = runBlocking {
        val key = "todo-placement-after-user"
        seedTodoPage(key)
        val pipe = TestPipe().apply {
            setTodoListPageKey(key)
            setTodoInjectionPoint(TodoInjectionPoint.AfterUserPrompt)
            applySystemPrompt()
        }
        val content = MultimodalContent(text = "The user ask.")
        pipe.injectTPipeTodo(content)
        assertTrue(content.text.contains("Build the first thing"))
        assertTrue(content.text.indexOf("The user ask.") < content.text.indexOf("Build the first thing"))
        assertFalse(pipe.getPrompt().contains("Build the first thing"))
    }

    @Test
    fun beforeConverseTargetInsertsDiscreteEntryAtTopOfHistory() = runBlocking {
        val key = "todo-placement-before-converse"
        seedTodoPage(key)
        val pipe = TestPipe().apply {
            setTodoListPageKey(key)
            setTodoInjectionPoint(TodoInjectionPoint.BeforeUserPromptWithConverse)
            applySystemPrompt()
        }
        val history = ConverseHistory().apply {
            add(ConverseRole.user, MultimodalContent(text = "The user ask."))
        }
        val content = MultimodalContent(text = serializeConverseHistory(history))
        pipe.injectTPipeTodo(content)
        val updated = deserialize<ConverseHistory>(content.text)!!
        assertEquals(2, updated.history.size)
        assertTrue(updated.history[0].content.text.contains("Build the first thing"))
        assertFalse(pipe.getPrompt().contains("Build the first thing"))
    }

    @Test
    fun afterConverseTargetAppendsDiscreteEntryAtBottomOfHistory() = runBlocking {
        val key = "todo-placement-after-converse"
        seedTodoPage(key)
        val pipe = TestPipe().apply {
            setTodoListPageKey(key)
            setTodoInjectionPoint(TodoInjectionPoint.AfterUserPromptWithConverse)
            applySystemPrompt()
        }
        val history = ConverseHistory().apply {
            add(ConverseRole.user, MultimodalContent(text = "The user ask."))
        }
        val content = MultimodalContent(text = serializeConverseHistory(history))
        pipe.injectTPipeTodo(content)
        val updated = deserialize<ConverseHistory>(content.text)!!
        assertEquals(2, updated.history.size)
        assertTrue(updated.history[1].content.text.contains("Build the first thing"))
        assertFalse(updated.history[0].content.text.contains("Build the first thing"))
    }

    @Test
    fun converseTargetFallsBackToTextPlacementOnPlainTextContent() = runBlocking {
        val key = "todo-placement-converse-fallback"
        seedTodoPage(key)
        val pipe = TestPipe().apply {
            setTodoListPageKey(key)
            setTodoInjectionPoint(TodoInjectionPoint.AfterUserPromptWithConverse)
            applySystemPrompt()
        }
        val content = MultimodalContent(text = "Plain text, no history.")
        pipe.injectTPipeTodo(content)
        assertTrue(content.text.contains("Plain text, no history."))
        assertTrue(content.text.contains("Build the first thing"))
        assertTrue(content.text.indexOf("Plain text, no history.") < content.text.indexOf("Build the first thing"))
    }

    @Test
    fun asContextTargetMergesIntoPopulatedContextWindow() = runBlocking {
        val key = "todo-placement-as-context"
        seedTodoPage(key)
        val pipe = TestPipe().apply {
            setTodoListPageKey(key)
            setTodoInjectionPoint(TodoInjectionPoint.AsContext)
            applySystemPrompt()
        }
        pipe.contextWindowForTest().addLoreBookEntry("seed", "existing seed entry")
        val content = MultimodalContent(text = "The user ask.")
        pipe.injectTPipeTodo(content)
        val entry = pipe.contextWindowForTest().loreBookKeys["todoList"]
        assertTrue(entry != null)
        assertTrue(entry!!.value.contains("Build the first thing"))
        assertFalse(content.text.contains("Build the first thing"))
    }

    @Test
    fun asContextTargetMergesIntoPopulatedMiniContextBank() = runBlocking {
        val key = "todo-placement-as-context-mini"
        seedTodoPage(key)
        val pipe = TestPipe().apply {
            setTodoListPageKey(key)
            setTodoInjectionPoint(TodoInjectionPoint.AsContext)
            applySystemPrompt()
        }
        pipe.miniContextBankForTest().contextMap["seed-page"] = ContextWindow()
        val content = MultimodalContent(text = "The user ask.")
        pipe.injectTPipeTodo(content)
        val page = pipe.miniContextBankForTest().contextMap["todoList"]
        assertTrue(page != null)
        val entry = page!!.loreBookKeys["todoList"]
        assertTrue(entry != null)
        assertTrue(entry!!.value.contains("Build the first thing"))
        assertFalse(content.text.contains("Build the first thing"))
    }
}
