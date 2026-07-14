# Pipeline

## OVERVIEW
Orchestration containers (Pipeline, Manifold, Junction, Connector, Splitter, MultiConnector, DistributionGrid) for chaining pipes and coordinating multi-agent workflows.

## STRUCTURE
```
Pipeline/
├── Pipeline.kt             # Base class, pipe sequencing (1,541 LOC)
├── Manifold.kt             # Manager/worker orchestration, P2P dispatch (2,223 LOC)
├── Junction.kt             # Democratic voting, workflow recipe phases (4,086 LOC)
├── DistributionGrid.kt     # Distributed node routing, registry discovery (8,738 LOC)
├── Connector.kt            # Conditional pipeline branching
├── Splitter.kt             # Parallel pipeline execution
├── MultiConnector.kt       # Complex routing (SEQUENTIAL/PARALLEL/FALLBACK)
├── ManifoldDsl.kt          # DSL builder: manifold { }
├── JunctionDsl.kt          # DSL builder: junction { }
├── DistributionGridDsl.kt  # DSL builder: distributionGrid { }
├── ManifoldLoopLimitExceededException.kt
└── *Models.kt, *MemoryModels.kt, *DurabilityModels.kt, *ProtocolModels.kt
```

## WHERE TO LOOK
| Task | Location | Notes |
|------|----------|-------|
| Multi-agent orchestration | `Manifold.kt` | Manager/worker, P2P dispatch, loop limits |
| Democratic decision-making | `Junction.kt` | Voting, moderator/participant, workflow recipes |
| Distributed routing | `DistributionGrid.kt` | Node registry, remote handoff, durability |
| DSL entry | `ManifoldDsl.kt`, `JunctionDsl.kt` | State machine stages |

## CONVENTIONS
- All containers implement `P2PInterface`
- KillSwitch accumulation pattern across all containers
- Tracing support via `enableTracing()`, `trace()` methods
- DSL builders use `configure()` with stage validation
- `@RuntimeState` fields preserved across lifecycle transitions

## ANTI-PATTERNS
- **Never** share a single container instance across simultaneous executions (mutable state per run)
- **Never** call `setTokenBudget()` while executing (thread safety)

## JUNCTION MEMORY — SUMMARIZATION BACKENDS

Junction's outbound memory system uses a two-tier budget: deterministic compaction first, optional summarization second. When older history exceeds the recent window, Junction can optionally summarize it via one of two backends.

### Backend priority

1. **Agent backend** — `JunctionMemoryPolicy.summaryAgent`: a `P2PInterface`. When set, Junction calls `executeLocal` with:
   - `text`: older history string
   - `metadata["junctionSummarizerContext"]`: `JunctionSummarizerContext` carrying `roleKind`, `phase`, `summaryBudget`, `summarySeed`

   Junction extracts `MultimodalContent.text` from the response as the summary. This backend takes absolute priority when both agent and lambda are configured.

2. **Lambda backend** — `JunctionMemoryPolicy.summarizer`: a `((String) -> String)?` lambda. Fallback when the agent backend is absent.

3. **Verbatim** — when neither backend is configured, older history is included verbatim (subject to token budget).

### Failure handling

Both backends are wrapped in `runCatching`:
- Agent/lambda exceptions → empty string → verbatim fallback
- Agent/lambda blank output (`text.isBlank()`) → verbatim fallback
- Token cap applied after summarization via `budgetText(summaryBudget)`

### Configuration

```kotlin
val junction = junction {
    moderator(MyModerator())
    participant("analyst", MyAnalyst())
    memoryPolicy {
        enableSummarization = true
        summaryBudget = 1024
        recentDiscussionEntries = 2
    }
    summaryAgent(mySummaryAgent)           // P2PInterface — agent backend
    // OR block form:
    summaryAgent { this.summaryAgent = myAgent }
    // Lambda fallback (used when no agent):
    memoryPolicy {
        summarizer = { rawHistory -> compact(rawHistory) }
    }
}
```

### Design note

`summaryAgent(agent)` calls `setSummaryAgent` directly rather than replacing the entire memory policy, preserving any previously set `enableSummarization`, `summaryBudget`, or `recentDiscussionEntries` values.