#include <dlfcn.h>
#include <stdio.h>
#include <stdint.h>
#include <stdlib.h>

typedef int (*fn_init)(void);
typedef int (*fn_shutdown)(void);
typedef int (*fn_get_state)(void);
typedef int (*fn_get_version)(int*, int*, int*);
typedef int (*fn_get_last_error)(char*, int);
typedef uint64_t (*fn_content_create)(const char*);
typedef int (*fn_handle_release)(uint64_t);

int main(int argc, char** argv) {
    if (argc < 2) {
        fprintf(stderr, "Usage: %s <path-to-libTPipe.so>\n", argv[0]);
        return 1;
    }
    void* lib = dlopen(argv[1], RTLD_NOW);
    if (!lib) {
        fprintf(stderr, "dlopen failed: %s\n", dlerror());
        return 1;
    }
    printf("Loaded %s\n", argv[1]);

    fn_init tpipe_init = (fn_init) dlsym(lib, "TPipe_init");
    fn_shutdown tpipe_shutdown = (fn_shutdown) dlsym(lib, "TPipe_shutdown");
    fn_get_state tpipe_get_state = (fn_get_state) dlsym(lib, "TPipe_getState");
    fn_get_version tpipe_get_version = (fn_get_version) dlsym(lib, "TPipe_getVersion");
    fn_get_last_error tpipe_get_last_error = (fn_get_last_error) dlsym(lib, "TPipe_getLastError");
    fn_content_create tpipe_content_create = (fn_content_create) dlsym(lib, "TPipe_ContentHandle_create");
    fn_handle_release tpipe_handle_release = (fn_handle_release) dlsym(lib, "TPipe_Handle_release");

    if (!tpipe_init || !tpipe_shutdown || !tpipe_get_state || !tpipe_get_version || !tpipe_content_create || !tpipe_handle_release) {
        fprintf(stderr, "dlsym failed for one or more required symbols\n");
        dlclose(lib);
        return 1;
    }

    int rc = tpipe_init();
    printf("TPipe_init -> %d\n", rc);
    if (rc != 0) {
        char err[256] = {0};
        tpipe_get_last_error(err, sizeof(err));
        fprintf(stderr, "Init failed: %s\n", err);
        dlclose(lib);
        return 1;
    }

    int state = tpipe_get_state();
    printf("TPipe_getState -> %d (expected 2=READY)\n", state);

    int major=0, minor=0, patch=0;
    rc = tpipe_get_version(&major, &minor, &patch);
    printf("TPipe_getVersion -> %d, %d.%d.%d\n", rc, major, minor, patch);

    uint64_t handle = tpipe_content_create("smoke test");
    printf("TPipe_ContentHandle_create -> %llu (expected non-zero)\n", (unsigned long long) handle);
    if (handle == 0) {
        fprintf(stderr, "Content handle creation failed\n");
        tpipe_shutdown();
        dlclose(lib);
        return 1;
    }

    rc = tpipe_handle_release(handle);
    printf("TPipe_Handle_release -> %d (expected 0)\n", rc);

    rc = tpipe_shutdown();
    printf("TPipe_shutdown -> %d\n", rc);

    dlclose(lib);
    printf("TPipe native smoke test passed\n");
    return 0;
}
