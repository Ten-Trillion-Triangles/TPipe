package com.TTT.Pipeline

import com.TTT.P2P.KillSwitchContext

/**
 * Exception thrown when the Manifold loop iteration limit is exceeded.
 * Acts as a secondary safety system to prevent infinite loops and runaway token consumption.
 *
 * @param iterationsReached The iteration count at which the limit was hit
 * @param maxIterations The configured maximum loop iterations
 * @param context Optional KillSwitchContext if tokens were accumulated before limit hit
 */
class ManifoldLoopLimitExceededException(
    val iterationsReached: Int,
    val maxIterations: Int,
    val context: KillSwitchContext? = null
)
    : RuntimeException("Loop limit exceeded: $iterationsReached/$maxIterations iterations")