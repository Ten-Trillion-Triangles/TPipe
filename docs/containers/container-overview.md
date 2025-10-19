# Container Overview

## Table of Contents
- [Container vs Pipeline](#container-vs-pipeline)
- [P2P Integration](#p2p-integration)
- [Container References](#container-references)
- [Tracing Support](#tracing-support)
- [Container Types](#container-types)
- [When to Use Containers](#when-to-use-containers)
- [Implementation Status](#implementation-status)
- [Example: Basic Container Pattern](#example-basic-container-pattern)

Containers are specialized classes that orchestrate multiple pipelines, providing higher-level coordination patterns beyond simple pipe composition. Most containers implement `P2PInterface`, though some like Junction are still in development.

## Container vs Pipeline

| Aspect | Pipeline | Container |
|--------|----------|-----------|
| **Purpose** | Sequential pipe execution | Multi-pipeline orchestration |
| **Child Management** | Individual pipes | Complete pipelines |
| **P2P Support** | Single agent | Can expose child pipelines |
| **Execution Model** | Linear flow | Routing, parallel, or distributed |
| **Tracing** | Pipe-level events | Container + child events |

## P2P Integration

Most containers implement `P2PInterface`:
```kotlin
class Connector : P2PInterface {
    override fun getPipelinesFromInterface(): List<Pipeline> {
        return branches.values.toList() // Expose managed pipelines
    }
    
    override suspend fun executeP2PRequest(request: P2PRequest): P2PResponse? {
        val pipeline = branches[lastConnection]
        return pipeline?.executeP2PRequest(request)
    }
}
```

**Note**: Junction is currently a stub class without P2P implementation.

## Container References

Child pipelines do **not** automatically store container references. This must be done manually if needed:
```kotlin
// Manual assignment when required
pipeline.pipelineContainer = this
```

## Tracing Support

Containers that support tracing follow this pattern:
```kotlin
fun enableTracing(config: TraceConfig = TraceConfig()) {
    tracingEnabled = true
    traceConfig = config
    // Enable on child pipelines
    childPipelines.forEach { it.enableTracing(config) }
}
```

## Container Types

### Routing Containers
- **Connector**: Key-based pipeline routing
- **MultiConnector**: Multiple connector management with execution modes

### Parallel Containers  
- **Splitter**: Fan-out execution with result collection

### Orchestration Containers
- **Manifold**: Manager-worker task coordination
- **DistributionGrid**: Decentralized agent swarm (stub implementation)
- **Junction**: Democratic discussion (stub implementation)

## When to Use Containers

**Use containers when:**
- Multiple pipelines need coordination
- Routing logic is required
- Parallel execution is needed
- Task orchestration is complex

**Use direct pipe composition when:**
- Simple sequential processing
- Single pipeline is sufficient
- No routing or coordination needed

## Implementation Status

| Container | Status | P2P Support | Tracing |
|-----------|--------|-------------|---------|
| Connector | ✅ Complete | ✅ Yes | ✅ Yes |
| MultiConnector | ✅ Complete | ✅ Yes | ❌ No |
| Splitter | ✅ Complete | ✅ Yes | ✅ Yes |
| Manifold | ✅ Complete | ✅ Yes | ✅ Yes |
| DistributionGrid | ⚠️ Stub | ✅ Yes | ❌ No |
| Junction | ⚠️ Stub | ❌ No | ❌ No |

## Example: Basic Container Pattern

```kotlin
class Connector : P2PInterface {
    private val branches = mutableMapOf<Any, Pipeline>()
    
    fun add(key: Any, pipeline: Pipeline): Connector {
        branches[key] = pipeline
        return this
    }
    
    suspend fun execute(path: Any, content: MultimodalContent): MultimodalContent {
        val connection = branches[path]
        return connection?.execute(content) ?: content.apply { terminatePipeline = true }
    }
    
    override fun getPipelinesFromInterface(): List<Pipeline> = branches.values.toList()
}
```

---

**Next:** [Connector →](connector.md)
