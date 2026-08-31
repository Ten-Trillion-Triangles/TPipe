package com.TTT.Pipeline

/**
 * Tracks active container executions and prevents implementation-plan mutation while a
 * container is running.
 *
 * The monitor is intentionally held while a mutation callback runs so checking the active
 * count and applying the associated configuration change is one atomic operation.
 */
internal class ImplementationPlanLifecycleGuard
{
    private val monitor = Any()
    private var activeExecutions: Int = 0

    /** Mark one container execution as active. */
    fun beginExecution()
    {
        synchronized(monitor)
        {
            activeExecutions++
        }
    }

    /** Mark one container execution as complete. */
    fun endExecution()
    {
        synchronized(monitor)
        {
            check(activeExecutions > 0) {
                "Implementation-plan execution lifecycle ended without a matching begin."
            }
            activeExecutions--
        }
    }

    /**
     * Run a configuration mutation only when no execution is active.
     *
     * @param block The mutation to run while holding the lifecycle monitor.
     * @return The value returned by [block].
     * @throws IllegalStateException when the container is executing.
     */
    fun <T> mutateBetweenExecutions(block: () -> T): T
    {
        synchronized(monitor)
        {
            check(activeExecutions == 0) {
                "Implementation plan cannot be changed while the container is executing."
            }
            return block()
        }
    }

    /** Return whether at least one execution is active. */
    fun isExecutionActive(): Boolean = synchronized(monitor) { activeExecutions > 0 }
}
