# P2P Descriptors and Transport

P2PDescriptor defines agent capabilities and connection details. Create one per agent and register it with the P2P system.

## Basic Descriptor

```kotlin
val descriptor = P2PDescriptor(
    agentName = "data-processor",
    agentDescription = "Processes CSV files and generates reports",
    transport = P2PTransport(Transport.Tpipe, "data-processor"),
    
    // Feature flags
    requiresAuth = false,
    usesConverse = true,
    allowsAgentDuplication = true,
    allowsCustomContext = true,
    allowsCustomAgentJson = true,
    recordsInteractionContext = true,
    recordsPromptContent = false,
    allowsExternalContext = true,
    
    // Protocol support
    contextProtocol = ContextProtocol.pcp,
    contextWindowSize = 32000,
    supportedContentTypes = mutableListOf(
        SupportedContentTypes.text,
        SupportedContentTypes.csv
    )
)
```

## Transport Configuration

### In-Process (Current)
```kotlin
P2PTransport(
    transportMethod = Transport.Tpipe,
    transportAddress = "my-agent-name",
    transportAuthBody = ""  // Leave empty for local agents
)
```

### Future Remote Transports
```kotlin
// HTTP (not yet implemented)
P2PTransport(
    transportMethod = Transport.Http,
    transportAddress = "https://api.example.com/agents/my-agent",
    transportAuthBody = "Bearer token123"
)

// STDIO (not yet implemented)
P2PTransport(
    transportMethod = Transport.Stdio,
    transportAddress = "/path/to/agent/binary",
    transportAuthBody = ""
)
```

## Skills and Capabilities

Define what your agent does with `P2PSkills`:

```kotlin
val descriptor = P2PDescriptor(
    // ... other fields
    agentSkills = mutableListOf(
        P2PSkills("csv-processing", "Parse and validate CSV files"),
        P2PSkills("report-generation", "Create summary reports from data"),
        P2PSkills("data-validation", "Check data quality and completeness")
    )
)
```

## Request Templates

Pre-configure common request patterns:

```kotlin
val descriptor = P2PDescriptor(
    // ... other fields
    requestTemplate = P2PRequest().apply {
        prompt.addText("<system>You are a data processing agent.</system>")
        context = ContextWindow().apply {
            contextElements.add("processing-rules: Always validate input before processing")
        }
    }
)
```

## Context Protocol Support

Specify how your agent handles external context:

```kotlin
// PCP tool support
contextProtocol = ContextProtocol.pcp
pcpDescriptor = PcpContext().apply {
    stdioOptions += StdioContextOptions().apply {
        command = "file-reader"
        args.add("--input")
    }
    stdioOptions += StdioContextOptions().apply {
        command = "data-validator"
        args.add("--schema")
    }
}

// MCP support  
contextProtocol = ContextProtocol.mcp

// No external context
contextProtocol = ContextProtocol.none
```

## Model Restrictions

Limit which models can use your agent:

```kotlin
val descriptor = P2PDescriptor(
    // ... other fields
    allowedModels = mutableMapOf(
        "data-processing" to mutableListOf("claude-3-sonnet", "gpt-4"),
        "report-generation" to mutableListOf("claude-3-haiku")
    )
)
```

## Complete Example

```kotlin
class DataProcessor : Pipeline(), P2PInterface {
    init {
        setP2pDescription(P2PDescriptor(
            agentName = "data-processor",
            agentDescription = "Processes CSV data and generates reports",
            transport = P2PTransport(Transport.Tpipe, "data-processor"),
            
            requiresAuth = true,
            usesConverse = true,
            allowsAgentDuplication = false,
            allowsCustomContext = true,
            allowsCustomAgentJson = false,
            recordsInteractionContext = false,
            recordsPromptContent = false,
            allowsExternalContext = false,
            
            contextProtocol = ContextProtocol.pcp,
            contextWindowSize = 16000,
            supportedContentTypes = mutableListOf(
                SupportedContentTypes.text,
                SupportedContentTypes.csv
            ),
            
            agentSkills = mutableListOf(
                P2PSkills("csv-processing", "Parse CSV files"),
                P2PSkills("report-generation", "Generate data reports")
            ),
            
            requestTemplate = P2PRequest().apply {
                prompt.addText("<system>Process the provided CSV data.</system>")
            }
        ))
    }
}
```
