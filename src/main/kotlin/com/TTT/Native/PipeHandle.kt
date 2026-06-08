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
    fun setSystemPrompt(text: String): Int {
        return try {
            pipe.setSystemPrompt(text)
            0
        } catch (e: Exception) {
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
    fun setUserPrompt(text: String): Int {
        return try {
            pipe.setUserPrompt(text)
            0
        } catch (e: Exception) {
            -0x01
        }
    }

    /**
     * C ABI: `TPipe_Pipe_setMiddlePrompt(handle, text)`.
     * Delegates to [Pipe.setMiddlePrompt].
     */
    fun setMiddlePrompt(text: String): Int {
        return try {
            pipe.setMiddlePrompt(text)
            0
        } catch (e: Exception) {
            -0x01
        }
    }

    /**
     * C ABI: `TPipe_Pipe_setFooterPrompt(handle, text)`.
     * Delegates to [Pipe.setFooterPrompt].
     */
    fun setFooterPrompt(text: String): Int {
        return try {
            pipe.setFooterPrompt(text)
            0
        } catch (e: Exception) {
            -0x01
        }
    }

    /**
     * C ABI: `TPipe_Pipe_setTopP(handle, doubleBits)`.
     * `doubleBits` is the raw long bits of a [Double] (IEEE 754).
     * Delegates to [Pipe.setTopP].
     */
    fun setTopP(doubleBits: Long): Int {
        return try {
            val v = Double.fromBits(doubleBits)
            pipe.setTopP(v)
            0
        } catch (e: Exception) {
            -0x01
        }
    }

    /**
     * C ABI: `TPipe_Pipe_setTopK(handle, top)`.
     * Delegates to [Pipe.setTopK].
     */
    fun setTopK(top: Int): Int {
        return try {
            pipe.setTopK(top)
            0
        } catch (e: Exception) {
            -0x01
        }
    }

    /**
     * C ABI: `TPipe_Pipe_setMaxTokens(handle, max)`.
     * Delegates to [Pipe.setMaxTokens].
     */
    fun setMaxTokens(max: Int): Int {
        return try {
            pipe.setMaxTokens(max)
            0
        } catch (e: Exception) {
            -0x01
        }
    }

    /**
     * C ABI: `TPipe_Pipe_setSeed(handle, seedBits)`.
     * `Long.MIN_VALUE` is the documented sentinel that clears the seed
     * (maps to the JVM-side `Int?` null). All other values take the lower
     * 32 bits as the Int seed. Delegates to [Pipe.setSeed].
     */
    fun setSeed(seedBits: Long): Int {
        return try {
            val s: Int? = if (seedBits == Long.MIN_VALUE) null else seedBits.toInt()
            pipe.setSeed(s)
            0
        } catch (e: Exception) {
            -0x01
        }
    }

    /**
     * C ABI: `TPipe_Pipe_setStopSequences(handle, text)`.
     * Splits the input text on the newline character. Empty input yields
     * an empty list, matching the JVM-side default for [Pipe.setStopSequences].
     */
    fun setStopSequences(text: String): Int {
        return try {
            val list = if (text.isEmpty()) emptyList() else text.split("\n")
            pipe.setStopSequences(list)
            0
        } catch (e: Exception) {
            -0x01
        }
    }

    sealed class Result {
        data class Success(val handleId: Long) : Result()
        data class Error(val message: String) : Result()
    }
}
