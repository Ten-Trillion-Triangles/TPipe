/**
 * @file tpipe_symbol_audit.c
 * @brief Audit every TPipe_* function declared in tpipe-abi.h against the
 *        symbols actually exported by the native shared library.
 *
 * This program scans the C ABI header (tpipe-abi.h) for every function
 * declaration whose name starts with "TPipe_". For each one, it uses
 * dlsym() on the .so to confirm a non-null function pointer is exported.
 *
 * The audit is the entry point for tracking the ABI completion work
 * (Phase 1 of the 9-phase plan). It is run by humans or by an automation
 * pipeline after a nativeCompile build to:
 *
 *   1. Quantify how many declared symbols are missing from the .so.
 *   2. Print the exact list of missing symbols with their line numbers in
 *      the header so a developer can locate the gap in a single step.
 *
 * Usage:
 *     tpipe_symbol_audit <path-to-tpipe-abi.h> <path-to-libTPipe.so>
 *
 * Exit codes:
 *     0  every declared TPipe_* function is exported by the .so (PASS)
 *     1  one or more declared TPipe_* functions are missing (FAIL)
 *     2  setup error (bad argv, dlopen failure, header not readable)
 *
 * Compile:
 *     gcc -o tpipe_symbol_audit tpipe_symbol_audit.c -ldl
 *
 * Run (after `./gradlew nativeCompile`):
 *     LD_LIBRARY_PATH=./build/libs/native/tpipe \
 *         ./tpipe_symbol_audit \
 *         ./build/libs/native/tpipe/tpipe-abi.h \
 *         ./build/libs/native/tpipe/libtpipe.so
 *
 * Note: the compilation toolchain is not used by the agent that produced
 * this file. The downstream human or automation step compiles it as shown
 * above. The program itself does not require a GraalVM SDK header — it
 * uses dlsym, which only needs the function NAME, not the signature.
 */

#include <ctype.h>
#include <dlfcn.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define MAX_LINE_LEN 1024
#define MAX_MISSING 512
#define MAX_NAME_LEN 256
#define MAX_REPORT_LEN (64 * 1024)

static void* g_lib = NULL;
static char g_missing[MAX_MISSING][MAX_NAME_LEN];
static int g_missing_line[MAX_MISSING];
static int g_missing_count = 0;
static int g_total_checked = 0;

static int is_identifier_char(int c) {
    return (c == '_' || isalnum(c)) ? 1 : 0;
}

/**
 * Scan a single line for a TPipe_ function declaration. A declaration
 * matches when:
 *   - the line contains the literal "TPipe_"
 *   - followed by a run of identifier characters
 *   - followed by zero or more whitespace characters
 *   - followed by '(' (the opening of the parameter list)
 *
 * Comments and multi-line decls spanning several source lines are not
 * handled — this audit only needs the function NAME, which is always
 * on the line that opens the parameter list. The header is written so
 * the return type + name + '(' all fit on a single line.
 */
static int extract_tpipe_function(const char* line, char* out_name, size_t out_name_size) {
    const char* p = line;
    while ((p = strstr(p, "TPipe_")) != NULL) {
        const char* name_start = p;
        const char* name_end = p + 6;
        if (!is_identifier_char((unsigned char)*(p + 6))) {
            p = name_end;
            continue;
        }
        while (is_identifier_char((unsigned char)*name_end)) {
            name_end++;
        }
        const char* q = name_end;
        while (*q == ' ' || *q == '\t') {
            q++;
        }
        if (*q != '(') {
            p = name_end;
            continue;
        }
        if (*name_start == '*' || *(name_start - 1) == '.') {
            p = name_end;
            continue;
        }
        size_t name_len = (size_t)(name_end - name_start);
        if (name_len == 0 || name_len >= out_name_size) {
            p = name_end;
            continue;
        }
        memcpy(out_name, name_start, name_len);
        out_name[name_len] = '\0';
        return 1;
    }
    return 0;
}

static int already_recorded(const char* name) {
    for (int i = 0; i < g_missing_count; i++) {
        if (strcmp(g_missing[i], name) == 0) return 1;
    }
    return 0;
}

static void record_missing(const char* name, int lineno) {
    if (already_recorded(name)) return;
    if (g_missing_count >= MAX_MISSING) return;
    snprintf(g_missing[g_missing_count], MAX_NAME_LEN, "%s", name);
    g_missing_line[g_missing_count] = lineno;
    g_missing_count++;
}

static int check_symbol(const char* name, int lineno) {
    g_total_checked++;
    void* sym = dlsym(g_lib, name);
    if (sym == NULL) {
        record_missing(name, lineno);
        return 0;
    }
    return 1;
}

int main(int argc, char** argv) {
    if (argc < 3) {
        fprintf(stderr, "Usage: %s <path-to-tpipe-abi.h> <path-to-libTPipe.so>\n", argv[0]);
        return 2;
    }
    const char* header_path = argv[1];
    const char* lib_path = argv[2];

    FILE* f = fopen(header_path, "r");
    if (!f) {
        fprintf(stderr, "Cannot open header '%s'\n", header_path);
        return 2;
    }

    g_lib = dlopen(lib_path, RTLD_NOW);
    if (!g_lib) {
        fprintf(stderr, "dlopen('%s') failed: %s\n", lib_path, dlerror());
        fclose(f);
        return 2;
    }
    printf("Loaded %s\n", lib_path);
    printf("Auditing %s\n", header_path);

    char line[MAX_LINE_LEN];
    int lineno = 0;
    int present = 0;
    char name[MAX_NAME_LEN];

    while (fgets(line, sizeof(line), f) != NULL) {
        lineno++;
        if (extract_tpipe_function(line, name, sizeof(name))) {
            if (check_symbol(name, lineno)) {
                present++;
            }
        }
    }
    fclose(f);

    int missing = g_missing_count;
    int total = g_total_checked;
    printf("\n=== TPipe ABI symbol audit ===\n");
    printf("Header declarations scanned: %d\n", total);
    printf("Symbols present in %s: %d\n", lib_path, present);
    printf("Symbols missing: %d\n", missing);

    if (missing == 0) {
        printf("RESULT: PASS — all declared TPipe_* functions exported\n");
        dlclose(g_lib);
        return 0;
    }

    printf("RESULT: FAIL — %d declared symbols are not exported\n", missing);
    printf("\nMissing symbols (header line : symbol name):\n");
    for (int i = 0; i < g_missing_count; i++) {
        printf("  %4d : %s\n", g_missing_line[i], g_missing[i]);
    }
    dlclose(g_lib);
    return 1;
}
