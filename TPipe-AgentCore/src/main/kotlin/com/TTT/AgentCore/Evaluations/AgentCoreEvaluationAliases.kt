package com.TTT.AgentCore.Evaluations

import com.TTT.AgentCore.evaluations.simulate as simulateInternal

/** Compatibility aliases for the capitalized package layout in the design. */
typealias AgentCoreEvaluationClient = com.TTT.AgentCore.evaluations.AgentCoreEvaluationClient
typealias AgentCoreEvaluationAdmin = com.TTT.AgentCore.evaluations.AgentCoreEvaluationAdmin
typealias AgentCoreEvaluationPoller = com.TTT.AgentCore.evaluations.AgentCoreEvaluationPoller
typealias AgentCoreEvaluationTraceAdapter = com.TTT.AgentCore.evaluations.AgentCoreEvaluationTraceAdapter
typealias AgentCoreDatasetRunner = com.TTT.AgentCore.evaluations.AgentCoreDatasetRunner
typealias AgentCoreDatasetExecutor = com.TTT.AgentCore.evaluations.AgentCoreDatasetExecutor
typealias AgentCoreDatasetExecution = com.TTT.AgentCore.evaluations.AgentCoreDatasetExecution
typealias AgentCoreDatasetRunResult = com.TTT.AgentCore.evaluations.AgentCoreDatasetRunResult
typealias AgentCoreDatasetCaseResult = com.TTT.AgentCore.evaluations.AgentCoreDatasetCaseResult
typealias AgentCoreEvaluationInsights = com.TTT.AgentCore.evaluations.AgentCoreEvaluationInsights
typealias AgentCoreUserSimulator = com.TTT.AgentCore.evaluations.AgentCoreUserSimulator
typealias AgentCoreSimulationContext = com.TTT.AgentCore.evaluations.AgentCoreSimulationContext
typealias AgentCoreSimulationTurn = com.TTT.AgentCore.evaluations.AgentCoreSimulationTurn
typealias AgentCoreSimulationResult = com.TTT.AgentCore.evaluations.AgentCoreSimulationResult

/** Compatibility extension for provider-neutral user simulation. */
suspend fun AgentCoreUserSimulator.simulate(
    scenario: aws.smithy.kotlin.runtime.content.Document,
    maxTurns: Int = 20
): AgentCoreSimulationResult =
    this.simulateInternal(scenario, maxTurns)
