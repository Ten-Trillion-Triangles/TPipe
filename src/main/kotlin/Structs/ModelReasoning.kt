package com.TTT.Structs

import com.TTT.Pipe.BinaryContent
import com.TTT.Pipe.MultimodalContent
import com.TTT.Util.extractJson

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
    var inputData: String = "",
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
            append("${step.reasoningStep}. Looking at ${step.inputData}, I need to consider ${step.considerations}. ")
            append("${step.deductionProcess} This means: ${step.conclusion}. ")
            append("${step.reasoningExplanation} ")
            if (step.connectionToNext.isNotEmpty()) append("${step.connectionToNext} ")
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
            if (step.alternativeAction.isNotEmpty()) {
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
                if (step.needs.isNotEmpty()) append("Requirements: ${step.needs.joinToString(", ")}. ")
                if (step.timeFrame.isNotEmpty()) append("Timeline: ${step.timeFrame}. ")
                if (step.prerequisites.isNotEmpty()) append("Prerequisites: ${step.prerequisites.joinToString(", ")}. ")
            }
            
            if (phase.checkpoints.isNotEmpty()) {
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

/**
 * Extract from the data classes, and fully unravel the content to be usable as model reasoning.
 */
fun extractReasoningContent(method: String, content: MultimodalContent) : String
{
    var resultJson = ""

    when (method)
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

    }

    return resultJson
}
