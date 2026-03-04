# Merged PCP + JSON Output Mode

TPipe supports a **Merged Mode** that allows pipes to use both structured JSON output AND Pipe Context Protocol (PCP) tool calling simultaneously. This resolves the previous mutual exclusivity between these two features.

## Table of Contents
- [The Problem](#the-problem)
- [The Solution](#the-solution)
- [Basic Example](#basic-example)
- [Extracting Results](#extracting-results)
- [AI Response Examples](#ai-response-examples)
- [The Operational Contract](#the-operational-contract)
- [Best Practices](#best-practices)
- [Next Steps](#next-steps)

---

## The Problem

Previously, when both PCP tools and JSON output were configured, the model received conflicting instructions:
- **PCP Injection**: "Return an array of PcPRequest objects."
- **JSON Injection**: "Return ONLY a JSON object matching this schema."

This created ambiguity, leading to inconsistent model behavior and parsing failures.

---

## The Solution

Merged Mode automatically activates when both conditions are met:
1.  **PCP Tools** are configured (Stdio, HTTP, Python, or TPipe functions).
2.  **JSON Output Schema** is configured with `requireJsonPromptInjection()`.

In this mode, the AI receives unified instructions to return both the required JSON data and any optional tool calls in a single response turn.

---

## Basic Example

```kotlin
val pipe = BedrockPipe()
    .setRegion("us-east-1")
    .setModel("anthropic.claude-3-5-sonnet-20241022-v2:0")
    
    // Configure PCP tools (Tool Belt)
    .setPcPContext(PcpContext().apply {
        addTPipeOption(TPipeContextOptions().apply {
            functionName = "search_database"
            description = "Search the customer database."
        })
    })
    
    // Configure JSON output (The Specification)
    .requireJsonPromptInjection()
    .setJsonOutput("""{"answer": "string", "confidence": 0.0}""")
    
    .setSystemPrompt("You are a helpful assistant.")

val response = pipe.execute("Find customer John Doe and tell me his status.")
```

---

## Extracting Results

Because the model returns a compound result, you need to use TPipe's specialized extractors for both output lines.

```kotlin
// 1. Extract JSON output (REQUIRED - will always be present)
data class MyOutput(val answer: String, val confidence: Double)
val output = extractJson<MyOutput>(response)
    ?: throw IllegalStateException("AI failed to return required JSON specification")

// 2. Extract PCP tool calls (OPTIONAL - may be empty)
val parser = PcpResponseParser()
val toolCalls = parser.extractPcpRequests(response)

if (toolCalls.success) {
    for (request in toolCalls.requests) {
        println("Tool requested: ${request.tPipeContextOptions.functionName}")
    }
}
```

---

## AI Response Examples

### Case 1: Status Only (No Tools Needed)
```json
{
  "answer": "Customer John Doe is active with account status: premium",
  "confidence": 0.95
}
```

### Case 2: Status + Tool Calls
```json
{
  "answer": "Searching database for customer information...",
  "confidence": 0.0
}

[
  {
    "tPipeContextOptions": { "functionName": "search_database" },
    "argumentsOrFunctionParams": ["John Doe"]
  }
]
```

---

## The Operational Contract

### JSON Output: REQUIRED
- The model MUST return valid, deserializable JSON matching your schema.
- This is enforced even if no tools are called.

### Tool Calls: OPTIONAL
- The model MAY return tool call requests as an array if it determines they are necessary to complete the task.

---

## Best Practices

*   **Always Validate JSON**: Since JSON is the required mainline, always check if `extractJson` returns a valid object before proceeding.
*   **Clear Tool Descriptions**: Provide clear descriptions so the model knows when it is appropriate to reach for a tool vs. when it should simply answer using the schema.
*   **Use Data Classes**: Use Kotlin `@Serializable` data classes for your JSON output to ensure type safety and cleaner extraction logic.
*   **Custom Instructions**: You can override the default merged instructions using `pipe.setMergedPcpJsonInstructions()` for fine-tuned control.

---

## Next Steps

Now that you can combine tools and structure, learn about the advanced session management that keeps these tools running across multiple turns.

**→ [Advanced Session Management](../advanced-concepts/advanced-session-management.md)** - Managing interactive tool sessions.
