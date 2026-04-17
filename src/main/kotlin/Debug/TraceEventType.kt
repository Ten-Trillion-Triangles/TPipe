package com.TTT.Debug

/**
 * Central event vocabulary used by the trace system.
 *
 * Junction, Manifold, Splitter, and ordinary pipe events all share this enum so the visualizer, priority
 * mapper, and failure analysis tooling can treat each orchestration layer consistently.
 */
enum class TraceEventType {
    // Existing Pipe Events
    PIPE_START,
    PIPE_END,
    PIPE_SUCCESS,
    PIPE_FAILURE,
    PIPE_TIMEOUT,
    PIPE_RETRY,
    CONTEXT_PULL,
    CONTEXT_TRUNCATE,
    CONTEXT_PREPARED,
    PRE_INVOKE,
    POST_GENERATE,
    VALIDATION_START,
    VALIDATION_SUCCESS,
    VALIDATION_FAILURE,
    TRANSFORMATION_START,
    TRANSFORMATION_SUCCESS,
    TRANSFORMATION_FAILURE,
    API_CALL_START,
    API_CALL_SUCCESS,
    API_CALL_FAILURE,
    BRANCH_PIPE_TRIGGERED,
    PIPELINE_TERMINATION,
    
    // Pipeline Pause/Resume Events
    PIPELINE_PAUSE,
    PIPELINE_RESUME,
    PAUSE_POINT_CHECK,
    
    // Manifold Orchestration Events
    MANIFOLD_START,
    MANIFOLD_END,
    MANIFOLD_SUCCESS,
    MANIFOLD_FAILURE,
    
    // Manager Decision Events
    MANAGER_DECISION,
    MANAGER_TASK_ANALYSIS,
    MANAGER_AGENT_SELECTION,
    
    // Task Progress Events
    TASK_PROGRESS_UPDATE,
    TASK_COMPLETION_CHECK,
    TASK_NEXT_STEPS,
    
    // Agent Communication Events
    AGENT_DISPATCH,
    AGENT_RESPONSE,
    AGENT_REQUEST_VALIDATION,
    AGENT_REQUEST_EXTRACTION,
    AGENT_RESPONSE_PROCESSING,

    // P2P Communication Events
    P2P_REQUEST_START,
    P2P_REQUEST_SUCCESS,
    P2P_REQUEST_FAILURE,
    P2P_TRANSPORT_SEND,
    P2P_TRANSPORT_RECEIVE,
    PCP_CONTEXT_TRANSFER,

    // Loop and Control Events
    MANIFOLD_LOOP_ITERATION,
    MANIFOLD_TERMINATION_CHECK,
    MANIFOLD_LOOP_LIMIT_EXCEEDED,
    CONVERSE_HISTORY_UPDATE,

    // Error and Recovery Events
    AGENT_REQUEST_INVALID,
    P2P_COMMUNICATION_FAILURE,
    MANIFOLD_RECOVERY_ATTEMPT,

    // KillSwitch Safety Events
    //
    // Emitted when a KillSwitch token limit is checked or triggered.
    // These events appear across all container types and pipeline traces
    // so developers can identify when and where token limits cut off execution.
    KILLSWITCH_CHECK,
    KILLSWITCH_TRIPPED,
    
    // Splitter Orchestration Events
    SPLITTER_START,
    SPLITTER_END,
    SPLITTER_SUCCESS,
    SPLITTER_FAILURE,
    
    // Content Distribution Events  
    SPLITTER_CONTENT_DISTRIBUTION,
    SPLITTER_PIPELINE_DISPATCH,
    SPLITTER_PIPELINE_COMPLETION,
    
    // Callback Events
    SPLITTER_PIPELINE_CALLBACK,
    SPLITTER_COMPLETION_CALLBACK,
    
    // Parallel Execution Events
    SPLITTER_PARALLEL_START,
    SPLITTER_PARALLEL_AWAIT,
    SPLITTER_RESULT_COLLECTION,

    // DistributionGrid Orchestration Events
    //
    // DistributionGrid uses its own trace family so validation, lifecycle, and later node-routing behavior can be
    // grouped separately from pipe, pipeline, and the other harness containers.
    DISTRIBUTION_GRID_INIT,
    DISTRIBUTION_GRID_VALIDATION_START,
    DISTRIBUTION_GRID_VALIDATION_SUCCESS,
    DISTRIBUTION_GRID_VALIDATION_FAILURE,
    DISTRIBUTION_GRID_PAUSE,
    DISTRIBUTION_GRID_RESUME,
    DISTRIBUTION_GRID_RUNTIME_RESET,
    DISTRIBUTION_GRID_START,
    DISTRIBUTION_GRID_END,
    DISTRIBUTION_GRID_SUCCESS,
    DISTRIBUTION_GRID_FAILURE,
    DISTRIBUTION_GRID_ROUTER_DECISION,
    DISTRIBUTION_GRID_LOCAL_WORKER_DISPATCH,
    DISTRIBUTION_GRID_LOCAL_WORKER_RESPONSE,
    DISTRIBUTION_GRID_PEER_HANDOFF,
    DISTRIBUTION_GRID_PEER_RESPONSE,
    DISTRIBUTION_GRID_RETURN_ROUTING,
    DISTRIBUTION_GRID_MEMORY_ENVELOPE,
    DISTRIBUTION_GRID_POLICY_EVALUATION,
    DISTRIBUTION_GRID_SESSION_HANDSHAKE,
    DISTRIBUTION_GRID_LOOP_GUARD,
    DISTRIBUTION_GRID_BOOTSTRAP_CATALOG_PULL,
    DISTRIBUTION_GRID_DISCOVERY_ADMISSION,
    DISTRIBUTION_GRID_REGISTRY_PROBE,
    DISTRIBUTION_GRID_REGISTRY_REGISTRATION,
    DISTRIBUTION_GRID_REGISTRY_LEASE_RENEWAL,
    DISTRIBUTION_GRID_REGISTRY_QUERY,
    DISTRIBUTION_GRID_DURABILITY_CHECKPOINT,
    DISTRIBUTION_GRID_PUBLIC_LISTING,
    DISTRIBUTION_GRID_PUBLIC_LISTING_AUTO_RENEW,

    // Junction Orchestration Events
    //
    // Junction gets its own trace family because the harness is a multi-stage orchestrator rather than a
    // single pipe. These events let the visualizer group discussion rounds, workflow phases, and handoff
    // boundaries separately from the lower-level pipe and P2P events they may wrap.
    JUNCTION_START,
    JUNCTION_END,
    JUNCTION_SUCCESS,
    JUNCTION_FAILURE,
    JUNCTION_PAUSE,
    JUNCTION_RESUME,
    JUNCTION_ROUND_START,
    JUNCTION_ROUND_END,
    JUNCTION_VOTE_TALLY,
    JUNCTION_CONSENSUS_CHECK,
    JUNCTION_PARTICIPANT_DISPATCH,
    JUNCTION_PARTICIPANT_RESPONSE,
    JUNCTION_WORKFLOW_START,
    JUNCTION_WORKFLOW_END,
    JUNCTION_WORKFLOW_SUCCESS,
    JUNCTION_WORKFLOW_FAILURE,
    JUNCTION_PHASE_START,
    JUNCTION_PHASE_END,
    JUNCTION_HANDOFF
}
