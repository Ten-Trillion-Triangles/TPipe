#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TPIPE_LIB="${1:-}"
if [ -z "$TPIPE_LIB" ]; then
    for candidate in \
        "$SCRIPT_DIR/../../../build/native/nativeCompile/TPipe.so" \
        "$SCRIPT_DIR/../../../build/native/nativeCompile/TPipe.dylib" \
        "$SCRIPT_DIR/../../../build/native/nativeCompile/TPipe.dll"; do
        if [ -f "$candidate" ]; then TPIPE_LIB="$candidate"; break; fi
    done
fi
if [ -z "$TPIPE_LIB" ] || [ ! -f "$TPIPE_LIB" ]; then
    echo "ERROR: TPipe shared library not found. Build first with ./gradlew nativeCompile."
    exit 1
fi
ISO_HEADER="$SCRIPT_DIR/../../../build/native/nativeCompile/graal_isolate.h"
if [ ! -f "$ISO_HEADER" ]; then ISO_HEADER=""; fi
TPIPE_ABI_DIR="$SCRIPT_DIR/../../../src/main/resources"
echo "Using library: $TPIPE_LIB"
gcc -O0 -g -o "$SCRIPT_DIR/tpipe_abi_compliance" "$SCRIPT_DIR/tpipe_abi_compliance.c" -ldl \
    ${ISO_HEADER:+-I"$(dirname "$ISO_HEADER")"} \
    -I"$TPIPE_ABI_DIR"
chmod +x "$SCRIPT_DIR/tpipe_abi_compliance_build.sh"
echo "Built $SCRIPT_DIR/tpipe_abi_compliance"
