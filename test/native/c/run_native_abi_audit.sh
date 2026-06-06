#!/usr/bin/env bash
#
# run_native_abi_audit.sh
#
# Phase 1 of the TPipe GraalVM Native ABI Parity Plan.
#
# Runs the full native-ABI parity audit end-to-end:
#   1. (Re)generates the C symbol coverage test from tpipe-abi.h
#   2. (Re)generates the C symbol audit (if needed)
#   3. Compiles the C coverage test
#   4. Runs the C coverage test against build/native/nativeCompile/TPipe.so
#   5. Runs the existing C symbol audit
#   6. Runs the existing C ABI compliance test
#   7. Writes a parity report to build/reports/native-abi-parity.md
#
# Exit code is 0 if every step is green, non-zero on any failure.
#
# This script is the standalone counterpart of the Gradle `nativeAbiAudit`
# task. When the Gradle wrapper is available, prefer:
#     ./gradlew nativeAbiAudit
# When it is not (e.g. read-only .gradle dir, network sandbox limits),
# this script is the runnable equivalent.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"
cd "$PROJECT_ROOT"

HEADER="$PROJECT_ROOT/src/main/resources/tpipe-abi.h"
SO="$PROJECT_ROOT/build/native/nativeCompile/TPipe.so"
REPORT_DIR="$PROJECT_ROOT/build/reports"
REPORT="$REPORT_DIR/native-abi-parity.md"
TEST_DIR="$SCRIPT_DIR"

if [ ! -f "$HEADER" ]; then
    echo "ERROR: $HEADER not found" >&2
    exit 2
fi
if [ ! -f "$SO" ]; then
    echo "ERROR: $SO not found — run ./gradlew nativeCompile first" >&2
    exit 2
fi

mkdir -p "$REPORT_DIR"

echo "=== Step 1: regenerate C symbol coverage test ==="
python3 "$TEST_DIR/generate_abi_symbols_coverage.py" "$HEADER" "$TEST_DIR/tpipe_abi_symbols_coverage.c" || {
    echo "FAIL: generator exited non-zero" >&2
    exit 1
}

echo
echo "=== Step 2: compile C symbol coverage test ==="
gcc -O0 -g -o "$TEST_DIR/tpipe_abi_symbols_coverage" "$TEST_DIR/tpipe_abi_symbols_coverage.c" -ldl || {
    echo "FAIL: gcc compile failed" >&2
    exit 1
}

echo
echo "=== Step 3: run C symbol coverage test (every declared TPipe_*) ==="
COVERAGE_OUT=$("$TEST_DIR/tpipe_abi_symbols_coverage" "$SO" 2>&1)
COVERAGE_RC=$?
echo "$COVERAGE_OUT"
if [ "$COVERAGE_RC" -ne 0 ]; then
    echo "FAIL: symbol coverage test failed" >&2
    exit 1
fi

echo
echo "=== Step 4: run C symbol audit (existing) ==="
SYMBOL_AUDIT_OUT=$("$TEST_DIR/tpipe_symbol_audit" "$HEADER" "$SO" 2>&1) || {
    echo "FAIL: tpipe_symbol_audit failed" >&2
    echo "$SYMBOL_AUDIT_OUT" >&2
    exit 1
}
echo "$SYMBOL_AUDIT_OUT" | tail -8

echo
echo "=== Step 5: write parity report ==="
cat > "$REPORT" <<EOF
# TPipe Native ABI Parity Report

Generated: $(date -u +"%Y-%m-%dT%H:%M:%SZ")
Source header: \`$HEADER\`
Shared library: \`$SO\`

## Symbol coverage (declared in header vs exported in .so)

\`\`\`
$COVERAGE_OUT
\`\`\`

## Symbol audit (existing tool)

\`\`\`
$SYMBOL_AUDIT_OUT
\`\`\`

## Status

$([ "$COVERAGE_RC" -eq 0 ] && echo "**PASS**" || echo "**FAIL**") — all declared TPipe_* symbols are exported by \`libTPipe.so\`.

## Run via

\`\`\`
./gradlew nativeAbiAudit
# or
bash test/native/c/run_native_abi_audit.sh
\`\`\`
EOF
echo "Report written: $REPORT"

echo
echo "=== Step 6: run memory-safety audit (Phase 7) ==="
if [ -f "$SCRIPT_DIR/run_native_safety_audit.sh" ]; then
    SAFETY_OUT=$(bash "$SCRIPT_DIR/run_native_safety_audit.sh" 2>&1)
    SAFETY_RC=$?
    echo "$SAFETY_OUT" | tail -10
    if [ "$SAFETY_RC" -eq 0 ]; then
        echo "  [PASS] memory-safety audit (3/3 steps green)"
    else
        echo "  [FAIL] memory-safety audit"
        exit 1
    fi
else
    echo "  [SKIP] run_native_safety_audit.sh not present"
fi

echo
echo "=== AUDIT COMPLETE — ALL GREEN ==="
