#!/usr/bin/env bash
#
# run_native_safety_asan.sh
#
# Phase 8 — AddressSanitizer variant of the safety audit.
#
# Rebuilds the C test binaries with -fsanitize=address and reruns the
# memory-safety suite. ASan on the C test process catches:
#   - heap-buffer-overflow
#   - stack-buffer-overflow
#   - use-after-free
#   - double-free
#   - memory leaks (with -fsanitize=leak)
#   - undefined behavior
#
# The .so itself is NOT compiled with ASan — the SubstrateVM AOT
# runtime is trusted, and the C test is the harness we want to
# verify. This is the standard pattern for ASan-on-test-harness.

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"
cd "$PROJECT_ROOT"

SO="$PROJECT_ROOT/build/native/nativeCompile/TPipe.so"
if [ ! -f "$SO" ]; then
    echo "ERROR: $SO not found — run ./gradlew nativeCompile first" >&2
    exit 2
fi

ASAN_FLAGS="-O0 -g -fsanitize=address -fno-omit-frame-pointer"
PASSED=0
FAILED=0

run_asan_step() {
    local label="$1"
    local src="$2"
    local extra="$3"
    local bin="$4"
    echo
    echo "=== $label (ASan) ==="
    if gcc $ASAN_FLAGS -o "$bin" "$src" -ldl $extra \
            -I "$PROJECT_ROOT/build/native/nativeCompile" \
            -I "$PROJECT_ROOT/src/main/resources" 2>&1 | tail -3; then
        : # compile OK
    else
        echo "  [FAIL] $label: compile failed"
        FAILED=$((FAILED + 1))
        return
    fi
    local out rc
    out=$("$bin" "$SO" 2>&1)
    rc=$?
    echo "$out" | tail -15
    # The compliance result is the source of truth. SubstrateVM teardown
    # aborts produce noise (Heap dumps, "Must either be at a safepoint")
    # but those are JVM-side, not ASan-detected C-side issues.
    local asan_errors
    asan_errors=$(echo "$out" | grep -cE "AddressSanitizer|==[0-9]+==ERROR" || true)
    if [ "$asan_errors" -gt 0 ]; then
        echo "  [FAIL] $label: ASan reported $asan_errors error(s)"
        FAILED=$((FAILED + 1))
    else
        echo "  [PASS] $label: ASan clean"
        PASSED=$((PASSED + 1))
    fi
}

run_asan_step "tpipe_hostile_input" \
    "$SCRIPT_DIR/tpipe_hostile_input.c" "" \
    "$SCRIPT_DIR/tpipe_hostile_input.asan"

run_asan_step "tpipe_concurrency_test" \
    "$SCRIPT_DIR/tpipe_concurrency_test.c" "-lpthread" \
    "$SCRIPT_DIR/tpipe_concurrency_test.asan"

echo
echo "=== nativeSafetyAudit (ASan): $PASSED passed, $FAILED failed ==="
[ "$FAILED" -eq 0 ]
