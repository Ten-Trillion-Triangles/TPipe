# ConverseHistory Refactor Plan

## Overview
Refactor the `converseHistory` variable in ContextWindow.kt to support lorebook selection and token-based truncation, bringing it in line with the existing `contextElements` and `loreBookKeys` functionality.

## Current State Analysis
- `converseHistory` is a `ConverseHistory()` object with basic conversation storage
- Lacks lorebook key matching and selection capabilities
- No token-based truncation support
- Missing integration with existing context management functions

## Required Changes

### 1. ConverseHistory Class Enhancement
**File**: `ConverseHistory.kt` (needs inspection/creation)
- Add method to extract text content for lorebook key matching
- Add token counting capability for conversation entries
- Add truncation support with configurable strategies
- Maintain conversation structure while enabling text analysis

### 2. New ContextWindow Methods

#### 2.1 Lorebook Selection for Conversations
```kotlin
fun selectConverseHistoryLoreBookContext(
    maxTokens: Int,
    countSubWordsInFirstWord: Boolean = true,
    favorWholeWords: Boolean = true,
    countOnlyFirstWordFound: Boolean = false,
    splitForNonWordChar: Boolean = true,
    alwaysSplitIfWholeWordExists: Boolean = false,
    countSubWordsIfSplit: Boolean = false,
    nonWordSplitCount: Int = 4
): List<String>
```

#### 2.2 Conversation History Truncation
```kotlin
fun truncateConverseHistory(
    maxTokens: Int,
    multiplyWindowSizeBy: Int,
    truncateSettings: com.TTT.Enums.ContextWindowSettings,
    countSubWordsInFirstWord: Boolean = true,
    favorWholeWords: Boolean = true,
    countOnlyFirstWordFound: Boolean = false,
    splitForNonWordChar: Boolean = true,
    alwaysSplitIfWholeWordExists: Boolean = false,
    countSubWordsIfSplit: Boolean = false,
    nonWordSplitCount: Int = 4
)
```

### 3. Integration with Existing Methods

#### 3.1 Update selectAndTruncateContext
- Modify to handle three-way token budget split: lorebook, contextElements, converseHistory
- Default allocation: 33% each, with unused tokens redistributed
- Maintain existing two-way split as fallback when converseHistory is empty

#### 3.2 Update combineAndTruncateAsString
- Include converseHistory text in combined string output
- Apply lorebook selection to conversation content
- Maintain conversation order and structure in output

#### 3.3 Update merge function
- Add converseHistory merging logic
- Handle conversation deduplication
- Preserve conversation chronology

### 4. Helper Methods

#### 4.1 Conversation Text Extraction
```kotlin
private fun extractConverseHistoryText(): String
```
- Extract all text content from converseHistory for lorebook matching
- Combine user and assistant messages
- Maintain conversation flow context

#### 4.2 Conversation Token Counting
```kotlin
private fun countConverseHistoryTokens(
    countSubWordsInFirstWord: Boolean = true,
    favorWholeWords: Boolean = true,
    countOnlyFirstWordFound: Boolean = false,
    splitForNonWordChar: Boolean = true,
    alwaysSplitIfWholeWordExists: Boolean = false,
    countSubWordsIfSplit: Boolean = false,
    nonWordSplitCount: Int = 4
): Int
```

### 5. Updated isEmpty() Method
```kotlin
fun isEmpty(): Boolean {
    return contextElements.isEmpty() && 
           loreBookKeys.isEmpty() && 
           converseHistory.isEmpty()
}
```

## Implementation Strategy

### Phase 1: ConverseHistory Class Analysis
1. Examine existing ConverseHistory implementation
2. Identify conversation structure and data types
3. Determine text extraction approach

### Phase 2: Core Method Implementation
1. Implement `extractConverseHistoryText()`
2. Implement `countConverseHistoryTokens()`
3. Implement `selectConverseHistoryLoreBookContext()`
4. Implement `truncateConverseHistory()`

### Phase 3: Integration Updates
1. Update `selectAndTruncateContext()` for three-way budget split
2. Update `combineAndTruncateAsString()` to include conversations
3. Update `merge()` to handle converseHistory
4. Update `isEmpty()` method

### Phase 4: Testing & Validation
1. Test lorebook selection with conversation content
2. Validate token counting accuracy
3. Test truncation strategies
4. Verify integration with existing functionality

## Token Budget Allocation Strategy

### Three-Way Split (when all components present)
- **LoreBook**: 33% of budget
- **ContextElements**: 33% of budget  
- **ConverseHistory**: 33% of budget
- **Redistribution**: Unused tokens from any component redistributed to others

### Fallback Strategies
- **Two components**: 50/50 split with redistribution
- **One component**: Full budget allocation
- **Empty converseHistory**: Maintain existing two-way split

## Conversation Truncation Strategies

### TruncateTop
- Remove oldest conversation entries first
- Preserve recent conversation context

### TruncateBottom  
- Remove newest conversation entries first
- Preserve conversation history

### TruncateMiddle
- Remove middle conversation entries
- Preserve conversation start and recent context

## Dependencies & Requirements

### Required Files to Examine
- `ConverseHistory.kt` - Understand conversation structure
- `Dictionary.kt` - Token counting and truncation methods
- `TruncationSettings.kt` - Parameter structure
- `ContextWindowSettings.kt` - Truncation strategy enum

### Potential New Dependencies
- Conversation message extraction utilities
- Conversation ordering/chronology management
- Message deduplication logic

## Risk Mitigation

### Backward Compatibility
- Maintain existing method signatures
- Add new parameters as optional with defaults
- Preserve existing behavior when converseHistory is empty

### Performance Considerations
- Cache conversation text extraction results
- Optimize token counting for large conversation histories
- Implement lazy evaluation where possible

### Error Handling
- Handle empty/null conversation entries
- Validate token budget parameters
- Graceful degradation when conversation parsing fails

## Success Criteria

1. **Functional Parity**: converseHistory supports same operations as contextElements and loreBookKeys
2. **Token Accuracy**: Conversation token counting matches Dictionary.countTokens behavior
3. **Integration**: Seamless integration with existing selectAndTruncateContext workflow
4. **Performance**: No significant performance degradation with large conversation histories
5. **Backward Compatibility**: Existing code continues to work without modification

## Future Enhancements

### Conversation-Specific Features
- Message-level lorebook key weighting
- Conversation role-based truncation (preserve system messages)
- Conversation topic clustering for smarter truncation

### Advanced Integration
- Cross-component lorebook key correlation
- Dynamic budget reallocation based on content importance
- Conversation summary generation for extreme truncation scenarios