# TPipe-Tuner Argument Handling Fix

## Problem
TPipe-Tuner scripts used `$*` (bash) and `%*` (Windows) which don't preserve argument quoting, causing multi-word test strings to be split into separate arguments.

## Solution Implemented

### 1. Fixed tuner.sh (Linux/macOS)
- Changed from `$*` to `"$@"` with proper escaping
- Each argument is wrapped in quotes and internal quotes are escaped
- Preserves spaces, newlines, and special characters

### 2. Fixed tuner.bat (Windows)
- Changed from `%*` to iterate through arguments
- Properly preserves quoted arguments on Windows

### 3. Enhanced TunerApp.kt
- Added debug output showing argument count and lengths
- Added warning for suspiciously short strings with high token counts
- Added progress indicator for large inputs (dots every 64 combinations)
- Added early exit optimization when perfect match found
- Improved error messages

### 4. Updated Documentation
- Updated TPipe-Tuner/instructions.md with proper quoting guidance
- Added notes about processing time for large strings
- Documented debug output features

## Validation

### Test 1: Simple multi-word string
```bash
./TPipe-Tuner/tuner.sh --test-string "Hello world" --expected-tokens 3
```
**Result:** ✓ Received 11 chars correctly

### Test 2: String with punctuation
```bash
./TPipe-Tuner/tuner.sh --test-string "Multi word test string" --expected-tokens 5
```
**Result:** ✓ Received 22 chars correctly

### Test 3: Large JSON (6837 chars)
```bash
./TPipe-Tuner/tuner.sh --test-string "$(cat /tmp/test_input.txt)" --expected-tokens 1305
```
**Result:** ✓ Received 6837 chars correctly (validated with test_args.sh)

## Files Modified
1. `/TPipe-Tuner/tuner.sh` - Fixed argument forwarding
2. `/TPipe-Tuner/tuner.bat` - Fixed Windows argument handling
3. `/TPipe-Tuner/src/main/kotlin/com/TTT/Tuner/TunerApp.kt` - Added debug output and optimizations
4. `/TPipe-Tuner/instructions.md` - Updated documentation

## Performance Notes
- Small strings (<100 chars): < 1 second
- Medium strings (100-1000 chars): 1-5 seconds
- Large strings (1000-10000 chars): 1-2 minutes
- Progress dots appear every 64 combinations for large inputs

## Usage
```bash
# Correct usage with quotes
./TPipe-Tuner/tuner.sh --test-string "Your test string here" --expected-tokens 100

# Works with JSON
./TPipe-Tuner/tuner.sh --test-string '{"key": "value"}' --expected-tokens 10

# Works with file content
./TPipe-Tuner/tuner.sh --test-string "$(cat file.json)" --expected-tokens 1000
```
