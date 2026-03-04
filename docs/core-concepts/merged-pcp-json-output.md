# Merged PCP + JSON Output Mode - Compound Flows

In an industrial infrastructure, sometimes you need a valve to do two things at once: provide a structured status report (JSON) AND call for a specific tool (PCP) to fix a problem. **Merged Mode** is the specialized configuration that allows TPipe to handle both structured output and tool-calling simultaneously, resolving what used to be a conflict in model instructions.

## The Solution: The Unified Blueprint

Previously, if you asked for JSON and Tools at the same time, the model would often "clog," unsure which format to prioritize. Merged Mode automatically activates when you provide both a `PcpContext` and a JSON schema. It gives the AI a unified blueprint:
-   **JSON Output (REQUIRED)**: The model must return your structured status object.
-   **Tool Calls (OPTIONAL)**: The model can also return an array of tool requests if it needs help.

---

## Basic Configuration

Merged mode activates automatically when you have both fittings attached to your pipe.

```kotlin
val agent = BedrockPipe()
    .setRegion("us-east-1")
    .setModel("anthropic.claude-3-5-sonnet-20241022-v2:0")
    
    // Fitting 1: The Tool Belt (PCP)
    .setPcPContext(PcpContext().apply {
        addTPipeOption(TPipeContextOptions().apply {
            functionName = "check_valve_pressure"
            description = "Get the real-time pressure for a specific valve ID."
        })
    })
    
    // Fitting 2: The Specification (JSON)
    .requireJsonPromptInjection()
    .setJsonOutput("""{"status": "string", "urgency": 0}""")
    
    .setSystemPrompt("Monitor the plumbing mainline.")
```

---

## Extracting the Results: The Two Output Lines

Because the model returns a "Compound Result," you need to extract both the JSON Water and the PCP "Tools."

```kotlin
val response = agent.execute("Check Valve 4")

// 1. Extract the Status Report (JSON)
val report = extractJson<ValveStatus>(response)

// 2. Extract the Tool Requests (PCP)
val parser = PcpResponseParser()
val toolCalls = parser.extractPcpRequests(response)

if (toolCalls.success) {
    // Execute the requested tools...
}
```

---

## AI Response Examples

### Case 1: Simple Status (No Tools Needed)
```json
{
  "status": "Pressure normal. No intervention required.",
  "urgency": 0
}
```

### Case 2: Status + Action Required
```json
{
  "status": "Pressure high in Valve 4. Requesting sensor check.",
  "urgency": 8
}

[
  {
    "tPipeContextOptions": { "functionName": "check_valve_pressure" },
    "argumentsOrFunctionParams": ["valve_4"]
  }
]
```

---

## Best Practices

*   **Always Validate JSON**: Even when tools are called, the JSON output is **REQUIRED**. Always check if `extractJson` returns a valid object.
*   **Clear Tool Descriptions**: Since the model has to follow a schema AND decide when to use tools, your tool descriptions must be very clear so it knows when it's appropriate to "reach for the belt."
*   **Use Data Classes**: For the JSON part, use Kotlin `@Serializable` data classes. This makes the extraction much safer and cleaner than raw string parsing.

---

## Next Steps

Now that you can combine tools and structure, learn about the advanced session management that keeps these tools running across multiple turns.

**→ [Advanced Session Management](../advanced-concepts/advanced-session-management.md)** - Managing interactive tool sessions.
