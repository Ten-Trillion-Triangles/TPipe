/*
 * tpipe_distribution_grid_compliance.c
 *
 * Focused compliance test for the TPipe_DistributionGrid_* C ABI symbols
 * exposed in Phase 11. Verifies that:
 *   1. TPipe.so can be dlopen'd.
 *   2. graal_create_isolate and graal_detach_thread are exported.
 *   3. The 6 TPipe_DistributionGrid_* symbols are exported and callable.
 *   4. Each call returns a sensible value (handle non-zero on create,
 *      0 on success, negative TPIPE_ERR_* on type mismatch).
 *
 * Usage:
 *     tpipe_distribution_grid_compliance <path-to-libTPipe.so>
 *
 * Exit codes:
 *     0  all checks passed
 *     1  one or more symbol calls misbehaved
 *     2  setup error (dlopen, isolate creation)
 *     3  dlsym failure on a required symbol
 *
 * Note: This test mirrors the structure of tpipe_abi_compliance.c — it
 * does not validate semantic correctness of every code path; only that
 * the 6 new Phase 11 symbols are bound, callable, and do not crash.
 */

#include <dlfcn.h>
#include <stdio.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#include "graal_isolate.h"
#include "tpipe-abi.h"

int main(int argc, char** argv) {
    if (argc < 2) {
        fprintf(stderr, "Usage: %s <path-to-libTPipe.so>\n", argv[0]);
        return 2;
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

    if (!gc || !gd || !gti) {
        fprintf(stderr, "dlsym(graal_*) failed: %s\n", dlerror());
        dlclose(lib);
        return 2;
    }

    /* Resolve the TPipe_init and TPipe_shutdown symbols. */
    int (*init_fn)(graal_isolatethread_t*) =
        (int (*)(graal_isolatethread_t*)) dlsym(lib, "TPipe_init");
    int (*shutdown_fn)(graal_isolatethread_t*) =
        (int (*)(graal_isolatethread_t*)) dlsym(lib, "TPipe_shutdown");
    if (!init_fn || !shutdown_fn) {
        fprintf(stderr, "dlsym(TPipe_init or TPipe_shutdown) failed: %s\n", dlerror());
        dlclose(lib);
        return 3;
    }

    /* Resolve the 6 Phase 11 DistributionGrid symbols. */
    TPipe_Handle (*dg_create)(graal_isolatethread_t*) =
        (TPipe_Handle (*)(graal_isolatethread_t*))
        dlsym(lib, "TPipe_DistributionGrid_create");
    int (*dg_release)(graal_isolatethread_t*, TPipe_DistributionGridHandle) =
        (int (*)(graal_isolatethread_t*, TPipe_DistributionGridHandle))
        dlsym(lib, "TPipe_DistributionGrid_release");
    int (*dg_get_node_count)(graal_isolatethread_t*, TPipe_DistributionGridHandle, int*) =
        (int (*)(graal_isolatethread_t*, TPipe_DistributionGridHandle, int*))
        dlsym(lib, "TPipe_DistributionGrid_getNodeCount");
    int (*dg_serialize)(graal_isolatethread_t*, TPipe_DistributionGridHandle, char*, int) =
        (int (*)(graal_isolatethread_t*, TPipe_DistributionGridHandle, char*, int))
        dlsym(lib, "TPipe_DistributionGrid_serialize");
    int (*dg_get_health)(graal_isolatethread_t*, TPipe_DistributionGridHandle, char*, int) =
        (int (*)(graal_isolatethread_t*, TPipe_DistributionGridHandle, char*, int))
        dlsym(lib, "TPipe_DistributionGrid_getHealth");
    int (*dg_rebalance_stub)(graal_isolatethread_t*, TPipe_DistributionGridHandle, char*, int) =
        (int (*)(graal_isolatethread_t*, TPipe_DistributionGridHandle, char*, int))
        dlsym(lib, "TPipe_DistributionGrid_rebalance_stub");

    if (!dg_create || !dg_release || !dg_get_node_count || !dg_serialize ||
        !dg_get_health || !dg_rebalance_stub) {
        fprintf(stderr, "dlsym(TPipe_DistributionGrid_*) failed: %s\n", dlerror());
        dlclose(lib);
        return 3;
    }
    printf("Resolved all 6 TPipe_DistributionGrid_* symbols\n");

    /* Create isolate + thread. */
    graal_isolate_t* iso = NULL;
    graal_isolatethread_t* thr = NULL;
    if (gc(NULL, &iso, &thr) != 0 || !thr) {
        fprintf(stderr, "graal_create_isolate failed\n");
        dlclose(lib);
        return 2;
    }
    printf("Isolate + thread created\n");

    /* Initialize the library. */
    int rc = init_fn(thr);
    if (rc != 0) {
        fprintf(stderr, "TPipe_init returned %d\n", rc);
        gd(thr); gti(thr); dlclose(lib);
        return 1;
    }

    /* Create a DistributionGrid handle. */
    TPipe_Handle grid = dg_create(thr);
    if (grid == 0) {
        fprintf(stderr, "TPipe_DistributionGrid_create returned 0\n");
        shutdown_fn(thr); gd(thr); gti(thr); dlclose(lib);
        return 1;
    }
    printf("TPipe_DistributionGrid_create -> %llu (handle high byte=0x%02llx)\n",
           (unsigned long long) grid, (unsigned long long)((grid >> 56) & 0xFF));

    /* getNodeCount on a valid handle. Stub returns 0 via [count]. */
    int nodeCount = -999;
    rc = dg_get_node_count(thr, grid, &nodeCount);
    if (rc != 0 || nodeCount != 0) {
        fprintf(stderr, "TPipe_DistributionGrid_getNodeCount: rc=%d, count=%d (expected 0, 0)\n",
                rc, nodeCount);
        dg_release(thr, grid); shutdown_fn(thr); gd(thr); gti(thr); dlclose(lib);
        return 1;
    }
    printf("TPipe_DistributionGrid_getNodeCount -> 0 nodes (rc=%d)\n", rc);

    /* serialize on a valid handle. Stub returns the fixed JSON sentinel. */
    char buf[256] = {0};
    int n = dg_serialize(thr, grid, buf, sizeof(buf));
    if (n <= 0) {
        fprintf(stderr, "TPipe_DistributionGrid_serialize returned %d\n", n);
        dg_release(thr, grid); shutdown_fn(thr); gd(thr); gti(thr); dlclose(lib);
        return 1;
    }
    printf("TPipe_DistributionGrid_serialize -> %d bytes: %s\n", n, buf);

    /* getHealth on a valid handle. Stub returns "ok". */
    memset(buf, 0, sizeof(buf));
    n = dg_get_health(thr, grid, buf, sizeof(buf));
    if (n <= 0 || strcmp(buf, "ok") != 0) {
        fprintf(stderr, "TPipe_DistributionGrid_getHealth: n=%d, buf='%s' (expected 'ok')\n", n, buf);
        dg_release(thr, grid); shutdown_fn(thr); gd(thr); gti(thr); dlclose(lib);
        return 1;
    }
    printf("TPipe_DistributionGrid_getHealth -> '%s' (%d bytes)\n", buf, n);

    /* rebalance_stub on a valid handle — returns the fixed sentinel. */
    memset(buf, 0, sizeof(buf));
    n = dg_rebalance_stub(thr, grid, buf, sizeof(buf));
    if (n <= 0) {
        fprintf(stderr, "TPipe_DistributionGrid_rebalance_stub returned %d\n", n);
        dg_release(thr, grid); shutdown_fn(thr); gd(thr); gti(thr); dlclose(lib);
        return 1;
    }
    printf("TPipe_DistributionGrid_rebalance_stub -> '%s' (%d bytes)\n", buf, n);

    /* release the handle. */
    rc = dg_release(thr, grid);
    if (rc != 0) {
        fprintf(stderr, "TPipe_DistributionGrid_release returned %d (expected 0)\n", rc);
        shutdown_fn(thr); gd(thr); gti(thr); dlclose(lib);
        return 1;
    }
    printf("TPipe_DistributionGrid_release -> 0\n");

    /* Negative path: getNodeCount on handle 0 should return TPIPE_ERR_INVALID_HANDLE. */
    int dummy = 0;
    rc = dg_get_node_count(thr, 0, &dummy);
    if (rc != TPIPE_ERR_INVALID_HANDLE) {
        fprintf(stderr, "TPipe_DistributionGrid_getNodeCount(0) returned %d (expected %d)\n",
                rc, TPIPE_ERR_INVALID_HANDLE);
        shutdown_fn(thr); gd(thr); gti(thr); dlclose(lib);
        return 1;
    }
    printf("TPipe_DistributionGrid_getNodeCount(0) -> %d (TPIPE_ERR_INVALID_HANDLE — expected)\n", rc);

    /* Print success BEFORE teardown — SubstrateVM may abort during teardown
     * due to a known issue with non-Java threads, but the compliance result
     * is already established. */
    printf("DistributionGrid compliance test passed\n");
    fflush(stdout);
    fflush(stderr);

    /* Teardown (best-effort). */
    shutdown_fn(thr);
    gd(thr);
    gti(thr);
    dlclose(lib);

    return 0;
}
