package com.TTT.Native

import com.TTT.Pipeline.Pipeline
import com.TTT.Pipeline.Connector
import com.TTT.Pipeline.MultiConnector
import com.TTT.Pipeline.Splitter
import com.TTT.Pipeline.Manifold
import com.TTT.Pipe.MultimodalContent

/**
 * Handle representing a TPipe Pipeline.
 *
 * A Pipeline orchestrates multiple pipes or agents in sequence or parallel.
 * PipelineHandle wraps a Pipeline and provides the C ABI methods.
 */
class PipelineHandle(
    val pipeline: Pipeline,
    var pipelineName: String = "CABI-Pipeline"
) {
    /**
     * Execute the pipeline with the given content.
     * 
     * @param inputContent Input content handle
     * @return Result containing output content handle or error
     */
    fun execute(inputContent: ContentHandle): Result {
        return try {
            val mc = inputContent.toMultimodalContent()
            val outputMc = kotlinx.coroutines.runBlocking { pipeline.execute(mc) }
            val outputHandle = ContentHandle.fromMultimodalContent(outputMc)
            val handleId = HandleRegistry.allocate(HandleTypes.CONTENT, outputHandle)
            Result.Success(handleId)
        } catch (e: Exception) {
            Result.Error(e.message ?: "Pipeline execution failed")
        }
    }
    
    /**
     * Get the pipeline outcome as JSON string.
     */
    fun getOutcome(): String {
        // Return pipeline metadata/outcome as JSON
        return """{"name":"$pipelineName","status":"executed","inputTokens":${pipeline.inputTokensSpent},"outputTokens":${pipeline.outputTokensSpent}}"""
    }
    
    /**
     * Get the pipeline name.
     */
    fun getName(): String = pipelineName
    
    /**
     * Set the pipeline name.
     */
    fun setName(name: String) {
        this.pipelineName = name
        pipeline.pipelineName = name
    }
    
    /**
     * Get the context window associated with this pipeline.
     */
    fun getContextWindow(): com.TTT.Context.ContextWindow = pipeline.context
    
    /**
     * Get the mini bank associated with this pipeline.
     */
    fun getMiniBank(): com.TTT.Context.MiniBank = pipeline.miniBank
    
    sealed class Result {
        data class Success(val handleId: Long) : Result()
        data class Error(val message: String) : Result()
    }
}