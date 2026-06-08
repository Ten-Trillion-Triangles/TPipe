package com.TTT.Native

import com.TTT.Enums.ContextWindowSettings
import com.TTT.Enums.SummaryMode
import com.TTT.Pipeline.Manifold
import com.TTT.Pipe.MultimodalContent
import com.TTT.Pipe.Pipe
import kotlinx.coroutines.runBlocking

/**
 * Handle representing a TPipe [Manifold] instance.
 *
 * Manifold is the multi-agent orchestration container that pairs a
 * manager pipeline with N worker pipelines and dispatches tasks in a
 * loop until completion. See [com.TTT.Pipeline.Manifold] for the full
 * contract.
 *
 * The C ABI exposes the executable surface plus the integer/enum
 * configuration methods (setContextWindowSize, setTruncationMethod,
 * setSummaryMode) and the corresponding getters / mirror queries.
 * The DSL's suspend-lambda configuration methods (setValidatorFunction,
 * setTransformationFunction, setFailureFunction, setManifoldInitFunction,
 * setSummaryPipeline, setContextTruncationFunction) require FFI thunks
 * for lambda marshaling, which is tracked separately.
 *
 * @param manifold The TPipe Manifold instance to wrap.
 * @param workers Map of worker name -> Pipe instance registered via addWorker.
 *   The C ABI caller is responsible for creating worker Pipes (via
 *   TPipe_Pipe_create) and registering them on the manifold by name.
 */
class ManifoldHandle(
    val manifold: Manifold,
    /** Map of worker name -> Pipe instance, registered via addWorker. */
    val workers: MutableMap<String, Pipe> = mutableMapOf()
)
{
    //====================================================================
    // Configuration mirror — C-callable view of the integer/enum
    // configuration fields. Updated by setters, read by getters.
    //====================================================================

    /** Mirror of [com.TTT.Pipeline.Manifold]'s context-window override. */
    private var _contextWindowSize: Int = 0

    /** Mirror of [com.TTT.Pipeline.Manifold]'s truncation method. */
    var truncationMethod: ContextWindowSettings = ContextWindowSettings.TruncateBottom

    /** Mirror of [com.TTT.Pipeline.Manifold]'s summary mode. */
    var summaryMode: SummaryMode = SummaryMode.APPEND

    /** Mirror of the configured max-loop-iterations value (-1 = unlimited). */
    var maxLoopIterationsMirror: Int = -1

    /** Mirror of the manager token budget. */
    private var _managerTokenBudget: Int = 0

    //====================================================================
    // Original C ABI surface
    //====================================================================

    /**
     * Initialize the manifold. Wraps the suspend `Manifold.init()` in runBlocking.
     *
     * @return 0 on success; TPIPE_ERR_INTERNAL on failure.
     */
    fun init(): Int = try {
        runBlocking { manifold.init() }
        0
    } catch (e: Exception) {
        -0x0E  // TPIPE_ERR_INTERNAL
    }

    /**
     * Execute the manifold with the given content. Returns a new content
     * handle wrapping the output MultimodalContent. The C ABI caller is
     * responsible for releasing the returned handle.
     *
     * @param inputContent The input content handle.
     * @return A new CONTENT handle wrapping the output MultimodalContent, or 0
     *   on failure.
     */
    fun execute(inputContent: ContentHandle): Long = try {
        val mc: MultimodalContent = inputContent.toMultimodalContent()
        val output: MultimodalContent = runBlocking { manifold.execute(mc) }
        val outputHandle = ContentHandle.fromMultimodalContent(output)
        HandleRegistry.allocate(HandleTypes.CONTENT, outputHandle)
    } catch (e: Exception) {
        0L
    }

    /**
     * Add a worker to the manifold under the given name. The C ABI caller
     * must create the worker Pipe first (via TPipe_Pipe_create) and pass
     * its handle here.
     *
     * The full DSL addWorkerPipeline takes a suspend lambda, descriptor, and
     * P2P requirements; the C ABI captures only the name -> Pipe mapping for
     * later wiring via the manager-loop dispatch.
     *
     * @param name Worker identifier (used by the manager when dispatching).
     * @param pipe The Pipe instance backing this worker.
     * @return 0 on success; TPIPE_ERR_INTERNAL on failure.
     */
    fun addWorker(name: String, pipe: Pipe): Int = try {
        workers[name] = pipe
        0
    } catch (e: Exception) {
        -0x0E
    }

    /**
     * Get the count of registered workers.
     *
     * @return Number of workers currently registered with this manifold.
     */
    fun getWorkerCount(): Int = workers.size

    /**
     * Set the manifold's maximum loop iterations. C ABI int -> Manifold.
     * The C ABI convention: 0 or -1 means "unlimited" (null in Kotlin).
     *
     * @param limit Maximum loop iterations (0 / -1 = unlimited; positive = limit).
     * @return 0 on success; TPIPE_ERR_INTERNAL on failure.
     */
    fun setMaxLoopIterations(limit: Int): Int = try {
        val kotlinLimit: Int? = if (limit <= 0) null else limit
        manifold.setMaxLoopIterations(kotlinLimit)
        this.maxLoopIterationsMirror = limit
        0
    } catch (e: Exception) {
        -0x0E
    }

    /**
     * Get a JSON snapshot of the manifold's state. The C ABI serialize
     * shim forwards this string to the caller's buffer.
     *
     * @return A JSON string with worker count, worker names, and the
     *   mirrored configuration.
     */
    fun serialize(): String = try {
        val sb = StringBuilder()
        sb.append("{")
        sb.append("\"workerCount\":${workers.size},")
        sb.append("\"workerNames\":[")
        sb.append(workers.keys.joinToString(",") { "\"$it\"" })
        sb.append("],")
        sb.append("\"contextWindowSize\":$_contextWindowSize,")
        sb.append("\"truncationMethod\":\"$truncationMethod\",")
        sb.append("\"summaryMode\":\"$summaryMode\",")
        sb.append("\"maxLoopIterations\":$maxLoopIterationsMirror,")
        sb.append("\"managerTokenBudget\":$_managerTokenBudget")
        sb.append("}")
        sb.toString()
    } catch (e: Exception) {
        "{}"
    }

    //====================================================================
    // Cycle 3 — C ABI configuration surface
    //====================================================================

    /**
     * C ABI: `TPipe_Manifold_setContextWindowSize(handle, size)`.
     *
     * @param size Desired context window size (must be >= 0).
     * @return 0 on success; TPIPE_ERR_INVALID_ARGUMENT on negative.
     */
    fun setContextWindowSize(size: Int): Int {
        if (size < 0) return -0x04
        return try {
            manifold.setContextWindowSize(size)
            this._contextWindowSize = size
            0
        } catch (e: Exception) {
            -0x0E
        }
    }

    /**
     * C ABI: `TPipe_Manifold_getContextWindowSize(handle, int*)`.
     *
     * @return The configured context-window size (0 if unset).
     */
    fun getContextWindowSize(): Int = _contextWindowSize

    /**
     * C ABI: `TPipe_Manifold_setTruncationMethod(handle, method)`.
     *
     * @param method Truncation method ordinal:
     *   0 = TruncateTop, 1 = TruncateBottom, 2 = TruncateMiddle.
     * @return 0 on success; TPIPE_ERR_INVALID_ARGUMENT on unknown ordinal.
     */
    fun setTruncationMethod(method: Int): Int {
        val m = enumValues<ContextWindowSettings>().getOrNull(method)
            ?: return -0x04
        return try {
            manifold.setTruncationMethod(m)
            this.truncationMethod = m
            0
        } catch (e: Exception) {
            -0x0E
        }
    }

    /**
     * C ABI: `TPipe_Manifold_getTruncationMethod(handle, int*)`.
     *
     * @return The configured truncation method ordinal.
     */
    fun getTruncationMethod(): Int = truncationMethod.ordinal

    /**
     * C ABI: `TPipe_Manifold_setSummaryMode(handle, mode)`.
     *
     * @param mode Summary mode ordinal:
     *   0 = APPEND, 1 = REGENERATE.
     * @return 0 on success; TPIPE_ERR_INVALID_ARGUMENT on unknown ordinal.
     */
    fun setSummaryMode(mode: Int): Int {
        val m = enumValues<SummaryMode>().getOrNull(mode) ?: return -0x04
        return try {
            manifold.setSummaryMode(m)
            this.summaryMode = m
            0
        } catch (e: Exception) {
            -0x0E
        }
    }

    /**
     * C ABI: `TPipe_Manifold_getSummaryMode(handle, int*)`.
     *
     * @return The configured summary mode ordinal.
     */
    fun getSummaryMode(): Int = summaryMode.ordinal

    /**
     * C ABI: `TPipe_Manifold_getMaxLoopIterations(handle, int*)`.
     *
     * @return The configured max loop iterations, or -1 if unlimited.
     */
    fun getMaxLoopIterations(): Int = maxLoopIterationsMirror

    /**
     * C ABI: `TPipe_Manifold_hasLoopLimit(handle, int*)`.
     *
     * @return 1 if a positive max-loop-iterations is configured, 0 if not.
     */
    fun hasLoopLimit(): Int = if (maxLoopIterationsMirror > 0) 1 else 0

    /**
     * C ABI: `TPipe_Manifold_getWorkerPipelines(handle, buf, bufSize)`.
     *
     * Comma-separated list of registered worker names.
     *
     * @return Worker names joined by ",", or "" if no workers.
     */
    fun getWorkerPipelines(): String = workers.keys.joinToString(",")

    /**
     * C ABI: `TPipe_Manifold_setManagerTokenBudget(handle, budget)`.
     *
     * @param budget Token budget for the manager pipeline.
     * @return 0 on success; TPIPE_ERR_INVALID_ARGUMENT on negative.
     */
    fun setManagerTokenBudget(budget: Int): Int {
        if (budget < 0) return -0x04
        return try {
            // The JVM setManagerTokenBudget takes a TokenBudgetSettings
            // object. Build a minimal one from the int budget.
            val tbs = com.TTT.Pipe.TokenBudgetSettings(userPromptSize = budget)
            manifold.setManagerTokenBudget(tbs)
            this._managerTokenBudget = budget
            0
        } catch (e: Exception) {
            -0x0E
        }
    }

    /**
     * C ABI: `TPipe_Manifold_getManagerTokenBudget(handle, int*)`.
     *
     * @return The configured manager token budget (user-prompt size), or 0
     *   if unset.
     */
    fun getManagerTokenBudget(): Int = _managerTokenBudget

    /**
     * C ABI: `TPipe_Manifold_getManagerPipeline(handle, int*)`.
     *
     * Returns 1 if a manager pipeline has been registered, 0 otherwise.
     * The manager pipeline is constructed via the JVM DSL and the C ABI
     * doesn't have a setter for it (would require Pipeline object wrapping).
     *
     * @return 1 if the manager pipeline is set, 0 otherwise.
     */
    fun getManagerPipeline(): Int =
        if (manifold.getManagerPipeline().getPipes().isNotEmpty()) 1 else 0
}
