package com.TTT.Native

import com.TTT.Pipeline.Splitter
import com.TTT.Pipe.MultimodalContent
import kotlinx.coroutines.runBlocking

/**
 * Handle representing a TPipe [Splitter] instance.
 *
 * Splitter is the parallel execution container that fans a single
 * MultimodalContent out to N registered Pipeline branches, each of
 * which runs asynchronously. See [com.TTT.Pipeline.Splitter] for the
 * full contract.
 *
 * The C ABI exposes only the executable surface (create, init, execute,
 * release, serialize). The DSL's branch registration (`addPipeline`,
 * `addContent`) requires JVM-side pipeline construction and is not
 * currently reachable from C.
 *
 * @param splitter The TPipe Splitter instance to wrap.
 */
class SplitterHandle(
    val splitter: Splitter
)
{
    /**
     * Initialize the splitter. Wraps the suspend `Splitter.init()` in
     * runBlocking. Note that the splitter requires content and pipelines
     * to be registered first; the C ABI caller is responsible for
     * configuring the splitter via internal JVM APIs before invoking the
     * init shim.
     *
     * @return 0 on success; TPIPE_ERR_INTERNAL on failure.
     */
    fun init(): Int = try {
        runBlocking { splitter.init() }
        0
    } catch (e: Exception) {
        -0x0E  // TPIPE_ERR_INTERNAL
    }

    /**
     * Execute the splitter with the given content. The splitter's
     * executeLocal fans the content out to all bound pipelines in
     * parallel and returns an aggregated MultimodalContent. The C ABI
     * caller is responsible for releasing the returned handle.
     *
     * @param inputContent The input content handle.
     * @return A new CONTENT handle wrapping the output MultimodalContent,
     *   or 0 on failure.
     */
    fun execute(inputContent: ContentHandle): Long = try {
        val mc: MultimodalContent = inputContent.toMultimodalContent()
        val output: MultimodalContent = runBlocking { splitter.executeLocal(mc) }
        val outputHandle = ContentHandle.fromMultimodalContent(output)
        HandleRegistry.allocate(HandleTypes.CONTENT, outputHandle)
    } catch (e: Exception) {
        0L
    }

    /**
     * Release this splitter handle. The actual handle release is
     * performed by [HandleRegistry.release]; this method is a no-op kept
     * for symmetry with the other container handle classes.
     */
    fun release() {
        // No-op: HandleRegistry.release() is the source of truth.
    }

    /**
     * Get a JSON snapshot of the splitter's state. The C ABI serialize
     * shim forwards this string to the caller's buffer.
     *
     * @return A JSON object describing the splitter handle.
     */
    fun serialize(): String = try {
        val sb = StringBuilder()
        sb.append("{")
        sb.append("\"type\":\"Splitter\",")
        sb.append("\"className\":\"${splitter::class.simpleName ?: "Splitter"}\"")
        sb.append("}")
        sb.toString()
    } catch (e: Exception) {
        "{\"type\":\"Splitter\"}"
    }
}
