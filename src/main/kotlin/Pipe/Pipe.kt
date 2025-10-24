package com.TTT.Pipe

import com.TTT.Context.ContextBank
import com.TTT.Context.ContextWindow
import com.TTT.Context.ConverseData
import com.TTT.Context.ConverseHistory
import com.TTT.Context.ConverseRole
import com.TTT.Context.Dictionary
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
import com.TTT.Structs.extractReasoningContent
import com.TTT.Util.deepCopy
import com.TTT.Util.deserialize
import com.TTT.Util.exampleFor
import com.TTT.Util.extractAllJsonObjects
import com.TTT.Util.extractNonJsonText
import com.TTT.Util.removeFromFirstOccurrence
import com.TTT.Util.serialize
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.Serializable
import java.util.UUID


data class TruncationSettings(
         var multiplyWindowSizeBy: Int = 1000,
         var countSubWordsInFirstWord: Boolean = true,
         var favorWholeWords: Boolean = true,
         var countOnlyFirstWordFound: Boolean = false,
         var splitForNonWordChar: Boolean = true,
         var alwaysSplitIfWholeWordExists: Boolean = false,
         var countSubWordsIfSplit: Boolean = false,
         var nonWordSplitCount: Int = 4)

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
 * @param truncationMethod Defines the method used to truncate the user prompt if it exceeds the context window.
 * If the user prompt is allowed to be truncated, it will be truncated according to the truncation method.
 * If the user prompt is not allowed to be truncated, an error will be thrown.
 *
 * @see ContextWindowSettings
 */
@kotlinx.serialization.Serializable
data class TokenBudgetSettings(
    var userPromptSize: Int? = null, //Assume 12K tokens
    var maxTokens: Int? = null, //Assume 20K tokens
    var reasoningBudget: Int? = null, //Assume 8K tokens
    var contextWindowSize: Int? = null, //Assume 32K tokens total
    var allowUserPromptTruncation: Boolean = false,
    var preserveJsonInUserPrompt: Boolean = true,
    var compressUserPrompt: Boolean = false,
    var truncateContextWindowAsString: Boolean = false,
    var truncationMethod: ContextWindowSettings = ContextWindowSettings.TruncateTop
)

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
 */
@kotlinx.serialization.Serializable
abstract class Pipe : P2PInterface, ProviderInterface {

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

//============================================= properties ===========================================================//

    /**
     * Optional name for this pipe. Useful for debugging and tracing pipes and pipelines.
     */
    @Serializable
    var pipeName = ""
    
    /**
     * Reference to the parent pipeline that this pipe is a part of. Required to communicate with the parent on actions
     * such as failure or success.
     */
    @kotlinx.serialization.Transient
    protected var pipelineRef: Pipeline? = null

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
    protected var temperature : Double = 1.0

    /**
     * TopP settings. Most models support adjusting this value. Lowering this value produces more predictable
     * output. Increasing this value produces more random output. topP measures in probability between 0 to 100%.
     *
     * at .7 70% of possible tokens are viable. At .1 only the top 10% are selectable by an llm.
     */
    @Serializable
    protected var topP : Double = 0.7

    /**
     * TopK settings. Only certain models support this. If unsupported, this setting will do nothing.
     * Number of possible token predictions will be restricted to value of topK. At 1000, the top 1000
     * most likely predictions can be randomly picked. At 10 only the 10 most likley predictions can be picked.
     */
    @Serializable
    protected var topK = 1000

    /**
     * Maximum number of tokens allowed to be generated. Not all AI models support this. For models that do not
     * support this, this setting will do nothing.
     */
    @Serializable
    protected var maxTokens = 8000

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
    protected var contextWindowSize = 10000

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
     * Allows for setting advanced token budgeting that supports accounting for system prompt size,
     * binary content size, user prompt size, reasoning budget, and output size. This is useful for
     * preventing overflow and pipeline crashes due to exceeding max token limits in complex multimodal
     * setups.
     */
    protected var tokenBudgetSettings : TokenBudgetSettings? = null

    /**
     * If true, the pipe will pull its context from the pipeline. This always overrides readFromGlobalContext.
     */
    @Serializable
    protected var readFromPipelineContext = false

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
     * Some models have thinking modes, also known as reasoning. If true TPipe will enable model thinking/reasoning
     * on models where it can be enabled or disabled.
     */
    @Serializable
    protected var useModelReasoning = false

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
     * Optional function to validate the output of the AI model. If the function returns true, the pipeline
     * will continue to the next pipe. If the function returns false, the pipeline will exit here.
     */
    @kotlinx.serialization.Transient
    var validatorFunction: (suspend (content: MultimodalContent) -> Boolean)? = null

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
    protected var multiplyWindowSizeBy: Int = 1000
    protected var countSubWordsInFirstWord: Boolean = true
    protected var favorWholeWords: Boolean = true
    protected var countOnlyFirstWordFound: Boolean = false
    protected var splitForNonWordChar: Boolean = true
    protected var alwaysSplitIfWholeWordExists: Boolean = false
    protected var countSubWordsIfSplit: Boolean = false
    protected var nonWordSplitCount: Int = 4

    /**
     * Tracing system properties for debugging and monitoring pipe execution.
     */
    protected var tracingEnabled = false
    @kotlinx.serialization.Transient
    protected var traceConfig = TraceConfig()
    protected var pipeId = UUID.randomUUID().toString()
    @kotlinx.serialization.Transient
    var currentPipelineId: String? = null

    /**
     * Allows for a complex object or container to be linked to this pipe. If this is not null, when this pipe executes,
     * it will attempt to execute the container via the interface instead. Then return that result forward.
     */
    var containerPtr : P2PInterface? = null

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

        if(!pcpContext.tpipeOptions.isEmpty() || !pcpContext.httpOptions.isEmpty() || !pcpContext.stdioOptions.isEmpty() || pcpContext.pythonOptions.availablePackages.isNotEmpty())
        {
            val pcpAsJson = serialize(pcpContext, false)
            val pcpRequestAsJson = exampleFor(PcPRequest::class)

            val pcpContextRequirement = """\n\nYou may take actions to carry out your task using the Pipe Context Protocol.
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
        val fullPrompt = serialize(converseHistory, encodedefault = false)
        userPrompt.text = fullPrompt

        //Finally clear away the system prompt to stop duplication.
        systemPrompt = ""

        return this
    }

    /**
     * Function to apply or re-apply the system prompt and other injections. Allows the user to re-apply after making
     * any out of order changes.
     */
    fun applySystemPrompt() : Pipe
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

        /**
         * Bind the pcp context and schema to the system prompt an agent that calls pcp should avoid also having
         * a second set of returning json. TPipe does support handling multiple, but it's strongly advised to avoid
         * such a design pattern.
         */
        if(!pcpContext.tpipeOptions.isEmpty() || !pcpContext.httpOptions.isEmpty() || !pcpContext.stdioOptions.isEmpty() || pcpContext.pythonOptions.availablePackages.isNotEmpty())
        {
            val pcpAsJson = serialize(pcpContext, false)
            val pcpRequestAsJson = exampleFor(PcPRequest::class)

            var pcpContextRequirement = """
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
            """.trimMargin()

            //Allow this to also be overridden.
            if(pcpDescription.isNotEmpty()) pcpContextRequirement = pcpDescription

            systemPrompt = systemPrompt + pcpContextRequirement
        }

        if(!p2pAgentDescriptors.isNullOrEmpty())
        {
            //Available agents to call and schema to use to call them with.
            val agentList = serialize(p2pAgentDescriptors, false)
            val agentRequestSchema = exampleFor(AgentRequest::class)

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
                |When returning any json requests for agents. You must always follow the json schema exactly.
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

        //Bind system prompt footer if valid.
        if(footerPrompt.isNotEmpty())
        {
            systemPrompt = "$systemPrompt \n\n $footerPrompt"
        }

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
     * Attempt to serialize a given object into a json string. This will generate all the default
     * values needed so that we can pass this example forward to the AI model with all default values known.
     * @param json The object to serialize into JSON format
     * @param senddefaults Whether to include default values in the serialization
     * @return This Pipe object for method chaining
     */
    inline fun <reified T> setJsonInput(json: T,senddefaults: Boolean = true): Pipe {

        this.jsonInput = exampleFor(T::class).toString()
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
        this.jsonOutput = exampleFor(T::class).toString()
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
        stripNonJson = true
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
     * Enables pulling context from the global context bank when this pipe executes.
     * @return This Pipe object for method chaining
     */
    fun pullGlobalContext() : Pipe
    {
        readFromGlobalContext = true
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
        tokenBudgetSettings = budget
        return this
    }

    /**
     * Assign the max token budget immediately and auto handle getting the correct token size remaining
     * for the context window size settings. This setter is treated as internal because it's intended to
     * be invoked at the pipe's runtime.
     */
    private fun setTokenBudgetInternal(budget: TokenBudgetSettings) : Pipe
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

        //First subtract the system prompt from our token budget.
        val systemPromptTokens = Dictionary.countTokens(systemPrompt, truncationSettings)
        workingTokenWindowSize -= systemPromptTokens

        if(workingTokenWindowSize <= 0) throw Exception("System prompt has overflowed the token budget.")

        //Subtract against max tokens next assuring that the system prompt, and output expectations won't overflow us.
        var maxTokensFromSettings = budget.maxTokens ?: maxTokens
        workingTokenWindowSize -= maxTokens
        if(workingTokenWindowSize <= 0) throw Exception("Max tokens has overflowed the token budget.")

        /**
         * If not valid assume reasoning budget of zero. Should the user supply a reasoning sub-pipe to this pipe,
         * it's limit on how long it can reason will be defined by maximum round counts, stop sequences, or outright
         * overflowing the max tokens (which would be a pipe failure if such an overflow occurred).
         */
        val reasoningBudget = budget.reasoningBudget ?: 0
        if(reasoningBudget > maxTokens) throw IllegalArgumentException("Reasoning tokens cannot be greater " +
                "than the overall max token budget for llm output.")

        /**
         * Subtract max token output to ensure we are keeping both model reasoning, and token output constrained
         * to the defined token budget.
         */
        maxTokensFromSettings -= reasoningBudget

        /**
         * Now after saving this back to the pipe we have our true max tokens which also ensure reasoning is accounted
         * for either being 0 for not being set, or being subtracted correctly from the max token value.
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
         * saftey checks for json. So that is very much user beware and up to the user to ensure saftey on that
         * front.
         */
        if(budget.userPromptSize != null && budget.userPromptSize!! > workingTokenWindowSize)
        {
            throw IllegalArgumentException("User prompt size cannot be greater than the token budget. " +
                    "After subtracting the system prompt, and the maxTokens out, the context window size is ${workingTokenWindowSize}. " +
                    "Your user prompt has been sized to ${budget.userPromptSize}")
        }

        /**
         * Subtract against context window if the user prompt size limit was provided. Otherwise, the size limit
         * will be decided based on full context window size - whatever is leftover when we go to execute
         * the prompt.
         */
        workingTokenWindowSize -= budget.userPromptSize ?: 0

        contextWindowSize = workingTokenWindowSize

        return this
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
     * Enables updating the pipeline's context with this pipe's context when execution completes.
     * @return This Pipe object for method chaining
     */
    fun updatePipelineContextOnExit() : Pipe
    {
        updatePipelineContextOnExit = true
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
     * Enable automatic context and lorebook selection, and truncation when this pipe executes.
     */
    fun autoTruncateContext() : Pipe
    {
        autoTruncateContext = true
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
        modelReasoningSettingsV3 = custom
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
    fun setValidatorFunction(func: suspend (content: MultimodalContent) -> Boolean): Pipe
    {
        this.validatorFunction = func
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
            if (result) {
                processed
            } else {
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
     * @return This Pipe object for method chaining
     */
    fun setValidatorPipe(pipe: Pipe): Pipe
    {
        this.validatorPipe = pipe
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
        return this
    }



    /**
     * Sets the branch pipe for the pipe. This pipe will be used to handle validation failures.
     * Will be invoked if the failure function is not assigned.
     *
     * @param pipe The branch pipe to use for failure handling
     * @return This Pipe object for method chaining
     */
    fun setBranchPipe(pipe: Pipe): Pipe
    {
        this.branchPipe = pipe
        return this
    }

    /**
     * Setter to set the TPipe model reasoning pipe.
     */
    fun setReasoningPipe(pipe: Pipe): Pipe
    {
        this.reasoningPipe = pipe
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
     * Sets the pcp description which can be used to override the default description.
     */
    fun setPcPDescription(description: String) : Pipe
    {
        pcpDescription = description
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
        
        if (!parseResult.success)
        {
            return PcpExecutionResult(
                success = false,
                results = emptyList(),
                errors = parseResult.errors,
                executionTimeMs = 0
            )
        }
        
        // Execute with actual pipe context (fixes dispatcher wiring issue)
        return pcpDispatcher.executeRequests(parseResult.requests, pcpContext)
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
        returnVar.alwaysSplitIfWholeWordExists = alwaysSplitIfWholeWordExists
        returnVar.countSubWordsInFirstWord = countSubWordsInFirstWord

        return returnVar
    }
    
    /**
     * Extracts reasoning content from the last response if available.
     * @return The reasoning content or empty string if not available
     */
    fun getReasoningContent(): String {
        return try {
            if (jsonOutput.contains("reasoning")) {
                val json = kotlinx.serialization.json.Json.parseToJsonElement(jsonOutput)
                if (json is kotlinx.serialization.json.JsonObject) {
                    json["reasoning"]?.let { element ->
                        if (element is kotlinx.serialization.json.JsonPrimitive) element.content else ""
                    } ?: ""
                } else ""
            } else ""
        } catch (e: Exception) {
            ""
        }
    }

    /**
     * Enables tracing for this pipe with the specified configuration.
     * @param config The tracing configuration to use
     * @return This Pipe object for method chaining
     */
    fun enableTracing(config: TraceConfig = TraceConfig(enabled = true)): Pipe
    {
        this.tracingEnabled = true
        this.traceConfig = config
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
        if (!tracingEnabled) return
        
        // Check if this event should be traced based on detail level
        if (!EventPriorityMapper.shouldTrace(eventType, traceConfig.detailLevel)) return
        
        // Build metadata based on detail level
        val enhancedMetadata = buildMetadataForLevel(metadata, traceConfig.detailLevel, eventType, error, content, phase)
        
        val event = TraceEvent(
            timestamp = System.currentTimeMillis(),
            pipeId = pipeId,
            pipeName = if (pipeName.isNotEmpty()) pipeName else (this::class.simpleName ?: "UnknownPipe"),
            eventType = eventType,
            phase = phase,
            content = if (shouldIncludeContent(traceConfig.detailLevel)) content else null,
            contextSnapshot = if (shouldIncludeContext(traceConfig.detailLevel)) contextWindow else null,
            metadata = if (traceConfig.includeMetadata) enhancedMetadata else emptyMap(),
            error = error
        )
        
        currentPipelineId?.let { pipelineId ->
            PipeTracer.addEvent(pipelineId, event)
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
        
        when (detailLevel) {
            TraceDetailLevel.MINIMAL -> {
                // Only error information for failures
                if (error != null) {
                    metadata["error"] = error.message ?: "Unknown error"
                }
            }
            
            TraceDetailLevel.NORMAL -> {
                // Basic pipe information
                metadata["model"] = model.ifEmpty { "not_set" }
                metadata["provider"] = provider.name
                if (error != null) {
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
                if (error != null) {
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
                metadata["validatorPipe"] = if (validatorPipe != null) validatorPipe!!::class.simpleName ?: "UnknownPipe" else "null"
                metadata["transformationPipe"] = if (transformationPipe != null) transformationPipe!!::class.simpleName ?: "UnknownPipe" else "null"
                metadata["branchPipe"] = if (branchPipe != null) branchPipe!!::class.simpleName ?: "UnknownPipe" else "null"
                if (error != null) {
                    metadata["error"] = error.message ?: "Unknown error"
                    metadata["errorType"] = error::class.simpleName ?: "Unknown"
                    metadata["stackTrace"] = error.stackTraceToString()
                }
            }
        }
        
        // Add reasoning pipe metadata only once per execution to avoid duplication
        if (isExecutingAsReasoningPipe && !reasoningContentAlreadyTraced && eventType == TraceEventType.API_CALL_SUCCESS && content != null)
        {
            metadata["reasoningContent"] = content.text
            metadata["isReasoningPipe"] = true
            metadata["modelSupportsReasoning"] = true
            metadata["reasoningEnabled"] = true
            reasoningContentAlreadyTraced = true
        }
        
        return metadata
    }
    
    private fun shouldIncludeContent(detailLevel: TraceDetailLevel): Boolean {
        return when (detailLevel) {
            TraceDetailLevel.MINIMAL -> false
            TraceDetailLevel.NORMAL -> false
            TraceDetailLevel.VERBOSE -> traceConfig.includeContext
            TraceDetailLevel.DEBUG -> traceConfig.includeContext
        }
    }

    private fun shouldIncludeContext(detailLevel: TraceDetailLevel): Boolean {
        return when (detailLevel) {
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
     * Selects the global context mode based on the pageKeyList. If the list is empty, it returns the example for MiniBank.
     * Otherwise, it returns the example for contextWindow.
     *
     * @return The selected global context mode as a string.
     */
    fun selectGlobalContextMode() : String
    {
        if(pageKeyList.isEmpty())
        {
            return exampleFor(MiniBank::class).toString()
        }

        return exampleFor(contextWindow::class).toString()
    }

    /**
     * Optional init function. Some AI APIs may require some level of complex login, api keys, access keys,
     * decryption, or other steps to start using the service or model in question. This function can be used to
     * handle that initialization. If not required, this function can be implemented with an empty function.
     */
     open suspend fun init() : Pipe
     {
         validatorPipe?.init()
         validatorPipe?.pipelineRef = pipelineRef
         branchPipe?.init()
         branchPipe?.pipelineRef = pipelineRef
         transformationPipe?.init()
         transformationPipe?.pipelineRef = pipelineRef
         reasoningPipe?.init()
         reasoningPipe?.pipelineRef = pipelineRef

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
     * Truncates context windows based on supplied settings, or custom configurations for supported models.
     * Each module implements models for a given provider. The developer of each TPipe module can proceed to
     * directly handle exact configurations for truncation or just use the class variables here to supply
     * the function parameters for truncation.
     */
    private suspend fun truncateToFitTokenBudget(content: MultimodalContent) : MultimodalContent
    {
        val budget = tokenBudgetSettings
        if(budget == null) return content //We can't truncate if this was never assigned.

        setTokenBudgetInternal(budget)  //Required to get all our internal window sizes to be correct.

        /**
         * Define how much space we actually have to supply external context.
         * External context is anything that's not the system prompt, user prompt, or binary data.
         */
        var workingContextWindowSpace = contextWindowSize
        val truncationSettings = getTruncationSettings()

        /**
         * Declare at the top level now. This ensures that we won't have to perform any potentially costly token
         * counting for the user prompt anymore times than need be.
         */
        val userPrompt = content.text
        val userPromptTokenCost = Dictionary.countTokens(userPrompt, truncationSettings)

        /**
         * First we need to count up all the tokens and all the binary content stored here is using. This allows us
         * to determine how much actual remaining space we have in our context window for external context.
         * Binary data cannot be compressed any further, and semantic compression only works on text that's actually
         * text. Base64 does not qualify this check, so we have to just chop out any binary token usage from our
         * available space.
         *
         * When setting up the initial token budget. This data was not present, so now at this second stage we're
         * able to account for it.
         */
        val binaryTokenCost = countBinaryTokens(content, truncationSettings)
        workingContextWindowSpace -= binaryTokenCost //Determine the actual space we have left for context.

        /**
         * Most API's will fail if we overflow. Even if we did not, truncating binary data could be disastrous
         * so we need to throw here and stop things on the spot.
         */
        if(workingContextWindowSpace <= 0)
        {
            throw Exception("Context window size is too small to fit the binary data. Please increase the context window size.")
        }

        /**
         * Next we need to truncate the context window, or mini bank depending on which is populated.
         * This will favor the mini bank over the context window. Beware that the mini bank cannot be
         * truncated as a string so that setting will end up being ignored.
         */
        if(miniContextBank.isEmpty())
        {
            if(!budget.truncateContextWindowAsString)
            {
                /**
                 *  todo: Allow some kind of value to be passed to give the user more control over what
                 *  handles key selection. This can act as a good in-between needing a custom truncation
                 *  function at prevalidation, or pre-init functions. Maybe use the pipe metadata,
                 *  or even the content metadata?
                 */

                //Standard select and truncate method. User prompt will be used to select lorebook keys.
                contextWindow.selectAndTruncateContext(
                    content.text,
                    workingContextWindowSpace,
                    budget.truncationMethod,
                    truncationSettings)
            }

            else
            {
                //Combine and truncate the entire context window as a string. This is useful for certain providers.
                val asString = contextWindow.combineAndTruncateAsStringWithSettings(
                    content.text,
                    workingContextWindowSpace,
                    truncationSettings,
                    budget.truncationMethod)

                contextWindow.clear()
                contextWindow.contextElements.add(asString)
            }
        }

        /**
         * Note: Combine and truncate as string is not supported with the mini bank object.
         * So we can only use standard truncation methods for the mini bank contents.
         */
        else
        {
            for(it in miniContextBank.contextMap)
            {
                val bankWindow = it.value
                bankWindow.selectAndTruncateContext(
                    content.text,
                    workingContextWindowSpace,
                    budget.truncationMethod,
                    truncationSettings)
            }
        }

        /**
         * Auto-assign the user prompt space if not assigned. Unlike many other settings in the budget that can be
         * left blank, the pipe class does not have a designated space for user prompt size storage.
         * So we need to calculate how much space would be allowed for it based on budget constraints if the
         * user did not supply a specified budget. In this case, the user prompt is now allowed to fill
         * the remaining space left in the budget for the context window.
         */
        if(budget.userPromptSize == null)
        {
            //Estimate tokens spent excluding our user prompt.
            val estimatedTokensSpent = calculateTokensSpent(content) - userPromptTokenCost
            val estimatedUserPromptAllowance = originalContextWindowSize - estimatedTokensSpent
            budget.userPromptSize = estimatedTokensSpent
        }

        /**
         * Check to see if the user prompt has exceeded the provided budget. If no budget has been provided
         * we need to calculate how much remaining space in the original value we actually have and fill to
         * that point.
         */
        val userPromptSpace = budget.userPromptSize

        if(userPromptTokenCost > userPromptSpace!!)
        {
            /**
             * Attempt to deploy compression. If we can compress and fit we pass, if even after compression
             * we cannot fit we must fail out. The pipe will need to have the compression ledger added to
             * the system prompt to ensure it can decompress the data. Ensure there is enough cluster slack
             * to spend on either reasoning out the decompression, or addressing it directly in the output.
             */
            if(budget.compressUserPrompt)
            {
                //todo: Place compression call here.
            }

            /**
             * WARNING: User prompt truncation is an experimental feature that allows for possibly fitting the
             * content into the given window. It's assuming the common strategy of supplying json as context,
             * or providing json after the user prompt. Or providing user prompt data that doesn't include json
             * code or other elements that are too risky to truncate. This may allow gains when there otherwise
             * is no chance to fit into the budget and execute this pipe. However, this should be considered
             * very carefully and generally avoided unless you're aiming to get maximum gains, and you're certain
             * of the content of the prompt and are prepared for the consequences.
             *
             * Truncating a converse history however, is far safer and just clips out
             * parts of the conversation that are unable to fit. If you're going to use user prompt truncation
             * it's recommended to store all prompts in a converse history object.
             */
            else
            {
                /**
                 * First test if the user prompt is stored using converse. If so we want to truncate
                 * it using the converse truncation mode which will preserve the converse json structuring.
                 */
                val converseContent = deserialize<ConverseHistory>(content.text)
                if(converseContent != null)
                {
                    /**
                     * Required boilerplate to move the converse history to a context window.
                     * Then use ContextWindow's helper function to truncate the converse
                     * history structure safely.
                     */
                    val truncateWindow = ContextWindow()
                    truncateWindow.converseHistory = converseContent
                    truncateWindow.truncateConverseHistoryWithObject(
                        budget.userPromptSize!!,
                        0,
                        budget.truncationMethod,
                        truncationSettings)

                    val newUserPrompt = serialize(truncateWindow.converseHistory, encodedefault = false)
                    content.text = newUserPrompt
                }

                /**
                 * User prompt is not structured as a converse object. In this case, we need to treat as a string
                 * regardless of the content contained. The option to preserve json is also available but
                 * if preserved could cause us to fail to fit.
                 */
                else
                {
                    /**
                     * First extract any json objects we can find. We'll come back to appending these again
                     * later if we need them. This will be determined by [TokenBudgetSettings.preserveJsonInUserPrompt]
                     */
                    val jsonContentInUserPrompt = extractAllJsonObjects(content.text)

                    /**
                     * We need to separate this portion in the event we're extracting the non json portions and
                     * separating the json for preservation.
                     */
                    var exteriorContent = extractNonJsonText(content.text)

                    /**
                     * We're preserving the json here so we only want to truncate the user prompt and then
                     * check to ensure we're now under the limit. We're presuming the prompt was that was
                     * non-json was supplied prior. Then json came after.
                     */
                    if(budget.preserveJsonInUserPrompt)
                    {
                        //Truncate the exterior content.
                       exteriorContent = Dictionary.truncateWithSettings(
                            exteriorContent,
                            budget.userPromptSize!!,
                            budget.truncationMethod,
                            truncationSettings)

                        var mergedUserPrompt = ""
                        mergedUserPrompt += exteriorContent //First add the non json portion.

                        for(jsonObject in jsonContentInUserPrompt)
                        {
                            /**
                             * Safe to use toString() here because it's already in the form of a json object
                             * This means that the schema and formatting will be preserved unlike calling
                             * toString() on a data class vs serializing it.
                             */
                            mergedUserPrompt += jsonObject.toString()
                        }

                        //Push back the now truncated user prompt.
                        content.text = mergedUserPrompt

                    }

                    /**
                     * Json preservation was not enabled. So we're just going try to truncate the entire thing.
                     * Exercise caution when truncating fully as a string.
                     */
                    else
                    {
                        var fullUserPrompt = content.text
                        fullUserPrompt = Dictionary.truncateWithSettings(
                            fullUserPrompt,
                            budget.userPromptSize!!,
                            budget.truncationMethod,
                            truncationSettings)

                        content.text = fullUserPrompt
                    }
                }
            }
        }

        return content
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
        val result = executeMultimodal(content)
        result.text
    }
    
    /**
     * Executes the current pipe with multimodal content support. This is the primary execution method
     * that handles validation, transformation, and failure logic with full multimodal capabilities.
     *
     * @param content Multimodal content containing text and/or binary data
     * @return The multimodal result of the AI api call
     */
    suspend fun execute(content: MultimodalContent): MultimodalContent = executeMultimodal(content)
    
    /**
     * Internal multimodal execution logic shared by both execute methods
     */
    private suspend fun executeMultimodal(inputContent: MultimodalContent): MultimodalContent = coroutineScope{

        /**
         * If we're using this pipe a proxy we'll repoint to the proxy container pointer instead, and execute whatever
         * it is. The result will be returned here and then out to the rest of the pipeline. By doing this, we can
         * allow the case of storing a higher level container inside a lower level pipeline.
         */
        if(containerPtr != null)
        {
            return@coroutineScope containerPtr!!.executeLocal(inputContent)
        }

        /**
         * NOTE: This has been moved here from [init] due to needing to reset each execution step to clean up any
         * injected model reasoning into the system prompt.
         */
        applySystemPrompt()

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

        try {

            
            if(readFromGlobalContext && !readFromPipelineContext)
            {
                //Pull from context bank if no page keys are set.
                if(pageKey.isEmpty() && pageKeyList.isEmpty())
                {
                    contextWindow = ContextBank.copyBankedContextWindow() ?: ContextWindow()
                }

                else
                {
                    //Pull multiple pages of global context from the bank if more than one key was provided.
                    if(pageKeyList.isNotEmpty())
                    {
                        //Populate the mini bank for multi page key setup.
                        for(page in pageKeyList)
                        {
                            val pagedContext = ContextBank.getContextFromBank(page)
                            miniContextBank.contextMap[page] = pagedContext
                        }
                    }


                    contextWindow = ContextBank.getContextFromBank(pageKey)

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
            else if(readFromPipelineContext)
            {
                contextWindow = pipelineRef?.context ?: ContextWindow()
                miniContextBank = pipelineRef?.miniBank ?: miniContextBank
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
             * pipe thus far. This is the final step before truncation, and
             */
            var baseContent = if (multimodalInput.text.isNotEmpty() || multimodalInput.binaryContent.isNotEmpty()) {
                val merged = MultimodalContent(multimodalInput.text, multimodalInput.binaryContent.toMutableList(), multimodalInput.terminatePipeline)
                merged.addText(inputContent.text)
                merged
            } else {
                inputContent
            }

            //Bind to prevent nullptr leakage.
            baseContent.currentPipe = inputContent.currentPipe

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

                //Use advanced token budgeting to fit constraints if available.
                if(tokenBudgetSettings != null)
                {
                   baseContent = truncateToFitTokenBudget(baseContent)
                }

                else
                {
                    truncateModuleContext() //Default to basic context truncation instead.
                }

            }
            
            // Build full prompt with correct ordering: userPrompt -> user content -> context
            var fullPrompt = if(userPrompt.isNotEmpty()) {"${userPrompt}\n\n${baseContent.text}"} else{baseContent.text}
            
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
                if(pageKeyList.isEmpty())
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

                    return@coroutineScope inputContent
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


             //Count input tokens.
            trace(TraceEventType.PIPE_START, TracePhase.EXECUTION,
                metadata = mapOf("tokenCount" to countTokens(true, processedContent)))

            try{
                if(reasoningPipe != null)
                {
                    var reasoningResult = executeReasoningPipe(processedContent)
                    reasoningResult.modelReasoning = removeFromFirstOccurrence(reasoningResult.modelReasoning, "##Final Answer##")
                    processedContent.modelReasoning = reasoningResult.modelReasoning
                    injectTPipeReasoning(processedContent)
                }
            }
            catch (e: Exception)
            {
                trace(TraceEventType.PIPE_FAILURE, TracePhase.EXECUTION, processedContent, error = e)
            }

            /**
             * Call generateContent() to invoke the loaded AI api with multimodal support.
             */
            trace(TraceEventType.API_CALL_START, TracePhase.EXECUTION, processedContent)
            val result : Deferred<MultimodalContent> = async {
                generateContent(processedContent)
            }

            //Run the llm and await it's output.
            var generatedContent = result.await()
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


            //Count tokens the model generated.
            trace(TraceEventType.API_CALL_SUCCESS, TracePhase.EXECUTION, generatedContent,
                metadata = mapOf("tokenCount" to countTokens(false, generatedContent)))


            /**
             * If a validator pipe is supplied it can be invoked prior to the validation function.
             */
            var validatorPipeContent = generatedContent
            if(validatorPipe != null)
            {
                trace(TraceEventType.BRANCH_PIPE_TRIGGERED, TracePhase.VALIDATION)
                try {
                    validatorPipe!!.init()
                    if (tracingEnabled) {
                        validatorPipe!!.enableTracing(traceConfig)
                        validatorPipe!!.currentPipelineId = currentPipelineId
                    }
                    val validatorPipeResult : Deferred<MultimodalContent> = async {
                        validatorPipe?.execute(generatedContent) ?: MultimodalContent()
                    }
                    validatorPipeContent = validatorPipeResult.await()
                    validatorPipeContent.currentPipe = inputContent.currentPipe //Avoid nullptr leakage.
                    validatorPipeContent.metadata = generatedContent.metadata //Copy to avoid leakage after llm call.
                } catch (e: Exception) {
                    trace(TraceEventType.PIPE_FAILURE, TracePhase.VALIDATION, generatedContent, error = e)
                    validatorPipeContent = generatedContent
                }
            }

            if(!validatorPipeContent.shouldTerminate())
            {
                /**
                 * Execute validation function if provided.
                 */
                if (validatorFunction != null)
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
                        if (transformationPipe != null)
                        {
                            trace(TraceEventType.BRANCH_PIPE_TRIGGERED, TracePhase.TRANSFORMATION)
                            try {
                                transformationPipe!!.init()
                                if (tracingEnabled)
                                {
                                    transformationPipe!!.enableTracing(traceConfig)
                                    transformationPipe!!.currentPipelineId = currentPipelineId
                                }

                                val transformPipeResult : Deferred<MultimodalContent> = async {
                                    transformationPipe?.execute(generatedContent) ?: generatedContent
                                }

                                val metadataBackup = generatedContent.metadata //Required to preserve prior to llm.
                                generatedContent = transformPipeResult.await()
                                generatedContent.currentPipe = inputContent.currentPipe
                                generatedContent.metadata = metadataBackup //Copy over in case the llm stomps this.

                            } catch (e: Exception) {
                                trace(TraceEventType.PIPE_FAILURE, TracePhase.TRANSFORMATION, generatedContent, error = e)
                                // Continue with original content if transformation pipe fails
                            }
                        }

                        /**
                         * Apply transformation function if provided.
                         */
                        val finalResult = if (transformationFunction != null)
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

                        //Merge in context window changes if enabled.
                        if(updatePipelineContextOnExit)
                        {
                            pipelineRef?.context?.merge(contextWindow, emplaceLorebook, appendLoreBook)
                            pipelineRef?.miniBank?.merge(miniContextBank, emplaceLorebook, appendLoreBook)
                        }

                        trace(TraceEventType.PIPE_SUCCESS, TracePhase.CLEANUP, finalResult,
                              metadata = mapOf("outputText" to if (isExecutingAsReasoningPipe) "" else finalResult.text))
                        return@coroutineScope finalResult
                    }
                    else
                    {
                        trace(TraceEventType.VALIDATION_FAILURE, TracePhase.VALIDATION, generatedContent)
                    }
                }
                else
                {
                    // No validation function, continue to transformation
                    if (transformationPipe != null)
                    {
                        trace(TraceEventType.BRANCH_PIPE_TRIGGERED, TracePhase.TRANSFORMATION)
                        try {
                            transformationPipe!!.init()
                            if (tracingEnabled) {
                                transformationPipe!!.enableTracing(traceConfig)
                                transformationPipe!!.currentPipelineId = currentPipelineId
                            }
                            val transformPipeResult : Deferred<MultimodalContent> = async {
                                transformationPipe?.execute(generatedContent) ?: generatedContent
                            }

                            val metadataBackup = generatedContent.metadata //Required to preserve prior to llm.
                            generatedContent = transformPipeResult.await()
                            generatedContent.currentPipe = inputContent.currentPipe //Prevent nullptr leakage.
                            generatedContent.metadata = metadataBackup

                        } catch (e: Exception) {
                            trace(TraceEventType.PIPE_FAILURE, TracePhase.TRANSFORMATION, generatedContent, error = e)
                            // Continue with original content if transformation pipe fails
                        }
                    }

                    val finalResult = if (transformationFunction != null)
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

                    if(updatePipelineContextOnExit)
                    {
                        pipelineRef?.context?.merge(contextWindow, emplaceLorebook, appendLoreBook)
                    }

                    trace(TraceEventType.PIPE_SUCCESS, TracePhase.CLEANUP, finalResult,
                          metadata = mapOf("outputText" to if (isExecutingAsReasoningPipe) "" else finalResult.text))
                    return@coroutineScope finalResult
                }

                //Execute branch pipe if provided.
                if (branchPipe != null)
                {
                    trace(TraceEventType.BRANCH_PIPE_TRIGGERED, TracePhase.POST_PROCESSING)
                    try {
                        // Initialize and setup branch pipe
                        branchPipe!!.init()
                        if (tracingEnabled) {
                            branchPipe!!.enableTracing(traceConfig)
                            branchPipe!!.currentPipelineId = currentPipelineId
                        }
                        
                        val branchPipeResult : Deferred<MultimodalContent> = async {
                            branchPipe?.execute(generatedContent) ?: generatedContent
                        }
                        val branchResult = branchPipeResult.await()
                        branchResult.currentPipe = inputContent.currentPipe
                        branchResult.metadata = generatedContent.metadata
                        
                        //If branch pipe allows continuation, continue pipeline.
                        if(!branchResult.shouldTerminate())
                        {
                            //Merge in context window changes if enabled.
                            if(updatePipelineContextOnExit)
                            {
                                pipelineRef?.context?.merge(contextWindow, emplaceLorebook, appendLoreBook)
                            }


                            return@coroutineScope branchResult
                        }
                        
                        generatedContent = branchResult
                    } catch (e: Exception) {
                        trace(TraceEventType.PIPE_FAILURE, TracePhase.POST_PROCESSING, generatedContent, error = e)
                        // Branch pipe failed, continue to failure function
                    }
                }

                //Invoke failure function if provided.
                if (onFailure != null) {
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

                        return@coroutineScope failureResult
                    }
                }
            }

            /**
             * Pipeline termination - return terminated content to signal pipeline failure.
             */
            trace(TraceEventType.PIPE_FAILURE, TracePhase.CLEANUP, inputContent)
            return@coroutineScope MultimodalContent()
            
        } catch (e: Exception) {
            trace(TraceEventType.PIPE_FAILURE, TracePhase.CLEANUP, inputContent, error = e)
            return@coroutineScope MultimodalContent("")
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
        val reasoningBudget = tokenBudgetSettings?.reasoningBudget ?: 0 //Declare our budget. 0 is unlimited.
        var budgetPerRound = 0 //Divided by reasoningBudget / number of reasoning rounds.
        var rounds = pipeMetadata["reasoningRounds"] as? Int //Get now. We'll need this many times forward alas.
        if(rounds == null) rounds = 1 //Define to adress behavior of Any to Any maps in kotlin. We can't be less than 1.

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
        val converseSchema = jsonInput

        /**
         * Try to get the ref for this. Because it's in schema form we should in theory be able to get this as
         * a non-null object.
         */
        val converseSchemaRef = deserialize<ConverseHistory>(converseSchema)

        /**
         * We're certain that the schema is converse so we need to assign the first step of this process as
         * "user". Then we'll work our way forward treating the focus data as "system" and reasoning as
         * "assistant".
         */
        var usingConverse = converseSchemaRef != null || rounds > 1


        if(usingConverse)
        {
            /**
             * System prompt must be copied from this pipe to the user prompt we're passing to our target reasoning
             * pipe.
             */
            val systemConverseData = ConverseData(ConverseRole.developer, MultimodalContent("$rawSystemPrompt ${getMiddlePromptForReasoning()} ${getFooterPromptForReasoning()}"))

            //Now we can add the user's original prompt.
            val converseData = ConverseData(ConverseRole.user, content)
            val newHistory = ConverseHistory()

            //Now we'll have each part we need for it to reason correctly.
            newHistory.add(systemConverseData)
            newHistory.add(converseData)

            //Force back to the string so we can push this into our copied content object.
            val historyAsJson = serialize(newHistory, encodedefault = false)
            contentCopy.text = historyAsJson
        }

        /**
         * Directly combine the system prompt to the user prompt in this case as a "developer prompt". We need to
         * do this because the reasoning pipe actually doesn't know the full rules of its own request otherwise.
         */
        else
        {
            val combinedPrompt = """##DEVELOPER PROMPT##
                |$rawSystemPrompt ${getMiddlePromptForReasoning()} ${getFooterPromptForReasoning()}
                |
                |##USER PROMPT##
                |${content.text}
            """.trimMargin()

            contentCopy.text = combinedPrompt
        }

        /**
         * Propagate tracing if we've enabled it in this pipe. Normally this happens at the execute stage of this
         * pipe. However, that area of the code is quite busy, and TPipe reasoning is extremely non-trivial in of itself.
         * So we'll just enable it here even if it breaks conventions a bit.
         */
        if(tracingEnabled)
        {
            reasoningPipe?.enableTracing()
            reasoningPipe?.currentPipelineId = currentPipelineId
        }

        /**
         * Iterate through every single round, and force the pipe to reason until every round is cleared.
         * Any overflow will be truncated against the budget if the budget is not 0. Otherwise, it can reason
         * until the end of time if it wishes.
         */
        for(round in 1..rounds)
        {
            /**
             * Get our focus target if there is one. This allows us to help push reasoning to target a specific area.
             * This is very useful for forcing models to actually pay attention to instructions or put more effort
             * into points that are more critical.
             */
            var focusTarget = pipeMetadata["focusPoints"] as? String
            if(focusTarget == null) focusTarget = ""

            /**
             * Any rounds past 1 will require us to keep appending the converse history as we go along.
             * However, for the very first round we can assume it's already setup for the first user request
             * so we'll need to be prepared to skip over that.
             */
            val firstRound = round == 1

            /**
             * Now we need to adress focus targets. These are values that can be assigned at each round to force
             * the model to focus on a specific part of the task. We need to handle both standard appends and
             * converse history appends as need be.
             */
            if(focusTarget.isNotEmpty())
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
                    val newJson = serialize(history, encodedefault = false)

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
                        val newJson = serialize(extractedHistory, encodedefault = false)
                        contentCopy.text = newJson
                    }

                    //Not using converse. Just append and emplace back.
                    else
                    {
                        contentCopy.text = "${contentCopy.text} ${focusMessage}"
                    }
                }
            }

            /**
             * Finally, execute the reasoning pipe and fetch the result. If it has a result we'll push it back
             * into the reasoning block of the copied content. The loop will continue until we've cleared every
             * step of the reasoning process.
             */
            val result = reasoningPipe?.let { pipe ->
                pipe.isExecutingAsReasoningPipe = true
                pipe.reasoningContentAlreadyTraced = false
                val pipeResult = pipe.executeMultimodal(contentCopy)
                pipe.isExecutingAsReasoningPipe = false
                pipeResult
            } ?: content

            /**
             * TPipe reasoning now stores the output as json to further coercee misbehaving models. This data needs
             * to be turned back to a fully structured string formatted to be a proper stream of model reasoning.
             * The assumption is that [ReasoningBuilder.assignDefaults] was correctly invoked. Prior. If not the
             * output of the reasoning pipe will become empty.
             */
            result.text = extractReasoningContent(reasoningPipe?.pipeMetadata["reasoningMethod"] as? String ?: "", result)
            contentCopy.modelReasoning += " ${result.text}"
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

        when (reasoningMethod)
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

                val reasoningCombo = "${injectionMessage} ${reasoningOutput}"

                val secondaryInjectionMessage = """USER PROMPT:"""

                //Combine reasoning then a notification of the user prompt, and then the user prompt.
                content.text  = "$injectionMessage $secondaryInjectionMessage ${content.text}"
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
                            val newConverseJson = serialize(converseHistory, encodedefault = false)
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
                            val newConverseJson = serialize(converseHistory, encodedefault = false)
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

                if(!contextWindow.isEmpty()) contextWindow.merge(newContextWindow)
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
            currentPipelineId = currentPipelineId
        )
    }

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

    override fun setP2pDescription(description: P2PDescriptor) {
        p2pDescriptor = description
    }

    override fun getP2pDescription(): P2PDescriptor? = p2pDescriptor

    override fun setP2pTransport(transport: P2PTransport) {
        p2pTransport = transport
    }

    override fun getP2pTransport(): P2PTransport? = p2pTransport

    override fun setP2pRequirements(requirements: P2PRequirements) {
        p2pRequirements = requirements
    }

    override fun getP2pRequirements(): P2PRequirements? = p2pRequirements

    override fun getContainerObject(): Any? = containerObject

    override fun setContainerObject(container: Any) {
        containerObject = container
    }

    override fun getPipelinesFromInterface(): List<Pipeline> = listOf(pipelineRef!!)

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

}

