package com.TTT.AgentCore

import com.TTT.AgentCore.memory.AgentCoreMemoryJson
import com.TTT.Context.ContextWindow
import com.TTT.Context.TodoList
import com.TTT.Context.TodoListTask
import com.TTT.Util.serialize
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class AgentCoreMemoryJsonTest
{
    /** Core-serialized context windows must decode with Core-compatible leniency. */
    @Test
    fun decodesCoreSerializedContextWindow()
    {
        val contextWindow = ContextWindow().apply {
            contextElements += "exact context"
            version = 12
        }
        val serialized = withCompatibilitySyntax(serialize(contextWindow))

        val decoded = AgentCoreMemoryJson.decode(serialized, ContextWindow.serializer())

        assertEquals(contextWindow.contextElements, decoded.contextElements)
        assertEquals(contextWindow.version, decoded.version)
    }

    /** Core-serialized todo lists must decode with the same compatibility boundary. */
    @Test
    fun decodesCoreSerializedTodoList()
    {
        val todoList = TodoList().apply {
            tasks.tasks += TodoListTask(
                taskNumber = 3,
                task = "exact task",
                completionRequirements = "exact requirement",
                isComplete = true
            )
            version = 9
        }
        val serialized = withCompatibilitySyntax(serialize(todoList))

        val decoded = AgentCoreMemoryJson.decode(serialized, TodoList.serializer())

        assertEquals(todoList.tasks.tasks, decoded.tasks.tasks)
        assertEquals(todoList.version, decoded.version)
    }

    private fun withCompatibilitySyntax(serialized: String): String
    {
        val objectBody = serialized.trim().removeSuffix("}")
        return "$objectBody,\n// compatibility field\n\"unknownField\": \"ignored\",\n}"
    }
}
