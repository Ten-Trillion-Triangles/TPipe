package com.TTT.AgentCore.evaluations

import aws.sdk.kotlin.services.bedrockagentcore.model.Evaluator
import aws.sdk.kotlin.services.bedrockagentcore.model.Insight
import aws.sdk.kotlin.services.bedrockagentcore.model.StartBatchEvaluationRequest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AgentCoreEvaluationInsightsTest
{
    @Test
    fun exposesStableBuiltInInsightIdentifiers()
    {
        assertEquals(
            "Builtin.Insight.FailureAnalysis",
            AgentCoreEvaluationInsights.failureAnalysis().insightId
        )
        assertEquals("Builtin.Insight.UserIntent", AgentCoreEvaluationInsights.userIntent().insightId)
        assertEquals(
            "Builtin.Insight.ExecutionSummary",
            AgentCoreEvaluationInsights.executionSummary().insightId
        )
    }

    @Test
    fun rejectsBatchRequestsThatMixEvaluatorsAndInsights()
    {
        val request = StartBatchEvaluationRequest {
            evaluators = listOf(Evaluator { evaluatorId = "evaluator" })
            insights = listOf(Insight { insightId = "insight" })
        }

        assertFailsWith<IllegalArgumentException> { request.requireOneEvaluationMode() }
    }
}
