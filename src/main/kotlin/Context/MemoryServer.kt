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
                if (authMechanism != null)
                {
                    val authHeader = call.request.header("Authorization") ?: ""
                    val isAuthorized = authMechanism(authHeader)
                    if (!isAuthorized)
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
                    call.respond(ContextBank.getPageKeys(skipRemote = true))
                }

                get("/{key}")
                {
                    val key = call.parameters["key"] ?: return@get call.respond(P2PResponse().apply {
                        rejection = P2PRejection(P2PError.transport, "Missing context key")
                    })

                    val context = ContextBank.getContextFromBank(key, skipRemote = true)
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

                    if (TPipeConfig.enforceMemoryVersioning)
                    {
                        val existing = ContextBank.getContextFromBank(key, skipRemote = true)
                        if (window.version < existing.version)
                        {
                            // If client version is older, attempt server-side merge if possible
                            // For simplicity, we currently just reject and expect client to fetch-merge-save
                            return@post call.respond(P2PResponse().apply {
                                rejection = P2PRejection(P2PError.transport, "Versioning conflict: server version is ${existing.version}, client version is ${window.version}")
                            })
                        }
                    }

                    // For remote writes, we ensure version is advanced to the next state
                    window.version = maxOf(window.version, ContextBank.getContextFromBank(key, skipRemote = true).version) + 1
                    ContextBank.emplaceWithMutex(key, window, mode = StorageMode.MEMORY_AND_DISK, skipRemote = true)
                    call.respond(window)
                }

                delete("/{key}")
                {
                    val key = call.parameters["key"] ?: return@delete call.respond(P2PResponse().apply {
                        rejection = P2PRejection(P2PError.transport, "Missing context key")
                    })

                    val result = ContextBank.deletePersistingBankKeyWithMutex(key, skipRemote = true)
                    call.respond(result)
                }
            }

            route("/todo")
            {
                get("/keys")
                {
                    call.respond(ContextBank.getTodoListKeys(skipRemote = true))
                }

                get("/{key}")
                {
                    val key = call.parameters["key"] ?: return@get call.respond(P2PResponse().apply {
                        rejection = P2PRejection(P2PError.transport, "Missing todo key")
                    })

                    val todo = ContextBank.getPagedTodoList(key, skipRemote = true)
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

                    if (TPipeConfig.enforceMemoryVersioning)
                    {
                        val existing = ContextBank.getPagedTodoList(key, skipRemote = true)
                        if (todo.version < existing.version)
                        {
                            return@post call.respond(P2PResponse().apply {
                                rejection = P2PRejection(P2PError.transport, "Versioning conflict: server version is ${existing.version}, client version is ${todo.version}")
                            })
                        }
                    }

                    todo.version = maxOf(todo.version, ContextBank.getPagedTodoList(key, skipRemote = true).version) + 1
                    ContextBank.emplaceTodoList(key, todo, mode = StorageMode.MEMORY_AND_DISK, skipRemote = true)
                    call.respond(todo)
                }
            }

            route("/lock")
            {
                get("/keys")
                {
                    call.respond(ContextLock.getLockKeys(skipRemote = true))
                }

                get("/{key}/state")
                {
                    val key = call.parameters["key"] ?: return@get call.respond(P2PResponse().apply {
                        rejection = P2PRejection(P2PError.transport, "Missing lock key")
                    })
                    call.respond(ContextLock.isKeyLocked(key, skipRemote = true))
                }

                get("/page/{pageKey}/state")
                {
                    val pageKey = call.parameters["pageKey"] ?: return@get call.respond(P2PResponse().apply {
                        rejection = P2PRejection(P2PError.transport, "Missing page key")
                    })
                    call.respond(ContextLock.isPageLocked(pageKey, skipRemote = true))
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

                    if (lockState) ContextLock.lockKeyBundleWithMutex(key, skipRemote = true)
                    else ContextLock.unlockKeyBundleWithMutex(key, skipRemote = true)

                    call.respond(true)
                }
            }
        }
    }
}
