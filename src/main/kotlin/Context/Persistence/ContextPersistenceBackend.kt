package com.TTT.Context.Persistence

import com.TTT.Context.ContextWindow
import com.TTT.Context.TodoList

/**
 * Provider-neutral persistence contract for TPipe's exact ContextBank values.
 *
 * Implementations must use `null` for a missing value and `false` when a delete
 * targets a missing key. Transport, authentication, serialization, and server
 * failures are surfaced as exceptions so callers cannot mistake an outage for
 * an empty context window.
 */
interface ContextPersistenceBackend {
    /** Stable identifier used in diagnostics and configuration. */
    val id: String

    /** Retrieve an exact [ContextWindow], or `null` when [key] is absent. */
    suspend fun getContextWindow(key: String): ContextWindow?

    /** Persist an exact [ContextWindow] under [key]. */
    suspend fun putContextWindow(key: String, window: ContextWindow)

    /** Delete [key], returning whether a value existed. */
    suspend fun deleteContextWindow(key: String): Boolean

    /** List exact ContextBank keys known to this backend. */
    suspend fun listContextWindowKeys(): List<String>

    /** Retrieve an exact [TodoList], or `null` when [key] is absent. */
    suspend fun getTodoList(key: String): TodoList?

    /** Persist an exact [TodoList] under [key]. */
    suspend fun putTodoList(key: String, todoList: TodoList)

    /** Delete a todo list, returning whether a value existed. */
    suspend fun deleteTodoList(key: String): Boolean

    /** List exact todo-list keys known to this backend. */
    suspend fun listTodoListKeys(): List<String>
}
