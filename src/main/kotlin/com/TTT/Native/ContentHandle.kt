package com.TTT.Native

import com.TTT.Pipe.*

/**
 * MultimodalContent handle for the C ABI.
 *
 * Wraps TPipe's MultimodalContent (from src/main/kotlin/Pipe/BinaryContent.kt)
 * with reference counting via the HandleRegistry.
 *
 * MultimodalContent holds:
 * - text: String (the main text content)
 * - control flags: terminate, repeat, pass, skip, jump
 * - error state: last error message (per-handle)
 * - binary content: list of BinaryHandle
 * - model reasoning: optional reasoning trace
 *
 * @param text The main text content
 * @param terminate Signals a critical failure forcing the pipeline to terminate
 * @param repeat If true, the pipe will be called again with this same content
 * @param pass Allows the pipeline to exit early without being considered an error
 * @param skip Allows the reasoning pipe system to be skipped
 * @param jump Optional pipe name to jump to
 * @param errorMessage Error message from last operation
 */
class ContentHandle(
    var text: String = "",
    var terminate: Boolean = false,
    var repeat: Boolean = false,
    var pass: Boolean = false,
    var skip: Boolean = false,
    var jump: String? = null,
    var errorMessage: String? = null
) {
    /** List of binary content attached to this content. */
    val binaryContent: MutableList<BinaryHandle> = mutableListOf()

    /** Optional model reasoning trace. */
    var modelReasoning: String? = null

    /** Context window from TPipe context system. */
    var context: String? = null

    /** MiniBank context object serialized as string. */
    var miniBank: String? = null

    /**
     * Creates a TPipe MultimodalContent from this handle's data.
     *
     * @return MultimodalContent populated from this handle's fields
     */
    fun toMultimodalContent(): MultimodalContent {
        val mc = MultimodalContent(text)
        mc.terminatePipeline = this.terminate
        mc.repeatPipe = this.repeat
        mc.passPipeline = this.pass
        mc.skipReasoningPipe = this.skip
        this.jump?.let { mc.jumpToPipe(it) }
        this.modelReasoning?.let { mc.modelReasoning = it }
        for (bh in binaryContent) {
            mc.addBinary(bh.toBinaryContent())
        }
        return mc
    }

    companion object {
        /**
         * Create a ContentHandle from a TPipe MultimodalContent.
         *
         * @param mc The TPipe MultimodalContent to wrap
         * @return A ContentHandle representing the same content
         */
        fun fromMultimodalContent(mc: MultimodalContent): ContentHandle {
            val ch = ContentHandle(mc.text)
            ch.terminate = mc.terminatePipeline
            ch.repeat = mc.repeatPipe
            ch.pass = mc.passPipeline
            ch.skip = mc.skipReasoningPipe
            ch.jump = mc.getJumpToPipe().ifEmpty { null }
            ch.modelReasoning = mc.modelReasoning.ifEmpty { null }
            for (bc in mc.binaryContent) {
                ch.binaryContent.add(BinaryHandle.fromBinaryContent(bc))
            }
            return ch
        }
    }
}