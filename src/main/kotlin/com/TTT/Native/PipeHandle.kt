package com.TTT.Native

import com.TTT.Pipe.Pipe
import com.TTT.Pipe.MultimodalContent
import com.TTT.Native.EnumMappings.ProviderName
import com.TTT.Native.EnumMappings.OperationStatus
import kotlinx.coroutines.runBlocking

/**
 * Handle representing a TPipe Pipe instance.
 *
 * A Pipe is the basic unit of LLM interaction — a single model call with
 * a MultimodalContent input and a MultimodalContent output.
 *
 * PipeHandle wraps a specific Pipe instance (e.g., BedrockPipe or OllamaPipe)
 * and provides the C ABI execute methods.
 */
class PipeHandle(
    val pipe: Pipe,
    val settings: PipeSettingsHandle
) {
    /**
     * Execute the pipe synchronously with the given content.
     *
     * @param inputContent The input MultimodalContent handle
     * @return Result containing output MultimodalContent handle or error
     */
    fun execute(inputContent: ContentHandle): Result {
        return try {
            val mc = inputContent.toMultimodalContent()
            val outputMc = runBlocking { pipe.execute(mc) }
            val outputHandle = ContentHandle.fromMultimodalContent(outputMc)
            val handleId = HandleRegistry.allocate(HandleTypes.CONTENT, outputHandle)
            Result.Success(handleId)
        } catch (e: Exception) {
            Result.Error(e.message ?: "Unknown error")
        }
    }

    /**
     * Execute the pipe asynchronously.
     *
     * Returns an OperationHandle that can be polled for completion.
     * For now, executes synchronously and wraps result in async handle.
     * Real async would use coroutines or thread pool.
     */
    fun executeAsync(inputContent: ContentHandle): Result {
        return try {
            val mc = inputContent.toMultimodalContent()
            val outputMc = runBlocking { pipe.execute(mc) }
            val outputHandle = ContentHandle.fromMultimodalContent(outputMc)
            val handleId = HandleRegistry.allocate(HandleTypes.CONTENT, outputHandle)
            val opHandle = OperationHandle(OperationStatus.COMPLETE, handleId)
            val opId = HandleRegistry.allocate(HandleTypes.OPERATION, opHandle)
            Result.Success(opId)
        } catch (e: Exception) {
            val opHandle = OperationHandle(OperationStatus.FAILED, 0L, e.message ?: "Unknown error")
            val opId = HandleRegistry.allocate(HandleTypes.OPERATION, opHandle)
            Result.Success(opId)
        }
    }

    /**
     * Gets the model identifier for this pipe.
     */
    fun getModel(): String = settings.model

    /**
     * Gets the region for this pipe (AWS region, etc.).
     */
    fun getRegion(): String = settings.region

    /**
     * Gets the provider name (AWS, Ollama, etc.).
     */
    fun getProvider(): String = settings.providerName

    //==========================================================================
    // Cycle 4 — Pipe prompt + sampling surface
    //
    // Each handle method mirrors a public JVM method on [com.TTT.Pipe.Pipe] so
    // the C ABI can drive every prompt/sampling setter exposed by Pipe.kt.
    //
    // `setSeed` accepts a Long at the C ABI boundary; the documented sentinel
    // `Long.MIN_VALUE` clears the seed (matches the JVM-side `Int?` null form).
    //
    // `setStopSequences` accepts a single C string and splits it on the `\n`
    // character to produce a `List<String>` for the JVM side. Empty input
    // yields an empty list, matching the JVM-side default.
    //==========================================================================

    /**
     * C ABI: `TPipe_Pipe_setSystemPrompt(handle, text)`.
     * Delegates to [Pipe.setSystemPrompt].
     */
    fun setSystemPrompt(text: String): Int
    {
        return try {
            pipe.setSystemPrompt(text)
            0
        }
        catch (e: Exception)
        {
            -0x01  // TPIPE_ERR_INTERNAL
        }
    }

    /**
     * C ABI: `TPipe_Pipe_getSystemPrompt(handle, buf, bufSize)`.
     * Writes the JVM [Pipe.getSystemPromptText] result into the caller's
     * UTF-8 buffer. The handle method itself returns the number of bytes
     * written (or a negative error code); the bridge layer copies bytes
     * into the caller's buffer at the C boundary.
     */
    fun getSystemPrompt(): String = pipe.getSystemPromptText()

    /**
     * C ABI: `TPipe_Pipe_setUserPrompt(handle, text)`.
     * Delegates to [Pipe.setUserPrompt].
     */
    fun setUserPrompt(text: String): Int
    {
        return try {
            pipe.setUserPrompt(text)
            0
        }
        catch (e: Exception)
        {
            -0x01
        }
    }

    /**
     * C ABI: `TPipe_Pipe_setMiddlePrompt(handle, text)`.
     * Delegates to [Pipe.setMiddlePrompt].
     */
    fun setMiddlePrompt(text: String): Int
    {
        return try {
            pipe.setMiddlePrompt(text)
            0
        }
        catch (e: Exception)
        {
            -0x01
        }
    }

    /**
     * C ABI: `TPipe_Pipe_setFooterPrompt(handle, text)`.
     * Delegates to [Pipe.setFooterPrompt].
     */
    fun setFooterPrompt(text: String): Int
    {
        return try {
            pipe.setFooterPrompt(text)
            0
        }
        catch (e: Exception)
        {
            -0x01
        }
    }

    /**
     * C ABI: `TPipe_Pipe_setTopP(handle, doubleBits)`.
     * `doubleBits` is the raw long bits of a [Double] (IEEE 754).
     * Delegates to [Pipe.setTopP].
     */
    fun setTopP(doubleBits: Long): Int
    {
        return try {
            val v = Double.fromBits(doubleBits)
            pipe.setTopP(v)
            0
        }
        catch (e: Exception)
        {
            -0x01
        }
    }

    /**
     * C ABI: `TPipe_Pipe_setTopK(handle, top)`.
     * Delegates to [Pipe.setTopK].
     */
    fun setTopK(top: Int): Int
    {
        return try {
            pipe.setTopK(top)
            0
        }
        catch (e: Exception)
        {
            -0x01
        }
    }

    /**
     * C ABI: `TPipe_Pipe_setMaxTokens(handle, max)`.
     * Delegates to [Pipe.setMaxTokens].
     */
    fun setMaxTokens(max: Int): Int
    {
        return try {
            pipe.setMaxTokens(max)
            0
        }
        catch (e: Exception)
        {
            -0x01
        }
    }

    /**
     * C ABI: `TPipe_Pipe_setSeed(handle, seedBits)`.
     * `Long.MIN_VALUE` is the documented sentinel that clears the seed
     * (maps to the JVM-side `Int?` null). All other values take the lower
     * 32 bits as the Int seed. Delegates to [Pipe.setSeed].
     */
    fun setSeed(seedBits: Long): Int
    {
        return try {
            val s: Int? = if (seedBits == Long.MIN_VALUE) null else seedBits.toInt()
            pipe.setSeed(s)
            0
        }
        catch (e: Exception)
        {
            -0x01
        }
    }

    /**
     * C ABI: `TPipe_Pipe_setStopSequences(handle, text)`.
     * Splits the input text on the newline character. Empty input yields
     * an empty list, matching the JVM-side default for [Pipe.setStopSequences].
     */
    fun setStopSequences(text: String): Int
    {
        return try {
            val list = if (text.isEmpty()) emptyList() else text.split("\n")
            pipe.setStopSequences(list)
            0
        }
        catch (e: Exception)
        {
            -0x01
        }
    }

    //==========================================================================
    // Cycle 5 — Pipe JSON / multimodal / binary surface
    //
    // Each handle method mirrors a public JVM method on [com.TTT.Pipe.Pipe] so
    // the C ABI can configure the JSON schema input/output, the multimodal
    // content input pipeline, the merged PCP+JSON instructions, and the
    // per-pipe input cache.
    //
    // `getCachedInput` returns the allocated Content handle id (a positive
    // Long) on success or a negative error code. The bridge layer is
    // responsible for the actual HandleRegistry.allocate() call.
    //==========================================================================

    /**
     * C ABI: `TPipe_Pipe_setJsonInput(handle, json)`.
     * Delegates to [Pipe.setJsonInput].
     */
    fun setJsonInput(json: String): Int
    {
        return try {
            pipe.setJsonInput(json)
            0
        }
        catch (e: Exception)
        {
            -0x01
        }
    }

    /**
     * C ABI: `TPipe_Pipe_setJsonOutput(handle, json)`.
     * Delegates to [Pipe.setJsonOutput].
     */
    fun setJsonOutput(json: String): Int
    {
        return try {
            pipe.setJsonOutput(json)
            0
        }
        catch (e: Exception)
        {
            -0x01
        }
    }

    /**
     * C ABI: `TPipe_Pipe_setJsonInputInstructions(handle, text)`.
     * Delegates to [Pipe.setJsonInputInstructions].
     */
    fun setJsonInputInstructions(text: String): Int
    {
        return try {
            pipe.setJsonInputInstructions(text)
            0
        }
        catch (e: Exception)
        {
            -0x01
        }
    }

    /**
     * C ABI: `TPipe_Pipe_setJsonOutputInstructions(handle, text)`.
     * Delegates to [Pipe.setJsonOutputInstructions].
     */
    fun setJsonOutputInstructions(text: String): Int
    {
        return try {
            pipe.setJsonOutputInstructions(text)
            0
        }
        catch (e: Exception)
        {
            -0x01
        }
    }

    /**
     * C ABI: `TPipe_Pipe_requireJsonPromptInjection(handle, stripExternalText)`.
     * `stripExternalText` is 0 or 1. Delegates to [Pipe.requireJsonPromptInjection].
     */
    fun requireJsonPromptInjection(stripExternalText: Int): Int
    {
        return try {
            pipe.requireJsonPromptInjection(stripExternalText != 0)
            0
        }
        catch (e: Exception)
        {
            -0x01
        }
    }

    /**
     * C ABI: `TPipe_Pipe_setMultimodalInput(handle, content)`.
     * The bridge layer is responsible for resolving the content handle and
     * building the [MultimodalContent] before this method is called.
     */
    fun setMultimodalInput(content: MultimodalContent): Int
    {
        return try {
            pipe.setMultimodalInput(content)
            0
        }
        catch (e: Exception)
        {
            -0x01
        }
    }

    /**
     * C ABI: `TPipe_Pipe_getCachedInput(handle)` (returns Content handle id).
     * Reads the cached input via [Pipe.getCachedInput] and allocates a new
     * Content handle wrapping it. Returns the positive handle id on success
     * or a negative error code.
     */
    fun getCachedInput(): Long
    {
        return try {
            val mc = pipe.getCachedInput()
            val ch = ContentHandle.fromMultimodalContent(mc)
            HandleRegistry.allocate(HandleTypes.CONTENT, ch)
        }
        catch (e: Exception)
        {
            -0x01L
        }
    }

    /**
     * C ABI: `TPipe_Pipe_setMergedPcpJsonInstructions(handle, text)`.
     * Delegates to [Pipe.setMergedPcpJsonInstructions].
     */
    fun setMergedPcpJsonInstructions(text: String): Int
    {
        return try {
            pipe.setMergedPcpJsonInstructions(text)
            0
        }
        catch (e: Exception)
        {
            -0x01
        }
    }

    /**
     * C ABI: `TPipe_Pipe_cacheInput(handle)`.
     * Toggles the per-pipe input cache flag. Delegates to [Pipe.cacheInput].
     */
    fun cacheInput(): Int
    {
        return try {
            pipe.cacheInput()
            0
        }
        catch (e: Exception)
        {
            -0x01
        }
    }

    /**
     * C ABI: `TPipe_Pipe_forceCacheInput(handle, content)`.
     * The bridge layer is responsible for resolving the content handle and
     * building the [MultimodalContent] before this method is called.
     */
    fun forceCacheInput(content: MultimodalContent): Int
    {
        return try {
            pipe.forceCacheInput(content)
            0
        }
        catch (e: Exception)
        {
            -0x01
        }
    }

    //==========================================================================
    // Cycle 6 — Pipe tracing / compression / token-budget surface
    //
    // Tracing methods: enable/disable + add/remove/clear trace ids +
    // getActiveTraceId (the first id in the active set, or empty if none).
    // Compression: enableSemanticCompression and enableSemanticDecompression
    // flip the corresponding flags.
    // Token budget: enableMaxTokenOverflow, isAutoTruncateContextEnabled.
    //==========================================================================

    /**
     * C ABI: `TPipe_Pipe_enableTracing(handle)`.
     * Delegates to [Pipe.enableTracing] using the default [com.TTT.Debug.TraceConfig].
     */
    fun enableTracing(): Int
    {
        return try
        {
            pipe.enableTracing()
            0
        }
        catch (e: Exception)
        {
            -0x01
        }
    }

    /**
     * C ABI: `TPipe_Pipe_disableTracing(handle)`.
     * Delegates to [Pipe.disableTracing].
     */
    fun disableTracing(): Int
    {
        return try
        {
            pipe.disableTracing()
            0
        }
        catch (e: Exception)
        {
            -0x01
        }
    }

    /**
     * C ABI: `TPipe_Pipe_addTraceId(handle, id)`.
     * Delegates to [Pipe.addTraceId].
     */
    fun addTraceId(id: String): Int
    {
        return try
        {
            pipe.addTraceId(id)
            0
        }
        catch (e: Exception)
        {
            -0x01
        }
    }

    /**
     * C ABI: `TPipe_Pipe_removeTraceId(handle, id)`.
     * Delegates to [Pipe.removeTraceId].
     */
    fun removeTraceId(id: String): Int
    {
        return try
        {
            pipe.removeTraceId(id)
            0
        }
        catch (e: Exception)
        {
            -0x01
        }
    }

    /**
     * C ABI: `TPipe_Pipe_clearTraceIds(handle)`.
     * Delegates to [Pipe.clearTraceIds].
     */
    fun clearTraceIds(): Int
    {
        return try
        {
            pipe.clearTraceIds()
            0
        }
        catch (e: Exception)
        {
            -0x01
        }
    }

    /**
     * C ABI: `TPipe_Pipe_getActiveTraceId(handle, buf, bufSize)`.
     * Writes the first id in the active set into the caller's UTF-8 buffer
     * (empty string when no ids are active). The bridge layer is responsible
     * for the actual buffer copy at the C boundary.
     */
    fun getActiveTraceId(): String
    {
        return try
        {
            pipe.currentPipelineId ?: ""
        }
        catch (e: Exception)
        {
            ""
        }
    }

    /**
     * C ABI: `TPipe_Pipe_enableSemanticCompression(handle)`.
     * Delegates to [Pipe.enableSemanticCompression].
     */
    fun enableSemanticCompression(): Int
    {
        return try
        {
            pipe.enableSemanticCompression()
            0
        }
        catch (e: Exception)
        {
            -0x01
        }
    }

    /**
     * C ABI: `TPipe_Pipe_enableSemanticDecompression(handle)`.
     * Delegates to [Pipe.enableSemanticDecompression].
     */
    fun enableSemanticDecompression(): Int
    {
        return try
        {
            pipe.enableSemanticDecompression()
            0
        }
        catch (e: Exception)
        {
            -0x01
        }
    }

    /**
     * C ABI: `TPipe_Pipe_enableMaxTokenOverflow(handle)`.
     * Delegates to [Pipe.enableMaxTokenOverflow].
     */
    fun enableMaxTokenOverflow(): Int
    {
        return try
        {
            pipe.enableMaxTokenOverflow()
            0
        }
        catch (e: Exception)
        {
            -0x01
        }
    }

    /**
     * C ABI: `TPipe_Pipe_isAutoTruncateContextEnabled(handle)` (writes 0/1 to int* out).
     * Delegates to [Pipe.isAutoTruncateContextEnabled].
     */
    fun isAutoTruncateContextEnabled(): Int
    {
        return try
        {
            if (pipe.isAutoTruncateContextEnabled()) 1 else 0
        }
        catch (e: Exception)
        {
            -0x01
        }
    }

    //==========================================================================
    // Cycle 7 — Pipe hooks (DSL suspend-lambda stubs) + P2P/PCP/ContextBank
    //
    // All 10 of these methods are UNSUPPORTED stubs. The JVM-side setters
    // they wrap take either a `suspend` lambda or an object-typed
    // parameter (PcpContext, MemoryIntrospectionConfig) that the C ABI
    // cannot accept directly.
    //
    // Per the original plan's "Out of Scope" policy, these return
    // TPIPE_ERR_NOT_IMPLEMENTED (-0x10). Functional support requires the
    // future vtable/indirection cycle, which will let the language
    // wrapper register function references that the JVM calls back out
    // to fetch schemas / invoke suspend-lambda bodies.
    //
    // Each stub still respects the null-handle check at the bridge layer
    // (so callers get -0x03 for a missing handle, not a confusing -0x10).
    //==========================================================================

    /**
     * C ABI: `TPipe_Pipe_setRetryFunction(handle)` (DSL-only stub).
     * Wraps [Pipe.setRetryFunction] (Kotlin signature: takes a
     * `suspend (Pipe, MultimodalContent) -> Boolean` lambda). The C ABI
     * cannot accept a function reference; the vtable indirection cycle
     * will provide a way to register a callback from the language
     * wrapper. Always returns `TPIPE_ERR_NOT_IMPLEMENTED` (-0x10).
     */
    fun setRetryFunction(): Int
    {
        return -0x10
    }

    /**
     * C ABI: `TPipe_Pipe_setExceptionFunction(handle)` (DSL-only stub).
     * Wraps [Pipe.setExceptionFunction] (Kotlin signature: takes a
     * `suspend (MultimodalContent, Throwable) -> Unit` lambda).
     * Always returns `TPIPE_ERR_NOT_IMPLEMENTED` (-0x10).
     */
    fun setExceptionFunction(): Int
    {
        return -0x10
    }

    /**
     * C ABI: `TPipe_Pipe_setStringValidatorFunction(handle)` (DSL-only stub).
     * Wraps [Pipe.setStringValidatorFunction] (Kotlin signature: takes
     * a `(String) -> Boolean` lambda). Always returns
     * `TPIPE_ERR_NOT_IMPLEMENTED` (-0x10).
     */
    fun setStringValidatorFunction(): Int
    {
        return -0x10
    }

    /**
     * C ABI: `TPipe_Pipe_setTransformationFunction(handle)` (DSL-only stub).
     * Wraps [Pipe.setTransformationFunction] (Kotlin signature: takes a
     * `suspend (MultimodalContent) -> MultimodalContent` lambda).
     * Always returns `TPIPE_ERR_NOT_IMPLEMENTED` (-0x10).
     */
    fun setTransformationFunction(): Int
    {
        return -0x10
    }

    /**
     * C ABI: `TPipe_Pipe_setPreInitFunction(handle)` (DSL-only stub).
     * Wraps [Pipe.setPreInitFunction] (Kotlin signature: takes a
     * `suspend (MultimodalContent) -> Unit` lambda). Always returns
     * `TPIPE_ERR_NOT_IMPLEMENTED` (-0x10).
     */
    fun setPreInitFunction(): Int
    {
        return -0x10
    }

    /**
     * C ABI: `TPipe_Pipe_setPreValidationFunction(handle)` (DSL-only stub).
     * Wraps [Pipe.setPreValidationFunction] (Kotlin signature: takes a
     * `suspend (ContextWindow, MultimodalContent?) -> ContextWindow`
     * lambda). Always returns `TPIPE_ERR_NOT_IMPLEMENTED` (-0x10).
     */
    fun setPreValidationFunction(): Int
    {
        return -0x10
    }

    /**
     * C ABI: `TPipe_Pipe_setPreInvokeFunction(handle)` (DSL-only stub).
     * Wraps [Pipe.setPreInvokeFunction] (Kotlin signature: takes a
     * `suspend (MultimodalContent) -> Boolean` lambda). Always returns
     * `TPIPE_ERR_NOT_IMPLEMENTED` (-0x10).
     */
    fun setPreInvokeFunction(): Int
    {
        return -0x10
    }

    /**
     * C ABI: `TPipe_Pipe_setPostGenerateFunction(handle)` (DSL-only stub).
     * Wraps [Pipe.setPostGenerateFunction] (Kotlin signature: takes a
     * `suspend (MultimodalContent) -> Unit` lambda). Always returns
     * `TPIPE_ERR_NOT_IMPLEMENTED` (-0x10).
     */
    fun setPostGenerateFunction(): Int
    {
        return -0x10
    }

    /**
     * C ABI: `TPipe_Pipe_setPcPContext(handle)` (object-typed stub).
     * Wraps [Pipe.setPcPContext] (Kotlin signature: takes a
     * [com.TTT.PipeContextProtocol.PcpContext] object). The C ABI
     * cannot accept a typed object reference; the vtable indirection
     * cycle will provide a way to register a context from the language
     * wrapper. Always returns `TPIPE_ERR_NOT_IMPLEMENTED` (-0x10).
     */
    fun setPcPContext(): Int
    {
        return -0x10
    }

    /**
     * C ABI: `TPipe_Pipe_enableMemoryIntrospection(handle)` (object-typed stub).
     * Wraps [Pipe.enableMemoryIntrospection] (Kotlin signature: takes a
     * [com.TTT.Context.MemoryIntrospectionConfig] object). The C ABI
     * cannot accept a typed object reference; the vtable indirection
     * cycle will provide a way to register a config from the language
     * wrapper. Always returns `TPIPE_ERR_NOT_IMPLEMENTED` (-0x10).
     */
    fun enableMemoryIntrospection(): Int
    {
        return -0x10
    }

    sealed class Result {
        data class Success(val handleId: Long) : Result()
        data class Error(val message: String) : Result()
    }
}
