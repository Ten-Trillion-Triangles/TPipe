# Fix TPipe Reasoning System Completion Language Issues

## Problem Statement

TPipe's reasoning system uses completion-oriented language that causes LLM confusion and undefined behavior. The system incorrectly claims actions have been taken ("Successfully identified and corrected", "Replaced", "Text now ends with") when it should be planning approaches.

**Example problematic output:**
```json
{
  "combinedResult": "Successfully identified and corrected three narrative violations...",
  "verifyResult": "Final text maintains same paragraph structure...", 
  "finalSummary": "Text now ends with Miran actively choosing..."
}
```

## Root Cause Analysis

### 1. ProcessFocusedResult Data Class (ModelReasoning.kt)
- `combinedResult` - implies completion
- `verifyResult` - implies verification done
- `finalSummary` - implies finalization

### 2. StructuredCot unravel() Method Language
- "Combining these results" - suggests action taken
- "Verification" - suggests verification completed

### 3. StructuredCot System Prompt Phase 4
- "Combine results from all steps" - action-oriented
- "Verify the solution satisfies requirements" - completion-oriented

## Implementation Plan

### Phase 1: Analysis and Design

#### Step 1: Analyze Current Problematic Language Patterns
**Target:** `src/main/kotlin/Structs/ModelReasoning.kt`
**Actions:**
- Identify all completion-oriented field names
- Document language patterns suggesting actions taken
- Map current terminology to planning alternatives

**Completion Criteria:** Complete inventory of problematic language patterns

#### Step 2: Audit All Reasoning System Prompts
**Target:** `TPipe-Defaults/src/main/kotlin/Defaults/reasoning/ReasoningPrompts.kt`
**Actions:**
- Review all reasoning method prompts
- Identify completion language instances
- Document prompt sections requiring updates

**Completion Criteria:** Complete audit of all reasoning prompts

#### Step 3: Design Consistent Naming Convention
**Actions:**
- Establish planning-oriented naming standards
- Define approved prefixes: "proposed", "planned", "recommended"
- Define approved suffixes: "approach", "strategy", "analysis"
- Prohibit: "result", "solution", "final", "completed"

**Completion Criteria:** Documented naming convention standards

### Phase 2: Core Data Structure Updates

#### Step 4: Update ProcessFocusedResult Data Class
**Target:** `ProcessFocusedResult` in `ModelReasoning.kt`
**Changes:**
```kotlin
data class ProcessFocusedResult(
    var proposedApproach: String = "",    // was: combinedResult
    var approachValidation: String = "",  // was: verifyResult
    var reasoningConclusion: String = ""  // was: finalSummary
)
```

**Completion Criteria:** Data class updated with planning-oriented field names

#### Step 5: Update SystemicExecution Data Classes
**Target:** `SubProblemSolution` and related classes in `ModelReasoning.kt`
**Changes:**
```kotlin
data class SubProblemApproach(  // was: SubProblemSolution
    var subProblemName: String = "",
    var proposedApproach: String = ""  // was: solution
)
```

**Completion Criteria:** All SystemicExecution classes use planning language

### Phase 3: Language and Prompt Updates

#### Step 6: Revise StructuredCot unravel() Method
**Target:** `StructuredCot.unravel()` in `ModelReasoning.kt`
**Changes:**
```kotlin
append("My proposed approach: ${reasoningSynthesis.proposedApproach}. ")
append("Approach validation: ${reasoningSynthesis.approachValidation}. ")
append(reasoningSynthesis.reasoningConclusion)
```

**Completion Criteria:** unravel() method uses planning language

#### Step 7: Update StructuredCot System Prompt Phase 4
**Target:** `chainOfThoughtSystemPrompt()` in `ReasoningPrompts.kt`
**Changes:**
```
[PHASE 4: REASONING SYNTHESIS]
- Propose how to integrate insights from all steps
- Validate that the proposed approach addresses all requirements
- Conclude with reasoning-based recommendation
```

**Completion Criteria:** Phase 4 prompt focuses on planning rather than completion

### Phase 4: Consistency and Validation

#### Step 8: Review Other Reasoning Method Prompts
**Target:** All reasoning prompts in `ReasoningPrompts.kt`
**Actions:**
- Update ExplicitCot prompts for planning language
- Update ProcessFocusedCot prompts for planning language  
- Update BestIdea prompts for planning language
- Ensure consistent planning-oriented terminology

**Completion Criteria:** All reasoning method prompts use planning language

#### Step 9: Test Changes with Sample Reasoning Scenarios
**Actions:**
- Create test scenarios for each reasoning method
- Validate outputs use planning language:
  - "I recommend..." instead of "I have completed..."
  - "The proposed approach would..." instead of "Successfully identified..."
  - "My analysis suggests..." instead of "Verification shows..."
- Ensure no completion claims in reasoning output

**Completion Criteria:** All test scenarios produce planning-oriented output

## Success Criteria

1. **No Completion Claims:** All reasoning outputs use planning/recommendation language
2. **Consistent Terminology:** Planning-oriented field names and language throughout
3. **LLM Behavior:** Elimination of confusion and undefined behavior
4. **Backward Compatibility:** Changes maintain reasoning system functionality

## Risk Mitigation

- **Breaking Changes:** Update `extractReasoningContent()` function to handle new field names
- **Testing:** Validate each reasoning method produces expected planning-oriented output
- **Documentation:** Update API documentation to reflect new field names

## Files to Modify

1. `src/main/kotlin/Structs/ModelReasoning.kt`
   - ProcessFocusedResult data class
   - SubProblemSolution → SubProblemApproach
   - StructuredCot.unravel() method

2. `TPipe-Defaults/src/main/kotlin/Defaults/reasoning/ReasoningPrompts.kt`
   - StructuredCot system prompt Phase 4
   - Other reasoning method prompts as needed

3. Any references to old field names in:
   - `extractReasoningContent()` function
   - ReasoningBuilder configuration
   - Test files

## Implementation Order

Execute steps sequentially to maintain system stability:
1. Complete analysis and design (Steps 1-3)
2. Update data structures (Steps 4-5)  
3. Update language and prompts (Steps 6-7)
4. Ensure consistency and validate (Steps 8-9)

This systematic approach ensures the reasoning system produces planning-oriented output that eliminates LLM confusion while maintaining all existing functionality.
