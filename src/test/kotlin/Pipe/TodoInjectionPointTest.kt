package com.TTT.Pipe

import com.TTT.Context.ContextBank
import com.TTT.Context.StorageMode
import com.TTT.Context.TodoList
import com.TTT.Context.TodoListTask
import com.TTT.Enums.TodoInjectionPoint
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
}
