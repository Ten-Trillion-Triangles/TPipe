package com.TTT.Pipe

import com.TTT.Context.ContextWindow
import com.TTT.Context.MiniBank
import com.TTT.P2P.P2PTransport
import com.TTT.PipeContextProtocol.PcPRequest
import com.TTT.Pipeline.DistributionGridDirective
import com.TTT.Util.deepCopy
import com.TTT.Util.deserialize
import com.TTT.Util.serialize
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import java.util.Base64

/**
 * Represents binary content that can be passed through TPipe pipelines.
 * Supports multiple transport formats and automatic conversion between them.
 */
@Serializable
sealed class BinaryContent
{
    
    /**
     * Raw binary data stored as byte array
     */
    @Serializable
    data class Bytes(
        val data: ByteArray,
        val mimeType: String,
        val filename: String? = null
    ) : BinaryContent() {
        
        fun toBase64(): Base64String = Base64String(
            data = Base64.getEncoder().encodeToString(data),
            mimeType = mimeType,
            filename = filename
        )
        
        override fun equals(other: Any?): Boolean {
            if(this === other) return true
            if(javaClass != other?.javaClass) return false
            other as Bytes
            return data.contentEquals(other.data) && mimeType == other.mimeType && filename == other.filename
        }
        
        override fun hashCode(): Int {
            var result = data.contentHashCode()
            result = 31 * result + mimeType.hashCode()
            result = 31 * result + (filename?.hashCode() ?: 0)
            return result
        }
    }
    
    /**
     * Base64 encoded binary data
     */
    @Serializable
    data class Base64String(
        val data: String,
        val mimeType: String,
        val filename: String? = null
    ) : BinaryContent() {
        
        fun toBytes(): Bytes = Bytes(
            data = Base64.getDecoder().decode(data),
            mimeType = mimeType,
            filename = filename
        )
    }
    
    /**
     * Reference to binary data stored in cloud storage
     */
    @Serializable
    data class CloudReference(
        val uri: String,
        val mimeType: String,
        val filename: String? = null
    ) : BinaryContent()
    
    /**
     * Text content that should be treated as a document
     */
    @Serializable
    data class TextDocument(
        val content: String,
        val mimeType: String = "text/plain",
        val filename: String? = null
    ) : BinaryContent()
}

/**
 * Represents multimodal content that can contain both text and binary data
 * @param text Any text to pass into the llm prompt.
 * @param binaryContent Binary content the llm may work with. Is transformed to various forms depending on
 * specifics of model support.
 * @param terminatePipeline Signals a critical failure forcing the pipeline to terminate. Should be only set internally
 * by validation checks and functions.
 * @param repeatPipe If true, this pipe will be called again until this is false. This allows for complex multi-step tasks
 * by a single llm, or creating a reasoning, or thinking mode for llm's that do not support model reasoning natively.
 * @param context The context window from the TPipe context system being passed. This is present to allow the coder
 * to manipulate and check the context data in their validation functions. The when exiting functions the Pipe class
 * will determine weather to push to the pipeline's context, or to the global context on the spot with the components
 * of this data.
 * @param miniBankContext MiniBank context object that can be interacted with in the same way as the regular context
 * object to automatically update the minibank context of the parent pipe.
 * @param tools List of pcp requests the llm may have generated. This is stored here because we often need to construct
 * a converse data object and need the tool requests the llm made to be preserved. In those cases we're typically stuck
 * in a situation where we only have the multiModalContent object the pcp context is just not available.
 * @param useSnapshot If true this object will be copied to the snapshot key of its own metadata to preserve a
 * copy of it prior to any events that occurred in the llm step of the pipe. This is useful for restoring to
 * a prior state in the event of an llm refusal or other reversal needed in a branch function. This however doubles
 * the memory usage that this pipe takes so it may be not desirable inn cases of larger content such as images,
 * video, or other binary data.
 */
@Serializable
data class MultimodalContent @OptIn(ExperimentalSerializationApi::class) constructor(
    var text: String = "",
    var binaryContent: MutableList<BinaryContent> = mutableListOf(),
    @EncodeDefault(EncodeDefault.Mode.NEVER)var terminatePipeline: Boolean = false,
    var context: ContextWindow = ContextWindow(),
    var miniBankContext: MiniBank = MiniBank(),
    @EncodeDefault(mode = EncodeDefault.Mode.NEVER)var tools: PcPRequest = PcPRequest(),
    @kotlinx.serialization.Transient var modelReasoning: String = "",
    var useSnapshot: Boolean = false,
    @kotlinx.serialization.Transient var pipeError: PipeError? = null
) {

//---------------------------------------Internal vars------------------------------------------------------------------
    /**
     * Determines where to jump to if this is non-empty. Allows for complex pipeline traversal such as skipping forward,
     * jumping forward to a specific pipe, or jumping backwards in time to a prior pipe in the pipeline. This variable
     * is kept private because the skipping feature is treated more like a bool, and the jumping feature is more of
     * a destination forward or backwards.
     */
    @kotlinx.serialization.Transient
   private var jumpToPipe = ""

    /**
     * If true the pipe using this content will be called again, passing exact content object back in. The pipe will
     * continue to call itself passing the content to itself until the programmer sets this to false. The pipe will
     * then be able to exit and move according to the pipe's jumpToPipe variable.
     */
   @kotlinx.serialization.Transient
   var repeatPipe: Boolean = false

    /**
     * Allows the pipeline to exit early without being considered an error. This is useful for cases where extra
     * steps are not needed for one reason or another and the given task can now exit early as completed.
     */
   @kotlinx.serialization.Transient
   var passPipeline: Boolean = false

    /**
     * Allows the reasoning pipe system to be skipped. When toggled to true reasoning content won't be extracted,
     * and instead the system will treat it like the reasoning pipe never ran. This is particularly useful for skipping
     * dynamic reasoning cases like semantic compression, where a token budget may not have needed to deploy
     * compression, and so running the reasoning pipe would be a waste of tokens, and just confuse the parent pipe.
     */
    @kotlinx.serialization.Transient
   var skipReasoningPipe: Boolean = false

    /**
     * Map of generic metadata that can be used by various functions interacting with this content object to store,
     * data, messages, signals, hooks, or any other information that is required to be passed into validation,
     * transformation, and branch failure functions or pipes to facilitate advanced logic, conditional branching,
     * and other more complex states when handling "human in the loop" events.
     *
     * This can be treated as a scratch pad, workspace, or anything else needed.
     */
   @kotlinx.serialization.Transient
   var metadata = mutableMapOf<Any, Any>()

    /**
     * Current pipe that is working on this content object. Useful for handling tasks like pipe templating
     * inside branch functions.
     */
   @kotlinx.serialization.Transient
   var currentPipe: Pipe? = null

//---------------------------------------Functions---------------------------------------------------------------------

    /**
     * Add binary content to this multimodal content
     */
    fun addBinary(content: ByteArray, mimeType: String, filename: String? = null)
    {
        binaryContent.add(BinaryContent.Bytes(content, mimeType, filename))
    }

    
    /**
     * Add binary content to this multimodal content
     */
    fun addBinary(content: BinaryContent)
    {
        binaryContent.add(content)
    }
    
    /**
     * Add text to this multimodal content
     */
    fun addText(additionalText: String)
    {
        text = if(text.isEmpty()) additionalText else "$text\n\n$additionalText"
    }
    
    /**
     * Mark this content to terminate the pipeline
     */
    fun terminate()
    {
        terminatePipeline = true
    }

    fun repeat()
    {
        repeatPipe = true
    }
    
    /**
     * Check if content is empty (equivalent to empty string check)
     */
    fun isEmpty(): Boolean = text.isEmpty() && binaryContent.isEmpty()
    
    /**
     * Check if this content contains any binary data
     */
    fun hasBinaryContent(): Boolean = binaryContent.isNotEmpty()
    
    /**
     * Check if pipeline should be terminated
     */
    fun shouldTerminate(): Boolean = terminatePipeline || isEmpty()
    
    /**
     * Get all images from binary content
     */
    fun getImages(): List<BinaryContent> = 
        binaryContent.filter { it.getMimeType().startsWith("image/") }
    
    /**
     * Get all documents from binary content
     */
    fun getDocuments(): List<BinaryContent> = 
        binaryContent.filter { 
            val mime = it.getMimeType()
            mime.startsWith("application/") || mime == "text/plain" || mime == "text/html"
        }

    /**
     * Mark this content to skip to the next pipe in the pipeline. Uses a specific internal string name to
     * act as a boolean to simplify the logic for pipe traversal.
     */
    fun skipToNextPipe()
    {
        jumpToPipe = "skip-to-next-pipe"
    }

    /**
     * Mark this content object to send a skip signal to TPipe's  reasoning system. When this is active, if this was coming
     * from a reasoning pipe it will treat the system like the reasoning never happened, and never extract the data
     * back into the system prompt or user prompt. This is useful for conditional reasoning cases like semantic compression
     * inside of token budgeting, where if the user prompt does not end up compressed, we can just skip over that case
     * completely and save the tokens on the spot.
     */
    fun skipReasoning()
    {
        skipReasoningPipe = true
    }

    /**
     * Trigger a pass pipeline flag to exit early in a state of succeess.
     */
    fun terminateAndPassPipeline()
    {
        passPipeline = true
    }

    /**
     * Mark this content to jump to a specific pipe in the pipeline. The pipeline will then continue forward from
     * where the jump lands. This will occur even if you jump backwards in the pipeline.
     *
     * @param pipeName Name of the pipe to jump to. The target pipe must have its name set correctly for it to be
     * reachable.
     *
     * @see com.TTT.Pipe.Pipe.setPipeName
     * @throws IllegalArgumentException If the pipeName is "skip-to-next-pipe" as this is a reserved internal name.
     */
    fun jumpToPipe(pipeName: String)
    {
        if(pipeName == "skip-to-next-pipe")
        {
            throw IllegalArgumentException("skip-to-next-pipe is a reserved internal name that cannot be used.")
        }

        jumpToPipe = pipeName
    }

    /**
     * Getter that returns a copy of our target pipe jump destination.
     */
    fun getJumpToPipe(): String = jumpToPipe

    /**
     * Clear the jump to pipe variable. This is called by the Pipe class after the jump has been executed.
     */
    fun clearJumpToPipe()
    {
        jumpToPipe = ""
    }

    /**
     * Attempt to copy this content object. Useful for snapshots and other metadata actions.
     */
    fun copyMultimodal() : MultimodalContent?
    {
        return this.deepCopy()
    }

    /**
     * Force save a snapshot of this content object. This is useful for restoring to a prior state in the event of
     * an llm refusal or other reversal needed in a branch function. This however doubles the memory usage that this
     * pipe takes so it may be not desirable in cases of larger content such as images, video, or other binary data.
     *
     * When called this will override [useSnapshot] if the pipe already auto-saved a snapshot.
     */
    fun saveSnapshot()
    {
        metadata["snapshot"] = this.deepCopy()
    }

    /**
     * Get the snapshot if present. This is a helper function to simplify using the multiModalContent's snapshot
     * feature.
     *
     * @see Pipe.executeMultimodal For more details on how snapshots are stored in the metadata variable of this
     * object.
     */
    fun getSnapshot() : MultimodalContent?
    {
        return metadata["snapshot"] as? MultimodalContent
    }

    /**
     * Delete a snapshot that has been stored. This is useful for memory management.
     */
    fun deleteSnapshot()
    {
        metadata.remove("snapshot")
    }
    
    /**
     * Merge another MultimodalContent object into this one, appending B to A.
     * @param other The MultimodalContent object to merge from (B)
     */
    fun merge(other: MultimodalContent)
    {
        // Merge text content
        if(other.text.isNotEmpty())
        {
            addText(other.text)
        }
        
        // Replace binary content if other has content
        if(other.binaryContent.isNotEmpty())
        {
            binaryContent = other.binaryContent.toMutableList()
        }
        
        // Replace model reasoning if other has content
        if(other.modelReasoning.isNotEmpty())
        {
            modelReasoning = other.modelReasoning
        }
        
        // Merge contexts
        context.merge(other.context)
        miniBankContext.merge(other.miniBankContext)
        
        // Merge metadata
        other.metadata.forEach { (key, value) ->
            metadata[key] = value
        }
    }

    /**
     * Set the todo task number that the llm should focus on if given a todo list object. This is read when
     * [com.TTT.Pipe.applySystemPrompt] is called on the pipe class.
     */
    fun setTodoTaskNumber(taskNumber: Int)
    {
        metadata["todoTaskNumber"] = taskNumber
    }

    /**
     * Set a DistributionGridDirective into this content's metadata. This is the canonical way to communicate
     * routing decisions from a router pipeline back to the DistributionGrid harness.
     *
     * @param directive The DistributionGridDirective to set
     * @see com.TTT.Pipeline.DistributionGridDirective
     */
    fun setDistributionGridDirective(directive: DistributionGridDirective)
    {
        metadata["distributionGridDirective"] = directive
    }

    /**
     * Get the DistributionGridDirective from this content's metadata, if present.
     *
     * @return The directive if set, null otherwise
     * @see com.TTT.Pipeline.DistributionGridDirective
     */
    fun getDistributionGridDirective(): DistributionGridDirective?
    {
        return metadata["distributionGridDirective"] as? DistributionGridDirective
    }

}

/**
 * Extension function to get MIME type from BinaryContent
 */
fun BinaryContent.getMimeType(): String = when(this)
{
    is BinaryContent.Bytes -> mimeType
    is BinaryContent.Base64String -> mimeType
    is BinaryContent.CloudReference -> mimeType
    is BinaryContent.TextDocument -> mimeType
}

/**
 * Extension function to get filename from BinaryContent
 */
fun BinaryContent.getFilename(): String? = when(this)
{
    is BinaryContent.Bytes -> filename
    is BinaryContent.Base64String -> filename
    is BinaryContent.CloudReference -> filename
    is BinaryContent.TextDocument -> filename
}


/**
 * Extension function to check if MultimodalContent has an error.
 * @return true if pipeError is present, false otherwise
 */
fun MultimodalContent.hasError(): Boolean = pipeError != null
