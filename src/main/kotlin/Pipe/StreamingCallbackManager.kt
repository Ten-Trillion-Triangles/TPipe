package com.TTT.Pipe

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * Execution mode for streaming callbacks.
 *
 * @property SEQUENTIAL Callbacks execute one after another in registration order
 * @property CONCURRENT Callbacks execute in parallel using coroutines
 */
enum class StreamingExecutionMode
{
    SEQUENTIAL,
    CONCURRENT
}

/**
 * Manages multiple streaming callbacks with configurable execution mode and error isolation.
 * 
 * Allows multiple independent callbacks to receive streaming chunks without interfering
 * with each other. Each callback's exceptions are isolated to prevent one failing callback
 * from affecting others or stopping the stream.
 *
 * @property executionMode Controls whether callbacks execute sequentially or concurrently
 * @property onError Optional error handler invoked when a callback throws an exception
 */
class StreamingCallbackManager(
    var executionMode: StreamingExecutionMode = StreamingExecutionMode.SEQUENTIAL,
    var onError: ((Exception, String) -> Unit)? = null
)
{
    private val callbacks = mutableListOf<suspend (String) -> Unit>()

    /**
     * Adds a callback to the manager.
     *
     * @param callback Suspendable function that receives streaming chunks
     */
    fun addCallback(callback: suspend (String) -> Unit)
    {
        callbacks.add(callback)
    }

    /**
     * Removes a specific callback from the manager.
     *
     * @param callback The callback to remove
     * @return True if callback was found and removed, false otherwise
     */
    fun removeCallback(callback: suspend (String) -> Unit): Boolean
    {
        return callbacks.remove(callback)
    }

    /**
     * Removes all registered callbacks.
     */
    fun clearCallbacks()
    {
        callbacks.clear()
    }

    /**
     * Returns true if at least one callback is registered.
     */
    fun hasCallbacks(): Boolean = callbacks.isNotEmpty()

    /**
     * Returns the number of registered callbacks.
     */
    fun callbackCount(): Int = callbacks.size

    /**
     * Emits a chunk to all registered callbacks with error isolation.
     * 
     * Each callback is wrapped in try-catch to prevent one callback's exception
     * from affecting others. Execution mode determines whether callbacks run
     * sequentially or concurrently.
     *
     * @param chunk The text chunk to emit to all callbacks
     */
    suspend fun emitToAll(chunk: String)
    {
        if(callbacks.isEmpty()) return

        when(executionMode)
        {
            StreamingExecutionMode.SEQUENTIAL -> emitSequential(chunk)
            StreamingExecutionMode.CONCURRENT -> emitConcurrent(chunk)
        }
    }

    /**
     * Emits chunk to callbacks sequentially in registration order.
     */
    private suspend fun emitSequential(chunk: String)
    {
        for(callback in callbacks)
        {
            try
            {
                callback(chunk)
            }
            catch(e: Exception)
            {
                onError?.invoke(e, chunk)
            }
        }
    }

    /**
     * Emits chunk to all callbacks concurrently using coroutines.
     */
    private suspend fun emitConcurrent(chunk: String) = coroutineScope {
        for(callback in callbacks)
        {
            launch {
                try
                {
                    callback(chunk)
                }
                catch(e: Exception)
                {
                    onError?.invoke(e, chunk)
                }
            }
        }
    }
}
