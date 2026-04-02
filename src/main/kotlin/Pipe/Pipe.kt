package com.TTT.Pipe

import com.TTT.Context.ContextBank
import com.TTT.Context.ContextWindow
import com.TTT.Context.ConverseData
import com.TTT.Context.ConverseHistory
import com.TTT.Context.ConverseRole
import com.TTT.Context.Dictionary
import com.TTT.Context.MemoryIntrospection
import com.TTT.Context.MemoryIntrospectionConfig
import com.TTT.Context.MemoryIntrospectionTools
import com.TTT.Context.MiniBank
import com.TTT.Debug.*
import com.TTT.Debug.EventPriorityMapper
import com.TTT.Enums.ContextWindowSettings
import com.TTT.Enums.PromptMode
import com.TTT.Enums.ProviderName
import com.TTT.P2P.AgentDescriptor
import com.TTT.PipeContextProtocol.PcpExecutionDispatcher
import com.TTT.PipeContextProtocol.PcpExecutionResult
import com.TTT.PipeContextProtocol.PcpResponseParser
import com.TTT.PipeContextProtocol.PcpInstructionGenerator
import com.TTT.P2P.AgentRequest
import com.TTT.P2P.P2PInterface
import com.TTT.P2P.P2PDescriptor
import com.TTT.P2P.P2PTransport
import com.TTT.P2P.P2PRequirements
import com.TTT.P2P.P2PRequest
import com.TTT.P2P.P2PResponse
import com.TTT.PipeContextProtocol.PcPRequest
import com.TTT.PipeContextProtocol.PcpContext
import com.TTT.Pipeline.Pipeline
import com.TTT.Structs.PipeSettings
import com.TTT.Structs.ReasoningRoundDirective
import com.TTT.Structs.ReasoningRoundMode
import com.TTT.Structs.composeBlindReasoningRoundPrompt
import com.TTT.Structs.composeMergeReasoningRoundPrompt
import com.TTT.Structs.extractReasoningContent
import com.TTT.Structs.extractReasoningStream
import com.TTT.Util.deepCopy
import com.TTT.Util.deserialize
import com.TTT.Util.examplePromptFor
import com.TTT.Util.extractAllJsonObjects
import com.TTT.Util.extractNonJsonText
import com.TTT.Util.removeFromFirstOccurrence
import com.TTT.Util.buildSemanticDecompressionInstructions
import com.TTT.Util.semanticCompress
import com.TTT.Util.SemanticCompressionResult
import com.TTT.Util.SemanticCompressionSettings
import com.TTT.Util.serializeConverseHistory
import com.TTT.Util.serialize
import kotlinx.coroutines.*
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import java.util.UUID
import kotlin.math.absoluteValue
import kotlin.reflect.KClass

//==============================================Data Classes==========================================================//
@kotlinx.serialization.Serializable
data class TruncationSettings(
         var multiplyWindowSizeBy: Int = 0,
         var countSubWordsInFirstWord: Boolean = true,
         var favorWholeWords: Boolean = true,
         var countOnlyFirstWordFound: Boolean = false,
         var splitForNonWordChar: Boolean = true,
         var alwaysSplitIfWholeWordExists: Boolean = false,
         var countSubWordsIfSplit: Boolean = false,
         var nonWordSplitCount: Int = 4,
         var tokenCountingBias: Double = 0.0,
         var fillMode: Boolean = false,
         var fillAndSplitMode: Boolean = false,
         var multiPageBudgetStrategy: MultiPageBudgetStrategy? = null,
         var pageWeights: Map<String, Double>? = null)

/**
 * Data class that defines how advanced token budgeting is applied. In this scheme the user prompt, and system prompt
 * are safely accounted for to fit into the total context window, and user prompt compression, or truncation
 * is able to be applied as desired.
 *
 * @param userPromptSize Defines the maximum tokens allotted for the user prompt. If the user prompt exceeds
 * this size an error will be thrown, if the use prompt is allowed to be truncated it will be truncated instead
 * if able.
 *
 * @param maxTokens Defines the maximum tokens that the llm is allowed to output. This combines both reasoning/thinking
 * tokens and the actual llm response. This gets subtracted against the total token budget reducing the available space
 * for extra context, binary content, and content beyond the user prompt.
 *
 * @param reasoningBudget Defines the maximum tokens that the llm is allowed to output for model reasoning.
 * Reasoning/thinking using TPipe's internal model reasoning system will be cut off once the prompt reaches
 * the max or overflows. Reasoning will be truncated from the bottom if it exceeds the allotted amount by default.
 * Any amount of reasoning tokens supplied will subtract directly from the max token output total. IE: 8K reasoning
 * in a 20K max token window will leave 12K allowed for the actual output.
 *
 * @param contextWindowSize Defines the total token budget the llm can accept between input and outputs.
 * This value should be defined slightly below the actual llm's rated maximum to provide safety slack for
 * token counting. If this is defined, it will override whatever has been set as the ContextWindow variable
 * in the pipe class.
 *
 * @param allowUserPromptTruncation If true, the user prompt will be truncated to fit into the context window.
 * If false, an error will be thrown if the user prompt exceeds the context window.
 *
 * @param compressUserPrompt If true, the user prompt will be compressed to fit into the context window using
 * semantic prompt compression. If false, an error will be thrown if the user prompt exceeds the context window.
 * Beware that semantic prompt compression requires the text context to be standard human language. Code, json,
 * xml, and content that isn't expressible as human language should not be compressed.
 *
 * @see SemanticCompression.md for more details on how semantic prompt compression works.
 *
 *@truncate ContextWindowAsString If true, the context window will be truncated to fit into the context window.
 * If false, an error will be thrown if the user prompt exceeds the context window.
 *
 * @param preserveTextMatches If true, text matches in converse history, and in context elements can be treated as
 * lowest weight and preserved over text that does not match the selection. Creates a secondary layer of selection
 * allowing for more preservation of text at the risk of possible truncation when memory pressure is still too
 * high.
 *
 * @param truncationMethod Defines the method used to truncate the user prompt if it exceeds the context window.
 * If the user prompt is allowed to be truncated, it will be truncated according to the truncation method.
 * If the user prompt is not allowed to be truncated, an error will be thrown.
 *
 * @param multiPageBudgetStrategy Determines how token budgeting allocates empty space, and leftover reserve area.
 * Options: EQUAL_SPLIT (equal allocation), WEIGHTED_SPLIT (user-defined weights), 
 * PRIORITY_FILL (key index order), DYNAMIC_FILL (priority + redistribution),
 * DYNAMIC_SIZE_FILL (size-based priority + redistribution - protects smaller contexts)
 *
 * @param pageWeights Optional page weight overrides for the weighted allocation strategy. Required if using any
 * weighted fill modes.
 *
 * @param reserveEmptyPageBudget Determines whether empty pages still reserve a portion of the budget. If true,
 * the budget will be divided regardless of weather a specified page has content, or how much it has. Will dynamically
 * fill when set to dynamic fill preventing space wasted.
 *
 * @see ContextWindowSettings
 */
@kotlinx.serialization.Serializable
data class TokenBudgetSettings(
    var userPromptSize: Int? = null, //Assume 12K tokens
    var maxTokens: Int? = null, //Assume 20K tokens
    var reasoningBudget: Int? = null, //Assume 8K tokens
    var subtractReasoningFromInput: Boolean = false,
    var contextWindowSize: Int? = null, //Assume 32K tokens total
    var allowUserPromptTruncation: Boolean = false,
    var preserveJsonInUserPrompt: Boolean = true,
    var compressUserPrompt: Boolean = false,
    var truncateContextWindowAsString: Boolean = false,
    var preserveTextMatches: Boolean = false, // Prioritize prompt-matching context/history entries
    var truncationMethod: ContextWindowSettings = ContextWindowSettings.TruncateTop,
    /**
     * Controls how the per-page budgets for MiniBank truncation are allocated.
     */
    var multiPageBudgetStrategy: MultiPageBudgetStrategy = MultiPageBudgetStrategy.DYNAMIC_SIZE_FILL,
    /**
     * Optional page weight overrides for the weighted allocation strategy.
     */
    var pageWeights: Map<String, Double>? = null,
    /**
     * Determines whether empty pages still reserve a portion of the budget.
     */
    var reserveEmptyPageBudget: Boolean = true
)

/**
 * Comprehensive token usage data for a pipe and any nested pipes it owns.
 */
@kotlinx.serialization.Serializable
data class TokenUsage(
    var inputTokens: Int = 0,
    var outputTokens: Int = 0,
    var childPipeTokens: MutableMap<String, TokenUsage> = mutableMapOf(),
    var totalInputTokens: Int = 0,
    var totalOutputTokens: Int = 0
) {
    /**
     * Adds or updates a child pipe entry and recalculates aggregate totals.
     * This method stores the token usage for a specific child pipe and immediately
     * recalculates the total token counts to maintain accurate aggregation.
     *
     * @param pipeName The unique identifier for the child pipe being tracked
     * @param usage The TokenUsage object containing the child pipe's token consumption data
     */
    fun addChildUsage(pipeName: String, usage: TokenUsage)
    {
        //Store the child pipe's token usage in our tracking map.
        childPipeTokens[pipeName] = usage
        
        //Immediately recalculate totals to ensure accuracy.
        recalculateTotals()
    }

    /**
     * Recalculates totals including this pipe and all tracked child pipes.
     * This method aggregates token counts from this pipe's direct usage plus
     * the recursive totals from all child pipes to provide comprehensive tracking.
     */
    fun recalculateTotals()
    {
        //Calculate total input tokens from this pipe plus all child pipe totals.
        totalInputTokens = inputTokens + childPipeTokens.values.sumOf { it.totalInputTokens }
        
        //Calculate total output tokens from this pipe plus all child pipe totals.
        totalOutputTokens = outputTokens + childPipeTokens.values.sumOf { it.totalOutputTokens }
    }

    /**
     * Returns a formatted breakdown of token usage for debugging purposes.
     * Shows parent pipe usage, child pipe usage, and totals in a readable format.
     *
     * @return Formatted string showing token usage breakdown
     */
    fun getUsageBreakdown(): String
    {
        val breakdown = StringBuilder()
        breakdown.append("Parent Pipe: $inputTokens input, $outputTokens output\n")
        
        if(childPipeTokens.isNotEmpty())
        {
            breakdown.append("Child Pipes:\n")
            childPipeTokens.forEach { (name, usage) ->
                breakdown.append("  $name: ${usage.totalInputTokens} input, ${usage.totalOutputTokens} output\n")
            }
        }
        
        breakdown.append("Total: $totalInputTokens input, $totalOutputTokens output")
        return breakdown.toString()
    }
}

/**
 * Defines the supported multi-page budget allocation strategies.
 *
 * @param EQUAL_SPLIT All pages are allocated the same amount of budget.
 * @param WEIGHTED_SPLIT Gives higher weight pages higher priority when determining which gets space in an equal split.
 * @param PRIORITY_FILL Walks pages in order and fills each page up to its full token need (based on current content), exhausting the total
 * budget as it goes.
 * @param DYNAMIC_FILL Starts with priority fill, simulates actual usage after truncation, then redistributes any unused budget to pages
 * that still need more, in up to 3 passes.
 */
@Serializable
enum class MultiPageBudgetStrategy {
    EQUAL_SPLIT,
    WEIGHTED_SPLIT,
    PRIORITY_FILL,
    DYNAMIC_FILL,
    DYNAMIC_SIZE_FILL
}

/**
 * Builds TokenBudgetSettings from TruncationSettings so the budget can inherit the same tokenizer configuration
 * plus the optional multi-page hints.
 */
fun TruncationSettings.toTokenBudgetSettings(
    contextWindowSize: Int? = null,
    userPromptSize: Int? = null,
    maxTokens: Int? = null
): TokenBudgetSettings
{
    return TokenBudgetSettings(
        contextWindowSize = contextWindowSize,
        userPromptSize = userPromptSize,
        maxTokens = maxTokens,
        truncationMethod = ContextWindowSettings.TruncateTop,
        multiPageBudgetStrategy = this.multiPageBudgetStrategy ?: MultiPageBudgetStrategy.DYNAMIC_FILL,
        pageWeights = this.pageWeights
    )
}

/**
 * Converts TokenBudgetSettings into TruncationSettings to reuse tokenizer configuration when only the budget
 * information is known.
 */
fun TokenBudgetSettings.toTruncationSettings(pipe: Pipe? = null): TruncationSettings
{
    val settings = pipe?.getTruncationSettings() ?: TruncationSettings()
    if(this.multiPageBudgetStrategy != null)
    {
        settings.multiPageBudgetStrategy = this.multiPageBudgetStrategy
    }
    if(this.pageWeights != null)
    {
        settings.pageWeights = this.pageWeights
    }

    return settings
}

/**
 * Create a stable copy of [TokenBudgetSettings] so callers can inspect or reuse a budget without sharing mutable state.
 *
 * @return Copy of the token budget settings with map values detached.
 */
fun TokenBudgetSettings.deepCopy(): TokenBudgetSettings
{
    return copy(pageWeights = pageWeights?.toMap())
}

//============================================Enum classes==============================================================

/**
 * Enum class that defines how pipe timeouts should be handled. Automatic failures, retries,
 * or to use a custom bound function to try to handle the case. This enum is used internally
 * in the pipe timeout system but not externally beyond the pipe class.
 */
enum class PipeTimeoutStrategy
{
    Fail,
    Retry,
    CustomLogic
}

//============================================Const Vars================================================================

var USER_PROMPT_SNAPSHOT = "validatorPipeUserPromptSnapshotTPipe"

//============================================Companion Objects=========================================================

data class TimeoutBundle(
    var contentCopy: MultimodalContent,
    var isPipeRunning: Boolean
)

/**
 * Global object for managing and tracking pipe timeouts. Handles coroutines, running timers, and
 * triggering interrupts when an llm on a pipe has become stuck.
 */
object PipeTimeoutManager
{
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val timers = java.util.concurrent.ConcurrentHashMap<Pipe, Job>()
    private val timedOutPipes = java.util.Collections.synchronizedSet(mutableSetOf<Pipe>())
    private val retryAttempts = java.util.concurrent.ConcurrentHashMap<Pipe, Int>()

    /**
     * Starts a timer for the given pipe. If the timer expires, the pipe's abort() method
     * is called, and the pipe is marked as timed out.
     */
    fun startTracking(pipe: Pipe, timeoutMs: Long)
    {
        timedOutPipes.remove(pipe)
        timers[pipe]?.cancel()
        timers[pipe] = scope.launch {
            delay(timeoutMs)
            timedOutPipes.add(pipe)
            pipe.abort()
        }
    }

    /**
     * Stops and removes the timer for a pipe. Should be called when the pipe completes
     * its execution successfully or fails due to other reasons.
     */
    fun stopTracking(pipe: Pipe)
    {
        timers.remove(pipe)?.cancel()
    }

    /**
     * Checks if a pipe has timed out.
     */
    fun isTimeout(pipe: Pipe): Boolean = timedOutPipes.contains(pipe)

    /**
     * Resets the timeout flag for a pipe.
     */
    fun clearTimeout(pipe: Pipe)
    {
        timedOutPipes.remove(pipe)
    }

    /**
     * Gets the current retry count for a pipe.
     */
    fun getRetryCount(pipe: Pipe): Int = retryAttempts.getOrDefault(pipe, 0)

    /**
     * Increments the retry count for a pipe.
     */
    fun incrementRetryCount(pipe: Pipe)
    {
        retryAttempts[pipe] = getRetryCount(pipe) + 1
    }

    /**
     * Resets the retry count for a pipe.
     */
    fun clearRetryCount(pipe: Pipe)
    {
        retryAttempts.remove(pipe)
    }

    /**
     * Determines the next action for a pipe that has timed out.
     */
    fun handleTimeoutSignal(pipe: Pipe, content: MultimodalContent): MultimodalContent
    {
        val attempts = getRetryCount(pipe)
        
        return if(attempts < pipe.maxRetryAttempts && pipe.timeoutStrategy == PipeTimeoutStrategy.Retry)
        {
            incrementRetryCount(pipe)
            pipe.timeoutTrace(TraceEventType.PIPE_RETRY, TracePhase.EXECUTION, metadata = mapOf("attempt" to getRetryCount(pipe)))
            
            val snapshot = content.getSnapshot()
            if(snapshot != null)
            {
                snapshot.repeatPipe = true
                snapshot
            } 
            else 
            {
                // If no snapshot, we can't safely retry, so we fail.
                pipe.timeoutTrace(TraceEventType.PIPE_FAILURE, TracePhase.EXECUTION, error = Exception("Timeout retry failed: No snapshot available to restore state."))
                content.terminate()
                content
            }
        } 
        else if(pipe.timeoutStrategy == PipeTimeoutStrategy.CustomLogic)
        {
            // Custom logic is handled by the pipe's own retry function if set.
            // We'll return the content as is and let the catch block handle the invocation if needed,
            // or we could potentially invoke it here.
            content
        }
        else 
        {
            pipe.timeoutTrace(TraceEventType.PIPE_FAILURE, TracePhase.EXECUTION, error = Exception("Pipe timed out after ${attempts} retries."))
            content.terminate()
            content
        }
    }
}

//===========================================Main Class=================================================================

/**
 * Main class for abstracting an AI pipe in the TPipe pipeline system. Provides interface abstractions for
 * all sdk's and frameworks and adds support for standard features and systems present in all AI api and frameworks.
 *
 * Each pipe supports the ability to assign an input class for serialization, or raw json strings, as well as
 * automatic handling of api calls and responses. Additionally, each pipe allows for the assignment of functions
 * to be called as a pass or failure, and allowing to automatically assign what variables in json denote success
 * or failure. These functions can be passed in from any codebase that embeds this library. In the future shims
 * will also be supported to allow for other languages to supply hooks that will enable TPipe to call their native code
 * when supplied to the pipe directly depending on the language, and it's ability to allow for function pointers,
 * function objects, reflection, or other mechanisms to bind native code to this library.
 *
 * This class should not be addressed directly, but instead be inherited from for each supported api and sdk.
 * As such, many functions here are abstract and have no functionality.
 *
 * Pipe instances are mutable execution objects. Build separate instances for concurrent top-level runs rather than
 * sharing the same instance across multiple simultaneous executions.
 */
@kotlinx.serialization.Serializable
abstract class Pipe : P2PInterface, ProviderInterface
{

//============================================= properties ===========================================================//

//============================================== P2PInterface ==========================================================

    @kotlinx.serialization.Transient
    private var p2pDescriptor: P2PDescriptor? = null
    
    @kotlinx.serialization.Transient
    private var p2pTransport: P2PTransport? = null
    
    @kotlinx.serialization.Transient
    private var p2pRequirements: P2PRequirements? = null
    
    @kotlinx.serialization.Transient
    private var containerObject: Any? = null

    /**
     * Tracks the active execution job for this pipe. Allows for manual or timeout-driven
     * hardware/api cancellation.
     */
    @kotlinx.serialization.Transient
    private var activeJob: kotlinx.coroutines.Job? = null

//============================================= properties ===========================================================//

    /**
     * Optional name for this pipe. Useful for debugging and tracing pipes and pipelines.
     */
    @SerialName("pipeName")
    var pipeName = ""

    /**
     * Timeout value for the pipe class. Defaults to 5 mins. When timeouts are enabled, the pipe
     * will be cut off if it has not exited by that point. The result can be handled by an automatic
     * retry, failure, or custom handler.
     */
    var pipeTimeout: Long = 300000L

    /**
     * Toggles weather to activate the pipe timeout system or not.
     */
    var enablePipeTimeout = false

    /**
     * Propagates pipe timeouts to all child pipes recursively.
     */
    var applyTimeoutRecursively = true

    /**
     * Defines the type of responce that should occur when a pipe timeout occurs. Failure, retry,
     * or bound custom logic.
     */
    var timeoutStrategy: PipeTimeoutStrategy = PipeTimeoutStrategy.Fail

    /**
     * Defines the maximum number of retry attempts to allow when activating automatic retry for
     * pipe timeouts.
     */
    var maxRetryAttempts: Int = 5

    /**
     * Bindable function for handling logic surrounding pipe timeouts. Allows the developer to
     * decide how to handle data management and how to kick off any other repair methods needed.
     *
     * @param pipe Reference to the pipe that timed out.
     * @param content Current multimodal content object that was running when the pipe timed out.
     * This will be in the exact state it was in prior to the llm hang.
     */
    protected var pipeRetryFunction: (suspend (pipe: Pipe, content: MultimodalContent) -> Boolean)? = null
    
    /**
     * Sets the custom retry function for this pipe.
     */
    fun setRetryFunction(func: (suspend (pipe: Pipe, content: MultimodalContent) -> Boolean)?)
    {
        this.pipeRetryFunction = func
    }

    
    /**
     * Reference to the parent pipeline that this pipe is a part of. Required to communicate with the parent on actions
     * such as failure or success.
     */
    @kotlinx.serialization.Transient
    protected var pipelineRef: Pipeline? = null

    /**
     * Reference to the parent pipe that owns this pipe. This happens when this pipe is a sub-pipe of a parent
     * such as developer-in-the-loop pipes.
     */
    @kotlinx.serialization.Transient
    protected var parentPipeRef: Pipe? = null

    /**
     * Manager for multiple streaming callbacks with configurable execution mode.
     * Allows multiple independent callbacks to receive streaming chunks.
     */
    @kotlinx.serialization.Transient
    protected var streamingCallbackManager: StreamingCallbackManager? = null

    /**
     * Model to use for this pipe. Useful for logic that needs to behave differently depending on the model.
     * Does not have any internal functionality and is intended to be referenced by validation functions.
     */
    @Serializable
    protected var provider = ProviderName.Aws

    /**
     * Name of the AI model to load. This is required by some AI APIs and frameworks such as OpenAI, and Ollama.
     */
    @Serializable
    protected var model = ""

    /**
     * Denotes the prompt mode to be used by the AI model. Some models support multiple modes with automatic
     * context handling. Others, only support single prompt interfaces.
     *
     * TPipe will handle context based on this setting.
     *
     * singlePrompt: TPipe will not automatically store or manage context and will require the programmer to handle
     * how and in what ways context is stored in the validation functions, or code external to the pipe or pipeline.
     *
     * chat: Model internally supports a chat role that is handled without user input and is storing all it's context
     * on its own end or provider's end.
     *
     * internalContext: TPipe will automatically treat user and model responses as a chat system and attempt to
     * map it into the pipe's standard list based context system.
     */
    @Serializable
    protected var promptMode = PromptMode.singlePrompt



    /**
     * System prompt for the AI model this pipe abstracts.
     */
    @Serializable
    protected var systemPrompt = ""

    /**
     * If true, when this pipe executes the system prompt will be deleted, and then instead, copied into a converse
     * history object. Then the user prompt will be added as another element to form a converse history of system
     * first, then user second. This is useful for several models and providers that do not adhere to system prompts
     * in the level or scale that is typically expected. Instead, such models often are wieghted to adress the user
     * prompt far more aggressively. This var will ensure that it's hassle-free to adress those types of models,
     * and to keep the setting needed to handle it down to a simple one-liner.
     */
    @Serializable
    protected var copySystemToUserPrompt = false

    /**
     * If true, applySystemPrompt will prepend a semantic decompression prelude ahead of the rebuilt system prompt.
     *
     * The prelude teaches the model how to read a legend-backed compressed prompt before continuing with the
     * rest of the system instructions.
     */
    @Serializable
    protected var semanticDecompressionEnabled = false

    /**
     * raw system prompt before injection is applied.
     */
    @Serializable
    protected var rawSystemPrompt = ""

    /**
     * Allows instructions to be overridden for json prompt injection for inputs and outputs.
     * Is applied if applySystemPrompt is invoked.
     */
    @Serializable
    protected var jsonInputInstructions = ""

    /**
     * Allows instructions to be overridden for json prompt injection for inputs and outputs.
     * Is applied if applySystemPrompt is invoked.
     */
    @Serializable
    protected var jsonOutputInstructions = ""

    /**
     * Allows for an injection into the system prompt after json input but before json output.
     */
    @Serializable
    protected var middlePromptInstructions = ""

    /**
     * Context instructions that are applied when context gets auto-injected or when the system prompt gets
     * applied. Allows the coder to define instructions on how to understand the context that is supplied and
     * injected prior to the displaying of the json schema for the TPipe context system.
     */
    @Serializable
    protected var contextInstructions = ""

    /**
     * Footer that can be added to the very end of a system prompt. This is useful for conveying any instructions that
     * need to be placed after the context injection into the system prompt.
     */
    @Serializable
    protected var footerPrompt = ""

    /**
     * User prompt. This is a value that will be prefixed to any input to the AI model. When generating text,
     * a prompt injection value will be suffixed to this variable to form a complete prompt.
     */
    @Serializable
    protected var userPrompt = ""
    
    /**
     * Multimodal input content that can contain text and binary data. This replaces the string-only
     * mechanisms and allows for comprehensive multimodal pipeline processing.
     */
    @Serializable
    protected var multimodalInput = MultimodalContent()

    /**
     * Json used for input. Is required by APIs that need to define json input. Also required if TPipe has to
     * use prompt injection to force json input into the AI model if it doesn't support it natively.
     */
    @Serializable
    var jsonInput = "" //Json used for input. Must contain all variables the json can use.

    /**
     * Json output from the AI model. Some models and APIs allow this to be defined by the sdk or rest API.
     * If not supported TPipe will employ prompt injection to force json output from the AI model. This isn't
     * 100% guaranteed to work. So TPipe uses an extremely liberal serialization and deserialization process
     * that attempts to make it work even if the output is malformed or incomplete.
     */
    @Serializable
    var jsonOutput = ""

    /**
     * TPipe Pipe Context Protocol settings if desired. This works similar to MCP but directly tailored to allow
     * TPipe to pass information about what the sytem calling the pipe can and cannnot allow the llm to do.
     */
    @Serializable
    protected var pcpContext = PcpContext()

    /**
     * PCP execution dispatcher for processing parsed requests with context validation.
     */
    @kotlinx.serialization.Transient
    private val pcpDispatcher = PcpExecutionDispatcher()

    /**
     * TPipe Pipe to Pipe agent request settings. Allows the pipe to be informed of what agents it can call.
     * This data is compiled at the system prompt time. Updating this data will require calling applySystemPrompt
     * again if needed.
     */
    @Serializable
    protected var p2pAgentDescriptors : MutableList<AgentDescriptor>? = null

    /**
     * If non-empty, the default injection for describing the p2p agents that are available and the schema to
     * call them will be overridden by this value.
     */
    protected var p2pAgentRequestsDescription = ""

    /**
     * Description that can override the default explanation of the pcp schema.
     */
    @Serializable
    protected var pcpDescription = ""

    /**
     * Custom instructions for merged PCP + JSON output mode.
     * If empty, default merged instructions will be used.
     * This mode is automatically activated when both PCP tools and JSON output are configured.
     */
    @Serializable
    protected var mergedPcpJsonInstructions = ""

    /**
     * If false the AI model does not support native and forced structured json input and output. In this case,
     * TPipe will use prompt injection to force json input and output. While not 100% guaranteed to work,
     * this method generally does output a functional result.
     */
    @Serializable
    protected var supportsNativeJson = true

    /**
     * If true, any text that isn't json will be stripped from the response of the llm. This ensures that llm's
     * that do not obey prompt injection fully but do output json as expected will not break the pipes and systems
     * ahead of them.
     */
    @Serializable
    protected var stripNonJson = false

    /**
     * Temperature/randomness settings. Higher values make the model more likely to pick less likely and plausible
     * token predictions.
     */
    @Serializable
    protected var temperature : Double = 0.0

    /**
     * TopP settings. Most models support adjusting this value. Lowering this value produces more predictable
     * output. Increasing this value produces more random output. topP measures in probability between 0 to 100%.
     *
     * at .7 70% of possible tokens are viable. At .1 only the top 10% are selectable by an llm.
     */
    @Serializable
    protected var topP : Double = 0.0

    /**
     * TopK settings. Only certain models support this. If unsupported, this setting will do nothing.
     * Number of possible token predictions will be restricted to value of topK. At 1000, the top 1000
     * most likely predictions can be randomly picked. At 10 only the 10 most likley predictions can be picked.
     */
    @Serializable
    protected var topK = 0

    /**
     * Maximum number of tokens allowed to be generated. Not all AI models support this. For models that do not
     * support this, this setting will do nothing.
     */
    @Serializable
    protected var maxTokens = 4000

    /**
     * Weather to treat max token overflow as an error or not. If true, when the model exits due to max tokens
     * the output is allowed through regardless of if it's incomplete or not.
     *
     * Note: there must be actual output in the response, output in the reasoning will not count and will be treated
     * as an error.
     */
    @Serializable
    protected var allowMaxTokenOverflow = false

    /**
     * Context window for input tokens. If supported by a class that inherits from this, the AI model will
     * use this value to limit the amount of context to be used. Tokens will be truncated from the top or
     * bottom depending on the settings of the child class.
     */
    @Serializable
    protected var contextWindowSize = 8000

    /**
     * Context window for inputs. Allows for keyed lorebook style context, or a simple list of strings that
     * can be contextually loaded based on both weight, and allotted input token space.
     */
    @Serializable
    protected var contextWindow = ContextWindow()

    /**
     * Cache copy of this value when set by the end user. This is required to be copied for saftey when using
     * token budgeting.
     */
    @kotlinx.serialization.Transient //Has to be transient due to an insane bug in kotlinx. todo: Replace kotlinx.
    private var originalContextWindowSize = 32000

    /**
     * Snapshot of the execution-time budget fields that are temporarily rewritten while truncation is in progress.
     * These values must be restored after each run so repeated executions do not inherit mutated state.
     */
    private data class TokenBudgetExecutionSnapshot(
        val maxTokens: Int,
        val contextWindowSize: Int,
        val tokenBudgetSettings: TokenBudgetSettings?
    )

    /**
     * MiniBank object to handle the case where multiple page keys have been passed forward.
     */
    @Serializable
    protected var miniContextBank = MiniBank()

    /**
     * If true, when this pipe starts it's execution it will pull its context window from the ContextBank object
     * that allows for sharing of llm context between multiple pipes and pipelines.
     * @see com.TTT.Context.ContextBank
     */
    @Serializable
    protected var readFromGlobalContext = false

    /**
     * If true the banked context will be force pulled even if a page key was assinged.
     */
    @Serializable
    protected var pullFromBankedContext = false

    /**
     * Allows for setting advanced token budgeting that supports accounting for system prompt size,
     * binary content size, user prompt size, reasoning budget, and output size. This is useful for
     * preventing overflow and pipeline crashes due to exceeding max token limits in complex multimodal
     * setups.
     */
    @Serializable
    protected var tokenBudgetSettings : TokenBudgetSettings? = null

    /**
     * If true, the pipe will pull its context from the pipeline. This always overrides readFromGlobalContext.
     */
    @Serializable
    protected var readFromPipelineContext = false

    /**
     * If true, and this pipe has a parent pipe, it will merge any context stored within into its own. This
     * happens after global, and pipeline pulls.
     */
    @Serializable
    protected var readFromParentPipeContext = false

    /**
     * If true this pipe will automatically update the pipeline's context with its own context by
     * merging the two together.
     */
    @Serializable
    protected var updatePipelineContextOnExit = false

    /**
     * If true, when starting the pipe we'll inject the contents of the ContextWindow into the user prompt.
     */
    @Serializable
    protected var autoInjectContext = false

    /**
     * If true, truncate the context window during pipe execution.
     */
    @Serializable
    protected var autoTruncateContext = false


    /**
     * Empty user prompts can confuse the llm's and cause unexpected and undefined behavior. This behavior is
     * not seen as a failure, or even able to be seen as a failure by the system, and if allowed through will
     * likely cause catastrophic behavior derailment, and total pipeline failures that won't come into play until
     * after the content, or the agentic action has been fully performed. For obvious reasons, this could be extremely
     * destructive, and entirely unexpected to happen.
     *
     * There are several reasons a content object and user prompt can vanish. Many of which can result in budget
     * configurations, token truncation, or other actions taken. These can happen after standard checks are made
     * allowing these failures to slip through. So by defaulting this to false we can at least detect and kill the pipeline
     * and throw an error that can be caught in testing.
     *
     * Enabling this will demand the developer is explicitly intending this to be possible, and assures that they are
     * promising that it, in fact, is ok and won't break the system.
     */
    protected var allowEmptyUserPrompt = false

    /**
     * This is an even higher escalation than [allowEmptyUserPrompt]. This allows the entire content object to be blank
     * and not shut down the pipeline and throw an error.
     */
    protected var allowEmptyContentObject = false


    /**
     * If true, the merge calls for context window merging will use the emplacement method for updating the lorebook
     * automatically.
     * @see contextWindow
     */
    @Serializable
    protected var emplaceLorebook =  true

    /**
     * If true an append method will be used for updating lorebook keys. This will allow new data to be added
     * but not allow old data to be removed from the same key. This may result in contradictory information
     * being stored into a single lorebook key but is preferable in certain lorebook strategies like writing
     * assistants.
     */
    @Serializable
    protected var appendLoreBook = false

    /**
     * When true, lorebook selection uses the select-and-fill strategy during context truncation.
     */
    @Serializable
    protected var loreBookFillMode = false

    /**
     * When true, lorebook selection uses the select-and-fill strategy and reserves a split budget for the rest of
     * the top-level context window during truncation.
     */
    @Serializable
    protected var loreBookFillAndSplitMode = false

    /**
     * If true when merging context windows we will replace the converse history with the incoming converse history.
     */
    @Serializable
    protected var emplaceConverseHistory = false

    /**
     * If true, overrides the [emplaceConverseHistory] and only allows emplacement if this context window's converse
     * history is null.
     */
    @Serializable
    protected var emplaceConverseHistoryOnlyIfNull = false

        /**

         * Some models have thinking modes, also known as reasoning. If true TPipe will enable model thinking/reasoning

         * on models where it can be enabled or disabled.

         */

        @Serializable

        protected var useModelReasoning = false

    

        /**

         * If true, model reasoning content will be streamed to the registered callbacks

         * during generation. This applies to models that produce reasoning in a separate

         * stream or field from the main response text.

         */

        @Serializable

        protected var streamModelReasoning: Boolean = true

    

        /**

         * Version of model reasoning that uses a designation of how many tokens to afford to model reasoning.

         */
    @Serializable
    protected var modelReasoningSettingsV2 = 5000

    /**
     * String version for certain models that have more pedantic reasoning settings requiring api and vendor specific
     * methods for setting reasoning. This is less common than on/off, or defining a max token allowance for
     * reasoning.
     */
    @Serializable
    protected var modelReasoningSettingsV3 = ""

    /**
     * Page key of the context bank to pull from. This allows separation of context types and use cases
     * while still enabling them all to be shared globally.
     */
    @Serializable
    protected var pageKey = ""

    /**
     * List of multiple page keys. This will become populted if the user supplies more than one page key to the
     * page key setter function.
     * @see setPageKey
     */
    @Serializable
    protected var pageKeyList = mutableListOf<String>()

    /**
     * Determines how context window truncation is handled. Allows for truncation
     * from the beginning, end, or both until the context window size is met.
     */
    @Serializable
    protected var contextWindowTruncation = ContextWindowSettings.TruncateTop

    /**
     * If true the context window will have its contents squashed into a single string that can then
     * be directly truncated based on tokenizer settings.
     */
    @Serializable
    protected var truncateContextAsString = false

    /**
     * Used for Novel AI models. If supported, this will be used to limit the amount of repetition in the
     * generated text.
     */
    @Serializable
    protected var repetitionPenalty = 0.0

    /**
     * Presence penalty for OpenAI-compatible models. Encourages topic diversity.
     * Range: -2.0 to 2.0. Positive values encourage new topics.
     * 
     * @see https://platform.openai.com/docs/api-reference/chat/create#chat-create-presence_penalty
     */
    @Serializable
    protected open var presencePenalty: Double = 0.0

    /**
     * Seed for deterministic generation. If set, model will attempt to generate
     * the same output for the same input. Not all models support this feature.
     * 
     * @see https://platform.openai.com/docs/api-reference/chat/create#chat-create-seed
     */
    @Serializable
    protected open var seed: Int? = null

    /**
     * Logit bias for token probability manipulation. Maps token IDs to bias values.
     * Range: -100 to 100. Positive values increase likelihood, negative decrease.
     * 
     * @see https://platform.openai.com/docs/api-reference/chat/create#chat-create-logit_bias
     */
    @Serializable
    protected var logitBias: Map<Int, Double> = emptyMap()

    /**
     * Number of completions to generate. Most models only support n=1.
     * Higher values increase cost proportionally.
     * 
     * @see https://platform.openai.com/docs/api-reference/chat/create#chat-create-n
     */
    @Serializable
    protected var n: Int = 1

    /**
     * User identifier for tracking and abuse monitoring. Helps OpenAI monitor
     * for abuse and provides better support.
     * 
     * @see https://platform.openai.com/docs/api-reference/chat/create#chat-create-user
     */
    @Serializable
    protected var user: String = ""


    /**
     * If supported, this allows for various substrings to trigger a stop sequence in
     * the AI's prompt generation.
     */
    @Serializable
    protected var stopSequences = listOf<String>()

    /**
     * Function called just prior to starting the loading of context, merging input with the base user prompt
     * and pre-validation stages. This allows for a chance to perform actions against the input content object,
     * or to copy or store its contents elsewhere before it gets appended to, merged, or altered with extra
     * bloat or unwanted context.
     */
    @kotlinx.serialization.Transient
    var preInitFunction: (suspend (content: MultimodalContent) -> Unit)? = null

    /**
     * Pre validation step function that allows for last minute context window adjustment after the context window
     * has been fully pulled and is settled. Extremely useful for complex tasks like needing to pull very specific
     * values from the context window, or needing to do further truncation, or very fine and direct adjustments
     * prior to the context being injected into the user prompt.
     */
    @kotlinx.serialization.Transient
    var preValidationFunction: (suspend (context: ContextWindow, content: MultimodalContent?) -> ContextWindow)? = null

    /**
     * Version of pre validation step function that allows for a mini bank to be passed tested against instead
     * of a regular context window. This is useful when there are cases where multiple pages of global context
     * needed to be pulled and used.
     */
    @kotlinx.serialization.Transient
    var preValidationMiniBankFunction: (suspend (context: MiniBank, content: MultimodalContent?) -> MiniBank)? = null



    /**
     * Pre invoke function that can be run to determine if the input this pipe has received requires us to
     * skip over this pipe and exit early. This exit is not considered a failure or error and allows the
     * pipeline to continue forward. This useful for a pipe in the pipeline that may be optional but is not
     * known until the actual runtime of the pipeline.
     *
     * @param content reference to the prompt data being sent into this pipe. Allows this function to
     * set where we should jump to upon exiting. Beware that changing any part of the content object will
     * affect the actual data the pipe has prior to making its api call to it's bound llm.
     *
     * @returns Returns true if we should exit early. Otherwise, continue the pipeline.
     */
    @kotlinx.serialization.Transient
    var preInvokeFunction: (suspend (content: MultimodalContent) -> Boolean)? = null

    /**
     * Post-generate function that is called exactly after the llm call has ran to generate content.
     * Allows for immediate action to be taken by the developer prior to the execution of any DITL validator
     * functions or pipes. This is especially useful for caching output in complex validator pipe/function
     * setups where the text output may be changed by the validation step before moving forward to any kind
     * of branch failure states.
     */
    @kotlinx.serialization.Transient
    var postGenerateFunction: (suspend (content: MultimodalContent) -> Unit)? = null

    /**
     * Optional function to validate the output of the AI model. If the function returns true, the pipeline
     * will continue to the next pipe. If the function returns false, the pipeline will exit here.
     */
    @kotlinx.serialization.Transient
    var validatorFunction: (suspend (content: MultimodalContent) -> Boolean)? = null

    /**
     * Optional function to help debug when exceptions get thrown. Includes the exception thrown and the state
     * of the content object when thrown.
     */
    @kotlinx.serialization.Transient
    var exceptionFunction: (suspend (content: MultimodalContent, exception: Throwable) -> Unit)? = null

    /**
     * Optional function to transform the output of the AI model. This can be used to pull apart content that
     * has been returned, provide additional context, or otherwise transform the output of the AI model.
     */
    @kotlinx.serialization.Transient
    var transformationFunction: (suspend (content: MultimodalContent) -> MultimodalContent)? = null

    /**
     * Optional function to handle validation failures. If the function returns a valid MultimodalContent,
     * the pipeline will continue. If it returns content marked for termination, the pipeline will exit.
     */
    @kotlinx.serialization.Transient
    var onFailure: (suspend (original: MultimodalContent, processed: MultimodalContent) -> MultimodalContent)? = null

    /**
     * Optional pipe to validate the output of the AI model. This can be deployed instead of a validator function
     * if the check is either simple enough for another AI to handle it, or validation requirements are too
     * abstract to be tested with code. Will be invoked the validator function is not assigned.
     */
    @kotlinx.serialization.Transient
    var validatorPipe : Pipe? = null


    /**
     * Optional pipe to transform the output of the AI model. This can be deployed instead of a transformation
     * function if the transformation is either simple enough for another AI to handle it, or transformation
     * requirements are too abstract to be tested with code. Will be invoked the transformation function is not
     * assigned.
     */
    @kotlinx.serialization.Transient
    var transformationPipe : Pipe? = null


    /**
     * Optional alternative to the branch function. If the failure function is not assigned, the branchPipe pipe
     * will be invoked if able. This allows another call to an AI model to perform repair or transformation in the event
     * of a validation failure.
     */
    @kotlinx.serialization.Transient
    var branchPipe : Pipe? = null

    /**
     * Optional pipe that is able to perform chain of thought, thinking style, and model reasoning behavior.
     * If non-null this pipe will be invoked prior to running the main llm on this pipe. It will then be used
     * to reason over the user prompt prior to provide further context and implement chain of thought mechanisms
     * even on models that do not support it.
     */
    @kotlinx.serialization.Transient
    var reasoningPipe : Pipe? = null

    /**
     * Flag to indicate when this pipe is executing as a reasoning pipe.
     * Used to modify trace behavior to mark output as reasoningContent.
     */
    @kotlinx.serialization.Transient
    private var isExecutingAsReasoningPipe = false
    
    /**
     * Flag to prevent duplicate reasoning content in the same execution cycle.
     */
    @kotlinx.serialization.Transient
    private var reasoningContentAlreadyTraced = false



    /**
     * Dictionary truncate parameters.
     * @see com.TTT.Context.Dictionary
     * @see contextWindow
     * @see com.TTT.Context.LoreBook
     */
    protected var multiplyWindowSizeBy: Int = 0
    protected var countSubWordsInFirstWord: Boolean = true
    protected var favorWholeWords: Boolean = true
    protected var countOnlyFirstWordFound: Boolean = false
    protected var splitForNonWordChar: Boolean = true
    protected var alwaysSplitIfWholeWordExists: Boolean = false
    protected var countSubWordsIfSplit: Boolean = true
    protected var nonWordSplitCount: Int = 2
    protected var tokenCountingBias: Double = 0.0

    /**
     * Tracing system properties for debugging and monitoring pipe execution.
     */
    protected var tracingEnabled = false
    @kotlinx.serialization.Transient
    protected var traceConfig = TraceConfig()
    protected var pipeId = UUID.randomUUID().toString()

    /**
     * Configuration for memory introspection tools. If non-null, introspection tools
     * will be enabled and restricted by this config.
     */
    @kotlinx.serialization.Transient
    protected var memoryIntrospectionConfig: MemoryIntrospectionConfig? = null

    /**
     * Set of active trace IDs for this pipe. Allows casting trace events to multiple streams.
     */
    @kotlinx.serialization.Transient
    private val activeTraceIds: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /**
     * Legacy property for backward compatibility.
     * Use [addTraceId] and [removeTraceId] for multi-stream support.
     */
    @kotlinx.serialization.Transient
    var currentPipelineId: String?
        get() = activeTraceIds.firstOrNull()
        set(value) {
            activeTraceIds.clear()
            if(value != null)
            {
                activeTraceIds.add(value)
            }
        }

    /**
     * Enables optional comprehensive token usage tracking.
     */
    @Serializable
    protected var comprehensiveTokenTracking = false

    /**
     * Stores token usage details when comprehensive tracking is enabled.
     */
    @kotlinx.serialization.Transient
    protected var pipeTokenUsage = TokenUsage()

    /**
     * Stores the most recent error that occurred during pipe execution.
     * This allows programmatic access to failure information.
     */
    @kotlinx.serialization.Transient
    var lastError: PipeError? = null

    /**
     * Allows for a complex object or container to be linked to this pipe. If this is not null, when this pipe executes,
     * it will attempt to execute the container via the interface instead. Then return that result forward.
     */
    var containerPtr : P2PInterface? = null

    /**
     * Page key to retrieve a todo list from ContextBank. This can be used to inject a todo list as context at runtime.
     * The todo list will be injected as a mini-bank key, or single context bank key if it's the only key. The contents
     * of the todo list will be serialized and stored as index 0 of the context elements.
     */
    var todoPageKey: String = ""

    /**
     * Instructions to replace the default explanation for todo list injection.
     */
    var todoListInstructions = ""

    /**
     * Denotes weather to check for todo list injection.
     */
    var injectTodoList = false

    /**
     * If true, automatically cache the input of the pipe upon startup.
     */
    @kotlinx.serialization.Transient
    var cacheInput = false

    /**
     * If true, save to the content's snapshot system upon startup of the pipe.
     */
    var saveSnapshot = false

    /**
     * If true, the content will be wrapped into a converse history object if the input content was found to be already
     * in the form of a converse history object. This is useful for automating multiple pipes inside a pipeline that
     * need to use converse history objects as input. If a pipe does use converse History as the output the automatic
     * chaining of this will be broken and the coder will need to manually intervene.
     */
    protected var wrapContentWithConverseHistory = false

    /**
     * Defines the converse role this pipe uses if we're wrapping content automatically.
     */
    protected var converseRole: ConverseRole = ConverseRole.agent

    /**
     * Allow arbitrary data to be stored on this pipe class. Useful for advanced features such as tracking
     * TPipe internal reasoning, advanced container class management, and other features that are add-ons and
     * extra features rather than core functionality of TPipe and the llm's they abstract.
     *
     * Note: Any to Any is unsafe to serialize so this data won't be copied using serialization.
     */
    @kotlinx.serialization.Transient
    val pipeMetadata = mutableMapOf<Any, Any>()

//============================================= constructor ==========================================================//

    /**
     * Sets the name for this pipe. This name is useful for debugging, tracing, and identifying
     * specific pipes within pipelines during execution and error reporting.
     *
     * @param name The name to assign to this pipe.
     * @return This Pipe object for method chaining.
     */
    fun setPipeName(name: String): Pipe {
        this.pipeName = name
        return this
    }

    /**
     * Assigns a reference to the pipeline this pipe is a part of. This is optional but can be useful for
     * logic that needs to behave differently depending on the pipeline.
     *
     * @param pipeline The pipeline to be assigned as a reference.
     *
     * @return This object for chaining.
     */
    fun setPipelineRef(pipeline: Pipeline?): Pipe {
        this.pipelineRef = pipeline
        containerObject = pipeline
        return this
    }

    /**
     * Set the reference to this pipe's parent pipe. Required by DITL pipes.
     */
    protected fun setParentPipe(ref: Pipe) : Pipe {
        parentPipeRef = ref
        return this
    }

    /**
     * Gets or creates the streaming callback manager for this pipe.
     * Lazy-initializes the manager on first access.
     *
     * @return The streaming callback manager instance
     */
    fun obtainStreamingCallbackManager(): StreamingCallbackManager
    {
        if(streamingCallbackManager == null)
        {
            streamingCallbackManager = StreamingCallbackManager()
        }
        return streamingCallbackManager!!
    }

    /**
     * Emits a streaming chunk to all registered callbacks.
     * Provides default no-op implementation for providers that don't support streaming.
     * Subclasses can override to add provider-specific behavior.
     *
     * @param chunk The text chunk to emit
     */
    protected open suspend fun emitStreamingChunk(chunk: String)
    {
        streamingCallbackManager?.emitToAll(chunk)
    }

    /**
     * Enables comprehensive token usage tracking for this pipe and its child pipes.
     * When enabled, this pipe will track detailed token consumption including input tokens,
     * output tokens, and aggregated usage from all nested child pipes. This provides
     * comprehensive visibility into token usage across complex pipe hierarchies.
     *
     * @return This Pipe object for method chaining.
     */
    fun enableComprehensiveTokenTracking(): Pipe
    {
        //Enable the comprehensive tracking flag.
        comprehensiveTokenTracking = true
        
        //Initialize a fresh TokenUsage object to start clean tracking.
        pipeTokenUsage = TokenUsage()
        
        return this
    }

    /**
     * Disables comprehensive token usage tracking and clears stored usage data.
     * This method turns off detailed token tracking and resets all stored usage
     * data to free memory and disable the tracking overhead.
     *
     * @return This Pipe object for method chaining.
     */
    fun disableComprehensiveTokenTracking(): Pipe
    {
        //Disable the comprehensive tracking flag.
        comprehensiveTokenTracking = false
        
        //Clear stored usage data to free memory.
        pipeTokenUsage = TokenUsage()
        
        return this
    }

    /**
     * Get the pipe that own's this pipe. Common in DITL pipe setups.
     */
    fun getParentPipe() : Pipe?
    {
        return parentPipeRef
    }

    /**
     * Checks if this pipe is configured as a reasoning pipe for its parent.
     * A reasoning pipe is one that has been assigned to a parent pipe using setReasoningPipe().
     * 
     * @return True if this pipe is the reasoning pipe of its parent, false otherwise
     */
    fun isReasoningPipe(): Boolean
    {
        val parent = getParentPipe()
        return parent?.reasoningPipe == this
    }

    /**
     * Get the context window object for this pipe.
     */
    fun getContextWindowObject() : ContextWindow
    {
        return contextWindow
    }

    /**
     * Get the mini bank object for this pipe.
     */
    fun getMiniContextBankObject() : MiniBank
    {
        return miniContextBank
    }

    /**
     * Sets the model to be used by this pipe. This is useful for logic that needs to behave differently
     * depending on the model being used. The model variable does not have any internal functionality and is intended
     * to be referenced by validation functions.
     *
     * @param provider The model to be assigned to this pipe.
     * @return This Pipe object for method chaining.
     */
    fun setProvider(provider: ProviderName): Pipe {
        this.provider = provider
        return this
    }

    /**
     * Access function to get the value of our assigned llm provider.
     */
    fun getProviderEnum() : ProviderName = provider


    /**
     * Sets the model to be used by this pipe. This function assigns the model name
     * to the internal model variable, which can be utilized by validation functions
     * or other logic that depends on the model being used.
     *
     * @param modelName The name of the model to be used by this pipe.
     * @return This Pipe object for method chaining.
     */
    fun setModel(modelName : String): Pipe {
        this.model = modelName
        return this
    }

    /**
     * Get the name of the current model this pipe has set.
     */
    fun getModelName() : String
    {
        return model
    }


    /**
     * Sets the prompt mode for this pipe. This determines how prompts should be handled,
     * whether the AI model supports chat with internal context or only single prompt interfaces.
     * It also handles whether the context should be stored and managed directly by TPipe.
     *
     * In the event the does not support denoting chat vs single prompt and internal context has not been set,
     * This function will do nothing.
     *
     * @param promptMode The prompt mode to set, indicating how TPipe should handle prompts.
     * @return This Pipe object for method chaining.
     */
    fun setPromptMode(promptMode: PromptMode): Pipe {
        this.promptMode = promptMode
        return this
    }


    /**
     * Sets the system prompt to be used by the AI model. This is a prompt that should provide information
     * about what the AI model should generate. If the AI does not support native json input and output,
     * this function will also use prompt injection to attempt to force json input and output.
     *
     * @param prompt The prompt to be used by the AI model.
     *
     * @since The system prompt should be set last due to it's use of prompt injection. Ensure the PCP context
     * and json inputs and outputs have been defined before calling this.
     *
     * @see setJsonInput
     * @see setJsonOutput
     * @see PcpContext
     *
     * @return This Pipe object for method chaining.
     */
    fun setSystemPrompt(prompt: String): Pipe 
    {
        this.systemPrompt = prompt
        rawSystemPrompt = prompt

        // Detect merged mode
        val hasPcpTools = !pcpContext.tpipeOptions.isEmpty() || 
                          !pcpContext.httpOptions.isEmpty() || 
                          !pcpContext.stdioOptions.isEmpty() || 
                          pcpContext.pythonOptions.availablePackages.isNotEmpty()

        val hasJsonOutput = !this.supportsNativeJson && jsonOutput.isNotEmpty()
        val useMergedMode = hasPcpTools && hasJsonOutput

        /**
         * MERGED MODE: Both JSON output and PCP tools configured.
         */
        if(useMergedMode)
        {
            val pcpAsJson = serialize(pcpContext, false)
            val pcpRequestExample = examplePromptFor(PcPRequest::class)
            
            val mergedInstructions = """\n\nYou must return your output in Json format matching this schema:
                |${jsonOutput}
                |
                |All variables in the json output must have valid values that match their declared types. 
                |Never use null as a value - instead provide appropriate default values: empty strings for text fields, 
                |empty arrays for lists, empty objects for nested structures, 0 for numbers, and false for booleans.
                |
                |You may also take actions using the Pipe Context Protocol if needed.
                |Available tools:
                |${pcpAsJson}
                |
                |Each tool's parameters include an "isRequired" field - you MUST provide all parameters where isRequired is true, 
                |while parameters where isRequired is false are optional and may be omitted.
                |
                |If you wish to call any tools, return an array of the following json:
                |[${pcpRequestExample}]
                |
                |Tool calls are optional - only include them if you need to use a tool.
                |
                |IMPORTANT - How to pass arguments when calling tools:
                |The 'params' field inside each tool definition describes the function's expected parameters (names, types, descriptions).
                |It is for reference only. Do NOT place argument values inside 'params' when making a call.
                |
                |To call a TPipe function, set 'tPipeContextOptions.functionName' to the function name and pass argument values using one of:
                |- 'callParams': A map of named arguments. Example: {"paramName": "value", "otherParam": "value2"}
                |- 'argumentsOrFunctionParams': A list of positional arguments in the order defined by the function signature. Example: ["value1", "value2"]
                |'callParams' is preferred. If both are provided, 'callParams' values override positional arguments.
                |
                |To call a stdio command, populate 'stdioContextOptions' with the command and arguments.
                |To call an HTTP endpoint, populate 'httpContextOptions' with the URL, method, and request body.
                |To execute Python, Kotlin, or JavaScript code, populate the respective context options ('pythonContextOptions', 'kotlinContextOptions', or 'javascriptContextOptions').
            """.trimMargin()
            
            systemPrompt = systemPrompt + mergedInstructions
        }

        /**
         * PCP-ONLY MODE: Tools configured but no JSON output requirement.
         */
        else if(hasPcpTools && !hasJsonOutput)
        {
            val pcpAsJson = serialize(pcpContext, false)
            val pcpRequestAsJson = examplePromptFor(PcPRequest::class)

            val pcpContextRequirement = """\n\nYou may take actions to carry out your task using the Pipe Context Protocol.
                |The Pipe Context Protocol is a standardized way to communicate with user's machine. The protocol is as follows:
                |${pcpAsJson}
                |
                |The above Pipe Context Protocol json defines each tool you can call and what it can do. Each tool's parameters 
                |include an "isRequired" field - you MUST provide all parameters where isRequired is true, while parameters 
                |where isRequired is false are optional and may be omitted.
                |
                |If you wish to call or use any tools provided to you return an array of the following json for each tool you wish to call:
                |${pcpRequestAsJson}
                |
                |When returning any json requests for tools. You must always follow the json schema exactly.
                |
                |IMPORTANT - How to pass arguments when calling tools:
                |The 'params' field inside each tool definition describes the function's expected parameters (names, types, descriptions).
                |It is for reference only. Do NOT place argument values inside 'params' when making a call.
                |
                |To call a TPipe function, set 'tPipeContextOptions.functionName' to the function name and pass argument values using one of:
                |- 'callParams': A map of named arguments. Example: {"paramName": "value", "otherParam": "value2"}
                |- 'argumentsOrFunctionParams': A list of positional arguments in the order defined by the function signature. Example: ["value1", "value2"]
                |'callParams' is preferred. If both are provided, 'callParams' values override positional arguments.
                |
                |To call a stdio command, populate 'stdioContextOptions' with the command and arguments.
                |To call an HTTP endpoint, populate 'httpContextOptions' with the URL, method, and request body.
                |To execute Python, Kotlin, or JavaScript code, populate the respective context options ('pythonContextOptions', 'kotlinContextOptions', or 'javascriptContextOptions').
            """.trimMargin()

            systemPrompt = systemPrompt + pcpContextRequirement
        }

        if(!this.supportsNativeJson)
        {
            var jsonRequirements = ""
            
            if(jsonInput.isNotEmpty())
            {
                jsonRequirements += """\n\n The user will provide input in the form of Json. 
                    |The json input is as follows: ${jsonInput}
                """.trimMargin()
            }
            
            if(jsonOutput.isNotEmpty())
            {
                jsonRequirements += """\n\n You must return your output only in Json format. You may not generate
                    |any text that is not in json format. You may not generate any text before, or after the json output. 
                    |All variables in the json output must have valid values that match their declared types. 
                    |Never use null as a value - instead provide appropriate default values: empty strings for text fields, 
                    |empty arrays for lists, empty objects for nested structures, 0 for numbers, and false for booleans. 
                    |
                    |The json output schema is as follows: ${jsonOutput}
                    |
                    |You must only return json that matches the variable types, and names in this schema exactly. Do not include any text above or below the json.
                    |Do not change the name of any json variables. Do not change the name of the json object itself either.
                    |CRITICAL: Every field must contain a valid value of the correct type - never use null values.
                """.trimMargin()
            }

            systemPrompt = systemPrompt + jsonRequirements
        }

        applySemanticDecompressionPrelude()

        return this
    }


    /**
     * Copy the system prompt and merge it with the user prompt, storing both into a converse history object
     * that is better suited for AI models that are more likely to listen to and abide by the user prompt
     * rather than the system prompt.
     */
    fun copySystemPromptToUserPrompt() : Pipe
    {
        copySystemToUserPrompt = true
        return this
    }

    /**
     * Enable automatic semantic compression for the user prompt.
     *
     * This merges with any existing token-budget configuration instead of replacing it, so callers can opt in
     * without losing the rest of their budgeting settings.
     *
     * @return This Pipe object for method chaining.
     */
    fun enableSemanticCompression(): Pipe
    {
        tokenBudgetSettings = tokenBudgetSettings?.copy(compressUserPrompt = true)
            ?: TokenBudgetSettings(compressUserPrompt = true)
        return this
    }

    /**
     * Enable the future semantic decompression hook in system-prompt application.
     *
     * This only flips the flag used by [applySystemPrompt]; the actual decompression prelude is injected at the
     * top of the rebuilt system prompt when semantic compression is also enabled.
     *
     * @return This Pipe object for method chaining.
     */
    fun enableSemanticDecompression(): Pipe
    {
        semanticDecompressionEnabled = true
        return this
    }

    /**
     * Prepends the semantic decompression prelude when semantic compression is active.
     *
     * The prelude is intentionally added ahead of the rebuilt system prompt so the model sees the decompression
     * contract before any of the other system instructions or protocol injections.
     */
    private fun applySemanticDecompressionPrelude()
    {
        if(semanticDecompressionEnabled && tokenBudgetSettings?.compressUserPrompt == true)
        {
            systemPrompt = buildSemanticDecompressionInstructions() + "\n\n" + systemPrompt
        }
    }

    /**
     * Copy the system prompt and merge it with the user prompt, storing both into a converse history object
     * that is better suited for AI models that are more likely to listen to and abide by the user prompt
     * rather than the system prompt.
     */
   private fun copySystemPrompt(userPrompt: MultimodalContent) : Pipe
    {
       applySystemPrompt() //Rebuild system prompt prior to our copy.

       //Construct our full container to manage this merge.
        val converseHistory = ConverseHistory()

        //We need to save the system prompt in a multimodal content object.
        val converseModal = MultimodalContent()
        converseModal.text = systemPrompt

        //Add the new content under the system role into the converse object.
        val systemPromptConverseData = ConverseData(role = ConverseRole.system, converseModal)

        //Store at top level so that our system prompt comes first.
        converseHistory.add(systemPromptConverseData)

        val userPromptConverseData = ConverseData(role = ConverseRole.user, userPrompt)
        converseHistory.add(userPromptConverseData)

        //Push back to the user prompt now merging the two together.
        val fullPrompt = serializeConverseHistory(converseHistory)
        userPrompt.text = fullPrompt

        //Finally clear away the system prompt to stop duplication.
        systemPrompt = ""

        return this
    }

    /**
     * Function to apply or re-apply the system prompt and other injections. Allows the user to re-apply after making
     * any out of order changes.
     */
    fun applySystemPrompt(content: MultimodalContent? = null) : Pipe
    {
        systemPrompt = rawSystemPrompt //Restore raw system prompt.

        if(!this.supportsNativeJson)
        {
            var jsonRequirements = ""

            val defaultJsonInput = """\n\n The user will provide input in the form of Json. 
                    |The json input is as follows: ${jsonInput}
                """.trimMargin()

            /**
             * Bind the chosen input instructions, or the default here if empty.
             */
            if(jsonInput.isNotEmpty())
            {
                jsonRequirements += jsonInputInstructions.ifEmpty { defaultJsonInput }
            }

            /**
             * Bind the chosen json output injection instructions, or the default if empty here.
             */
            if(jsonOutput.isNotEmpty())
            {
                val defaultJsonOutput = """\n\n You must return your output only in Json format. You may not generate
                    |any text that is not in json format. You may not generate any text before, or after the json output. 
                    |All variables in the json output must have valid values that match their declared types. 
                    |Never use null as a value - instead provide appropriate default values: empty strings for text fields, 
                    |empty arrays for lists, empty objects for nested structures, 0 for numbers, and false for booleans. 
                    |
                    |The json output schema is as follows: ${jsonOutput}
                    |
                    |You must only return json that matches the variable types, and names in this schema exactly. Do not include any text above or below the json.
                    |Do not change the name of any json variables. Do not change the name of the json object itself either.
                    |CRITICAL: Every field must contain a valid value of the correct type - never use null values.
                """.trimMargin()


                /**
                 * Middle prompt allows injection after the json input schema, but before the json output schema.
                 */
                jsonRequirements += (middlePromptInstructions.ifEmpty { "" }) + jsonOutputInstructions.ifEmpty { defaultJsonOutput }
            }

            systemPrompt = systemPrompt + jsonRequirements
        }

        //todo: Add support for a custom pcp instruction set. Low prio vs other options.

        // Detect prompt injection mode
        val hasPcpTools = !pcpContext.tpipeOptions.isEmpty() || 
                          !pcpContext.httpOptions.isEmpty() || 
                          !pcpContext.stdioOptions.isEmpty() || 
                          pcpContext.pythonOptions.availablePackages.isNotEmpty()

        val hasJsonOutput = !this.supportsNativeJson && jsonOutput.isNotEmpty()
        val useMergedMode = hasPcpTools && hasJsonOutput

        /**
         * MERGED MODE: Both JSON output and PCP tools configured.
         * JSON output is REQUIRED, tool calls are OPTIONAL.
         * This resolves the mutual exclusivity between JSON output and PCP tool requests.
         */
        if(useMergedMode)
        {
            val pcpAsJson = serialize(pcpContext, false)
            val pcpRequestExample = examplePromptFor(PcPRequest::class)
            
            val defaultMergedInstructions = """
                |
                |You must return your output in Json format matching this schema:
                |${jsonOutput}
                |
                |All variables in the json output must have valid values that match their declared types. 
                |Never use null as a value - instead provide appropriate default values: empty strings for text fields, 
                |empty arrays for lists, empty objects for nested structures, 0 for numbers, and false for booleans.
                |
                |You may also take actions using the Pipe Context Protocol if needed.
                |Available tools:
                |${pcpAsJson}
                |
                |If you wish to call any tools, return an array of the following json:
                |[${pcpRequestExample}]
                |
                |Tool calls are optional - only include them if you need to use a tool.
                |
                |IMPORTANT - How to pass arguments when calling tools:
                |The 'params' field inside each tool definition describes the function's expected parameters (names, types, descriptions).
                |It is for reference only. Do NOT place argument values inside 'params' when making a call.
                |
                |To call a TPipe function, set 'tPipeContextOptions.functionName' to the function name and pass argument values using one of:
                |- 'callParams': A map of named arguments. Example: {"paramName": "value", "otherParam": "value2"}
                |- 'argumentsOrFunctionParams': A list of positional arguments in the order defined by the function signature. Example: ["value1", "value2"]
                |'callParams' is preferred. If both are provided, 'callParams' values override positional arguments.
                |
                |To call a stdio command, populate 'stdioContextOptions' with the command and arguments.
                |To call an HTTP endpoint, populate 'httpContextOptions' with the URL, method, and request body.
                |To execute Python, Kotlin, or JavaScript code, populate the respective context options ('pythonContextOptions', 'kotlinContextOptions', or 'javascriptContextOptions').
            """.trimMargin()
            
            // Allow custom override
            val actualMergedInstructions = mergedPcpJsonInstructions.ifEmpty { defaultMergedInstructions }
            systemPrompt = systemPrompt + actualMergedInstructions
        }

        /**
         * PCP-ONLY MODE: Tools configured but no JSON output requirement.
         * Bind the pcp context and schema to the system prompt.
         */
        else if(hasPcpTools && !hasJsonOutput)
        {
            val pcpAsJson = serialize(pcpContext, false)
            val pcpRequestAsJson = examplePromptFor(PcPRequest::class)

            val defaultPcpInstructions = """
                |
                |You may take actions to carry out your task using the Pipe Context Protocol.
                |The Pipe Context Protocol is a standardized way to communicate with user's machine. The protocol is as follows:
                |${pcpAsJson}
                |
                |The above Pipe Context Protocol json defines each tool you can call and what it can do. You may use any of it
                |to complete the task the user has requested of you.
                |
                |If you wish to call or use any tools provided to you return an array of the following json for each tool you wish to call:
                |${pcpRequestAsJson}
                |
                |When returning any json requests for tools. You must always follow the json schema exactly.
                |
                |IMPORTANT - How to pass arguments when calling tools:
                |The 'params' field inside each tool definition describes the function's expected parameters (names, types, descriptions).
                |It is for reference only. Do NOT place argument values inside 'params' when making a call.
                |
                |To call a TPipe function, set 'tPipeContextOptions.functionName' to the function name and pass argument values using one of:
                |- 'callParams': A map of named arguments. Example: {"paramName": "value", "otherParam": "value2"}
                |- 'argumentsOrFunctionParams': A list of positional arguments in the order defined by the function signature. Example: ["value1", "value2"]
                |'callParams' is preferred. If both are provided, 'callParams' values override positional arguments.
                |
                |To call a stdio command, populate 'stdioContextOptions' with the command and arguments.
                |To call an HTTP endpoint, populate 'httpContextOptions' with the URL, method, and request body.
                |To execute Python, Kotlin, or JavaScript code, populate the respective context options ('pythonContextOptions', 'kotlinContextOptions', or 'javascriptContextOptions').
            """.trimMargin()

            // Allow custom override
            val actualPcpInstructions = pcpDescription.ifEmpty { defaultPcpInstructions }
            systemPrompt = systemPrompt + actualPcpInstructions
            
            // Add Kotlin-specific security boundaries if Kotlin is configured
            if(pcpContext.kotlinOptions.allowTpipeIntrospection ||
                pcpContext.kotlinOptions.allowHostApplicationAccess ||
                pcpContext.kotlinOptions.allowedImports.isNotEmpty() ||
                pcpContext.kotlinOptions.blockedImports.isNotEmpty())
            {
                val kotlinInstructions = PcpInstructionGenerator.generateKotlinInstructions(
                    pcpContext.kotlinOptions, 
                    pcpContext
                )
                val kotlinGuide = PcpInstructionGenerator.generateCodeExecutionGuide(pcpContext.kotlinOptions)
                systemPrompt = systemPrompt + "\n\n" + kotlinInstructions + "\n\n" + kotlinGuide
            }
            
            // Add Python-specific security boundaries if Python is configured
            if(pcpContext.pythonOptions.availablePackages.isNotEmpty() ||
                pcpContext.pythonOptions.workingDirectory.isNotEmpty() ||
                pcpContext.pythonOptions.permissions.isNotEmpty())
            {
                val pythonInstructions = PcpInstructionGenerator.generatePythonInstructions(
                    pcpContext.pythonOptions,
                    pcpContext
                )
                val pythonGuide = PcpInstructionGenerator.generatePythonCodeExecutionGuide()
                systemPrompt = systemPrompt + "\n\n" + pythonInstructions + "\n\n" + pythonGuide
            }
            
            // Add JavaScript-specific security boundaries if JavaScript is configured
            if(pcpContext.javascriptOptions.allowedModules.isNotEmpty() ||
                pcpContext.javascriptOptions.workingDirectory.isNotEmpty() ||
                pcpContext.javascriptOptions.permissions.isNotEmpty())
            {
                val javascriptInstructions = PcpInstructionGenerator.generateJavaScriptInstructions(
                    pcpContext.javascriptOptions,
                    pcpContext
                )
                val javascriptGuide = PcpInstructionGenerator.generateJavaScriptCodeExecutionGuide()
                systemPrompt = systemPrompt + "\n\n" + javascriptInstructions + "\n\n" + javascriptGuide
            }
        }

        if(!p2pAgentDescriptors.isNullOrEmpty())
        {
            //Available agents to call and schema to use to call them with.
            val agentList = serialize(p2pAgentDescriptors, false)
            val agentRequestSchema = examplePromptFor(AgentRequest::class)

            var defaultP2PDescription = """\n\nYou may request another agent to help continue your task using the Pipe-to-Pipe Agent Protocol.
                |The Pipe-to-Pipe Agent Protocol allows you to delegate specific tasks to specialized agents. The available agents are:
                |${agentList}
                |
                |The above json defines each agent you can call and what it can do. You may use any of them
                |to complete the task the user has requested of you.
                |
                |If you wish to call or use any agents provided to you return an array of the following json for each agent you wish to call:
                |${agentRequestSchema}
                |
                |When returning any json requests for agents. You must always follow the json schema exactly. You may not issue any
                |calls to agents that do not exist. You may not change the name of the agent you are calling. You must call the agent
                |exactly as it is named in the json and fill the json output that is expected EXACTLY.
            """.trimMargin()

            //Allow this to also be overridden.
            if(p2pAgentRequestsDescription.isNotEmpty()) defaultP2PDescription = p2pAgentRequestsDescription

            systemPrompt = systemPrompt + defaultP2PDescription
        }

        //Bind context instructions and context json schema.
        if(autoInjectContext)
        {
            systemPrompt = "$systemPrompt \n\n $contextInstructions \n\n ${selectGlobalContextMode()}"
        }

        //Inject todo list and focus point if supplied.
        if(injectTodoList)
        {
            val todoListObj = ContextBank.getPagedTodoList(todoPageKey)
            if(!todoListObj.isEmpty())
            {
                val defaultTodoListInstructions = """You will be provided with a todo list that has a list of tasks
                    |you have been asked to complete. Each element on the list will contain a description of the task,
                    |the requirements to verify it has been completed, and weather it has been completed or not. The
                    |todo list is as follows:
                """.trimMargin()

                val actualTodoListInstructions = todoListInstructions.ifEmpty { defaultTodoListInstructions }

                val todoListInjector = "${actualTodoListInstructions}\n\n${serialize(todoListObj)}"
                systemPrompt+= "\n\n${todoListInjector}"

                val taskNumber = content?.metadata["todoTaskNumber"] ?: -1
                if(taskNumber as Int > 0)
                {
                    val task = todoListObj.find(taskNumber)
                    if(task != null)
                    {
                        val focusInstructions = """The current task you must focus on from this todo list is:"""
                        systemPrompt+= "\n\n${focusInstructions}\n\n${serialize(task)}"
                    }
                }
            }
        }

        //Bind system prompt footer if valid.
        if(footerPrompt.isNotEmpty())
        {
            systemPrompt = "$systemPrompt \n\n $footerPrompt"
        }

        applySemanticDecompressionPrelude()

        return this
    }

    /**
     * Set the prompt to be injected into the middle of the system prompt. After initial introduction, and after
     * the json input. But before the json output.
     *
     * @param prompt The prompt to be injected into the middle of the system prompt.
     */
    fun setMiddlePrompt(prompt: String): Pipe
    {
        middlePromptInstructions = prompt
        return this
    }


    /**
     * Set the footer prompt which is injected at the end of the system prompt. This is useful for adding extra
     * instructions that need to be placed after context injection rather than prior to it.
     */
    fun setFooterPrompt(prompt: String) : Pipe
    {
        footerPrompt = prompt
        return this
    }




    /**
     * Sets the user prompt to be prefixed to any input to the AI model.
     * This prompt will be combined with a prompt injection value to form a complete prompt
     * when generating text. Not every model requires a user prompt and simply having TPipe inject the
     * previous output is sufficient.
     *
     * @param prompt The user prompt to be used by the AI model.
     * @return This Pipe object for method chaining.
     */
    fun setUserPrompt(prompt: String): Pipe {
        this.userPrompt = prompt
        return this
    }

    /**
     * Compresses a prompt string using TPipe's semantic compression rules without mutating the pipe state.
     *
     * This helper is intentionally opt-in so callers can compress raw system prompts or other reusable prompt
     * fragments before they are assembled into a pipe. It delegates to the same deterministic compressor used
     * by the user-prompt budget path.
     *
     * @param prompt The prompt text to compress.
     * @param settings Optional semantic compression settings for legend sizing and extra phrase/stop-word tables.
     *
     * @return The compressed prompt text and its legend.
     */
    fun compressPrompt(
        prompt: String,
        settings: SemanticCompressionSettings = SemanticCompressionSettings()
    ): SemanticCompressionResult
    {
        return semanticCompress(prompt, settings)
    }
    
    /**
     * Sets the multimodal input content for this pipe. This replaces string-only mechanisms
     * and allows for comprehensive multimodal processing including text and binary data.
     *
     * @param content The multimodal content to be used as input.
     * @return This Pipe object for method chaining.
     */
    fun setMultimodalInput(content: MultimodalContent): Pipe {
        this.multimodalInput = content
        return this
    }

    /**
     * Cache the input of this pipe upon kicking off [executeMultimodal]. The cached data can be then pulled
     * by calling [getCachedInput].
     */
    fun cacheInput() : Pipe
    {
        cacheInput = true
        return this
    }

    /**
     * Function to immediately cache and snapshot a content object right now. Can be invoked even at runtime
     * or prior to execution. Data can then be read from [getCachedInput] at any time after using this or
     * at runtime if [\cacheInput] was set.
     */
    fun forceCacheInput(content: MultimodalContent) : Pipe
    {
        pipeMetadata[USER_PROMPT_SNAPSHOT] = content.deepCopy()
        return this
    }

    /**
     * Read the cached input of this pipe if stored by automatic caching.
     */
    fun getCachedInput() : MultimodalContent
    {
        return try {
            pipeMetadata[USER_PROMPT_SNAPSHOT] as MultimodalContent
        }

        catch(e: Exception)
        {
            MultimodalContent()
        }

    }

    /**
     * Forces the pipe to save a snapshot to the content object of the pipe at startup. Useful for when you
     * aren't sure which object gets passed in or can't turn this on at the content object level.
     */
    fun forceSaveSnapshot() : Pipe
    {
        saveSnapshot = true
        return this
    }


    /**
     * Attempt to serialize a given object into a json string. This will generate all the default
     * values needed so that we can pass this example forward to the AI model with all default values known.
     * @param json The object to serialize into JSON format
     * @param senddefaults Whether to include default values in the serialization
     * @return This Pipe object for method chaining
     */
    inline fun <reified T> setJsonInput(json: T,senddefaults: Boolean = true): Pipe {

        this.jsonInput = examplePromptFor(T::class)
        return this
    }

    /**
     * Set json input by providing a KClass directly. Useful for primitives or classes with private constructors.
     * @param kclass The KClass to generate JSON schema from
     * @return This Pipe object for method chaining
     */
    fun setJsonInput(kclass: KClass<*>): Pipe
    {
        this.jsonInput = examplePromptFor(kclass)
        return this
    }

    /**
     * Set json input by providing the string directly. This is not recommended, but may be required for
     * languages or frameworks that don't support that are not written in Kotlin.
     * @param json The JSON string to set as input
     * @return This Pipe object for method chaining
     */
    fun setJsonInput(json: String): Pipe {
        this.jsonInput = json
        return this
    }



    /**
     * Set the instructions the llm sees on how to understand it's json input. This overrides the default
     * if non-empty.
     * @param instructions The instructions to set
     * @return This Pipe object for method chaining
     */
    fun setJsonInputInstructions(instructions: String) : Pipe
    {
        jsonInputInstructions = instructions
        return this
    }

    /**
     * Set the json output from the AI model. Some models and APIs allow this to be defined by the sdk or rest API.
     * If not supported TPipe will employ prompt injection to force json output from the AI model.
     * @param json Object class that can be serialized to json. If invalid no value will be set. The contents of all
     * serialized variables will be encoded including null values, and default values. This ensures the entire
     * json method is made available to the AI model.
     * @return This Pipe object for method chaining
     */
    inline fun <reified T> setJsonOutput(json: T): Pipe
    {
        this.jsonOutput = examplePromptFor(T::class)
        return this
    }

    /**
     * Set json output by providing a KClass directly. Useful for primitives or classes with private constructors.
     * @param kclass The KClass to generate JSON schema from
     * @return This Pipe object for method chaining
     */
    fun setJsonOutput(kclass: KClass<*>): Pipe
    {
        this.jsonOutput = examplePromptFor(kclass)
        return this
    }

    /**
     * Sets json output by providing the string directly. This is not recommended, but may be required for
     * languages or frameworks that don't support serialization, or that are not written in Kotlin.
     * @param json String to save as an output. It's strongly recommended to have all the values it can hold.
     * This includes null values, and default values.
     * @return This Pipe object for method chaining
     */
    fun setJsonOutput(json: String): Pipe
    {
        this.jsonOutput = json
        return this
    }



    /**
     * Set the output instructions for output json. This overrides the default injection instrucions if non-empty.
     */
    fun setJsonOutputInstructions(instructions: String) : Pipe
    {
        this.jsonOutputInstructions = instructions
        return this
    }

    /**
     * Set the page key to pull an active todo list from the Context bank. This will be invoked when [applySystemPrompt]
     * is called. During pipe runtime, if the content object has an assigned focus point value, extra instructions will
     * be provided to have the llm focus on that specific task.
     */
    fun setTodoListPageKey(key: String) : Pipe
    {
        todoPageKey = key
        return this
    }

    /**
     * Override default instructions for todo list injection into the system prompt.
     */
    fun setTodoListInstructions(instructions: String) : Pipe
    {
        todoListInstructions = instructions
        return this
    }

    /**
     * Activate system prompt json injection mode. TPipe will use prompt injection to attempt to force the AI model
     * to accept json input, and generate only json output. This is not 100% guaranteed to work, but is able to
     * work in many cases where the model or API does not support forced structured json input and output.
     * @param stripExternalText If true, all text before and after json will be removed automatically by the pipe
     * after the llm has returned it's output.
     * @return This Pipe object for method chaining
     */
    fun requireJsonPromptInjection(stripExternalText: Boolean = false): Pipe
    {
        this.supportsNativeJson = false
        stripNonJson = stripExternalText
        return this
    }


    /**
     * Set the temperature for generation. This is used to control the randomness of the generation.
     * Higher temperatures will result in more random output, while lower temperatures will result in more conservative output.
     * @param temp The temperature to set. Must be greater than 0.
     * @return This Pipe object for method chaining
     */
    fun setTemperature(temp: Double): Pipe
    {
        this.temperature = temp
        return this
    }

    /**
     * Sets the nucleus sampling top_p value. This is used to control the generation quality and output.
     * Nucleus sampling is a technique used to generate text where the top tokens from the model are selected
     * according to a given probability. The top_p value is used to determine the number of tokens to select
     * based on the probability of the tokens. A higher top_p will result in more tokens being selected, and
     * a lower top_p will result in fewer tokens being selected.
     * @param top The top_p value to set. Must be between 0 and 1.
     * @return This Pipe object for method chaining
     */
    fun setTopP(top: Double): Pipe
    {
        this.topP = top
        return this
    }


    /**
     * Sets the top_k value for the pipe. This is used to control the number of highest probability tokens
     * to keep during text generation. A larger top_k will allow more tokens to be considered, while a smaller
     * top_k will restrict the token selection to only the most probable ones.
     *
     * Not all models support controlling the top_k value. If the model does not support this, this will do nothing.
     *
     * @param top The top_k value to set. Must be greater than 0.
     * @return This pipe, allowing calls to be chained.
     */
    fun setTopK(top: Int) : Pipe
    {
       this.topK = top
       return this
    }


    /**
     * Set the maximum number of tokens to generate. This can be used to limit the amount of output generated.
     * @param max The maximum number of tokens to generate. Must be greater than 0.
     * @return This pipe, allowing calls to be chained.
     *
     * @since Some models may not support this. If this is case, this will do nothing.
     */
    fun setMaxTokens(max: Int): Pipe
    {
        this.maxTokens = max
        return this
    }

    /**
     * If enabled, max tokens hitting will not be treated as an error and the output text will be returned
     * and not discarded pending that there is output text. Reasoning content will not count as output and will
     * be discarded if we overflow.
     */
    fun enableMaxTokenOverflow() : Pipe
    {
        this.allowMaxTokenOverflow = true
        return this
    }


    /**
     * Sets the context window size for the pipe. This is used to control how much of the previous input is passed
     * to the model when generating text. A larger context window will result in more of the previous input
     * being passed to the model, while a smaller context window will result in less of the previous input being
     * passed to the model.
     *
     * @param window The context window to set. Must be greater than 0.
     * @return This pipe, allowing calls to be chained.
     */
    fun setContextWindowSize(window: Int): Pipe
    {
        this.contextWindowSize = window
        originalContextWindowSize = window
        return this
    }

    /**
     * Sets the context window settings for the pipe. This is used to control how the context window is
     * truncated when generating text. The context window is the amount of previous input that is passed to
     * the model when generating text. Different models and APIs may support different context window
     * truncation settings.
     *
     * @param windowSettings The context window settings to set. This must be a valid
     * [ContextWindowSettings].
     * @return This pipe, allowing calls to be chained.
     */
    fun setContextWindowSettings(windowSettings: ContextWindowSettings): Pipe
    {
        this.contextWindowTruncation = windowSettings
        return this
    }

    /**
     * When enabled, empty user prompts will no longer crash a pipeline. By default, we treat empty prompts, as an
     * error because in 99% of cases, having an empty user prompt, and empty input in the content object is likely to
     * confuse the llm and cause subtle but highly destructive bugs in a pipeline that can induce catastrophic damage
     * down the line. This is basically almost never desirable. So this function acts as a promise that the developer
     * is assuring that they have handled this, designed the pipe to accept this state, and that it will not invoke
     * destructive behavior if it happens.
     */
    fun allowEmptyUserPrompt() : Pipe
    {
        allowEmptyUserPrompt = true
        return this
    }

    /**
     * If true, the programmer is disabling the second level saftey system that shuts down pipelines when a content
     * object is completely empty. This means no user prompt, no text input prompt. No binary content, and no context
     * data of any kind is present in the [MultimodalContent] object. This is often just a footgun 99.99% of the time
     * so we also block this behavior by default. Enabling this is a contractural promise that this issue is safe,
     * has been handled, and won't wreck total destruction on whatever the pipeline is interacting with.
     */
    fun allowEmptyContentObject() : Pipe
    {
        allowEmptyContentObject = true
        return this
    }


    /**
     * Enables pulling context from the global context bank when this pipe executes.
     * @return This Pipe object for method chaining
     */
    fun pullGlobalContext() : Pipe
    {
        readFromGlobalContext = true
        return this
    }


    /**
     * Enables pulling the banked context in [ContextBank] regardless of if a page key was supplied or not. This is
     * useful when you need to pull the banked context, and one or more page keys.
     */
    fun pullBankedContext() : Pipe
    {
        pullFromBankedContext = true
        return this
    }

    /**
     * External setter for the token budget. Allows an advanced token budget to be assigned
     * that will account for the system prompt, user prompt, any binary content, reasoning budget, max token budget,
     * and any remaining context to ensure it all fits inside the context window.
     * If assigned, the internal version of this function will be called
     * at runtime during the critical context truncation stage.
     *
     * WARNING: DO NOT CALL THIS WHILE THE PIPE IS EXECUTING
     *
     * @param budget The token budget to set. This must be a valid [TokenBudgetSettings] object.
     *
     * @see setTokenBudgetInternal
     */
    fun setTokenBudget(budget: TokenBudgetSettings) : Pipe
    {
        truncateModuleContext() //Call to ensure our settings is bound.
        tokenBudgetSettings = cloneTokenBudgetSettings(budget)
        setContextWindowSize(budget.contextWindowSize ?: contextWindowSize)
        setTokenBudgetInternal(tokenBudgetSettings!!)
        return this
    }

    /**
     * Creates a detached copy of token-budget settings so execution-time mutations do not leak back into the
     * caller's original configuration object.
     *
     * @param budget The source settings to copy.
     * @return A detached copy safe for internal mutation.
     */
    private fun cloneTokenBudgetSettings(budget: TokenBudgetSettings): TokenBudgetSettings
    {
        return budget.copy(
            pageWeights = budget.pageWeights?.toMap()
        )
    }

    /**
     * Captures the mutable token-budget fields that execution may rewrite temporarily.
     *
     * @return Snapshot of the current execution-visible budget state.
     */
    private fun captureTokenBudgetExecutionSnapshot(): TokenBudgetExecutionSnapshot
    {
        return TokenBudgetExecutionSnapshot(
            maxTokens = maxTokens,
            contextWindowSize = contextWindowSize,
            tokenBudgetSettings = tokenBudgetSettings?.let { cloneTokenBudgetSettings(it) }
        )
    }

    /**
     * Restores the mutable token-budget fields after runtime truncation has completed.
     *
     * @param snapshot Snapshot captured before execution-time budgeting began.
     */
    private fun restoreTokenBudgetExecutionSnapshot(snapshot: TokenBudgetExecutionSnapshot)
    {
        maxTokens = snapshot.maxTokens
        contextWindowSize = snapshot.contextWindowSize
        tokenBudgetSettings = snapshot.tokenBudgetSettings?.let { cloneTokenBudgetSettings(it) }
    }

    /**
     * Sets multi-page budget allocation strategy for MiniBank truncation.
     * Only affects behavior when multiple page keys are used.
     *
     * @param strategy Budget allocation strategy
     * @return This Pipe object for method chaining
     */
    fun setMultiPageBudgetStrategy(strategy: MultiPageBudgetStrategy) : Pipe
    {
        if(tokenBudgetSettings == null)
        {
            tokenBudgetSettings = TokenBudgetSettings()
        }
        tokenBudgetSettings!!.multiPageBudgetStrategy = strategy
        return this
    }

    /**
     * Enable dynamic size-based budget allocation that prioritizes smaller contexts.
     * Smaller contexts are protected and get full allocation first.
     * Larger contexts are truncated as needed to fit budget.
     * Includes intelligent redistribution of unused budget.
     * 
     * @return This Pipe object for method chaining
     */
    fun enableDynamicSizeFill(): Pipe
    {
        if(tokenBudgetSettings == null)
        {
            tokenBudgetSettings = TokenBudgetSettings()
        }
        tokenBudgetSettings!!.multiPageBudgetStrategy = MultiPageBudgetStrategy.DYNAMIC_SIZE_FILL
        return this
    }

    /**
     * Sets page weights for weighted budget allocation.
     * Weights are normalized automatically.
     *
     * @param weights Map of page key to weight (higher = more budget)
     * @return This Pipe object for method chaining
     */
    fun setPageWeights(weights: Map<String, Double>) : Pipe
    {
        if(tokenBudgetSettings == null)
        {
            tokenBudgetSettings = TokenBudgetSettings()
        }
        tokenBudgetSettings!!.pageWeights = weights.toMap()
        return this
    }

    /**
     * Enables text-matching preservation for context elements and conversation history during truncation.
     * When enabled, items containing words from the user prompt are kept before the remaining content is truncated.
     */
    fun enableTextMatchingPreservation(): Pipe
    {
        if(tokenBudgetSettings == null)
        {
            tokenBudgetSettings = TokenBudgetSettings()
        }
        tokenBudgetSettings!!.preserveTextMatches = true
        return this
    }

    /**
     * Disables text-matching preservation so truncation reverts to standard ordering.
     */
    fun disableTextMatchingPreservation(): Pipe
    {
        tokenBudgetSettings?.preserveTextMatches = false
        return this
    }

    /**
     * Enables dynamic budget redistribution for optimal token utilization.
     */
    fun enableDynamicFill(): Pipe
    {
        if(tokenBudgetSettings == null)
        {
            tokenBudgetSettings = TokenBudgetSettings()
        }
        tokenBudgetSettings!!.multiPageBudgetStrategy = MultiPageBudgetStrategy.DYNAMIC_FILL
        return this
    }

    /**
     * Assign the max token budget immediately and auto handle getting the correct token size remaining
     * for the context window size settings. This setter is treated as internal because it's intended to
     * be invoked at the pipe's runtime.
     */
    private fun setTokenBudgetInternal(budget: TokenBudgetSettings, liveContent: MultimodalContent? = null) : Pipe
    {

        /**
         * Now fetch our truncation settings so we that we can proceed with dictionary counting.
         * Beware that this is leveraging known tokenizer configurations for providers and models.
         * Should a provider or model be used that is not known to TPipe the generic counter will be used
         * instead. This may result in counting being somewhat inaccurate.
         */
        val truncationSettings = getTruncationSettings()

        /**
         * Assign the token budget we have to fit everything into. If non-null we use the value here.
         * Otherwise, default to whatever has been set in this pipe as the maximum context window.
         */
        var workingTokenWindowSize : Int = budget.contextWindowSize ?: contextWindowSize

        //Override the set context window size if the budget did assign this value.
        if(budget.contextWindowSize != null) contextWindowSize = budget.contextWindowSize!!

        //First subtract the system prompt from our token budget.
        val systemPromptTokens = Dictionary.countTokens(systemPrompt, truncationSettings)
        workingTokenWindowSize -= systemPromptTokens

        if(workingTokenWindowSize <= 0) throw Exception("System prompt has overflowed the token budget.")

        //Subtract against max tokens next assuring that the system prompt, and output expectations won't overflow us.
        var maxTokensFromSettings = budget.maxTokens ?: maxTokens
        workingTokenWindowSize -= maxTokensFromSettings
        if(workingTokenWindowSize <= 0) throw Exception("Max tokens has overflowed the token budget.")

        /**
         * If not valid assume reasoning budget of zero. Should the user supply a reasoning sub-pipe to this pipe,
         * it's limit on how long it can reason will be defined by maximum round counts, stop sequences, or outright
         * overflowing the max tokens (which would be a pipe failure if such an overflow occurred).
         */
        val reasoningBudget = budget.reasoningBudget ?: 0

        if(!budget.subtractReasoningFromInput)
        {
            if(reasoningBudget > maxTokensFromSettings) throw IllegalArgumentException("Reasoning tokens cannot be greater " +
                    "than the overall max token budget for llm output.")

            /**
             * Subtract max token output to ensure we are keeping both model reasoning, and token output constrained
             * to the defined token budget.
             */
            maxTokensFromSettings -= reasoningBudget
        }
        else
        {
            if(reasoningBudget > workingTokenWindowSize) throw IllegalArgumentException("Reasoning tokens cannot be greater " +
                    "than the input token budget.")

            workingTokenWindowSize -= reasoningBudget
        }

        /**
         * Now after saving this back to the pipe we have our true max tokens which also ensure reasoning is accounted
         * for either being 0 for not being set, or being subtracted correctly from the max token value.
         * If subtractReasoningFromInput is true, maxTokens remains unchanged by reasoning.
         */
        maxTokens = maxTokensFromSettings

        /**
         * If a max user prompt size has been assigned, and we'll overflow we need to throw.
         * WARNING: Even if you don't supply a max size here, and opt to neither compress, nor truncate it,
         * if it overflows at runtime we will still need to throw.
         *
         * Also, beware that while truncation attempts and compression may occur, we can't truncate unknown json
         * so if you try to truncate it as a string, and it's a json object we'll still need to throw. The only
         * allowed exception to this is if it's stored as a ConverseHistory object.
         *
         * @see ConverseHistory
         *
         * If you decide to opt for compression instead, the user prompt will just be compressed not making any
         * safety checks for json. So that is very much user beware and up to the user to ensure safety on that
         * front.
         */
        if(budget.userPromptSize != null && budget.userPromptSize!! > workingTokenWindowSize)
        {
            throw IllegalArgumentException("User prompt size cannot be greater than the token budget. " +
                    "After subtracting the system prompt, and the maxTokens out, the context window size is ${workingTokenWindowSize}. " +
                    "Your user prompt budget has been sized to ${budget.userPromptSize}")
        }

        /**
         * Subtract against context window if the user prompt size limit was provided. Otherwise, the size limit
         * will be decided based on full context window size - whatever is leftover when we go to execute
         * the prompt after accounting for binary data at runtime.
         */
        workingTokenWindowSize -= budget.userPromptSize ?: 0

        contextWindowSize = workingTokenWindowSize

        return this
    }

    /**
     * Calculates per-page budget allocations based on the configured strategy.
     */
    internal fun calculatePageBudgets(
        totalBudget: Int,
        pageKeys: List<String>,
        strategy: MultiPageBudgetStrategy,
        weights: Map<String, Double>?,
        truncationSettings: TruncationSettings,
        reserveEmptyPageBudget: Boolean
    ): Map<String, Int>
    {
        if(totalBudget <= 0 || pageKeys.isEmpty())
        {
            return pageKeys.associateWith { 0 }
        }

        val effectiveKeys = if(reserveEmptyPageBudget) {
            pageKeys
        }
        else
        {
            pageKeys.filter { key ->
                val window = miniContextBank.contextMap[key]
                window != null && !window.isEmpty()
            }
        }

        if(effectiveKeys.isEmpty())
        {
            return pageKeys.associateWith { 0 }
        }

        val allocations = when(strategy)
        {
            MultiPageBudgetStrategy.EQUAL_SPLIT -> calculateEqualSplitMap(totalBudget, effectiveKeys)
            MultiPageBudgetStrategy.WEIGHTED_SPLIT ->
            {
                if(weights == null) calculateEqualSplitMap(totalBudget, effectiveKeys)
                else calculateWeightedSplit(totalBudget, effectiveKeys, weights)
            }
            MultiPageBudgetStrategy.PRIORITY_FILL -> calculatePriorityFill(totalBudget, effectiveKeys, truncationSettings)
            MultiPageBudgetStrategy.DYNAMIC_FILL -> calculateDynamicFill(totalBudget, effectiveKeys, truncationSettings)
            MultiPageBudgetStrategy.DYNAMIC_SIZE_FILL -> calculateDynamicSizeFill(totalBudget, effectiveKeys, truncationSettings)
        }

        return pageKeys.associateWith { allocations[it] ?: 0 }
    }

    private fun calculateEqualSplitMap(totalBudget: Int, pageKeys: List<String>): Map<String, Int>
    {
        if(totalBudget <= 0 || pageKeys.isEmpty())
        {
            return pageKeys.associateWith { 0 }
        }

        val allocations = mutableMapOf<String, Int>()
        val baseAllocation = totalBudget / pageKeys.size
        var remainder = totalBudget - (baseAllocation * pageKeys.size)

        for(pageKey in pageKeys)
        {
            var share = baseAllocation
            if(remainder > 0)
            {
                share += 1
                remainder--
            }

            allocations[pageKey] = share
        }

        return allocations
    }

    private fun calculateWeightedSplit(
        totalBudget: Int,
        pageKeys: List<String>,
        weights: Map<String, Double>
    ): Map<String, Int>
    {
        if(totalBudget <= 0 || pageKeys.isEmpty())
        {
            return pageKeys.associateWith { 0 }
        }

        val totalWeight = pageKeys.sumOf { weights[it] ?: 0.0 }
        if(totalWeight <= 0.0)
        {
            return calculateEqualSplitMap(totalBudget, pageKeys)
        }

        val allocations = mutableMapOf<String, Int>()
        var distributed = 0

        for(pageKey in pageKeys)
        {
            val weight = weights[pageKey] ?: 0.0
            val proportion = weight / totalWeight
            val share = (totalBudget * proportion).toInt()
            allocations[pageKey] = share
            distributed += share
        }

        var remaining = totalBudget - distributed
        for(pageKey in pageKeys)
        {
            if(remaining <= 0) break
            allocations[pageKey] = (allocations[pageKey] ?: 0) + 1
            remaining--
        }

        return allocations
    }

    private fun calculatePriorityFill(
        totalBudget: Int,
        pageKeys: List<String>,
        truncationSettings: TruncationSettings
    ): Map<String, Int>
    {
        if(totalBudget <= 0 || pageKeys.isEmpty())
        {
            return pageKeys.associateWith { 0 }
        }

        val allocations = mutableMapOf<String, Int>()
        var remainingBudget = totalBudget

        for(pageKey in pageKeys)
        {
            if(remainingBudget <= 0) break

            val contextWindow = miniContextBank.contextMap[pageKey]
            if(contextWindow == null || contextWindow.isEmpty())
            {
                allocations[pageKey] = 0
                continue
            }

            val requiredTokens = countContextWindowTokens(contextWindow, truncationSettings)
            if(requiredTokens <= 0)
            {
                allocations[pageKey] = 0
                continue
            }

            val allocation = minOf(requiredTokens, remainingBudget)
            allocations[pageKey] = allocation
            remainingBudget -= allocation
        }

        return allocations
    }

    /**
     * Calculates dynamic budget allocation with redistribution for optimal token utilization.
     * Uses multi-pass approach: initial allocation → usage simulation → redistribution.
     */
    private fun calculateDynamicFill(
        totalBudget: Int,
        pageKeys: List<String>,
        truncationSettings: TruncationSettings
    ): Map<String, Int>
    {
        if(totalBudget <= 0 || pageKeys.isEmpty())
        {
            return pageKeys.associateWith { 0 }
        }

        val initialAllocations = calculatePriorityFillInternal(totalBudget, pageKeys, truncationSettings)
        val simulatedUsage = simulateTruncationUsage(initialAllocations, truncationSettings)

        return redistributeBudgetDynamically(initialAllocations, simulatedUsage, totalBudget, truncationSettings)
    }

    /**
     * Calculates size-based dynamic budget allocation with redistribution.
     * Prioritizes smaller contexts for protection, truncates larger contexts first.
     * Uses multi-pass approach: size-based allocation → usage simulation → redistribution.
     */
    internal fun calculateDynamicSizeFill(
        totalBudget: Int,
        pageKeys: List<String>,
        truncationSettings: TruncationSettings
    ): Map<String, Int>
    {
        if(totalBudget <= 0 || pageKeys.isEmpty())
        {
            return pageKeys.associateWith { 0 }
        }

        val initialAllocations = calculateSizeBasedPriorityFill(totalBudget, pageKeys, truncationSettings)
        val simulatedUsage = simulateTruncationUsage(initialAllocations, truncationSettings)

        return redistributeBudgetDynamically(initialAllocations, simulatedUsage, totalBudget, truncationSettings)
    }

    /**
     * Allocates budget by size priority - smallest contexts get full allocation first.
     * Larger contexts get remaining budget and may be truncated.
     */
    internal fun calculateSizeBasedPriorityFill(
        totalBudget: Int,
        pageKeys: List<String>,
        truncationSettings: TruncationSettings
    ): Map<String, Int>
    {
        if(totalBudget <= 0 || pageKeys.isEmpty())
        {
            return pageKeys.associateWith { 0 }
        }

        // Calculate context sizes and sort by size (smallest first for protection)
        val contextSizes = mutableMapOf<String, Int>()
        for(pageKey in pageKeys)
        {
            val contextWindow = miniContextBank.contextMap[pageKey]
            val size = if(contextWindow == null || contextWindow.isEmpty()) 0
                      else countContextWindowTokens(contextWindow, truncationSettings)
            contextSizes[pageKey] = size
        }

        val sortedKeys = pageKeys.sortedBy { contextSizes[it] ?: 0 }
        
        // Allocate budget in size order (smallest first gets protected, largest gets truncated)
        val allocations = mutableMapOf<String, Int>()
        var remainingBudget = totalBudget

        for(pageKey in sortedKeys)
        {
            if(remainingBudget <= 0) 
            {
                allocations[pageKey] = 0
                continue
            }

            val requiredTokens = contextSizes[pageKey] ?: 0
            if(requiredTokens <= 0)
            {
                allocations[pageKey] = 0
                continue
            }

            val allocation = minOf(requiredTokens, remainingBudget)
            allocations[pageKey] = allocation
            remainingBudget -= allocation
        }

        return allocations
    }

    /**
     * Simulates truncation to predict actual token usage per page.
     */
    private fun simulateTruncationUsage(
        allocations: Map<String, Int>,
        truncationSettings: TruncationSettings
    ): Map<String, Int>
    {
        val usage = mutableMapOf<String, Int>()

        for((pageKey, allocatedBudget) in allocations)
        {
            if(allocatedBudget <= 0)
            {
                usage[pageKey] = 0
                continue
            }

            val contextWindow = miniContextBank.contextMap[pageKey]
            if(contextWindow == null || contextWindow.isEmpty())
            {
                usage[pageKey] = 0
                continue
            }

            val tempWindow = contextWindow.deepCopy()
            tempWindow.selectAndTruncateContext(
                "",
                allocatedBudget,
                ContextWindowSettings.TruncateTop,
                truncationSettings
            )

            usage[pageKey] = countContextWindowTokens(tempWindow, truncationSettings)
        }

        return usage
    }

    /**
     * Redistributes unused budget dynamically to maximize token utilization.
     */
    private fun redistributeBudgetDynamically(
        initialAllocations: Map<String, Int>,
        simulatedUsage: Map<String, Int>,
        totalBudget: Int,
        truncationSettings: TruncationSettings
    ): Map<String, Int>
    {
        val optimizedAllocations = simulatedUsage.toMutableMap()
        for(pageKey in initialAllocations.keys)
        {
            optimizedAllocations.putIfAbsent(pageKey, 0)
        }

        val totalUsed = simulatedUsage.values.sum()
        var unusedBudget = totalBudget - totalUsed

        if(unusedBudget <= 0) return optimizedAllocations

        repeat(3)
        {
            if(unusedBudget <= 0) return@repeat

            val candidates = findRedistributionCandidates(optimizedAllocations, truncationSettings)
            if(candidates.isEmpty()) return@repeat

            val redistributed = redistributeToPages(candidates, unusedBudget)
            if(redistributed.isEmpty()) return@repeat

            for((pageKey, additionalBudget) in redistributed)
            {
                optimizedAllocations[pageKey] = (optimizedAllocations[pageKey] ?: 0) + additionalBudget
                unusedBudget -= additionalBudget
            }
        }

        return optimizedAllocations
    }

    /**
     * Finds pages that could benefit from additional budget allocation.
     */
    private fun findRedistributionCandidates(
        currentAllocations: Map<String, Int>,
        truncationSettings: TruncationSettings
    ): List<Pair<String, Int>>
    {
        val candidates = mutableListOf<Pair<String, Int>>()

        for((pageKey, currentBudget) in currentAllocations)
        {
            val contextWindow = miniContextBank.contextMap[pageKey] ?: continue
            if(contextWindow.isEmpty()) continue

            val fullContentTokens = countContextWindowTokens(contextWindow, truncationSettings)
            val additionalNeed = maxOf(0, fullContentTokens - currentBudget)

            if(additionalNeed > 0)
            {
                candidates.add(pageKey to additionalNeed)
            }
        }

        return candidates.sortedByDescending { it.second }
    }

    /**
     * Redistributes unused budget to candidate pages proportionally.
     */
    private fun redistributeToPages(
        candidates: List<Pair<String, Int>>,
        unusedBudget: Int
    ): Map<String, Int>
    {
        if(candidates.isEmpty() || unusedBudget <= 0) return emptyMap()

        val totalNeed = candidates.sumOf { it.second }
        if(totalNeed <= 0) return emptyMap()

        val redistributed = mutableMapOf<String, Int>()
        var totalAllocated = 0

        for((pageKey, need) in candidates)
        {
            if(totalAllocated >= unusedBudget) break

            val allocation = minOf(
                ((unusedBudget.toDouble() * need) / totalNeed).toInt(),
                need,
                unusedBudget - totalAllocated
            )

            if(allocation > 0)
            {
                redistributed[pageKey] = allocation
                totalAllocated += allocation
            }
        }

        var remaining = unusedBudget - totalAllocated
        var candidateIndex = 0
        while(remaining > 0 && candidateIndex < candidates.size)
        {
            val (pageKey, need) = candidates[candidateIndex]
            val currentAllocation = redistributed[pageKey] ?: 0
            if(currentAllocation < need)
            {
                redistributed[pageKey] = currentAllocation + 1
                remaining--
                totalAllocated++
                continue
            }

            candidateIndex++
        }

        return redistributed
    }

    /**
     * Internal priority fill calculation for reuse in dynamic fill.
     */
    private fun calculatePriorityFillInternal(
        totalBudget: Int,
        pageKeys: List<String>,
        truncationSettings: TruncationSettings
    ): Map<String, Int>
    {
        return calculatePriorityFill(totalBudget, pageKeys, truncationSettings)
    }

    /**
     * Enables pulling context from the parent pipeline when this pipe executes.
     * This overrides pulling from the global context.
     * @return This Pipe object for method chaining
     */
    fun pullPipelineContext() : Pipe
    {
        readFromPipelineContext = true
        return this
    }

    /**
     * Enables pulling context from a parent pipe if it's not null internally. This is useful for any child pipes
     * that are part of a pipe.
     *
     * NOTE: This will do nothing for reasoning pipes since they have the fully prepared prompt with context
     * dispatched by the parent pipe automatically.
     */
    fun pullParentPipeContext() : Pipe
    {
        readFromParentPipeContext = true
        return this
    }

    /**
     * Enables updating the pipeline's context with this pipe's context when execution completes.
     * @return This Pipe object for method chaining
     */
    fun updatePipelineContextOnExit() : Pipe
    {
        updatePipelineContextOnExit = true
        return this
    }

    /**
     * Enable automatic converse history wrapping when the prior pipe outputs converse history wrapping. When the pipe
     * detects it's input is converse history it will store it will continue to build off that output and embed it's
     * internal output into the converse history data that was inputted into it. This is agnostic of what kind of json
     * the pipe produces, and allows the coder to automatically handle this wrapping without transformation functions,
     * fiddling, or needing to intercept and wrap the output through code. It also allows the desired json output to be
     * retained correctly without needing to worry about it.
     *
     * Should a pipe prior to this not return an input as converse, this automatic chaining will be broken silently.
     * So it's very important to keep this setting toggled on for each pipe in the pipeline, and supply the first pipe
     * in the pipeline with a converse history input.
     */
    fun wrapContentWithConverse(role: ConverseRole = ConverseRole.agent) : Pipe
    {
        wrapContentWithConverseHistory = true
        converseRole = role
        return this
    }

    /**
     * Enable automatic context injection to the user prompt when the pipe executes. The system prompt will also
     * have instructions on how to handle context injected, followed by all the variables in json that the
     * ContextWindow{} class has.
     *
     * @param instruction Instructions you wish to issue to the model to explain what the context is, and what
     * you expect the model to do with it. This will be injected into bottom of the system prompt at the time this
     * is called.
     *
     * @see contextWindow
     */
    fun autoInjectContext(instruction: String) : Pipe
    {
        autoInjectContext = true
        contextInstructions = instruction
        systemPrompt = "$systemPrompt \n\n $instruction \n\n ${selectGlobalContextMode()}"
        return this
    }

    /**
     * Function to allow us to append more instructions to a built pipe that has enabled [autoInjectContext]
     * This is useful for any extra .apply { modifications, or other scope function modifications when
     * building pipes using builder functions and patterns.
     */
    fun appendContextInstructions(instruction: String) : Pipe
    {
        contextInstructions += instruction
        autoInjectContext(contextInstructions)
        return this
    }

    /**
     * Enable automatic context and lorebook selection, and truncation when this pipe executes.
     * @param fillMode If true, enables select-and-fill lorebook selection during context truncation. When active,
     * split budgets are applied after priority lorebook selection has filled with top-weighted entries.
     * @param fillAndSplitMode If true, enables fill mode and reserves a split budget for the non-lorebook context.
     * @return This Pipe object for method chaining
     */
    fun autoTruncateContext(fillMode: Boolean = false, fillAndSplitMode: Boolean = false) : Pipe
    {
        autoTruncateContext = true

        if(fillAndSplitMode)
        {
            enableLoreBookFillAndSplitMode()
        }

        else if(fillMode)
        {
            enableLoreBookFillMode()
        }

        return this
    }

    /**
     * Make lorebook keys immutable. Once they are added, the value of their context, linked keys, and alias keys
     * can no longer be automatically updated by any validation function transformation function, branch function,
     * or any other pipe that is executed as part of this pipe's execution.
     */
    fun enableImmutableLoreBook() : Pipe
    {
        emplaceLorebook = false
        appendLoreBook = false
        return this
    }

    /**
     * Switch standard lorebook emplacement scheme to append mode. In append mode, new context can be added to the
     * value of existing keys. But the old context of that key cannot be removed. This may result in contradictory
     * information being stored to a key. This feature is generally preferable in agents like writing assistants,
     * or other creative tasks where random, or complex events and unpredicable actions can occur.
     */
    fun enableAppendLoreBookScheme() : Pipe
    {
        appendLoreBook = true
        emplaceLorebook = false
        return this
    }

    /**
     * Get the settings for the lorebook scheme.
     * @return [appendLoreBook] is first, [emplaceLorebook] is second
     */
    fun getLorebookScheme() : Pair<Boolean, Boolean>
    {
        return Pair(appendLoreBook, emplaceLorebook)
    }

    /**
     * Enables select-and-fill lorebook selection during context truncation.
     * When active, split budgets are applied after priority lorebook selection has filled with top-weighted entries.
     */
    fun enableLoreBookFillMode(): Pipe
    {
        loreBookFillMode = true
        return this
    }

    /**
     * Enables select-and-fill lorebook selection and reserves a split budget for the rest of the top-level context
     * window during truncation.
     */
    fun enableLoreBookFillAndSplitMode(): Pipe
    {
        loreBookFillMode = true
        loreBookFillAndSplitMode = true
        return this
    }

    /**
     * Enables full rewriting and construction of the converse history upon merging into the context window
     * or mini bank of this pipe. Is mutually exclusive with [emplaceConverseHistoryOnlyIfNull]
     */
    fun emplaceConverseHistory() : Pipe
    {
        emplaceConverseHistoryOnlyIfNull = false
        emplaceConverseHistory = true
        return this
    }

    /**
     * Allows for converse history emplacement upon merging context, but only if the target window or mini bank's key
     * has a null converse history reference. Is mutually exclusive with [emplaceConverseHistoryOnlyIfNull]
     */
    fun emplaceConverseHistoryOnlyIfNull() : Pipe
    {
        emplaceConverseHistoryOnlyIfNull = true
        emplaceConverseHistory = true
        return this
    }

    /**
     * Sets the page key for context bank operations. Can accept multiple page keys by using the delmiter ", " to
     * pass in many at once.
     * @param key The page key to use for context separation
     * @return This Pipe object for method chaining
     */
    fun setPageKey(key: String) : Pipe
    {
        /**
         * Split off the page key if we find multiple. This means we need to pull global context from many
         * pages on the ContextBank.
         * @see ContextBank
         */
        if(key.contains(","))
        {
            pageKeyList = key.split(", ").toMutableList()

            /**We need to clear this to prevent any unexpected behaviors if this function get called twice and
             * the second time multiple page keys have been provided.
             */
            pageKey = ""

            return this
        }



        //Default to single key mode if "'" isn't present in the string as a delimiter.
        pageKey = key
        pageKeyList.clear() //Clear to avoid choas when getting our json schemas at injection time.
        return this
    }


    /**
     * Sets the multiplier for window size calculations in dictionary truncation.
     * @param value The multiplier value to apply to window size
     * @return This Pipe object for method chaining
     */
    fun setMultiplyWindowSizeBy(value: Int): Pipe
    {
        this.multiplyWindowSizeBy = value
        return this
    }

    /**
     * Sets whether to count sub-words within the first word during dictionary truncation.
     * @param value True to count sub-words in the first word, false otherwise
     * @return This Pipe object for method chaining
     */
    fun setCountSubWordsInFirstWord(value: Boolean): Pipe
    {
        this.countSubWordsInFirstWord = value
        return this
    }

    /**
     * Sets whether to favor whole words during dictionary truncation.
     * @param value True to favor whole words, false otherwise
     * @return This Pipe object for method chaining
     */
    fun setFavorWholeWords(value: Boolean): Pipe
    {
        this.favorWholeWords = value
        return this
    }

    /**
     * Sets whether to count only the first word found during dictionary truncation.
     * @param value True to count only the first word found, false otherwise
     * @return This Pipe object for method chaining
     */
    fun setCountOnlyFirstWordFound(value: Boolean): Pipe
    {
        this.countOnlyFirstWordFound = value
        return this
    }

    /**
     * Sets whether to split on non-word characters during dictionary truncation.
     * @param value True to split on non-word characters, false otherwise
     * @return This Pipe object for method chaining
     */
    fun setSplitForNonWordChar(value: Boolean): Pipe
    {
        this.splitForNonWordChar = value
        return this
    }

    /**
     * Sets whether to always split if a whole word exists during dictionary truncation.
     * @param value True to always split when whole words exist, false otherwise
     * @return This Pipe object for method chaining
     */
    fun setAlwaysSplitIfWholeWordExists(value: Boolean): Pipe
    {
        this.alwaysSplitIfWholeWordExists = value
        return this
    }

    /**
     * Sets whether to count sub-words when splitting during dictionary truncation.
     * @param value True to count sub-words when splitting, false otherwise
     * @return This Pipe object for method chaining
     */
    fun setCountSubWordsIfSplit(value: Boolean): Pipe
    {
        this.countSubWordsIfSplit = value
        return this
    }

    /**
     * Sets the count for non-word splits during dictionary truncation.
     * @param value The number of non-word splits to allow
     * @return This Pipe object for method chaining
     */
    fun setNonWordSplitCount(value: Int): Pipe
    {
        this.nonWordSplitCount = value
        return this
    }

    /**
     * Enables truncating context as a single string rather than individual entries.
     * @return This Pipe object for method chaining
     */
    fun truncateContextAsString() : Pipe
    {
        truncateContextAsString = true
        return this
    }



    /**
     * Sets the repetition penalty for the pipe. This is a value between 0 and 1 that is used to control how much the AI model
     * should be penalized for generating text that is the same as the input. A higher penalty will cause the model to more
     * strongly avoid generating the same text, while a lower penalty will allow it to generate the same text more often.
     *
     * @param penalty The repetition penalty to set. Must be between 0 and 1.
     * @return This pipe, allowing calls to be chained.
     */
    fun setRepetitionPenalty(penalty: Double): Pipe
    {
        this.repetitionPenalty = penalty
        return this
    }

    /**
     * Sets the presence penalty for encouraging topic diversity.
     * Higher values make the model less likely to talk about existing topics.
     * 
     * @param penalty Penalty value between -2.0 and 2.0
     * @return This Pipe object for method chaining
     * @throws IllegalArgumentException if penalty is outside valid range
     */
    fun setPresencePenalty(penalty: Double): Pipe
    {
        if(penalty < -2.0 || penalty > 2.0)
        {
            throw IllegalArgumentException("Presence penalty must be between -2.0 and 2.0, got: $penalty")
        }
        this.presencePenalty = penalty
        return this
    }

    /**
     * Sets the seed for deterministic generation. When set, the model will
     * attempt to generate the same output for the same input.
     * 
     * @param seed Integer seed value, or null to disable deterministic generation
     * @return This Pipe object for method chaining
     */
    fun setSeed(seed: Int?): Pipe
    {
        this.seed = seed
        return this
    }

    /**
     * Sets logit bias to modify the likelihood of specific tokens appearing.
     * Maps token IDs to bias values between -100 and 100.
     * 
     * @param bias Map of token IDs to bias values
     * @return This Pipe object for method chaining
     * @throws IllegalArgumentException if any bias value is outside valid range
     */
    fun setLogitBias(bias: Map<Int, Double>): Pipe
    {
        bias.values.forEach { value ->
            if(value < -100.0 || value > 100.0)
            {
                throw IllegalArgumentException("Logit bias values must be between -100.0 and 100.0, got: $value")
            }
        }
        this.logitBias = bias
        return this
    }

    /**
     * Sets the number of completions to generate for each input.
     * Most models only support n=1. Higher values increase cost proportionally.
     * 
     * @param completions Number of completions to generate (minimum 1)
     * @return This Pipe object for method chaining
     * @throws IllegalArgumentException if completions is less than 1
     */
    fun setN(completions: Int): Pipe
    {
        if(completions < 1)
        {
            throw IllegalArgumentException("Number of completions must be at least 1, got: $completions")
        }
        this.n = completions
        return this
    }

    /**
     * Sets the user identifier for tracking and abuse monitoring.
     * This helps OpenAI monitor for abuse and provide better support.
     * 
     * @param userId User identifier string
     * @return This Pipe object for method chaining
     */
    fun setUser(userId: String): Pipe
    {
        this.user = userId
        return this
    }

    /**
     * Convenience method to reduce repetition using both frequency and presence penalties.
     * Sets repetitionPenalty (mapped to frequency_penalty) and presencePenalty to the same value.
     * 
     * @param penalty Penalty value between 0.0 and 2.0 (higher = less repetition)
     * @return This Pipe object for method chaining
     */
    fun setRepetitionControl(penalty: Double): Pipe
    {
        setRepetitionPenalty(penalty)
        setPresencePenalty(penalty)
        return this
    }

    /**
     * Convenience method to enable deterministic generation with a random seed.
     * Uses current timestamp as seed if no seed is provided.
     * 
     * @param seed Optional seed value (uses timestamp if null)
     * @return This Pipe object for method chaining
     */
    fun enableDeterministicGeneration(seed: Int? = null): Pipe
    {
        val actualSeed = seed ?: System.currentTimeMillis().toInt()
        setSeed(actualSeed)
        return this
    }

    /**
     * Convenience method to disable deterministic generation.
     * 
     * @return This Pipe object for method chaining
     */
    fun disableDeterministicGeneration(): Pipe
    {
        setSeed(null)
        return this
    }

    /**
     * Convenience method to ban specific words/phrases from generation.
     * Uses helper function to convert text to approximate token IDs.
     * 
     * @param bannedWords List of words/phrases to ban
     * @return This Pipe object for method chaining
     */
    fun banWords(bannedWords: List<String>): Pipe
    {
        val banMap = createTokenBanList(bannedWords)
        setLogitBias(logitBias + banMap)
        return this
    }

    /**
     * Convenience method to encourage specific words/phrases in generation.
     * Uses helper function to convert text to approximate token IDs.
     * 
     * @param encouragedWords List of words/phrases to encourage
     * @param bias Positive bias value (default 1.0)
     * @return This Pipe object for method chaining
     */
    fun encourageWords(encouragedWords: List<String>, bias: Double = 1.0): Pipe
    {
        val encourageMap = createTokenEncourageList(encouragedWords, bias)
        setLogitBias(logitBias + encourageMap)
        return this
    }

    /**
     * Convenience method to clear all logit bias settings.
     * 
     * @return This Pipe object for method chaining
     */
    fun clearLogitBias(): Pipe
    {
        setLogitBias(emptyMap())
        return this
    }

    /**
     * Sets the stop sequences for the pipe. The stop sequences are a list of strings that, if generated by the AI model,
     * will cause the generation to stop. This can be used to prevent the AI model from generating certain text that is not
     * desired.
     *
     * @param seqs The list of strings to use as the stop sequences. A generation will be stopped if any of these strings are
     *             generated.
     * @return This pipe, allowing calls to be chained.
     */
    fun setStopSequences(seqs: List<String>): Pipe {
        this.stopSequences = seqs
        return this
    }

    /**
     * Enable model reasoning/thinking mode if available. Not every model supports reasoning, disabling or enabling
     * reasoning, or outright defaults only to thinking modes.
     * @return This Pipe object for method chaining
     */
    fun setReasoning(): Pipe
    {
        useModelReasoning = true
        return this
    }

    /**
     * Overload to activate token allocation based reasoning used by some models.
     */
    fun setReasoning(tokens: Int): Pipe
    {
        modelReasoningSettingsV2 = tokens
        useModelReasoning = true
        return this
    }

    /**
     * Overload to activate custom reasoning used by some models. Some models require magic strings or other
     * api and vendor specific settings to be supplied.
     */
    fun setReasoning(custom: String): Pipe
    {
        useModelReasoning = true
        modelReasoningSettingsV3 = custom
        return this
    }

    /**
     * Function to disable model reasoning. Present to support cases where we need to unset this upon
     * swapping a model in a pipe suddenly. In families of llm models it's common for the one major api
     * difference between them to be having a thinking mode. So typically common settings like TopK, and other
     * settings that are numeric are always shared among that family. However, if reasoning is passed to models
     * of a family that do not have it, they will often just crash. So this function exists to help remove some
     * of the pain points of needing to do a model swap due to issues like llm refusals.
     */
    fun disableReasoning() : Pipe
    {
        useModelReasoning = false
        modelReasoningSettingsV3 = ""
        return this
    }

    /**
     * Set the token counting bias for this pipe.
     */
    fun setTokenCountingBias(value: Double): Pipe {
        this.tokenCountingBias = value
        return this
    }

    /**
     * Activate pipe timeout system. This allows for a pipe to be stopped in the event the llm
     * hangs, the provider hangs, or another system failure occurs that prevents the framework
     * from detecting a dropped connection, or if the connection gets stuck and never drops.
     *
     * @param applyRecursively If true, all child pipes attached to this pipe, and all pipes attached
     * to those child pipes will inherit the timeout.
     * @param duration Timeout duration to wait. Defaults to 5 mins before shutting a pipe down.
     * @param autoRetry If true, the content object will be snapshot upon pipe start, and restored
     * when we hit the timeout, the pipeline will then capture this failure state, and issue an automatic
     * jump back into this pipe to try again. This will repeat until the llm actually
     * completes the task.
     * @param retryLimit: Defines the maximum number of attempts to allow a retry on a pipe before
     * failing it and giving up.
     * @param customLogic Allows for a bindable custom function to be set to enable the developer
     * to attempt to handle the case as they see fit. Returns a boolean that can trip an automatic
     * retry if they want to try again, otherwise fails the pipe.
     *
     */
    fun enablePipeTimeout(
        applyRecursively: Boolean = true,
        duration: Long = 300000,
        autoRetry: Boolean = false,
        retryLimit: Int = 5,
        customLogic: (suspend(pipe: Pipe, content: MultimodalContent) -> Boolean)? = null) : Pipe
    {
        pipeTimeout = duration //Bind duration. Defaults to 5 mins.
        maxRetryAttempts = retryLimit
        this.enablePipeTimeout = true

        //Activate auto retry mode. This is exclusive with any custom logic systems.
        if(autoRetry)
        {
            timeoutStrategy = PipeTimeoutStrategy.Retry
            return this
        }

        //Allow the developer to drive custom logic to handle retries and repairs. Exclusive with auto retry.
        else if(customLogic != null)
        {
            pipeRetryFunction = customLogic
            return this
        }

        //Default if retry is not on, or a custom handler is not provided.
        timeoutStrategy = PipeTimeoutStrategy.Fail

        return this
    }

    /**
     * Sets the validator function for the pipe. This function will be used to validate the multimodal output
     * of the AI model. If the function returns true, the pipeline will continue to the next pipe.
     * If the function returns false, the pipeline will exit here.
     *
     * @param func A higher order function that takes MultimodalContent as input and returns a Boolean indicating
     *             whether the output is valid or not.
     *
     * @return This Pipe object for method chaining.
     */
    infix fun setValidatorFunction(func: suspend (content: MultimodalContent) -> Boolean): Pipe
    {
        this.validatorFunction = func
        return this
    }

    /**
     * Set the bound exception function which is called when an exception is thrown during [generateContent]
     * This is helpful for debugging difficult pipes in which the error state is unclear in the trace file.
     *
     * @param func: A higher order function that takes MultimodalContent and Throwable as input and returns Unit.
     * @param exception The exception that was thrown during [generateContent]
     *
     * @return This Pipe object for method chaining.
     */
    fun setExceptionFunction(func: suspend (content: MultimodalContent, exception: Throwable) -> Unit) : Pipe
    {
        exceptionFunction = func
        return this
    }


    
    /**
     * Legacy validator function for backward compatibility with string-based validation.
     */
    fun setStringValidatorFunction(func: (json: String) -> Boolean): Pipe
    {
        this.validatorFunction = { content -> func(content.text) }
        return this
    }



    /**
     * Sets the transformation function for the pipe. This function will be used to transform
     * the multimodal output from the AI model. The transformation function allows for modifications
     * or adjustments to the content before it is passed to the next pipe in the pipeline.
     *
     * @param func A higher-order function that takes MultimodalContent as input and returns transformed
     *             MultimodalContent.
     *
     * @return This Pipe object for method chaining.
     */
    fun setTransformationFunction(func: suspend (content: MultimodalContent) -> MultimodalContent): Pipe
    {
        this.transformationFunction = func
        return this
    }

    /**
     * setter to assign the pre init function that executes at the very start of the pipe.
     * Pre-init allows for actions like taking a snapshot of the input content, making last minute changes
     * to the content before it gets merged into the user prompt, and the context data.
     */
    fun setPreInitFunction(func: (suspend (content: MultimodalContent) ->  Unit)) : Pipe
    {
        preInitFunction = func
        return this
    }



    /**
     * Set the pre-validation function that will be called after the context window is fully pulled into this pipe
     * and settled. This happens right before the user prompt gets merged and the llm gets called.
     * @see preValidationFunction
     */
    fun setPreValidationFunction(func: suspend (context: ContextWindow, content: MultimodalContent?) -> ContextWindow) : Pipe
    {
        preValidationFunction = func
        return this
    }



    /**
     * Set the mini bank version of the pre-validation function that will be called after the context window and
     * mini bank have been both fully pulled into this pipe and settled. This happens right before the user prompt
     * gets merged and the llm gets called.
     * @see preValidationMiniBankFunction
     */
    fun setPreValidationMiniBankFunction(func: suspend (context: MiniBank, content: MultimodalContent?) -> MiniBank) : Pipe
    {
        preValidationMiniBankFunction = func
        return this
    }




    /**
     * Sets the pre-invoke function that executes just prior to making the api call to the llm. This function can
     * be used to exit early and treat this pipe as optional dynamically at runtime in cases where we do not know
     * that this pipe needs to be skipped until after the transformation function and final output of the prior
     * pipe in the pipeline has occurred. If this function exits this pipe early, it will not consider a failure
     * and the multiModalContent object will be passed forward to the next pipe.
     *
     * @see preInvokeFunction
     */
    fun setPreInvokeFunction(func: suspend (content: MultimodalContent) -> Boolean) : Pipe
    {
        preInvokeFunction = func
        return this
    }

    /**
     * Sets the post generation function that is called immediately after the llm has generated an output.
     * @see [postGenerateFunction]
     */
    fun setPostGenerateFunction(func: suspend (content: MultimodalContent) -> Unit) : Pipe
    {
        postGenerateFunction = func
        return this
    }
    
    /**
     * Legacy transformation function for backward compatibility with string-based transformation.
     */
    fun setStringTransformationFunction(func: (json: String) -> String): Pipe
    {
        this.transformationFunction = { content -> 
            val result = MultimodalContent(func(content.text), content.binaryContent.toMutableList(), content.terminatePipeline)
            result
        }
        return this
    }

    /**
     * Sets the failure function for the pipe. This function will be used to handle multimodal output
     * from the AI model if validation fails. The failure function can modify the content or mark it
     * for pipeline termination.
     *
     * @param func The failure function that takes original and processed content and returns recovery content.
     *
     * @return This Pipe object for method chaining.
     */
    fun setOnFailure(func: suspend (original: MultimodalContent, processed: MultimodalContent) -> MultimodalContent): Pipe {
        this.onFailure = func
        return this
    }


    
    /**
     * Legacy failure function for backward compatibility with string-based failure handling.
     */
    fun setStringOnFailure(func: (json: String, newText: String) -> Boolean): Pipe {
        this.onFailure = { original, processed ->
            val result = func(original.text, processed.text)
            if(result)
            {
                processed
            }
            else
            {
                processed.terminate()
                processed
            }
        }
        return this
    }

    /**
     * Sets the validator pipe for the pipe. This pipe will be used to validate the multimodal output
     * of the AI model. Will be invoked if the validator function is not assigned.
     *
     * @param pipe The validator pipe to use for validation
     * @param saveSnapshotAsPageKey If true, the default snapshot saving to ensure the output survives this pipe,
     * will instead be saved into context during an overwrite of preValidation.
     * @return This Pipe object for method chaining
     */
    fun setValidatorPipe(pipe: Pipe, saveSnapshotAsPageKey: Boolean = false) : Pipe
    {
        this.validatorPipe = pipe
        pipe.setParentPipe(this)
        return this
    }



    /**
     * Sets the transformation pipe for the pipe. This pipe will be used to transform the multimodal output
     * from the AI model. Will be invoked if the transformation function is not assigned.
     *
     * @param pipe The transformation pipe to use for transformation
     * @return This Pipe object for method chaining
     */
    fun setTransformationPipe(pipe: Pipe): Pipe
    {
        this.transformationPipe = pipe
        transformationPipe?.setParentPipe(this)
        return this
    }



    /**
     * Sets the branch pipe for the pipe. This pipe will be used to handle validation failures.
     * Will be invoked if the failure function is not assigned.
     *
     * @param pipe The branch pipe to use for failure handling
     * @return This Pipe object for method chaining
     */
    infix fun setBranchPipe(pipe: Pipe): Pipe
    {
        this.branchPipe = pipe
        branchPipe?.setParentPipe(this)
        return this
    }

    /**
     * Setter to set the TPipe model reasoning pipe.
     */
    fun setReasoningPipe(pipe: Pipe): Pipe
    {
        this.reasoningPipe = pipe
        reasoningPipe?.setParentPipe(this)
        return this
    }

    /**
     * Set the Pipe Protocol Context if it's desirable to have the llm decide on tool usage instead of the
     * programmer.
     *
     * @see PcpContext
     * @param context The Pipe Protocol Context to set
     * @return This Pipe object for method chaining
     */
    fun setPcPContext(context: PcpContext) : Pipe
    {
        pcpContext = context
        return this
    }

    /**
     * Enables memory introspection tools for this pipe with the specified configuration.
     * These tools allow agents to query and manage the lorebook and memory system.
     *
     * @param config The security "leash" configuration for introspection
     * @return This Pipe object for method chaining
     */
    fun enableMemoryIntrospection(config: MemoryIntrospectionConfig = MemoryIntrospectionConfig()): Pipe
    {
        memoryIntrospectionConfig = config
        return this
    }

    /**
     * Sets the pcp description which can be used to override the default description.
     */
    fun setPcPDescription(description: String) : Pipe
    {
        pcpDescription = description
        return this
    }

    /**
     * Sets custom instructions for merged PCP + JSON output mode.
     * This overrides the default merged response format instructions.
     * Merged mode is automatically activated when both PCP tools and JSON output are configured.
     *
     * @param instructions Custom instructions for how to format merged responses
     * @return This Pipe object for method chaining
     */
    fun setMergedPcpJsonInstructions(instructions: String): Pipe
    {
        mergedPcpJsonInstructions = instructions
        return this
    }

    /**
     * Process LLM response for PCP requests and execute them with context validation.
     * This is the main integration point between Pipe and PCP execution.
     * 
     * @param llmResponse The raw LLM response containing potential PCP requests
     * @return PcpExecutionResult with execution results and any errors
     * 
     * @since This method enforces context restrictions and security policies.
     * Ensure pcpContext is properly configured before calling.
     */
    suspend fun processPcpResponse(llmResponse: String): PcpExecutionResult
    {
        val parser = PcpResponseParser()
        val parseResult = parser.extractPcpRequests(llmResponse)
        
        if(!parseResult.success)
        {
            return PcpExecutionResult(
                success = false,
                results = emptyList(),
                errors = parseResult.errors,
                executionTimeMs = 0
            )
        }
        
        // Execute with actual pipe context and apply introspection leash if enabled
        val config = memoryIntrospectionConfig
        return if(config != null)
        {
            MemoryIntrospection.withCoroutineScope(config)
            {
                pcpDispatcher.executeRequests(parseResult.requests, pcpContext)
            }
        }
        else
        {
            pcpDispatcher.executeRequests(parseResult.requests, pcpContext)
        }
    }

    /**
     * Define the available p2p agents that can be called by this pipe. Will be saved and injected at system prompt
     * construction. Can be updated by calling applySystemPrompt again if required.
     * @param agentList The list of agents to set
     * @return This Pipe object for method chaining
     */
    fun setP2PAgentList(agentList: MutableList<AgentDescriptor>) : Pipe
    {
        if(agentList.isEmpty()) throw IllegalArgumentException("Agent list cannot be empty")

        p2pAgentDescriptors = agentList
        return this
    }

    /**
     * Getter to return what p2p descriptors are on this pipe. This is mainly useful for exception handling
     * and ensuring conformance requirements.
     */
    fun getP2PAgentList() : List<AgentDescriptor>?
    {
        return p2pAgentDescriptors
    }

    /**
     * Define the description used to inform the llm of the agents it can attempt to make calls to.
     */
    fun setP2PDescription(description: String) : Pipe
    {
        p2pAgentRequestsDescription = description
        return this
    }


    /**
     * Get the truncation settings for this pipe. Useful for quickly setting up token counting.
     */
    fun getTruncationSettings() : TruncationSettings
    {
        val returnVar = TruncationSettings()
        returnVar.favorWholeWords = favorWholeWords
        returnVar.nonWordSplitCount = nonWordSplitCount
        returnVar.splitForNonWordChar = splitForNonWordChar
        returnVar.multiplyWindowSizeBy = multiplyWindowSizeBy
        returnVar.countSubWordsIfSplit = countSubWordsIfSplit
        returnVar.tokenCountingBias = tokenCountingBias
        returnVar.alwaysSplitIfWholeWordExists = alwaysSplitIfWholeWordExists
        returnVar.countSubWordsInFirstWord = countSubWordsInFirstWord

        returnVar.fillMode = loreBookFillMode
        returnVar.fillAndSplitMode = loreBookFillAndSplitMode
        returnVar.multiPageBudgetStrategy = tokenBudgetSettings?.multiPageBudgetStrategy
        returnVar.pageWeights = tokenBudgetSettings?.pageWeights

        return returnVar
    }

    /**
     * Return a detached copy of the current token-budget settings, if any are configured.
     *
     * @return Copy of the configured token budget settings, or null when token budgeting is disabled.
     */
    fun copyTokenBudgetSettings() : TokenBudgetSettings?
    {
        return tokenBudgetSettings?.deepCopy()
    }

    /**
     * Determine whether legacy automatic truncation is enabled for this pipe.
     *
     * @return True when the pipe is configured to run its built-in truncation path.
     */
    fun isAutoTruncateContextEnabled() : Boolean
    {
        return autoTruncateContext
    }

    /**
     * Determine whether this pipe has any configured overflow-protection path for prompt assembly.
     *
     * @return True when either token budgeting or legacy auto truncation is configured.
     */
    fun hasContextOverflowProtectionConfigured() : Boolean
    {
        return tokenBudgetSettings != null || autoTruncateContext
    }

    /**
     * Read the configured context-window size for this pipe.
     *
     * @return Current context-window size in tokens.
     */
    fun getConfiguredContextWindowSize() : Int
    {
        return contextWindowSize
    }

    /**
     * Read the configured maximum output token count for this pipe.
     *
     * @return Current max-token setting.
     */
    fun getConfiguredMaxTokens() : Int
    {
        return maxTokens
    }

    /**
     * Read the current system prompt text bound to this pipe.
     *
     * @return Current system prompt string.
     */
    fun getSystemPromptText() : String
    {
        return systemPrompt
    }
    
    /**
     * Extracts reasoning content from the last response if available.
     * @return The reasoning content or empty string if not available
     */
    fun getReasoningContent(): String {
        return try {
            if(jsonOutput.contains("reasoning"))
            {
                val json = kotlinx.serialization.json.Json.parseToJsonElement(jsonOutput)
                if(json is kotlinx.serialization.json.JsonObject)
                {
                    json["reasoning"]?.let { element ->
                        if(element is kotlinx.serialization.json.JsonPrimitive) element.content else ""
                    } ?: ""
                } else ""
            } else ""
        }
        catch(e: Exception)
        {
            ""
        }
    }

    /**
     * Enables tracing for this pipe with the specified configuration.
     * Also enables comprehensive token tracking to provide detailed token usage data in traces.
     * @param config The tracing configuration to use
     * @return This Pipe object for method chaining
     * @see traceConfig
     */
    fun enableTracing(config: TraceConfig = TraceConfig(enabled = true)): Pipe
    {
        this.tracingEnabled = true
        this.traceConfig = config
        this.comprehensiveTokenTracking = true
        this.pipeTokenUsage = TokenUsage()
        return this
    }

    /**
     * Disables tracing for this pipe.
     * @return This Pipe object for method chaining
     */
    fun disableTracing(): Pipe
    {
        this.tracingEnabled = false
        return this
    }

    /**
     * Internal method to add trace events during pipe execution.
     */
    protected fun trace(eventType: TraceEventType, phase: TracePhase, content: MultimodalContent? = null, metadata: Map<String, Any> = emptyMap(), error: Throwable? = null)
    {
        // Auto-capture errors for failure event types
        // Only capture if no error exists yet, or if this error has an exception (more specific)
        if(eventType == TraceEventType.PIPE_FAILURE ||
            eventType == TraceEventType.API_CALL_FAILURE || 
            eventType == TraceEventType.VALIDATION_FAILURE || 
            eventType == TraceEventType.TRANSFORMATION_FAILURE)
        {
            if(lastError == null || (error != null && lastError?.exception == null))
            {
                lastError = PipeError(
                    exception = error,
                    eventType = eventType,
                    phase = phase,
                    pipeName = if(pipeName.isNotEmpty()) pipeName else (this::class.simpleName ?: "UnknownPipe"),
                    pipeId = pipeId
                )
            }
        }
        
        if(!tracingEnabled) return
        
        // Check if this event should be traced based on detail level
        if(!EventPriorityMapper.shouldTrace(eventType, traceConfig.detailLevel)) return
        
        // Build metadata based on detail level
        val enhancedMetadata = buildMetadataForLevel(metadata, traceConfig.detailLevel, eventType, error, content, phase)
        
        val event = TraceEvent(
            timestamp = System.currentTimeMillis(),
            pipeId = pipeId,
            pipeName = if(pipeName.isNotEmpty()) pipeName else (this::class.simpleName ?: "UnknownPipe"),
            eventType = eventType,
            phase = phase,
            content = if(shouldIncludeContent(traceConfig.detailLevel)) content else null,
            contextSnapshot = if(shouldIncludeContext(traceConfig.detailLevel)) contextWindow else null,
            metadata = if(traceConfig.includeMetadata) enhancedMetadata else emptyMap(),
            error = error
        )
        
        activeTraceIds.forEach { pipelineId ->
            PipeTracer.addEvent(pipelineId, event)
        }
    }

    /**
     * Internal wrapper for the timeout system to allow it to perform tracing on pipe objects without
     * requiring public visibility for the main trace function.
     */
    internal fun timeoutTrace(eventType: TraceEventType, phase: TracePhase, content: MultimodalContent? = null, metadata: Map<String, Any> = emptyMap(), error: Throwable? = null)
    {
        trace(eventType, phase, content, metadata, error)
    }

    /**
     * Adds a trace ID to the active set for this pipe. Trace events will be broadcast to this ID.
     * Thread-safe.
     * @param id The trace ID to add.
     */
    fun addTraceId(id: String)
    {
        activeTraceIds.add(id)
    }

    /**
     * Removes a trace ID from the active set for this pipe.
     * Thread-safe.
     * @param id The trace ID to remove.
     */
    fun removeTraceId(id: String)
    {
        activeTraceIds.remove(id)
    }

    /**
     * Clears all active trace IDs.
     * Thread-safe.
     */
    fun clearTraceIds()
    {
        activeTraceIds.clear()
    }

    /**
     * Clears the last error stored in this pipe.
     */
    fun clearError()
    {
        lastError = null
    }

    /**
     * Checks if this pipe has an error stored.
     * @return true if an error is present, false otherwise
     */
    fun hasError(): Boolean = lastError != null

    /**
     * Gets the error message from the last error, or empty string if no error.
     * @return The error message or empty string
     */
    fun getErrorMessage(): String = lastError?.message ?: ""

    /**
     * Gets the error type from the last error, or null if no error.
     * @return The TraceEventType of the error or null
     */
    fun getErrorType(): TraceEventType? = lastError?.eventType

    /**
     * Recursively propagates tracing configuration to every child pipe and protects against cycles.
     */
    private fun propagateTracingRecursively(visitedPipes: MutableSet<String> = mutableSetOf())
    {
        if(pipeId in visitedPipes) return
        visitedPipes.add(pipeId)

        if(!tracingEnabled) return

        listOfNotNull(validatorPipe, transformationPipe, branchPipe, reasoningPipe).forEach { childPipe ->
            childPipe.enableTracing(traceConfig)
            childPipe.currentPipelineId = currentPipelineId
            childPipe.propagateTracingRecursively(visitedPipes)
        }
    }

    private fun buildMetadataForLevel(
        baseMetadata: Map<String, Any>, 
        detailLevel: TraceDetailLevel, 
        eventType: TraceEventType,
        error: Throwable?,
        content: MultimodalContent? = null,
        phase: TracePhase? = null
    ): Map<String, Any> {
        val metadata = baseMetadata.toMutableMap()
        
        when(detailLevel)
        {
            TraceDetailLevel.MINIMAL -> {
                // Only error information for failures
                if(error != null)
                {
                    metadata["error"] = error.message ?: "Unknown error"
                }
            }
            
            TraceDetailLevel.NORMAL -> {
                // Basic pipe information
                metadata["model"] = model.ifEmpty { "not_set" }
                metadata["provider"] = provider.name
                if(error != null)
                {
                    metadata["error"] = error.message ?: "Unknown error"
                    metadata["errorType"] = error::class.simpleName ?: "Unknown"
                }
            }
            
            TraceDetailLevel.VERBOSE -> {
                // Standard metadata plus function bindings
                metadata["pipeClass"] = this::class.qualifiedName ?: "UnknownPipe"
                metadata["model"] = model.ifEmpty { "not_set" }
                metadata["provider"] = provider.name
                metadata["hasValidatorFunction"] = (validatorFunction != null).toString()
                metadata["hasTransformationFunction"] = (transformationFunction != null).toString()
                metadata["hasPreValidationFunction"] = (preValidationFunction != null).toString()
                if(error != null)
                {
                    metadata["error"] = error.message ?: "Unknown error"
                    metadata["errorType"] = error::class.simpleName ?: "Unknown"
                }
            }
            
            TraceDetailLevel.DEBUG -> {
                // Everything including function details and model reasoning
                metadata["pipeClass"] = this::class.qualifiedName ?: "UnknownPipe"
                metadata["model"] = model.ifEmpty { "not_set" }
                metadata["provider"] = provider.name
                metadata["inputText"] = if(eventType == TraceEventType.PIPE_START && phase == TracePhase.INITIALIZATION) content?.text ?: "" else {"N/A"}
                metadata["useModelReasoning"] = useModelReasoning.toString()
                metadata["validatorFunction"] = validatorFunction?.toString() ?: "null"
                metadata["transformationFunction"] = transformationFunction?.toString() ?: "null"
                metadata["preValidationFunction"] = preValidationFunction?.toString() ?: "null"
                metadata["onFailure"] = onFailure?.toString() ?: "null"
                metadata["validatorPipe"] = if(validatorPipe != null) validatorPipe!!::class.simpleName ?: "UnknownPipe" else "null"
                metadata["transformationPipe"] = if(transformationPipe != null) transformationPipe!!::class.simpleName ?: "UnknownPipe" else "null"
                metadata["branchPipe"] = if(branchPipe != null) branchPipe!!::class.simpleName ?: "UnknownPipe" else "null"
                if(error != null)
                {
                    metadata["error"] = error.message ?: "Unknown error"
                    metadata["errorType"] = error::class.simpleName ?: "Unknown"
                    metadata["stackTrace"] = error.stackTraceToString()
                }
            }
        }
        
        // Add model reasoning metadata when available (for models with native reasoning support)
        if(eventType == TraceEventType.API_CALL_SUCCESS && content != null && content.modelReasoning.isNotEmpty())
        {
            metadata["reasoningContent"] = content.modelReasoning
            metadata["modelSupportsReasoning"] = true
            metadata["reasoningEnabled"] = useModelReasoning
            metadata["reasoningLength"] = content.modelReasoning.length
        }
        
        // Add reasoning pipe metadata only once per execution to avoid duplication (for reasoning pipes)
        if(isExecutingAsReasoningPipe && !reasoningContentAlreadyTraced && eventType == TraceEventType.API_CALL_SUCCESS && content != null)
        {
            metadata["reasoningContent"] = content.text
            metadata["isReasoningPipe"] = true
            reasoningContentAlreadyTraced = true
        }
        
        return metadata
    }
    
    private fun shouldIncludeContent(detailLevel: TraceDetailLevel): Boolean {
        return when(detailLevel) {
            TraceDetailLevel.MINIMAL -> false
            TraceDetailLevel.NORMAL -> false
            TraceDetailLevel.VERBOSE -> traceConfig.includeContext
            TraceDetailLevel.DEBUG -> traceConfig.includeContext
        }
    }

    private fun shouldIncludeContext(detailLevel: TraceDetailLevel): Boolean {
        return when(detailLevel) {
            TraceDetailLevel.MINIMAL -> false
            TraceDetailLevel.NORMAL -> false
            TraceDetailLevel.VERBOSE -> traceConfig.includeContext
            TraceDetailLevel.DEBUG -> traceConfig.includeContext
        }
    }


    /**
     * Assign the container ptr to this pipe. This allows the pipe to be a proxy for a container that would normally
     * be top level. IE: You can place a manifold or splitter inside a pipeline. Instead of executing this pipe
     * the container pointer will be redirected to and ran instead.
     */
    fun setContainerPtr(ptr: P2PInterface) : Pipe
    {
        containerPtr = ptr
        return this
    }


//============================================= functions ============================================================//

    /**
     * Selects the global context mode based on the pageKeyList. If the list is empty, it returns the example for ContextWindow.
     * Otherwise, it returns the example for MiniBank.
     *
     * @return The selected global context mode as a string.
     */
    fun selectGlobalContextMode() : String
    {
        if(pageKeyList.isEmpty())
        {
            return examplePromptFor(ContextWindow::class)
        }

        return examplePromptFor(MiniBank::class)
    }

    /**
     * Optional init function. Some AI APIs may require some level of complex login, api keys, access keys,
     * decryption, or other steps to start using the service or model in question. This function can be used to
     * handle that initialization. If not required, this function can be implemented with an empty function.
     */
     open suspend fun init() : Pipe
     {
         // Propagate timeout settings recursively if enabled
         if(enablePipeTimeout && applyTimeoutRecursively)
         {
             listOfNotNull(validatorPipe, branchPipe, transformationPipe, reasoningPipe).forEach { child ->
                 child.enablePipeTimeout = true
                 child.pipeTimeout = pipeTimeout
                 child.timeoutStrategy = timeoutStrategy
                 child.maxRetryAttempts = maxRetryAttempts
                 child.pipeRetryFunction = pipeRetryFunction
                 child.applyTimeoutRecursively = true
             }
         }

         // Name children FIRST, before they initialize their own children
         if(validatorPipe?.pipeName?.isEmpty() == true) validatorPipe?.pipeName = "$pipeName->validator pipe"
         if(branchPipe?.pipeName?.isEmpty() == true) branchPipe?.pipeName = "$pipeName->branch pipe"
         if(transformationPipe?.pipeName?.isEmpty() == true) transformationPipe?.pipeName = "$pipeName->transformation pipe"
         if(reasoningPipe?.pipeName?.isEmpty() == true) reasoningPipe?.pipeName = "$pipeName->reasoning pipe"
         
         // THEN initialize them
         validatorPipe?.init()
         validatorPipe?.pipelineRef = pipelineRef
         
         branchPipe?.init()
         branchPipe?.pipelineRef = pipelineRef
         
         transformationPipe?.init()
         transformationPipe?.pipelineRef = pipelineRef
         
         reasoningPipe?.init()
         reasoningPipe?.pipelineRef = pipelineRef

         // Enable memory introspection tools if configured
         memoryIntrospectionConfig?.let {
             MemoryIntrospectionTools.registerAndEnable(pcpContext)
         }

         //Force name the pipe for sanity when debugging issues of nested reasoning.
         if(isReasoningPipe())
         {
             if(pipeName.isEmpty()) pipeName = "${getParentPipe()?.pipeName}->reasoning pipe"
         }

         return this
     }

    /**
     * Truncates context windows based on supplied settings, or custom configurations for supported models.
     * Each module implements models for a given provider. The developer of each TPipe module can proceed to
     * directly handle exact configurations for truncation or just use the class variables here to supply
     * the function parameters for truncation.
     */
    abstract fun truncateModuleContext(): Pipe

    /**
     * Suspend-safe truncation entrypoint used during execution. Subclasses that need remote-aware lorebook
     * selection should override this instead of relying on the legacy synchronous helper.
     *
     * @return This pipe instance after truncation has been applied.
     */
    open suspend fun truncateModuleContextSuspend(): Pipe
    {
        return truncateModuleContext()
    }

    /**
     * Iterates through all binary content in the multimodal object, converts any non-base64 content to base64,
     * and counts the total token cost of all binary content combined using Dictionary and truncation settings.
     *
     * @param content The multimodal content containing binary data to process.
     * @param truncationSettings The truncation settings to use for token counting.
     * @return The total token count for all binary content.
     */
    fun countBinaryTokens(content: MultimodalContent, truncationSettings: TruncationSettings): Int
    {
        var totalTokens = 0

        for(i in content.binaryContent.indices)
        {
            val binary = content.binaryContent[i]

            //Convert to base64 if not already in that format.
            val base64Content = when(binary)
            {
                is BinaryContent.Bytes ->
                {
                    val converted = binary.toBase64()
                    content.binaryContent[i] = converted
                    converted
                }
                is BinaryContent.Base64String -> binary
                is BinaryContent.CloudReference ->
                {
                    //Cloud references don't need conversion, count the URI.
                    totalTokens += Dictionary.countTokens(binary.uri, truncationSettings)
                    continue
                }
                is BinaryContent.TextDocument ->
                {
                    //Count text document content directly.
                    totalTokens += Dictionary.countTokens(binary.content, truncationSettings)
                    continue
                }
            }

            //Count tokens in the base64 string.
            totalTokens += Dictionary.countTokens(base64Content.data, truncationSettings)
        }

        return totalTokens
    }

    /**
     * Count all tokens used for system prompt, reasoning, input, context, and binary content.
     * This allows us to determine how much we've spent in the context window and can be used to compare
     * how much space we actually have left to use.
     */
    private fun calculateTokensSpent(content: MultimodalContent) : Int
    {
       var totalTokens = 0

        val truncationSettings = getTruncationSettings()
        val systemPromptSize = Dictionary.countTokens(systemPrompt, truncationSettings)
        totalTokens += systemPromptSize //Count the system prompt.

        val userPrompt = content.text
        val userPromptSize = Dictionary.countTokens(userPrompt, truncationSettings)
        totalTokens += userPromptSize //Then the user prompt.

        //Next, all binary content.
        totalTokens += countBinaryTokens(content, truncationSettings)

        //Next count any spent model reasoning.
        val reasoningSpent = content.modelReasoning
        totalTokens += Dictionary.countTokens(reasoningSpent, truncationSettings)

        //Finally, count up all our context we've spent.
        if(miniContextBank.isEmpty())
        {
            val contextJson = serialize(contextWindow)
            val contextSize = Dictionary.countTokens(contextJson, truncationSettings)
            totalTokens += contextSize
        }

        else
        {
            val miniBankJson = serialize(miniContextBank)
            val miniBankSize = Dictionary.countTokens(miniBankJson, truncationSettings)
            totalTokens += miniBankSize
        }

        return totalTokens
    }

    /**
     * Counts tokens inside a ContextWindow snapshot after truncation.
     */
    internal fun countContextWindowTokens(contextWindow: ContextWindow, truncationSettings: TruncationSettings): Int
    {
        val serializedWindow = serialize(contextWindow)
        return Dictionary.countTokens(serializedWindow, truncationSettings)
    }



    /**
     * Truncates context windows based on supplied settings, or custom configurations for supported models.
     * Each module implements models for a given provider. The developer of each TPipe module can proceed to
     * directly handle exact configurations for truncation or just use the class variables here to supply
     * the function parameters for truncation.
     */
    private suspend fun truncateToFitTokenBudget(content: MultimodalContent) : MultimodalContent
    {
        val configuredBudget = tokenBudgetSettings ?: return content
        val workingBudget = cloneTokenBudgetSettings(configuredBudget)
        val executionSnapshot = captureTokenBudgetExecutionSnapshot()

        try
        {
            setTokenBudgetInternal(workingBudget)

            var tempContextWindowSize = 0
            var workingContextWindowSpace = contextWindowSize
            tempContextWindowSize = workingContextWindowSpace
            val truncationSettings = getTruncationSettings()

            val userPrompt = content.text
            var userPromptTokenCost = Dictionary.countTokens(userPrompt, truncationSettings)
            val isUserPromptSpaceDynamic = workingBudget.userPromptSize == null

            if(isUserPromptSpaceDynamic)
            {
                workingBudget.userPromptSize = userPromptTokenCost
                workingContextWindowSpace -= userPromptTokenCost

                if(workingContextWindowSpace <= 0)
                {
                    workingBudget.userPromptSize = workingBudget.userPromptSize?.minus(
                        workingContextWindowSpace.absoluteValue
                    )

                    workingContextWindowSpace = tempContextWindowSize
                    userPromptTokenCost = workingBudget.userPromptSize ?: 0
                    workingContextWindowSpace -= userPromptTokenCost.takeIf { workingContextWindowSpace > userPromptTokenCost }
                        ?: throw Exception("userPromptTokenCost at: $userPromptTokenCost tokens has overflowed the window size of: $workingContextWindowSpace tokens.")
                    tempContextWindowSize = workingContextWindowSpace
                }
            }

            val binaryTokenCost = countBinaryTokens(content, truncationSettings)
            workingContextWindowSpace -= binaryTokenCost

            if(workingContextWindowSpace <= 0)
            {
                if(workingBudget.allowUserPromptTruncation || workingBudget.compressUserPrompt)
                {
                    val tempUserPromptCost = userPromptTokenCost
                    workingBudget.userPromptSize = userPromptTokenCost - binaryTokenCost
                    userPromptTokenCost = workingBudget.userPromptSize ?: 0

                    if(userPromptTokenCost <= 0 && !allowEmptyUserPrompt && !allowEmptyContentObject)
                    {
                        throw Exception("Unable to allocate user prompt space of: $tempUserPromptCost due to large binary content allocation of: $binaryTokenCost")
                    }
                }

                throw Exception("Context window size is too small to fit the binary data. Please increase the context window size. " +
                    "Context window size: ${workingBudget.contextWindowSize} Binary size: ${binaryTokenCost}")
            }

            if(miniContextBank.isEmpty())
            {
                if(!workingBudget.truncateContextWindowAsString)
                {
                    contextWindow.selectAndTruncateContextSuspend(
                        content.text,
                        workingContextWindowSpace,
                        workingBudget.truncationMethod,
                        truncationSettings,
                        fillMode = loreBookFillMode,
                        fillAndSplitMode = loreBookFillAndSplitMode,
                        preserveTextMatches = workingBudget.preserveTextMatches
                    )
                }

                else
                {
                    val asString = contextWindow.combineAndTruncateAsStringWithSettingsSuspend(
                        content.text,
                        workingContextWindowSpace,
                        truncationSettings,
                        workingBudget.truncationMethod
                    )

                    contextWindow.clear()
                    contextWindow.contextElements.add(asString)
                }
            }

            else
            {
                truncateMiniBank(content, workingContextWindowSpace, workingBudget, truncationSettings)
            }

            val userPromptSpace = workingBudget.userPromptSize

            if(userPromptTokenCost > userPromptSpace!!)
            {
                if(workingBudget.compressUserPrompt)
                {
                    if(shouldBypassSemanticCompression(content.text))
                    {
                        content.metadata["semanticCompressionSkipped"] = "structured-content"
                    }

                    else
                    {
                        val compressionResult = compressPrompt(content.text)
                        val compressedPrompt = if(compressionResult.legend.isNotEmpty())
                        {
                            "${compressionResult.legend}\n\n${compressionResult.compressedText}"
                        }
                        else
                        {
                            compressionResult.compressedText
                        }

                        content.text = compressedPrompt
                        userPromptTokenCost = Dictionary.countTokens(content.text, truncationSettings)

                        content.metadata["semanticCompressionApplied"] = true
                        content.metadata["semanticCompressionLegend"] = compressionResult.legend
                        content.metadata["semanticCompressionLegendMap"] = compressionResult.legendMap
                        content.metadata["semanticCompressionTokenCost"] = userPromptTokenCost
                        pipeMetadata["semanticCompressionApplied"] = true
                        pipeMetadata["semanticCompressionLegend"] = compressionResult.legend
                        pipeMetadata["semanticCompressionLegendMap"] = compressionResult.legendMap
                        pipeMetadata["semanticCompressionTokenCost"] = userPromptTokenCost
                        trace(
                            TraceEventType.CONTEXT_PREPARED,
                            TracePhase.CONTEXT_PREPARATION,
                            content.deepCopy(),
                            metadata = mapOf(
                                "semanticCompressionApplied" to true,
                                "semanticCompressionLegend" to compressionResult.legend,
                                "semanticCompressionLegendMap" to compressionResult.legendMap,
                                "semanticCompressionTokenCost" to userPromptTokenCost
                            )
                        )
                    }
                }

                if(userPromptTokenCost > userPromptSpace!!)
                {
                    if(workingBudget.allowUserPromptTruncation)
                    {
                        val converseContent = deserialize<ConverseHistory>(content.text)
                        if(converseContent != null)
                        {
                            val truncateWindow = ContextWindow()
                            truncateWindow.converseHistory = converseContent
                            truncateWindow.truncateConverseHistoryWithObject(
                                workingBudget.userPromptSize!!,
                                0,
                                workingBudget.truncationMethod,
                                truncationSettings
                            )

                            val newUserPrompt = serializeConverseHistory(truncateWindow.converseHistory)
                            content.text = newUserPrompt
                        }

                        else
                        {
                            val jsonContentInUserPrompt = extractAllJsonObjects(content.text)
                            var exteriorContent = extractNonJsonText(content.text)

                            if(workingBudget.preserveJsonInUserPrompt)
                            {
                                exteriorContent = Dictionary.truncateWithSettings(
                                    exteriorContent,
                                    workingBudget.userPromptSize!!,
                                    workingBudget.truncationMethod,
                                    truncationSettings
                                )

                                var mergedUserPrompt = ""
                                mergedUserPrompt += exteriorContent

                                for(jsonObject in jsonContentInUserPrompt)
                                {
                                    mergedUserPrompt += jsonObject.toString()
                                }

                                content.text = mergedUserPrompt
                            }

                            else
                            {
                                var fullUserPrompt = content.text
                                fullUserPrompt = Dictionary.truncateWithSettings(
                                    fullUserPrompt,
                                    workingBudget.userPromptSize!!,
                                    workingBudget.truncationMethod,
                                    truncationSettings
                                )

                                content.text = fullUserPrompt
                            }
                        }
                    }
                    else
                    {
                        throw Exception(
                            "User prompt still exceeds the allotted budget after semantic compression. " +
                                "Enable allowUserPromptTruncation or increase the prompt budget."
                        )
                    }
                }
            }

            return content
        }

        finally
        {
            restoreTokenBudgetExecutionSnapshot(executionSnapshot)
        }
    }

    /**
     * Determines whether semantic compression should be bypassed because the prompt is structured content.
     *
     * Semantic compression is intended for natural-language prompts. JSON, XML, code fences, and other
     * machine-readable fragments should continue to use TPipe's existing budgeting and truncation paths.
     *
     * @param prompt Raw user prompt text.
     *
     * @return True when the prompt appears too structured for semantic compression.
     */
    internal fun shouldBypassSemanticCompression(prompt: String): Boolean
    {
        val trimmed = prompt.trim()

        if(trimmed.isEmpty())
        {
            return true
        }

        if(trimmed.contains("```"))
        {
            return true
        }

        if(trimmed.startsWith("{") || trimmed.startsWith("["))
        {
            if(extractAllJsonObjects(trimmed).isNotEmpty())
            {
                return true
            }
        }

        if(Regex("<[A-Za-z!/][^>]*>").containsMatchIn(trimmed))
        {
            return true
        }

        return false
    }


    /**
     * Apply budget-aware truncation to MiniBank pages.
     * Each page receives an allocated portion of the total budget.
     */
    private suspend fun truncateMiniBank(
        content: MultimodalContent,
        totalBudget: Int,
        budget: TokenBudgetSettings,
        truncationSettings: TruncationSettings
    )
    {
        val pageKeys = miniContextBank.contextMap.keys.toList()
        if(pageKeys.isEmpty()) return

        val pageBudgets = calculatePageBudgets(
            totalBudget,
            pageKeys,
            budget.multiPageBudgetStrategy,
            budget.pageWeights,
            truncationSettings,
            budget.reserveEmptyPageBudget
        )

        var totalUsedBudget = 0

        for((pageKey, contextWindow) in miniContextBank.contextMap)
        {
            val allocatedBudget = pageBudgets[pageKey] ?: 0
            if(allocatedBudget <= 0) continue

            contextWindow.selectAndTruncateContextSuspend(
                content.text,
                allocatedBudget,
                budget.truncationMethod,
                truncationSettings,
                fillMode = loreBookFillMode,
                preserveTextMatches = budget.preserveTextMatches
            )

            totalUsedBudget += countContextWindowTokens(contextWindow, truncationSettings)
        }

        if(totalUsedBudget > totalBudget)
        {
            throw IllegalStateException("MiniBank truncation exceeded allocated budget: used $totalUsedBudget, allocated $totalBudget")
        }
    }




    /**
     * Runs a call to generate text from a loaded AI api system. Pipe properties will be used to populate api
     * settings. Then after the call is cleared a check will be made against the validation function provided
     * to handle which pipe direction to flow into for the next step.
     *
     * @param promptInjector A string to inject into the user prompt. This will be appended to the end of the user prompt.
     * This is required to pass the result of a previous pipe to the next pipe.
     *
     * @return Returns the text generated from the api call.
     */
    abstract suspend fun generateText(promptInjector : String = ""): String
    
    /**
     * Runs a call to generate content from a loaded AI api system with multimodal support.
     * This method supports both text and binary content (images, documents, etc.).
     *
     * @param content Multimodal content containing text and/or binary data
     * @return Returns the generated multimodal content from the api call
     */
    open suspend fun generateContent(content: MultimodalContent): MultimodalContent {
        // Default implementation falls back to text-only generation
        val textResult = generateText(content.text)
        return MultimodalContent(text = textResult)
    }


    /**
     * Legacy execute method for backward compatibility with string-based pipelines.
     * Converts string input to MultimodalContent and extracts text from result.
     *
     * @param promptResult A string to inject into the prompt.
     * @return The text result of the AI api call.
     */
    suspend fun execute(promptResult : String = "") : String = coroutineScope{
        val content = MultimodalContent(text = promptResult)
        val result = execute(content)
        result.text
    }
    
    /**
     * Executes the current pipe with multimodal content support. This is the primary execution method
     * that handles validation, transformation, and failure logic with full multimodal capabilities.
     *
     * This execution path expects single-owner usage per instance. If you need concurrency, build a fresh pipe
     * instance for each top-level run.
     *
     * @param content Multimodal content containing text and/or binary data.
     * @return The multimodal result of the AI api call.
     */
    suspend fun execute(content: MultimodalContent): MultimodalContent = coroutineScope {
        var result = executeMultimodal(content)
        while(result.repeatPipe)
        {
            result = executeMultimodal(result)
        }
        return@coroutineScope result
    }
    
    /**
     * Attempts to abort the current LLM call by cancelling the active execution job.
     * Child classes can override this to perform additional provider-specific cleanup.
     */
    open suspend fun abort() {
        activeJob?.cancel()
        activeJob = null
    }

    /**
     * Internal multimodal execution logic shared by both execute methods
     */
    private suspend fun executeMultimodal(inputContent: MultimodalContent): MultimodalContent = coroutineScope{
        if(enablePipeTimeout)
        {
            PipeTimeoutManager.startTracking(this@Pipe, pipeTimeout)
            // If we're set to retry, we MUST have a snapshot.
            if(timeoutStrategy == PipeTimeoutStrategy.Retry && maxRetryAttempts > 0)
            {
                inputContent.saveSnapshot()
            }
        }

        try {

        /**
         * These now need to be mutually exclusive due to the destructive interaction they can have with each other
         * after enabling token budgeting. In practice, this should be fine since token budgeting does everything
         * standard auto-truncation does and more. So there would never be a case where the user would want both.
         */
        if(tokenBudgetSettings != null) autoTruncateContext = false

        //Initialize comprehensive token tracking if enabled.
        if(comprehensiveTokenTracking)
        {
            //Reset token usage tracking for this execution cycle.
            pipeTokenUsage = TokenUsage()
        }

        if(saveSnapshot)
        {
            inputContent.saveSnapshot()
        }

        /**
         * Footgun. This case needs to be handled, and we need to shut down the pipeline by default when we have
         * an empty user prompt or empty content object. Typically, this is just a footgun that will go undetected,
         * and then cause catastrophic damage as the llm gets confused by the empty prompt.
         *
         * @see [allowEmptyUserPrompt] [allowEmptyContentObject]
         */
        if(inputContent.isEmpty() || userPrompt.isEmpty())
        {
            if(userPrompt.isEmpty() && inputContent.text.isEmpty() && !allowEmptyUserPrompt && !allowEmptyContentObject)
            {
                inputContent.terminate()
                trace(TraceEventType.PIPE_FAILURE, TracePhase.INITIALIZATION,
                    error = Exception("Empty user prompt, or content object was passed into this pipe."))

                throw Exception("Empty user prompt, or content object was passed into this pipe.")
            }

            if(inputContent.isEmpty() && !allowEmptyContentObject)
            {
                inputContent.terminate()
                trace(TraceEventType.PIPE_FAILURE, TracePhase.INITIALIZATION,
                    error = Exception("Empty user prompt, or content object was passed into this pipe."))

                throw Exception("Empty user prompt, or content object was passed into this pipe.")
            }

            //Both cases were explicitly bypassed by the developer, so we'll proceed despite the empty content object.
        }

        /**
         * Declare local function to address the multiple times we need to call this internal wrapping.
         * This is intended to be an internal mechanism of pipe execution so we want to leverage Kotlin's ability
         * to have true local functions.
         */
        fun embedContentIntoInternalConverse(content: MultimodalContent) : MultimodalContent
        {
            val existingHistory = pipeMetadata["wrappedConverseHistory"] as? ConverseHistory
            if(existingHistory != null)
            {
                existingHistory.add(converseRole, content)
                pipeMetadata["wrappedConverseHistory"] = existingHistory
                return MultimodalContent(text = serializeConverseHistory(existingHistory))
            }

            return content
        }

        /**
         * If we're using this pipe a proxy we'll repoint to the proxy container pointer instead, and execute whatever
         * it is. The result will be returned here and then out to the rest of the pipeline. By doing this, we can
         * allow the case of storing a higher level container inside a lower level pipeline.
         */
        if(containerPtr != null)
        {
            return@coroutineScope embedContentIntoInternalConverse(inputContent).takeIf { wrapContentWithConverseHistory } ?: inputContent
        }

        /**
         * Detect if the prior pipe's output was in converse. If it was we'll try to capture it and store it in the
         * pipe's metadata to reference at the exit of this pipe. We'll then wrap whatever the output of this pipe is
         * with [ConverseHistory]
         */
        if(wrapContentWithConverseHistory)
        {
            val converseHistory = deserialize<ConverseHistory>(inputContent.text)

            if(converseHistory != null)
            {
                pipeMetadata["wrappedConverseHistory"] = converseHistory
            }
        }

        /**
         * NOTE: This has been moved here from [init] due to needing to reset each execution step to clean up any
         * injected model reasoning into the system prompt.
         */
        applySystemPrompt(inputContent)

        //Trace the start of this pipe, and print out the value of the output of the previous pipe or initial input.
        trace(TraceEventType.PIPE_START, TracePhase.INITIALIZATION, inputContent)

        //Bind this pipe to the content object as we pass it through for usage later if needed.
        inputContent.currentPipe = this@Pipe

        /**
         * Duplicate content to create a safe snapshot if desired. This is useful for branch failures
         * and walking back in time in the event of the llm transforming the text output into a refusal
         * or otherwise broken state.
         */
        if(inputContent.useSnapshot)
        {
            inputContent.metadata["snapshot"] = inputContent.deepCopy() as Any
        }

        /**
         * Allows for caching and snapshotting directly to the metadata of this pipe. Which can simplify
         * retrival of saved user prompts if we want to autosave this data.
         */
        if(cacheInput)
        {
            pipeMetadata[USER_PROMPT_SNAPSHOT] = inputContent.deepCopy()
        }

        //Get rid of model reasoning to prevent messed up token counts later.
        inputContent.modelReasoning = ""

        /**
         * Run the pre init function if it exists. This is executed before any changes can happen and before context is
         * loaded. This is useful for things like managing context truncation, token counting, adjusting any malformed
         * inputs or any other cases where the human needs to intervene before the automated systems of the pipe's
         * execution comes into play.
         */
        if(preInitFunction != null)
        {
            preInitFunction?.invoke(inputContent)
        }


        
            if(readFromGlobalContext)
            {
                //Pull from context bank if no page keys are set.
                if(pageKey.isEmpty() && pageKeyList.isEmpty())
                {
                    contextWindow = ContextBank.getBankedContextWindowSuspend()
                }

                else
                {
                    //Pull multiple pages of global context from the bank if more than one key was provided.
                    if(pageKeyList.isNotEmpty())
                    {
                        //Populate the mini bank for multi-page key setup.
                        for(page in pageKeyList)
                        {
                            val pagedContext = ContextBank.getContextFromBankSuspend(page)
                            miniContextBank.contextMap[page] = pagedContext
                        }

                        //Force add the banked context if the user forced it to be pulled even with page keys.
                        if(pullFromBankedContext)
                        {
                            val bankedContext = ContextBank.getBankedContextWindowSuspend()
                            miniContextBank.contextMap["Banked Context"] = bankedContext
                        }
                    }


                    contextWindow = ContextBank.getContextFromBankSuspend(pageKey)

                    /**
                     * Pull from global context if designated defaulting to the loaded bank, and using a paged bank value
                     * if the page key was ever set.
                     */
                    var contextWindowJson = ""
                    var miniBankJson = ""

                    //Only serialize this is if tracing is enabled to keep things performant.
                    if(tracingEnabled)
                    {
                        contextWindowJson = serialize(contextWindow)
                        miniBankJson = serialize(miniContextBank)
                    }

                    /**
                     * Trace both the context pull stage, and everything we have extracted from the context.
                     * NOTE: We will need to do this trace against the window, and the mini bank if any of
                     * the pre-validation functions are used.
                     */
                    trace(TraceEventType.CONTEXT_PULL,
                        TracePhase.CONTEXT_PREPARATION,
                        metadata = mapOf("contextText" to contextWindow.toString(),
                            "pageKey" to pageKey,
                            "contextWindow" to contextWindowJson,
                            "miniBank" to miniBankJson))
                }
            }


            /**
             * Otherwise try to pull from the parent pipeline's context if set. This overrides pulling from global
             * context, and also overrides and overwrites any manual context setting if enabled.
             */
            if(readFromPipelineContext)
            {
                contextWindow.merge(pipelineRef?.context?.deepCopy() ?: ContextWindow(), emplaceLorebook, appendLoreBook, emplaceConverseHistory, emplaceConverseHistoryOnlyIfNull)
                miniContextBank.merge(pipelineRef?.miniBank?.deepCopy() ?: miniContextBank, emplaceLorebook, appendLoreBook, emplaceConverseHistory, emplaceConverseHistoryOnlyIfNull)
            }

            if(readFromParentPipeContext && parentPipeRef != null)
            {
                contextWindow.merge(parentPipeRef!!.contextWindow.deepCopy(), emplaceLorebook, appendLoreBook, emplaceConverseHistory, emplaceConverseHistoryOnlyIfNull)
                miniContextBank.merge(parentPipeRef!!.miniContextBank.deepCopy(), emplaceLorebook, appendLoreBook, emplaceConverseHistory, emplaceConverseHistoryOnlyIfNull)
            }

            /**
             * If enabled, apply the pre-validation function to the context window before the user prompt is merged.
             * This allows for context window modifications that are not dependent on the user prompt. This may be desired
             * when very granular, or specific modifications need to be made and always applied, regardless of the function
             * that might be calling this pipe or pipeline, or any other events that might be occurring prior to the context
             * window becoming fully settled after all external pulls, or retrieval has occurred.
             */
            if(preValidationFunction != null)
            {
                trace(TraceEventType.VALIDATION_START, TracePhase.PRE_VALIDATION,
                      metadata = mapOf("contextText" to contextWindow.toString()))
                val deferredContextResult : Deferred<ContextWindow> = async {
                    preValidationFunction?.invoke(contextWindow, inputContent) ?: contextWindow
                }

                if(tracingEnabled)
                {
                    var contextWindowJson = ""
                    contextWindowJson = serialize(contextWindow)

                    contextWindow = deferredContextResult.await()
                    trace(TraceEventType.VALIDATION_SUCCESS, TracePhase.PRE_VALIDATION,
                        metadata = mapOf("contextWindow" to contextWindowJson))
                }


            }

            /**
             * Execute the mini bank version of pre-validation if enabled. This allows the pipe to address
             * situations where multiple banked context pages need to be evaluated prior to our pipe's llm
             * api call.
             */
            if(preValidationMiniBankFunction !=  null)
            {
                trace(TraceEventType.VALIDATION_START, TracePhase.PRE_VALIDATION,
                    metadata = mapOf("miniBankText" to miniContextBank.toString(),
                        "functionName" to preValidationMiniBankFunction.toString()))

                val deferredMiniBankResult : Deferred<MiniBank> = async {
                    preValidationMiniBankFunction?.invoke(miniContextBank, inputContent) ?: miniContextBank
                }

                miniContextBank = deferredMiniBankResult.await()

                if(tracingEnabled)
                {
                    var miniBankJson = ""
                    miniBankJson = serialize(miniContextBank)

                    trace(TraceEventType.VALIDATION_SUCCESS, TracePhase.PRE_VALIDATION,
                        metadata = mapOf("miniBank" to miniBankJson))
                }

            }


            /**
             * Merge input content, or supplied externally set content into all of our data that has come into this
             * pipe thus far. This is the final step before truncation, and then execution.
             */
            var baseContent = if(multimodalInput.text.isNotEmpty() || multimodalInput.binaryContent.isNotEmpty()) {
                val merged = MultimodalContent(multimodalInput.text, multimodalInput.binaryContent.toMutableList(), multimodalInput.terminatePipeline)
                merged.addText(inputContent.text)
                merged
            }
            else
            {
                inputContent
            }

            //Bind to prevent nullptr leakage.
            baseContent.currentPipe = inputContent.currentPipe

            /**
             * Merge userPrompt into baseContent before truncation to ensure accurate token budget calculations
             * and proper lorebook key selection using the complete prompt.
             */
            if(userPrompt.isNotEmpty())
            {
                baseContent.text = "${userPrompt}\n\n${baseContent.text}"
            }

            /**
             * If enabled, use the model's truncation settings to automatically truncate the context and lorebook to fit
             * the correct token budget. Lorebook key selection is typically done using the user prompt.
             * However, each provider module may use different methods for handling automatic truncation.
             * Be sure to look at each module to learn how it is addressing internal truncation.
             */
            if(autoTruncateContext)
            {
                trace(TraceEventType.CONTEXT_TRUNCATE, TracePhase.CONTEXT_PREPARATION,
                    metadata = mapOf(
                        "contextWindowSize" to contextWindowSize,
                        "truncateAsString" to truncateContextAsString,
                        "contextWindowTruncation" to contextWindowTruncation.name
                    ))

                truncateModuleContextSuspend() //Default to basic context truncation instead.
            }
            
            // Build full prompt with correct ordering: userPrompt -> user content -> context
            // Note: userPrompt already merged into baseContent before truncation
            var fullPrompt = baseContent.text

            /**
             * NOTE: due to the need to deploy a major bug fix to redress our main issue, this is now decoupled from
             * [autoInjectContext] As such, it is now mutually exclusive and if this enabled, we will be disabling
             * autoTruncateContext outright at the start of the execution step.
             */
            if(tokenBudgetSettings != null)
            {
                //todo: edge case
                /**
                 * Possible edge case with memory usage if binary data is in the converse history of a context object
                 * or in the mini-bank. While this is a very unlikely edge case, standards are very high here, so we
                 * need to at least make a note of this in the event this becomes obviously visible in performance
                 * testing.
                 */

                //Get the correct context data and bring it forward into our base content object.
                if(miniContextBank.isEmpty())
                {
                    /**
                     * We need to do a deep copy so we can clear out the data here and meet expectations for the code ahead.
                     */
                    baseContent.context = contextWindow.deepCopy()
                }

                else
                {
                    baseContent.miniBankContext = miniContextBank.deepCopy()
                }

                /**
                 * Now, we can clear the footgun of how this function behaves. Ensuring we truncate and account for everything as expected.
                 */
                baseContent = truncateToFitTokenBudget(baseContent)
                contextWindow = baseContent.context.deepCopy()
                miniContextBank = baseContent.miniBankContext.deepCopy()

                //Clear both to free up the duplicated memory and ensure our forward merge works correctly after this fix.
                baseContent.context.clear()
                baseContent.miniBankContext.clear()
            }
            
            /**
             * Context can be auto-injected after user content to maintain proper ordering
             */
            if(autoInjectContext)
            {
                /**
                 * EDGE CASE: If the user set a single page key, then programmatically pulled another in, the expected
                 * behavior would be that both are visible to the system. However, that is not at all what happens.
                 * Instead, the contextWindow will stop the mini bank outright. This can cause undefined behavior due
                 * to missing context the programmer would expect to be supplied.
                 */
                if(!contextWindow.isEmpty() && !miniContextBank.isEmpty() &&pageKeyList.isEmpty())
                {
                    //Push into the mini bank to correct the behavior.
                    miniContextBank.contextMap[pageKey] = contextWindow

                    //Push back into the page key list to ensure our mini bank is selected as intended.
                    pageKeyList.add(pageKey)
                }

                //Default to standard context window if multiple keys are not present.
                if(pageKeyList.isEmpty() && miniContextBank.isEmpty())
                {
                    fullPrompt = "${fullPrompt}\n\n${serialize(contextWindow)}"
                }

                //Default to multiple page configuration if multiple page keys were supplied.
                else
                {
                    fullPrompt = "${fullPrompt}\n\n${serialize(miniContextBank)}"
                }
            }

            var processedContent = MultimodalContent(fullPrompt, baseContent.binaryContent.toMutableList(), baseContent.terminatePipeline)
            processedContent.currentPipe = inputContent.currentPipe //Avoid nullptr leakage.

            //Call pre-invoke function to test if we need to optionally skip over this pipe.
            if(preInvokeFunction  != null)
            {
                trace(TraceEventType.PRE_INVOKE, TracePhase.PRE_VALIDATION,
                    metadata = mapOf("invokeFunctionName" to preInvokeFunction.toString()))

                var result : Deferred<Boolean> = async {
                    var internalInvokeResult = false
                    preInvokeFunction?.invoke(inputContent) ?: false
                }

                //Exit if the pre-invoke function returns true indicating we should skip over this pipe.
                val invokeStatus = result.await()

                if(invokeStatus)
                {
                    trace(TraceEventType.PRE_INVOKE, TracePhase.PRE_VALIDATION,
                        metadata = mapOf("exitingInvoke" to preInvokeFunction.toString()))

                    return@coroutineScope embedContentIntoInternalConverse(inputContent).takeIf { wrapContentWithConverseHistory } ?: inputContent
                }

            }

            // Trace the full prompt input
            trace(TraceEventType.PIPE_START, TracePhase.EXECUTION, processedContent, 
                  metadata = mapOf("fullPrompt" to fullPrompt))

            /**Bind this pipe's context window into the multimodal object context window to make it visible to
             * native functions. This allows the transformation function, and validator function to apply modifications
             * to the context
             */
            processedContent.context = contextWindow
            processedContent.miniBankContext = miniContextBank


            /**
             * Some providers are very pedantic about certain chars being in a prompt. To resolve this we need
             * to handle any provider specific cleanups here and now. Beware that if illegal chars are in the context,
             * or the prompt, or system prompt and are needed for some reason that this may break those setups.
             * However, the alternative is a total pipe failure. So it's best learn what illegal chars are
             * for each API and in general avoid using them at all.
             */
            processedContent = cleanPromptText(processedContent)

            //If enabled convert our system prompt into a full converse history object and merge with the user prompt.
            if(copySystemToUserPrompt)
            {
                copySystemPrompt(processedContent)
            }



            //Count input tokens for basic tracking so pipeline counters are always populated.
            countTokens(true, processedContent)

            if(comprehensiveTokenTracking)
            {
                val actualInputTokens = countActualInputTokens(processedContent)
                pipeTokenUsage.inputTokens = actualInputTokens
                pipeTokenUsage.recalculateTotals()
                trace(TraceEventType.CONTEXT_PREPARED, TracePhase.CONTEXT_PREPARATION, processedContent,
                    metadata = mapOf("actualInputTokens" to actualInputTokens))
            }

            try{
                if(reasoningPipe != null)
                {
                    var reasoningResult = executeReasoningPipe(processedContent)
                    reasoningResult.modelReasoning = removeFromFirstOccurrence(reasoningResult.modelReasoning, "##Final Answer##")
                    processedContent.modelReasoning = reasoningResult.modelReasoning
                    injectTPipeReasoning(processedContent) //Required step to transform json into llm thought streams.
                    processedContent = truncateToFitTokenBudget(processedContent) //Invoke again to account for injection overflow.
                }
            }
            catch(e: Exception)
            {
                trace(TraceEventType.PIPE_FAILURE, TracePhase.EXECUTION, processedContent, error = e)
            }

            if(comprehensiveTokenTracking)
            {
                reasoningPipe?.let { pipe ->
                    pipeTokenUsage.addChildUsage("reasoning-${pipe.pipeName}", pipe.getTokenUsage())
                }
            }

            /**
             * Call [generateContent] to invoke the loaded AI api with multimodal support.
             */
            trace(TraceEventType.API_CALL_START, TracePhase.EXECUTION, processedContent)

            var generatedContent = MultimodalContent()

            /**
             * Execute api call to run the llm. Capture any exceptions, and return the result after awaiting the
             * call.
             */
            try {
                val result : Deferred<MultimodalContent> = async {
                    generateContent(processedContent)
                }

                activeJob = result
                try {
                    //Run the llm and await it's output.
                    generatedContent = result.await()
                } finally {
                    activeJob = null
                }
            }
            catch(e: Exception)
            {
                if(e is CancellationException && PipeTimeoutManager.isTimeout(this@Pipe))
                {
                    val result = PipeTimeoutManager.handleTimeoutSignal(this@Pipe, inputContent)
                    
                    if(timeoutStrategy == PipeTimeoutStrategy.CustomLogic)
                    {
                        pipeRetryFunction?.invoke(this@Pipe, inputContent)
                    }

                    if(result.repeatPipe || result.terminatePipeline)
                    {
                        // Return a copy with empty text if we are terminating to avoid returning input text
                        if(result.terminatePipeline) return@coroutineScope result.apply { text = "" }
                        return@coroutineScope result
                    }
                }
                
                // Trace API call failure to capture error
                trace(TraceEventType.API_CALL_FAILURE, TracePhase.EXECUTION, processedContent, error = e)
                exceptionFunction?.invoke(processedContent, e)
            }

            /**
             * LLM generated context objects do not contain the pipe bound to it. So unlike the other passees
             * we need to re-bind it once more.
             */
            generatedContent.currentPipe = this@Pipe

            //Execute post-generation function if provided to allow us to immediately operate on this before validation.
            postGenerateFunction?.invoke(generatedContent).also {
                trace(TraceEventType.POST_GENERATE, TracePhase.VALIDATION,
                    metadata = mapOf("generatedContent" to generatedContent))
            }

            generatedContent.currentPipe = inputContent.currentPipe //Prevent nullptr leakage.
            generatedContent.metadata = inputContent.metadata //Copy to prevent leakage after llm call.

            /**
             * Some llm's are very stubborn even when ordered to return only json. This will allow us to auto-strip
             * any text that's not part of the json away and prevent pipes ahead of us from having unintended behavior
             * or outright failures.
             */
            if(stripNonJson)
            {
                val jsonObjects = extractAllJsonObjects(generatedContent.text)
                if(jsonObjects.isNotEmpty())
                {
                    val newText = serialize(jsonObjects)
                    generatedContent.text = newText
                }

            }


            //Count tokens the model generated for basic tracking.
            val outputTokens = countTokens(false, generatedContent)

            //Perform comprehensive output token tracking if enabled.
            if(comprehensiveTokenTracking)
            {
                //Store the output token count in our usage tracking.
                pipeTokenUsage.outputTokens = outputTokens
                
                //Recalculate totals to include any child pipe usage.
                pipeTokenUsage.recalculateTotals()

                //Trace API call success with comprehensive token metadata.
                trace(TraceEventType.API_CALL_SUCCESS, TracePhase.EXECUTION, generatedContent,
                    metadata = mapOf(
                        "outputTokens" to outputTokens,
                        "totalInputTokens" to pipeTokenUsage.totalInputTokens,
                        "totalOutputTokens" to pipeTokenUsage.totalOutputTokens
                    ))
            }
            
            else
            {
                //Trace API call success with basic token count for backward compatibility.
                trace(TraceEventType.API_CALL_SUCCESS, TracePhase.EXECUTION, generatedContent,
                    metadata = mapOf("tokenCount" to outputTokens))
            }


            /**
             * If a validator pipe is supplied it can be invoked prior to the validation function.
             */
            var validatorPipeContent = generatedContent
            if(validatorPipe != null)
            {
                trace(TraceEventType.BRANCH_PIPE_TRIGGERED, TracePhase.VALIDATION)
                try {
                    if(tracingEnabled)
                    {
                        validatorPipe!!.propagateTracingRecursively()
                    }

                    val validatorPipeResult : Deferred<MultimodalContent> = async {
                        validatorPipe?.execute(generatedContent) ?: MultimodalContent()
                    }

                    validatorPipeContent = validatorPipeResult.await()
                    validatorPipeContent.currentPipe = inputContent.currentPipe //Avoid nullptr leakage.
                    validatorPipeContent.metadata = generatedContent.metadata //Copy to avoid leakage after llm call.
                    //Track validator pipe token usage if comprehensive tracking is enabled.
                    if(comprehensiveTokenTracking)
                    {
                        validatorPipe?.let { pipe ->
                            //Add the validator pipe's token usage to our child pipe tracking.
                            pipeTokenUsage.addChildUsage("validator-${pipe.pipeName}", pipe.getTokenUsage())
                        }
                    }
                }
                catch(e: Exception)
                {
                    trace(TraceEventType.PIPE_FAILURE, TracePhase.VALIDATION, generatedContent, error = e)
                    validatorPipeContent = generatedContent
                }
            }

            if(!validatorPipeContent.shouldTerminate())
            {
                /**
                 * Execute validation function if provided.
                 */
                if(validatorFunction != null)
                {
                    trace(TraceEventType.VALIDATION_START, TracePhase.VALIDATION, generatedContent,
                          metadata = mapOf("inputText" to generatedContent.text))
                    val validatorResult : Deferred<Boolean> = async {
                        validatorFunction?.invoke(generatedContent) ?: true
                    }

                    //Validation passed. Continue to transformation.
                    if(validatorResult.await())
                    {
                        trace(TraceEventType.VALIDATION_SUCCESS, TracePhase.VALIDATION, generatedContent)
                        //Invoke transformation pipe if provided.
                        if(transformationPipe != null)
                        {
                            trace(TraceEventType.BRANCH_PIPE_TRIGGERED, TracePhase.TRANSFORMATION)
                            try {
                                if(tracingEnabled)
                                {
                                    transformationPipe!!.propagateTracingRecursively()
                                }

                                val transformPipeResult : Deferred<MultimodalContent> = async {
                                    transformationPipe?.execute(generatedContent) ?: generatedContent
                                }

                                val metadataBackup = generatedContent.metadata //Required to preserve prior to llm.
                                generatedContent = transformPipeResult.await()
                                generatedContent.currentPipe = inputContent.currentPipe
                                generatedContent.metadata = metadataBackup //Copy over in case the llm stomps this.
                                //Track transformation pipe token usage if comprehensive tracking is enabled.
                                if(comprehensiveTokenTracking)
                                {
                                    transformationPipe?.let { pipe ->
                                        //Add the transformation pipe's token usage to our child pipe tracking.
                                        pipeTokenUsage.addChildUsage("transformation-${pipe.pipeName}", pipe.getTokenUsage())
                                    }
                                }

                            }
                            catch(e: Exception)
                            {
                                trace(TraceEventType.PIPE_FAILURE, TracePhase.TRANSFORMATION, generatedContent, error = e)
                                // Continue with original content if transformation pipe fails
                            }
                        }

                        /**
                         * Apply transformation function if provided.
                         */
                        val finalResult = if(transformationFunction != null)
                        {
                            trace(TraceEventType.TRANSFORMATION_START, TracePhase.TRANSFORMATION, generatedContent,
                                  metadata = mapOf("inputText" to generatedContent.text))
                            val transformed = transformationFunction?.invoke(generatedContent) ?: generatedContent

                            trace(TraceEventType.TRANSFORMATION_SUCCESS, TracePhase.TRANSFORMATION, transformed,
                                  metadata = mapOf("outputText" to transformed.text))
                            transformed
                        } else generatedContent

                        if(!finalResult.context.isEmpty())
                        {
                            /**If the transformation function has adjusted any context values, rewrite the pipe's internal
                             * context window to allow us to update the context window going forward. We need to do it
                             * this way because the validator and transformation function system does not provide a mechanism
                             * to access the actual pipe object that is invoking them.
                             */
                            contextWindow = finalResult.context
                        }

                        if(!finalResult.miniBankContext.isEmpty())
                        {
                            miniContextBank = finalResult.miniBankContext
                        }

                        //Merge in context window changes if enabled.
                        if(updatePipelineContextOnExit)
                        {
                            pipelineRef?.context?.merge(contextWindow, emplaceLorebook, appendLoreBook)
                            pipelineRef?.miniBank?.merge(miniContextBank, emplaceLorebook, appendLoreBook)
                        }

                        trace(TraceEventType.PIPE_SUCCESS, TracePhase.CLEANUP, finalResult,
                              metadata = mapOf("outputText" to if(isExecutingAsReasoningPipe) "" else finalResult.text))
                        return@coroutineScope embedContentIntoInternalConverse(finalResult).takeIf { wrapContentWithConverseHistory } ?: finalResult
                    }

                    else
                    {
                        trace(TraceEventType.VALIDATION_FAILURE, TracePhase.VALIDATION, generatedContent,
                              metadata = mapOf(
                                  "validationResult" to "false",
                                  "inputText" to generatedContent.text,
                                  "reason" to "Validation function returned false"
                              ))
                    }
                }

                else
                {
                    // No validation function, continue to transformation
                    if(transformationPipe != null)
                    {
                        trace(TraceEventType.BRANCH_PIPE_TRIGGERED, TracePhase.TRANSFORMATION)
                        try {
                            if(tracingEnabled)
                            {
                                transformationPipe!!.propagateTracingRecursively()
                            }

                            val transformPipeResult : Deferred<MultimodalContent> = async {
                                transformationPipe?.execute(generatedContent) ?: generatedContent
                            }

                            val metadataBackup = generatedContent.metadata //Required to preserve prior to llm.
                            generatedContent = transformPipeResult.await()
                            generatedContent.currentPipe = inputContent.currentPipe //Prevent nullptr leakage.
                            generatedContent.metadata = metadataBackup
                            //Track transformation pipe token usage if comprehensive tracking is enabled.
                            if(comprehensiveTokenTracking)
                            {
                                transformationPipe?.let { pipe ->
                                    //Add the transformation pipe's token usage to our child pipe tracking.
                                    pipeTokenUsage.addChildUsage("transformation-${pipe.pipeName}", pipe.getTokenUsage())
                                }
                            }

                        }
                        catch(e: Exception)
                        {
                            trace(TraceEventType.PIPE_FAILURE, TracePhase.TRANSFORMATION, generatedContent, error = e)
                            // Continue with original content if transformation pipe fails
                        }
                    }

                    val finalResult = if(transformationFunction != null)
                    {
                        trace(TraceEventType.TRANSFORMATION_START, TracePhase.TRANSFORMATION, generatedContent,
                              metadata = mapOf("inputText" to generatedContent.text))
                        val transformed = transformationFunction?.invoke(generatedContent) ?: generatedContent
                        trace(TraceEventType.TRANSFORMATION_SUCCESS, TracePhase.TRANSFORMATION, transformed,
                              metadata = mapOf("outputText" to transformed.text))
                        transformed
                    } else generatedContent

                    if(!finalResult.context.isEmpty())
                    {
                        contextWindow = finalResult.context
                    }

                    if(!finalResult.miniBankContext.isEmpty())
                    {
                        miniContextBank = finalResult.miniBankContext
                    }

                    if(updatePipelineContextOnExit)
                    {
                        pipelineRef?.context?.merge(contextWindow, emplaceLorebook, appendLoreBook)
                    }

                    trace(TraceEventType.PIPE_SUCCESS, TracePhase.CLEANUP, finalResult,
                          metadata = mapOf("outputText" to if(isExecutingAsReasoningPipe) "" else finalResult.text))
                    return@coroutineScope embedContentIntoInternalConverse(finalResult).takeIf { wrapContentWithConverseHistory } ?: finalResult
                }
            }
            else
            {
                // Validator pipe requested termination
                trace(TraceEventType.VALIDATION_FAILURE, TracePhase.VALIDATION, validatorPipeContent,
                      metadata = mapOf(
                          "reason" to "Validator pipe returned content with terminate flag",
                          "validatorPipeOutput" to validatorPipeContent.text
                      ))
            }

            //Execute branch pipe if provided.
            if(branchPipe != null)
            {
                    trace(TraceEventType.BRANCH_PIPE_TRIGGERED, TracePhase.POST_PROCESSING)
                    try {
                        // Initialize and setup branch pipe
                        if(tracingEnabled)
                        {
                            branchPipe!!.propagateTracingRecursively()
                        }

                        val branchPipeResult : Deferred<MultimodalContent> = async {
                            branchPipe?.execute(generatedContent) ?: generatedContent
                        }
                        var branchResult = branchPipeResult.await()
                        
                        //Track branch pipe token usage if comprehensive tracking is enabled.
                        if(comprehensiveTokenTracking)
                        {
                            branchPipe?.let { pipe ->
                                //Add the branch pipe's token usage to our child pipe tracking.
                                pipeTokenUsage.addChildUsage("branch-${pipe.pipeName}", pipe.getTokenUsage())
                            }
                        }
                        
                        branchResult.currentPipe = inputContent.currentPipe
                        branchResult.metadata = generatedContent.metadata
                        
                        //If branch pipe allows continuation, continue pipeline.
                        if(!branchResult.shouldTerminate())
                        {
                            //Apply transformation pipe if set.
                            if(transformationPipe != null)
                            {
                                trace(TraceEventType.BRANCH_PIPE_TRIGGERED, TracePhase.TRANSFORMATION)
                                try {
                                    if(tracingEnabled)
                                    {
                                        transformationPipe!!.propagateTracingRecursively()
                                    }

                                    val transformPipeResult : Deferred<MultimodalContent> = async {
                                        transformationPipe?.execute(branchResult) ?: branchResult
                                    }

                                    val metadataBackup = branchResult.metadata
                                    branchResult = transformPipeResult.await()
                                    branchResult.currentPipe = inputContent.currentPipe
                                    branchResult.metadata = metadataBackup
                                    
                                    //Track transformation pipe token usage if comprehensive tracking is enabled.
                                    if(comprehensiveTokenTracking)
                                    {
                                        transformationPipe?.let { pipe ->
                                            pipeTokenUsage.addChildUsage("transformation-${pipe.pipeName}", pipe.getTokenUsage())
                                        }
                                    }
                                }
                                catch(e: Exception)
                                {
                                    trace(TraceEventType.PIPE_FAILURE, TracePhase.TRANSFORMATION, branchResult, error = e)
                                    // Continue with branch result if transformation pipe fails
                                }
                            }
                            
                            //Apply transformation function if set.
                            if(transformationFunction != null)
                            {
                                trace(TraceEventType.TRANSFORMATION_START, TracePhase.TRANSFORMATION, branchResult,
                                      metadata = mapOf("inputText" to branchResult.text))
                                branchResult = transformationFunction?.invoke(branchResult) ?: branchResult
                                trace(TraceEventType.TRANSFORMATION_SUCCESS, TracePhase.TRANSFORMATION, branchResult,
                                      metadata = mapOf("outputText" to branchResult.text))
                            }
                            
                            //Merge in context window changes if enabled.
                            if(updatePipelineContextOnExit)
                            {
                                pipelineRef?.context?.merge(contextWindow, emplaceLorebook, appendLoreBook)
                                pipelineRef?.miniBank?.merge(miniContextBank, emplaceLorebook, appendLoreBook)
                            }

                            trace(TraceEventType.PIPE_SUCCESS, TracePhase.CLEANUP, branchResult,
                                  metadata = mapOf("outputText" to branchResult.text))
                            
                            if(!branchResult.repeatPipe)
                            {
                                PipeTimeoutManager.clearRetryCount(this@Pipe)
                            }

                            return@coroutineScope embedContentIntoInternalConverse(branchResult).takeIf { wrapContentWithConverseHistory } ?: branchResult
                        }
                        
                        generatedContent = branchResult
                    }
                    catch(e: Exception)
                    {
                        trace(TraceEventType.PIPE_FAILURE, TracePhase.POST_PROCESSING, generatedContent, error = e)
                        // Branch pipe failed, continue to failure function
                    }
                }

                //Invoke failure function if provided.
                if(onFailure != null)
                {
                    trace(TraceEventType.VALIDATION_FAILURE, TracePhase.POST_PROCESSING,
                          metadata = mapOf("originalText" to processedContent.text, "failedText" to generatedContent.text))
                    val branchResult: Deferred<MultimodalContent> = async {
                        onFailure?.invoke(processedContent, generatedContent) ?: generatedContent
                    }

                    var failureResult = branchResult.await()
                    failureResult.currentPipe = inputContent.currentPipe
                    failureResult.metadata = generatedContent.metadata
                    
                    //If failure function allows continuation, continue pipeline.
                    if(!failureResult.shouldTerminate())
                    {
                        //Merge in context window changes if enabled.
                        if(updatePipelineContextOnExit)
                        {
                            pipelineRef?.context?.merge(contextWindow, emplaceLorebook, appendLoreBook)
                        }

                        //Invoke transformation pipe if failure pipe was valid if able.
                        if(transformationPipe != null)
                        {
                            val transformResult: Deferred<MultimodalContent> = async {
                              transformationPipe?.executeMultimodal(failureResult) ?: failureResult
                            }

                            failureResult = transformResult.await()
                            failureResult.currentPipe = inputContent.currentPipe //Prevent nullptr leakage.
                        }


                        if(transformationFunction != null)
                        {
                            failureResult = transformationFunction?.invoke(failureResult) ?: failureResult
                        }

                        //todo: Finish this trace output.
                        trace(TraceEventType.PIPE_SUCCESS, TracePhase.TRANSFORMATION,
                            metadata = mapOf("output" to "${failureResult.text}"))

                        if(!failureResult.repeatPipe)
                        {
                            PipeTimeoutManager.clearRetryCount(this@Pipe)
                        }

                        return@coroutineScope embedContentIntoInternalConverse(failureResult).takeIf { wrapContentWithConverseHistory } ?: failureResult
                    }
                }

            /**
             * Pipeline termination - return terminated content to signal pipeline failure.
             */
            trace(TraceEventType.PIPE_FAILURE, TracePhase.CLEANUP, inputContent)
            val failedContent = MultimodalContent()
            failedContent.pipeError = lastError
            return@coroutineScope failedContent
            
        }
        catch(e: Exception)
        {
            trace(TraceEventType.PIPE_FAILURE, TracePhase.CLEANUP, inputContent, error = e)
            val failedContent = MultimodalContent("")
            failedContent.pipeError = lastError
            return@coroutineScope failedContent
        } finally {
            PipeTimeoutManager.stopTracking(this@Pipe)
            activeJob = null
        }
    }


    /**
     * Executes TPipe model reasoning step by step. Reasoning is broken down into rounds in which the user can
     * define focus points, and the reasoning pipe will reason over the problem until all rounds are cleared.
     * Afterward, we'll truncate to whatever the allowed budget is, or just be unlimited otherwise. Max tokens
     * will be defined based on token budget settings divided by the number of rounds.
     *
     * @param content Content object passed forward during the execution step. This should only ever be invoked
     * during executeMultimodal so it's largely safe to assume that it's the working content object at that given
     * time.
     */
    private suspend fun executeReasoningPipe(content: MultimodalContent) : MultimodalContent
    {
        /**
         * Copy the entire object safely first. The reasoning pipe is likely going to stomp over whatever is in the
         * content object, and we want to be able to sandbox that.
         */
        val contentCopy = content.deepCopy()

        /**
         * If the reasoning method is SemanticDecompression, automatically inject the legendMap from the
         * content metadata into the reasoning pipe's pipeMetadata. The legendMap is placed there by
         * truncateToFitTokenBudget when semantic compression fires, so the reasoning pipe can use it
         * for deterministic code-to-phrase expansion before the LLM even sees the text.
         */
        val reasoningMethod = reasoningPipe?.pipeMetadata?.get("reasoningMethod") as? String ?: ""
        if(reasoningMethod == "SemanticDecompression")
        {
            val legendMap = content.metadata["semanticCompressionLegendMap"]
            if(legendMap != null)
            {
                reasoningPipe?.pipeMetadata?.set("legendMap", legendMap)
            }
        }

        val reasoningBudget = tokenBudgetSettings?.reasoningBudget ?: 0 //Declare our budget. 0 is unlimited.
        var budgetPerRound = 0 //Divided by reasoningBudget / number of reasoning rounds.
        var rounds = reasoningPipe?.pipeMetadata?.get("reasoningRounds") as? Int
        if(rounds == null)
        {
            rounds = pipeMetadata["reasoningRounds"] as? Int
        }
        if(rounds == null) rounds = 1 //Define to address behavior of Any to Any maps in kotlin. We can't be less than 1.

        //Define budget if applicable. Otherwise, set to 0 and treat as unlimited.
        if(reasoningBudget > 0)
        {
            /**
             * Currently each round gets an equal share of the budget. We don't have plans to change this at this time
             * maybe we'll allow more customization later but... it adds more complexity to the coder and the there's
             * already too many data classes needed to configure reasoning as it is. Three is enough. We don't
             * need a fourth....
             */
            budgetPerRound = reasoningBudget / rounds

            //Define the maximum per round but allow overflow which is better than a complete failure.
            reasoningPipe?.setMaxTokens(budgetPerRound)
                ?.enableMaxTokenOverflow()
        }

        /**
         * If rounds are greater than one there's a 100% chance we're using converse. Likewise, we also need
         * to test the schema for being set to converse just in case. It's entirely possible the user has assigned
         * the schema to converse directly.
         */
        val converseSchema = content.text

        /**
         * Try to get the ref for this. Because it's in schema form we should in theory be able to get this as
         * a non-null object.
         */
        val converseSchemaRef = deserialize<ConverseHistory>(converseSchema)

        val reasoningStream = StringBuilder(contentCopy.modelReasoning)
        val roundDirectives = resolveReasoningRoundDirectives()
        val usingDirectiveRounds = roundDirectives.isNotEmpty()
        val decorateReasoningRounds = rounds > 1
        var usingConverse = !usingDirectiveRounds && (converseSchemaRef?.isEmpty() != true ||  rounds > 1)
        val originalUserPrompt = extractOriginalReasoningPrompt(converseSchemaRef, content)


        if(usingConverse)
        {
            /**
             * System prompt must be copied from this pipe to the user prompt we're passing to our target reasoning
             * pipe.
             */
            val systemConverseData = ConverseData(
                ConverseRole.developer,
                MultimodalContent(
                    "$rawSystemPrompt ${getMiddlePromptForReasoning()} ${getFooterPromptForReasoning()}"))

            val newHistory = ConverseHistory()
            newHistory.add(systemConverseData)

            //Jokes on me for not commenting why we're doing this....
            if(converseSchemaRef != null && !converseSchemaRef.isEmpty())
            {
                // We have an existing ConverseHistory, append its items
                for (item in converseSchemaRef.history)
                {
                    newHistory.add(item)
                }
            }

            else
            {
                //Now we can add the user's original prompt.
                val converseData = ConverseData(ConverseRole.user, content)

                //Now we'll have each part we need for it to reason correctly.
                newHistory.add(converseData)
            }

            /**
             * Re-apply the system prompt of the reasoning pipe at the very bottom of the user prompt. This helps
             * to reinforce under very large context situations. Million token model can forget the context and rules
             * of their own system when overwhelemed by massive and complex context. So adding it a second time should
             * help boost output quality.
             */
            if(reasoningPipe?.pipeMetadata["reinforceSystemPrompt"] is Boolean)
            {
                val reinforceSystemPrompt = reasoningPipe?.pipeMetadata["reinforceSystemPrompt"] as Boolean

                if(reinforceSystemPrompt)
                {
                    val reasoningPipeSystemPrompt = reasoningPipe?.systemPrompt ?: ""
                    newHistory.add(ConverseData(ConverseRole.system, MultimodalContent(reasoningPipeSystemPrompt)))
                }
            }



            //Force back to the string so we can push this into our copied content object.
            val historyAsJson = serialize(newHistory, encodedefault = false)
            contentCopy.text = historyAsJson
        }

        /**
         * Mode-driven multi-round reasoning bypasses the converse-history transport layer entirely. Each round will
         * build its own prompt envelope so blind rounds stay isolated and merge rounds can synthesize the flattened
         * reasoning stream from earlier rounds.
         */
        else if(usingDirectiveRounds)
        {
            contentCopy.text = originalUserPrompt
        }

        /**
         * Directly combine the system prompt to the user prompt in this case as a "developer prompt". We need to
         * do this because the reasoning pipe actually doesn't know the full rules of its own request otherwise.
         */
        else
        {
            var combinedPrompt = """##DEVELOPER PROMPT##
                |$rawSystemPrompt ${getMiddlePromptForReasoning()} ${getFooterPromptForReasoning()}
                |
                |##USER PROMPT##
                |${content.text}
            """.trimMargin()

            //Apply the system prompt of the reasoning pipe to the user prompt to reinforce it's output in long context.
            if(reasoningPipe?.pipeMetadata["reinforceSystemPrompt"] is Boolean)
            {
                val reinforceSystemPrompt = reasoningPipe?.pipeMetadata["reinforceSystemPrompt"] as Boolean
                if(reinforceSystemPrompt)
                {
                    combinedPrompt = "$combinedPrompt\n\n##SYSTEM PROMPT##\n${reasoningPipe?.systemPrompt}"
                }
            }

            contentCopy.text = combinedPrompt
        }

        /**
         * Propagate tracing if we've enabled it in this pipe. Normally this happens at the execute stage of this
         * pipe. However, that area of the code is quite busy, and TPipe reasoning is extremely non-trivial in of itself.
         * So we'll just enable it here even if it breaks conventions a bit.
         */
        if(tracingEnabled)
        {
            propagateTracingRecursively()
        }

        /**
         * Iterate through every single round, and force the pipe to reason until every round is cleared.
         * Any overflow will be truncated against the budget if the budget is not 0. Otherwise, it can reason
         * until the end of time if it wishes.
         */
        for(round in 1..rounds)
        {
            /**
             * Resolve the focus target and round directive for this specific round. New mode-driven runs use the
             * directive map, while older focus-point-only callers keep the legacy behavior.
             */
            val roundDirective = if(usingDirectiveRounds) roundDirectives[round] else null
            if(usingDirectiveRounds && roundDirective == null)
            {
                throw Exception("Round $round is missing a round directive while blind/merge reasoning is enabled.")
            }

            val focusTarget = roundDirective?.focusPoint ?: resolveReasoningFocusTarget(round)

            /**
             * Any rounds past 1 will require us to keep appending the converse history as we go along.
             * However, for the very first round we can assume it's already setup for the first user request
             * so we'll need to be prepared to skip over that.
             */
            val firstRound = round == 1

            /**
             * Legacy converse-history rounds still use the old focus-point append behavior. The mode-driven path
             * handles focus directly in the round prompt envelope, so it intentionally skips this injection branch.
             */
            if(focusTarget.isNotEmpty() && !usingDirectiveRounds && decorateReasoningRounds)
            {
                val focusMessage = """Please pay special attention to, and focus your time on: $focusTarget"""

                /**
                 * We need to append to existing converse history point if this is our first round.
                 * In that event we'll need to re-serialize the data class.
                 */
                if(firstRound && usingConverse)
                {
                    //Extract the history from the json again.
                    val history = deserialize<ConverseHistory>(contentCopy.text)
                    var dataTarget = history?.history?.last() ?: throw Exception("" +
                            "Converse history cannot be empty if we're assigned to converse, and also setting" +
                            " a focus point.")

                    //Emplace back the addendum on focusing on a specific aspect.
                    dataTarget.content.text = dataTarget.content.text + " ${focusMessage}"
                    history.history.last().content = dataTarget.content
                    val newJson = serializeConverseHistory(history)

                    //Emplace back the text to the content object.
                    contentCopy.text = newJson
                }

                /**
                 * We're at the second round or beyond. So if we're using converse we need to now instead add
                 * to the struct instead of just emplacing back. Otherwise, we need to just append forward to
                 * our user prompt.
                 */
                else
                {
                    if(usingConverse)
                    {
                        val extractedHistory = deserialize<ConverseHistory>(contentCopy.text) ?: throw Exception("" +
                                "Converse history cannot be empty if we're assigned to converse, and also setting" +
                                " a focus point.")

                        val tempContentObj = MultimodalContent(focusMessage)
                        val newData = ConverseData(ConverseRole.system, tempContentObj)
                        extractedHistory.add(newData)
                        val newJson = serializeConverseHistory(extractedHistory)
                        contentCopy.text = newJson
                    }

                    //Not using converse. Just append and emplace back.
                    else
                    {
                        contentCopy.text = "${contentCopy.text} $focusMessage"
                    }
                }
            }

            /**
             * Finally, execute the reasoning pipe and fetch the result. If it has a result we'll push it back
             * into the reasoning block of the copied content. The loop will continue until we've cleared every
             * step of the reasoning process.
             */
            val roundInputContent = if(usingDirectiveRounds)
            {
                val mode = roundDirective!!.mode
                buildDirectiveReasoningRoundContent(
                    originalContent = content,
                    round = round,
                    focusTarget = focusTarget,
                    mode = mode,
                    accumulatedReasoning = reasoningStream.toString(),
                    originalUserPrompt = originalUserPrompt
                )
            }
            else
            {
                contentCopy
            }

            val result = reasoningPipe?.let { pipe ->
                //We have to propagate again to prevent any gaps where tracing can just "fall through".
                if(tracingEnabled)
                {
                    pipe.propagateTracingRecursively()
                }

                pipe.isExecutingAsReasoningPipe = true
                pipe.reasoningContentAlreadyTraced = false
                val pipeResult = pipe.executeMultimodal(roundInputContent)
                pipe.isExecutingAsReasoningPipe = false
                pipeResult
            } ?: content

            if(usingDirectiveRounds)
            {
                val roundMode = roundDirective!!.mode
                val normalizedRoundText = extractReasoningStream(reasoningMethod, result)
                val roundStreamBlock = if(decorateReasoningRounds)
                {
                    formatReasoningRoundBlock(round, focusTarget, normalizedRoundText, roundMode.name)
                }
                else
                {
                    normalizedRoundText
                }

                if(reasoningStream.isNotEmpty())
                {
                    reasoningStream.append("\n\n")
                }
                reasoningStream.append(roundStreamBlock)
                contentCopy.modelReasoning = reasoningStream.toString()
                contentCopy.text = roundInputContent.text

                // Emit the post-append state so traces show the carried-forward reasoning stream for this round.
                trace(
                    TraceEventType.API_CALL_SUCCESS,
                    TracePhase.VALIDATION,
                    contentCopy,
                    metadata = mapOf(
                        "reasoningRound" to round,
                        "reasoningMode" to roundMode.name,
                        "focusTarget" to focusTarget
                    )
                )
            }
            else if(usingConverse)
            {
                val updatedHistory = deserialize<ConverseHistory>(contentCopy.text)
                    ?: throw Exception("Converse history cannot be empty when multi-round reasoning is using converse mode.")

                val normalizedRoundText = extractReasoningStream(reasoningMethod, result)
                val roundStreamBlock = if(decorateReasoningRounds)
                {
                    formatReasoningRoundBlock(round, focusTarget, normalizedRoundText)
                }
                else
                {
                    normalizedRoundText
                }

                updatedHistory.add(
                    ConverseData(
                        ConverseRole.agent,
                        MultimodalContent(text = roundStreamBlock)
                    )
                )
                val updatedHistoryJson = serializeConverseHistory(updatedHistory)
                contentCopy.text = updatedHistoryJson

                if(reasoningStream.isNotEmpty())
                {
                    reasoningStream.append("\n\n")
                }
                reasoningStream.append(roundStreamBlock)
                contentCopy.modelReasoning = reasoningStream.toString()

                // Emit the post-append state so traces show the carried-forward reasoning stream for this round.
                trace(
                    TraceEventType.API_CALL_SUCCESS,
                    TracePhase.VALIDATION,
                    contentCopy,
                    metadata = mapOf(
                        "reasoningRound" to round,
                        "reasoningMode" to "converse-history",
                        "focusTarget" to focusTarget
                    )
                )
            }
            else
            {
                result.text = extractReasoningContent(reasoningPipe?.pipeMetadata["reasoningMethod"] as? String ?: "", result)
                contentCopy.modelReasoning += " ${result.text}"
            }
        }

        if(reasoningBudget > 0)
        {
            //Required boilerplate to truncate the reasoning data if it has overflowed the budget.
            val newContextWindow = ContextWindow()
            newContextWindow.contextElements.add(contentCopy.modelReasoning)
            val truncationSettings = reasoningPipe?.getTruncationSettings()  ?: TruncationSettings()

            /**
             * Unlike typical truncation setups, we need to chop the bottom instead of the top.
             * Having earlier context is better than having that be missing in the case of model
             * reasoning.
             */
            newContextWindow.selectAndTruncateContext(
                "",
                reasoningBudget,
                ContextWindowSettings.TruncateBottom,
                truncationSettings)

            //Copy back now that we've reduced it to fit our budget.
           contentCopy.modelReasoning = newContextWindow.contextElements.first()
        }

        return contentCopy
    }

    /**
     * Format one multi-round reasoning block so the internal history and the parent-facing stream use the same
     * round boundary. The focus label is kept in plain text so later rounds and the parent pipe can read it.
     */
    private fun formatReasoningRoundBlock(
        round: Int,
        focusTarget: String,
        reasoningText: String,
        modeLabel: String = ""
    ): String
    {
        return buildString {
            append("ROUND $round")
            if(modeLabel.isNotBlank())
            {
                append(" [${modeLabel.uppercase()}]")
            }
            if(focusTarget.isNotBlank())
            {
                append("\nFOCUS: $focusTarget")
            }
            append("\n\n")
            append(reasoningText.trim())
        }
    }

    /**
     * Resolves the round directive map used by the new blind/merge multi-round path.
     *
     * The reasoning builder stores the canonical map in pipe metadata so the runtime can decide, round by round,
     * whether to isolate the prompt or merge prior thought blocks back in.
     */
    private fun resolveReasoningRoundDirectives(): Map<Int, ReasoningRoundDirective>
    {
        val directiveMetadata = reasoningPipe?.pipeMetadata?.get("roundDirectives") ?: pipeMetadata["roundDirectives"]

        return when(directiveMetadata)
        {
            is Map<*, *> -> {
                directiveMetadata.entries.mapNotNull { entry ->
                    val round = when(val key = entry.key)
                    {
                        is Int -> key
                        is String -> key.toIntOrNull()
                        else -> null
                    } ?: return@mapNotNull null

                    val directive = entry.value as? ReasoningRoundDirective ?: return@mapNotNull null
                    round to directive
                }.toMap()
            }

            else -> emptyMap()
        }
    }

    /**
     * Extracts the original user prompt from either a plain-text payload or a converse-history payload.
     *
     * Blind rounds should not inherit earlier reasoning, so the runtime pulls only the first user turn when the
     * incoming payload is already wrapped in a converse history.
     */
    private fun extractOriginalReasoningPrompt(
        converseSchemaRef: ConverseHistory?,
        content: MultimodalContent
    ) : String
    {
        if(converseSchemaRef == null)
        {
            return content.text
        }

        return converseSchemaRef.history.firstOrNull { it.role == ConverseRole.user }
            ?.content
            ?.text
            ?: content.text
    }

    /**
     * Build the per-round input envelope used by blind and merge mode.
     *
     * Blind rounds receive only the original prompt and the current focus. Merge rounds receive the flattened stream
     * from earlier rounds so they can synthesize the separate blocks into one conclusion.
     */
    private fun buildDirectiveReasoningRoundContent(
        originalContent: MultimodalContent,
        round: Int,
        focusTarget: String,
        mode: ReasoningRoundMode,
        accumulatedReasoning: String,
        originalUserPrompt: String
    ) : MultimodalContent
    {
        val prompt = when(mode)
        {
            ReasoningRoundMode.Blind -> composeBlindReasoningRoundPrompt(
                round = round,
                originalUserPrompt = originalUserPrompt,
                focusPoint = focusTarget
            )

            ReasoningRoundMode.Merge -> composeMergeReasoningRoundPrompt(
                round = round,
                originalUserPrompt = originalUserPrompt,
                accumulatedReasoning = accumulatedReasoning,
                focusPoint = focusTarget
            )
        }

        return MultimodalContent(
            text = prompt,
            binaryContent = originalContent.binaryContent.deepCopy()
        )
    }

    /**
     * Resolves the focus target for one reasoning round.
     *
     * Focus metadata is expected to be a map keyed by round number. Some older call sites may still store a single
     * string, so we preserve that as a round-1 fallback instead of dropping the focus instruction entirely.
     */
    private fun resolveReasoningFocusTarget(round: Int): String
    {
        val focusMetadata = reasoningPipe?.pipeMetadata?.get("focusPoints") ?: pipeMetadata["focusPoints"]

        return when(focusMetadata)
        {
            is Map<*, *> -> {
                focusMetadata[round]?.toString()
                    ?: focusMetadata[round.toString()]?.toString()
                    ?: ""
            }

            is String -> if(round == 1) focusMetadata else ""

            else -> ""
        }
    }


    /**
     * Second step of addressing TPipe reasoning support. We now need to inject the result of reasoning into the user
     * prompt or system prompt depending on the setting dispatched in order get it to affect the actual llm prediction.
     *
     * @param content Content object passed forward during the execution step. This should only ever be invoked
     * during executeMultimodal so it's largely safe to assume that it's the working content object at that given
     * time.
     */
    private fun injectTPipeReasoning(content: MultimodalContent)
    {
        val reasoningOutput = content.modelReasoning

        //Circular reference issue here. We need to get this to string form or something.
        val reasoningMethod = reasoningPipe?.pipeMetadata["injectionMethod"] as? String ?: ""

        when(reasoningMethod)
        {
            "SystemPrompt" -> {
                val injectionMessage = """
                    |
                    |CONTINUE FROM PREVIOUS THINKING:
                    |Your prior reasoning is provided below. Extend and build upon this analysis:
                """.trimMargin()

                /**
                 * Directly modify the system prompt without the setter. This avoids saving the original system prompt
                 * so when we invoke apply system prompt at a later stage it won't result in this getting stuck there
                 * and poisoning the system prompt.
                 */
                systemPrompt += "$injectionMessage $reasoningOutput"
            }

            "BeforeUserPrompt" -> {
                val injectionMessage = """
                    |
                    |CONTINUE FROM PREVIOUS THINKING:
                    |Your prior reasoning is provided below. Extend and build upon this analysis:
                """.trimMargin()

                val secondaryInjectionMessage = """USER PROMPT:"""

                // Combine the reasoning output first, then the user prompt marker, then the original user prompt.
                content.text = "$injectionMessage $reasoningOutput $secondaryInjectionMessage ${content.text}"
            }

            "BeforeUserPromptWithConverse" -> {
                val injectionMessage = """CONTINUE FROM PREVIOUS THINKING:
                    |Your prior reasoning is provided below. Extend and build upon this analysis:
                """.trimMargin()

                val jsonObjectsFound = extractAllJsonObjects(content.text)
                var foundConverse = false

                for(jsonObject in jsonObjectsFound)
                {
                    val asJsonString = serialize(jsonObject)
                    val converseHistory = deserialize<ConverseHistory>(jsonObject.toString())
                    foundConverse = converseHistory != null

                    if(foundConverse)
                    {
                        val newConverseEntry = ConverseData(
                            ConverseRole.system,
                            MultimodalContent("$injectionMessage ${content.modelReasoning}"))

                        //Insert at 0. For some reason kotlin doesn't have an insert function and overloads add().
                        converseHistory?.history?.add(0, newConverseEntry)

                        /**
                         *  We need to be very careful and only replace the converse history and not any context or other
                         *  present context.
                         */
                        if(converseHistory != null)
                        {
                            val newConverseJson = serializeConverseHistory(converseHistory)
                            content.text = newConverseJson
                            break
                        }
                    }


                }
            }

            "AfterUserPrompt"  -> {
                val injectionMessage = """
                    |
                    |CONTINUE FROM PREVIOUS THINKING:
                    |Your prior reasoning is provided below. Extend and build upon this analysis:
                """.trimMargin()

                content.text += "$injectionMessage ${content.modelReasoning}"
            }

            "AfterUserPromptWithConverse" -> {
                val injectionMessage = """CONTINUE FROM PREVIOUS THINKING:
                    |Your prior reasoning is provided below. Extend and build upon this analysis:
                """.trimMargin()

                val jsonObjectsFound = extractAllJsonObjects(content.text)
                var foundConverse = false

                for(jsonObject in jsonObjectsFound)
                {
                    val asJsonString = serialize(jsonObject)
                    val converseHistory = deserialize<ConverseHistory>(jsonObject.toString())
                    foundConverse = converseHistory != null

                    if(foundConverse)
                    {
                        val newConverseEntry = ConverseData(
                            ConverseRole.system,
                            MultimodalContent("$injectionMessage ${content.modelReasoning}"))

                        //Insert at 0. For some reason kotlin doesn't have an insert function and overloads add().
                        converseHistory?.history?.add(newConverseEntry)

                        /**
                         *  We need to be very careful and only replace the converse history and not any context or other
                         *  present context.
                         */
                        if(converseHistory != null)
                        {
                            val newConverseJson = serializeConverseHistory(converseHistory)
                            content.text = newConverseJson
                            break
                        }
                    }


                }
            }

            /**
             * Apply as reasoning. The user will need to explain how the key works and what the content of the
             * context window should be.
             */
            "AsContext" -> {
                val newContextWindow = ContextWindow()
                newContextWindow.addLoreBookEntry("reasoning", content.text)

                if(!contextWindow.isEmpty()) contextWindow.merge(newContextWindow, emplaceLorebook, appendLoreBook)
                if(!miniContextBank.isEmpty()) miniContextBank.contextMap["reasoning"] = newContextWindow
            }
        }
    }

    /**
     * Count the number of tokens in the input or output of the pipe. Useful for tracking and estimating costs.
     * TPipes token counter is on average around 1K less than the actual value so this should be kept in mind
     * when using this to estimate costs.
     */
    fun countTokens(input: Boolean, content: MultimodalContent) : Int
    {
        if(input)
        {
            val inputText = systemPrompt + userPrompt + content.text
            val truncationSettings = getTruncationSettings()
            val result = Dictionary.countTokens(inputText, truncationSettings)
            pipelineRef?.inputTokensSpent = result
            return result

        }

        val outputText = content.text + content.modelReasoning
        val truncationSettings = getTruncationSettings()
        val result = Dictionary.countTokens(outputText, truncationSettings)
        pipelineRef?.outputTokensSpent = result
        return result
    }

    /**
     * Counts the actual tokens consumed by this pipe after all truncation and budgeting is completed.
     * This method provides accurate post-processing token counts that reflect the actual content
     * sent to the AI model, including system prompts, user content, context windows, and binary content.
     * 
     * @param content The MultimodalContent object containing the processed input data
     * @return The total number of input tokens actually consumed by this pipe
     */
    private fun countActualInputTokens(content: MultimodalContent): Int
    {
        //Get the truncation settings for accurate token counting.
        val truncationSettings = getTruncationSettings()
        var totalTokens = 0

        //Count tokens from the system prompt.
        totalTokens += Dictionary.countTokens(systemPrompt, truncationSettings)
        
        //Count tokens from the main user content text.
        totalTokens += Dictionary.countTokens(content.text, truncationSettings)

        //Count context tokens based on whether we're using minibank or regular context.
        if(miniContextBank.isEmpty())
        {
            //Use regular context window for token counting.
            val contextJson = serialize(contextWindow)
            totalTokens += Dictionary.countTokens(contextJson, truncationSettings)
        }
        
        else
        {
            //Use minibank context for token counting.
            val miniBankJson = serialize(miniContextBank)
            totalTokens += Dictionary.countTokens(miniBankJson, truncationSettings)
        }

        //Count tokens from any binary content (images, files, etc.).
        totalTokens += countBinaryTokens(content, truncationSettings)
        
        //Count tokens from model reasoning content.
        totalTokens += Dictionary.countTokens(content.modelReasoning, truncationSettings)

        //Update pipeline reference with input token count for backward compatibility.
        pipelineRef?.inputTokensSpent = totalTokens
        
        return totalTokens
    }
    
    /**
     * Constructs a PipeSettings object populated with all current pipe configuration values.
     * @return PipeSettings object containing all current pipe settings
     */
    fun toPipeSettings(): PipeSettings
    {
        return PipeSettings(
            pipeName = pipeName,
            provider = provider,
            model = model,
            promptMode = promptMode,
            systemPrompt = systemPrompt,
            userPrompt = userPrompt,
            multimodalInput = multimodalInput,
            jsonInput = jsonInput,
            jsonOutput = jsonOutput,
            pcpContext = pcpContext,
            supportsNativeJson = supportsNativeJson,
            temperature = temperature,
            topP = topP,
            topK = topK,
            maxTokens = maxTokens,
            contextWindowSize = contextWindowSize,
            contextWindow = contextWindow,
            miniContextBank = miniContextBank,
            readFromGlobalContext = readFromGlobalContext,
            readFromPipelineContext = readFromPipelineContext,
            updatePipelineContextOnExit = updatePipelineContextOnExit,
            autoInjectContext = autoInjectContext,
            autoTruncateContext = autoTruncateContext,
            emplaceLorebook = emplaceLorebook,
            appendLoreBook = appendLoreBook,
            loreBookFillMode = loreBookFillMode,
            loreBookFillAndSplitMode = loreBookFillAndSplitMode,
            useModelReasoning = useModelReasoning,
            modelReasoningSettingsV2 = modelReasoningSettingsV2,
            modelReasoningSettingsV3 = modelReasoningSettingsV3,
            pageKey = pageKey,
            pageKeyList = pageKeyList,
            contextWindowTruncation = contextWindowTruncation,
            truncateContextAsString = truncateContextAsString,
            repetitionPenalty = repetitionPenalty,
            stopSequences = stopSequences,
            multiplyWindowSizeBy = multiplyWindowSizeBy,
            countSubWordsInFirstWord = countSubWordsInFirstWord,
            favorWholeWords = favorWholeWords,
            countOnlyFirstWordFound = countOnlyFirstWordFound,
            splitForNonWordChar = splitForNonWordChar,
            alwaysSplitIfWholeWordExists = alwaysSplitIfWholeWordExists,
            countSubWordsIfSplit = countSubWordsIfSplit,
            nonWordSplitCount = nonWordSplitCount,
            tracingEnabled = tracingEnabled,
            pipeId = pipeId,
            currentPipelineId = currentPipelineId,
            tokenBudgetSettings = tokenBudgetSettings
        )
    }

    /**
     * Returns comprehensive usage data for this pipe and its children.
     * This method provides access to detailed token usage information when comprehensive
     * tracking is enabled, or returns an empty TokenUsage object when tracking is disabled.
     *
     * @return TokenUsage object containing comprehensive usage data, or empty object if tracking disabled
     */
    fun getTokenUsage(): TokenUsage = if(comprehensiveTokenTracking) pipeTokenUsage else TokenUsage()

    /**
     * Returns total input tokens consumed by this pipe and nested pipes when tracking is enabled.
     * This includes input tokens from this pipe plus the recursive totals from all child pipes
     * when comprehensive tracking is active.
     *
     * @return Total input token count across this pipe and all nested pipes, or 0 if tracking disabled
     */
    fun getTotalInputTokens(): Int = if(comprehensiveTokenTracking) pipeTokenUsage.totalInputTokens else 0

    /**
     * Returns total output tokens consumed by this pipe and nested pipes when tracking is enabled.
     * This includes output tokens from this pipe plus the recursive totals from all child pipes
     * when comprehensive tracking is active.
     *
     * @return Total output token count across this pipe and all nested pipes, or 0 if tracking disabled
     */
    fun getTotalOutputTokens(): Int = if(comprehensiveTokenTracking) pipeTokenUsage.totalOutputTokens else 0

    /**
     * Indicates whether comprehensive token tracking is enabled on this pipe.
     * This method allows external code to check if detailed token usage data is being
     * collected and is available through the token usage methods.
     *
     * @return True if comprehensive token tracking is enabled, false otherwise
     */
    fun isComprehensiveTokenTrackingEnabled(): Boolean = comprehensiveTokenTracking

    /**
     * Getter function to retrieve the middle prompt instructions from a pipe if the pipe's reasoning settings
     * were defined. Called on the parent pipe and attempts to poll the reasoning pipe to determine if it has
     * been set to use the middle prompt or not. If true, this parent pipe's middle prompt will be returned.
     * Otherwise, returns an empty string.
     */
    fun getMiddlePromptForReasoning() : String
    {
        if(reasoningPipe == null) return ""
        val usingMiddlePrompt = reasoningPipe?.pipeMetadata["injectMiddlePrompt"] as Boolean
        if(!usingMiddlePrompt) return ""
        return middlePromptInstructions
    }

    /**
     * Get and retrive this parent pipe's footer prompt for a reasoning pipe if it has been set to inject
     * the footer prompt back in. The reasoning pipe of this parent pipe will be checked for, and if it exists
     * we'll return the footer prompt. Otherwise, if it does not, or it does not have the setting applied to
     * its pipe metadata, return an empty string instead.
     */
    fun getFooterPromptForReasoning() : String
    {
        if(reasoningPipe == null) return ""
        val usingFooterPrompt = reasoningPipe?.pipeMetadata["injectFooterPrompt"] as Boolean
        if(!usingFooterPrompt) return ""
        return footerPrompt
    }

//============================================== P2PInterface Implementation ==========================================

    override fun setP2pDescription(description: P2PDescriptor)
    {
        p2pDescriptor = description
    }

    override fun getP2pDescription(): P2PDescriptor? = p2pDescriptor

    override fun setP2pTransport(transport: P2PTransport)
    {
        p2pTransport = transport
    }

    override fun getP2pTransport(): P2PTransport? = p2pTransport

    override fun setP2pRequirements(requirements: P2PRequirements)
    {
        p2pRequirements = requirements
    }

    override fun getP2pRequirements(): P2PRequirements? = p2pRequirements

    override fun getContainerObject(): Any? = containerObject

    override fun setContainerObject(container: Any)
    {
        containerObject = container
    }

    override fun getPipelinesFromInterface(): List<Pipeline> = if(pipelineRef != null) listOf(pipelineRef!!) else emptyList()

    override suspend fun executeP2PRequest(request: P2PRequest): P2PResponse? {
        val response = P2PResponse()
        
        // Execute the pipe with the request prompt
        val content = MultimodalContent()
        content.text = request.prompt.text
        content.binaryContent = request.prompt.binaryContent
        
        val result = execute(content)
        
        response.output?.text = result.text
        response.output?.binaryContent = result.binaryContent
        
        return response
    }

    companion object {
        /**
         * Helper function to convert text tokens to token IDs for logit bias.
         * This is a simplified implementation - production code should use
         * the actual tokenizer for the specific model.
         * 
         * @param tokens List of text tokens to convert
         * @return Map of estimated token IDs to 1.0 bias (encourage these tokens)
         */
        fun createLogitBiasForTokens(tokens: List<String>): Map<Int, Double>
        {
            // This is a placeholder implementation
            // In production, this should use the actual tokenizer
            val biasMap = mutableMapOf<Int, Double>()
            
            tokens.forEachIndexed { index, token ->
                // Simple hash-based token ID estimation (NOT ACCURATE)
                val tokenId = token.hashCode().absoluteValue % 50000
                biasMap[tokenId] = 1.0
            }
            
            return biasMap
        }
        
        /**
         * Helper function to ban specific tokens by setting their bias to -100.
         * 
         * @param tokens List of text tokens to ban
         * @return Map of estimated token IDs to -100.0 bias (ban these tokens)
         */
        fun createTokenBanList(tokens: List<String>): Map<Int, Double>
        {
            return createLogitBiasForTokens(tokens).mapValues { -100.0 }
        }
        
        /**
         * Helper function to encourage specific tokens by setting their bias to positive values.
         * 
         * @param tokens List of text tokens to encourage
         * @param bias Positive bias value (default 1.0)
         * @return Map of estimated token IDs to positive bias values
         */
        fun createTokenEncourageList(tokens: List<String>, bias: Double = 1.0): Map<Int, Double>
        {
            if(bias < 0.0 || bias > 100.0)
            {
                throw IllegalArgumentException("Bias must be between 0.0 and 100.0, got: $bias")
            }
            
            return createLogitBiasForTokens(tokens).mapValues { bias }
        }
    }

}
