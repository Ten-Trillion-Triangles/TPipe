# Bedrock Converse API Refactor Plan

## Problem Analysis

The current Bedrock implementation has **architectural inconsistencies** that cause stop sequences to fail for certain models (particularly Qwen). The core issues are:

### 1. **Multimodal Pipe Bypasses Model-Specific Logic**
- **BedrockPipe**: Has 13 different Converse request builders with model-specific stop sequence handling
- **BedrockMultimodalPipe**: Bypasses all builders and creates requests directly with generic `stopSequences` parameter
- **Result**: Qwen models fail in multimodal mode because multimodal pipe uses unsupported `stopSequences` instead of model-specific `"stop"` parameter

### 2. **Architecture Understanding**
```
Pipe (base class)
├── BedrockPipe (text-only, has model-specific builders)
└── BedrockMultimodalPipe extends BedrockPipe (binary content support, ignores parent builders)
```

**BedrockPipe.generateContent()**: Text-only, converts text result to MultimodalContent wrapper
**BedrockMultimodalPipe.generateContent()**: Handles actual binary content (images, documents)

### 3. **Code Duplication**
- Legacy wrapper functions (`handleConverseGeneration`, `handleGptOssConverse`, etc.) duplicate streaming logic
- Multimodal pipe reimplements request building instead of reusing parent builders
- Two separate streaming implementations doing the same work

### 4. **The Actual Issue**
- **Text-only generation**: ✅ Works correctly with model-specific builders
- **Multimodal generation**: ❌ Fails for Qwen models due to generic `stopSequences` usage

**Root Cause**: `BedrockMultimodalPipe.generateMultimodalWithConverseApi()` at line 182:
```kotlin
// PROBLEMATIC - Uses generic stopSequences for all models
if (this@BedrockMultimodalPipe.stopSequences.isNotEmpty()) stopSequences = this@BedrockMultimodalPipe.stopSequences
```

## Refactor Strategy

### Phase 1: Fix Multimodal Stop Sequences (HIGH PRIORITY)

#### 1.1 Add Model-Specific Logic to Multimodal Pipe
**Fix the problematic code** in `BedrockMultimodalPipe.generateMultimodalWithConverseApi()`:

```kotlin
// CURRENT BROKEN CODE:
inferenceConfig = InferenceConfiguration {
    maxTokens = this@BedrockMultimodalPipe.maxTokens
    if (this@BedrockMultimodalPipe.temperature > 0) temperature = this@BedrockMultimodalPipe.temperature.toFloat()
    if (this@BedrockMultimodalPipe.topP < 1.0) topP = this@BedrockMultimodalPipe.topP.toFloat()
    if (this@BedrockMultimodalPipe.stopSequences.isNotEmpty()) stopSequences = this@BedrockMultimodalPipe.stopSequences  // BREAKS QWEN
}

// FIXED CODE:
inferenceConfig = InferenceConfiguration {
    maxTokens = this@BedrockMultimodalPipe.maxTokens
    if (this@BedrockMultimodalPipe.temperature > 0) temperature = this@BedrockMultimodalPipe.temperature.toFloat()
    if (this@BedrockMultimodalPipe.topP < 1.0) topP = this@BedrockMultimodalPipe.topP.toFloat()
    
    // Model-specific stop sequence handling
    if (this@BedrockMultimodalPipe.stopSequences.isNotEmpty()) {
        when {
            modelId.contains("qwen") -> {
                // Qwen models don't support stopSequences in InferenceConfiguration
                // Will be handled in additionalModelRequestFields below
            }
            else -> stopSequences = this@BedrockMultimodalPipe.stopSequences
        }
    }
}

// Add Qwen-specific stop sequences handling
if (modelId.contains("qwen") && this@BedrockMultimodalPipe.stopSequences.isNotEmpty()) {
    additionalModelRequestFields = Document.fromMap(mapOf("stop" to this@BedrockMultimodalPipe.stopSequences))
}
```

### Phase 2: Create Unified Builder System (MEDIUM PRIORITY)

#### 2.1 Enhance Builder Interface for Binary Content
```kotlin
interface ConverseRequestBuilder {
    fun buildRequest(
        modelId: String,
        contentBlocks: List<ContentBlock>,  // Supports text + binary
        systemBlocks: List<SystemContentBlock>,
        inferenceConfig: InferenceConfiguration
    ): ConverseRequest
}
```

#### 2.2 Extract Binary Content Conversion
```kotlin
object ContentBlockConverter {
    fun convertMultimodalContent(content: MultimodalContent): List<ContentBlock> {
        val blocks = mutableListOf<ContentBlock>()
        
        if (content.text.isNotEmpty()) {
            blocks.add(ContentBlock.Text(content.text))
        }
        
        content.binaryContent.forEach { binaryContent ->
            convertBinaryToContentBlock(binaryContent)?.let { blocks.add(it) }
        }
        
        return blocks
    }
    
    // Move from BedrockMultimodalPipe to shared utility
    private fun convertBinaryToContentBlock(binaryContent: BinaryContent): ContentBlock? {
        // Existing conversion logic
    }
}
```

#### 2.3 Update Multimodal Pipe to Use Enhanced Builders
```kotlin
private suspend fun generateMultimodalWithConverseApi(
    client: BedrockRuntimeClient, 
    modelId: String, 
    content: MultimodalContent
): MultimodalContent {
    // Convert content to ContentBlocks (preserve existing binary handling)
    val contentBlocks = ContentBlockConverter.convertMultimodalContent(content)
    val systemBlocks = if (systemPrompt.isNotEmpty()) {
        listOf(SystemContentBlock.Text(systemPrompt))
    } else emptyList()
    
    // Use enhanced builder system
    val builder = ConverseRequestBuilderFactory.getBuilder(modelId)
    val converseRequest = builder.buildRequest(modelId, contentBlocks, systemBlocks, createInferenceConfig())
    
    val response = client.converse(converseRequest)
    return convertResponseToMultimodalContent(response, content)
}
```

### Phase 3: Remove Legacy Code (LOW PRIORITY)

#### 3.1 Delete Commented Legacy Functions
Remove these already-commented functions:
- `handleConverseGeneration()`
- `handleGptOssConverse()`
- `handleDeepSeekConverse()`
- `handleGenericConverse()`

#### 3.2 Consolidate Streaming Logic
- Keep unified streaming in main generation methods
- Remove duplicate streaming implementations

## Implementation Priority

### **CRITICAL (Fix Immediately)**
1. **Fix Qwen stop sequences in BedrockMultimodalPipe** - Add model-specific logic to `generateMultimodalWithConverseApi()`
2. **Test multimodal Qwen models** with stop sequences
3. **Verify binary content still works** after fix

### **MEDIUM (Next Sprint)**
1. Create enhanced builder interface supporting ContentBlocks
2. Extract ContentBlockConverter utility
3. Update multimodal pipe to use unified builders

### **LOW (Technical Debt)**
1. Remove commented legacy functions
2. Consolidate streaming logic
3. Add comprehensive tests

## Files to Modify

### **Immediate Changes**
- `BedrockMultimodalPipe.kt` - Fix `generateMultimodalWithConverseApi()` stop sequences handling

### **Phase 2 Changes**
- `BedrockPipe.kt` - Enhance builders to support ContentBlocks
- `BedrockMultimodalPipe.kt` - Use enhanced builders
- New file: `ContentBlockConverter.kt` - Shared binary conversion utilities

### **Phase 3 Changes**
- `BedrockPipe.kt` - Remove commented legacy functions
- Clean up documentation

## Testing Strategy

### **Critical Tests**
- **Qwen models with stop sequences** in multimodal mode
- **Binary content preservation** - Ensure images/documents work
- **All model families** with multimodal content

### **Integration Tests**
- End-to-end multimodal reasoning pipes
- Mixed content (text + images + documents)
- Streaming with binary content

### **Regression Tests**
- All existing multimodal functionality
- Text-only generation still works
- Performance with large binary content

## Success Criteria

1. **Qwen models work with stop sequences** in multimodal mode
2. **Binary content fully preserved** - No loss of images/documents
3. **Unified architecture** - Same builder system for text and multimodal (future)
4. **No regressions** in existing functionality
5. **Clean codebase** - Remove legacy commented code

## Risk Mitigation

### **Functionality Preservation**
- **Preserve all existing binary content handling**
- Test extensively before removing any code
- Rollback plan if multimodal functionality breaks

### **Breaking Changes**
- Phase 1 is additive only - no breaking changes
- Phase 2 changes are internal refactoring
- Keep deprecated wrappers during transition

---

**Estimated Effort**: 1-2 sprints
**Risk Level**: Low-Medium (well-defined fix, preserve existing functionality)
**Business Impact**: High (fixes critical Qwen multimodal functionality)
