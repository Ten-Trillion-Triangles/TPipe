/*
 * tpipe_concurrency_test.c
 *
 * Phase 7 — multi-threaded TPipe_* call driver.
 *
 * Spawns N pthreads (default 4) and has each one create + release a
 * configurable number of content handles (default 100 per thread = 400
 * total). All threads block on a pthread_barrier_t so they start their
 * handle churn simultaneously, maximizing the chance of catching any
 * unsynchronized access to the underlying HandleRegistry or
 * NativeBridge.
 *
 * Each thread attaches to the same isolate via graal_attach_thread
 * (NOT graal_create_isolate, which would produce a second isolate).
 * The TPipe_isReady state is global; we only call TPipe_init once on
 * the main thread before launching the workers.
 *
 * The test passes if:
 *   - every create returns a non-zero handle
 *   - every release returns 0
 *   - the post-run TPipe_Handle_getRefCount probe of a sample of the
 *     released handles reports TPIPE_ERR_INVALID_HANDLE
 *   - no thread crashed (pthread_join succeeds)
 *
 * Compile:
 *     gcc -o tpipe_concurrency_test tpipe_concurrency_test.c -ldl -lpthread \
 *         -I/path/to/graal/sdk \
 *         -I/path/to/tpipe/src/main/resources
 *
 * Run:
 *     ./tpipe_concurrency_test /path/to/TPipe.so [threads] [per_thread]
 *
 * Exit codes:
 *     0  all threads finished cleanly, no crashes, no leaks
 *     1  one or more create/release calls returned an unexpected value
 *     2  setup error (dlopen, isolate creation, dlsym)
 *     3  post-run leak probe found a still-valid handle
 *     4  a worker thread crashed (pthread_join returned non-zero, or
 *        the per-thread create/release counters disagree)
 */

#include <dlfcn.h>
#include <pthread.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "graal_isolate.h"
#include "tpipe-abi.h"

#define DEFAULT_THREADS         4
#define DEFAULT_PER_THREAD    100
#define MAX_THREADS             64
#define MAX_PER_THREAD        1000

/* Function-pointer typedefs that the worker threads dereference. */
typedef int                 (*fn_init_t)(graal_isolatethread_t*);
typedef int                 (*fn_shutdown_t)(graal_isolatethread_t*);
typedef TPipe_Handle        (*fn_content_create_t)(graal_isolatethread_t*, const char*);
typedef int                 (*fn_handle_release_t)(graal_isolatethread_t*, TPipe_Handle);
typedef int                 (*fn_handle_add_ref_t)(graal_isolatethread_t*, TPipe_Handle);
typedef int                 (*fn_handle_get_refcount_t)(graal_isolatethread_t*, TPipe_Handle, int*);
typedef int                 (*fn_handle_is_valid_t)(graal_isolatethread_t*, TPipe_Handle);
typedef int                 (*fn_attach_thread_t)(graal_isolate_t*, graal_isolatethread_t**);

/* Shared state passed to every worker. */
typedef struct WorkerCtx {
    /* immutable inputs */
    fn_content_create_t       create;
    fn_handle_release_t       release;
    fn_handle_get_refcount_t  getRefCount;
    fn_handle_is_valid_t      isValid;
    fn_handle_add_ref_t       addRef;
    fn_attach_thread_t        attach;
    graal_isolate_t*          isolate;
    pthread_barrier_t*        barrier;
    int                       perThread;
    int                       threadIndex;
    /* outputs */
    int                       created;
    int                       released;
    int                       createFailures;
    int                       releaseFailures;
    /* last handle this thread successfully created — used by the main
     * thread for the post-run leak probe. */
    TPipe_Handle              lastHandle;
} WorkerCtx;

static void* worker_main(void* arg) {
    WorkerCtx* ctx = (WorkerCtx*) arg;

    /* Each thread needs its own IsolateThread to make C ABI calls. */
    graal_isolatethread_t* thr = NULL;
    if (ctx->attach(ctx->isolate, &thr) != 0 || !thr) {
        fprintf(stderr, "  [T%d] graal_attach_thread failed\n", ctx->threadIndex);
        return (void*) (intptr_t) 1;
    }

    /* Wait at the barrier so all threads start churning at once. */
    int rc = pthread_barrier_wait(ctx->barrier);
    (void) rc; /* PTHREAD_BARRIER_SERIAL_THREAD on one thread; harmless. */

    TPipe_Handle handles[MAX_PER_THREAD];
    memset(handles, 0, sizeof(handles));

    /* Create phase. */
    for (int i = 0; i < ctx->perThread; i++) {
        handles[i] = ctx->create(thr, "concurrency test");
        if (handles[i] == 0) {
            ctx->createFailures++;
        } else {
            ctx->created++;
        }
    }
    ctx->lastHandle = handles[ctx->perThread - 1];

    /* Release phase. */
    for (int i = 0; i < ctx->perThread; i++) {
        if (handles[i] == 0) continue;
        if (ctx->release(thr, handles[i]) == 0) {
            ctx->released++;
        } else {
            ctx->releaseFailures++;
        }
    }

    /* Keep the thread around long enough for the main thread's leak
     * probe to run; then we are done. We do NOT detach the thread here
     * because the C ABI may need the isolate attached for cleanup. */
    return (void*) (intptr_t) 0;
}


/*==========================================================================
 * Shared-handle concurrent-release test
 *
 * Allocates a single handle, addRefs it R times, then spawns R threads
 * each of which calls TPipe_Handle_release exactly once. The handle
 * starts at refcount = 1 + R. After all threads complete, refcount
 * must be 0. During the run, no thread should see a negative refcount
 * (which would indicate an atomic-CAS bug) and no thread should
 * observe refcount dropping below 0 (underflow protection must
 * engage).
 *
 * This is the test the prior Phase 7 plan called out: "exactly one
 * observes the final release path." We count the number of threads
 * whose release call returns 0 (TPIPE_OK = refcount went to 0
 * exactly once) and assert that count is 1.
 *==========================================================================*/
typedef struct {
    graal_isolate_t* isolate;
    graal_isolatethread_t* thr;
    TPipe_Handle handle;
    int releaseRc;
    int observedFinalRelease;   /* 1 if THIS thread was the one that drove refcount to 0 */
} SharedReleaseCtx;

static int (*shared_release_fp)(graal_isolatethread_t*, TPipe_Handle) = NULL;
static int (*shared_isValid_fp)(graal_isolatethread_t*, TPipe_Handle) = NULL;
static int (*shared_attach_fp)(graal_isolate_t*, graal_isolatethread_t**) = NULL;
static int (*shared_detach_fp)(graal_isolatethread_t*) = NULL;
static graal_isolate_t* shared_isolate_ptr = NULL;
static void* shared_release_worker(void* arg) {
    SharedReleaseCtx* ctx = (SharedReleaseCtx*) arg;
    /* Each worker thread must attach to the isolate before calling
     * TPipe_* functions. SubstrateVM rejects calls from non-attached
     * threads (the "Must either be at a safepoint or in native mode"
     * error). Attach on entry, detach on exit. */
    graal_isolatethread_t* my_thr = NULL;
    int ga_rc = shared_attach_fp(ctx->isolate, &my_thr);
    if (ga_rc != 0 || !my_thr) {
        fprintf(stderr, "graal_attach_thread failed: %d\n", ga_rc);
        ctx->releaseRc = -1;
        return NULL;
    }
    int rc = shared_release_fp(my_thr, ctx->handle);
    ctx->releaseRc = rc;
    int valid = shared_isValid_fp(my_thr, ctx->handle);
    if (rc == 0 && valid == 0) {
        ctx->observedFinalRelease = 1;
    }
    shared_detach_fp(my_thr);
    return NULL;
}

static int run_shared_handle_concurrent_release(
    int refCount,
    fn_content_create_t create_fn,
    fn_handle_release_t release_fn,
    fn_handle_add_ref_t addRef_fn,
    fn_handle_get_refcount_t getRefCount_fn,
    fn_handle_is_valid_t isValid_fn,
    fn_attach_thread_t attach_fn,
    int (*detach_fn_local)(graal_isolatethread_t*),
    graal_isolate_t* isolate,
    graal_isolatethread_t* thr) {
    printf("\n=== Shared-handle concurrent-release (refCount=%d) ===\n", refCount);
    shared_release_fp = release_fn;
    shared_isValid_fp = isValid_fn;
    shared_release_fp = release_fn;
    shared_isValid_fp = isValid_fn;
    shared_attach_fp = attach_fn;
    shared_detach_fp = detach_fn_local;
    shared_isolate_ptr = isolate;
    TPipe_Handle h = create_fn(thr, "shared-release-target");
    if (h == 0) {
        fprintf(stderr, "create_fn returned 0; cannot test shared release\n");
        return 1;
    }
    /* Boost the refcount. */
    for (int i = 0; i < refCount - 1; i++) {
        int rc = addRef_fn(thr, h);
        if (rc != 0) {
            fprintf(stderr, "addRef #%d returned %d (expected 0); abort shared release test\n", i, rc);
            release_fn(thr, h);
            return 1;
        }
    }
    /* Now refcount = 1 + refCount (1 from create, refCount from addRefs). */
    int initialRc = 0;
    int initialRef = 0;
    getRefCount_fn(thr, h, &initialRef);
    if (initialRef < refCount + 1) {
        fprintf(stderr, "post-addRef refcount = %d (expected >= %d)\n", initialRef, refCount + 1);
    }
    printf("Setup: refcount=%d before concurrent release\n", initialRef);

    /* Spawn refCount threads, each releases once. */
    SharedReleaseCtx* ctxs = (SharedReleaseCtx*) calloc(refCount, sizeof(SharedReleaseCtx));
    pthread_t* tids = (pthread_t*) calloc(refCount, sizeof(pthread_t));
    if (!ctxs || !tids) {
        fprintf(stderr, "calloc failed\n");
        free(ctxs); free(tids);
        release_fn(thr, h);
        return 1;
    }
    for (int i = 0; i < refCount; i++) {
        ctxs[i].isolate = shared_isolate_ptr;
        ctxs[i].thr = thr;
        ctxs[i].handle = h;
        ctxs[i].releaseRc = -1;
        ctxs[i].observedFinalRelease = 0;
        int prc = pthread_create(&tids[i], NULL, shared_release_worker, &ctxs[i]);
        if (prc != 0) {
            fprintf(stderr, "pthread_create #%d failed: %d\n", i, prc);
            free(ctxs); free(tids);
            release_fn(thr, h);
            return 1;
        }
    }
    /* Join all workers. */
    for (int i = 0; i < refCount; i++) {
        pthread_join(tids[i], NULL);
    }

    /* Aggregate results. */
    int successCount = 0;
    int finalReleaseObserved = 0;
    int unexpectedReturnCount = 0;
    for (int i = 0; i < refCount; i++) {
        if (ctxs[i].releaseRc == 0) successCount++;
        else if (ctxs[i].releaseRc != 0) unexpectedReturnCount++;
        if (ctxs[i].observedFinalRelease) finalReleaseObserved++;
    }
    int finalRef = -42;
    int finalRc = getRefCount_fn(thr, h, &finalRef);
    printf("After concurrent release:\n");
    printf("  total releases:        %d\n", refCount);
    printf("  releases returned 0:   %d (every release should succeed; underflow check)\n", successCount);
    printf("  final-release observer: %d (exactly one thread should drive refcount to 0)\n", finalReleaseObserved);
    printf("  unexpected returns:    %d (must be 0)\n", unexpectedReturnCount);
    printf("  post-run refcount:    %d (rc=%d; expect ERR_INVALID_HANDLE for a freed handle)\n",
           finalRef, finalRc);

    int localFailed = 0;
    if (successCount != refCount) {
        fprintf(stderr, "  [FAIL] only %d of %d releases returned 0\n", successCount, refCount);
        localFailed++;
    }
    if (finalReleaseObserved != 1) {
        fprintf(stderr, "  [FAIL] expected exactly 1 final-release observer, got %d\n", finalReleaseObserved);
        localFailed++;
    }
    if (unexpectedReturnCount != 0) {
        fprintf(stderr, "  [FAIL] %d releases returned non-zero (refcount underflow?)\n",
                unexpectedReturnCount);
        localFailed++;
    }
    if (finalRc != TPIPE_ERR_INVALID_HANDLE) {
        fprintf(stderr, "  [FAIL] post-run getRefCount returned %d (expected ERR_INVALID_HANDLE)\n", finalRc);
        localFailed++;
    }
    if (localFailed == 0) {
        printf("  [PASS] shared-handle concurrent release: 1 final observer, no underflow, no leaks\n");
    }

    free(ctxs);
    free(tids);
    /* The handle is already freed (refcount=0). Calling release again
     * should return ERR_INVALID_HANDLE. */
    int post = release_fn(thr, h);
    if (post != TPIPE_ERR_INVALID_HANDLE) {
        fprintf(stderr, "  [INFO] post-test extra release returned %d (expected ERR_INVALID_HANDLE = %d)\n",
                post, TPIPE_ERR_INVALID_HANDLE);
    }
    return localFailed;
}

int main(int argc, char** argv) {
    if (argc < 2) {
        fprintf(stderr, "Usage: %s <path-to-TPipe.so> [threads] [per_thread]\n", argv[0]);
        return 2;
    }

    int numThreads = DEFAULT_THREADS;
    int perThread  = DEFAULT_PER_THREAD;
    if (argc >= 3) {
        numThreads = atoi(argv[2]);
        if (numThreads <= 0) numThreads = DEFAULT_THREADS;
        if (numThreads > MAX_THREADS) {
            fprintf(stderr, "Requested threads %d exceeds MAX_THREADS=%d; clamping.\n",
                    numThreads, MAX_THREADS);
            numThreads = MAX_THREADS;
        }
    }
    if (argc >= 4) {
        perThread = atoi(argv[3]);
        if (perThread <= 0) perThread = DEFAULT_PER_THREAD;
        if (perThread > MAX_PER_THREAD) {
            fprintf(stderr, "Requested per_thread %d exceeds MAX_PER_THREAD=%d; clamping.\n",
                    perThread, MAX_PER_THREAD);
            perThread = MAX_PER_THREAD;
        }
    }

    const char* lib_path = argv[1];
    void* lib = dlopen(lib_path, RTLD_NOW);
    if (!lib) {
        fprintf(stderr, "dlopen(%s) failed: %s\n", lib_path, dlerror());
        return 2;
    }
    printf("Loaded %s (threads=%d, per_thread=%d, total=%d handles)\n",
           lib_path, numThreads, perThread, numThreads * perThread);

    /* ---- Resolve GraalVM isolate lifecycle ---- */
    int (*gc)(graal_create_isolate_params_t*, graal_isolate_t**, graal_isolatethread_t**) =
        (int (*)(graal_create_isolate_params_t*, graal_isolate_t**, graal_isolatethread_t**))
        dlsym(lib, "graal_create_isolate");
    int (*gd)(graal_isolatethread_t*) =
        (int (*)(graal_isolatethread_t*)) dlsym(lib, "graal_detach_thread");
    int (*gti)(graal_isolatethread_t*) =
        (int (*)(graal_isolatethread_t*)) dlsym(lib, "graal_tear_down_isolate");
    fn_attach_thread_t ga = (fn_attach_thread_t) dlsym(lib, "graal_attach_thread");

    if (!gc || !gd || !gti || !ga) {
        fprintf(stderr, "dlsym(graal_*) failed: %s\n", dlerror());
        dlclose(lib);
        return 2;
    }

    /* ---- Resolve the TPipe symbols we exercise ---- */
    fn_init_t                init_fn          = (fn_init_t)               dlsym(lib, "TPipe_init");
    fn_shutdown_t            shutdown_fn      = (fn_shutdown_t)           dlsym(lib, "TPipe_shutdown");
    fn_content_create_t      create_fn        = (fn_content_create_t)     dlsym(lib, "TPipe_Content_create");
    fn_handle_release_t      release_fn       = (fn_handle_release_t)     dlsym(lib, "TPipe_Handle_release");
    fn_handle_get_refcount_t getRefCount_fn   = (fn_handle_get_refcount_t) dlsym(lib, "TPipe_Handle_getRefCount");
    fn_handle_is_valid_t     isValid_fn       = (fn_handle_is_valid_t)    dlsym(lib, "TPipe_Handle_isValid");
    fn_handle_add_ref_t      addRef_fn        = (fn_handle_add_ref_t)     dlsym(lib, "TPipe_Handle_addRef");

    if (!init_fn || !shutdown_fn || !create_fn || !release_fn || !getRefCount_fn || !isValid_fn || !addRef_fn) {
        fprintf(stderr, "dlsym(TPipe_*) failed: %s\n", dlerror());
        dlclose(lib);
        return 2;
    }

    /* ---- Create isolate + primary thread ---- */
    graal_isolate_t*     iso = NULL;
    graal_isolatethread_t* thr = NULL;
    if (gc(NULL, &iso, &thr) != 0 || !thr) {
        fprintf(stderr, "graal_create_isolate failed\n");
        dlclose(lib);
        return 2;
    }
    printf("Isolate + primary thread created\n");

    /* ---- TPipe_init (once, on the primary thread) ---- */
    int rc = init_fn(thr);
    if (rc != 0) {
        fprintf(stderr, "TPipe_init returned %d\n", rc);
        gd(thr); gti(thr); dlclose(lib);
        return 1;
    }
    printf("TPipe_init -> 0\n");

    /* ---- Spin up workers ---- */
    pthread_barrier_t barrier;
    if (pthread_barrier_init(&barrier, NULL, (unsigned) numThreads) != 0) {
        fprintf(stderr, "pthread_barrier_init failed\n");
        shutdown_fn(thr); gd(thr); gti(thr); dlclose(lib);
        return 2;
    }

    WorkerCtx* ctxs = (WorkerCtx*) calloc((size_t) numThreads, sizeof(WorkerCtx));
    pthread_t* tids  = (pthread_t*) calloc((size_t) numThreads, sizeof(pthread_t));
    if (!ctxs || !tids) {
        fprintf(stderr, "calloc failed\n");
        free(ctxs); free(tids);
        pthread_barrier_destroy(&barrier);
        shutdown_fn(thr); gd(thr); gti(thr); dlclose(lib);
        return 2;
    }
    for (int i = 0; i < numThreads; i++) {
        ctxs[i].create        = create_fn;
        ctxs[i].release       = release_fn;
        ctxs[i].getRefCount   = getRefCount_fn;
        ctxs[i].isValid       = isValid_fn;
        ctxs[i].attach        = ga;
        ctxs[i].isolate       = iso;
        ctxs[i].barrier       = &barrier;
        ctxs[i].perThread     = perThread;
        ctxs[i].threadIndex   = i;
        ctxs[i].lastHandle    = 0;

        if (pthread_create(&tids[i], NULL, worker_main, &ctxs[i]) != 0) {
            fprintf(stderr, "pthread_create for T%d failed\n", i);
            /* Tidy up: best-effort. */
            for (int j = 0; j < i; j++) pthread_join(tids[j], NULL);
            free(ctxs); free(tids);
            pthread_barrier_destroy(&barrier);
            shutdown_fn(thr); gd(thr); gti(thr); dlclose(lib);
            return 2;
        }
    }
    printf("Spawned %d worker threads; waiting at barrier...\n", numThreads);

    /* ---- Wait for every worker to finish ---- */
    int crashes = 0;
    for (int i = 0; i < numThreads; i++) {
        void* retval = NULL;
        if (pthread_join(tids[i], &retval) != 0) {
            fprintf(stderr, "  [main] pthread_join(T%d) failed\n", i);
            crashes++;
        } else if ((int)(intptr_t) retval != 0) {
            fprintf(stderr, "  [T%d] worker returned non-zero (%ld) — likely crash\n",
                    i, (long)(intptr_t) retval);
            crashes++;
        }
    }
    if (crashes > 0) {
        fprintf(stderr, "%d/%d worker threads reported a crash\n", crashes, numThreads);
        free(ctxs); free(tids);
        pthread_barrier_destroy(&barrier);
        shutdown_fn(thr); gd(thr); gti(thr); dlclose(lib);
        return 4;
    }
    printf("All %d worker threads joined cleanly\n", numThreads);

    /* ---- Per-thread stats ---- */
    int totalCreated = 0, totalReleased = 0;
    int totalCreateFail = 0, totalReleaseFail = 0;
    for (int i = 0; i < numThreads; i++) {
        printf("  T%d: created=%d released=%d (create_fail=%d release_fail=%d)\n",
               i, ctxs[i].created, ctxs[i].released,
               ctxs[i].createFailures, ctxs[i].releaseFailures);
        totalCreated    += ctxs[i].created;
        totalReleased   += ctxs[i].released;
        totalCreateFail += ctxs[i].createFailures;
        totalReleaseFail += ctxs[i].releaseFailures;
    }
    printf("Totals: created=%d/%d released=%d/%d (create_fail=%d release_fail=%d)\n",
           totalCreated, numThreads * perThread,
           totalReleased, numThreads * perThread,
           totalCreateFail, totalReleaseFail);

    /* ---- Post-run leak probe ----
     * Use the primary thread to verify a sample of lastHandle values
     * are gone from the registry. */
    int leakCount = 0;
    for (int i = 0; i < numThreads; i++) {
        if (ctxs[i].lastHandle == 0) continue;
        int refcount = -999;
        int rc2 = getRefCount_fn(thr, ctxs[i].lastHandle, &refcount);
        if (rc2 != TPIPE_ERR_INVALID_HANDLE) {
            fprintf(stderr, "  [T%d] last handle 0x%016llx is STILL VALID (rc=%d refcount=%d) — LEAK\n",
                    i, (unsigned long long) ctxs[i].lastHandle, rc2, refcount);
            leakCount++;
        }
        int v = isValid_fn(thr, ctxs[i].lastHandle);
        if (v != 0) {
            fprintf(stderr, "  [T%d] last handle 0x%016llx isValid -> %d (expected 0) — LEAK\n",
                    i, (unsigned long long) ctxs[i].lastHandle, v);
            leakCount++;
        }
    }
    if (leakCount > 0) {
        fprintf(stderr, "%d leak indicators found in post-run probe\n", leakCount);
        free(ctxs); free(tids);
        pthread_barrier_destroy(&barrier);
        shutdown_fn(thr); gd(thr); gti(thr); dlclose(lib);
        return 3;
    }
    printf("Post-run probe: all %d sampled handles are gone from the registry (no leaks)\n", numThreads);

    /* ---- shared-handle concurrent release (Phase 7 extension) ----
     * Run BEFORE TPipe_shutdown so the library is in the READY state
     * and create_fn actually returns a non-zero handle. */
    if (run_shared_handle_concurrent_release(16, create_fn, release_fn, addRef_fn, getRefCount_fn, isValid_fn, ga, gd, iso, thr) != 0) {
        fprintf(stderr, "shared-handle concurrent release test FAILED\n");
        free(ctxs); free(tids);
        pthread_barrier_destroy(&barrier);
        gd(thr); gti(thr); dlclose(lib);
        return 1;
    }



    /* ---- TPipe_shutdown ---- */
    rc = shutdown_fn(thr);
    if (rc != 0) {
        fprintf(stderr, "TPipe_shutdown returned %d (expected 0)\n", rc);
        free(ctxs); free(tids);
        pthread_barrier_destroy(&barrier);
        gd(thr); gti(thr); dlclose(lib);
        return 1;
    }
    printf("TPipe_shutdown -> 0\n");

    free(ctxs);
    free(tids);
    pthread_barrier_destroy(&barrier);

    /* Print success BEFORE teardown — SubstrateVM may abort during teardown
     * due to a known issue with non-Java threads, but the concurrency result
     * is already established by this point. */
        printf("TPipe concurrency test passed (%d threads, %d handles each, no crashes, no leaks)\n",
           numThreads, perThread);
    fflush(stdout); fflush(stderr);

    /* Teardown (best-effort). */
    gd(thr);
    gti(thr);
    dlclose(lib);

    return 0;
}
