package com.TTT.PipeContextProtocol

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Registry for managing global PCP context and execution.
 * Allows hosting a PCP service that agents and external systems can call.
 *
 * This registry holds the global [PcpContext] which defines the allowed operations
 * (functions, commands, HTTP endpoints, etc.) for incoming standalone PCP requests.
 */
object PcpRegistry
{
    private val mutex = Mutex()
    private val dispatcher = PcpExecutionDispatcher()

    /**
     * The global PCP context used for standalone PCP requests.
     * Defines allowed functions, commands, paths, and security settings.
     */
    @Volatile
    var globalContext = PcpContext()

    /**
     * Execute a list of PCP requests against the global context.
     *
     * @param requests List of [PcPRequest] objects to execute
     * @return [PcpExecutionResult] containing the results of each request
     */
    suspend fun executeRequests(requests: List<PcPRequest>): PcpExecutionResult
    {
        return mutex.withLock {
            dispatcher.executeRequests(requests, globalContext)
        }
    }

    /**
     * Execute a list of PCP requests against a specific context.
     * FIX S3: Allows per-session context isolation by accepting context as parameter.
     *
     * @param requests List of [PcPRequest] objects to execute
     * @param context The [PcpContext] to execute against
     * @return [PcpExecutionResult] containing the results of each request
     */
    suspend fun executeRequests(requests: List<PcPRequest>, context: PcpContext): PcpExecutionResult
    {
        return mutex.withLock {
            dispatcher.executeRequests(requests, context)
        }
    }

    /**
     * Execute a single PCP request against the global context.
     *
     * @param request A single [PcPRequest] object to execute
     * @return [PcpRequestResult] containing the result of the request
     */
    suspend fun executeRequest(request: PcPRequest): PcpRequestResult
    {
        return mutex.withLock {
            dispatcher.executeRequest(request, globalContext)
        }
    }

    /**
     * Update the global PCP context.
     *
     * @param context The new [PcpContext] to use
     */
    suspend fun updateGlobalContext(context: PcpContext)
    {
        mutex.withLock {
            globalContext = context
        }
    }
}
