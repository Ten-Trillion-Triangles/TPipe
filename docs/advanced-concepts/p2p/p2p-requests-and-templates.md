# P2P Requests and Templates

P2P uses two request types: `AgentRequest` (LLM-facing) and `P2PRequest` (internal runtime). Templates help merge these efficiently.

## AgentRequest (LLM Interface)

Simple structure for LLMs to populate:

```kotlin
val agentRequest = AgentRequest(
    agentName = "data-processor",
    promptSchema = InputSchema.text,
    prompt = "Process this CSV and generate a summary",
    content = csvFileContent,
    pcpRequest = optionalPcpRequest
)
```

## P2PRequest (Full Request)

Complete request with all runtime details:

```kotlin
val p2pRequest = P2PRequest(
    transport = P2PTransport(Transport.Tpipe, "data-processor"),
    returnAddress = P2PTransport(Transport.Tpipe, "caller-agent"),
    prompt = MultimodalContent().apply {
        addText("Process this data")
        addBinary("data.csv", csvBytes, "text/csv")
    },
    authBody = "auth-token",
    contextExplanationMessage = "Use these processing rules",
    context = ContextWindow().apply {
        contextElements.add("rules: ${processingRules}")
    },
    customContextDescriptions = mutableMapOf(
        "DataProcessor" to "Custom processing instructions"
    ),
    pcpRequest = PcPRequest(),
    inputSchema = CustomJsonSchema.newSchema(
        "DataInput",
        "CSV processing input",
        DataInput(file = "", format = "")
    ),
    outputSchema = CustomJsonSchema.newSchema(
        "DataOutput", 
        "Processing results",
        DataOutput(summary = "", errors = listOf())
    )
)
```

## Request Templates

### Creating Templates

```kotlin
val template = P2PRequest().apply {
    authBody = "default-auth-token"
    prompt.addText("<system>You are a data processor.</system>")
    context = ContextWindow().apply {
        contextElements.add("guidelines: Always validate input data")
    }
}
```

### Using Templates

```kotlin
// Merge AgentRequest with template
val fullRequest = agentRequest.buildP2PRequest(template)

// Or use registry templates
val fullRequest = agentRequest.buildRequestFromRegistry("data-processor-template")
```

## Custom Schemas

Define input/output schemas for agent duplication:

```kotlin
data class ProcessingInput(
    val file: String,
    val format: String,
    val options: Map<String, String>
)

val inputSchema = CustomJsonSchema.newSchema(
    pipeName = "DataProcessor",
    description = "CSV processing configuration",
    jsonObject = ProcessingInput(
        file = "example.csv",
        format = "csv",
        options = mapOf("delimiter" to ",")
    )
)

val request = P2PRequest().apply {
    inputSchema = inputSchema
}
```

## PCP Integration

Attach PCP tool requests to P2P calls:

```kotlin
val pcpRequest = PcPRequest(
    stdioContextOptions = StdioContextOptions().apply {
        command = "file-reader"
        args.add("/data/input.csv")
        args.add("--validate")
    }
)

val p2pRequest = P2PRequest().apply {
    pcpRequest = pcpRequest
}
```

## Common Patterns

### Simple Text Request
```kotlin
val request = AgentRequest(
    agentName = "summarizer",
    prompt = "Summarize this document",
    content = documentText
)
```

### Multimodal Request
```kotlin
val request = P2PRequest().apply {
    prompt.addText("Analyze this image and data")
    prompt.addBinary("chart.png", imageBytes, "image/png")
    prompt.addBinary("data.csv", csvBytes, "text/csv")
}
```

### Authenticated Request
```kotlin
val request = P2PRequest().apply {
    authBody = generateAuthToken()
    prompt.addText("Secure operation request")
}
```

### Context-Rich Request
```kotlin
val request = P2PRequest().apply {
    contextExplanationMessage = "Use the provided business rules"
    context = ContextWindow().apply {
        contextElements.add("business-rules: ${rulesDocument}")
        contextElements.add("examples: ${exampleData}")
    }
    customContextDescriptions = mutableMapOf(
        "RuleEngine" to "Apply strict validation rules",
        "OutputFormatter" to "Use JSON format"
    )
}
```

## Template Management

Store templates in the registry for reuse:

```kotlin
// Store template
P2PRegistry.requestTemplates["data-processing"] = myTemplate

// Use stored template
val request = agentRequest.buildRequestFromRegistry("data-processing")
```

Or embed in descriptors:

```kotlin
val descriptor = P2PDescriptor(
    // ... other fields
    requestTemplate = myTemplate
)
```
## Next Steps

- [P2P Requirements and Validation](p2p-requirements-and-validation.md) - Continue with validation and safety rules.
