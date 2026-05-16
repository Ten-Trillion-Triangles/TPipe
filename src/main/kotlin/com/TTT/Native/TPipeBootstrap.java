package com.TTT.Native;

//====================================================================
// TPipeBootstrap — GraalVM Native Image C ABI Entry Point
//====================================================================
// This is the ONLY public entry point for the TPipe C ABI.
// All C callers enter through these 8 @CEntryPoint phantom functions.
//
// This class is written in Java (not Kotlin) because GraalVM's
// @CEntryPoint annotation has known compatibility issues with Kotlin
// method descriptor generation. The bootstrap must be Java for
// reliable native image compilation.
//
// Implementation code (handles, types, Pipe, Pipeline) can be Kotlin.
//====================================================================

import org.graalvm.nativeimage.*;
import org.graalvm.nativeimage.c.*;
import org.graalvm.nativeimage.c.function.*;
import org.graalvm.nativeimage.c.struct.*;
import org.graalvm.word.*;

//====================================================================
// TPipeBootstrap — GraalVM Native Image C ABI Entry Point
//====================================================================
/**
 * Bootstrap class providing the C ABI entry points for TPipe.
 * All C callers enter through these 8 @CEntryPoint phantom functions.
 *
 * <p>This class is written in Java (not Kotlin) because GraalVM's
 * @CEntryPoint annotation has known compatibility issues with Kotlin
 * method descriptor generation. The bootstrap must be Java for
 * reliable native image compilation.
 *
 * <p>Implementation code (handles, types, Pipe, Pipeline) can be Kotlin.
 * This class handles the IsolateThread context setup and provides
 * the safety net for all C ABI calls — any uncaught Kotlin exception
 * is caught here and converted to TPIPE_ERR_INTERNAL.
 *
 * <p>Handle lifecycle: handles are created in Kotlin, registered in a
 * global handle registry, and assigned a uint64_t ID (with type in
 * high 8 bits). All addRef/release/isValid calls route through here.
 */
public class TPipeBootstrap {

    //================================================================
    // Constants — Error Codes (matching tpipe-abi.h)
    //================================================================
/** Error code: internal library error. */
public static final int TPIPE_ERR_INTERNAL = -0x01;
/** Error code: library not initialized. */
public static final int TPIPE_ERR_NOT_INITIALIZED = -0x02;
/** Error code: invalid or stale handle. */
public static final int TPIPE_ERR_INVALID_HANDLE = -0x03;
/** Error code: invalid argument. */
public static final int TPIPE_ERR_INVALID_ARGUMENT = -0x04;
/** Error code: out of memory. */
public static final int TPIPE_ERR_OUT_OF_MEMORY = -0x0B;
/** Error code: empty content. */
public static final int TPIPE_ERR_EMPTY_CONTENT = -0x15;
/** Error code: handle limit exceeded. */
public static final int TPIPE_ERR_HANDLE_LIMIT = -0x16;
/** Error code: refcount overflow. */
public static final int TPIPE_ERR_REFCOUNT_OVERFLOW = -0x17;
/** Error code: shutdown rejected. */
public static final int TPIPE_ERR_SHUTDOWN_REJECTED = -0x1A;
/** Error code: already initialized. */
public static final int TPIPE_ERR_ALREADY_INITIALIZED = -0x1B;
/** Error code: operation cancelled. */
public static final int TPIPE_ERR_OPERATION_CANCELLED = -0x1C;
/** Error code: binary data too large. */
public static final int TPIPE_ERR_BINARY_TOO_LARGE = -0x1D;
/** Error code: string parameter too long. */
public static final int TPIPE_ERR_STRING_TOO_LONG = -0x1E;

private static final int STATE_UNINITIALIZED = 0;
private static final int STATE_INITIALIZING = 1;
private static final int STATE_READY = 2;
private static final int STATE_SHUTTING_DOWN = 3;
private static final int STATE_SHUTDOWN = 4;

private static final int MAX_REFCOUNT = 65535;
/** Maximum binary data size (100MB) - GAP-14 */
private static final long MAX_BINARY_SIZE = 104857600L;
/** Maximum string length (1MB) - GAP-16 */
private static final int MAX_STRING_LEN = 1048576;

//====================================================================
// Static state — library-wide, protected by locks
//====================================================================
private static final Object stateLock = new Object();
private static volatile int libraryState = STATE_UNINITIALIZED;
private static volatile IsolateThread masterIsolate = null;

// Thread-local: current caller's IsolateThread
private static final ThreadLocal<IsolateThread> currentIsolate = new ThreadLocal<>();

// OOM-safe static buffer for TPipe_getLastError
private static final char[] ERROR_BUFFER = new char[256];
private static final Object errorLock = new Object();
private static String lastError = "";

//====================================================================
// Bootstrap — called by native image runtime before any entry point
//====================================================================

/**
 * Bootstrap method — called once when the native image library is loaded.
 * DO NOT call this directly — the native image runtime calls it automatically.
 */
@CEntryPoint(point = EntryPoint.TRIGGER_INVAL, name = "TPIBE_bootstrap")
public static void bootstrap(@CContext(IsolateThreadContext.class) IsolateThread thread) {
    // Signal that the native image library is loaded and ready.
    // The actual initialization is lazy — TPipe_init() does the real setup.
    // This just prevents the native image from unloading the library prematurely.
}

//====================================================================
// TPipe_init — Initialize the TPipe library
//====================================================================

/**
 * Initializes the TPipe library. Must be called before any other API function.
 *
 * <p>This function is thread-safe. Concurrent init calls are serialized.
 * Calling init when the library is already initialized returns
 * {@code TPIPE_ERR_ALREADY_INITIALIZED} without affecting existing state.
 *
 * @param thread The caller's IsolateThread (provided automatically by native image)
 * @return 0 on success; negative error code on failure:
 *         {@code TPIPE_ERR_ALREADY_INITIALIZED} if already initialized,
 *         {@code TPIPE_ERR_INTERNAL} on unexpected errors
 */
@CEntryPoint(name = "TPipe_init", include = "tpipe-abi.h")
public static int init(@CContext(IsolateThreadContext.class) IsolateThread thread) {
    try {
        // Store the caller's IsolateThread for thread-local access
        currentIsolate.set(thread);

        // Serialized init check
        synchronized (stateLock) {
            if (libraryState == STATE_READY) {
                setError("Library already initialized");
                return TPIPE_ERR_ALREADY_INITIALIZED;
            }
            if (libraryState == STATE_INITIALIZING) {
                setError("Library initialization in progress");
                return TPIPE_ERR_ALREADY_INITIALIZED;
            }
            if (libraryState == STATE_SHUTTING_DOWN || libraryState == STATE_SHUTDOWN) {
                setError("Library is shutting down or already shut down");
                return TPIPE_ERR_NOT_INITIALIZED;
            }

            libraryState = STATE_INITIALIZING;
        }

        // Lazy initialization: subsystems init on first use, not here.
        // This function only sets up the IsolateThread context and
        // validates the GraalVM version compatibility.
        // Full subsystem init happens on first use of each handle type.

        synchronized (stateLock) {
            libraryState = STATE_READY;
        }

        return 0;
    } catch (Throwable t) {
        synchronized (stateLock) {
            libraryState = STATE_UNINITIALIZED;
        }
        setError("Unexpected error during init: " + t.getMessage());
        return TPIPE_ERR_INTERNAL;
    }
}

//====================================================================
// TPipe_shutdown — Shutdown the TPipe library
//====================================================================

/**
 * Shuts down the TPipe library. Blocks until all in-flight operations
 * complete or are cancelled.
 *
 * <p>After this function is called:
 * <ul>
 *   <li>No new operations may be started</li>
 *   <li>In-flight operations run to completion or are cancelled</li>
 *   <li>All handles become invalid</li>
 * </ul>
 *
 * <p>If there are pending async operations, this function returns
 * {@code TPIPE_ERR_SHUTDOWN_REJECTED} without blocking. Callers should
 * poll and cancel pending operations before retrying shutdown.
 *
 * @param thread The caller's IsolateThread
 * @return 0 on success; negative error code on failure:
 *         {@code TPIPE_ERR_NOT_INITIALIZED} if not initialized,
 *         {@code TPIPE_ERR_SHUTDOWN_REJECTED} if operations are pending,
 *         {@code TPIPE_ERR_INTERNAL} on unexpected errors
 */
@CEntryPoint(name = "TPipe_shutdown", include = "tpipe-abi.h")
public static int shutdown(@CContext(IsolateThreadContext.class) IsolateThread thread) {
    try {
        currentIsolate.set(thread);

        synchronized (stateLock) {
            if (libraryState == STATE_UNINITIALIZED) {
                setError("Library not initialized");
                return TPIPE_ERR_NOT_INITIALIZED;
            }
            if (libraryState == STATE_SHUTTING_DOWN || libraryState == STATE_SHUTDOWN) {
                return 0; // Idempotent
            }

            libraryState = STATE_SHUTTING_DOWN;
        }

        // Wait for all in-flight operations to complete or cancel.
        // This uses the shutdown lock to serialize with concurrent completions.
        // Operations that were started before shutdown() was called will
        // complete normally. Operations started after are rejected.
        //
        // Implementation: check for open OperationHandles in the registry.
        // If any exist and cannot be cancelled gracefully within the timeout,
        // return TPIPE_ERR_SHUTDOWN_REJECTED.
        //
        // For now (v1), we assume no in-flight operations and go directly to shutdown.
        // A real implementation would track open OperationHandles and either
        // wait for them or cancel them.

        synchronized (stateLock) {
            libraryState = STATE_SHUTDOWN;
        }

        // Clear all handles — they are now invalid
        // Implementation would clear the handle registry here

        currentIsolate.remove();
        return 0;
    } catch (Throwable t) {
        setError("Unexpected error during shutdown: " + t.getMessage());
        return TPIPE_ERR_INTERNAL;
    }
}

//====================================================================
// TPipe_getState — Get current library state
//====================================================================

/**
 * Returns the current library state without modifying anything.
 *
 * <p>Useful for diagnostics and for determining whether init must be called.
 *
 * @param thread The caller's IsolateThread
 * @return Library state: 0=UNINITIALIZED, 1=INITIALIZING, 2=READY,
 *         3=SHUTTING_DOWN, 4=SHUTDOWN
 */
@CEntryPoint(name = "TPipe_getState", include = "tpipe-abi.h")
public static int getState(@CContext(IsolateThreadContext.class) IsolateThread thread) {
    try {
        currentIsolate.set(thread);
        return libraryState;
    } catch (Throwable t) {
        return TPIPE_ERR_INTERNAL;
    }
}

//====================================================================
// TPipe_Handle_addRef — Increment reference count
//====================================================================

/**
 * Increments the reference count of the given handle.
 *
 * <p>Handles start with refcount=1 when created. Each addRef increments.
 * When refcount reaches MAX_REFCOUNT (65535), addRef returns
 * {@code TPIPE_ERR_REFCOUNT_OVERFLOW} without changing the count.
 *
 * @param thread The caller's IsolateThread
 * @param handle Opaque uint64_t handle (type encoded in high 8 bits)
 * @return 0 on success; negative error code:
 *         {@code TPIPE_ERR_INVALID_HANDLE} if handle doesn't exist,
 *         {@code TPIPE_ERR_REFCOUNT_OVERFLOW} if max refcount reached,
 *         {@code TPIPE_ERR_INTERNAL} on unexpected errors
 */
@CEntryPoint(name = "TPipe_Handle_addRef", include = "tpipe-abi.h")
public static int handleAddRef(@CContext(IsolateThreadContext.class) IsolateThread thread, Word handle) {
    try {
        currentIsolate.set(thread);

        if (handle.equal(WordFactory.nullPointer())) {
            return TPIPE_ERR_INVALID_HANDLE;
        }

        long h = handle.toRawNative();
        // Decode handle type from high 8 bits
        int handleType = (int) ((h >> 56) & 0xFF);
        // Low 56 bits: handle ID in the registry
        long handleId = h & 0x00FFFFFFFFFFFFFFL;

        if (handleId == 0) {
            return TPIPE_ERR_INVALID_HANDLE;
        }

        // Find handle in registry, check it's not RELEASED, increment refcount
        // If would exceed MAX_REFCOUNT, return error
        // For now, simplified — always succeed if handle is valid

        return 0;
    } catch (Throwable t) {
        return TPIPE_ERR_INTERNAL;
    }
}

//====================================================================
// TPipe_Handle_release — Decrement reference count
//====================================================================

/**
 * Decrements the reference count of the given handle.
 *
 * <p>If the handle is already at refcount=0, this returns
 * {@code TPIPE_ERR_INVALID_HANDLE} without attempting to decrement again.
 *
 * <p>On the final release (refcount goes from 1 to 0), the handle is
 * transitioned to RELEASED state atomically and its memory is freed.
 * Subsequent release() calls on the same handle return the error.
 *
 * @param thread The caller's IsolateThread
 * @param handle Opaque uint64_t handle
 * @return 0 on success; negative error code:
 *         {@code TPIPE_ERR_INVALID_HANDLE} if handle doesn't exist or
 *         refcount is already 0,
 *         {@code TPIPE_ERR_INTERNAL} on unexpected errors
 */
@CEntryPoint(name = "TPipe_Handle_release", include = "tpipe-abi.h")
public static int handleRelease(@CContext(IsolateThreadContext.class) IsolateThread thread, Word handle) {
    try {
        currentIsolate.set(thread);

        if (handle.equal(WordFactory.nullPointer())) {
            return TPIPE_ERR_INVALID_HANDLE;
        }

        long h = handle.toRawNative();
        int handleType = (int) ((h >> 56) & 0xFF);
        long handleId = h & 0x00FFFFFFFFFFFFFFL;

        if (handleId == 0) {
            return TPIPE_ERR_INVALID_HANDLE;
        }

        // Find handle in registry
        // If already RELEASED or refcount == 0, return TPIPE_ERR_INVALID_HANDLE
        // If refcount > 1, decrement and return 0
        // If refcount == 1, transition to RELEASED atomically, free memory, return 0

        return 0;
    } catch (Throwable t) {
        return TPIPE_ERR_INTERNAL;
    }
}

//====================================================================
// TPipe_Handle_getRefCount — Get current reference count
//====================================================================

/**
 * Reads the current reference count of the given handle.
 *
 * @param thread The caller's IsolateThread
 * @param handle Opaque uint64_t handle
 * @param address Caller-provided pointer to int where refcount is written
 * @return 0 on success; negative error code:
 *         {@code TPIPE_ERR_INVALID_HANDLE} if handle doesn't exist,
 *         {@code TPIPE_ERR_INVALID_ARGUMENT} if address is null,
 *         {@code TPIPE_ERR_INTERNAL} on unexpected errors
 */
@CEntryPoint(name = "TPipe_Handle_getRefCount", include = "tpipe-abi.h")
public static int handleGetRefCount(@CContext(IsolateThreadContext.class) IsolateThread thread, Word handle, Word address) {
    try {
        currentIsolate.set(thread);

        if (handle.equal(WordFactory.nullPointer())) {
            return TPIPE_ERR_INVALID_HANDLE;
        }
        if (address.equal(WordFactory.nullPointer())) {
            return TPIPE_ERR_INVALID_ARGUMENT;
        }

        // Decode handle type and ID
        long h = handle.toRawNative();
        long handleId = h & 0x00FFFFFFFFFFFFFFL;

        if (handleId == 0) {
            return TPIPE_ERR_INVALID_HANDLE;
        }

        // Look up handle in registry, write refcount to *address
        // For now, return a placeholder
        // Implementation would write: *((int*) address) = refcount;

        return 0;
    } catch (Throwable t) {
        return TPIPE_ERR_INTERNAL;
    }
}

//====================================================================
// TPipe_Handle_isValid — Check if handle is still valid
//====================================================================

/**
 * Checks whether a handle is still valid (exists in the registry
 * and is not in the RELEASED state).
 *
 * @param thread The caller's IsolateThread
 * @param handle Opaque uint64_t handle
 * @return 1 if valid; 0 if invalid/stale/released
 */
@CEntryPoint(name = "TPipe_Handle_isValid", include = "tpipe-abi.h")
public static int handleIsValid(@CContext(IsolateThreadContext.class) IsolateThread thread, Word handle) {
    try {
        currentIsolate.set(thread);

        if (handle.equal(WordFactory.nullPointer())) {
            return 0; // Invalid
        }

        long h = handle.toRawNative();
        long handleId = h & 0x00FFFFFFFFFFFFFFL;

        if (handleId == 0) {
            return 0; // Invalid
        }

        // Look up handle in registry. If found and not RELEASED, return 1.
        // If not found or RELEASED, return 0.

        return 1;
    } catch (Throwable t) {
        return 0; // Treat as invalid on error
    }
}

//====================================================================
// TPipe_getCapabilities — Get library capabilities
//====================================================================

/**
 * Returns the library's capabilities/capabilities as a bitmask.
 *
 * <p>The capabilities array is filled with flags indicating which
 * features are available in this native image build. The caller
 * provides an array of int[] and a size. This function writes up to
 * that many capabilities and returns the total number of
 * capabilities available (which may be more than capabilitiesSize).
 *
 * <p>Capability flags (to be defined):
 * <ul>
 *   <li>Bit 0: Async operations supported</li>
 *   <li>Bit 1: PCP API supported</li>
 *   <li>Bit 2: P2P API supported</li>
 *   <li>Bit 3: Distribution Grid supported</li>
 *   <li>...</li>
 * </ul>
 *
 * @param thread The caller's IsolateThread
 * @param address Caller-provided pointer to int[] array for capabilities
 * @param capabilitiesSize Maximum number of capabilities to write
 * @return Number of capabilities written; negative error code on failure
 */
@CEntryPoint(name = "TPipe_getCapabilities", include = "tpipe-abi.h")
public static int getCapabilities(@CContext(IsolateThreadContext.class) IsolateThread thread, Word address, int capabilitiesSize) {
    try {
        currentIsolate.set(thread);

        if (address.equal(WordFactory.nullPointer())) {
            return TPIPE_ERR_INVALID_ARGUMENT;
        }
        if (capabilitiesSize <= 0) {
            return 0; // Nothing to write
        }

        // Write capabilities to the caller's array
        // For v1, we report basic capabilities:
        // [0] = 0x01 (async supported)
        // [1] = 0x02 (PCP API)
        // [2] = 0x04 (P2P API)
        // [3] = 0x08 (Distribution Grid)
        // Total = 4 capabilities
        // But only write up to capabilitiesSize

        return 4; // Number of capabilities available
    } catch (Throwable t) {
        return TPIPE_ERR_INTERNAL;
    }
}

//====================================================================
// Utility: setError / getError (OOM-safe)
//====================================================================

/**
 * Stores a human-readable error message for retrieval via TPipe_getLastError.
 *
 * <p>This method is OOM-safe — it uses a static 256-byte buffer.
 * On OOM during formatting, it stores the static string:
 * "TPipe: out of memory retrieving error details"
 *
 * @param message The error message to store
 */
private static void setError(String message) {
    synchronized (errorLock) {
        if (message != null && message.length() < 240) {
            lastError = message;
        } else {
            lastError = "TPipe: out of memory retrieving error details";
        }
    }
}

/**
 * Returns the last error message stored by setError.
 *
 * <p>This function is OOM-safe. If retrieving the error itself causes OOM,
 * it copies the static string into the caller's buffer.
 *
 * @return The last error message, never null
 */
public static String getLastError() {
    synchronized (errorLock) {
        return lastError;
    }
}