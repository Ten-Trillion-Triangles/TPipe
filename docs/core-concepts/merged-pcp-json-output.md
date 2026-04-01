# Merged PCP + JSON Output Mode

## Overview

TPipe now supports a **merged mode** that allows pipes to use both structured JSON output AND Pipe Context Protocol (PCP) tool calling simultaneously. This resolves the previous mutual exclusivity between these two features.

## The Problem

Previously, when both PCP tools and JSON output were configured, the AI received conflicting instructions:
- PCP injection: "return an array of PcPRequest objects"
- JSON injection: "return ONLY JSON matching this schema"

This created ambiguity, and the AI's behavior was undefined.

## The Solution

**Merged mode** automatically activates when BOTH conditions are met:
1. PCP tools are configured (stdio, tpipe functions, http, or python)
2. JSON output schema is configured with `requireJsonPromptInjection()`

In merged mode, the AI receives unified instructions to return:
- **JSON output** (REQUIRED) - matching your schema
- **Tool calls** (OPTIONAL) - as an array if needed

## Usage

### Basic Example

```kotlin
val pipe = BedrockPipe()
    .setRegion("us-east-1")
    .setModel("anthropic.claude-3-sonnet-20240229-v1:0")
    
    // Configure PCP tools
    .setPcPContext(PcpContext().apply {
        addTPipeOption(TPipeContextOptions().apply {
            functionName = "searchDatabase"
            description = "Search customer database"
        })
    })
    
    // Configure JSON output
    .requireJsonPromptInjection()
    .setJsonOutput("""{"answer": "string", "confidence": 0.0}""")
    
    // Set system prompt (merged mode activates automatically)
    .setSystemPrompt("You are an automated security auditor responsible for identifying PII leakage in application logs.")

val response = pipe.execute("Find customer John Doe and tell me his status")
```

### Extracting Results

Use TPipe's existing extractors to parse the response:

```kotlin
// Extract JSON output (REQUIRED - will always be present)
data class MyOutput(val answer: String, val confidence: Double)
val output = extractJson<MyOutput>(response)
    ?: throw IllegalStateException("AI failed to return required JSON output")

// Extract PCP tool calls (OPTIONAL - may be empty)
val parser = PcpResponseParser()
val pcpResult = parser.extractPcpRequests(response)

if (pcpResult.success) {
    for (request in pcpResult.requests) {
        // Execute tool calls
        println("Tool call: ${request.tPipeContextOptions.functionName}")
    }
}
```

## AI Response Examples

### Case 1: Output Only (No Tools Needed)

```json
{
  "answer": "Customer John Doe is active with account status: premium",
  "confidence": 0.95
}
```

### Case 2: Output + Tool Calls

```json
{
  "answer": "Searching database for customer information...",
  "confidence": 0.0
}

[
  {
    "tPipeContextOptions": {
      "functionName": "searchDatabase"
    },
    "argumentsOrFunctionParams": ["John Doe"]
  }
]
```

## Contract

### JSON Output: REQUIRED
- The AI MUST return valid, deserializable JSON matching your schema
- All fields must have valid values (no nulls - use defaults)
- This is enforced even if no tools are called

### Tool Calls: OPTIONAL
- The AI MAY return tool call requests if needed
- Tool calls are returned as an array (can be empty)
- The AI decides when tools are necessary

## Custom Instructions

You can override the default merged mode instructions:

```kotlin
pipe.setMergedPcpJsonInstructions("""
    Custom instructions for how the AI should format
    responses when both JSON output and tools are available.
""")
```

## Mode Detection

TPipe automatically detects which mode to use:

| PCP Tools | JSON Output | Mode | Behavior |
|-----------|-------------|------|----------|
| ✅ | ✅ | **Merged** | JSON output REQUIRED, tools OPTIONAL |
| ✅ | ❌ | PCP-Only | Tool calls only |
| ❌ | ✅ | JSON-Only | JSON output only |
| ❌ | ❌ | None | No special injection |

> ℹ️ **Note:** `supportsNativeJson = true` disables JSON injection, preventing merged mode.

## Migration Guide

### Breaking Change

**Before:** Pipes with both PCP and JSON output had undefined behavior.

**After:** Pipes with both PCP and JSON output use merged mode.

### Migration Steps

If you have existing pipes with both PCP and JSON output:

1. **Update response parsing:**
   ```kotlin
   // Old (undefined behavior)
   val result = pipe.execute("task")
   // Hope for the best...
   
   // New (explicit extraction)
   val result = pipe.execute("task")
   val output = extractJson<MyOutput>(result)
   val tools = PcpResponseParser().extractPcpRequests(result)
   ```

2. **Handle both outputs:**
   ```kotlin
   // Process JSON output
   if (output != null) {
       println("Answer: ${output.answer}")
   }
   
   // Process tool calls
   if (tools.success) {
       for (tool in tools.requests) {
           // Execute tools
       }
   }
   ```

3. **If you want old behavior (not recommended):**
   - Remove one of the configurations (either PCP or JSON output)
   - Or set `supportsNativeJson = true` to disable JSON injection

## Advanced Usage

### Multiple Tool Types

```kotlin
val pcpContext = PcpContext().apply {
    // Shell commands
    addStdioOption(StdioContextOptions().apply {
        command = "ls"
        args = mutableListOf("-la")
    })
    
    // Kotlin functions
    addTPipeOption(TPipeContextOptions().apply {
        functionName = "searchDatabase"
    })
    
    // HTTP endpoints
    addHttpOption(HttpContextOptions().apply {
        baseUrl = "https://api.example.com"
        endpoint = "/search"
    })
}

pipe.setPcPContext(pcpContext)
    .requireJsonPromptInjection()
    .setJsonOutput("""{"status": "string", "data": []}""")
    .setSystemPrompt("Process request")
```

### Order Independence

Configuration order doesn't matter when using `applySystemPrompt()`:

```kotlin
// These produce the same result
pipe.setJsonOutput(schema)
    .setPcPContext(tools)
    .setSystemPrompt("prompt")

// vs

pipe.setSystemPrompt("prompt")
    .setJsonOutput(schema)
    .setPcPContext(tools)
    .applySystemPrompt()  // Rebuilds prompt with correct injections
```

## Best Practices

1. **Always validate JSON output:**
   ```kotlin
   val output = extractJson<MyOutput>(response)
       ?: throw IllegalStateException("Required JSON output missing")
   ```

2. **Handle tool calls gracefully:**
   ```kotlin
   val tools = PcpResponseParser().extractPcpRequests(response)
   if (tools.success && tools.requests.isNotEmpty()) {
       // Execute tools
   }
   ```

3. **Use type-safe data classes:**
   ```kotlin
   @Serializable
   data class MyOutput(
       val answer: String,
       val confidence: Double,
       val sources: List<String> = emptyList()
   )
   ```

4. **Provide clear tool descriptions:**
   ```kotlin
   addTPipeOption(TPipeContextOptions().apply {
       functionName = "searchDatabase"
       description = "Search customer database by name or ID. Returns customer details including status, account type, and contact information."
   })
   ```

## Troubleshooting

### AI Not Returning JSON

**Problem:** `extractJson()` returns null

**Solutions:**
- Ensure `requireJsonPromptInjection()` was called
- Check that `supportsNativeJson` is false
- Verify your JSON schema is valid
- Check AI model supports instruction following

### AI Not Calling Tools

**Problem:** Tool calls array is empty when tools should be used

**Solutions:**
- Verify PCP context is configured correctly
- Ensure tool descriptions are clear
- Check that tools are relevant to the task
- Remember: tool calls are OPTIONAL - AI decides when to use them

### Conflicting Instructions

**Problem:** AI seems confused about format

**Solutions:**
- Verify merged mode is activating (check system prompt)
- Ensure both PCP and JSON are configured
- Try custom instructions with `setMergedPcpJsonInstructions()`

## See Also

- [Pipe Context Protocol Overview](pipe-context-protocol.md)
- [JSON Schema and System Prompts](json-and-system-prompts.md)
- [Developer-in-the-Loop Functions](developer-in-the-loop.md)
