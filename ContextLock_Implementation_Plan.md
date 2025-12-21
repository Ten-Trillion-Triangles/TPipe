# ContextLock Implementation Plan

## Overview
Complete the ContextLock system by implementing the missing enforcement mechanisms that check and respect lock states during lorebook selection processes.

## Current State Analysis
- ✅ ContextLock infrastructure exists with KeyBundle tracking
- ✅ Lock metadata is set on ContextWindows (`metaData["isLocked"]`)
- ✅ Thread-safe lock management with Mutex
- ❌ **MISSING**: Lock enforcement during lorebook selection
- ❌ **MISSING**: passthroughFunction integration
- ❌ **MISSING**: Tests and validation

## Implementation Strategy

### Phase 1: Core Lock Enforcement

#### Step 1: Implement Lock Checking in ContextWindow
**File**: `/src/main/kotlin/Context/ContextWindow.kt`

**Objective**: Add lock checking methods to ContextWindow class

**Changes Required**:
1. Add `isContextLocked()` method to check if ContextWindow has lock metadata
2. Add `canSelectLoreBookKey()` method to validate individual key selection
3. Add `getLockedKeys()` method to retrieve list of locked keys from ContextLock

**Implementation Details**:
```kotlin
/**
 * Checks if this ContextWindow is currently locked by ContextLock system
 */
fun isContextLocked(): Boolean {
    return metaData["isLocked"] as? Boolean ?: false
}

/**
 * Checks if a specific lorebook key can be selected based on ContextLock state
 */
fun canSelectLoreBookKey(key: String): Boolean {
    if (!isContextLocked()) return true
    
    // Check if key has passthrough function that allows bypass
    val bundle = ContextLock.getKeyBundle(key)
    if (bundle?.passthroughFunction != null) {
        try {
            // Execute passthrough function - if returns true, allow selection
            return bundle.passthroughFunction?.invoke() ?: false
        } catch (e: Exception) {
            // If passthrough function fails, default to lock state
            return !bundle.isLocked
        }
    }
    
    return !ContextLock.isKeyLocked(key)
}

/**
 * Gets list of locked lorebook keys for this ContextWindow
 */
fun getLockedKeys(): Set<String> {
    if (!isContextLocked()) return emptySet()
    return ContextLock.getLockedKeysForContext(this)
}
```

**Validation Criteria**:
- Methods compile without errors
- `isContextLocked()` correctly reads metadata
- `canSelectLoreBookKey()` handles null cases safely
- Thread-safe access to ContextLock methods

---

#### Step 2: Add Missing ContextLock Query Methods
**File**: `/src/main/kotlin/Context/ContextLock.kt`

**Objective**: Add synchronous methods to query existing lock state

**Changes Required**:
1. Add `getKeyBundle()` method for external access
2. Add `isKeyLocked()` method for key-specific checks
3. Add `getLockedKeysForContext()` method for ContextWindow integration

**Implementation Details**:
```kotlin
/**
 * Gets the KeyBundle for a specific key (direct map access)
 */
fun getKeyBundle(key: String): KeyBundle? {
    return locks[key.lowercase()]
}

/**
 * Checks if a specific key is currently locked (direct map access)
 */
fun isKeyLocked(key: String): Boolean {
    val bundle = locks[key.lowercase()]
    return bundle?.isLocked ?: false
}

/**
 * Gets all locked keys that affect a specific ContextWindow
 */
fun getLockedKeysForContext(contextWindow: ContextWindow): Set<String> {
    return locks.values
        .filter { it.isLocked && !it.isPageKey }
        .flatMap { it.keys }
        .toSet()
}
```

**Validation Criteria**:
- Methods handle null/empty cases correctly
- Performance impact is minimal with direct map access

---

### Phase 2: Integrate Lock Enforcement into Selection Logic

#### Step 3: Modify findMatchingLoreBookKeys() to Respect Locks
**File**: `/src/main/kotlin/Context/ContextWindow.kt`
**Function**: `findMatchingLoreBookKeys()` (line 61)

**Objective**: Filter out locked keys during initial matching phase

**Changes Required**:
```kotlin
fun findMatchingLoreBookKeys(text: String): List<String> {
    val lowerText = text.lowercase()
    val matchingKeys = mutableSetOf<String>()
    
    loreBookKeys.forEach { (key, loreBook) ->
        // Check main key
        if (lowerText.contains(key.lowercase())) {
            // NEW: Check if key is locked before adding
            if (canSelectLoreBookKey(key)) {
                matchingKeys.add(key)
            }
        }
        
        // Check alias keys
        loreBook.aliasKeys.forEach { alias ->
            if (lowerText.contains(alias.lowercase())) {
                // NEW: Check if key is locked before adding
                if (canSelectLoreBookKey(key)) {
                    matchingKeys.add(key)
                }
            }
        }
    }
    
    return matchingKeys.toList()
}
```

**Validation Criteria**:
- Locked keys are excluded from matching results
- Alias key locks are properly handled

---

#### Step 4: Add Lock Validation to selectLoreBookContext()
**File**: `/src/main/kotlin/Context/ContextWindow.kt`
**Function**: `selectLoreBookContext()` (line 145)

**Objective**: Add final lock validation before token budget selection

**Changes Required**:
Insert lock validation after dependency checking (around line 220):

```kotlin
// Step 4: Create candidates with key, lorebook entry, and hit count
// Filter to only eligible keys and create sortable triples
val candidates = loreBookKeys.filter { (key, _) -> key in eligibleKeys }
    .map { (key, loreBook) -> 
        Triple(key, loreBook, keyHitCounts[key] ?: 0)
    }
    // NEW: Filter out locked keys
    .filter { (key, _, _) -> canSelectLoreBookKey(key) }
    .sortedWith(compareByDescending<Triple<String, LoreBook, Int>> { it.second.weight }
        .thenByDescending { it.third })
```

**Validation Criteria**:
- Locked keys are excluded from final selection
- Sorting and prioritization still work correctly

---

#### Step 5: Add Lock Validation to selectAndFillLoreBookContext()
**File**: `/src/main/kotlin/Context/ContextWindow.kt`
**Function**: `selectAndFillLoreBookContext()` (line 265)

**Objective**: Ensure weight-based filling respects locks

**Changes Required**:
Modify the fill candidates filtering (around line 310):

```kotlin
val fillCandidates = loreBookKeys.keys
    .filter { it !in selectedSet }
    // NEW: Filter out locked keys
    .filter { key -> canSelectLoreBookKey(key) }
    .mapNotNull { key -> loreBookKeys[key]?.let { key to it } }
    .sortedByDescending { (_, loreBook) -> loreBook.weight }
```

**Validation Criteria**:
- Weight-based filling excludes locked keys
- Performance remains acceptable
- Dependency validation still works with filtered candidates

---

### Phase 3: Enhanced Features and Edge Cases

#### Step 7: Implement Page Lock Enforcement in ContextBank
**File**: `/src/main/kotlin/Context/ContextBank.kt`

**Objective**: Prevent locked pages from being retrieved via getContextFromBank()

**Changes Required**:
Add page lock checking to `getContextFromBank()` method:

```kotlin
fun getContextFromBank(key: String, copy: Boolean = true) : ContextWindow
{
    // NEW: Check if this page is locked
    if (ContextLock.isPageLocked(key)) {
        return ContextWindow() // Return empty context for locked pages
    }
    
    var context = bank[key] ?: ContextWindow()

    /**
     * Automatically read from disk if this key is persisted. Triggers if the key is not loaded into memory,
     * but is found on disk.
     */
    val diskPath = "${TPipeConfig.getLorebookDir()}/${key}.bank"
    if(File(diskPath).exists() && !bank.containsKey(key))
    {
        val contextJson = readStringFromFile(diskPath)
        context = deserialize<ContextWindow>(contextJson) ?: ContextWindow()
    }

    if(copy)
    {
        return context.deepCopy()
    }

    return context
}
```

**Additional ContextLock Method Required**:
```kotlin
/**
 * Checks if a specific page key is locked
 */
fun isPageLocked(pageKey: String): Boolean {
    val bundle = locks[pageKey.lowercase()]
    return bundle?.isPageKey == true && bundle.isLocked
}
```

**Validation Criteria**:
- Locked pages return empty ContextWindow
- Unlocked pages work normally
- Page lock checking is performant

---

#### Step 8: Handle Global vs Page-Specific Locks
**File**: `/src/main/kotlin/Context/ContextLock.kt`

**Objective**: Ensure page-specific locks only affect relevant ContextWindows

**Changes Required**:
Enhance `getLockedKeysForContext()` to consider page context:

```kotlin
fun getLockedKeysForContext(contextWindow: ContextWindow, pageKey: String? = null): Set<String> {
    return locks.values
        .filter { bundle ->
            bundle.isLocked && !bundle.isPageKey && (
                bundle.isGlobal || 
                (pageKey != null && bundle.pages.contains(pageKey))
            )
        }
        .flatMap { it.keys }
        .toSet()
}
```

**Validation Criteria**:
- Global locks affect all ContextWindows
- Page-specific locks only affect relevant pages
- Performance is acceptable for page key lookups

---

### Phase 4: Testing and Validation

#### Step 9: Create Unit Tests
**File**: `/src/test/kotlin/Context/ContextLockTest.kt`

**Objective**: Comprehensive testing of ContextLock enforcement

**Test Cases Required**:
1. **Basic Lock Enforcement**:
   - Locked keys are excluded from `findMatchingLoreBookKeys()`
   - Locked keys are excluded from `selectLoreBookContext()`
   - Locked keys are excluded from `selectAndFillLoreBookContext()`

2. **passthroughFunction Testing**:
   - Function returning `true` allows selection despite lock
   - Function returning `false` respects lock
   - Exception handling in passthrough functions

3. **Global vs Page-Specific Locks**:
   - Global locks affect all pages
   - Page-specific locks only affect target pages
   - Lock removal clears metadata correctly

4. **Page Lock Enforcement**:
   - Locked pages return empty ContextWindow from ContextBank
   - Unlocked pages work normally
   - Page locks don't interfere with lorebook locks

5. **Thread Safety**:
   - Concurrent lock operations don't cause race conditions
   - Mutex usage prevents data corruption

5. **Performance Testing**:
   - Lock checking doesn't significantly impact selection performance
   - Large numbers of locks don't cause timeouts

**Validation Criteria**:
- All tests pass consistently
- Code coverage > 90% for ContextLock functionality
- Performance benchmarks meet acceptable thresholds

---

#### Step 10: Integration Testing
**File**: `/src/test/kotlin/Context/ContextLockIntegrationTest.kt`

**Objective**: Test ContextLock with full TPipe pipeline

**Test Scenarios**:
1. **Pipe Execution with Locked Context**:
   - Create pipe with locked lorebook keys
   - Verify locked keys don't appear in final context
   - Verify unlocked keys are still selected

2. **Token Budgeting with Locks**:
   - Test `truncateToFitTokenBudget()` respects locks
   - Verify budget allocation works with reduced key set

3. **AutoTruncate with Locks**:
   - Test `autoTruncateContext` respects locks
   - Verify provider-specific truncation works with locks

**Validation Criteria**:
- End-to-end pipeline respects ContextLock settings
- No regression in existing functionality
- Lock behavior is consistent across all truncation methods

---

### Phase 5: Documentation and Examples

#### Step 11: Update API Documentation
**File**: `/docs/api/context-lock.md`

**Objective**: Document complete ContextLock functionality

**Documentation Required**:
1. **Overview**: Purpose and use cases for ContextLock
2. **API Reference**: All public methods with examples
3. **Usage Patterns**: Common scenarios and best practices
4. **Performance Considerations**: Impact on selection performance
5. **Thread Safety**: Proper usage in concurrent environments

**Validation Criteria**:
- Documentation is complete and accurate
- Examples are tested and functional
- Performance guidance is provided

---

## Implementation Rules and Constraints

### Code Style Requirements
- Follow TPipe formatting rules (braces below parameters)
- Add comprehensive KDoc for all new methods
- Use proper error handling and null safety
- Maintain thread safety with appropriate mutex usage

### Performance Constraints
- Lock checking must not significantly impact selection performance
- Use volatile fields for thread-safe reads without mutex overhead
- Cache lock state to avoid repeated lookups
- Consider caching lock state for frequently accessed keys

### Compatibility Requirements
- Maintain backward compatibility with existing ContextWindow API
- Ensure all existing tests continue to pass
- Don't break existing lorebook selection behavior when locks are not used

### Error Handling
- Handle null/empty cases gracefully
- Provide meaningful error messages for invalid lock operations
- Ensure passthroughFunction exceptions don't crash selection
- Log lock-related decisions for debugging

## Success Criteria

### Functional Requirements
- ✅ Locked lorebook keys are excluded from all selection methods
- ✅ passthroughFunction allows conditional lock bypassing
- ✅ Global and page-specific locks work correctly
- ✅ Thread safety is maintained throughout
- ✅ Performance impact is minimal (< 10% overhead)

### Quality Requirements
- ✅ Unit test coverage > 90%
- ✅ Integration tests pass consistently
- ✅ Documentation is complete and accurate
- ✅ Code follows TPipe style guidelines
- ✅ No regressions in existing functionality

## Risk Mitigation

### Performance Risks
- **Risk**: Async lock checking in selection loops causes performance degradation
- **Mitigation**: Implement lock state caching and batch validation where possible

### Thread Safety Risks
- **Risk**: Race conditions between lock operations and selection
- **Mitigation**: Consistent mutex usage and proper async/await patterns

### Compatibility Risks
- **Risk**: Changes break existing lorebook selection behavior
- **Mitigation**: Comprehensive regression testing and feature flags for gradual rollout

## Estimated Implementation Time
- **Phase 1-2**: 2-3 days (Core enforcement)
- **Phase 3**: 1-2 days (Enhanced features)
- **Phase 4**: 2-3 days (Testing)
- **Phase 5**: 1 day (Documentation)
- **Total**: 6-9 days

This plan provides a systematic approach to completing the ContextLock implementation while maintaining code quality, performance, and compatibility with the existing TPipe system.
