package com.TTT.AgentCore.evaluations

import aws.sdk.kotlin.services.bedrockagentcore.model.EvaluateResponse
import aws.smithy.kotlin.runtime.content.Document
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AgentCoreDatasetRunnerTest
{
    @Test
    fun preservesAnIndividualScenarioFailureAndContinuesTheDataset()
    {
        val scenarios = listOf(Document.String("first"), Document.String("second"))
        val result = runBlocking {
            AgentCoreDatasetRunner(
                loadExamples = { scenarios },
                executor = AgentCoreDatasetExecutor { scenario ->
                    if(scenario == scenarios.first()) error("first failed")
                    AgentCoreDatasetExecution(traceId = "trace-2", sessionId = "session-2")
                },
                waitForTelemetry = {},
                evaluate = { _, _ -> EvaluateResponse { evaluationResults = emptyList() } }
            ).run()
        }

        assertEquals(2, result.cases.size)
        assertEquals("first failed", result.cases[0].failure?.message)
        assertTrue(result.cases[1].failure == null)
        assertFalse(result.succeeded)
    }

    @Test
    fun preservesExecutionCorrelationWhenTelemetryOrEvaluationFails()
    {
        val execution = AgentCoreDatasetExecution(traceId = "trace-1", sessionId = "session-1")
        val result = runBlocking {
            AgentCoreDatasetRunner(
                loadExamples = { listOf(Document.String("scenario")) },
                executor = AgentCoreDatasetExecutor { execution },
                waitForTelemetry = { error("telemetry unavailable") },
                evaluate = { _, _ -> EvaluateResponse {} }
            ).run()
        }

        assertEquals(execution, result.cases.single().execution)
        assertEquals("telemetry unavailable", result.cases.single().failure?.message)
    }
}
