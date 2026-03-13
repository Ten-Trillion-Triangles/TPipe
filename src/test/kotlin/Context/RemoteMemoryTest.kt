package com.TTT.Context

import com.TTT.Config.TPipeConfig
import com.TTT.P2P.P2PRegistry
import com.TTT.module
import io.ktor.http.HttpStatusCode
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.net.ServerSocket
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * End-to-end tests for the typed remote-memory client and server contract.
 */
class RemoteMemoryTest
{
    /**
     * Verifies that `MemoryClient` supports full CRUD behavior for context pages, todo lists, and locks.
     * This also validates delete-followed-by-read semantics and key-list cache invalidation.
     */
    @Test
    fun memoryClientSupportsRemoteCrudAndFullDeleteSemantics() = runBlocking {
        withRemoteServer {
            val contextKey = "remote-context-${System.nanoTime()}"
            val todoKey = "remote-todo-${System.nanoTime()}"
            val lockKey = "remote-lock-${System.nanoTime()}"

            val contextWindow = ContextWindow().apply {
                contextElements.add("remote context element")
            }
            val storedContext = assertSuccess(
                MemoryClient.emplaceContextWindow(contextKey, contextWindow),
                "store remote context window"
            )
            assertEquals(0, storedContext.version)
            assertTrue(assertSuccess(MemoryClient.getPageKeys(), "list remote page keys").contains(contextKey))

            val fetchedContext = assertSuccess(
                MemoryClient.getContextWindow(contextKey),
                "fetch remote context window"
            )
            assertEquals("remote context element", fetchedContext.contextElements[0])
            assertTrue(ContextBank.contextWindowExistsSuspend(contextKey))

            assertSuccess(MemoryClient.deleteContextWindow(contextKey), "delete remote context window")
            assertNotFound(MemoryClient.getContextWindow(contextKey), HttpStatusCode.NotFound)
            assertFalse(assertSuccess(MemoryClient.getPageKeys(), "list remote page keys after delete").contains(contextKey))
            assertFalse(ContextBank.contextWindowExistsSuspend(contextKey))

            val recreatedWindow = ContextWindow().apply {
                contextElements.add("fresh context")
                version = 99
            }
            val recreatedContext = assertSuccess(
                MemoryClient.emplaceContextWindow(contextKey, recreatedWindow),
                "recreate remote context window"
            )
            assertEquals(0, recreatedContext.version)

            val todoList = TodoList().apply {
                tasks.tasks.add(TodoListTask(1, "Remote task", "Complete it", false))
            }
            val storedTodoList = assertSuccess(
                MemoryClient.emplaceTodoList(todoKey, todoList),
                "store remote todo list"
            )
            assertEquals(0, storedTodoList.version)
            assertTrue(assertSuccess(MemoryClient.getTodoListKeys(), "list remote todo keys").contains(todoKey))

            val fetchedTodoList = assertSuccess(
                MemoryClient.getTodoList(todoKey),
                "fetch remote todo list"
            )
            assertEquals("Remote task", fetchedTodoList.tasks.tasks[0].task)
            assertTrue(ContextBank.todoListExistsSuspend(todoKey))

            assertSuccess(MemoryClient.deleteTodoList(todoKey), "delete remote todo list")
            assertNotFound(MemoryClient.getTodoList(todoKey), HttpStatusCode.NotFound)
            assertFalse(assertSuccess(MemoryClient.getTodoListKeys(), "list remote todo keys after delete").contains(todoKey))
            assertFalse(ContextBank.todoListExistsSuspend(todoKey))

            assertSuccess(MemoryClient.addLock(LockRequest(key = lockKey, lockState = true)), "add remote lock")
            assertTrue(assertSuccess(MemoryClient.isKeyLocked(lockKey), "check remote key lock"))
            assertTrue(assertSuccess(MemoryClient.getLockKeys(), "list remote lock keys").contains(lockKey))

            assertSuccess(MemoryClient.updateLockState(lockKey, false), "update remote lock state")
            assertFalse(assertSuccess(MemoryClient.isKeyLocked(lockKey), "check unlocked remote key lock"))

            assertSuccess(MemoryClient.removeLock(lockKey), "remove remote lock")
            assertFalse(assertSuccess(MemoryClient.getLockKeys(), "list remote lock keys after delete").contains(lockKey))
            assertFalse(assertSuccess(MemoryClient.isKeyLocked(lockKey), "check removed remote key lock"))
        }
    }

    /**
     * Verifies that auth failures are surfaced as typed remote-memory failures rather than soft fallbacks.
     */
    @Test
    fun memoryClientReturnsTypedAuthFailures() = runBlocking {
        withRemoteServer(authMechanism = { authHeader -> authHeader == "Bearer good-token" }) {
            TPipeConfig.remoteMemoryAuthToken = "bad-token"
            MemoryClient.clearCaches()

            val authFailure = MemoryClient.getPageKeys()
            val typedFailure = assertFailure(authFailure, "request remote page keys with invalid auth")
            assertEquals(HttpStatusCode.Unauthorized, typedFailure.statusCode)
            assertEquals(MemoryErrorType.auth, typedFailure.error.errorType)
        }
    }

    /**
     * Verifies that transport failures remain typed and do not collapse into empty remote state.
     */
    @Test
    fun memoryClientReturnsTypedTransportFailures() = runBlocking {
        withRemoteClientConfig("http://127.0.0.1:${allocatePort()}") {
            val transportFailure = MemoryClient.getPageKeys()
            val typedFailure = assertFailure(transportFailure, "request remote page keys without a server")
            assertEquals(null, typedFailure.statusCode)
            assertEquals(MemoryErrorType.transport, typedFailure.error.errorType)
        }
    }

    /**
     * Start a real embedded memory server with isolated disk state and restore all global configuration afterward.
     *
     * @param authMechanism Authorization mechanism used by the memory server.
     * @param block Test body that runs while the server is active.
     */
    private suspend fun withRemoteServer(
        authMechanism: suspend (String) -> Boolean = { true },
        block: suspend () -> Unit
    )
    {
        val port = allocatePort()
        val server = embeddedServer(Netty, port = port, module = module()).start(wait = false)
        delay(200)
        withRemoteClientConfig(
            remoteUrl = "http://127.0.0.1:$port",
            authMechanism = authMechanism,
            stopServer = { server.stop(1000, 2000) },
            block = block
        )
    }

    /**
     * Configure isolated remote-memory state for a test, then restore all globals after [block] finishes.
     *
     * @param remoteUrl Remote-memory base URL to assign.
     * @param authMechanism Optional server auth mechanism.
     * @param stopServer Optional stop hook for an embedded server started by the test.
     * @param block Test body executed with the supplied config.
     */
    private suspend fun withRemoteClientConfig(
        remoteUrl: String,
        authMechanism: (suspend (String) -> Boolean)? = null,
        stopServer: (() -> Unit)? = null,
        block: suspend () -> Unit
    )
    {
        val originalConfigDir = TPipeConfig.configDir
        val originalInstanceId = TPipeConfig.instanceID
        val originalRemoteMemoryEnabled = TPipeConfig.remoteMemoryEnabled
        val originalRemoteMemoryUrl = TPipeConfig.remoteMemoryUrl
        val originalRemoteMemoryAuthToken = TPipeConfig.remoteMemoryAuthToken
        val originalUseRemoteMemoryGlobally = TPipeConfig.useRemoteMemoryGlobally
        val originalEnforceMemoryVersioning = TPipeConfig.enforceMemoryVersioning
        val originalAuthMechanism = P2PRegistry.globalAuthMechanism
        val isolatedConfigDir = Files.createTempDirectory("tpipe-remote-memory-test").toFile()

        try
        {
            resetMemoryState()
            TPipeConfig.configDir = isolatedConfigDir.absolutePath
            TPipeConfig.instanceID = "RemoteMemoryTest-${System.nanoTime()}"
            TPipeConfig.remoteMemoryEnabled = true
            TPipeConfig.remoteMemoryUrl = remoteUrl
            TPipeConfig.remoteMemoryAuthToken = ""
            TPipeConfig.useRemoteMemoryGlobally = true
            TPipeConfig.enforceMemoryVersioning = true
            P2PRegistry.globalAuthMechanism = authMechanism
            MemoryClient.clearCaches()
            block()
        }

        finally
        {
            MemoryClient.clearCaches()
            resetMemoryState()
            TPipeConfig.configDir = originalConfigDir
            TPipeConfig.instanceID = originalInstanceId
            TPipeConfig.remoteMemoryEnabled = originalRemoteMemoryEnabled
            TPipeConfig.remoteMemoryUrl = originalRemoteMemoryUrl
            TPipeConfig.remoteMemoryAuthToken = originalRemoteMemoryAuthToken
            TPipeConfig.useRemoteMemoryGlobally = originalUseRemoteMemoryGlobally
            TPipeConfig.enforceMemoryVersioning = originalEnforceMemoryVersioning
            P2PRegistry.globalAuthMechanism = originalAuthMechanism
            stopServer?.invoke()
            isolatedConfigDir.deleteRecursively()
        }
    }

    /**
     * Reset singleton memory state so remote-memory tests do not leak global state across runs.
     */
    private fun resetMemoryState()
    {
        ContextBank.evictAllFromMemory()
        ContextBank.evictAllTodoListsFromMemory()
        ContextBank.clearRetrievalFunctions()
        ContextBank.clearWriteBackFunctions()
        ContextBank.clearCache()
        ContextLock.getLockKeys(skipRemote = true).toList().forEach { lockKey ->
            ContextLock.removeLock(lockKey, skipRemote = true)
        }
    }

    /**
     * Allocate an ephemeral TCP port.
     *
     * @return A currently free localhost port.
     */
    private fun allocatePort(): Int
    {
        return ServerSocket(0).use { serverSocket ->
            serverSocket.localPort
        }
    }

    /**
     * Assert that a remote-memory result succeeded and return its payload.
     *
     * @param result Result to inspect.
     * @param operation Description used in assertion failures.
     * @return Successful payload.
     */
    private fun <T> assertSuccess(result: MemoryOperationResult<T>, operation: String): T
    {
        return when(result)
        {
            is MemoryOperationResult.Success -> result.value
            is MemoryOperationResult.Failure -> fail(
                "Expected success for $operation but received ${result.error.errorType}: ${result.error.message}"
            )
        }
    }

    /**
     * Assert that a remote-memory result failed and return the typed failure payload.
     *
     * @param result Result to inspect.
     * @param operation Description used in assertion failures.
     * @return Typed failure details.
     */
    private fun <T> assertFailure(result: MemoryOperationResult<T>, operation: String): MemoryOperationResult.Failure
    {
        return when(result)
        {
            is MemoryOperationResult.Success -> fail("Expected failure for $operation but the call succeeded")
            is MemoryOperationResult.Failure -> result
        }
    }

    /**
     * Assert that a remote-memory read failed with a typed not-found error.
     *
     * @param result Result to inspect.
     * @param expectedStatus Expected not-found HTTP status.
     */
    private fun <T> assertNotFound(result: MemoryOperationResult<T>, expectedStatus: HttpStatusCode)
    {
        val typedFailure = assertFailure(result, "read deleted remote memory key")
        assertEquals(expectedStatus, typedFailure.statusCode)
        assertEquals(MemoryErrorType.notFound, typedFailure.error.errorType)
    }
}
