package com.TTT.Structs

//=======================================Explicit chain of thought======================================================

/**
 * Data class to force llm's to abide by explicit chain of through reasoning.
 */
@kotlinx.serialization.Serializable
data class ExplicitCot(
    var analysis: String = "",
    var logicalSteps: List<String> = listOf()
)

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
data class SubProblemSolution(
    var subProblemName: String = "",
    var solution: String = ""
)

@kotlinx.serialization.Serializable
data class SystemicExecution(
    var subProblemSolutions: List<SubProblemSolution> = listOf(),
    var intermediateSteps: List<String> = listOf(),
    var purposeOfEachStep: List<String> = listOf()
)

@kotlinx.serialization.Serializable
data class ProcessFocusedResult(
    var combinedResult: String = "",
    var verifyResult: String = "",
    var finalSummary: String = ""
)

/**
 * Data class to coerce the llm into staying inside the constraints of structured chain of thought.
 * Should force the llm to abide by the steps weather it like it or not.
 */
@kotlinx.serialization.Serializable
data class StructuredCot(
    var componentIdentification: ComponentIdentification = ComponentIdentification(),
    var solutionDecomposition: SolutionDecomposition = SolutionDecomposition(),
    var systematicExecution: SystemicExecution = SystemicExecution(),
    var reasoningSynthesis: ProcessFocusedResult = ProcessFocusedResult()
)

//==========================================Process focused chain of thought============================================

@kotlinx.serialization.Serializable
data class ProcessSteps(
    var stepName: String = "",
    var justification: String = "",
    var alternativeAction: String = ""
)

@kotlinx.serialization.Serializable
data class ProcessFocused(
    var question: String = "",
    var knownData: String  = "",
    var approach: String = "",
    var workThroughEachStep: List<ProcessSteps> = listOf(),
    var findings: String = ""
)

//==========================================Best idea chain of thought==================================================



