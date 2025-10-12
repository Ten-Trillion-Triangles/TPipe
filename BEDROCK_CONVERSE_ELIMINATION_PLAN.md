# Bedrock Converse API Code Elimination Plan (Revised)

## Problem Analysis

**Current Architecture Issue**: BedrockMultimodalPipe duplicates all request building logic instead of reusing the 13 model-specific builders from BedrockPipe, causing:
- Stop sequences failures for Qwen models
- Code duplication and maintenance burden
- Loss of model-specific optimizations

**Root Cause**: BedrockPipe builders only accept `String` prompts, but multimodal needs `List<ContentBlock>` for binary content.

## Critical Requirements

1. **Eliminate redundant request building** in BedrockMultimodalPipe
2. **Preserve binary content handling** (images, documents, S3 references)
3. **Fix Qwen stop sequences** by using existing model-specific logic
4. **Maintain all existing functionality** without breaking changes

## Solution Strategy

### Phase 1: Enhance BedrockPipe Builders for ContentBlocks

#### 1.1 Create Enhanced Builder Interface
```kotlin
// Add to BedrockPipe.kt
private fun buildQwenConverseRequest(contentBlocks: List<ContentBlock>): ConverseRequest {
    val messages = mutableListOf<Message>()
    
    messages.add(Message {
        role = ConversationRole.User
        content = contentBlocks  // Accept ContentBlocks instead of just text
    })
    
    // Rest of existing Qwen-specific logic remains unchanged
    val systemBlocks = mutableListOf<SystemContentBlock>()
    if (systemPrompt.isNotEmpty()) {
        systemBlocks.add(SystemContentBlock.Text(systemPrompt))
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
            // ... existing Document creation logic
        }
    }
}
```

#### 1.2 Add ContentBlock Conversion Utility
```kotlin
// Add to BedrockPipe.kt
private fun convertMultimodalToContentBlocks(content: MultimodalContent): List<ContentBlock> {
    val contentBlocks = mutableListOf<ContentBlock>()
    
    // Add text content
    if (content.text.isNotEmpty()) {
        contentBlocks.add(ContentBlock.Text(content.text))
    }
    
    // Add binary content using existing conversion logic from BedrockMultimodalPipe
    content.binaryContent.forEach { binaryContent ->
        convertBinaryToContentBlock(binaryContent)?.let { contentBlocks.add(it) }
    }
    
    return contentBlocks
}
```

#### 1.3 Move Binary Conversion Logic to BedrockPipe
```kotlin
// Move convertBinaryToContentBlock() from BedrockMultimodalPipe to BedrockPipe
// This preserves all existing binary handling logic
private fun convertBinaryToContentBlock(binaryContent: BinaryContent): ContentBlock? {
    // Exact same implementation as in BedrockMultimodalPipe
    return when (binaryContent) {
        is BinaryContent.Bytes -> {
            when {
                binaryContent.getMimeType().startsWith("image/") -> {
                    // ... existing image handling
                }
                isDocumentMimeType(binaryContent.getMimeType()) -> {
                    // ... existing document handling
                }
                else -> null
            }
        }
        // ... rest of existing logic
    }
}
```

### Phase 2: Update BedrockMultimodalPipe to Use Builders

#### 2.1 Replace generateMultimodalWithConverseApi()
```kotlin
// In BedrockMultimodalPipe.kt - REPLACE entire function
private suspend fun generateMultimodalWithConverseApi(
    client: BedrockRuntimeClient, 
    modelId: String, 
    content: MultimodalContent
): MultimodalContent {
    
    // Convert multimodal content to ContentBlocks
    val contentBlocks = convertMultimodalToContentBlocks(content)
    
    // Use existing model-specific builder from parent class
    val converseRequest = when {
        modelId.contains("qwen") -> buildQwenConverseRequest(contentBlocks)
        modelId.contains("anthropic.claude") -> buildClaudeConverseRequest(contentBlocks)
        modelId.contains("amazon.nova") -> buildNovaConverseRequest(contentBlocks)
        modelId.contains("deepseek") -> buildDeepSeekConverseRequestObject(modelId, contentBlocks)
        // ... all other existing model builders
        else -> buildGenericConverseRequest(contentBlocks)
    }
    
    // Execute API call and process response (existing logic)
    val response = client.converse(converseRequest)
    return convertResponseToMultimodalContent(response, content)
}
```

#### 2.2 Enhance All 13 Builders
Update each builder in BedrockPipe.kt to accept `List<ContentBlock>`:
- `buildQwenConverseRequest(contentBlocks: List<ContentBlock>)`
- `buildClaudeConverseRequest(contentBlocks: List<ContentBlock>)`
- `buildNovaConverseRequest(contentBlocks: List<ContentBlock>)`
- `buildDeepSeekConverseRequestObject(modelId: String, contentBlocks: List<ContentBlock>)`
- ... all others

### Phase 3: Remove Redundant Code

#### 3.1 Delete Duplicate Logic from BedrockMultimodalPipe
- Remove duplicate `InferenceConfiguration` building
- Remove duplicate model-specific parameter handling
- Keep only response processing and binary content conversion

#### 3.2 Preserve Essential Multimodal Functions
**KEEP these functions** (they're not redundant):
- `convertBinaryToContentBlock()` → Move to BedrockPipe
- `getImageFormat()`, `getDocumentFormat()` → Move to BedrockPipe
- `isDocumentMimeType()` → Move to BedrockPipe
- Response processing logic for binary content

## Implementation Steps

### Step 1: Move Binary Logic to BedrockPipe
1. Copy `convertBinaryToContentBlock()` and helper functions from BedrockMultimodalPipe to BedrockPipe
2. Add `convertMultimodalToContentBlocks()` function to BedrockPipe
3. Test that binary content conversion still works

### Step 2: Enhance One Builder (Qwen)
1. Create `buildQwenConverseRequest(contentBlocks: List<ContentBlock>)` overload
2. Keep existing `buildQwenConverseRequest(prompt: String)` for backward compatibility
3. Test Qwen models with stop sequences and binary content

### Step 3: Update BedrockMultimodalPipe to Use Enhanced Builder
1. Modify `generateMultimodalWithConverseApi()` to call enhanced Qwen builder
2. Test that Qwen multimodal generation works with stop sequences
3. Verify no regression in binary content handling

### Step 4: Enhance Remaining Builders
1. Update all 12 remaining builders to accept ContentBlocks
2. Update BedrockMultimodalPipe routing to use all enhanced builders
3. Remove duplicate request building logic

### Step 5: Clean Up
1. Remove redundant code from BedrockMultimodalPipe
2. Add comprehensive tests
3. Update documentation

## Files to Modify

### Primary Changes
1. **BedrockPipe.kt** - Add binary conversion logic, enhance all 13 builders
2. **BedrockMultimodalPipe.kt** - Remove duplicate logic, use parent builders

### No Breaking Changes
- All existing public APIs remain unchanged
- Backward compatibility maintained
- Internal refactoring only

## Success Criteria

1. **Qwen models work with stop sequences** in multimodal mode ✅
2. **Binary content fully preserved** - images, documents, S3 references ✅
3. **Code duplication eliminated** - single source of truth for request building ✅
4. **All 13 model builders enhanced** to support multimodal content ✅
5. **No regressions** in existing functionality ✅

## Risk Mitigation

### Critical Preservation
- **Binary content handling logic** moved, not deleted
- **All model-specific parameters** preserved in enhanced builders
- **Response processing** kept in BedrockMultimodalPipe

### Testing Strategy
- Test each builder enhancement individually
- Verify binary content with all model types
- Regression test all existing multimodal functionality

## Estimated Effort
- **Step 1-3**: 2-3 hours (critical Qwen fix)
- **Step 4**: 3-4 hours (enhance remaining builders)  
- **Step 5**: 1-2 hours (cleanup and testing)
- **Total**: 6-9 hours

This plan **eliminates redundant code** while **preserving all binary content functionality** and **fixing the Qwen stop sequences issue** through proper architectural design.
