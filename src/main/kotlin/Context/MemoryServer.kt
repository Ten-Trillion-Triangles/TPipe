package com.TTT.Context

import com.TTT.Config.TPipeConfig
import com.TTT.P2P.P2PError
import com.TTT.P2P.P2PRejection
import com.TTT.P2P.P2PRegistry
import com.TTT.P2P.P2PResponse
import com.TTT.Util.deserialize
import com.TTT.Util.serialize
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

/**
 * Server module for remote memory access.
 * Provides REST endpoints for ContextBank, TodoList, and ContextLock operations.
 */
object MemoryServer
{
    /**
     * Configures the routing for the remote memory endpoints.
     */
    fun configureMemoryRouting(routing: Routing)
    {
        routing.route("/context")
        {
            // Middleware for authentication
            intercept(ApplicationCallPipeline.Plugins)
            {
                // Always check auth if routes are accessed, regardless of enable flag
                val authMechanism = P2PRegistry.globalAuthMechanism
                if(authMechanism != null)
                {
                    val authHeader = call.request.header("Authorization") ?: ""
                    val isAuthorized = authMechanism(authHeader)
                    if(!isAuthorized)
                    {
                        call.respond(P2PResponse().apply {
                            rejection = P2PRejection(P2PError.auth, "Unauthorized memory request")
                        })
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
                    val key = call.parameters["key"] ?: return@get call.respond(P2PResponse().apply {
                        rejection = P2PRejection(P2PError.transport, "Missing context key")
                    })

                    val context = ContextBank.getContextFromBankSuspend(key, skipRemote = true)
                    call.respond(context)
                }

                post("/{key}")
                {
                    val key = call.parameters["key"] ?: return@post call.respond(P2PResponse().apply {
                        rejection = P2PRejection(P2PError.transport, "Missing context key")
                    })

                    val body = call.receiveText()
                    val window = deserialize<ContextWindow>(body) ?: return@post call.respond(P2PResponse().apply {
                        rejection = P2PRejection(P2PError.transport, "Failed to deserialize ContextWindow")
                    })

                    if(TPipeConfig.enforceMemoryVersioning)
                    {
                        val existing = ContextBank.getContextFromBankSuspend(key, skipRemote = true)
                        if(window.version < existing.version)
                        {
                            // If client version is older, attempt server-side merge if possible
                            // For simplicity, we currently just reject and expect client to fetch-merge-save
                            return@post call.respond(P2PResponse().apply {
                                rejection = P2PRejection(P2PError.transport, "Versioning conflict: server version is ${existing.version}, client version is ${window.version}")
                            })
                        }
                    }

                    // For remote writes, we ensure version is advanced to the next state
                    window.version = maxOf(window.version, ContextBank.getContextFromBankSuspend(key, skipRemote = true).version) + 1
                    ContextBank.emplaceSuspend(key, window, mode = StorageMode.MEMORY_AND_DISK, skipRemote = true)
                    call.respond(window)
                }

                get("/{key}/query")
                {
                    val key = call.parameters["key"] ?: return@get call.respond(P2PResponse().apply {
                        rejection = P2PRejection(P2PError.transport, "Missing context key")
                    })

                    val query = call.request.queryParameters["query"] ?: ""
                    val minWeight = call.request.queryParameters["minWeight"]?.toIntOrNull() ?: Int.MIN_VALUE
                    val extractRegex = call.request.queryParameters["extractRegex"] ?: ""
                    val requiredKeys = call.request.queryParameters["requiredKeys"]?.split(",")?.filter { it.isNotEmpty() } ?: emptyList()
                    val aliasKeys = call.request.queryParameters["aliasKeys"]?.split(",")?.filter { it.isNotEmpty() } ?: emptyList()

                    // Server-side, we permit all introspection because transport-level auth is already handled
                    val results = MemoryIntrospection.withCoroutineScope(MemoryIntrospectionConfig(allowedPageKeys = mutableSetOf("*"), allowRead = true))
                    {
                        MemoryIntrospectionTools.queryLorebook(
                            key, query, minWeight, requiredKeys, aliasKeys, extractRegex
                        )
                    }
                    call.respond(results)
                }

                get("/{key}/simulate")
                {
                    val key = call.parameters["key"] ?: return@get call.respond(P2PResponse().apply {
                        rejection = P2PRejection(P2PError.transport, "Missing context key")
                    })

                    val text = call.request.queryParameters["text"] ?: ""

                    val results = MemoryIntrospection.withCoroutineScope(MemoryIntrospectionConfig(allowedPageKeys = mutableSetOf("*"), allowRead = true))
                    {
                        MemoryIntrospectionTools.simulateLorebookTrigger(key, text)
                    }
                    call.respond(results)
                }

                delete("/{key}")
                {
                    val key = call.parameters["key"] ?: return@delete call.respond(P2PResponse().apply {
                        rejection = P2PRejection(P2PError.transport, "Missing context key")
                    })

                    val result = ContextBank.deletePersistingBankKeySuspend(key, skipRemote = true)
                    call.respond(result)
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
                    val key = call.parameters["key"] ?: return@get call.respond(P2PResponse().apply {
                        rejection = P2PRejection(P2PError.transport, "Missing todo key")
                    })

                    val todo = ContextBank.getPagedTodoListSuspend(key, skipRemote = true)
                    call.respond(todo)
                }

                post("/{key}")
                {
                    val key = call.parameters["key"] ?: return@post call.respond(P2PResponse().apply {
                        rejection = P2PRejection(P2PError.transport, "Missing todo key")
                    })

                    val body = call.receiveText()
                    val todo = deserialize<TodoList>(body) ?: return@post call.respond(P2PResponse().apply {
                        rejection = P2PRejection(P2PError.transport, "Failed to deserialize TodoList")
                    })

                    if(TPipeConfig.enforceMemoryVersioning)
                    {
                        val existing = ContextBank.getPagedTodoListSuspend(key, skipRemote = true)
                        if(todo.version < existing.version)
                        {
                            return@post call.respond(P2PResponse().apply {
                                rejection = P2PRejection(P2PError.transport, "Versioning conflict: server version is ${existing.version}, client version is ${todo.version}")
                            })
                        }
                    }

                    todo.version = maxOf(todo.version, ContextBank.getPagedTodoListSuspend(key, skipRemote = true).version) + 1
                    ContextBank.emplaceTodoListSuspend(key, todo, mode = StorageMode.MEMORY_AND_DISK, skipRemote = true)
                    call.respond(todo)
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
                    val key = call.parameters["key"] ?: return@get call.respond(P2PResponse().apply {
                        rejection = P2PRejection(P2PError.transport, "Missing lock key")
                    })
                    call.respond(ContextLock.isKeyLockedSuspend(key, skipRemote = true))
                }

                get("/page/{pageKey}/state")
                {
                    val pageKey = call.parameters["pageKey"] ?: return@get call.respond(P2PResponse().apply {
                        rejection = P2PRejection(P2PError.transport, "Missing page key")
                    })
                    call.respond(ContextLock.isPageLockedSuspend(pageKey, skipRemote = true))
                }

                post("/")
                {
                    val body = call.receiveText()
                    val request = deserialize<LockRequest>(body) ?: return@post call.respond(P2PResponse().apply {
                        rejection = P2PRejection(P2PError.transport, "Failed to deserialize LockRequest")
                    })

                    ContextLock.addLockWithMutex(request.key, request.pageKeys, request.isPageKey, request.lockState, skipRemote = true)
                    call.respond(true)
                }

                delete("/{key}")
                {
                    val key = call.parameters["key"] ?: return@delete call.respond(P2PResponse().apply {
                        rejection = P2PRejection(P2PError.transport, "Missing lock key")
                    })

                    ContextLock.removeLockWithMutex(key, skipRemote = true)
                    call.respond(true)
                }

                post("/{key}/state")
                {
                    val key = call.parameters["key"] ?: return@post call.respond(P2PResponse().apply {
                        rejection = P2PRejection(P2PError.transport, "Missing lock key")
                    })

                    val body = call.receiveText()
                    val lockState = body.toBoolean()

                    if(lockState) ContextLock.lockKeyBundleWithMutex(key, skipRemote = true)
                    else ContextLock.unlockKeyBundleWithMutex(key, skipRemote = true)

                    call.respond(true)
                }
            }
        }
    }
}
