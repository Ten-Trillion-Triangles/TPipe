#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TPIPE_LIB="${1:-}"
if [ -z "$TPIPE_LIB" ]; then
    # Auto-detect
    for candidate in \
        "$SCRIPT_DIR/../../../../build/native/libTPipe.so" \
        "$SCRIPT_DIR/../../../../build/native/libTPipe.dylib" \
        "$SCRIPT_DIR/../../../../build/native/TPipe.dll"; do
        if [ -f "$candidate" ]; then
            TPIPE_LIB="$candidate"
            break
        fi
    done
fi
if [ -z "$TPIPE_LIB" ] || [ ! -f "$TPIPE_LIB" ]; then
    echo "ERROR: Could not find libTPipe.so/.dylib/TPipe.dll. Build first with ./gradlew nativeCompile, then pass the path as argument."
    exit 1
fi
echo "Using TPipe library: $TPIPE_LIB"
gcc -O0 -g -o "$SCRIPT_DIR/tpipe_smoke" "$SCRIPT_DIR/tpipe_smoke.c" -ldl
chmod +x "$SCRIPT_DIR/tpipe_smoke_build.sh"
echo "Built $SCRIPT_DIR/tpipe_smoke"
