package com.TTT.Native

import com.TTT.Pipeline.Manifold
import com.TTT.Pipe.MultimodalContent
import com.TTT.Pipe.Pipe
import kotlinx.coroutines.runBlocking

/**
 * Handle representing a TPipe [Manifold] instance.
 *
 * Manifold is the multi-agent orchestration container that pairs a manager
 * pipeline with N worker pipelines and dispatches tasks in a loop until
 * completion. See [com.TTT.Pipeline.Manifold] for the full contract.
 *
 * The C ABI exposes only the executable surface (create, init, execute,
 * addWorker-by-name, getWorkerCount, setMaxLoopIterations, serialize, release).
 * The DSL's suspend-lambda configuration methods require FFI thunks for
 * lambda marshaling, which is tracked separately.
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
     *
     * @param limit Maximum loop iterations (null=unlimited is not representable
     *   in the C ABI; the C ABI treats 0 as the default).
     * @return 0 on success; TPIPE_ERR_INTERNAL on failure.
     */
    fun setMaxLoopIterations(limit: Int): Int = try {
        manifold.setMaxLoopIterations(limit)
        0
    } catch (e: Exception) {
        -0x0E
    }

    /**
     * Get a JSON snapshot of the manifold's state. The C ABI serialize shim
     * forwards this string to the caller's buffer.
     *
     * @return A JSON string with worker count and worker names.
     */
    fun serialize(): String = try {
        val sb = StringBuilder()
        sb.append("{")
        sb.append("\"workerCount\":${workers.size},")
        sb.append("\"workerNames\":[")
        sb.append(workers.keys.joinToString(",") { "\"$it\"" })
        sb.append("]")
        sb.append("}")
        sb.toString()
    } catch (e: Exception) {
        "{}"
    }
}
