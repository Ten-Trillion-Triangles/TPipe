# TPipe Reasoning System Naming Convention

## Planning-Oriented Language Standards

### Approved Prefixes
- `proposed` - Indicates a suggested approach or plan
- `planned` - Indicates a structured approach or methodology  
- `recommended` - Indicates a suggested course of action
- `analyzed` - Indicates examination or assessment
- `evaluated` - Indicates consideration or review

### Approved Suffixes
- `approach` - Method or strategy for addressing something
- `strategy` - Plan of action or methodology
- `analysis` - Examination or assessment
- `assessment` - Evaluation or review
- `recommendation` - Suggested course of action
- `conclusion` - Reasoned endpoint (acceptable when not implying completion)

### Prohibited Terms
- `result` - Implies completion or finalization
- `solution` - Implies a completed answer
- `final` - Implies completion or end state
- `completed` - Explicitly states completion
- `verified` - Implies verification has been done
- `combined` - Suggests action has been taken

### Field Naming Examples

#### Before (Completion-Oriented)
```kotlin
var combinedResult: String = ""
var verifyResult: String = ""  
var finalSummary: String = ""
var solution: String = ""
```

#### After (Planning-Oriented)
```kotlin
var proposedApproach: String = ""
var approachValidation: String = ""
var reasoningConclusion: String = ""
var recommendedStrategy: String = ""
```

### Class Naming Examples

#### Before (Completion-Oriented)
```kotlin
data class SubProblemSolution(...)
data class ProcessFocusedResult(...)
```

#### After (Planning-Oriented)
```kotlin
data class SubProblemApproach(...)
data class ProcessFocusedResult(...) // Keep class name, change fields
```

### Language Patterns in Methods

#### Before (Action-Oriented)
- "Combining these results"
- "Verification shows"
- "Successfully identified"
- "Final analysis"

#### After (Planning-Oriented)  
- "My proposed approach"
- "Approach validation"
- "I recommend"
- "Reasoning conclusion"

## Implementation Guidelines

1. **Field Names**: Use planning-oriented prefixes and suffixes
2. **Method Language**: Focus on recommendations and proposals
3. **Prompt Language**: Emphasize planning rather than completion
4. **Consistency**: Apply standards across all reasoning components

## Validation Criteria

- No field names suggest completion or finalization
- All language emphasizes planning and recommendation
- Prompts guide LLMs toward planning rather than claiming completion
- Consistent terminology throughout reasoning system
