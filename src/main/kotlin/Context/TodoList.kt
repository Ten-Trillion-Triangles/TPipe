package com.TTT.Context

import com.TTT.Pipe.MultimodalContent
import com.TTT.Util.serialize

/**
 * Single element of a todo list. Defines the task number for order of completion, what the task is, it's requirements
 * and task completion status.
 */
@kotlinx.serialization.Serializable
data class TodoListTask(
    var taskNumber: Int = 0,
    var task: String = "",
    var completionRequirements: String = "",
    var isComplete: Boolean = false,
    var id: String = "",
    var status: String = ""
)
{
    /**
     * Returns the normalized lifecycle status (`"Completed"`, `"InProgress"`, or `"Pending"`).
     *
     * @return Normalized status string compatible with both legacy [isComplete] and rich [status] fields.
     */
    fun effectiveStatus(): String = when
    {
        isComplete || status.equals("Completed", ignoreCase = true) -> "Completed"
        status.equals("InProgress", ignoreCase = true) || status.equals("in_progress", ignoreCase = true) -> "InProgress"
        else -> "Pending"
    }
}

@kotlinx.serialization.Serializable
data class TodoTaskArray(
    var tasks: MutableList<TodoListTask> = mutableListOf()
)

/**
 * List of TodoListTask elements with a converse history that can be used to show exactly what work on the given task
 * has been undertaken overtime.
 */
@kotlinx.serialization.Serializable
data class TodoList(
    var tasks: TodoTaskArray = TodoTaskArray(),
    var workHistory: ConverseHistory = ConverseHistory(),
    var version: Long = 0
)
{
    override fun toString(): String {
        return serialize(tasks)
    }

    fun isEmpty() : Boolean
    {
        return tasks.tasks.isEmpty()
    }


    fun find(taskNumber: Int) : TodoListTask?
    {
        for(task in tasks.tasks)
        {
            if(task.taskNumber == taskNumber) return task
        }

        return null
    }

    /**
     * Set the todo task number that the llm should focus on if given a todo list object. This is read when
     * [com.TTT.Pipe.applySystemPrompt] is called on the pipe class.
     */
    fun setTodoTaskNumber(taskNumber: Int, content: MultimodalContent)
    {
        content.setTodoTaskNumber(taskNumber)
    }
}
