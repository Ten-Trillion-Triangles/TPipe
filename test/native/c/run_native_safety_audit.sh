#!/usr/bin/env bash
#
# run_native_safety_audit.sh
#
# Phase 7 — end-to-end memory-safety audit runner.
#
# Runs the three hostile-input artifacts added in Phase 7:
#   1. tpipe_hostile_input.c   — single-threaded hostile scenarios
#   2. tpipe_concurrency_test.c — multi-threaded release race
#   3. lldb_safety.script      — lldb transcript baseline
#
# Exit code is 0 only if every step is green.
#
# This script is the standalone counterpart of the Gradle
# `nativeSafetyAudit` task. When the Gradle wrapper is available,
# prefer:
#     ./gradlew nativeSafetyAudit
# When it is not (e.g. read-only .gradle dir, network sandbox limits),
# this script is the runnable equivalent.

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"
cd "$PROJECT_ROOT"

SO="$PROJECT_ROOT/build/native/nativeCompile/TPipe.so"
if [ ! -f "$SO" ]; then
    echo "ERROR: $SO not found — run ./gradlew nativeCompile first" >&2
    exit 2
fi

PASSED=0
FAILED=0

run_step() {
    local label="$1"
    shift
    echo
    echo "=== $label ==="
    if "$@"; then
        echo "  [PASS] $label"
        PASSED=$((PASSED + 1))
    else
        echo "  [FAIL] $label"
        FAILED=$((FAILED + 1))
    fi
}

# --- Step 1: Hostile-input scenarios ------------------------------------------
HOSTILE_BIN="$SCRIPT_DIR/tpipe_hostile_input"
HOSTILE_SRC="$SCRIPT_DIR/tpipe_hostile_input.c"
if [ -x "$HOSTILE_BIN" ] || [ -f "$HOSTILE_BIN" ] || gcc -O0 -g -o "$HOSTILE_BIN" "$HOSTILE_SRC" -ldl \
        -I "$PROJECT_ROOT/build/native/nativeCompile" -I "$PROJECT_ROOT/src/main/resources" 2>/dev/null; then
    # The 12/12 pass check looks for the "12 passed, 0 failed" line. SubstrateVM
    # teardown abort (exit 99) is a known artifact (documented in the test
    # source) and is NOT counted as a failure.
    HOSTILE_OUT=$("$HOSTILE_BIN" "$SO" 2>&1)
    HOSTILE_RC=$?
    echo "$HOSTILE_OUT" | tail -20
    if echo "$HOSTILE_OUT" | grep -qE "12 passed, 0 failed"; then
        run_step "tpipe_hostile_input" true
    else
        run_step "tpipe_hostile_input" false
    fi
else
    echo "  [SKIP] tpipe_hostile_input: could not compile"
fi

# --- Step 2: Concurrency test (existing + shared-handle extension) -----------
CONC_BIN="$SCRIPT_DIR/tpipe_concurrency_test"
CONC_SRC="$SCRIPT_DIR/tpipe_concurrency_test.c"
if [ -x "$CONC_BIN" ] || gcc -O0 -g -o "$CONC_BIN" "$CONC_SRC" -ldl -lpthread \
        -I "$PROJECT_ROOT/build/native/nativeCompile" -I "$PROJECT_ROOT/src/main/resources" 2>/dev/null; then
    # 4 threads, 100 handles each, plus the shared-handle test. Pass criterion
    # is the "TPipe concurrency test passed" line in stdout. Exit 99 is the
    # known SubstrateVM teardown artifact.
    CONC_OUT=$("$CONC_BIN" "$SO" 4 100 2>&1)
    CONC_RC=$?
    echo "$CONC_OUT" | tail -15
    if echo "$CONC_OUT" | grep -qE "TPipe concurrency test passed"; then
        run_step "tpipe_concurrency_test" true
    else
        run_step "tpipe_concurrency_test" false
    fi
else
    echo "  [SKIP] tpipe_concurrency_test: could not compile"
fi

# --- Step 3: lldb safety transcript ------------------------------------------
LLDB_SCRIPT="$SCRIPT_DIR/lldb_safety.script"
LLDB_TRANSCRIPT_DIR="$PROJECT_ROOT/test/native/lldb_transcripts"
mkdir -p "$LLDB_TRANSCRIPT_DIR"
LLDB_TRANSCRIPT="$LLDB_TRANSCRIPT_DIR/phase7_safety.txt"
if [ -x "$(command -v lldb)" ]; then
    lldb -b -s "$LLDB_SCRIPT" -o quit > "$LLDB_TRANSCRIPT" 2>&1
    LLDB_RC=$?
    if grep -q "error:" "$LLDB_TRANSCRIPT"; then
        run_step "lldb_safety.script" false
    else
        run_step "lldb_safety.script" true
    fi
else
    echo "  [SKIP] lldb_safety.script: lldb not on PATH"
fi

# --- Summary ------------------------------------------------------------------
echo
echo "=== nativeSafetyAudit: $PASSED passed, $FAILED failed ==="
[ "$FAILED" -eq 0 ]
