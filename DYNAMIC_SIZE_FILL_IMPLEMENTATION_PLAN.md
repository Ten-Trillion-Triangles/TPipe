# DYNAMIC_SIZE_FILL Implementation Plan

## Overview
Implement a new token budgeting strategy that prioritizes smaller context windows over larger ones, with intelligent redistribution of unused budget. This strategy protects critical small contexts (like gameplay data) while allowing expendable large contexts (like story data) to be truncated first.

## Strategy Behavior
- **Phase 1**: Allocate tokens by size priority (smallest contexts first)
- **Phase 2**: Simulate actual usage after truncation
- **Phase 3**: Redistribute unused budget to contexts that can utilize more tokens
- **Goal**: Preserve smaller contexts intact, truncate larger contexts as needed

## Implementation Steps

### Step 1: Add Enum Value
**File**: `src/main/kotlin/Pipe/Pipe.kt`
**Location**: Line ~200 (MultiPageBudgetStrategy enum)

```kotlin
enum class MultiPageBudgetStrategy {
    EQUAL_SPLIT,
    WEIGHTED_SPLIT,
    PRIORITY_FILL,
    DYNAMIC_FILL,
    DYNAMIC_SIZE_FILL  // New strategy
}
```

### Step 2: Add Strategy Selection Case
**File**: `src/main/kotlin/Pipe/Pipe.kt`
**Location**: Line ~1878 (strategy selection in calculateBudgetAllocations)

```kotlin
MultiPageBudgetStrategy.DYNAMIC_SIZE_FILL -> calculateDynamicSizeFill(totalBudget, effectiveKeys, truncationSettings)
```

### Step 3: Implement calculateDynamicSizeFill Method
**File**: `src/main/kotlin/Pipe/Pipe.kt`
**Location**: After calculateDynamicFill method (~line 2010)

```kotlin
/**
 * Calculates size-based dynamic budget allocation with redistribution.
 * Prioritizes smaller contexts for protection, truncates larger contexts first.
 * Uses multi-pass approach: size-based allocation → usage simulation → redistribution.
 */
private fun calculateDynamicSizeFill(
    totalBudget: Int,
    pageKeys: List<String>,
    truncationSettings: TruncationSettings
): Map<String, Int>
{
    if(totalBudget <= 0 || pageKeys.isEmpty())
    {
        return pageKeys.associateWith { 0 }
    }

    val initialAllocations = calculateSizeBasedPriorityFill(totalBudget, pageKeys, truncationSettings)
    val simulatedUsage = simulateTruncationUsage(initialAllocations, truncationSettings)

    return redistributeBudgetDynamically(initialAllocations, simulatedUsage, totalBudget, truncationSettings)
}
```

### Step 4: Implement calculateSizeBasedPriorityFill Method
**File**: `src/main/kotlin/Pipe/Pipe.kt`
**Location**: After calculateDynamicSizeFill method

```kotlin
/**
 * Allocates budget by size priority - smallest contexts get full allocation first.
 * Larger contexts get remaining budget and may be truncated.
 */
private fun calculateSizeBasedPriorityFill(
    totalBudget: Int,
    pageKeys: List<String>,
    truncationSettings: TruncationSettings
): Map<String, Int>
{
    if(totalBudget <= 0 || pageKeys.isEmpty())
    {
        return pageKeys.associateWith { 0 }
    }

    // Calculate context sizes and sort by size (smallest first)
    val contextSizes = mutableMapOf<String, Int>()
    for(pageKey in pageKeys)
    {
        val contextWindow = miniContextBank.contextMap[pageKey]
        val size = if(contextWindow == null || contextWindow.isEmpty()) 0
                  else countContextWindowTokens(contextWindow, truncationSettings)
        contextSizes[pageKey] = size
    }

    val sortedKeys = pageKeys.sortedBy { contextSizes[it] ?: 0 }
    
    // Allocate budget in size order (smallest first)
    val allocations = mutableMapOf<String, Int>()
    var remainingBudget = totalBudget

    for(pageKey in sortedKeys)
    {
        if(remainingBudget <= 0) 
        {
            allocations[pageKey] = 0
            continue
        }

        val requiredTokens = contextSizes[pageKey] ?: 0
        if(requiredTokens <= 0)
        {
            allocations[pageKey] = 0
            continue
        }

        val allocation = minOf(requiredTokens, remainingBudget)
        allocations[pageKey] = allocation
        remainingBudget -= allocation
    }

    return allocations
}
```

### Step 5: Add Public API Method
**File**: `src/main/kotlin/Pipe/Pipe.kt`
**Location**: After setMultiPageBudgetStrategy method (~line 1692)

```kotlin
/**
 * Enable dynamic size-based budget allocation that prioritizes smaller contexts.
 * Smaller contexts are protected and get full allocation first.
 * Larger contexts are truncated as needed to fit budget.
 * Includes intelligent redistribution of unused budget.
 * 
 * @return This Pipe object for method chaining
 */
fun enableDynamicSizeFill(): Pipe
{
    if(tokenBudgetSettings == null)
    {
        tokenBudgetSettings = TokenBudgetSettings()
    }
    tokenBudgetSettings!!.multiPageBudgetStrategy = MultiPageBudgetStrategy.DYNAMIC_SIZE_FILL
    return this
}
```

### Step 6: Update Documentation Comments
**File**: `src/main/kotlin/Pipe/Pipe.kt`
**Location**: Line ~106 (multiPageBudgetStrategy parameter documentation)

Update the documentation to include the new strategy:

```kotlin
* @param multiPageBudgetStrategy Determines how token budgeting allocates empty space, and leftover reserve area.
* Options: EQUAL_SPLIT (equal allocation), WEIGHTED_SPLIT (user-defined weights), 
* PRIORITY_FILL (key index order), DYNAMIC_FILL (priority + redistribution),
* DYNAMIC_SIZE_FILL (size-based priority + redistribution - protects smaller contexts)
```

### Step 7: Add Unit Tests
**File**: `src/test/kotlin/PipeTest.kt` (or appropriate test file)

```kotlin
@Test
fun testDynamicSizeFillStrategy()
{
    // Test scenario: Large story context + small gameplay context
    val pipe = BedrockPipe()
        .setPageKey("story, gameplay, inventory")
        .enableDynamicSizeFill()
    
    // Setup contexts with different sizes
    val storyContext = ContextWindow()
    storyContext.contextElements.addAll((1..1000).map { "Story line $it" })
    
    val gameplayContext = ContextWindow()
    gameplayContext.contextElements.add("Player health: 100")
    gameplayContext.contextElements.add("Player level: 5")
    
    val inventoryContext = ContextWindow()
    inventoryContext.contextElements.add("Sword")
    inventoryContext.contextElements.add("Shield")
    
    // Verify smaller contexts are protected
    // Implementation would test that gameplay and inventory get full allocation
    // while story gets truncated to fit remaining budget
}

@Test
fun testDynamicSizeFillRedistribution()
{
    // Test that unused budget from small contexts gets redistributed
    // to larger contexts that can use more tokens
}
```

### Step 8: Integration Testing
**Scenarios to Test**:

1. **Small contexts protected**: Verify small contexts get full allocation
2. **Large contexts truncated**: Verify large contexts are truncated appropriately  
3. **Budget redistribution**: Verify unused budget is redistributed intelligently
4. **Edge cases**: Empty contexts, zero budget, single context
5. **Mixed sizes**: Various combinations of small, medium, large contexts

### Step 9: Performance Considerations

**Complexity Analysis**:
- Size calculation: O(n) where n = number of contexts
- Sorting: O(n log n) 
- Allocation: O(n)
- Redistribution: O(n²) (inherited from DYNAMIC_FILL)
- **Total**: O(n²) - same as DYNAMIC_FILL

**Memory Impact**: Minimal additional memory for size calculations and sorting.

### Step 10: Documentation Updates

**Files to Update**:
- Core concepts documentation mentioning token budgeting strategies
- API documentation for MultiPageBudgetStrategy
- Examples showing DYNAMIC_SIZE_FILL usage

## Validation Criteria

### Functional Requirements
- ✅ Smaller contexts receive full allocation before larger contexts
- ✅ Larger contexts are truncated when budget is insufficient
- ✅ Unused budget is redistributed intelligently
- ✅ Strategy works with MiniBank multi-page contexts
- ✅ Integration with existing token budgeting system

### Performance Requirements  
- ✅ Performance comparable to existing DYNAMIC_FILL strategy
- ✅ No significant memory overhead
- ✅ Handles edge cases gracefully

### API Requirements
- ✅ Consistent with existing strategy API patterns
- ✅ Clear method naming and documentation
- ✅ Proper error handling and validation

## Example Usage

```kotlin
val pipe = BedrockPipe()
    .setPageKey("storyContent, gameplayData, userPreferences")
    .enableDynamicSizeFill()  // Protects smaller contexts first
    .setTokenBudget(TokenBudgetSettings(
        contextWindowSize = 32000,
        maxTokens = 4000
    ))
```

**Expected Behavior**:
- `userPreferences` (smallest): Gets full allocation
- `gameplayData` (medium): Gets full allocation if budget allows
- `storyContent` (largest): Gets remaining budget, may be truncated

## Implementation Notes

- Reuses existing `simulateTruncationUsage` and `redistributeBudgetDynamically` methods
- Follows same multi-pass approach as DYNAMIC_FILL
- Size-based sorting ensures predictable, logical allocation order
- Maintains compatibility with existing token budgeting infrastructure
