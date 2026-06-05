package com.TTT.Native

import com.TTT.Pipeline.Connector
import com.TTT.Pipe.MultimodalContent
import kotlinx.coroutines.runBlocking

/**
 * Handle representing a TPipe [Connector] instance.
 *
 * Connector is the conditional branching container that routes a
 * MultimodalContent across one of N registered Pipeline branches keyed
 * by an arbitrary path value. See [com.TTT.Pipeline.Connector] for the
 * full contract.
 *
 * The C ABI exposes only the executable surface (create, init, execute,
 * release, serialize). The DSL's branch registration (`add(key, pipeline)`)
 * requires JVM-side pipeline construction and is not currently reachable
 * from C.
 *
 * @param connector The TPipe Connector instance to wrap.
 */
class ConnectorHandle(
    val connector: Connector
)
{
    /**
     * Initialize the connector. Connector does not have a public init()
     * method, so this is a no-op that always returns success.
     *
     * @return Always 0 (TPIPE_OK).
     */
    fun init(): Int = 0

    /**
     * Execute the connector with the given content. The connector's
     * executeLocal reads the branch path from the content via
     * `content.getConnectorPath()`. The C ABI caller is responsible for
     * setting that path on the input content before calling execute, or
     * for adding branches with a default key. The returned content handle
     * wraps the output MultimodalContent; the C ABI caller is responsible
     * for releasing it.
     *
     * @param inputContent The input content handle.
     * @return A new CONTENT handle wrapping the output MultimodalContent,
     *   or 0 on failure.
     */
    fun execute(inputContent: ContentHandle): Long = try {
        val mc: MultimodalContent = inputContent.toMultimodalContent()
        val output: MultimodalContent = runBlocking { connector.executeLocal(mc) }
        val outputHandle = ContentHandle.fromMultimodalContent(output)
        HandleRegistry.allocate(HandleTypes.CONTENT, outputHandle)
    } catch (e: Exception) {
        0L
    }

    /**
     * Release this connector handle. The actual handle release is
     * performed by [HandleRegistry.release]; this method is a no-op kept
     * for symmetry with the other container handle classes.
     */
    fun release() {
        // No-op: HandleRegistry.release() is the source of truth.
    }

    /**
     * Get a JSON snapshot of the connector's state. The C ABI serialize
     * shim forwards this string to the caller's buffer.
     *
     * @return A JSON object describing the connector handle.
     */
    fun serialize(): String = try {
        val sb = StringBuilder()
        sb.append("{")
        sb.append("\"type\":\"Connector\",")
        sb.append("\"className\":\"${connector::class.simpleName ?: "Connector"}\"")
        sb.append("}")
        sb.toString()
    } catch (e: Exception) {
        "{\"type\":\"Connector\"}"
    }
}
