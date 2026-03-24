package com.TTT.Structs

import com.TTT.Pipe.BinaryContent
import com.TTT.Pipe.MultimodalContent
import com.TTT.Context.ConverseHistory
import com.TTT.Context.ConverseRole
import com.TTT.Util.extractJson
import com.TTT.Util.deserialize

//=======================================Explicit chain of thought======================================================

/**
 * Data class to force llm's to abide by explicit chain of through reasoning.
 */
@kotlinx.serialization.Serializable
data class CoreAnalysis(
    var analysisSubject: String = "",
    var identifiedComponents: List<String> = listOf(),
    var underlyingIssues: List<String> = listOf(),
    var knownFacts: List<String> = listOf()
)

@kotlinx.serialization.Serializable
data class LogicalStep(
    var stepId: Int = 0,
    var reasoningStep: String = "",
    var contextualFocus: String = "",
    var considerations: String = "",
    var deductionProcess: String = "",
    var conclusion: String = "",
    var reasoningExplanation: String = "",
    var connectionToNext: String = ""
)

@kotlinx.serialization.Serializable
data class StepByStepProcess(
    var totalSteps: Int = 0,
    var steps: List<LogicalStep> = listOf(),
    var dependencies: List<String> = listOf(),
    var verificationPoints: List<String> = listOf()
)

//Unravel this back into a string.
@kotlinx.serialization.Serializable
data class ExplicitReasoningDetailed(
    var coreAnalysis: CoreAnalysis = CoreAnalysis(),
    var logicalBreakdown: List<String> = listOf(),
    var sequentialReasoning: StepByStepProcess = StepByStepProcess(),
    var recommendedSteps: String = ""
) {
    fun unravel(): String = buildString {
        append("Let me think through this: ${coreAnalysis.analysisSubject}. ")
        append("The key components I need to consider are: ${coreAnalysis.identifiedComponents.joinToString(", ")}. ")
        append("The main challenges are: ${coreAnalysis.underlyingIssues.joinToString(", ")}. ")
        append("What I know: ${coreAnalysis.knownFacts.joinToString(", ")}. ")
        
        logicalBreakdown.forEach { append("$it ") }
        
        append("Working through this step by step: ")
        sequentialReasoning.steps.forEach { step ->
            append("${step.reasoningStep}. Looking at ${step.contextualFocus}, I need to consider ${step.considerations}. ")
            append("${step.deductionProcess} This means: ${step.conclusion}. ")
            append("${step.reasoningExplanation} ")
            if(step.connectionToNext.isNotEmpty()) append("${step.connectionToNext} ")
        }
        
        append("Based on this reasoning, the next steps should be: ${recommendedSteps}")
    }
}

//========================================Structured chain of thought===================================================

@kotlinx.serialization.Serializable
data class ComponentIdentification(
    var taskElements: List<String> = listOf(),
    var informationAndConstraints: List<String>  = listOf(),
    var whatNeedsToBeSolved: String = ""
)

@kotlinx.serialization.Serializable
data class SolutionDecomposition(
    var subProblems: List<String> = listOf(),
    var sequenceOfOperations: List<String> = listOf(),
    var dependenciesBetweenSteps: List<String> = listOf()
)

@kotlinx.serialization.Serializable
data class SubProblemApproach(
    var subProblemName: String = "",
    var recommendedStrategy: String = ""
)

@kotlinx.serialization.Serializable
data class SystemicExecution(
    var subProblemApproaches: List<SubProblemApproach> = listOf(),
    var intermediateSteps: List<String> = listOf(),
    var purposeOfEachStep: List<String> = listOf()
)

@kotlinx.serialization.Serializable
data class ProcessFocusedResult(
    var proposedApproach: String = "",
    var approachValidation: String = "",
    var reasoningConclusion: String = ""
)

//Unravel this back into a string.
@kotlinx.serialization.Serializable
data class StructuredCot(
    var componentIdentification: ComponentIdentification = ComponentIdentification(),
    var solutionDecomposition: SolutionDecomposition = SolutionDecomposition(),
    var systematicExecution: SystemicExecution = SystemicExecution(),
    var reasoningSynthesis: ProcessFocusedResult = ProcessFocusedResult()
) {
    fun unravel(): String = buildString {
        append("I need to break this down systematically. ")
        append("The key elements I'm working with are: ${componentIdentification.taskElements.joinToString(", ")}. ")
        append("My constraints and available information: ${componentIdentification.informationAndConstraints.joinToString(", ")}. ")
        append("What I'm ultimately trying to solve: ${componentIdentification.whatNeedsToBeSolved}. ")
        
        append("I can decompose this into these sub-problems: ${solutionDecomposition.subProblems.joinToString(", ")}. ")
        append("The sequence of operations will be: ${solutionDecomposition.sequenceOfOperations.joinToString(", then ")}. ")
        append("Dependencies between steps: ${solutionDecomposition.dependenciesBetweenSteps.joinToString(", ")}. ")
        
        append("Now executing systematically: ")
        systematicExecution.subProblemApproaches.forEach { approach ->
            append("For ${approach.subProblemName}: ${approach.recommendedStrategy}. ")
        }
        systematicExecution.intermediateSteps.forEachIndexed { index, step ->
            val purpose = systematicExecution.purposeOfEachStep.getOrNull(index) ?: ""
            append("$step ($purpose). ")
        }
        
        append("My proposed approach: ${reasoningSynthesis.proposedApproach}. ")
        append("Approach validation: ${reasoningSynthesis.approachValidation}. ")
        append(reasoningSynthesis.reasoningConclusion)
    }
}

//==========================================Process focused chain of thought============================================

@kotlinx.serialization.Serializable
data class ProcessSteps(
    var stepName: String = "",
    var justification: String = "",
    var alternativeAction: String = ""
)

//Unravel this back into a string.
@kotlinx.serialization.Serializable
data class ProcessFocused(
    var question: String = "",
    var knownData: String  = "",
    var approach: String = "",
    var workThroughEachStep: List<ProcessSteps> = listOf(),
    var findings: String = ""
) {
    fun unravel(): String = buildString {
        append("The question I'm addressing: $question. ")
        append("What I know: $knownData. ")
        append("My approach will be: $approach. ")
        
        append("Working through this step by step: ")
        workThroughEachStep.forEach { step ->
            append("${step.stepName}. ${step.justification}. ")
            if(step.alternativeAction.isNotEmpty())
            {
                append("Alternative consideration: ${step.alternativeAction}. ")
            }
        }
        
        append("My findings: $findings")
    }
}

//==========================================Best idea chain of thought==================================================

@kotlinx.serialization.Serializable
data class SolutionApproach(
    var approachName: String = "",
    var description: String = "",
    var strengths: List<String> = listOf(),
    var weaknesses: List<String> = listOf(),
    var successPotential: Int = 0 // 1-10 scale
)

@kotlinx.serialization.Serializable
data class ComparativeAnalysis(
    var consideredApproaches: List<SolutionApproach> = listOf(),
    var selectionCriteria: List<String> = listOf(),
    var tradeOffs: List<String> = listOf(),
    var eliminatedApproaches: List<String> = listOf()
)

@kotlinx.serialization.Serializable
data class BestIdeaJustification(
    var whyOptimal: String = "",
    var expectedOutcomes: List<String> = listOf(),
    var riskMitigation: List<String> = listOf(),
    var alternativesConsidered: String = ""
)

@kotlinx.serialization.Serializable
data class RefinedSolution(
    var coreConcept: String = "",
    var keyFeatures: List<String> = listOf(),
    var implementationSteps: List<String> = listOf(),
    var successMetrics: List<String> = listOf()
)

//Unravel this back into a string.
@kotlinx.serialization.Serializable
data class BestIdeaResponse(
    var problemAnalysis: String = "", // Multi-angle analysis
    var comparativeAnalysis: ComparativeAnalysis = ComparativeAnalysis(),
    var selectedApproach: SolutionApproach = SolutionApproach(),
    var justification: BestIdeaJustification = BestIdeaJustification(),
    var refinedSolution: RefinedSolution = RefinedSolution()
) {
    fun unravel(): String = buildString {
        append("$problemAnalysis ")
        
        append("I've considered several approaches: ")
        comparativeAnalysis.consideredApproaches.forEach { approach ->
            append("${approach.approachName} - ${approach.description}. ")
            append("Strengths: ${approach.strengths.joinToString(", ")}. ")
            append("Weaknesses: ${approach.weaknesses.joinToString(", ")}. ")
            append("Success potential: ${approach.successPotential}/10. ")
        }
        
        append("My selection criteria: ${comparativeAnalysis.selectionCriteria.joinToString(", ")}. ")
        append("Key tradeoffs: ${comparativeAnalysis.tradeOffs.joinToString(", ")}. ")
        append("I eliminated these approaches: ${comparativeAnalysis.eliminatedApproaches.joinToString(", ")}. ")
        
        append("The best approach is ${selectedApproach.approachName}: ${selectedApproach.description}. ")
        append("Why this is optimal: ${justification.whyOptimal}. ")
        append("Expected outcomes: ${justification.expectedOutcomes.joinToString(", ")}. ")
        append("Risk mitigation: ${justification.riskMitigation.joinToString(", ")}. ")
        
        append("The refined solution centers on: ${refinedSolution.coreConcept}. ")
        append("Key features: ${refinedSolution.keyFeatures.joinToString(", ")}. ")
        append("Implementation: ${refinedSolution.implementationSteps.joinToString(", then ")}. ")
        append("Success will be measured by: ${refinedSolution.successMetrics.joinToString(", ")}")
    }
}

//==========================================Comprehensive plan chain of thought=========================================

@kotlinx.serialization.Serializable
data class TaskAnalysis(
    var whatNeedsSolving: String = "",
    var limitations: List<String> = listOf(),
    var desiredOutcomes: List<String> = listOf(),
    var importantFactors: List<String> = listOf()
)

@kotlinx.serialization.Serializable
data class ExecutionStep(
    var stepDescription: String = "",
    var needs: List<String> = listOf(),
    var timeFrame: String = "",
    var prerequisites: List<String> = listOf()
)

@kotlinx.serialization.Serializable
data class RiskPlan(
    var potentialIssue: String = "",
    var likelihood: String = "",
    var impact: String = "",
    var mitigation: String = "",
    var backupPlan: String = ""
)

@kotlinx.serialization.Serializable
data class Phase(
    var phaseTitle: String = "",
    var purpose: String = "",
    var steps: List<ExecutionStep> = listOf(),
    var checkpoints: List<String> = listOf(),
    var phaseRisks: List<RiskPlan> = listOf()
)

//Unravel this back into a string.
@kotlinx.serialization.Serializable
data class MultiPhasePlan(
    var analysis: TaskAnalysis = TaskAnalysis(),
    var phases: List<Phase> = listOf(),
    var howToMeasureSuccess: List<String> = listOf(),
    var totalDuration: String = ""
) {
    fun unravel(): String = buildString {
        append("Here's what needs solving: ${analysis.whatNeedsSolving}. ")
        append("I'm working within these limitations: ${analysis.limitations.joinToString(", ")}. ")
        append("The desired outcomes are: ${analysis.desiredOutcomes.joinToString(", ")}. ")
        append("Important factors to consider: ${analysis.importantFactors.joinToString(", ")}. ")
        
        append("My plan consists of ${phases.size} phases over $totalDuration: ")
        
        phases.forEachIndexed { index, phase ->
            append("Phase ${index + 1}: ${phase.phaseTitle}. ${phase.purpose}. ")
            
            phase.steps.forEach { step ->
                append("${step.stepDescription}. ")
                if(step.needs.isNotEmpty()) append("Requirements: ${step.needs.joinToString(", ")}. ")
                if(step.timeFrame.isNotEmpty()) append("Timeline: ${step.timeFrame}. ")
                if(step.prerequisites.isNotEmpty()) append("Prerequisites: ${step.prerequisites.joinToString(", ")}. ")
            }
            
            if(phase.checkpoints.isNotEmpty())
            {
                append("Checkpoints: ${phase.checkpoints.joinToString(", ")}. ")
            }
            
            phase.phaseRisks.forEach { risk ->
                append("Risk: ${risk.potentialIssue} (${risk.likelihood} likelihood, ${risk.impact} impact). ")
                append("Mitigation: ${risk.mitigation}. Backup: ${risk.backupPlan}. ")
            }
        }
        
        append("Success will be measured by: ${howToMeasureSuccess.joinToString(", ")}")
    }
}

//==========================================Role play chain of thought==================================================


@kotlinx.serialization.Serializable
data class CharacterPerspective(
    var characterBackground: String = "",
    var expertiseDomain: String = "",
    var worldview: String = "",
    var typicalTerminology: List<String> = listOf()
)

@kotlinx.serialization.Serializable
data class CharacterAnalysis(
    var problemInterpretation: String = "", // How the character sees the problem
    var characterInsights: List<String> = listOf(), // Unique insights from character's perspective
    var methodology: String = "" // Character's typical approach to problems
)

@kotlinx.serialization.Serializable
data class CharacterReasoning(
    var thoughtProcess: List<String> = listOf(), // In-character thinking
    var appliedExpertise: String = "", // How character's skills are used
    var reasoningStyle: String = "" // Character's unique reasoning patterns
)

@kotlinx.serialization.Serializable
data class CharacterSolution(
    var proposedApproach: String = "",
    var characterRationale: String = "", // Why character thinks this works
    var uniqueAdvantages: List<String> = listOf() // Benefits of character's perspective
)

//Unravel this back into a string.
@kotlinx.serialization.Serializable
data class MethodActorResponse(
    var characterProfile: CharacterPerspective = CharacterPerspective(),
    var problemView: CharacterAnalysis = CharacterAnalysis(),
    var inCharacterThinking: CharacterReasoning = CharacterReasoning(),
    var characterSolution: CharacterSolution = CharacterSolution(),
    var signatureStyle: String = "" // Summary of character's voice and approach
) {
    fun unravel(): String = buildString {
        append("Drawing from my background in ${characterProfile.expertiseDomain}, ")
        append("${characterProfile.characterBackground}. ")
        append("My worldview shapes how I see this: ${characterProfile.worldview}. ")
        
        append("${problemView.problemInterpretation}. ")
        problemView.characterInsights.forEach { insight ->
            append("$insight ")
        }
        append("My approach: ${problemView.methodology}. ")
        
        inCharacterThinking.thoughtProcess.forEach { thought ->
            append("$thought ")
        }
        append("Applying my expertise: ${inCharacterThinking.appliedExpertise}. ")
        append("${inCharacterThinking.reasoningStyle}. ")
        
        append("${characterSolution.proposedApproach}. ")
        append("${characterSolution.characterRationale}. ")
        characterSolution.uniqueAdvantages.forEach { advantage ->
            append("$advantage ")
        }
        
        append(signatureStyle)
    }
}

//==========================================Chain of Draft reasoning==================================================

/**
 * Represents a single step in Chain of Draft reasoning methodology.
 *
 * @param stepNumber Sequential number of this reasoning step.
 * @param draftContent Brief reasoning content with maximum 5-word constraint.
 * @param operation Mathematical or logical operation being performed.
 * @param result Intermediate result from this step.
 */
@kotlinx.serialization.Serializable
data class DraftStep(
    var stepNumber: Int = 0,
    var draftContent: String = "", // Max 5 words constraint
    var operation: String = "",    // Mathematical/logical operation
    var result: String = ""        // Intermediate result
)

/**
 * Response structure for Chain of Draft reasoning methodology with minimal verbosity constraints.
 *
 * @param problemAnalysis Brief problem statement with maximum 5-word constraint.
 * @param draftSteps List of constrained reasoning steps following Chain of Draft methodology.
 * @param finalCalculation Final operation with maximum 5-word constraint.
 * @param answer Final answer to the problem.
 */
@kotlinx.serialization.Serializable
data class ChainOfDraftResponse(
    var problemAnalysis: String = "",           // Brief problem statement (5 words max)
    var draftSteps: List<DraftStep> = listOf(), // Constrained reasoning steps
    var finalCalculation: String = "",          // Final operation (5 words max)
    var answer: String = ""                     // Final answer
)
{
    fun unravel(): String = buildString {
        append("Looking at this problem: $problemAnalysis. ")
        
        draftSteps.forEach { step ->
            append("${step.draftContent}. ")
            if(step.operation.isNotEmpty())
            {
                append("${step.operation}. ")
            }
            if(step.result.isNotEmpty())
            {
                append("This gives me: ${step.result}. ")
            }
        }
        
        if(finalCalculation.isNotEmpty())
        {
            append("Final reasoning: $finalCalculation. ")
        }
        
        append("Therefore: $answer")
    }
}

//==========================================Semantic Decompression reasoning==========================================

/**
 * The reasoning pipe's analysis of the legend mappings present in the compressed prompt.
 *
 * @param codesFound The 2-character legend codes discovered in the compressed text.
 * @param mappings Human-readable summary of each code-to-phrase mapping.
 */
@kotlinx.serialization.Serializable
data class LegendAnalysis(
    var codesFound: List<String> = listOf(),
    var mappings: List<String> = listOf()
)

/**
 * Identification of the task the parent pipe is being asked to perform, extracted from the compressed content.
 *
 * @param taskDescription What the parent pipe is being asked to do.
 * @param taskType The category of task (e.g., summarization, analysis, generation, decompression).
 * @param requiresFullDecompression Whether the task demands complete restoration of the original text.
 */
@kotlinx.serialization.Serializable
data class TaskIdentification(
    var taskDescription: String = "",
    var taskType: String = "",
    var requiresFullDecompression: Boolean = false
)

/**
 * The reasoning pipe's strategy for how it approached decompression of the compressed content.
 *
 * @param approach Description of the decompression approach taken.
 * @param legendExpansionNotes Notes on how legend codes were expanded.
 * @param inferenceNotes Notes on how omitted function words and syntax were restored.
 */
@kotlinx.serialization.Serializable
data class DecompressionStrategy(
    var approach: String = "",
    var legendExpansionNotes: String = "",
    var inferenceNotes: String = ""
)

/**
 * Response structure for the SemanticDecompression reasoning method.
 *
 * @param legendAnalysis The pipe's understanding of the legend mappings.
 * @param taskIdentification What the parent pipe is being asked to do.
 * @param keyDataPoints Critical information extracted and decompressed from the prompt.
 * @param decompressionStrategy How the pipe decided what to decompress and why.
 * @param restoredContent The decompressed/restored text (partial or full depending on the task).
 */
@kotlinx.serialization.Serializable
data class SemanticDecompressionResponse(
    var legendAnalysis: LegendAnalysis = LegendAnalysis(),
    var taskIdentification: TaskIdentification = TaskIdentification(),
    var keyDataPoints: List<String> = listOf(),
    var decompressionStrategy: DecompressionStrategy = DecompressionStrategy(),
    var restoredContent: String = ""
)
{
    fun unravel(): String = buildString {
        if(legendAnalysis.mappings.isNotEmpty())
        {
            append("Legend mappings found: ${legendAnalysis.mappings.joinToString(", ")}. ")
        }

        append("Task identified: ${taskIdentification.taskDescription}. ")
        append("Task type: ${taskIdentification.taskType}. ")

        if(taskIdentification.requiresFullDecompression)
        {
            append("This task requires full decompression of the original content. ")
        }

        if(keyDataPoints.isNotEmpty())
        {
            append("Key data points extracted: ")
            keyDataPoints.forEach { append("$it. ") }
        }

        append("Decompression approach: ${decompressionStrategy.approach}. ")

        if(decompressionStrategy.legendExpansionNotes.isNotEmpty())
        {
            append("Legend expansion: ${decompressionStrategy.legendExpansionNotes}. ")
        }

        if(decompressionStrategy.inferenceNotes.isNotEmpty())
        {
            append("Inference notes: ${decompressionStrategy.inferenceNotes}. ")
        }

        if(restoredContent.isNotEmpty())
        {
            append("Restored content: $restoredContent")
        }
    }
}

/**
 * Extract from the data classes, and fully unravel the content to be usable as model reasoning.
 */
fun extractReasoningContent(method: String, content: MultimodalContent) : String
{
    var resultJson = ""

    when(method)
    {
        "BestIdea" -> {
            val asObject = extractJson<BestIdeaResponse>(content.text) ?: BestIdeaResponse()
            resultJson = asObject.unravel()
        }

        "ComprehensivePlan" -> {
            val asObject = extractJson<MultiPhasePlan>(content.text) ?: MultiPhasePlan()
            resultJson = asObject.unravel()
        }

        "RolePlay" -> {
            val asObject = extractJson<MethodActorResponse>(content.text) ?: MethodActorResponse()
            resultJson = asObject.unravel()
        }

        "ExplicitCot" -> {
            val asObject = extractJson<ExplicitReasoningDetailed>(content.text) ?: ExplicitReasoningDetailed()
            resultJson = asObject.unravel()
        }

        "StructuredCot" -> {
            val asObject = extractJson<StructuredCot>(content.text) ?: StructuredCot()
            resultJson = asObject.unravel()
        }

        "processFocusedCot" -> {
            val asObject = extractJson<ProcessFocused>(content.text) ?: ProcessFocused()
            resultJson = asObject.unravel()
        }

        "ChainOfDraft" ->
        {
            val asObject = extractJson<ChainOfDraftResponse>(content.text) ?: ChainOfDraftResponse()
            resultJson = asObject.unravel()
        }

        "SemanticDecompression" ->
        {
            val asObject = extractJson<SemanticDecompressionResponse>(content.text) ?: SemanticDecompressionResponse()
            resultJson = asObject.unravel()
        }

    }

    return resultJson
}

/**
 * Normalize a reasoning payload into the visible thought stream used by the parent pipe.
 *
 * Multi-round reasoning may carry the transport history as a [ConverseHistory] wrapper. This helper unwraps
 * that transport and then applies the same schema-specific unraveling logic that single-round reasoning uses.
 * If the payload is already plain text, it is returned as-is instead of being converted into a scaffold.
 */
fun extractReasoningStream(method: String, content: MultimodalContent) : String
{
    val normalizedText = unwrapReasoningHistoryText(content.text)
    if(normalizedText.isBlank()) return normalizedText

    val hasExpectedSchema = when(method)
    {
        "BestIdea" -> normalizedText.contains("\"problemAnalysis\"")
        "ComprehensivePlan" -> normalizedText.contains("\"taskAnalysis\"")
        "RolePlay" -> normalizedText.contains("\"characterAnalysis\"")
        "ExplicitCot" -> normalizedText.contains("\"coreAnalysis\"")
        "StructuredCot" -> normalizedText.contains("\"componentIdentification\"")
        "processFocusedCot" -> normalizedText.contains("\"question\"")
        "ChainOfDraft" -> normalizedText.contains("\"problemAnalysis\"")
        "SemanticDecompression" -> normalizedText.contains("\"legendAnalysis\"")
        else -> false
    }

    if(!hasExpectedSchema) return normalizedText

    return when(method)
    {
        "BestIdea" -> extractJson<BestIdeaResponse>(normalizedText)?.unravel() ?: normalizedText
        "ComprehensivePlan" -> extractJson<MultiPhasePlan>(normalizedText)?.unravel() ?: normalizedText
        "RolePlay" -> extractJson<MethodActorResponse>(normalizedText)?.unravel() ?: normalizedText
        "ExplicitCot" -> extractJson<ExplicitReasoningDetailed>(normalizedText)?.unravel() ?: normalizedText
        "StructuredCot" -> extractJson<StructuredCot>(normalizedText)?.unravel() ?: normalizedText
        "processFocusedCot" -> extractJson<ProcessFocused>(normalizedText)?.unravel() ?: normalizedText
        "ChainOfDraft" -> extractJson<ChainOfDraftResponse>(normalizedText)?.unravel() ?: normalizedText
        "SemanticDecompression" -> extractJson<SemanticDecompressionResponse>(normalizedText)?.unravel() ?: normalizedText
        else -> normalizedText
    }
}

private fun unwrapReasoningHistoryText(text: String): String
{
    val history = deserialize<ConverseHistory>(text) ?: return text
    val lastVisibleTurn = history.history.lastOrNull {
        it.role == ConverseRole.agent || it.role == ConverseRole.assistant || it.role == ConverseRole.user
    } ?: return text

    return unwrapReasoningHistoryText(lastVisibleTurn.content.text)
}
