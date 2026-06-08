package com.TTT.Native

import com.TTT.Pipeline.DiscussionStrategy
import com.TTT.Pipeline.Junction
import com.TTT.Pipeline.JunctionMemoryPolicy
import com.TTT.Pipeline.JunctionWorkflowRecipe
import com.TTT.Pipe.MultimodalContent
import com.TTT.Util.serialize
import kotlinx.coroutines.runBlocking

/**
 * Handle representing a TPipe [Junction] instance.
 *
 * Junction is the multi-participant discussion harness that pairs a
 * moderator P2PInterface with N participant P2PInterfaces and runs
 * rounds until a decision is reached. See [com.TTT.Pipeline.Junction]
 * for the full contract.
 *
 * The C ABI exposes the executable surface plus the integer/enum
 * configuration methods (setStrategy, setRounds, setVotingThreshold,
 * setMaxNestedDepth, setWorkflowRecipe, setMemoryPolicy, enableTracing)
 * and the corresponding getters. The DSL's suspend-lambda configuration
 * methods (addModerator, addParticipant, setWorkflowRecipe's phase
 * function form, etc.) require FFI thunks for lambda marshaling and are
 * not currently reachable from C.
 *
 * Mirror fields: the C ABI getter methods (`getStrategy`, `getRounds`,
 * `getVotingThreshold`, `getMaxNestedDepth`, `getWorkflowRecipe`,
 * `getMemoryPolicy`) read mirror fields updated by the corresponding
 * setters. This avoids requiring new public JVM accessors on Junction
 * while still giving C callers a faithful view of the configuration.
 *
 * @param junction The TPipe Junction instance to wrap.
 */
class JunctionHandle(
    val junction: Junction
)
{
    //====================================================================
    // Configuration mirror — keeps the C-callable view of the integer/enum
    // configuration fields that Junction holds in private state.
    //====================================================================

    /** Mirror of [com.TTT.Pipeline.DiscussionState.strategy]. */
    private var _strategy: DiscussionStrategy = DiscussionStrategy.SIMULTANEOUS

    /** Mirror of [com.TTT.Pipeline.DiscussionState.maxRounds]. */
    private var _rounds: Int = 3

    /** Mirror of [com.TTT.Pipeline.DiscussionState.consensusThreshold]. */
    var votingThreshold: Double = 0.75

    /** Mirror of Junction's nested-depth cap. */
    private var _maxNestedDepth: Int = 8

    /** Mirror of the workflow recipe. */
    private var _workflowRecipe: JunctionWorkflowRecipe = JunctionWorkflowRecipe.DISCUSSION_ONLY

    /** Mirror of the outbound token budget from the memory policy. */
    private var _memoryOutboundBudget: Int = 8192

    /** Mirror of the summary budget from the memory policy. */
    private var _memorySummaryBudget: Int = 1024

    //====================================================================
    // Original C ABI surface (create/init/execute/release/serialize)
    //====================================================================

    /**
     * Initialize the junction. Wraps the suspend `Junction.init()` in
     * runBlocking. Note that `Junction.init()` requires a moderator and at
     * least one participant to be registered first; the C ABI caller is
     * responsible for configuring the junction via internal JVM APIs before
     * invoking the init shim.
     *
     * @return 0 on success; TPIPE_ERR_INTERNAL on failure.
     */
    fun init(): Int = try {
        runBlocking { junction.init() }
        0
    } catch (e: Exception) {
        -0x0E  // TPIPE_ERR_INTERNAL
    }

    /**
     * Execute the junction with the given content. Returns a new content
     * handle wrapping the output MultimodalContent. The C ABI caller is
     * responsible for releasing the returned handle.
     *
     * @param inputContent The input content handle.
     * @return A new CONTENT handle wrapping the output MultimodalContent,
     *   or 0 on failure.
     */
    fun execute(inputContent: ContentHandle): Long = try {
        val mc: MultimodalContent = inputContent.toMultimodalContent()
        val output: MultimodalContent = runBlocking { junction.execute(mc) }
        val outputHandle = ContentHandle.fromMultimodalContent(output)
        HandleRegistry.allocate(HandleTypes.CONTENT, outputHandle)
    } catch (e: Exception) {
        0L
    }

    /**
     * Release this junction handle. The actual handle release is performed
     * by [HandleRegistry.release]; this method is a no-op kept for symmetry
     * with the other container handle classes.
     */
    fun release() {
        // No-op: HandleRegistry.release() is the source of truth.
    }

    /**
     * Get a JSON snapshot of the junction's state. The C ABI serialize
     * shim forwards this string to the caller's buffer.
     *
     * @return A JSON object describing the junction handle, including the
     *   mirrored configuration (strategy, rounds, votingThreshold,
     *   maxNestedDepth, workflowRecipe, memoryOutboundBudget,
     *   memorySummaryBudget).
     */
    fun serialize(): String = try {
        val sb = StringBuilder()
        sb.append("{")
        sb.append("\"type\":\"Junction\",")
        sb.append("\"className\":\"${junction::class.simpleName ?: "Junction"}\",")
        sb.append("\"strategy\":\"$_strategy\",")
        sb.append("\"rounds\":$_rounds,")
        sb.append("\"votingThreshold\":$votingThreshold,")
        sb.append("\"maxNestedDepth\":$_maxNestedDepth,")
        sb.append("\"workflowRecipe\":\"$_workflowRecipe\",")
        sb.append("\"memoryOutboundBudget\":$_memoryOutboundBudget,")
        sb.append("\"memorySummaryBudget\":$_memorySummaryBudget")
        sb.append("}")
        sb.toString()
    } catch (e: Exception) {
        "{\"type\":\"Junction\"}"
    }

    //====================================================================
    // Cycle 3 — C ABI configuration surface
    //====================================================================

    /**
     * C ABI: `TPipe_Junction_setStrategy(handle, strategy)`.
     *
     * Set the discussion strategy (SIMULTANEOUS, CONVERSATIONAL,
     * ROUND_ROBIN). The strategy is passed as an int matching the
     * [DiscussionStrategy] enum ordinal.
     *
     * @param strategy Strategy ordinal.
     * @return 0 on success; TPIPE_ERR_INVALID_ARGUMENT on unknown ordinal.
     */
    fun setStrategy(strategy: Int): Int {
        val s = enumValues<DiscussionStrategy>().getOrNull(strategy)
            ?: return -0x04  // TPIPE_ERR_INVALID_ARGUMENT
        return try {
            junction.setStrategy(s)
            this._strategy = s
            0
        } catch (e: Exception) {
            -0x0E
        }
    }

    /**
     * C ABI: `TPipe_Junction_getStrategy(handle, int*)`.
     *
     * Get the current discussion strategy ordinal. Writes to a
     * caller-provided 4-byte int.
     *
     * @return The strategy ordinal (0..n) on success; negative error code.
     */
    fun getStrategy(): Int = _strategy.ordinal

    /**
     * C ABI: `TPipe_Junction_setRounds(handle, rounds)`.
     *
     * Set the maximum number of discussion rounds. The C ABI value 0
     * is treated as "do not change" (defensive: prevents the JVM
     * require(rounds > 0) from firing on uninitialized buffers).
     *
     * @param rounds Round count (must be > 0).
     * @return 0 on success; TPIPE_ERR_INVALID_ARGUMENT on non-positive.
     */
    fun setRounds(rounds: Int): Int {
        if (rounds <= 0) return -0x04
        return try {
            junction.setRounds(rounds)
            this._rounds = rounds
            0
        } catch (e: Exception) {
            -0x0E
        }
    }

    /**
     * C ABI: `TPipe_Junction_getRounds(handle, int*)`.
     *
     * @return The configured round count.
     */
    fun getRounds(): Int = _rounds

    /**
     * C ABI: `TPipe_Junction_setVotingThreshold(handle, threshold)`.
     *
     * Set the consensus threshold (Double bits encoded in a long).
     *
     * @param thresholdBits Double.toRawLongBits(threshold).
     * @return 0 on success; TPIPE_ERR_INVALID_ARGUMENT on out-of-range.
     */
    fun setVotingThreshold(thresholdBits: Long): Int {
        val t = Double.fromBits(thresholdBits)
        if (t <= 0.0 || t > 1.0) return -0x04
        return try {
            junction.setVotingThreshold(t)
            this.votingThreshold = t
            0
        } catch (e: Exception) {
            -0x0E
        }
    }

    /**
     * C ABI: `TPipe_Junction_getVotingThreshold(handle, double*)`.
     *
     * @return Double.toRawLongBits(threshold).
     */
    fun getVotingThreshold(): Long = votingThreshold.toRawBits()

    /**
     * C ABI: `TPipe_Junction_setMaxNestedDepth(handle, depth)`.
     *
     * @param depth Maximum allowed nested depth (must be > 0).
     * @return 0 on success; TPIPE_ERR_INVALID_ARGUMENT on non-positive.
     */
    fun setMaxNestedDepth(depth: Int): Int {
        if (depth <= 0) return -0x04
        return try {
            junction.setMaxNestedDepth(depth)
            this._maxNestedDepth = depth
            0
        } catch (e: Exception) {
            -0x0E
        }
    }

    /**
     * C ABI: `TPipe_Junction_getMaxNestedDepth(handle, int*)`.
     *
     * @return The configured maximum nested depth.
     */
    fun getMaxNestedDepth(): Int = _maxNestedDepth

    /**
     * C ABI: `TPipe_Junction_setWorkflowRecipe(handle, recipe)`.
     *
     * Set the workflow recipe by enum ordinal.
     *
     * @param recipe Workflow recipe ordinal.
     * @return 0 on success; TPIPE_ERR_INVALID_ARGUMENT on unknown ordinal.
     */
    fun setWorkflowRecipe(recipe: Int): Int {
        val r = enumValues<JunctionWorkflowRecipe>().getOrNull(recipe)
            ?: return -0x04
        return try {
            junction.setWorkflowRecipe(r)
            this._workflowRecipe = r
            0
        } catch (e: Exception) {
            -0x0E
        }
    }

    /**
     * C ABI: `TPipe_Junction_getWorkflowRecipe(handle, int*)`.
     *
     * @return The configured workflow recipe ordinal.
     */
    fun getWorkflowRecipe(): Int = _workflowRecipe.ordinal

    /**
     * C ABI: `TPipe_Junction_setMemoryPolicy(handle, outboundBudget, summaryBudget)`.
     *
     * Apply a new memory policy with the given outbound and summary
     * budgets. Other policy fields keep their default values.
     *
     * @param outboundBudget New outbound token budget.
     * @param summaryBudget New summary budget.
     * @return 0 on success; TPIPE_ERR_INVALID_ARGUMENT on negative values.
     */
    fun setMemoryPolicy(outboundBudget: Int, summaryBudget: Int): Int {
        if (outboundBudget < 0 || summaryBudget < 0) return -0x04
        return try {
            junction.setMemoryPolicy(
                JunctionMemoryPolicy(
                    outboundTokenBudget = outboundBudget,
                    summaryBudget = summaryBudget
                )
            )
            this._memoryOutboundBudget = outboundBudget
            this._memorySummaryBudget = summaryBudget
            0
        } catch (e: Exception) {
            -0x0E
        }
    }

    /**
     * C ABI: `TPipe_Junction_getMemoryPolicy(handle, int*)`.
     *
     * Get the outbound token budget (the most useful single field for
     * the C ABI). Callers can request the summary budget via the
     * `getMemoryPolicyEx` companion if needed.
     *
     * @return The configured outbound token budget.
     */
    fun getMemoryPolicy(): Int = _memoryOutboundBudget

    /**
     * C ABI: `TPipe_Junction_getMemoryPolicyEx(handle, int*, int*)`.
     *
     * Get both the outbound budget and the summary budget.
     *
     * @return Pair of (outboundBudget, summaryBudget) as a long
     *   (outboundBudget in low 32 bits, summaryBudget in high 32 bits).
     */
    fun getMemoryPolicyEx(): Long {
        val lo = _memoryOutboundBudget.toLong() and 0xFFFFFFFFL
        val hi = _memorySummaryBudget.toLong() and 0xFFFFFFFFL
        return lo or (hi shl 32)
    }

    /**
     * C ABI: `TPipe_Junction_enableTracing(handle)`.
     *
     * @return 0 on success; TPIPE_ERR_INTERNAL on failure.
     */
    fun enableTracing(): Int = try {
        junction.enableTracing()
        0
    } catch (e: Exception) {
        -0x0E
    }

    /**
     * C ABI: `TPipe_Junction_disableTracing(handle)`.
     *
     * @return 0 on success; TPIPE_ERR_INTERNAL on failure.
     */
    fun disableTracing(): Int = try {
        junction.disableTracing()
        0
    } catch (e: Exception) {
        -0x0E
    }

    /**
     * C ABI: `TPipe_Junction_getTraceId(handle, buf, bufSize)`.
     *
     * @return The trace ID string, or empty string if tracing is disabled.
     */
    fun getTraceId(): String = junction.getTraceId()

    /**
     * C ABI: `TPipe_Junction_getFailureAnalysis(handle, buf, bufSize)`.
     *
     * Serialize the [com.TTT.Debug.FailureAnalysis] (or null) into a JSON
     * string. Returns "{}" when tracing is disabled.
     *
     * @return JSON string.
     */
    fun getFailureAnalysis(): String {
        val fa = junction.getFailureAnalysis() ?: return "{}"
        return try {
            serialize(fa)
        } catch (e: Exception) {
            "{\"failureReason\":\"${e.message?.replace("\"", "\\\"") ?: "unknown"}\"}"
        }
    }
}
