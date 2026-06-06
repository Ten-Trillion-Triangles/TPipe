#!/usr/bin/env python3
"""
ABI symbols coverage test generator for TPipe GraalVM Native Image.

Reads tpipe-abi.h and emits a C source file that:
  1. dlopens the .so
  2. dlsym-checks every declared TPipe_* function symbol
  3. Returns 0 if all declared symbols are exported, 1 otherwise

This is the "C-side parity" half of the Phase 1 matrix. The Kotlin
test (AbiParityMatrixTest) handles the JVM-side and header-side
parity; this C test confirms the binary side agrees with the
header side.

This script is idempotent: run it after any change to
tpipe-abi.h, and the generated C file stays in sync.

Usage:
    generate_abi_symbols_coverage.py \\
        <path-to-tpipe-abi.h> \\
        <output-c-file>
"""
import re
import sys
from pathlib import Path

# Match a function name "TPipe_*" followed by "(". Reject pointer-deref
# forms "*TPipe_foo(" and member access ".TPipe_foo(".
IDENT_RE = re.compile(r'\b(TPipe_[A-Za-z0-9_]+)\s*\(')


def extract_declared_symbols(header_path: Path):
    """Return the deduplicated, sorted list of TPipe_* function
    names declared in the header."""
    seen = {}
    for raw_line in header_path.read_text().splitlines():
        line = raw_line.split("//", 1)[0]
        if "TPipe_" not in line:
            continue
        for m in IDENT_RE.finditer(line):
            name = m.group(1)
            if m.start() > 0 and line[m.start() - 1] in ("*", "."):
                continue
            seen.setdefault(name, None)
    return sorted(seen.keys())


# The template is a regular triple-quoted Python string. Backslash-n
# in a Python string literal is a single newline; we want the C
# source to see the two-character sequence \n (backslash + n), so we
# use raw strings or escape them as \\n.
HEADER_TEMPLATE = r"""
/**
 * @file __OUT_NAME__
 * @brief ABI symbol coverage test (GENERATED - do not edit by hand).
 *
 * Regenerate with:
 *     test/native/c/generate_abi_symbols_coverage.py \
 *         src/main/resources/tpipe-abi.h \
 *         test/native/c/__OUT_NAME__
 *
 * Exit codes:
 *     0  every declared TPipe_* function is exported by the .so (PASS)
 *     1  one or more declared TPipe_* functions are missing from the .so
 *     2  setup error (bad argv, dlopen failure)
 *
 * This test does NOT verify that the symbols do anything sensible.
 * It only verifies the symbol exists in the .so. The Kotlin
 * AbiParityMatrixTest is the source of truth for "called from
 * JVM = exported in .so" and "declared in header = has @CEntryPoint".
 */

#include <dlfcn.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define MAX_NAME_LEN 256
#define MAX_MISSING 1024

static void* g_lib = NULL;
static int g_total = 0;
static int g_present = 0;
static char g_missing[MAX_MISSING][MAX_NAME_LEN];
static int g_missing_count = 0;

static void record_missing(const char* name) {
    if (g_missing_count >= MAX_MISSING) return;
    snprintf(g_missing[g_missing_count], MAX_NAME_LEN, "%s", name);
    g_missing_count++;
}

static int check(const char* name) {
    g_total++;
    void* sym = dlsym(g_lib, name);
    if (sym == NULL) {
        record_missing(name);
        return 0;
    }
    g_present++;
    return 1;
}

int main(int argc, char** argv) {
    if (argc < 2) {
        fprintf(stderr, "Usage: %s <path-to-libTPipe.so>\n", argv[0]);
        return 2;
    }
    const char* lib_path = argv[1];
    g_lib = dlopen(lib_path, RTLD_NOW);
    if (!g_lib) {
        fprintf(stderr, "dlopen('%s') failed: %s\n", lib_path, dlerror());
        return 2;
    }
    printf("Loaded %s\n", lib_path);

__SYMBOL_BLOCK__

    printf("Checking %d declared TPipe_* symbols...\n",
           (int)(sizeof(SYMBOL_LIST)/sizeof(SYMBOL_LIST[0])));
    printf("\n=== TPipe ABI Symbol Coverage ===\n");
    printf("Declared symbols checked: %d\n", g_total);
    printf("Symbols present in .so:  %d\n", g_present);
    printf("Symbols missing:          %d\n", g_missing_count);

    if (g_missing_count == 0) {
        printf("RESULT: PASS\n");
        dlclose(g_lib);
        return 0;
    }
    printf("RESULT: FAIL - %d symbols are declared in tpipe-abi.h but not exported\n", g_missing_count);
    printf("\nMissing symbols:\n");
    for (int i = 0; i < g_missing_count; i++) {
        printf("  - %s\n", g_missing[i]);
    }
    dlclose(g_lib);
    return 1;
}
"""


def render_symbol_block(names):
    """Render the per-symbol check() invocations."""
    lines = []
    lines.append("    static const char* SYMBOL_LIST[] = {")
    for n in names:
        lines.append('        "%s",' % n)
    lines.append("    };")
    lines.append("    for (size_t i = 0; i < sizeof(SYMBOL_LIST)/sizeof(SYMBOL_LIST[0]); i++) {")
    lines.append("        check(SYMBOL_LIST[i]);")
    lines.append("    }")
    return "\n".join(lines)


def main(argv):
    if len(argv) != 3:
        print("Usage: %s <header> <out.c>" % argv[0], file=sys.stderr)
        return 2
    header = Path(argv[1])
    out = Path(argv[2])
    if not header.exists():
        print("Header not found: %s" % header, file=sys.stderr)
        return 2
    names = extract_declared_symbols(header)
    if not names:
        print("No TPipe_* symbols found in %s" % header, file=sys.stderr)
        return 2
    body = (HEADER_TEMPLATE
            .replace("__OUT_NAME__", out.name)
            .replace("__SYMBOL_BLOCK__", render_symbol_block(names)))
    out.write_text(body)
    print("Generated %s with %d symbols from %s" % (out, len(names), header))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
