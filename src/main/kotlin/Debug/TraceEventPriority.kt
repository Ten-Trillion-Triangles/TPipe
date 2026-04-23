package com.TTT.Debug

/**
 * Coarse trace visibility buckets used by the event filter.
 */
enum class TraceEventPriority {
    CRITICAL,    // MINIMAL level events
    STANDARD,    // NORMAL level events  
    DETAILED,    // VERBOSE level events
    INTERNAL     // DEBUG level events
}

/**
 * Maps trace event types to the visibility level expected by the trace viewer.
 */
object EventPriorityMapper
{
    /**
     * Map a trace event to the display priority used by the trace filters.
     *
     * Harness lifecycle markers stay visible at normal trace detail levels, while phase plumbing and internal
     * bookkeeping are progressively gated behind more verbose views.
     *
     * @param eventType The event to classify.
     * @return The priority bucket that controls trace visibility.
     */
    fun getPriority(eventType: TraceEventType): TraceEventPriority {
        return when(eventType) {
            // Existing CRITICAL events
            TraceEventType.PIPE_FAILURE,
            TraceEventType.API_CALL_FAILURE,
            TraceEventType.PIPELINE_TERMINATION,
            // New Manifold / Junction CRITICAL events
            TraceEventType.MANIFOLD_FAILURE,
            TraceEventType.P2P_COMMUNICATION_FAILURE,
            TraceEventType.AGENT_REQUEST_INVALID,
            TraceEventType.JUNCTION_FAILURE,
            TraceEventType.JUNCTION_WORKFLOW_FAILURE,
            TraceEventType.DISTRIBUTION_GRID_FAILURE,
            // New Splitter CRITICAL events
            TraceEventType.SPLITTER_FAILURE -> TraceEventPriority.CRITICAL
            
            // Existing STANDARD events
            TraceEventType.PIPE_START,
            TraceEventType.PIPE_SUCCESS,
            TraceEventType.API_CALL_START,
            TraceEventType.API_CALL_SUCCESS,
            // New Manifold / Junction STANDARD events
            //
            // Harness-level lifecycle markers stay at STANDARD priority so they appear in normal trace output
            // without drowning out detailed phase-by-phase events.
            TraceEventType.MANIFOLD_START,
            TraceEventType.MANIFOLD_END,
            TraceEventType.MANIFOLD_SUCCESS,
            TraceEventType.MANIFOLD_INIT_CHECK,
            TraceEventType.JUNCTION_START,
            TraceEventType.JUNCTION_END,
            TraceEventType.JUNCTION_SUCCESS,
            TraceEventType.JUNCTION_PAUSE,
            TraceEventType.JUNCTION_RESUME,
            TraceEventType.JUNCTION_WORKFLOW_START,
            TraceEventType.JUNCTION_WORKFLOW_END,
            TraceEventType.JUNCTION_WORKFLOW_SUCCESS,
            TraceEventType.JUNCTION_HANDOFF,
            TraceEventType.DISTRIBUTION_GRID_INIT,
            TraceEventType.DISTRIBUTION_GRID_PAUSE,
            TraceEventType.DISTRIBUTION_GRID_RESUME,
            TraceEventType.DISTRIBUTION_GRID_RUNTIME_RESET,
            TraceEventType.DISTRIBUTION_GRID_START,
            TraceEventType.DISTRIBUTION_GRID_END,
            TraceEventType.DISTRIBUTION_GRID_SUCCESS,
            TraceEventType.DISTRIBUTION_GRID_PEER_HANDOFF,
            TraceEventType.DISTRIBUTION_GRID_PEER_RESPONSE,
            TraceEventType.DISTRIBUTION_GRID_REGISTRY_PROBE,
            TraceEventType.DISTRIBUTION_GRID_REGISTRY_REGISTRATION,
            TraceEventType.DISTRIBUTION_GRID_REGISTRY_LEASE_RENEWAL,
            TraceEventType.DISTRIBUTION_GRID_REGISTRY_QUERY,
            TraceEventType.DISTRIBUTION_GRID_PUBLIC_LISTING,
            TraceEventType.DISTRIBUTION_GRID_PUBLIC_LISTING_AUTO_RENEW,
            TraceEventType.DISTRIBUTION_GRID_ROUTER_DECISION,
            TraceEventType.DISTRIBUTION_GRID_LOCAL_WORKER_DISPATCH,
            TraceEventType.DISTRIBUTION_GRID_LOCAL_WORKER_RESPONSE,
            TraceEventType.DISTRIBUTION_GRID_RETURN_ROUTING,
            TraceEventType.DISTRIBUTION_GRID_SESSION_HANDSHAKE,
            TraceEventType.MANAGER_DECISION,
            TraceEventType.AGENT_DISPATCH,
            TraceEventType.AGENT_RESPONSE,
            TraceEventType.P2P_REQUEST_START,
            TraceEventType.P2P_REQUEST_SUCCESS,
            TraceEventType.P2P_REQUEST_FAILURE,
            // New Splitter STANDARD events
            TraceEventType.SPLITTER_START,
            TraceEventType.SPLITTER_END,
            TraceEventType.SPLITTER_SUCCESS,
            TraceEventType.SPLITTER_PIPELINE_COMPLETION -> TraceEventPriority.STANDARD
            
            // Existing DETAILED events
            //
            // Phase transitions, vote tallies, and request plumbing are intentionally more verbose than the
            // top-level lifecycle markers because they are the events you need when diagnosing a broken harness.
            TraceEventType.POST_GENERATE,
            TraceEventType.VALIDATION_START,
            TraceEventType.VALIDATION_SUCCESS,
            TraceEventType.VALIDATION_FAILURE,
            TraceEventType.TRANSFORMATION_START,
            TraceEventType.TRANSFORMATION_SUCCESS,
            TraceEventType.TRANSFORMATION_FAILURE,
            TraceEventType.CONTEXT_PULL,
            TraceEventType.PRE_INVOKE,
            TraceEventType.CONTEXT_TRUNCATE,
            // New Manifold / Junction DETAILED events
            TraceEventType.MANAGER_TASK_ANALYSIS,
            TraceEventType.MANAGER_AGENT_SELECTION,
            TraceEventType.TASK_PROGRESS_UPDATE,
            TraceEventType.TASK_COMPLETION_CHECK,
            TraceEventType.TASK_NEXT_STEPS,
            TraceEventType.AGENT_REQUEST_VALIDATION,
            TraceEventType.AGENT_RESPONSE_PROCESSING,
            TraceEventType.P2P_TRANSPORT_SEND,
            TraceEventType.P2P_TRANSPORT_RECEIVE,
            TraceEventType.PCP_CONTEXT_TRANSFER,
            TraceEventType.JUNCTION_ROUND_START,
            TraceEventType.JUNCTION_ROUND_END,
            TraceEventType.JUNCTION_VOTE_TALLY,
            TraceEventType.JUNCTION_CONSENSUS_CHECK,
            TraceEventType.JUNCTION_PARTICIPANT_DISPATCH,
            TraceEventType.JUNCTION_PARTICIPANT_RESPONSE,
            TraceEventType.JUNCTION_PHASE_START,
            TraceEventType.JUNCTION_PHASE_END,
            TraceEventType.DISTRIBUTION_GRID_VALIDATION_START,
            TraceEventType.DISTRIBUTION_GRID_VALIDATION_SUCCESS,
            TraceEventType.DISTRIBUTION_GRID_VALIDATION_FAILURE,
            TraceEventType.DISTRIBUTION_GRID_MEMORY_ENVELOPE,
            TraceEventType.DISTRIBUTION_GRID_POLICY_EVALUATION,
            TraceEventType.DISTRIBUTION_GRID_BOOTSTRAP_CATALOG_PULL,
            TraceEventType.DISTRIBUTION_GRID_DISCOVERY_ADMISSION,
            TraceEventType.DISTRIBUTION_GRID_DURABILITY_CHECKPOINT,
            // New Splitter DETAILED events
            TraceEventType.SPLITTER_CONTENT_DISTRIBUTION,
            TraceEventType.SPLITTER_PIPELINE_DISPATCH,
            TraceEventType.SPLITTER_PIPELINE_CALLBACK,
            TraceEventType.SPLITTER_COMPLETION_CALLBACK -> TraceEventPriority.DETAILED
            
            // Existing INTERNAL events
            //
            // Internal events are noisy bookkeeping details and should only surface at DEBUG detail levels.
            TraceEventType.PIPE_END,
            TraceEventType.BRANCH_PIPE_TRIGGERED,
            // New Manifold INTERNAL events
            TraceEventType.MANIFOLD_LOOP_ITERATION,
            TraceEventType.MANIFOLD_TERMINATION_CHECK,
            TraceEventType.CONVERSE_HISTORY_UPDATE,
            TraceEventType.MANIFOLD_RECOVERY_ATTEMPT,
            TraceEventType.DISTRIBUTION_GRID_LOOP_GUARD,
            // New Splitter INTERNAL events
            TraceEventType.SPLITTER_PARALLEL_START,
            TraceEventType.SPLITTER_PARALLEL_AWAIT,
            TraceEventType.SPLITTER_RESULT_COLLECTION -> TraceEventPriority.INTERNAL
            
            // Default case for any unmapped events
            else -> TraceEventPriority.STANDARD
        }
    }
    
    /**
     * Decide whether a trace event should be emitted for the requested detail level.
     *
     * @param eventType The event being evaluated.
     * @param detailLevel The requested trace verbosity.
     * @return True when the event belongs in the requested output stream.
     */
    fun shouldTrace(eventType: TraceEventType, detailLevel: TraceDetailLevel): Boolean {
        val priority = getPriority(eventType)
        return when(detailLevel) {
            TraceDetailLevel.MINIMAL -> priority == TraceEventPriority.CRITICAL
            TraceDetailLevel.NORMAL -> priority in listOf(TraceEventPriority.CRITICAL, TraceEventPriority.STANDARD)
            TraceDetailLevel.VERBOSE -> priority in listOf(TraceEventPriority.CRITICAL, TraceEventPriority.STANDARD, TraceEventPriority.DETAILED)
            TraceDetailLevel.DEBUG -> true // All events
        }
    }
}
