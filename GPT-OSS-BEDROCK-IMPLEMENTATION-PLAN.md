# GPT-OSS Bedrock Implementation Plan

## Overview
Implementation plan for integrating OpenAI GPT-OSS models (20B and 120B) on Amazon Bedrock within the TPipe framework. This implementation will support both OpenAI-compatible Chat Completions API and Bedrock-native Converse API paths.

## Model IDs and Availability
- `openai.gpt-oss-20b-1:0` (20B parameter model)
- `openai.gpt-oss-120b-1:0` (120B parameter model)
- **Region**: Currently available in `us-west-2` only
- **Base URL**: `https://bedrock-runtime.us-west-2.amazonaws.com/openai/v1`

## Implementation Strategy

### 1. Request Builder Architecture

#### 1.1 OpenAI Chat Completions Format
```kotlin
data class GptOssRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val temperature: Double? = null,
    val max_tokens: Int? = null,
    val top_p: Double? = null,
    val stop: List<String>? = null,
    val stream: Boolean = false,
    val reasoning_effort: String? = null // "low", "medium", "high"
)

data class ChatMessage(
    val role: String, // "system", "user", "assistant", "developer"
    val content: String
)
```

#### 1.2 Bedrock Converse API Mapping
Map TPipe parameters to Bedrock's native Converse API:
- `systemPrompt` → `SystemContentBlock.Text`
- `userPrompt` → `Message` with `ConversationRole.User`
- `temperature`, `topP`, `maxTokens` → `InferenceConfiguration`
- `stopSequences` → `InferenceConfiguration.stopSequences`

### 2. Implementation Components

#### 2.1 Extend BedrockPipe Class
Add GPT-OSS specific methods to existing `BedrockPipe`:

```kotlin
// In BedrockPipe.kt
private fun buildGptOssRequest(fullPrompt: String): String {
    val messages = mutableListOf<JsonObject>()
    
    // Add system message if present
    if (systemPrompt.isNotEmpty()) {
        messages.add(buildJsonObject {
            put("role", "system")
            put("content", systemPrompt)
        })
    }
    
    // Add user message
    messages.add(buildJsonObject {
        put("role", "user") 
        put("content", fullPrompt)
    })
    
    return buildJsonObject {
        put("model", model)
        put("messages", JsonArray(messages))
        if (temperature > 0) put("temperature", temperature)
        if (maxTokens > 0) put("max_tokens", maxTokens)
        if (topP < 1.0) put("top_p", topP)
        if (stopSequences.isNotEmpty()) put("stop", JsonArray(stopSequences.map { JsonPrimitive(it) }))
        if (useModelReasoning) put("reasoning_effort", "medium")
    }.toString()
}
```

#### 2.2 Response Parsing
Handle both OpenAI format and Bedrock format responses:

```kotlin
private fun extractGptOssResponse(responseBody: String): String {
    return try {
        val json = Json.parseToJsonElement(responseBody).jsonObject
        
        // OpenAI Chat Completions format
        json["choices"]?.jsonArray?.firstOrNull()
            ?.jsonObject?.get("message")
            ?.jsonObject?.get("content")?.jsonPrimitive?.content
            ?: ""
    } catch (e: Exception) {
        // Fallback to generic extraction
        extractTextFromResponse(responseBody, model)
    }
}
```

#### 2.3 Harmony Reasoning Support
Handle system/developer roles and chain-of-thought:

```kotlin
private fun buildGptOssHarmonyRequest(fullPrompt: String): String {
    val messages = mutableListOf<JsonObject>()
    
    // System role for instructions
    if (systemPrompt.isNotEmpty()) {
        messages.add(buildJsonObject {
            put("role", "system")
            put("content", systemPrompt)
        })
    }
    
    // Developer role for technical context (if PCP context exists)
    if (!pcpContext.tpipeOptions.isEmpty()) {
        val pcpInstructions = "Available tools: ${serialize(pcpContext, false)}"
        messages.add(buildJsonObject {
            put("role", "developer")
            put("content", pcpInstructions)
        })
    }
    
    // User message
    messages.add(buildJsonObject {
        put("role", "user")
        put("content", fullPrompt)
    })
    
    return buildJsonObject {
        put("model", model)
        put("messages", JsonArray(messages))
        if (temperature > 0) put("temperature", temperature)
        if (maxTokens > 0) put("max_tokens", maxTokens)
        if (topP < 1.0) put("top_p", topP)
        if (stopSequences.isNotEmpty()) put("stop", JsonArray(stopSequences.map { JsonPrimitive(it) }))
        put("reasoning_effort", if (useModelReasoning) "high" else "low")
    }.toString()
}
```

### 3. Integration Points

#### 3.1 Model Detection
Update `generateText()` method in `BedrockPipe`:

```kotlin
// In generateText() method, add case for GPT-OSS
val requestJson = when {
    modelId.contains("openai.gpt-oss") -> buildGptOssRequest(fullPrompt)
    modelId.contains("amazon.nova") -> buildNovaRequest(fullPrompt)
    modelId.contains("anthropic.claude") -> buildClaudeRequest(fullPrompt)
    // ... existing cases
    else -> buildGenericRequest(fullPrompt)
}
```

#### 3.2 Response Extraction
Update `extractTextFromResponse()` method:

```kotlin
// In extractTextFromResponse() method
return when {
    modelId.contains("openai.gpt-oss") -> extractGptOssResponse(responseBody)
    modelId.contains("anthropic.claude") -> extractClaudeResponse(responseBody)
    // ... existing cases
    else -> ""
}
```

#### 3.3 Converse API Support
Add GPT-OSS support to Converse API path:

```kotlin
private suspend fun generateGptOssWithConverseApi(
    client: BedrockRuntimeClient,
    modelId: String, 
    fullPrompt: String
): String {
    val messages = mutableListOf<Message>()
    
    messages.add(Message {
        role = ConversationRole.User
        content = listOf(ContentBlock.Text(fullPrompt))
    })
    
    val systemPrompts = if (systemPrompt.isNotEmpty()) {
        listOf(SystemContentBlock.Text(systemPrompt))
    } else emptyList()
    
    val converseRequest = ConverseRequest {
        this.modelId = modelId
        this.messages = messages
        if (systemPrompts.isNotEmpty()) this.system = systemPrompts
        inferenceConfig = InferenceConfiguration {
            maxTokens = this@BedrockPipe.maxTokens
            if (this@BedrockPipe.temperature > 0) temperature = this@BedrockPipe.temperature.toFloat()
            if (this@BedrockPipe.topP < 1.0) topP = this@BedrockPipe.topP.toFloat()
            if (this@BedrockPipe.stopSequences.isNotEmpty()) stopSequences = this@BedrockPipe.stopSequences
        }
    }
    
    val response = client.converse(converseRequest)
    return response.output?.asMessage()?.content?.mapNotNull { contentBlock ->
        when (contentBlock) {
            is ContentBlock.Text -> contentBlock.value
            else -> null
        }
    }?.joinToString("\n") ?: ""
}
```

### 4. Configuration Updates

#### 4.1 Environment Configuration
Update `bedrockEnv.kt` to include GPT-OSS models:

```kotlin
// Already included in createDefaultInferenceConfig():
"openai.gpt-oss-120b-1:0",
"openai.gpt-oss-20b-1:0",
```

#### 4.2 Region Validation
Add region check for GPT-OSS models:

```kotlin
private fun validateGptOssRegion(region: String): Boolean {
    return region.lowercase() == "us-west-2"
}

// In init() method, add validation:
if (model.contains("openai.gpt-oss") && !validateGptOssRegion(region)) {
    throw IllegalArgumentException("GPT-OSS models are only available in us-west-2 region")
}
```

### 5. Multimodal Support

#### 5.1 BedrockMultimodalPipe Extension
GPT-OSS models currently support text-only, but prepare for future multimodal:

```kotlin
// In BedrockMultimodalPipe.kt
private fun buildGptOssMultimodalRequest(modelId: String, content: MultimodalContent): String {
    // For now, fall back to text-only with binary content descriptions
    val textContent = StringBuilder(content.text)
    
    content.binaryContent.forEach { binaryContent ->
        when (binaryContent) {
            is BinaryContent.TextDocument -> {
                textContent.append("\n\n[Document] ${binaryContent.filename ?: "document"}: ${binaryContent.content}")
            }
            else -> {
                textContent.append("\n\n[Binary Content] ${binaryContent.getMimeType()}: ${binaryContent.getFilename() ?: "file"}")
            }
        }
    }
    
    return buildGptOssRequest(textContent.toString())
}
```

### 6. Testing Strategy

#### 6.1 Unit Tests
Create test cases for:
- Request building with various parameter combinations
- Response parsing for both success and error cases
- Region validation
- Harmony reasoning format
- Converse API mapping

#### 6.2 Integration Tests
- End-to-end text generation with both models
- Context window handling
- Temperature and parameter validation
- Error handling and fallback scenarios

### 7. Implementation Steps

1. **Phase 1: Core Request Builder** ✅ **COMPLETE**
   - ✅ Add `buildGptOssRequest()` method to `BedrockPipe`
   - ✅ Update model detection logic in `generateText()`
   - ✅ Add response extraction for GPT-OSS format

2. **Phase 2: Converse API Integration** ✅ **COMPLETE**
   - ✅ Implement `generateGptOssWithConverseApi()` method
   - ✅ Add Converse API path to main generation logic
   - ✅ Test both API paths for consistency

3. **Phase 3: Harmony Features** ✅ **COMPLETE**
   - ✅ Add developer role support for PCP context
   - ✅ Implement reasoning effort parameter
   - ✅ Add chain-of-thought handling

4. **Phase 4: Validation and Testing** ✅ **COMPLETE**
   - ✅ Add region validation for us-west-2 requirement
   - ✅ Create comprehensive test suite
   - ✅ Performance testing with both model sizes

5. **Phase 5: Documentation and Examples** ✅ **COMPLETE**
   - ✅ Update README with GPT-OSS usage examples
   - ✅ Add configuration documentation
   - ✅ Create sample applications

### 8. Code Changes Required

#### Files to Modify:
- `TPipe-Bedrock/src/main/kotlin/bedrockPipe/BedrockPipe.kt`
- `TPipe-Bedrock/src/main/kotlin/bedrockPipe/BedrockMultimodalPipe.kt`
- `TPipe-Bedrock/src/main/kotlin/env/bedrockEnv.kt`

#### New Files to Create:
- Test files for GPT-OSS specific functionality
- Example usage files
- Updated documentation

### 9. Compatibility Considerations

- **Backward Compatibility**: All existing TPipe functionality remains unchanged
- **API Consistency**: GPT-OSS models work with same TPipe interface as other models
- **Parameter Mapping**: Standard TPipe parameters map appropriately to GPT-OSS API
- **Error Handling**: Graceful fallback for unsupported features

### 10. Future Enhancements

- **Streaming Support**: Add streaming response handling
- **Function Calling**: Implement when GPT-OSS supports tool use
- **Multimodal**: Add native multimodal support when available
- **Fine-tuning**: Support for custom GPT-OSS model variants

This implementation plan provides a comprehensive approach to integrating GPT-OSS models while maintaining the existing TPipe architecture and ensuring compatibility with all current features.