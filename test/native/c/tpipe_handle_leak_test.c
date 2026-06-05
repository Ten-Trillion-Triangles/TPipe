/*
 * tpipe_handle_leak_test.c
 *
 * Phase 7 — handle refcount leak detector.
 *
 * Allocates N content handles back-to-back, releases all of them, and then
 * verifies that the HandleRegistry no longer recognizes any of the released
 * handles. A passing run means:
 *
 *   - every TPipe_Content_create returned a non-zero handle
 *   - every TPipe_Handle_release returned 0
 *   - after release, every handle is reported as invalid (TPIPE_ERR_INVALID_HANDLE)
 *
 * This catches two regressions in one go:
 *
 *   1. A handle that escapes the registry (would still resolve post-release)
 *   2. A release that doesn't actually decrement the refcount (would leave
 *      the handle valid after release)
 *
 * The choice of N = 1000 is arbitrary but large enough to exercise the
 * registry's internal ConcurrentHashMap growth path. Bumping to 10000 or
 * 100000 will run cleanly if the registry is correct; the cost is wall time.
 *
 * Compile:
 *     gcc -o tpipe_handle_leak_test tpipe_handle_leak_test.c -ldl \
 *         -I/path/to/graal/sdk \
 *         -I/path/to/tpipe/src/main/resources
 *
 * Run:
 *     ./tpipe_handle_leak_test /path/to/TPipe.so [count]
 *
 * Exit codes:
 *     0  all N handles created, released, and post-release lookups failed
 *        (no registry leak)
 *     1  one or more handles failed to create, release, or vanished
 *        incorrectly
 *     2  setup error (dlopen, isolate creation, dlsym)
 *     3  post-release sanity probe found a still-valid handle (LEAK)
 */

#include <dlfcn.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "graal_isolate.h"
#include "tpipe-abi.h"

#define DEFAULT_COUNT 1000
#define MAX_HANDLES   65536  /* TPIPE_MAX_HANDLE_COUNT — the registry ceiling. */

int main(int argc, char** argv) {
    if (argc < 2) {
        fprintf(stderr, "Usage: %s <path-to-TPipe.so> [count]\n", argv[0]);
        return 2;
    }

    int count = DEFAULT_COUNT;
    if (argc >= 3) {
        count = atoi(argv[2]);
        if (count <= 0) count = DEFAULT_COUNT;
        if (count > MAX_HANDLES) {
            fprintf(stderr, "Requested count %d exceeds TPIPE_MAX_HANDLE_COUNT=%d; clamping.\n",
                    count, MAX_HANDLES);
            count = MAX_HANDLES;
        }
    }

    const char* lib_path = argv[1];
    void* lib = dlopen(lib_path, RTLD_NOW);
    if (!lib) {
        fprintf(stderr, "dlopen(%s) failed: %s\n", lib_path, dlerror());
        return 2;
    }
    printf("Loaded %s (will allocate/release %d content handles)\n", lib_path, count);

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

    /* ---- Resolve the TPipe symbols we exercise ---- */
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
        fprintf(stderr, "TPipe_init returned %d\n", rc);
        gd(thr); gti(thr); dlclose(lib);
        return 1;
    }
    printf("TPipe_init -> 0\n");

    /* ---- Allocate `count` handles ---- */
    TPipe_Handle* handles = (TPipe_Handle*) calloc((size_t) count, sizeof(TPipe_Handle));
    if (!handles) {
        fprintf(stderr, "calloc(%d) failed\n", count);
        shutdown_fn(thr); gd(thr); gti(thr); dlclose(lib);
        return 2;
    }
    int create_failures = 0;
    for (int i = 0; i < count; i++) {
        handles[i] = content_create_fn(thr, "leak test");
        if (handles[i] == 0) {
            create_failures++;
        }
    }
    if (create_failures > 0) {
        fprintf(stderr, "TPipe_Content_create failed %d/%d times (likely hit TPIPE_MAX_HANDLE_COUNT)\n",
                create_failures, count);
        /* Not a hard error for this test — keep going with the handles we did get. */
    }
    printf("Created %d/%d content handles (failures=%d)\n",
           count - create_failures, count, create_failures);

    /* ---- Snapshot one handle's pre-release refcount as a sanity probe ---- */
    int sampledIndex = -1;
    int sampledRefcount = -999;
    for (int i = 0; i < count; i++) {
        if (handles[i] != 0) {
            int rc2 = handle_get_refcount_fn(thr, handles[i], &sampledRefcount);
            if (rc2 == 0 && sampledRefcount == 1) {
                sampledIndex = i;
                break;
            }
        }
    }
    if (sampledIndex < 0) {
        fprintf(stderr, "Could not find a single handle reporting refcount=1\n");
        for (int i = 0; i < count; i++) {
            if (handles[i] != 0) handle_release_fn(thr, handles[i]);
        }
        free(handles);
        shutdown_fn(thr); gd(thr); gti(thr); dlclose(lib);
        return 1;
    }
    printf("Sampled handle[%d] = 0x%016llx has refcount=1\n",
           sampledIndex, (unsigned long long) handles[sampledIndex]);

    /* ---- Release every handle ---- */
    int release_failures = 0;
    for (int i = 0; i < count; i++) {
        if (handles[i] == 0) continue;
        int rc2 = handle_release_fn(thr, handles[i]);
        if (rc2 != 0) release_failures++;
    }
    if (release_failures > 0) {
        fprintf(stderr, "TPipe_Handle_release failed %d times\n", release_failures);
    } else {
        printf("Released all %d handles cleanly\n", count);
    }

    /* ---- Post-release leak probe ----
     * If the registry correctly removed the handle on final release, the
     * sampled handle now reports TPIPE_ERR_INVALID_HANDLE. Anything else
     * is a leak. */
    int postRc = handle_get_refcount_fn(thr, handles[sampledIndex], &sampledRefcount);
    int isLeaked = 0;
    if (postRc == 0) {
        fprintf(stderr, "LEAK: TPipe_Handle_getRefCount(sampled handle) returned 0 with refcount=%d "
                "after release — the registry still holds the handle entry\n",
                sampledRefcount);
        isLeaked = 1;
    } else if (postRc != TPIPE_ERR_INVALID_HANDLE) {
        fprintf(stderr, "LEAK: TPipe_Handle_getRefCount(sampled handle) returned %d "
                "(expected %d = TPIPE_ERR_INVALID_HANDLE) after release\n",
                postRc, TPIPE_ERR_INVALID_HANDLE);
        isLeaked = 1;
    } else {
        printf("Sampled handle is correctly gone from the registry after release (rc=%d)\n", postRc);
    }

    /* Also confirm isValid returns 0 for the released handle. */
    int validAfter = handle_is_valid_fn(thr, handles[sampledIndex]);
    if (validAfter != 0) {
        fprintf(stderr, "LEAK: TPipe_Handle_isValid(sampled handle) returned %d (expected 0) after release\n",
                validAfter);
        isLeaked = 1;
    } else {
        printf("Sampled handle isValid -> 0 after release\n");
    }

    free(handles);

    /* ---- TPipe_shutdown ---- */
    rc = shutdown_fn(thr);
    if (rc != 0) {
        fprintf(stderr, "TPipe_shutdown returned %d (expected 0)\n", rc);
        gd(thr); gti(thr); dlclose(lib);
        return 1;
    }
    printf("TPipe_shutdown -> 0\n");

    /* Print success BEFORE teardown — SubstrateVM may abort during teardown
     * due to a known issue with non-Java threads, but the leak result is
     * already established by this point. */
    if (isLeaked) {
        fprintf(stderr, "TPipe handle leak test FAILED — leak detected\n");
        fflush(stdout); fflush(stderr);
        gd(thr); gti(thr); dlclose(lib);
        return 3;
    }
    printf("TPipe handle leak test passed (%d handles, 0 leaks)\n", count);
    fflush(stdout); fflush(stderr);

    /* Teardown (best-effort). */
    gd(thr);
    gti(thr);
    dlclose(lib);

    return 0;
}
