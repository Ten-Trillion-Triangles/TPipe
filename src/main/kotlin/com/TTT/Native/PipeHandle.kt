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
    
    sealed class Result {
        data class Success(val handleId: Long) : Result()
        data class Error(val message: String) : Result()
    }
}