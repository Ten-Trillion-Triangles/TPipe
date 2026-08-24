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
    private val completionCallbacks = mutableListOf<suspend () -> Unit>()

    /**
     * Adds a callback to the manager.
     *
     * @param callback Suspendable function that receives streaming chunks
     */
    fun addCallback(callback: suspend (String) -> Unit)
    {
        // Dedup by reference equality. The same suspend lambda is a single
        // object; if a caller registers it twice (e.g. once via the parent
        // pipe's setStreamingCallback and once via child propagation), we
        // don't want it firing twice. Without this, chunks appear in the
        // terminal as exact duplicates interleaved (e.g. "HelloHello").
        if(!callbacks.contains(callback))
        {
            callbacks.add(callback)
        }
    }

    /**
     * Returns a read-only snapshot of the currently registered callbacks.
     * Used by parent pipes to propagate callbacks to descendants so chunks
     * from every pipe in the tree flow to the same sinks.
     */
    fun getCallbacks(): List<suspend (String) -> Unit> = callbacks.toList()

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
     * Registers a callback to fire when the stream ends normally.
     *
     * The callback fires exactly once per [emitCompleteToAll] invocation.
     * Errors thrown by a completion callback are routed through [onError]
     * (set in the constructor) and do not stop subsequent callbacks from
     * firing. Dedup by reference identity — registering the same lambda
     * twice results in one call.
     *
     * @param callback Suspendable no-arg function invoked when the stream ends.
     */
    fun addCompleteCallback(callback: suspend () -> Unit)
    {
        if(!completionCallbacks.contains(callback))
        {
            completionCallbacks.add(callback)
        }
    }

    /**
     * Removes a previously-added completion callback.
     *
     * @param callback The callback to remove.
     * @return True if removed, false if not found.
     */
    fun removeCompleteCallback(callback: suspend () -> Unit): Boolean
    {
        return completionCallbacks.remove(callback)
    }

    /**
     * Returns the number of registered completion callbacks.
     */
    fun completeCallbackCount(): Int = completionCallbacks.size

    /**
     * Emits a completion event to all registered completion callbacks.
     *
     * Each callback runs in the caller's coroutine. Errors thrown by one
     * callback are caught and routed through [onError]; subsequent callbacks
     * still fire. Honors the same [executionMode] as chunk callbacks.
     */
    suspend fun emitCompleteToAll()
    {
        if(completionCallbacks.isEmpty()) return

        when(executionMode)
        {
            StreamingExecutionMode.SEQUENTIAL -> emitCompleteSequential()
            StreamingExecutionMode.CONCURRENT -> emitCompleteConcurrent()
        }
    }

    private suspend fun emitCompleteSequential()
    {
        for(callback in completionCallbacks)
        {
            try
            {
                callback()
            }
            catch(e: Exception)
            {
                onError?.invoke(e, "")
            }
        }
    }

    private suspend fun emitCompleteConcurrent() = coroutineScope {
        for(callback in completionCallbacks)
        {
            launch {
                try
                {
                    callback()
                }
                catch(e: Exception)
                {
                    onError?.invoke(e, "")
                }
            }
        }
    }

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
