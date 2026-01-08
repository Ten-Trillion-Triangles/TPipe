# Complete Numeric Parameter Investigation & Fix

## Date: 2026-01-08 15:11

## Investigation Results

Investigated all numeric parameters across all builders in BedrockPipe.

### Parameters Analyzed
1. **temperature** ✅ Already correct
2. **topP** ❌ Was checking `< 1.0` - FIXED to `> 0`
3. **topK** ✅ Already correct
4. **maxTokens** ❌ Was always set - FIXED to check `> 0`

## Issues Found & Fixed

### 1. topP Parameter
**Issue:** Checked `if (topP < 1.0)` which would send even when 0
**Fix:** Changed to `if (topP > 0)`
**Locations:** 26 occurrences across all builders (Converse API and InvokeModel API)

### 2. maxTokens Parameter
**Issue:** Always set without checking if > 0
**Fix:** Changed to `if (maxTokens > 0)` before setting
**Locations:** 17 occurrences across all builders

## Fixed Patterns

### Converse API (InferenceConfiguration)
**Before:**
```kotlin
inferenceConfig = InferenceConfiguration {
    maxTokens = this@BedrockPipe.maxTokens  // Always set
    if (temperature > 0) temperature = ...
    if (topP < 1.0) topP = ...              // Wrong condition
}
```

**After:**
```kotlin
inferenceConfig = InferenceConfiguration {
    if (this@BedrockPipe.maxTokens > 0) maxTokens = this@BedrockPipe.maxTokens
    if (temperature > 0) temperature = ...
    if (topP > 0) topP = ...                // Correct condition
}
```

### InvokeModel API (JSON)
**Before:**
```kotlin
put("maxTokens", maxTokens)      // Always set
if (topP < 1.0) put("topP", topP)  // Wrong condition
```

**After:**
```kotlin
if (maxTokens > 0) put("maxTokens", maxTokens)
if (topP > 0) put("topP", topP)    // Correct condition
```

## All Builders Fixed

### Converse API Builders (12)
1. DeepSeek - maxTokens, topP
2. Claude - maxTokens, topP
3. Nova - maxTokens, topP (with highReasoning checks)
4. MiniMax - maxTokens, topP
5. Titan - maxTokens, topP
6. AI21 - maxTokens, topP
7. Cohere - maxTokens, topP
8. Llama - maxTokens, topP
9. Mistral - maxTokens, topP
10. Generic - maxTokens, topP
11. GPT-OSS - maxTokens, topP
12. Qwen - maxTokens, topP

### InvokeModel API Builders (10+)
1. Nova (InvokeModel) - maxTokens, topP
2. Qwen (InvokeModel) - maxTokens, topP
3. Claude (InvokeModel) - maxTokens, topP
4. Titan (InvokeModel) - maxTokens, topP
5. Cohere (InvokeModel) - maxTokens, topP
6. Llama (InvokeModel) - maxTokens, topP
7. Mistral (InvokeModel) - maxTokens, topP
8. DeepSeek (InvokeModel) - maxTokens, topP
9. MiniMax (InvokeModel) - maxTokens, topP
10. Generic (InvokeModel) - maxTokens, topP

## Parameters Already Correct

### temperature
✅ Already checking `if (temperature > 0)` everywhere

### topK
✅ Already checking `if (topK > 0)` everywhere

## Impact

### User Expectations Met
When users set any numeric parameter to 0 or less:
- ✅ Parameter is NOT sent to AWS Bedrock
- ✅ Model uses its default value
- ✅ No validation errors from models with minimum requirements

### Specific Fixes
1. **Claude Extended Thinking** - topP now unset when 0, meets >= 0.95 requirement
2. **All Models** - maxTokens unset when 0, allows model defaults
3. **All Models** - topP unset when 0, cleaner requests

## Validation
- ✅ Build: PASSED
- ✅ 43+ locations fixed across both API paths
- ✅ Consistent behavior across all builders

## Behavior Summary

| Parameter | Condition | Behavior |
|-----------|-----------|----------|
| temperature | `> 0` | ✅ Correct (already was) |
| topP | `> 0` | ✅ Fixed (was `< 1.0`) |
| topK | `> 0` | ✅ Correct (already was) |
| maxTokens | `> 0` | ✅ Fixed (was always set) |

## Related Files
- `/home/cage/Desktop/Workspaces/TPipe/TPipe/TPipe-Bedrock/src/main/kotlin/bedrockPipe/BedrockPipe.kt`
