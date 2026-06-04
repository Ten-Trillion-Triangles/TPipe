/*
 * tpipe_stdio_main_smoke.c
 *
 * Smoke test for the TPipe_main top-level C entry point. Verifies that:
 *   1. TPipe.so can be dlopen'd.
 *   2. graal_create_isolate and graal_detach_thread are exported.
 *   3. TPipe_main is exported and callable.
 *   4. Calling TPipe_main with "stdio-once" mode + EOF on stdin returns 0.
 *
 * Usage:
 *     tpipe_stdio_main_smoke <path-to-TPipe.so>
 *
 * Exit codes:
 *     0  success
 *     1  dlsym failure
 *     2  setup error (dlopen, isolate creation)
 *     3  TPipe_main returned non-zero
 *
 * Recommended invocation:
 *     echo "" | LD_LIBRARY_PATH=build/native/nativeCompile \
 *                  ./tpipe_stdio_main_smoke build/native/nativeCompile/TPipe.so
 */

#include <dlfcn.h>
#include <stdio.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#include "graal_isolate.h"

int main(int argc, char** argv) {
    if (argc < 2) {
        fprintf(stderr, "Usage: %s <path-to-TPipe.so>\n", argv[0]);
        return 1;
    }

    const char* lib_path = argv[1];
    void* lib = dlopen(lib_path, RTLD_NOW);
    if (!lib) {
        fprintf(stderr, "dlopen(%s) failed: %s\n", lib_path, dlerror());
        return 2;
    }
    printf("Loaded %s\n", lib_path);

    /* Resolve GraalVM isolate lifecycle. */
    int (*gc)(graal_create_isolate_params_t*, graal_isolate_t**, graal_isolatethread_t**) =
        (int (*)(graal_create_isolate_params_t*, graal_isolate_t**, graal_isolatethread_t**))
        dlsym(lib, "graal_create_isolate");
    int (*gd)(graal_isolatethread_t*) =
        (int (*)(graal_isolatethread_t*)) dlsym(lib, "graal_detach_thread");
    int (*gti)(graal_isolatethread_t*) =
        (int (*)(graal_isolatethread_t*)) dlsym(lib, "graal_tear_down_isolate");

    if (!gc) {
        fprintf(stderr, "dlsym(graal_create_isolate) failed: %s\n", dlerror());
        dlclose(lib);
        return 2;
    }
    if (!gd) {
        fprintf(stderr, "dlsym(graal_detach_thread) failed: %s\n", dlerror());
        dlclose(lib);
        return 2;
    }

    /* Resolve TPipe_main. */
    int (*main_fn)(graal_isolatethread_t*, const char*) =
        (int (*)(graal_isolatethread_t*, const char*)) dlsym(lib, "TPipe_main");
    if (!main_fn) {
        fprintf(stderr, "dlsym(TPipe_main) failed: %s\n", dlerror());
        dlclose(lib);
        return 2;
    }
    printf("Resolved graal_create_isolate, graal_detach_thread, TPipe_main\n");

    /* Create isolate + thread. */
    graal_isolate_t* iso = NULL;
    graal_isolatethread_t* thr = NULL;
    if (gc(NULL, &iso, &thr) != 0 || !thr) {
        fprintf(stderr, "graal_create_isolate failed\n");
        dlclose(lib);
        return 2;
    }
    printf("Isolate + thread created\n");

    /* Call TPipe_main with "stdio-once" mode. With EOF on stdin, P2PStdioHost
     * returns without writing — TPipe_main must still return 0. */
    int rc = main_fn(thr, "stdio-once");
    fprintf(stderr, "TPipe_main returned %d\n", rc);
    printf("TPipe_main returned %d\n", rc);

    if (rc != 0) {
        fprintf(stderr, "TPipe_main returned %d (expected 0)\n", rc);
        if (gd) gd(thr);
        if (gti) gti(thr);
        dlclose(lib);
        return 3;
    }

    /* Print success BEFORE teardown — SubstrateVM may abort during teardown
     * due to a known issue with non-Java threads, but the smoke result is
     * already established by this point. The same pattern is used in
     * tpipe_abi_compliance.c. */
    printf("TPipe_main smoke test passed\n");
    fflush(stdout);
    fflush(stderr);

    /* Tear down (best-effort — may abort with SubstrateVM safepoint error
     * on some configurations, but the smoke result is already printed). */
    if (gd) gd(thr);
    if (gti) gti(thr);
    dlclose(lib);

    return 0;
}
