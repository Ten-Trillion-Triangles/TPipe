# JSON Repair Implementation Plan

## Overview
Implement comprehensive JSON repair functionality to handle malformed JSON output from AI models that cannot be deserialized with standard kotlinx.serialization even with lenient settings.

## Problem Statement
AI models frequently generate JSON with:
- Missing quotes around keys/values
- Trailing commas
- Unescaped characters
- Structural inconsistencies
- Mixed formatting patterns

## Implementation Strategy

### Phase 1: Core Repair Functions

#### 1.1 Add to Util.kt
```kotlin
/**
 * Repairs malformed JSON and attempts deserialization with fallback strategies
 */
@OptIn(ExperimentalSerializationApi::class)
inline fun <reified T> repairAndDeserialize(malformedJson: String): T?

/**
 * Extracts key-value pairs using regex when JSON is too malformed
 */
fun extractJsonData(malformedJson: String): Map<String, String>

/**
 * Applies multiple repair strategies in sequence
 */
fun repairJsonString(input: String): String
```

#### 1.2 Repair Strategies (in order of application)
1. **Boundary Cleanup**: Extract JSON from surrounding text
2. **Quote Normalization**: Fix escaped and double quotes
3. **Comma Cleanup**: Remove trailing commas
4. **Key Quoting**: Add quotes around unquoted keys
5. **Value Quoting**: Add quotes around unquoted string values
6. **Null Handling**: Replace empty values with null
7. **Whitespace Normalization**: Clean excessive whitespace

### Phase 2: Fallback Extraction

#### 2.1 Regex Patterns
- Key-value pairs: `"?([a-zA-Z_][a-zA-Z0-9_]*)"?\s*:\s*"([^"]*)"`
- Nested objects: Extract using bracket matching
- Arrays: Handle both quoted and unquoted elements

#### 2.2 Manual Construction
When regex extraction succeeds but deserialization fails:
- Build Map<String, Any> manually
- Convert to target data class using reflection or builder pattern

### Phase 3: Integration Points

#### 3.1 Update Existing deserialize() Function
```kotlin
inline fun <reified T> deserialize(jsonString: String): T? {
    // Try standard deserialization first
    val standard = tryStandardDeserialization<T>(jsonString)
    if (standard != null) return standard
    
    // Try repair and deserialize
    return repairAndDeserialize<T>(jsonString)
}
```

#### 3.2 Add Diagnostic Function
```kotlin
fun diagnoseJsonIssues(jsonString: String): List<String>
```

### Phase 4: Testing Strategy

#### 4.1 Test Cases
1. **Standard Valid JSON**: Ensure no regression
2. **Missing Quotes**: Keys and values without quotes
3. **Trailing Commas**: Various positions
4. **Nested Structures**: Complex objects and arrays
5. **Mixed Issues**: Multiple problems in single JSON
6. **Edge Cases**: Empty objects, null values, special characters

#### 4.2 Test Data
```kotlin
val testCases = listOf(
    // Your specific case
    """{needsChanges:true,changesToMake:{narrative_perspective:"Replace first-person..."}}""",
    // Missing quotes on keys
    """{key1: "value1", key2: "value2"}""",
    // Trailing commas
    """{\"key1\": \"value1\", \"key2\": \"value2\",}""",
    // Mixed issues
    """{key1: value1, "key2": "value2",}"""
)
```

### Phase 5: Implementation Steps

#### Step 1: Add Core Functions to Util.kt
- Implement `repairJsonString()`
- Implement `repairAndDeserialize()`
- Implement `extractJsonData()`

#### Step 2: Create Test File
- Create `JsonRepairTest.kt` in test directory
- Add comprehensive test cases
- Verify all scenarios work

#### Step 3: Update Existing Functions
- Modify `deserialize()` to use repair as fallback
- Add optional parameter to control repair behavior

#### Step 4: Documentation
- Update function documentation
- Add usage examples
- Document known limitations

### Phase 6: Advanced Features (Optional)

#### 6.1 Schema-Aware Repair
- Use target class structure to guide repairs
- Infer missing fields with default values

#### 6.2 Confidence Scoring
- Return confidence level of repair success
- Allow caller to decide on acceptance threshold

#### 6.3 Repair Logging
- Log what repairs were applied
- Useful for debugging AI model output issues

## File Structure

```
TPipe/
├── src/main/kotlin/Util/
│   └── Util.kt (add repair functions)
├── src/test/kotlin/Util/
│   └── JsonRepairTest.kt (new test file)
└── JSON_REPAIR_IMPLEMENTATION_PLAN.md (this file)
```

## Success Criteria

1. **Compatibility**: No breaking changes to existing code
2. **Performance**: Minimal overhead for valid JSON
3. **Coverage**: Handle 90%+ of common AI JSON malformation patterns
4. **Reliability**: Graceful degradation when repair fails
5. **Maintainability**: Clear, documented code with comprehensive tests

## Risk Mitigation

1. **False Positives**: Repair might "fix" intentionally formatted strings
   - Solution: Only apply repair when standard deserialization fails
   
2. **Performance Impact**: Complex regex operations on large JSON
   - Solution: Size limits and timeout mechanisms
   
3. **Security**: Malicious input could exploit regex patterns
   - Solution: Input validation and pattern complexity limits

## Timeline

- **Phase 1-2**: 2-3 hours (core implementation)
- **Phase 3**: 1 hour (integration)
- **Phase 4**: 2-3 hours (testing)
- **Phase 5**: 1 hour (documentation)
- **Total**: 6-8 hours

## Next Steps

1. Implement core repair functions in Util.kt
2. Create comprehensive test suite
3. Integrate with existing deserialization flow
4. Test with your specific JSON case
5. Document and optimize based on results