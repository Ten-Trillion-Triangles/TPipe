# Bedrock Converse API Code Elimination Plan (Revised)

## Problem Analysis

**Current Architecture Issue**: BedrockMultimodalPipe duplicates all request building logic instead of reusing the 13 model-specific builders from BedrockPipe, causing:
- Stop sequences failures for Qwen models
- Code duplication and maintenance burden
- Loss of model-specific optimizations

**Root Cause**: BedrockPipe builders only accept `String` prompts, but multimodal needs `List<ContentBlock>` for binary content.

## Separation of Concerns

**BedrockPipe Responsibilities**:
- Model-specific request building
- Stop sequences and model parameters
- Inference configuration

**BedrockMultimodalPipe Responsibilities**:
- Binary content conversion (images, documents, S3)
- Multimodal content handling
- Response processing for binary content

## Solution Strategy

### Phase 1: Enhance BedrockPipe Builders for ContentBlocks

#### 1.1 Add ContentBlock Overloads to Existing Builders
```kotlin
// In BedrockPipe.kt - Add overload, keep existing String version
protected fun buildQwenConverseRequest(contentBlocks: List<ContentBlock>): ConverseRequest {
    val messages = mutableListOf<Message>()
    
    messages.add(Message {
        role = ConversationRole.User
        content = contentBlocks  // Accept ContentBlocks instead of converting from string
    })
    
    val systemBlocks = mutableListOf<SystemContentBlock>()
    if (systemPrompt.isNotEmpty()) {
        systemBlocks.add(SystemContentBlock.Text(systemPrompt))
    }
    
    if (!pcpContext.tpipeOptions.isEmpty()) {
        val pcpInstructions = "Available tools: ${com.TTT.Util.serialize(pcpContext, false)}"
        systemBlocks.add(SystemContentBlock.Text(pcpInstructions))
    }
    
    return ConverseRequest {
        this.modelId = model
        this.messages = messages
        if (systemBlocks.isNotEmpty()) this.system = systemBlocks
        
        inferenceConfig = InferenceConfiguration {
            maxTokens = this@BedrockPipe.maxTokens
            if (this@BedrockPipe.temperature > 0) temperature = this@BedrockPipe.temperature.toFloat()
            if (this@BedrockPipe.topP < 1.0) topP = this@BedrockPipe.topP.toFloat()
        }
        
        // Existing Qwen-specific additionalModelRequestFields logic
        if (useModelReasoning || topK > 0 || this@BedrockPipe.stopSequences.isNotEmpty()) {
            try {
                val documentMap = mutableMapOf<String, Any>()
                if (this@BedrockPipe.topK > 0) documentMap["top_k"] = this@BedrockPipe.topK
                if (this@BedrockPipe.stopSequences.isNotEmpty()) documentMap["stop"] = this@BedrockPipe.stopSequences
                if (useModelReasoning) {
                    documentMap["enable_thinking"] = true
                } else {
                    documentMap["enable_thinking"] = false
                }
                
                val documentClass = Document::class.java
                val document = documentClass.getDeclaredConstructor().newInstance()
                val mapField = documentClass.getDeclaredField("value")
                mapField.isAccessible = true
                mapField.set(document, documentMap)
                
                additionalModelRequestFields = document
            } catch (e: Exception) {
                addTrace("Failed to set Qwen additionalModelRequestFields: ${e.message}")
            }
        }
    }
}

// Keep existing string version for backward compatibility
private fun buildQwenConverseRequest(prompt: String): ConverseRequest {
    return buildQwenConverseRequest(listOf(ContentBlock.Text(prompt)))
}
```

### Phase 2: Update BedrockMultimodalPipe to Use Parent Builders

#### 2.1 Replace generateMultimodalWithConverseApi()
```kotlin
// In BedrockMultimodalPipe.kt - REPLACE entire function
private suspend fun generateMultimodalWithConverseApi(
    client: BedrockRuntimeClient, 
    modelId: String, 
    content: MultimodalContent
): MultimodalContent {
    
    // Convert multimodal content to ContentBlocks (STAYS in this class)
    val contentBlocks = mutableListOf<ContentBlock>()
    
    // Add text content
    if (content.text.isNotEmpty()) {
        contentBlocks.add(ContentBlock.Text(content.text))
    }
    
    // Add binary content using existing conversion logic (STAYS here)
    content.binaryContent.forEach { binaryContent ->
        val contentBlock = convertBinaryToContentBlock(binaryContent)
        if (contentBlock != null) {
            contentBlocks.add(contentBlock)
        }
    }
    
    // Use parent class builders (ELIMINATES duplicate logic)
    val converseRequest = when {
        modelId.contains("qwen") -> buildQwenConverseRequest(contentBlocks)
        modelId.contains("anthropic.claude") -> buildClaudeConverseRequest(contentBlocks)
        modelId.contains("amazon.nova") -> buildNovaConverseRequest(contentBlocks)
        modelId.contains("deepseek") -> buildDeepSeekConverseRequestObject(modelId, contentBlocks)
        modelId.contains("openai.gpt-oss") -> buildGptOssConverseRequest(modelId, contentBlocks)
        else -> buildGenericConverseRequest(contentBlocks)
    }
    
    // Execute API call and process response (existing logic STAYS here)
    val response = client.converse(converseRequest)
    val outputMessage = response.output?.asMessage()
    val responseContent = outputMessage?.content
    
    // Extract response content (existing logic STAYS here)
    val responseText = mutableListOf<String>()
    val responseBinaryContent = mutableListOf<BinaryContent>()
    
    responseContent?.forEach { contentBlock ->
        when (contentBlock) {
            is ContentBlock.Text -> responseText.add(contentBlock.value)
            is ContentBlock.Image -> {
                // Existing image processing logic
            }
            is ContentBlock.Document -> {
                // Existing document processing logic  
            }
        }
    }
    
    // Extract reasoning (existing logic STAYS here)
    val reasoningContent = extractReasoningFromResponse(response, modelId)
    
    return MultimodalContent(
        text = responseText.joinToString("\n"),
        binaryContent = responseBinaryContent,
        modelReasoning = reasoningContent
    )
}
```

### Phase 3: Enhance All 13 Builders

Add ContentBlock overloads for each builder in BedrockPipe.kt:
- `buildClaudeConverseRequest(contentBlocks: List<ContentBlock>)`
- `buildNovaConverseRequest(contentBlocks: List<ContentBlock>)`
- `buildDeepSeekConverseRequestObject(modelId: String, contentBlocks: List<ContentBlock>)`
- `buildGptOssConverseRequest(modelId: String, contentBlocks: List<ContentBlock>)`
- ... all others

Change visibility from `private` to `protected` for multimodal access.

### Phase 4: Remove Redundant Code

#### 4.1 Delete from BedrockMultimodalPipe
- Remove duplicate `InferenceConfiguration` building
- Remove duplicate model-specific parameter handling
- Remove duplicate system prompt handling

#### 4.2 Keep in BedrockMultimodalPipe
- `convertBinaryToContentBlock()` and all helper functions
- Response processing for binary content
- Multimodal-specific logic

## Implementation Steps

### Step 1: Enhance Qwen Builder
1. Add `buildQwenConverseRequest(contentBlocks: List<ContentBlock>)` overload to BedrockPipe
2. Change visibility from `private` to `protected`
3. Keep existing string version for backward compatibility

### Step 2: Update BedrockMultimodalPipe for Qwen
1. Modify `generateMultimodalWithConverseApi()` to call parent Qwen builder
2. Remove duplicate Qwen-specific logic
3. Test Qwen models with stop sequences and binary content

### Step 3: Enhance Remaining Builders
1. Add ContentBlock overloads for all 12 remaining builders
2. Update BedrockMultimodalPipe routing to use all parent builders
3. Remove all duplicate request building logic

### Step 4: Clean Up and Test
1. Verify all binary content handling preserved
2. Test all model types with multimodal content
3. Confirm stop sequences work for all models

## Files to Modify

### BedrockPipe.kt
- Add ContentBlock overloads for all 13 builders
- Change builder visibility from `private` to `protected`
- Keep all existing model-specific logic

### BedrockMultimodalPipe.kt  
- Replace `generateMultimodalWithConverseApi()` to use parent builders
- Remove duplicate request building logic
- Keep all binary content conversion logic

## Success Criteria

1. **Code duplication eliminated** - single source of truth for request building ✅
2. **Qwen models work with stop sequences** in multimodal mode ✅
3. **Binary content fully preserved** - images, documents, S3 references ✅
4. **Proper separation of concerns** maintained ✅
5. **No regressions** in existing functionality ✅

## Class Responsibilities After Refactor

**BedrockPipe**: Model expertise (stop sequences, parameters, inference config)
**BedrockMultimodalPipe**: Binary content expertise (images, documents, response processing)

**No cross-contamination** - each class keeps its domain knowledge.
