# GraalVM Native ABI Specification — DistributionGrid Envelope

**Spec File:** graalvm-abi-distribution-grid-envelope.md  
**Version:** 0.2.0-draft  
**Created:** 2026-05-07  
**Status:** Draft

**Design Decision:** Option A — Full C struct exposure. All user-facing types are exposed as C structs. Wire protocol types remain internal. (Confirmed via codebase: DistributionGridModels.kt uses data classes that map directly to these C structs; Option A matches existing TPipe patterns for user-facing types.)

---

## 1. Overview

The DistributionGrid envelope is the primary data structure for task dispatch and node communication in TPipe's distributed multi-agent system.

```
┌─────────────────────────────────────────────────────────────┐
│                 DistributionGrid Envelope                    │
│  ┌─────────────────────────────────────────────────────┐    │
│  │ envelopeId: String (UUID)                            │    │
│  │ sourceNodeId: String                                │    │
│  │ targetNodeId: String                                │    │
│  │ directive: DirectiveKind                           │    │
│  │ payload: String (JSON-encoded task data)            │    │
│  │ timestamp: int64 (Unix ms)                          │    │
│  │ ttl: int64 (milliseconds before expiration)         │    │
│  │ priority: int32 (higher = more urgent)              │    │
│  │ retryCount: int32                                   │    │
│  │ traceId: String?                                    │    │
│  └─────────────────────────────────────────────────────┘    │
└─────────────────────────────────────────────────────────────┘
```

---

## 2. DirectiveKind Enum

All 9 directive kinds are exposed in the ABI:

```c
typedef enum {
    TPIPE_DIRECTIVE_SUBMIT_TASK,    // Submit new task to grid
    TPIPE_DIRECTIVE_REPORT_PROGRESS, // Worker reports progress
    TPIPE_DIRECTIVE_COMPLETE_TASK,   // Worker completes task
    TPIPE_DIRECTIVE_FAILURE_NOTICE,  // Report task failure
    TPIPE_DIRECTIVE_JOIN_REQUEST,     // Node requests to join grid
    TPIPE_DIRECTIVE_LEAVE_NOTICE,     // Node voluntarily leaves
    TPIPE_DIRECTIVE_HEARTBEAT,        // Keep-alive ping
    TPIPE_DIRECTIVE_CTRL_SHUTDOWN,    // Shutdown signal
    TPIPE_DIRECTIVE_REBALANCE         // Trigger task redistribution
} TPipe_DirectiveKind;
```

---

## 3. TaskOutcomeKind Enum

```c
typedef enum {
    TPIPE_OUTCOME_PENDING,     // Task not yet started
    TPIPE_OUTCOME_IN_PROGRESS, // Task currently executing
    TPIPE_OUTCOME_COMPLETED,   // Task finished successfully
    TPIPE_OUTCOME_FAILED,      // Task failed
    TPIPE_OUTCOME_CANCELLED,    // Task was cancelled
    TPIPE_OUTCOME_TIMEOUT       // Task exceeded time limit
} TPipe_TaskOutcomeKind;
```

---

## 4. TaskStatusKind Enum

```c
typedef enum {
    TPIPE_STATUS_IDLE,           // Node has no tasks
    TPIPE_STATUS_BUSY,            // Node is working
    TPIPE_STATUS_DEGRADED,         // Node partially available
    TPIPE_STATUS_OFFLINE           // Node disconnected
} TPipe_TaskStatusKind;
```

---

## 5. FailureKind Enum

```c
typedef enum {
    TPIPE_FAILURE_NONE,            // No failure
    TPIPE_FAILURE_TIMEOUT,         // Operation timed out
    TPIPE_FAILURE_REJECTED,        // Task rejected by node
    TPIPE_FAILURE_INVALID_STATE,   // Invalid state transition
    TPIPE_FAILURE_NETWORK,         // Network error
    TPIPE_FAILURE_INTERNAL,        // Internal error
    TPIPE_FAILURE_USER_CANCELLED   // User cancelled task
} TPipe_FailureKind;
```

---

## 6. NodeRoleKind Enum

```c
typedef enum {
    TPIPE_NODE_COORDINATOR,  // Orchestrates grid (submitter)
    TPIPE_NODE_WORKER,       // Executes tasks
    TPIPE_NODE_OBSERVER      // Monitors only
} TPipe_NodeRoleKind;
```

---

## 7. DistributionGridEnvelope C Struct

```c
typedef struct {
    // Header fields
    const char* envelopeId;     // UUID string (TPipe owns, do not free)
    const char* sourceNodeId;   // Node that created this envelope
    const char* targetNodeId;   // Node to deliver to (NULL for broadcast)

    // Directive
    TPipe_DirectiveKind directive;

    // Payload
    const char* payload;        // JSON-encoded task data (TPipe owns)

    // Timing
    int64_t timestamp;          // Unix milliseconds
    int64_t ttl;                // Expiration time in ms (0 = never)

    // Priority/retry
    int32_t priority;          // Higher = more urgent (-2147483648 to 2147483647)
    int32_t retryCount;         // Number of retry attempts

    // Trace
    const char* traceId;        // Optional trace correlation ID (NULL if not set)
} TPipe_DistributionGridEnvelope;
```

---

## 8. DistributionGridOutcome Struct

Result of grid operations:

```c
typedef struct {
    const char* outcomeId;               // UUID of this outcome
    TPipe_TaskOutcomeKind outcomeKind;    // Outcome type

    // Task info
    const char* taskId;                  // Related task ID
    const char* nodeId;                  // Node that produced this outcome

    // Result data
    const char* resultData;               // JSON result payload (NULL if failure)

    // Failure info (set if outcomeKind == FAILED)
    TPipe_FailureKind failureKind;        // Failure classification
    const char* failureMessage;           // Human-readable error message
    const char* failureTrace;             // Stack trace or debug info

    // Timing
    int64_t submittedAt;                 // When task was submitted
    int64_t completedAt;                  // When task completed (0 if not done)
} TPipe_DistributionGridOutcome;
```

---

## 9. DistributionGridFailure Struct

Failure notification:

```c
typedef struct {
    const char* failureId;              // UUID of failure event
    TPipe_FailureKind kind;             // Failure type

    const char* envelopeId;             // Related envelope
    const char* sourceNodeId;           // Node that detected failure

    const char* message;                // Error message
    const char* stackTrace;             // Debug info

    int64_t timestamp;                  // When failure occurred

    // Recovery suggestion
    const char* recoveryHint;           // Suggested action (NULL if none)
} TPipe_DistributionGridFailure;
```

---

## 10. TaskProgress Struct

Progress tracking:

```c
typedef struct {
    const char* taskId;                 // Task identifier
    const char* nodeId;                 // Node reporting progress

    int32_t progressPercent;            // 0-100 percentage
    const char* statusMessage;        // Human-readable status

    int64_t startedAt;                 // Task start time (Unix ms)
    int64_t estimatedCompletion;      // Estimated completion time (0 if unknown)

    const char* currentStep;           // Current step description
    int32_t stepsTotal;               // Total number of steps (0 if unknown)
    int32_t stepsCompleted;           // Completed steps
} TPipe_TaskProgress;
```

---

## 11. P2PAgentListing Struct

Agent discovery:

```c
typedef struct {
    const char* agentName;             // Unique agent name
    const char* nodeId;                // Node hosting this agent

    TPipe_NodeRoleKind role;           // COORDINATOR, WORKER, or OBSERVER

    int32_t currentLoad;               // Number of tasks currently running
    int32_t maxLoad;                  // Maximum concurrent tasks

    int64_t lastHeartbeat;            // Unix timestamp of last heartbeat

    // Capabilities (bitfield)
    uint64_t capabilities;            // See TPIPE_AGENT_CAP_* flags

    const char* description;          // Agent description (NULL if none)
} TPipe_P2PAgentListing;
```

### 11.1 Agent Capability Flags

```c
#define TPIPE_AGENT_CAP_EXECUTE_TASKS   (1ULL << 0)   // Can execute tasks
#define TPIPE_AGENT_CAP_SUBMIT_TASKS    (1ULL << 1)   // Can submit tasks
#define TPIPE_AGENT_CAP_OBSERVE         (1ULL << 2)   // Can observe grid
#define TPIPE_AGENT_CAP_MANAGE_WORKERS  (1ULL << 3)   // Can manage workers
```

---

## 12. JoinResult Struct

Node join result:

```c
typedef struct {
    int success;                       // 1 = success, 0 = failure

    const char* nodeId;                // Assigned node ID
    TPipe_NodeRoleKind assignedRole;  // Role assigned by coordinator

    const char* gridId;               // Grid the node joined
    const char* coordinatorUrl;       // URL of coordinator

    int32_t assignedSlot;             // Slot number assigned (for ordering)

    // On failure
    const char* failureReason;        // Why join failed (NULL on success)
} TPipe_JoinResult;
```

---

## 13. Policy Structs

### 13.1 RetryPolicy

```c
typedef struct {
    int32_t maxRetries;               // Maximum retry attempts (0 = no retries)
    int32_t initialDelayMs;           // Initial delay before first retry
    int32_t maxDelayMs;               // Maximum delay cap
    float backoffMultiplier;           // Multiplier for exponential backoff (e.g., 2.0)
} TPipe_RetryPolicy;
```

### 13.2 TimeoutPolicy

```c
typedef struct {
    int32_t taskTimeoutMs;             // Task execution timeout
    int32_t nodeTimeoutMs;            // Node unresponsive timeout
    int32_t heartbeatIntervalMs;      // Heartbeat send interval
} TPipe_TimeoutPolicy;
```

### 13.3 P2PConcurrencyMode (enum)

```c
typedef enum {
    TPIPE_CONCURRENCY_SHARED,   // Workers share context
    TPIPE_CONCURRENCY_ISOLATED, // Each worker has isolated context
    TPIPE_CONCURRENCY_AUTO      // TPipe chooses based on workload
} TPipe_P2PConcurrencyMode;
```

---

## 14. Envelope Lifecycle Functions

### 14.1 Create

```c
// Create a new envelope with the specified directive.
// Caller receives handle with refcount = 1.

TPipe_DistributionGridEnvelopeHandle TPipe_DistributionGridEnvelope_create(
    TPipe_DirectiveKind directive
);
```

### 14.2 Release

```c
// Decrement refcount. If count reaches 0, envelope is GC'd.

TPipe_Result TPipe_DistributionGridEnvelope_release(
    TPipe_DistributionGridEnvelopeHandle envelope
);
```

### 14.3 Accessors

```c
// Get envelope fields
TPipe_Result TPipe_DistributionGridEnvelope_getId(
    TPipe_DistributionGridEnvelopeHandle envelope,
    char* buffer, int buffer_size
);

TPipe_DirectiveKind TPipe_DistributionGridEnvelope_getDirective(
    TPipe_DistributionGridEnvelopeHandle envelope
);

TPipe_Result TPipe_DistributionGridEnvelope_getSourceNodeId(
    TPipe_DistributionGridEnvelopeHandle envelope,
    char* buffer, int buffer_size
);

TPipe_Result TPipe_DistributionGridEnvelope_getTargetNodeId(
    TPipe_DistributionGridEnvelopeHandle envelope,
    char* buffer, int buffer_size
);

TPipe_Result TPipe_DistributionGridEnvelope_getPayload(
    TPipe_DistributionGridEnvelopeHandle envelope,
    char* buffer, int buffer_size
);
```

### 14.4 Mutators

```c
// Set target node (NULL for broadcast)
TPipe_Result TPipe_DistributionGridEnvelope_setTargetNodeId(
    TPipe_DistributionGridEnvelopeHandle envelope,
    const char* targetNodeId
);

// Set JSON payload
TPipe_Result TPipe_DistributionGridEnvelope_setPayload(
    TPipe_DistributionGridEnvelopeHandle envelope,
    const char* jsonPayload
);

// Set priority
TPipe_Result TPipe_DistributionGridEnvelope_setPriority(
    TPipe_DistributionGridEnvelopeHandle envelope,
    int32_t priority
);

// Set TTL
TPipe_Result TPipe_DistributionGridEnvelope_setTtl(
    TPipe_DistributionGridEnvelopeHandle envelope,
    int64_t ttlMs
);

// Set trace ID
TPipe_Result TPipe_DistributionGridEnvelope_setTraceId(
    TPipe_DistributionGridEnvelopeHandle envelope,
    const char* traceId
);
```

### 14.5 Submit to Grid

```c
// Submit envelope to the DistributionGrid for processing.
// Handles routing to appropriate node(s).

TPipe_Result TPipe_DistributionGrid_submitEnvelope(
    TPipe_DistributionGridEnvelopeHandle envelope,
    TPipe_DistributionGridOutcome* out_result
);
```

---

## 15. Handle Type

```c
typedef uint64_t TPipe_DistributionGridEnvelopeHandle;
typedef uint64_t TPipe_DistributionGridOutcomeHandle;
typedef uint64_t TPipe_DistributionGridFailureHandle;
typedef uint64_t TPipe_TaskProgressHandle;
typedef uint64_t TPipe_P2PAgentListingHandle;
typedef uint64_t TPipe_JoinResultHandle;
```

---

## 16. Implementation Checklist

### Enums
| Item | Status |
|------|--------|
| `TPipe_DirectiveKind` (9 values) | ☐ TODO |
| `TPipe_TaskOutcomeKind` | ☐ TODO |
| `TPipe_TaskStatusKind` | ☐ TODO |
| `TPipe_FailureKind` | ☐ TODO |
| `TPipe_NodeRoleKind` | ☐ TODO |
| `TPipe_P2PConcurrencyMode` | ☐ TODO |

### Structs
| Item | Status |
|------|--------|
| `TPipe_DistributionGridEnvelope` | ☐ TODO |
| `TPipe_DistributionGridOutcome` | ☐ TODO |
| `TPipe_DistributionGridFailure` | ☐ TODO |
| `TPipe_TaskProgress` | ☐ TODO |
| `TPipe_P2PAgentListing` | ☐ TODO |
| `TPipe_JoinResult` | ☐ TODO |
| `TPipe_RetryPolicy` | ☐ TODO |
| `TPipe_TimeoutPolicy` | ☐ TODO |

### Functions
| Function | Status |
|----------|--------|
| `TPipe_DistributionGridEnvelope_create()` | ☐ TODO |
| `TPipe_DistributionGridEnvelope_release()` | ☐ TODO |
| `TPipe_DistributionGridEnvelope_get*()` accessors | ☐ TODO |
| `TPipe_DistributionGridEnvelope_set*()` mutators | ☐ TODO |
| `TPipe_DistributionGrid_submitEnvelope()` | ☐ TODO |

---

*End of Group 4 specifications. Next: graalvm-abi-handle-types.md (all remaining handle types)*