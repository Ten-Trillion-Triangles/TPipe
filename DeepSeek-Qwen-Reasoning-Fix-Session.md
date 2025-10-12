# DeepSeek & Qwen Reasoning Fix Session Documentation

## Session Overview
Fixed reasoning content extraction and tracing issues for DeepSeek and Qwen models in TPipe Bedrock integration. The main issues were:
1. DeepSeek Converse API not properly extracting reasoning content
2. Qwen models not working with Invoke API due to wrong request format
3. Missing reasoning metadata in trace files
4. Qwen models falling back to generic request builder instead of proper Qwen builder

## Files Modified

### 1. `/home/cage/Desktop/Workspaces/TPipe/TPipe/TPipe-Bedrock/src/main/kotlin/bedrockPipe/BedrockPipe.kt`

#### Change 1: Fixed DeepSeek Converse reasoning extraction
**Location:** `generateTextWithConverseApi()` method (~line 1661)
**Problem:** Used complex reflection-based parsing that often failed
**Solution:** Replaced with proper `extractReasoningFromConverseResponse()` helper function and added comprehensive tracing

```kotlin
// OLD: Complex reflection-based parsing
// NEW: Clean helper function usage with tracing
val reasoningContent = extractReasoningFromConverseResponse(response)
```

#### Change 2: Enhanced Qwen Converse reasoning extraction  
**Location:** `generateWithConverseApi()` method (~line 2345)
**Problem:** Only collected Text blocks, ignored reasoning content
**Solution:** Added `extractReasoningFromConverseResponse()` call and Qwen thinking block stripping

```kotlin
// Added reasoning extraction for all Converse API calls
val reasoningContent = extractReasoningFromConverseResponse(response)

// Strip <think> blocks from Qwen main text
if (modelId.contains("qwen.qwen3")) {
    text = text.replace(Regex("<think>.*?</think>", RegexOption.DOT_MATCHES_ALL), "").trim()
}
```

#### Change 3: Updated reasoning extraction helper
**Location:** `extractReasoningFromConverseResponse()` method (~line 1866)  
**Problem:** Only handled ReasoningContent blocks
**Solution:** Added support for Qwen `<think>...</think>` blocks in Text content

```kotlin
is ContentBlock.Text -> {
    val text = contentBlock.value
    val thinkPattern = Regex("<think>(.*?)</think>", RegexOption.DOT_MATCHES_ALL)
    thinkPattern.findAll(text).forEach { match ->
        reasoningParts.add(match.groupValues[1].trim())
    }
}
```

#### Change 4: Enhanced streaming reasoning capture
**Location:** `executeConverseStream()` method (~line 2591)
**Problem:** Missing reasoning metadata in streaming traces  
**Solution:** Added `modelSupportsReasoning` and `reasoningEnabled` flags to metadata

```kotlin
"modelSupportsReasoning" to (modelId.contains("deepseek") || modelId.contains("qwen.qwen3") || modelId.contains("anthropic.claude")),
"reasoningEnabled" to useModelReasoning
```

#### Change 5: Improved Invoke API reasoning extraction
**Location:** `extractReasoningContent()` method (~line 3018)
**Problem:** Qwen thinking blocks not extracted from Invoke API responses
**Solution:** Enhanced Qwen reasoning extraction to handle multiple locations and `<think>` blocks

```kotlin
modelId.contains("qwen.qwen3") -> {
    val reasoningParts = mutableListOf<String>()
    
    // Check standard locations + extract <think> blocks from content
    json["choices"]?.jsonArray?.firstOrNull()?.jsonObject?.get("message")?.jsonObject?.get("content")?.jsonPrimitive?.content?.let { content ->
        val thinkPattern = Regex("<think>(.*?)</think>", RegexOption.DOT_MATCHES_ALL)
        thinkPattern.findAll(content).forEach { match ->
            reasoningParts.add(match.groupValues[1].trim())
        }
    }
    
    reasoningParts.joinToString("\n")
}
```

#### Change 6: Fixed Qwen text extraction  
**Location:** `extractTextFromResponse()` method (~line 3097)
**Problem:** Thinking blocks included in main response text
**Solution:** Strip `<think>` blocks from Qwen response text

```kotlin
modelId.contains("qwen.qwen3") -> {
    val content = json["choices"]?.jsonArray?.firstOrNull()?.jsonObject?.get("message")?.jsonObject?.get("content")?.jsonPrimitive?.content ?: ""
    // Strip <think>...</think> blocks from main response text
    content.replace(Regex("<think>.*?</think>", RegexOption.DOT_MATCHES_ALL), "").trim()
}
```

#### Change 7: Enhanced tracing consistency
**Location:** Multiple methods (`generateText`, `generateContent`, etc.)
**Problem:** Inconsistent reasoning metadata in traces
**Solution:** Added `hasReasoning`, `modelSupportsReasoning`, `reasoningEnabled` flags consistently

```kotlin
if (reasoningContent.isNotEmpty()) {
    responseMetadata["reasoningContent"] = reasoningContent
    responseMetadata["hasReasoning"] = true
    responseMetadata["modelSupportsReasoning"] = true
    responseMetadata["reasoningEnabled"] = useModelReasoning
}
```

#### Change 8: Fixed Qwen request builder format
**Location:** `buildQwenRequest()` method (~line 1532)
**Problem:** Used unsupported parameters (`top_k`, `enable_thinking`, `system` field)
**Solution:** Removed unsupported parameters and moved system prompt to messages array

```kotlin
// OLD: Separate system field (not supported)
put("system", systemPrompt)

// NEW: System message in messages array  
messages.add(buildJsonObject {
    put("role", "system")
    put("content", systemPrompt)
})
```

#### Change 9: Made buildQwenRequest accessible
**Location:** `buildQwenRequest()` method declaration
**Problem:** Private method not accessible from BedrockMultimodalPipe
**Solution:** Changed from `private` to `protected`

```kotlin
// OLD: private fun buildQwenRequest(prompt: String): String
// NEW: protected fun buildQwenRequest(prompt: String): String
```

#### Change 10: Fixed generic builder for Qwen fallback
**Location:** `buildGenericRequest()` method (~line 1785)
**Problem:** Generic builder used wrong format for Qwen models
**Solution:** Added special Qwen handling in generic builder

```kotlin
// Special handling for Qwen models that need OpenAI format
if (model.contains("qwen")) {
    return buildJsonObject {
        put("messages", JsonArray(listOf(
            buildJsonObject {
                put("role", "user")
                put("content", prompt)
            }
        )))
        // ... proper Qwen parameters
    }.toString()
}
```

### 2. `/home/cage/Desktop/Workspaces/TPipe/TPipe/TPipe-Bedrock/src/main/kotlin/bedrockPipe/BedrockMultimodalPipe.kt`

#### Change 11: Added Qwen support to multimodal requests
**Location:** `buildMultimodalRequest()` method (last method in file)
**Problem:** Qwen models fell back to generic builder in multimodal pipe
**Solution:** Added explicit Qwen case to use proper `buildQwenRequest()`

```kotlin
// OLD: Only handled GPT-OSS, Claude, Nova
return when {
    modelId.contains("openai.gpt-oss") -> buildGptOssRequest(textContent.toString())
    modelId.contains("anthropic.claude") -> buildClaudeRequest(textContent.toString())
    modelId.contains("amazon.nova") -> buildNovaRequest(textContent.toString())
    else -> buildGenericRequest(textContent.toString())
}

// NEW: Added Qwen support
return when {
    modelId.contains("openai.gpt-oss") -> buildGptOssRequest(textContent.toString())
    modelId.contains("anthropic.claude") -> buildClaudeRequest(textContent.toString())
    modelId.contains("amazon.nova") -> buildNovaRequest(textContent.toString())
    modelId.contains("qwen") -> buildQwenRequest(textContent.toString())
    else -> buildGenericRequest(textContent.toString())
}
```

## Root Cause Analysis

### Primary Issue: BedrockMultimodalPipe Override
The main issue was that `BedrockMultimodalPipe` overrides `generateContent()` and has its own request building logic in `buildMultimodalRequest()`. This method only handled specific models (GPT-OSS, Claude, Nova) and fell back to `buildGenericRequest()` for everything else, including Qwen.

### Secondary Issues:
1. **DeepSeek Converse**: Used reflection instead of proper helper functions
2. **Qwen Request Format**: Invoke API doesn't support Converse API parameters
3. **Reasoning Extraction**: Missing calls to reasoning extraction helpers
4. **Tracing Metadata**: Inconsistent reasoning flags across different code paths

## Testing Results

### Before Fixes:
- Qwen models: API errors due to wrong request format (`prompt` field rejected)
- DeepSeek Converse: Empty reasoning content in traces
- Missing reasoning metadata in all traces

### After Fixes:
- Qwen models: Working with Invoke API (reasoning only works in Converse API)
- DeepSeek Converse: Proper reasoning extraction and tracing
- Consistent reasoning metadata across all supported models
- Proper separation of reasoning content from main response text

## Notes

1. **Qwen Reasoning Limitation**: Qwen models only support reasoning (`enable_thinking`) in Converse API, not Invoke API
2. **API Compatibility**: Some models have different parameter support between Converse and Invoke APIs
3. **Inheritance Complexity**: BedrockMultimodalPipe override required separate fixes from base BedrockPipe
4. **Debugging Challenge**: Exception handling masked the real issue, making it hard to trace the problem

## Verification

To verify the fixes work:
1. Test Qwen models with `useModelReasoning: true` and `.useConverseApi()` - should show reasoning in traces
2. Test DeepSeek models with Converse API - should show reasoning content
3. Check trace files for `reasoningContent`, `hasReasoning`, `modelSupportsReasoning` fields
4. Verify reasoning content is separate from main response text
