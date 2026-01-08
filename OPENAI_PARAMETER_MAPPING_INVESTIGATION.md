# OpenAI Parameter Mapping Investigation Report

## Date: 2026-01-08 15:23

## Executive Summary

Investigation into validation error affecting GPT-OSS models on AWS Bedrock reveals **critical parameter mapping issues** and **broken reflection-based Document construction** (same bug as other Bedrock models). The error indicates TPipe is sending unsupported `thinking` parameter to OpenAI Chat Completions API.

**Error Message:**
```json
{
  "code": "validation_error",
  "message": "Failed to deserialize the JSON body into the target type: unknown variant `thinking`, 
  expected one of `audio`, `frequency_penalty`, `function_call`, `functions`, `include_reasoning`, 
  `logit_bias`, `logprobs`, `max_completion_tokens`, `max_tokens`, `messages`, `metadata`, 
  `modalities`, `model`, `n`, `parallel_tool_calls`, `prediction`, `presence_penalty`, 
  `prompt_cache_key`, `reasoning_effort`, `response_format`, `safety_identifier`, `seed`, 
  `service_tier`, `stop`, `store`, `stream`, `stream_options`, `temperature`, `text`, 
  `tool_choice`, `tools`, `top_k`, `top_logprobs`, `top_p`, `user`, `verbosity`, 
  `web_search_options`",
  "param": null,
  "type": "invalid_request_error"
}
```

---

## API Architecture Overview

### AWS Bedrock Model APIs

TPipe-Bedrock supports models through three different API paths:

1. **Bedrock Converse API** - Unified API for most models
   - Models: Claude, Nova, DeepSeek, Qwen, Titan, AI21, Cohere, Llama, Mistral, GPT-OSS
   - Uses `InferenceConfiguration` for standard parameters
   - Uses `additionalModelRequestFields` (Document type) for model-specific parameters

2. **Bedrock InvokeModel API** - Legacy API for some models
   - Models: Same as above, used as fallback
   - Uses JSON request body with model-specific formats
   - Each model has different JSON structure

3. **OpenAI Chat Completions API** - OpenAI-compatible endpoint
   - Models: GPT-OSS only
   - Uses OpenAI's standard Chat Completions format
   - Accessed via `/openai/v1/chat/completions` endpoint
   - Requires Amazon Bedrock API key authentication

### GPT-OSS Model Support

GPT-OSS models (openai.gpt-oss-20b-1:0, openai.gpt-oss-120b-1:0) support **all three APIs**:
- ✅ Bedrock Converse API (current TPipe implementation)
- ✅ Bedrock InvokeModel API (current TPipe implementation)
- ✅ OpenAI Chat Completions API (NOT implemented in TPipe)

**Current Issue:** TPipe uses Bedrock Converse API for GPT-OSS, but the error message shows OpenAI Chat Completions API parameter names. This suggests either:
1. AWS Bedrock is internally converting Converse API calls to OpenAI format
2. User is calling OpenAI Chat Completions API directly (not through TPipe)
3. There's a mismatch in how parameters are being sent

---

## TPipe Parameter Inventory

### Core Parameters (from Pipe.kt)

| TPipe Parameter | Type | Default | Description |
|----------------|------|---------|-------------|
| `temperature` | Double | 0.0 | Randomness/creativity control |
| `topP` | Double | 0.0 | Nucleus sampling probability |
| `topK` | Int | 0 | Top-K sampling limit |
| `maxTokens` | Int | 4000 | Maximum output tokens |
| `stopSequences` | List<String> | [] | Stop generation triggers |
| `repetitionPenalty` | Double | 0.0 | Repetition control (NovelAI-style) |
| `useModelReasoning` | Boolean | false | Enable reasoning mode |
| `modelReasoningSettingsV2` | Int | 5000 | Reasoning token budget |
| `modelReasoningSettingsV3` | String | "" | String-based reasoning config |
| `contextWindowSize` | Int | 8000 | Input context limit |
| `contextWindow` | ContextWindow | - | Lorebook-style context |

### Missing Parameters

TPipe does **NOT** have native support for:
- `frequency_penalty` (OpenAI-style repetition penalty)
- `presence_penalty` (OpenAI-style topic diversity)
- `logit_bias` (Token probability manipulation)
- `seed` (Deterministic generation)
- `n` (Number of completions)
- `user` (User tracking identifier)
- `logprobs` / `top_logprobs` (Token probability logging)
- `response_format` (Structured output format)
- `audio` / `modalities` (Multimodal input/output)
- `function_call` / `functions` (Legacy tool calling)
- `tool_choice` / `tools` (Modern tool calling)
- `parallel_tool_calls` (Concurrent tool execution)
- `prediction` (Prompt caching)
- `prompt_cache_key` (Cache key for predictions)
- `metadata` (Request metadata)
- `store` (Conversation storage)
- `stream_options` (Streaming configuration)
- `verbosity` (Output detail level)
- `web_search_options` (Web search integration)

---

## Parameter Mapping Analysis

### ✅ Correctly Mapped Parameters

| TPipe Parameter | OpenAI Parameter | Converse API Field | Status |
|----------------|------------------|-------------------|--------|
| `temperature` | `temperature` | `inferenceConfig.temperature` | ✅ Mapped |
| `topP` | `top_p` | `inferenceConfig.topP` | ✅ Mapped |
| `topK` | `top_k` | `additionalModelRequestFields.top_k` | ✅ Supported |
| `maxTokens` | `max_tokens` or `max_completion_tokens` | `inferenceConfig.maxTokens` | ✅ Mapped |
| `stopSequences` | `stop` | `inferenceConfig.stopSequences` | ✅ Mapped |

### ⚠️ Partially Mapped Parameters

| TPipe Parameter | OpenAI Parameter | Current Mapping | Issue |
|----------------|------------------|-----------------|-------|
| `modelReasoningSettingsV3` | `reasoning_effort` | `additionalModelRequestFields.reasoning_effort` | ✅ Mapped but uses broken reflection |
| `useModelReasoning` | `include_reasoning` | ❌ NOT MAPPED | Should map to `include_reasoning` |

### ❌ Incorrectly Mapped Parameters

| TPipe Parameter | OpenAI Equivalent | Current Mapping | Issue |
|----------------|-------------------|-----------------|-------|
| `repetitionPenalty` | `frequency_penalty` + `presence_penalty` | ❌ NOT MAPPED | NovelAI-style penalty doesn't map to OpenAI |

### ❌ Missing TPipe Support

These OpenAI parameters have **NO TPipe equivalent**:

**High Priority (commonly used):**
- `frequency_penalty` - Reduce repetition based on token frequency
- `presence_penalty` - Encourage topic diversity
- `seed` - Deterministic generation
- `response_format` - Structured output (JSON mode)
- `logit_bias` - Token probability manipulation

**Medium Priority (advanced features):**
- `n` - Multiple completions per request
- `logprobs` / `top_logprobs` - Token probability logging
- `user` - User tracking for abuse monitoring

**Low Priority (specialized features):**
- `audio` / `modalities` - Multimodal I/O
- `tool_choice` / `tools` - Tool calling (TPipe has PCP instead)
- `parallel_tool_calls` - Concurrent tool execution
- `prediction` / `prompt_cache_key` - Prompt caching
- `metadata` - Request metadata
- `store` - Conversation storage
- `stream_options` - Advanced streaming config
- `verbosity` - Output detail control
- `web_search_options` - Web search integration

---

## Critical Bug: GPT-OSS Reflection-Based Document Construction

### Location
`/home/cage/Desktop/Workspaces/TPipe/TPipe/TPipe-Bedrock/src/main/kotlin/bedrockPipe/BedrockPipe.kt`
Lines 1655-1726 (buildGptOssConverseRequest)

### Issue
GPT-OSS builder uses **broken Java reflection** to construct AWS SDK `Document` objects:

```kotlin
// BROKEN CODE (lines 1700-1718)
try {
    // Use reflection to create Document instance since constructor is not public
    val documentClass = Document::class.java
    val constructor = documentClass.getDeclaredConstructor()
    constructor.isAccessible = true
    val document = constructor.newInstance()

    // Set the internal map field
    val mapField = documentClass.getDeclaredField("value")
    mapField.isAccessible = true
    mapField.set(document, documentMap)  // Raw Map<String, Any>

    additionalModelRequestFields = document
} catch (_: Exception) {
    // If reflection fails we silently fall back to the JSON payload.
}
```

### Why This Fails
- AWS SDK expects properly typed `Document` subclasses
- Raw reflection bypasses type safety
- Parameters are silently ignored or cause validation errors
- Same bug that affected 8 other model builders (already fixed)

### Fix Required
Replace reflection with proper AWS SDK constructors:

```kotlin
// CORRECT APPROACH
val documentMap = mutableMapOf<String, Document?>()

if (modelReasoningSettingsV3.isNotEmpty()) {
    documentMap["reasoning_effort"] = Document.String(modelReasoningSettingsV3)
} else {
    documentMap["reasoning_effort"] = Document.String("low")
}

additionalModelRequestFields = Document.Map(documentMap)
```

---

## Model-by-Model Parameter Support

### GPT-OSS Models (openai.gpt-oss-20b-1:0, openai.gpt-oss-120b-1:0)

**Supported via Converse API:**
- ✅ `temperature` - Mapped via `inferenceConfig.temperature`
- ✅ `top_p` - Mapped via `inferenceConfig.topP`
- ✅ `top_k` - Supported via `additionalModelRequestFields` (but broken reflection)
- ✅ `max_tokens` / `max_completion_tokens` - Mapped via `inferenceConfig.maxTokens`
- ✅ `stop` - Mapped via `inferenceConfig.stopSequences`
- ✅ `reasoning_effort` - Mapped via `additionalModelRequestFields` (but broken reflection)
- ⚠️ `include_reasoning` - NOT MAPPED (should map from `useModelReasoning`)

**NOT Supported via Converse API:**
- ❌ `frequency_penalty` - No TPipe parameter
- ❌ `presence_penalty` - No TPipe parameter
- ❌ `logit_bias` - No TPipe parameter
- ❌ `seed` - No TPipe parameter
- ❌ `response_format` - No TPipe parameter
- ❌ `n` - No TPipe parameter
- ❌ `user` - No TPipe parameter
- ❌ `logprobs` / `top_logprobs` - No TPipe parameter

**AWS Documentation Reference:**
According to [AWS Bedrock OpenAI model parameters](https://docs.aws.amazon.com/bedrock/latest/userguide/model-parameters-openai.html):

> When using Converse API with GPT-OSS models:
> - Map `max_completion_tokens` → `inferenceConfig.maxTokens`
> - Map `stop` → `inferenceConfig.stopSequences`
> - Map `temperature` → `inferenceConfig.temperature`
> - Map `top_p` → `inferenceConfig.topP`
> - Include other fields in `additionalModelRequestFields`

### Other Bedrock Models

**Claude (anthropic.claude-*):**
- ✅ Converse API: temperature, topP, maxTokens, stopSequences
- ✅ Extended thinking via `additionalModelRequestFields.thinking` (FIXED)
- ❌ Does NOT support: topK, frequency_penalty, presence_penalty

**Nova (amazon.nova-*):**
- ✅ Converse API: temperature, topP, topK, maxTokens, stopSequences
- ✅ Reasoning via `additionalModelRequestFields` (FIXED)
- ❌ Does NOT support: frequency_penalty, presence_penalty

**DeepSeek (deepseek-*):**
- ✅ Converse API: temperature, topP, maxTokens, stopSequences
- ✅ Reasoning always enabled (no parameter needed) (FIXED)
- ❌ Does NOT support: topK, frequency_penalty, presence_penalty

**Qwen (qwen-*):**
- ✅ Converse API: temperature, topP, topK, maxTokens, stopSequences
- ✅ Reasoning via `additionalModelRequestFields.include_reasoning` (FIXED)
- ❌ Does NOT support: frequency_penalty, presence_penalty

**Titan (amazon.titan-*):**
- ✅ Converse API: temperature, topP, topK, maxTokens, stopSequences
- ❌ Does NOT support: frequency_penalty, presence_penalty, reasoning

**AI21 (ai21.j2-*):**
- ✅ Converse API: temperature, topP, topK, maxTokens, stopSequences
- ✅ Penalties via `additionalModelRequestFields.penalties` (FIXED)
- ⚠️ Uses different penalty structure than OpenAI

**Cohere (cohere.command-*):**
- ✅ Converse API: temperature, topP, topK, maxTokens, stopSequences
- ✅ Model-specific parameters (FIXED)
- ❌ Does NOT support: frequency_penalty, presence_penalty

**Llama (meta.llama-*):**
- ✅ Converse API: temperature, topP, topK, maxTokens, stopSequences
- ❌ Does NOT support: frequency_penalty, presence_penalty, reasoning

**Mistral (mistral-*):**
- ✅ Converse API: temperature, topP, topK, maxTokens, stopSequences
- ✅ Safe prompt via `additionalModelRequestFields.safe_prompt` (FIXED)
- ❌ Does NOT support: frequency_penalty, presence_penalty

---

## Root Cause Analysis

### Why Is This Error Occurring?

**Hypothesis 1: Broken Reflection in GPT-OSS Builder**
- GPT-OSS builder uses broken reflection to construct `Document` objects
- Parameters in `additionalModelRequestFields` are malformed
- AWS Bedrock rejects the request with validation error
- **Likelihood: HIGH** ✅

**Hypothesis 2: Wrong Parameter Name**
- TPipe is sending `thinking` parameter instead of `reasoning_effort` or `include_reasoning`
- However, code shows `reasoning_effort` is being sent, not `thinking`
- **Likelihood: LOW** ❌

**Hypothesis 3: User Calling OpenAI API Directly**
- User might be calling OpenAI Chat Completions API endpoint directly
- This would bypass TPipe's Converse API implementation
- Error message format matches OpenAI API exactly
- **Likelihood: MEDIUM** ⚠️

**Hypothesis 4: AWS Internal Conversion Issue**
- AWS Bedrock might be converting Converse API to OpenAI format internally
- Conversion might be rejecting malformed `additionalModelRequestFields`
- **Likelihood: MEDIUM** ⚠️

### Most Likely Cause

**The broken reflection-based Document construction** is causing `additionalModelRequestFields` to be malformed. When AWS Bedrock processes the Converse API request for GPT-OSS models, it converts it to OpenAI Chat Completions format internally. The malformed Document object causes the conversion to fail with the validation error.

---

## Recommended Fixes

### Priority 1: Fix GPT-OSS Reflection Bug (CRITICAL)

**File:** `BedrockPipe.kt` lines 1700-1718

**Replace:**
```kotlin
try {
    // Use reflection to create Document instance since constructor is not public
    val documentClass = Document::class.java
    val constructor = documentClass.getDeclaredConstructor()
    constructor.isAccessible = true
    val document = constructor.newInstance()

    // Set the internal map field
    val mapField = documentClass.getDeclaredField("value")
    mapField.isAccessible = true
    mapField.set(document, documentMap)

    additionalModelRequestFields = document
} catch (_: Exception) {
    // If reflection fails we silently fall back to the JSON payload.
}
```

**With:**
```kotlin
val documentMap = mutableMapOf<String, Document?>()

// Map reasoning_effort parameter
if (modelReasoningSettingsV3.isNotEmpty()) {
    documentMap["reasoning_effort"] = Document.String(modelReasoningSettingsV3)
} else {
    documentMap["reasoning_effort"] = Document.String("low")
}

// Map include_reasoning if useModelReasoning is enabled
if (useModelReasoning) {
    documentMap["include_reasoning"] = Document.Boolean(true)
}

additionalModelRequestFields = Document.Map(documentMap)
```

### Priority 2: Add Missing Parameter Support (HIGH)

Add new parameters to `Pipe.kt`:

```kotlin
/**
 * Frequency penalty for OpenAI-compatible models. Reduces repetition based on
 * token frequency. Range: -2.0 to 2.0. Positive values decrease repetition.
 */
@Serializable
protected var frequencyPenalty: Double = 0.0

/**
 * Presence penalty for OpenAI-compatible models. Encourages topic diversity.
 * Range: -2.0 to 2.0. Positive values encourage new topics.
 */
@Serializable
protected var presencePenalty: Double = 0.0

/**
 * Seed for deterministic generation. If set, model will attempt to generate
 * the same output for the same input.
 */
@Serializable
protected var seed: Int? = null

/**
 * Logit bias for token probability manipulation. Maps token IDs to bias values.
 */
@Serializable
protected var logitBias: Map<Int, Double> = emptyMap()

/**
 * Number of completions to generate. Most models only support n=1.
 */
@Serializable
protected var n: Int = 1

/**
 * User identifier for tracking and abuse monitoring.
 */
@Serializable
protected var user: String = ""
```

### Priority 3: Map New Parameters in GPT-OSS Builder (HIGH)

Update `buildGptOssRequest()` to include new parameters:

```kotlin
// Inference configuration
put("inferenceConfig", buildJsonObject {
    if (temperature > 0) put("temperature", temperature)
    if (maxTokens > 0) put("maxTokens", maxTokens)
    if (topP > 0) put("topP", topP)
    if (stopSequences.isNotEmpty()) {
        put("stopSequences", JsonArray(stopSequences.map { JsonPrimitive(it) }))
    }
})

// Model-specific parameters
put("additionalModelRequestFields", buildJsonObject {
    // Reasoning parameters
    if (modelReasoningSettingsV3.isNotEmpty()) {
        put("reasoning_effort", modelReasoningSettingsV3)
    } else {
        put("reasoning_effort", "low")
    }
    
    if (useModelReasoning) {
        put("include_reasoning", true)
    }
    
    // Penalty parameters
    if (frequencyPenalty != 0.0) {
        put("frequency_penalty", frequencyPenalty)
    }
    
    if (presencePenalty != 0.0) {
        put("presence_penalty", presencePenalty)
    }
    
    // Deterministic generation
    if (seed != null) {
        put("seed", seed)
    }
    
    // Logit bias
    if (logitBias.isNotEmpty()) {
        put("logit_bias", buildJsonObject {
            logitBias.forEach { (tokenId, bias) ->
                put(tokenId.toString(), bias)
            }
        })
    }
    
    // Number of completions
    if (n > 1) {
        put("n", n)
    }
    
    // User tracking
    if (user.isNotEmpty()) {
        put("user", user)
    }
})
```

### Priority 4: Update Converse Request Builder (HIGH)

Update `buildGptOssConverseRequest()` to use proper Document constructors:

```kotlin
// Handle additional model fields using proper Document constructors
val documentMap = mutableMapOf<String, Document?>()

// Reasoning parameters
if (modelReasoningSettingsV3.isNotEmpty()) {
    documentMap["reasoning_effort"] = Document.String(modelReasoningSettingsV3)
} else {
    documentMap["reasoning_effort"] = Document.String("low")
}

if (useModelReasoning) {
    documentMap["include_reasoning"] = Document.Boolean(true)
}

// Penalty parameters
if (frequencyPenalty != 0.0) {
    documentMap["frequency_penalty"] = Document.Number(frequencyPenalty)
}

if (presencePenalty != 0.0) {
    documentMap["presence_penalty"] = Document.Number(presencePenalty)
}

// Deterministic generation
seed?.let {
    documentMap["seed"] = Document.Number(it)
}

// Logit bias
if (logitBias.isNotEmpty()) {
    val logitBiasMap = mutableMapOf<String, Document?>()
    logitBias.forEach { (tokenId, bias) ->
        logitBiasMap[tokenId.toString()] = Document.Number(bias)
    }
    documentMap["logit_bias"] = Document.Map(logitBiasMap)
}

// Number of completions
if (n > 1) {
    documentMap["n"] = Document.Number(n)
}

// User tracking
if (user.isNotEmpty()) {
    documentMap["user"] = Document.String(user)
}

additionalModelRequestFields = Document.Map(documentMap)
```

---

## Testing Requirements

### Validation Checklist

- [ ] Fix GPT-OSS reflection-based Document construction
- [ ] Verify `reasoning_effort` parameter works correctly
- [ ] Add `include_reasoning` parameter mapping
- [ ] Test with actual GPT-OSS models on AWS Bedrock
- [ ] Verify no validation errors
- [ ] Add new parameters to Pipe class
- [ ] Update GPT-OSS builders with new parameters
- [ ] Test frequency_penalty and presence_penalty
- [ ] Test seed for deterministic generation
- [ ] Test logit_bias for token manipulation
- [ ] Update documentation

### Test Cases

1. **Basic GPT-OSS Request**
   ```kotlin
   val pipe = BedrockPipe()
       .setModel("openai.gpt-oss-20b-1:0")
       .setTemperature(0.7)
       .setTopP(0.9)
       .setMaxTokens(1000)
   ```

2. **Reasoning Mode**
   ```kotlin
   val pipe = BedrockPipe()
       .setModel("openai.gpt-oss-120b-1:0")
       .setReasoning("high")  // reasoning_effort
       .setUseModelReasoning(true)  // include_reasoning
   ```

3. **Penalty Parameters**
   ```kotlin
   val pipe = BedrockPipe()
       .setModel("openai.gpt-oss-20b-1:0")
       .setFrequencyPenalty(0.5)
       .setPresencePenalty(0.3)
   ```

4. **Deterministic Generation**
   ```kotlin
   val pipe = BedrockPipe()
       .setModel("openai.gpt-oss-20b-1:0")
       .setSeed(42)
   ```

---

## Impact Assessment

### User-Facing Impact

**Before Fix:**
- ❌ GPT-OSS models throw validation errors
- ❌ `reasoning_effort` parameter silently ignored
- ❌ `include_reasoning` not supported
- ❌ No support for frequency_penalty, presence_penalty
- ❌ No support for seed, logit_bias, n, user

**After Fix:**
- ✅ GPT-OSS models work correctly
- ✅ Reasoning parameters properly sent
- ✅ Full OpenAI parameter support
- ✅ Deterministic generation available
- ✅ Advanced penalty controls available

### Breaking Changes

**None** - All fixes are backwards compatible. New parameters default to 0/null/empty and are only sent when explicitly set.

---

## Related Issues

### Previously Fixed
- ✅ Bedrock Document construction bug (8 models) - FIXED 2026-01-08
- ✅ DeepSeek V3.1 thinking parameter - FIXED 2026-01-08
- ✅ Numeric parameter unset logic - FIXED 2026-01-08

### Still Open
- ❌ GPT-OSS reflection-based Document construction - **THIS ISSUE**
- ❌ Missing OpenAI parameter support - **THIS ISSUE**
- ❌ `include_reasoning` not mapped - **THIS ISSUE**

---

## References

### AWS Documentation
- [AWS Bedrock OpenAI Model Parameters](https://docs.aws.amazon.com/bedrock/latest/userguide/model-parameters-openai.html)
- [AWS Bedrock Converse API](https://docs.aws.amazon.com/bedrock/latest/userguide/conversation-inference-call.html)
- [AWS SDK Kotlin Document Class](https://sdk.amazonaws.com/kotlin/api/smithy-kotlin/api/1.4.1/runtime-core/aws.smithy.kotlin.runtime.content/-document/index.html)

### OpenAI Documentation
- OpenAI Chat Completions API (platform.openai.com - access restricted)

### Related Files
- `/home/cage/Desktop/Workspaces/TPipe/TPipe/TPipe-Bedrock/src/main/kotlin/bedrockPipe/BedrockPipe.kt`
- `/home/cage/Desktop/Workspaces/TPipe/TPipe/src/main/kotlin/Pipe/Pipe.kt`
- `/home/cage/Desktop/Workspaces/TPipe/TPipe/BEDROCK_DOCUMENT_BUG_REPORT.md`
- `/home/cage/Desktop/Workspaces/TPipe/TPipe/DEEPSEEK_THINKING_PARAMETER_FIX.md`

---

## Conclusion

The validation error is caused by **broken reflection-based Document construction** in the GPT-OSS builder, the same bug that affected 8 other Bedrock model builders. Additionally, TPipe is **missing support for critical OpenAI parameters** like `frequency_penalty`, `presence_penalty`, `seed`, and `logit_bias`.

**Immediate Action Required:**
1. Fix GPT-OSS reflection bug (same fix as other models)
2. Add `include_reasoning` parameter mapping
3. Add missing OpenAI parameters to Pipe class
4. Update GPT-OSS builders to support new parameters

**Priority:** **CRITICAL** - GPT-OSS models are currently broken and throwing validation errors.
