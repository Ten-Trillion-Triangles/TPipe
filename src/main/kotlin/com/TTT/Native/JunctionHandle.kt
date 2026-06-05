package com.TTT.Native

import com.TTT.Pipeline.Junction
import com.TTT.Pipe.MultimodalContent
import kotlinx.coroutines.runBlocking

/**
 * Handle representing a TPipe [Junction] instance.
 *
 * Junction is the multi-participant discussion harness that pairs a
 * moderator P2PInterface with N participant P2PInterfaces and runs
 * rounds until a decision is reached. See [com.TTT.Pipeline.Junction]
 * for the full contract.
 *
 * The C ABI exposes only the executable surface (create, init, execute,
 * release, serialize). The DSL's suspend-lambda configuration methods
 * (addModerator, addParticipant, setWorkflowRecipe, etc.) require FFI
 * thunks for lambda marshaling and are not currently reachable from C.
 *
 * @param junction The TPipe Junction instance to wrap.
 */
class JunctionHandle(
    val junction: Junction
)
{
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
     * @return A JSON object describing the junction handle.
     */
    fun serialize(): String = try {
        val sb = StringBuilder()
        sb.append("{")
        sb.append("\"type\":\"Junction\",")
        sb.append("\"className\":\"${junction::class.simpleName ?: "Junction"}\"")
        sb.append("}")
        sb.toString()
    } catch (e: Exception) {
        "{\"type\":\"Junction\"}"
    }
}
