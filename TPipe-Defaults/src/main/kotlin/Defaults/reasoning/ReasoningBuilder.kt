package Defaults.reasoning

import Defaults.BedrockConfiguration
import Defaults.ManifoldDefaults.withBedrock
import Defaults.OllamaConfiguration
import Defaults.providers.BedrockDefaults.createBedrockPipe
import Defaults.providers.OllamaDefaults.createOllamaPipe
import Defaults.reasoning.ReasoningPrompts.bestIdeaPrompt
import Defaults.reasoning.ReasoningPrompts.chainOfThoughtSystemPrompt
import Defaults.reasoning.ReasoningPrompts.comprehensivePlanPrompt
import Defaults.reasoning.ReasoningPrompts.rolePlayPrompt
import Defaults.reasoning.ReasoningPrompts.selectDepth
import Defaults.reasoning.ReasoningPrompts.selectDuration
import com.TTT.Context.ConverseHistory
import com.TTT.Pipe.Pipe
import com.TTT.Structs.BestIdeaResponse
import com.TTT.Structs.ChainOfDraftResponse
import com.TTT.Structs.ExplicitReasoningDetailed
import com.TTT.Structs.MethodActorResponse
import com.TTT.Structs.MultiPhasePlan
import com.TTT.Structs.PipeSettings
import com.TTT.Structs.ProcessFocusedResult
import com.TTT.Structs.StructuredCot
import kotlin.reflect.KClass

/**
 * Defines the strategy that will be used to make the llm think.
 *
 * @param BestIdea Ask the llm to come with what it thinks is the single best idea to solve the problem.
 * @param ComprehensivePlan Ask the llm to come up with a substantial comprehensive plan on how it would try to
 * solve the problem.
 * @param ExplicitCot Ask the llm to show step-by-step reasoning with clear transitions between each logical step.
 * @param StructuredCot Ask the llm to use a formal phase-based framework (analyze→plan→execute→validate).
 * @param processFocusedCot Ask the llm to focus on methodological justification and adaptive thinking strategies.
 * @param  RolePlay Ask the llm to play as a character, and act as that character trying to reason out whatever
 * the given task is.
 * @param  ChainOfDraft Use minimal draft-based reasoning with maximum 5-word constraints per step,
 * focusing on essential calculations and transformations only.
 */
enum class ReasoningMethod
{
    BestIdea,
    ComprehensivePlan,
    ExplicitCot,
    StructuredCot,
    processFocusedCot,
    RolePlay,
    ChainOfDraft
}

/**
 * Determines where model reasoning will be injected to become visible to the main pipe that's using the internal
 * reasoning pipe.
 *
 * @param SystemPrompt: Injects the reasoning at the very end of the system prompt.
 * @param BeforeUserPrompt: Injects the reasoning prior to the user prompt.
 * @param BeforeUserPromptWithConverse: Injects the reasoning into a [com.TTT.Context.ConverseHistory] block
 * at the top of the block prior to the user's prompt.
 * @param  AfterUserPrompt: Injects the reasoning after the user prompt.
 * @param AfterUserPromptWithConverse: Injects the reasoning into a [com.TTT.Context.ConverseHistory] block
 * at the bottom of the block after the user's prompt.
 * @param AsContext: Injects the reasoning as a context to a designated page key.
 */
enum class ReasoningInjector
{
    SystemPrompt,
    BeforeUserPrompt,
    BeforeUserPromptWithConverse,
    AfterUserPrompt,
    AfterUserPromptWithConverse,
    AsContext
}

/**
 * Defines the logical complexity and depth of analysis for the reasoning process.
 * Higher depth levels force the model to perform more rigorous logical checks, explore
 * more alternatives, and increase the total number of reasoning steps.
 *
 * This setting interacts with [ReasoningPrompts.selectDepth] to inject specific
 * mandatory instructions into the system prompt.
 *
 * @param Low Minimal analysis focusing only on the core problem. typically 3-5 reasoning steps.
 * @param Med Balanced analysis considering 2-3 potential approaches. Typically 6-10 reasoning steps.
 * @param High Exhaustive analysis exploring multiple approaches, implications, and edge cases. Typically 10+ reasoning steps.
 */
enum class ReasoningDepth
{
    Low,
    Med,
    High
}

/**
 * Defines the verbosity and linguistic length of the reasoning output.
 * Unlike [ReasoningDepth], which affects logical complexity, duration affects how
 * much detail and justification is provided for each logical inference.
 *
 * This setting interacts with [ReasoningPrompts.selectDuration] to inject specific
 * verbosity constraints into the system prompt.
 *
 * @param Short Concise output (40-60% of normal length) using abbreviated explanations and skipping obvious inferences.
 * @param Med Standard output (90-110% of normal length) with balanced detail and justification for key decisions.
 * @param Long Verbose output (150-200% of normal length) providing detailed explanations and justifying every inference or assumption.
 */
enum class ReasoningDuration
{
    Short,
    Med,
    Long
}

/**
 * Data class to encompass the settings required to build a reasoning pipe.
 *
 * @param reasoningMethod The strategy used to structure the model's thinking process via [ReasoningMethod].
 * @param depth The level of logical complexity and analysis depth via [ReasoningDepth].
 * @param duration The linguistic verbosity and length of the reasoning output via [ReasoningDuration].
 * @param roleCharacter The persona instructions used when [ReasoningMethod.RolePlay] is selected.
 * @param reasoningInjector Determines where the reasoning content is injected into the final prompt via [ReasoningInjector].
 * @param numberOfRounds Defines a set of rounds or steps that the reasoning occurs in. Each round will divide
 * into the assigned number of tokens allotted for model reasoning. Reasoning rounds allows for the strategy and the
 * focus to be changed each round.
 * @param focusPoints Maps a round number to a request in string form of instructions you want the pipe to focus
 * reasoning on specifically. This allows for specific aspects of a task to be thought about for longer, or for
 * a dedicated amount of time over other portions of the task.
 * @param injectMiddlePrompt If true, the middle prompt of the system prompt will be visible to the reasoning pipe.
 * @param injectFooterPrompt If true, the footer prompt of the system prompt will be visible to the reasoning pipe.
 * @param reinforceSystemPrompt If true, the system prompt will be placed again at the end of the reasoning generation
 * in order to reinforce compliance with instructions in large context windows.
 */
data class ReasoningSettings(
    var reasoningMethod: ReasoningMethod = ReasoningMethod.StructuredCot,
    var depth: ReasoningDepth = ReasoningDepth.Med,
    var duration: ReasoningDuration = ReasoningDuration.Med,
    var roleCharacter: String = "You are a helpful assistant.",
    var reasoningInjector: ReasoningInjector = ReasoningInjector.SystemPrompt,
    var numberOfRounds: Int = 1,
    var focusPoints: MutableMap<Int, String> = mutableMapOf(),
    var injectMiddlePrompt: Boolean = false,
    var injectFooterPrompt: Boolean = false,
    var reinforceSystemPrompt: Boolean = false
)

/**
 * Object responsible for building model reasoning pipes, and assigning all the defaults to them.
 *
 */
object ReasoningBuilder
{
    /**
     * Given a target pipe, reasoning settings, and pipe settings that may or may not be pulled from another pipe,
     * Alter the target pipe to have the reasoning defaults applied to it, turning it into a reasoning system
     * for llm's that don't natively support it.
     */
    fun assignDefaults(settings: ReasoningSettings, pipeSettings: PipeSettings, targetPipe: Pipe)
    {
        var targetSystemPrompt = ""

        var jsonOutputObject : Any? = null
        var jsonOutputClass : KClass<*>? = null

        /**
         * Assign the system prompt to configure the pipe to have chain of thought behavior based on the enum
         * settings provided.
         */
        when (settings.reasoningMethod)
        {
            ReasoningMethod.StructuredCot -> {
                targetSystemPrompt = chainOfThoughtSystemPrompt(
                    settings.depth,
                    settings.duration,
                    settings.reasoningMethod)

                jsonOutputObject = StructuredCot()
                jsonOutputClass = StructuredCot::class
            }

            ReasoningMethod.processFocusedCot -> {
                targetSystemPrompt = chainOfThoughtSystemPrompt(
                    settings.depth,
                    settings.duration,
                    settings.reasoningMethod
                )

                jsonOutputObject = ProcessFocusedResult()
                jsonOutputClass = ProcessFocusedResult::class
            }

            ReasoningMethod.ExplicitCot -> {
                targetSystemPrompt = chainOfThoughtSystemPrompt(
                    settings.depth,
                    settings.duration,
                    settings.reasoningMethod
                )

                jsonOutputObject = ExplicitReasoningDetailed()
                jsonOutputClass = ExplicitReasoningDetailed::class
            }

            ReasoningMethod.BestIdea -> {
                targetSystemPrompt = bestIdeaPrompt(settings.depth, settings.duration)
                jsonOutputObject = BestIdeaResponse()
                jsonOutputClass = BestIdeaResponse::class
            }

            ReasoningMethod.RolePlay -> {
                targetSystemPrompt = rolePlayPrompt(settings.roleCharacter, settings.depth, settings.duration)
                jsonOutputObject = MethodActorResponse()
                jsonOutputClass = MethodActorResponse::class
                targetSystemPrompt += """ROLE PLAY AS THE FOLLOWING CHARACTER: ${settings.roleCharacter}"""
            }

            ReasoningMethod.ComprehensivePlan -> {
                targetSystemPrompt = comprehensivePlanPrompt(settings.depth, settings.duration)
                jsonOutputObject = MultiPhasePlan()
                jsonOutputClass = MultiPhasePlan::class
            }

            ReasoningMethod.ChainOfDraft ->
            {
                targetSystemPrompt = chainOfThoughtSystemPrompt(
                    settings.depth,
                    settings.duration,
                    settings.reasoningMethod
                )

                jsonOutputObject = ChainOfDraftResponse()
                jsonOutputClass = ChainOfDraftResponse::class
            }
        }

        //Assign our system prompt to order the pipe to reason/think.
        targetPipe.setSystemPrompt(targetSystemPrompt)

        /**
         * Copy settings over. This can be pre-assigned, or captured from a Pipe. Ideally this function will be called
         * internally by another helper function that abstracts many of the steps we have to apply here.
         */
        targetPipe.setTemperature(pipeSettings.temperature)
            .setTopP(pipeSettings.topP)
            .setTopK(pipeSettings.topK)
            .setMaxTokens(pipeSettings.maxTokens)
            .setContextWindowSize(pipeSettings.contextWindowSize)
            .requireJsonPromptInjection()
        
        // Apply token budget settings if provided
        if (pipeSettings.tokenBudgetSettings != null) {
            targetPipe.setTokenBudget(pipeSettings.tokenBudgetSettings!!)
        }
        // Type-safe JSON output using cast
        when (jsonOutputClass) {
            StructuredCot::class -> targetPipe.setJsonOutput(jsonOutputObject as StructuredCot)
            ProcessFocusedResult::class -> targetPipe.setJsonOutput(jsonOutputObject as ProcessFocusedResult)
            ExplicitReasoningDetailed::class -> targetPipe.setJsonOutput(jsonOutputObject as ExplicitReasoningDetailed)
            BestIdeaResponse::class -> targetPipe.setJsonOutput(jsonOutputObject as BestIdeaResponse)
            MethodActorResponse::class -> targetPipe.setJsonOutput(jsonOutputObject as MethodActorResponse)
            MultiPhasePlan::class -> targetPipe.setJsonOutput(jsonOutputObject as MultiPhasePlan)
            ChainOfDraftResponse::class -> targetPipe.setJsonOutput(jsonOutputObject as ChainOfDraftResponse)
        }

        if(settings.numberOfRounds > 1)
        {
            /**
             * Require converse history so it can see its prior steps as we go along. Internally at the
             * execution stage of the pipe itself, we'll adress this and quietly wrap the user's request into
             * the converse history block as needed. This only matters if we're doing multiple rounds of
             * reasoning. Otherwise, we won't stomp over whatever was assigned as json input and output.
             */
            targetPipe.setJsonOutput(ConverseHistory())
                .setJsonInput(ConverseHistory())
                .requireJsonPromptInjection()
        }

        else
        {
            /**
             * Clamp to 1 so we don't have to ever assume 0 is a number we're having to account for when handling
             * more reasoning rounds. This also ensures we're defended against any divide by zero errors.
             */
            settings.numberOfRounds = 1
        }

        /**
         * Bind our rounds and focus points as metadata. The internal functions bound to our reasoning pipe will
         * be able to pick up on this and execute based on the behavior provided.
         */
        targetPipe.pipeMetadata["reasoningRounds"] = settings.numberOfRounds
        targetPipe.pipeMetadata["focusPoints"] = settings.focusPoints

        //Beware, we have to use .toString to evade a circular reference problem.
        targetPipe.pipeMetadata["injectionMethod"] = settings.reasoningInjector.toString()

        targetPipe.pipeMetadata["reasoningMethod"] = settings.reasoningMethod.toString()

        //Settings to define if we should keep these parts of the system prompt, or discard them.
        targetPipe.pipeMetadata["injectMiddlePrompt"] = settings.injectMiddlePrompt
        targetPipe.pipeMetadata["injectFooterPrompt"] = settings.injectFooterPrompt
        targetPipe.pipeMetadata["reinforceSystemPrompt"] = settings.reinforceSystemPrompt

        targetPipe.setFooterPrompt("""
            
            IMPORTANT: You must fill all json values of your output. This INCLUDES NESTED JSON OBJECTS!! Fully
            complete your json output when producing your response.
        """.trimIndent())

        //Bind now to cache our system prompt we saved as the original system prompt.
        targetPipe.applySystemPrompt()
    }



    /**
     * Generate a bedrock reasoning pipe, and assign the defaults to it. This generates the bedrock multimodal pipe,
     * assigns all reasoning defaults to it, and returns it cast to a regular pipe.
     *
     * @param bedrockConfig [BedrockConfiguration] Configuration settings to quickly build a bedrock pipe.
     * @param reasoningSettings [ReasoningSettings] Settings to configure the reasoning pipe.
     * @param pipeSettings [PipeSettings] Settings to configure the pipe.
     * @param targetPipe [Pipe] The pipe to apply the reasoning defaults to.
     *
     * @return [Pipe] returns the reasoning pipe cast to a regular pipe.
     */
    fun reasonWithBedrock(
        bedrockConfig: BedrockConfiguration,
        reasoningSettings: ReasoningSettings,
        pipeSettings: PipeSettings) : Pipe
    {
        val bedrockPipe = createBedrockPipe(bedrockConfig)
        assignDefaults(reasoningSettings, pipeSettings, bedrockPipe)
        return bedrockPipe
    }



    /**
     * Generate an Ollama reasoning pipe, and assign the defaults to it. This generates the bedrock multimodal pipe,
     * assigns all reasoning defaults to it, and returns it cast to a regular pipe.
     *
     * @param ollamaConfig [Defaults.OllamaConfiguration] Configuration settings to quickly build a bedrock pipe.
     * @param reasoningSettings [ReasoningSettings] Settings to configure the reasoning pipe.
     * @param pipeSettings [PipeSettings] Settings to configure the pipe.
     * @param targetPipe [Pipe] The pipe to apply the reasoning defaults to.
     *
     * @return [Pipe] returns the reasoning pipe cast to a regular pipe.
     */
    fun reasonWithOllama(
        ollamaConfig: OllamaConfiguration,
        reasoningSettings: ReasoningSettings,
        pipeSettings: PipeSettings) : Pipe
    {
        val ollamaPipe = createOllamaPipe(ollamaConfig)
        assignDefaults(reasoningSettings, pipeSettings, ollamaPipe)
        return ollamaPipe
    }

}
