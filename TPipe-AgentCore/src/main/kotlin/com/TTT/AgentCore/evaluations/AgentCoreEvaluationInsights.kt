package com.TTT.AgentCore.evaluations

import aws.sdk.kotlin.services.bedrockagentcore.model.Insight

/** Stable identifiers for AgentCore built-in Insights. */
object Builtin
{
    /** AWS built-in Insight identifiers. */
    object Insight
    {
        const val FailureAnalysis: String = "Builtin.Insight.FailureAnalysis"
        const val UserIntent: String = "Builtin.Insight.UserIntent"
        const val ExecutionSummary: String = "Builtin.Insight.ExecutionSummary"
    }
}

/** Builders for Insights passed to StartBatchEvaluation. */
object AgentCoreEvaluationInsights
{
    /** Build the failure-analysis Insight request. */
    fun failureAnalysis(): Insight = Insight { insightId = Builtin.Insight.FailureAnalysis }

    /** Build the user-intent Insight request. */
    fun userIntent(): Insight = Insight { insightId = Builtin.Insight.UserIntent }

    /** Build the execution-summary Insight request. */
    fun executionSummary(): Insight = Insight { insightId = Builtin.Insight.ExecutionSummary }
}
