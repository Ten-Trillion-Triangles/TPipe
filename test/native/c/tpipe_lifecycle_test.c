/*
 * tpipe_lifecycle_test.c
 *
 * Phase 7 — basic ABI lifecycle smoke test.
 *
 * Exercises the minimum viable call sequence that a real C program would
 * use to drive the TPipe native library:
 *
 *   1. dlopen the .so
 *   2. graal_create_isolate
 *   3. TPipe_init
 *   4. TPipe_Content_create with a short ASCII string
 *   5. TPipe_Handle_release on the returned handle
 *   6. TPipe_shutdown
 *   7. graal_detach_thread + graal_tear_down_isolate
 *
 * The test passes if every step returns the documented success code and no
 * symbol resolution fails. It is intentionally minimal — see
 * tpipe_abi_compliance.c for the full per-symbol call surface, and
 * tpipe_handle_leak_test.c / tpipe_concurrency_test.c for the higher-load
 * variants.
 *
 * Compile:
 *     gcc -o tpipe_lifecycle_test tpipe_lifecycle_test.c -ldl \
 *         -I/path/to/graal/sdk \
 *         -I/path/to/tpipe/src/main/resources
 *
 * Run:
 *     ./tpipe_lifecycle_test /path/to/TPipe.so
 *
 * Exit codes:
 *     0  all lifecycle steps succeeded
 *     1  one or more lifecycle steps returned an unexpected value
 *     2  setup error (dlopen, isolate creation, dlsym)
 *     3  handle leak — the released handle still reports a non-zero refcount
 */

#include <dlfcn.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "graal_isolate.h"
#include "tpipe-abi.h"

int main(int argc, char** argv) {
    if (argc < 2) {
        fprintf(stderr, "Usage: %s <path-to-TPipe.so>\n", argv[0]);
        return 2;
    }

    const char* lib_path = argv[1];
    void* lib = dlopen(lib_path, RTLD_NOW);
    if (!lib) {
        fprintf(stderr, "dlopen(%s) failed: %s\n", lib_path, dlerror());
        return 2;
    }
    printf("Loaded %s\n", lib_path);

    /* ---- Resolve GraalVM isolate lifecycle ---- */
    int (*gc)(graal_create_isolate_params_t*, graal_isolate_t**, graal_isolatethread_t**) =
        (int (*)(graal_create_isolate_params_t*, graal_isolate_t**, graal_isolatethread_t**))
        dlsym(lib, "graal_create_isolate");
    int (*gd)(graal_isolatethread_t*) =
        (int (*)(graal_isolatethread_t*)) dlsym(lib, "graal_detach_thread");
    int (*gti)(graal_isolatethread_t*) =
        (int (*)(graal_isolatethread_t*)) dlsym(lib, "graal_tear_down_isolate");

    if (!gc || !gd || !gti) {
        fprintf(stderr, "dlsym(graal_*) failed: %s\n", dlerror());
        dlclose(lib);
        return 2;
    }

    /* ---- Resolve the 4 TPipe lifecycle symbols ---- */
    int (*init_fn)(graal_isolatethread_t*) =
        (int (*)(graal_isolatethread_t*)) dlsym(lib, "TPipe_init");
    int (*shutdown_fn)(graal_isolatethread_t*) =
        (int (*)(graal_isolatethread_t*)) dlsym(lib, "TPipe_shutdown");
    TPipe_Handle (*content_create_fn)(graal_isolatethread_t*, const char*) =
        (TPipe_Handle (*)(graal_isolatethread_t*, const char*)) dlsym(lib, "TPipe_Content_create");
    int (*handle_release_fn)(graal_isolatethread_t*, TPipe_Handle) =
        (int (*)(graal_isolatethread_t*, TPipe_Handle)) dlsym(lib, "TPipe_Handle_release");
    int (*handle_get_refcount_fn)(graal_isolatethread_t*, TPipe_Handle, int*) =
        (int (*)(graal_isolatethread_t*, TPipe_Handle, int*)) dlsym(lib, "TPipe_Handle_getRefCount");
    int (*handle_is_valid_fn)(graal_isolatethread_t*, TPipe_Handle) =
        (int (*)(graal_isolatethread_t*, TPipe_Handle)) dlsym(lib, "TPipe_Handle_isValid");

    if (!init_fn || !shutdown_fn || !content_create_fn ||
        !handle_release_fn || !handle_get_refcount_fn || !handle_is_valid_fn) {
        fprintf(stderr, "dlsym(TPipe_*) failed: %s\n", dlerror());
        dlclose(lib);
        return 2;
    }
    printf("Resolved graal_* + TPipe_init/shutdown/Content_create/Handle_release/getRefCount/isValid\n");

    /* ---- Create isolate + thread ---- */
    graal_isolate_t* iso = NULL;
    graal_isolatethread_t* thr = NULL;
    if (gc(NULL, &iso, &thr) != 0 || !thr) {
        fprintf(stderr, "graal_create_isolate failed\n");
        dlclose(lib);
        return 2;
    }
    printf("Isolate + thread created\n");

    /* ---- TPipe_init ---- */
    int rc = init_fn(thr);
    if (rc != 0) {
        fprintf(stderr, "TPipe_init returned %d (expected 0)\n", rc);
        gd(thr); gti(thr); dlclose(lib);
        return 1;
    }
    printf("TPipe_init -> 0\n");

    /* ---- TPipe_Content_create ---- */
    TPipe_Handle content = content_create_fn(thr, "lifecycle test");
    if (content == 0) {
        fprintf(stderr, "TPipe_Content_create returned 0 (expected non-zero)\n");
        shutdown_fn(thr); gd(thr); gti(thr); dlclose(lib);
        return 1;
    }
    printf("TPipe_Content_create -> %llu (high byte 0x%02llx = type discriminator)\n",
           (unsigned long long) content, (unsigned long long)((content >> 56) & 0xFF));

    /* ---- Verify the new handle reports refcount = 1 and is valid ---- */
    int refcount = -999;
    rc = handle_get_refcount_fn(thr, content, &refcount);
    if (rc != 0) {
        fprintf(stderr, "TPipe_Handle_getRefCount(new handle) returned %d\n", rc);
        handle_release_fn(thr, content);
        shutdown_fn(thr); gd(thr); gti(thr); dlclose(lib);
        return 1;
    }
    if (refcount != 1) {
        fprintf(stderr, "TPipe_Handle_getRefCount(new handle) -> %d (expected 1)\n", refcount);
        handle_release_fn(thr, content);
        shutdown_fn(thr); gd(thr); gti(thr); dlclose(lib);
        return 1;
    }
    printf("TPipe_Handle_getRefCount -> 1\n");

    if (handle_is_valid_fn(thr, content) != 1) {
        fprintf(stderr, "TPipe_Handle_isValid(new handle) returned 0 (expected 1)\n");
        handle_release_fn(thr, content);
        shutdown_fn(thr); gd(thr); gti(thr); dlclose(lib);
        return 1;
    }
    printf("TPipe_Handle_isValid -> 1\n");

    /* ---- TPipe_Handle_release ---- */
    rc = handle_release_fn(thr, content);
    if (rc != 0) {
        fprintf(stderr, "TPipe_Handle_release returned %d (expected 0)\n", rc);
        shutdown_fn(thr); gd(thr); gti(thr); dlclose(lib);
        return 1;
    }
    printf("TPipe_Handle_release -> 0\n");

    /* ---- Post-release sanity: getRefCount must report the handle as gone
     * (HandleRegistry returns TPIPE_ERR_INVALID_HANDLE = -0x03 = -3) ---- */
    int postRefcount = -999;
    rc = handle_get_refcount_fn(thr, content, &postRefcount);
    if (rc != TPIPE_ERR_INVALID_HANDLE) {
        fprintf(stderr, "TPipe_Handle_getRefCount after release returned %d (expected %d = TPIPE_ERR_INVALID_HANDLE)\n",
                rc, TPIPE_ERR_INVALID_HANDLE);
        shutdown_fn(thr); gd(thr); gti(thr); dlclose(lib);
        return 1;
    }
    printf("TPipe_Handle_getRefCount after release -> %d (TPIPE_ERR_INVALID_HANDLE — expected)\n", rc);

    /* isValid must report 0 (false) for the now-released handle. */
    if (handle_is_valid_fn(thr, content) != 0) {
        fprintf(stderr, "TPipe_Handle_isValid after release returned 1 (expected 0)\n");
        shutdown_fn(thr); gd(thr); gti(thr); dlclose(lib);
        return 1;
    }
    printf("TPipe_Handle_isValid after release -> 0 (expected)\n");

    /* ---- TPipe_shutdown ---- */
    rc = shutdown_fn(thr);
    if (rc != 0) {
        fprintf(stderr, "TPipe_shutdown returned %d (expected 0)\n", rc);
        gd(thr); gti(thr); dlclose(lib);
        return 1;
    }
    printf("TPipe_shutdown -> 0\n");

    /* Print success BEFORE teardown — SubstrateVM may abort during teardown
     * due to a known issue with non-Java threads, but the lifecycle result
     * is already established by this point. Same pattern as
     * tpipe_abi_compliance.c and tpipe_stdio_main_smoke.c. */
    printf("TPipe lifecycle test passed\n");
    fflush(stdout);
    fflush(stderr);

    /* Teardown (best-effort). */
    gd(thr);
    gti(thr);
    dlclose(lib);

    return 0;
}
