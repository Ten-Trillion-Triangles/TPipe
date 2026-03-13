package com.TTT.Context

import com.TTT.Config.TPipeConfig
import com.TTT.P2P.P2PRegistry
import com.TTT.Util.deserialize
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.*

/**
 * Server module for remote memory access.
 * Provides REST endpoints for ContextBank, TodoList, and ContextLock operations.
 */
object MemoryServer
{
    /**
     * Configures the routing for the remote memory endpoints.
     *
     * @param routing Routing tree that should receive the memory routes.
     */
    fun configureMemoryRouting(routing: Routing)
    {
        routing.route("/context")
        {
            intercept(ApplicationCallPipeline.Plugins)
            {
                val authMechanism = P2PRegistry.globalAuthMechanism
                if(authMechanism != null)
                {
                    val authHeader = call.request.header("Authorization") ?: ""
                    val isAuthorized = authMechanism(authHeader)
                    if(!isAuthorized)
                    {
                        call.respondMemoryError(
                            HttpStatusCode.Unauthorized,
                            MemoryErrorType.auth,
                            "Unauthorized memory request"
                        )
                        finish()
                    }
                }
            }

            route("/bank")
            {
                get("/keys")
                {
                    call.respond(ContextBank.getPageKeysSuspend(skipRemote = true))
                }

                get("/{key}")
                {
                    val key = call.parameters["key"] ?: return@get call.respondMemoryError(
                        HttpStatusCode.BadRequest,
                        MemoryErrorType.badRequest,
                        "Missing context key"
                    )

                    if(!ContextBank.contextWindowExistsSuspend(key))
                    {
                        return@get call.respondMemoryError(
                            HttpStatusCode.NotFound,
                            MemoryErrorType.notFound,
                            "Context window '$key' was not found"
                        )
                    }

                    val context = ContextBank.getContextFromBankSuspend(key, skipRemote = true)
                    call.respond(context)
                }

                post("/{key}")
                {
                    val key = call.parameters["key"] ?: return@post call.respondMemoryError(
                        HttpStatusCode.BadRequest,
                        MemoryErrorType.badRequest,
                        "Missing context key"
                    )

                    val body = call.receiveText()
                    val window = deserialize<ContextWindow>(body, useRepair = false) ?: return@post call.respondMemoryError(
                        HttpStatusCode.BadRequest,
                        MemoryErrorType.serialization,
                        "Failed to deserialize ContextWindow"
                    )

                    val pageExists = ContextBank.contextWindowExistsSuspend(key)
                    if(TPipeConfig.enforceMemoryVersioning && pageExists)
                    {
                        val existingWindow = ContextBank.getContextFromBankSuspend(key, skipRemote = true)
                        if(window.version < existingWindow.version)
                        {
                            return@post call.respondMemoryError(
                                HttpStatusCode.Conflict,
                                MemoryErrorType.conflict,
                                "Versioning conflict: server version is ${existingWindow.version}, client version is ${window.version}"
                            )
                        }
                        window.version = maxOf(window.version, existingWindow.version) + 1
                    }
                    else if(!pageExists)
                    {
                        window.version = 0
                    }
                    else
                    {
                        window.version = ContextBank.getContextFromBankSuspend(key, skipRemote = true).version + 1
                    }

                    ContextBank.emplaceSuspend(key, window, mode = StorageMode.MEMORY_AND_DISK, skipRemote = true)
                    call.respond(window)
                }

                get("/{key}/query")
                {
                    val key = call.parameters["key"] ?: return@get call.respondMemoryError(
                        HttpStatusCode.BadRequest,
                        MemoryErrorType.badRequest,
                        "Missing context key"
                    )

                    if(!ContextBank.contextWindowExistsSuspend(key))
                    {
                        return@get call.respondMemoryError(
                            HttpStatusCode.NotFound,
                            MemoryErrorType.notFound,
                            "Context window '$key' was not found"
                        )
                    }

                    val query = call.request.queryParameters["query"] ?: ""
                    val minWeight = call.request.queryParameters["minWeight"]?.toIntOrNull() ?: Int.MIN_VALUE
                    val extractRegex = call.request.queryParameters["extractRegex"] ?: ""
                    val requiredKeys = call.request.queryParameters["requiredKeys"]?.split(",")?.filter { it.isNotEmpty() } ?: emptyList()
                    val aliasKeys = call.request.queryParameters["aliasKeys"]?.split(",")?.filter { it.isNotEmpty() } ?: emptyList()

                    val results = MemoryIntrospection.withCoroutineScope(MemoryIntrospectionConfig(allowedPageKeys = mutableSetOf("*"), allowRead = true))
                    {
                        MemoryIntrospectionTools.queryLorebook(
                            key,
                            query,
                            minWeight,
                            requiredKeys,
                            aliasKeys,
                            extractRegex
                        )
                    }
                    call.respond(results)
                }

                get("/{key}/simulate")
                {
                    val key = call.parameters["key"] ?: return@get call.respondMemoryError(
                        HttpStatusCode.BadRequest,
                        MemoryErrorType.badRequest,
                        "Missing context key"
                    )

                    if(!ContextBank.contextWindowExistsSuspend(key))
                    {
                        return@get call.respondMemoryError(
                            HttpStatusCode.NotFound,
                            MemoryErrorType.notFound,
                            "Context window '$key' was not found"
                        )
                    }

                    val text = call.request.queryParameters["text"] ?: ""
                    val results = MemoryIntrospection.withCoroutineScope(MemoryIntrospectionConfig(allowedPageKeys = mutableSetOf("*"), allowRead = true))
                    {
                        MemoryIntrospectionTools.simulateLorebookTrigger(key, text)
                    }
                    call.respond(results)
                }

                delete("/{key}")
                {
                    val key = call.parameters["key"] ?: return@delete call.respondMemoryError(
                        HttpStatusCode.BadRequest,
                        MemoryErrorType.badRequest,
                        "Missing context key"
                    )

                    val deleted = ContextBank.deleteContextWindowSuspend(key, skipRemote = true)
                    if(!deleted)
                    {
                        return@delete call.respondMemoryError(
                            HttpStatusCode.NotFound,
                            MemoryErrorType.notFound,
                            "Context window '$key' was not found"
                        )
                    }

                    call.respondNoContent()
                }
            }

            route("/todo")
            {
                get("/keys")
                {
                    call.respond(ContextBank.getTodoListKeysSuspend(skipRemote = true))
                }

                get("/{key}")
                {
                    val key = call.parameters["key"] ?: return@get call.respondMemoryError(
                        HttpStatusCode.BadRequest,
                        MemoryErrorType.badRequest,
                        "Missing todo key"
                    )

                    if(!ContextBank.todoListExistsSuspend(key))
                    {
                        return@get call.respondMemoryError(
                            HttpStatusCode.NotFound,
                            MemoryErrorType.notFound,
                            "Todo list '$key' was not found"
                        )
                    }

                    val todo = ContextBank.getPagedTodoListSuspend(key, skipRemote = true)
                    call.respond(todo)
                }

                post("/{key}")
                {
                    val key = call.parameters["key"] ?: return@post call.respondMemoryError(
                        HttpStatusCode.BadRequest,
                        MemoryErrorType.badRequest,
                        "Missing todo key"
                    )

                    val body = call.receiveText()
                    val todo = deserialize<TodoList>(body, useRepair = false) ?: return@post call.respondMemoryError(
                        HttpStatusCode.BadRequest,
                        MemoryErrorType.serialization,
                        "Failed to deserialize TodoList"
                    )

                    val todoExists = ContextBank.todoListExistsSuspend(key)
                    if(TPipeConfig.enforceMemoryVersioning && todoExists)
                    {
                        val existingTodo = ContextBank.getPagedTodoListSuspend(key, skipRemote = true)
                        if(todo.version < existingTodo.version)
                        {
                            return@post call.respondMemoryError(
                                HttpStatusCode.Conflict,
                                MemoryErrorType.conflict,
                                "Versioning conflict: server version is ${existingTodo.version}, client version is ${todo.version}"
                            )
                        }
                        todo.version = maxOf(todo.version, existingTodo.version) + 1
                    }
                    else if(!todoExists)
                    {
                        todo.version = 0
                    }
                    else
                    {
                        todo.version = ContextBank.getPagedTodoListSuspend(key, skipRemote = true).version + 1
                    }

                    ContextBank.emplaceTodoListSuspend(key, todo, mode = StorageMode.MEMORY_AND_DISK, skipRemote = true)
                    call.respond(todo)
                }

                delete("/{key}")
                {
                    val key = call.parameters["key"] ?: return@delete call.respondMemoryError(
                        HttpStatusCode.BadRequest,
                        MemoryErrorType.badRequest,
                        "Missing todo key"
                    )

                    val deleted = ContextBank.deleteTodoListSuspend(key, skipRemote = true)
                    if(!deleted)
                    {
                        return@delete call.respondMemoryError(
                            HttpStatusCode.NotFound,
                            MemoryErrorType.notFound,
                            "Todo list '$key' was not found"
                        )
                    }

                    call.respondNoContent()
                }
            }

            route("/lock")
            {
                get("/keys")
                {
                    call.respond(ContextLock.getLockKeysSuspend(skipRemote = true))
                }

                get("/{key}/state")
                {
                    val key = call.parameters["key"] ?: return@get call.respondMemoryError(
                        HttpStatusCode.BadRequest,
                        MemoryErrorType.badRequest,
                        "Missing lock key"
                    )
                    call.respond(ContextLock.isKeyLockedSuspend(key, skipRemote = true))
                }

                get("/page/{pageKey}/state")
                {
                    val pageKey = call.parameters["pageKey"] ?: return@get call.respondMemoryError(
                        HttpStatusCode.BadRequest,
                        MemoryErrorType.badRequest,
                        "Missing page key"
                    )
                    call.respond(ContextLock.isPageLockedSuspend(pageKey, skipRemote = true))
                }

                post("")
                {
                    val body = call.receiveText()
                    val request = deserialize<LockRequest>(body, useRepair = false) ?: return@post call.respondMemoryError(
                        HttpStatusCode.BadRequest,
                        MemoryErrorType.serialization,
                        "Failed to deserialize LockRequest"
                    )

                    ContextLock.addLockWithMutex(request.key, request.pageKeys, request.isPageKey, request.lockState, skipRemote = true)
                    call.respondNoContent()
                }

                delete("/{key}")
                {
                    val key = call.parameters["key"] ?: return@delete call.respondMemoryError(
                        HttpStatusCode.BadRequest,
                        MemoryErrorType.badRequest,
                        "Missing lock key"
                    )

                    if(ContextLock.getKeyBundle(key) == null)
                    {
                        return@delete call.respondMemoryError(
                            HttpStatusCode.NotFound,
                            MemoryErrorType.notFound,
                            "Lock '$key' was not found"
                        )
                    }

                    ContextLock.removeLockWithMutex(key, skipRemote = true)
                    call.respondNoContent()
                }

                post("/{key}/state")
                {
                    val key = call.parameters["key"] ?: return@post call.respondMemoryError(
                        HttpStatusCode.BadRequest,
                        MemoryErrorType.badRequest,
                        "Missing lock key"
                    )

                    if(ContextLock.getKeyBundle(key) == null)
                    {
                        return@post call.respondMemoryError(
                            HttpStatusCode.NotFound,
                            MemoryErrorType.notFound,
                            "Lock '$key' was not found"
                        )
                    }

                    val body = call.receiveText()
                    val lockState = body.toBooleanStrictOrNull() ?: return@post call.respondMemoryError(
                        HttpStatusCode.BadRequest,
                        MemoryErrorType.badRequest,
                        "Lock state body must be 'true' or 'false'"
                    )

                    if(lockState)
                    {
                        ContextLock.lockKeyBundleWithMutex(key, skipRemote = true)
                    }
                    else
                    {
                        ContextLock.unlockKeyBundleWithMutex(key, skipRemote = true)
                    }

                    call.respondNoContent()
                }
            }
        }
    }

    /**
     * Respond with a typed remote-memory failure payload.
     *
     * @param statusCode HTTP status to send.
     * @param errorType Typed memory error category.
     * @param message Human-readable failure description.
     */
    private suspend fun ApplicationCall.respondMemoryError(
        statusCode: HttpStatusCode,
        errorType: MemoryErrorType,
        message: String
    )
    {
        response.status(statusCode)
        respond(MemoryErrorResponse(errorType, message))
    }

    /**
     * Respond with HTTP 204 and an empty body for mutation success paths that do not need a payload.
     */
    private suspend fun ApplicationCall.respondNoContent()
    {
        response.status(HttpStatusCode.NoContent)
        respondText("")
    }
}
