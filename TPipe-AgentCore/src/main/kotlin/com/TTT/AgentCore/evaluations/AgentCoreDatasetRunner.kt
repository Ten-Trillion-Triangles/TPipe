package com.TTT.AgentCore.evaluations

import aws.sdk.kotlin.services.bedrockagentcore.model.EvaluateResponse
import aws.smithy.kotlin.runtime.content.Document
import kotlinx.coroutines.CancellationException

/** Result of executing one dataset scenario before evaluation. */
data class AgentCoreDatasetExecution(
    val traceId: String? = null,
    val sessionId: String? = null,
    val output: Document? = null
)

/** Executes one dataset scenario using any caller-owned TPipe transport. */
fun interface AgentCoreDatasetExecutor
{
    /** Execute [scenario] and return trace/session correlation identifiers. */
    suspend fun execute(scenario: Document): AgentCoreDatasetExecution
}

/** Result of one dataset case, including an isolated failure when present. */
data class AgentCoreDatasetCaseResult(
    val index: Int,
    val scenario: Document,
    val execution: AgentCoreDatasetExecution? = null,
    val evaluation: EvaluateResponse? = null,
    val failure: Throwable? = null
)

/** Aggregate result for a dataset run. */
data class AgentCoreDatasetRunResult(val cases: List<AgentCoreDatasetCaseResult>)
{
    /** Whether every case completed execution and evaluation. */
    val succeeded: Boolean get() = cases.all { it.failure == null && it.evaluation != null }
}

/**
 * Provider-neutral orchestration for AgentCore dataset evaluation.
 *
 * Dataset evaluation is preview functionality and is therefore explicit and
 * caller-injected. The runner never constructs a model provider or buffers a
 * provider-specific trace format.
 */
class AgentCoreDatasetRunner(
    private val loadExamples: suspend () -> List<Document>,
    private val executor: AgentCoreDatasetExecutor,
    private val waitForTelemetry: suspend (AgentCoreDatasetExecution) -> Unit,
    private val evaluate: suspend (Document, AgentCoreDatasetExecution) -> EvaluateResponse
)
{
    /** Execute and evaluate every dataset example, preserving per-case failures. */
    suspend fun run(): AgentCoreDatasetRunResult
    {
        val results = loadExamples().mapIndexed { index, scenario ->
            var execution: AgentCoreDatasetExecution? = null
            try
            {
                execution = executor.execute(scenario)
                waitForTelemetry(execution)
                AgentCoreDatasetCaseResult(index, scenario, execution, evaluate(scenario, execution))
            }
            catch(exception: CancellationException)
            {
                throw exception
            }
            catch(exception: Throwable)
            {
                AgentCoreDatasetCaseResult(index, scenario, execution = execution, failure = exception)
            }
        }
        return AgentCoreDatasetRunResult(results)
    }
}
