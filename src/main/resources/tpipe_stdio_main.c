/* Example C program that uses TPipe.so to act as a P2P stdio agent.
 * Compile and run separately — NOT part of the Gradle build. This file
 * lives in src/main/resources/ as documentation/reference.
 *
 *   gcc -I build/native/nativeCompile tpipe_stdio_main.c -o tpipe_stdio_main -ldl
 *   echo '{"version":1,"sender":"test"}' | ./tpipe_stdio_main
 */
#include <dlfcn.h>
#include <stdio.h>
#include <stdint.h>
#include "graal_isolate.h"
#include "tpipe-abi.h"

int main(int argc, char** argv) {
    /* Default library path: relative to cwd. Override with argv[2] if set. */
    const char* lib_path = (argc > 2) ? argv[2]
        : "./build/native/nativeCompile/TPipe.so";

    void* lib = dlopen(lib_path, RTLD_NOW);
    if (!lib) {
        fprintf(stderr, "dlopen(%s): %s\n", lib_path, dlerror());
        return 2;
    }

    /* Resolve GraalVM isolate lifecycle entry points. */
    int (*gc)(graal_create_isolate_params_t*, graal_isolate_t**, graal_isolatethread_t**) =
        (int (*)(graal_create_isolate_params_t*, graal_isolate_t**, graal_isolatethread_t**))
        dlsym(lib, "graal_create_isolate");
    int (*gd)(graal_isolatethread_t*) =
        (int (*)(graal_isolatethread_t*)) dlsym(lib, "graal_detach_thread");

    if (!gc) {
        fprintf(stderr, "graal_create_isolate not found in %s: %s\n", lib_path, dlerror());
        dlclose(lib);
        return 2;
    }

    graal_isolate_t* iso = NULL;
    graal_isolatethread_t* thr = NULL;
    if (gc(NULL, &iso, &thr) != 0) {
        fprintf(stderr, "graal_create_isolate failed\n");
        dlclose(lib);
        return 2;
    }

    /* Resolve the TPipe_main C entry point. */
    int (*main_fn)(graal_isolatethread_t*, const char*) =
        (int (*)(graal_isolatethread_t*, const char*)) dlsym(lib, "TPipe_main");
    if (!main_fn) {
        fprintf(stderr, "TPipe_main not found in %s: %s\n", lib_path, dlerror());
        if (gd) gd(thr);
        dlclose(lib);
        return 2;
    }

    /* Mode defaults to "stdio-once" — pass another mode as argv[1]. */
    const char* mode = (argc > 1) ? argv[1] : "stdio-once";
    int rc = main_fn(thr, mode);
    fprintf(stderr, "TPipe_main returned %d\n", rc);

    if (gd) gd(thr);
    dlclose(lib);
    return rc;
}
