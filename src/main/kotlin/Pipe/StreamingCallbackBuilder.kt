package com.TTT.Pipe

/**
 * Builder for configuring streaming callbacks with fluent API.
 * 
 * Provides a chainable interface for adding multiple callbacks and configuring
 * execution mode. Follows the builder pattern used elsewhere in TPipe.
 *
 * Example usage:
 * ```
 * val manager = StreamingCallbackBuilder()
 *     .add { chunk -> print(chunk) }
 *     .add { chunk -> logToFile(chunk) }
 *     .concurrent()
 *     .build()
 * ```
 */
class StreamingCallbackBuilder
{
    private val manager = StreamingCallbackManager()

    /**
     * Adds a suspendable callback to the manager.
     *
     * @param callback Suspendable function that receives streaming chunks
     * @return This builder for method chaining
     */
    fun add(callback: suspend (String) -> Unit): StreamingCallbackBuilder
    {
        manager.addCallback(callback)
        return this
    }

    /**
     * Adds a non-suspending callback to the manager.
     * Automatically wraps the callback in a suspending lambda.
     *
     * @param callback Regular function that receives streaming chunks
     * @return This builder for method chaining
     */
    fun add(callback: (String) -> Unit): StreamingCallbackBuilder
    {
        manager.addCallback { chunk -> callback(chunk) }
        return this
    }

    /**
     * Sets the execution mode for callbacks.
     *
     * @param mode The execution mode (SEQUENTIAL or CONCURRENT)
     * @return This builder for method chaining
     */
    fun executionMode(mode: StreamingExecutionMode): StreamingCallbackBuilder
    {
        manager.executionMode = mode
        return this
    }

    /**
     * Convenience method to set sequential execution mode.
     *
     * @return This builder for method chaining
     */
    fun sequential(): StreamingCallbackBuilder
    {
        manager.executionMode = StreamingExecutionMode.SEQUENTIAL
        return this
    }

    /**
     * Convenience method to set concurrent execution mode.
     *
     * @return This builder for method chaining
     */
    fun concurrent(): StreamingCallbackBuilder
    {
        manager.executionMode = StreamingExecutionMode.CONCURRENT
        return this
    }

    /**
     * Sets an error handler for callback exceptions.
     *
     * @param handler Function invoked when a callback throws an exception
     * @return This builder for method chaining
     */
    fun onError(handler: (Exception, String) -> Unit): StreamingCallbackBuilder
    {
        manager.onError = handler
        return this
    }

    /**
     * Builds and returns the configured StreamingCallbackManager.
     *
     * @return The configured manager instance
     */
    fun build(): StreamingCallbackManager
    {
        return manager
    }
}
