package com.TTT.Context

/**
 * Data class that defines a todo list task that can be assigned by an llm or human. Defines a task, requirements
 * which can be validated by another llm. This is used for task creation or validation.
 */
@kotlinx.serialization.Serializable
data class TodoListTask(
    var stepNumber: Int = 1,
    var task: String = "",
    var completionRequirements: String = ""
)

/**
 * Single entry of an agent todo list. Defines bullet point number, task at hand, and completion status.
 */
@kotlinx.serialization.Serializable
data class AgentTodo(
    var stepNumber: Int = 1,
    var task: String = "",
    var isComplete: Boolean = false
)

/**
 * Defines a todo list an agent can examine and track it's progress on a given task or set of tasks. Validation of tasks
 * should be handled by a human or a separate judge llm.
 */
@kotlinx.serialization.Serializable
data class AgentTodoList(
    var todoList: MutableList<AgentTodo> = mutableListOf()
)
{
    fun findTodo(taskNumber: Int) : AgentTodo?
    {
        for(i in todoList)
        {
            if(i.stepNumber == taskNumber) return i
        }

        return null
    }
}

/**
 * Data class that contains the todolist task, and the history of llm actions to work on that task. Intended to be input
 * for an llm judge to determine if the todo list task has been completed or not.
 */
@kotlinx.serialization.Serializable
data class TodoListHistory(
    var task: TodoListTask = TodoListTask(),
    var agentTaskHistory: ConverseHistory = ConverseHistory()
)

/**
 * Data class that maps a todo list task to a boolean to determine if it's completed or not. Acts as a return output
 * by a judge llm to validate each tasks completion.
 */
@kotlinx.serialization.Serializable
data class TodoValidationList(
    var todo: MutableMap<TodoListTask, Boolean> = mutableMapOf()
)
{
    fun updateAgentTodoList(validationList: TodoValidationList, agentList: AgentTodoList)
    {
        for(it in validationList.todo)
        {
            val task = it.key
            if(it.value)
            {
                val taskNumber = task.stepNumber
                val task: AgentTodo? = agentList.findTodo(taskNumber)

                if(task != null)
                {
                    //Assign by finding the index and then directly overwriting the value of the index's reference.
                    agentList.todoList[agentList.todoList.indexOf(task)].isComplete = true
                }
            }
        }
    }
}


