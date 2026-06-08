package com.TTT.Native;

import org.graalvm.nativeimage.IsolateThread;
import org.graalvm.nativeimage.c.function.CEntryPoint;
import org.graalvm.nativeimage.c.type.CCharPointer;

import java.util.concurrent.locks.ReentrantLock;

/**
 * TPipeBootstrap — GraalVM Native Image C ABI Entry Point
 *
 * <p>This is the ONLY public entry point for the TPipe C ABI. All C callers
 * enter through these {@code @CEntryPoint}-annotated methods. The native
 * image build toolchain generates C-callable wrapper functions for each
 * declared method.
 *
 * <h2>Calling convention (Option A)</h2>
 * Every method takes a {@code graal_isolatethread_t*} as the first parameter
 * (mapped to Java's {@link IsolateThread}). The C ABI signatures are
 * declared in {@code src/main/resources/tpipe-abi.h}.
 *
 * <h2>Parameter mapping</h2>
 * <ul>
 *   <li>C {@code graal_isolatethread_t*} → Java {@link IsolateThread} (first param)</li>
 *   <li>C {@code TPipe_Handle} (uint64_t) → Java {@code long}</li>
 *   <li>C {@code int*} (output pointer) → Java {@code long} (raw pointer address)</li>
 *   <li>C {@code const char*} (string input) → Java {@code String} via
 *       {@link #readCString(long, int)}</li>
 * </ul>
 *
 * <h2>Bridge architecture</h2>
 * All business logic lives in {@link NativeBridge} (Kotlin). This class is a
 * thin shim that handles:
 * <ol>
 *   <li>C string ↔ Java String conversion via {@link sun.misc.Unsafe}</li>
 *   <li>Output pointer writes via {@link sun.misc.Unsafe}</li>
 *   <li>State check + delegation to {@link NativeBridge}</li>
 * </ol>
 *
 * <h2>ReentrantLock for synchronization</h2>
 * Native-image SubstrateVM rejects Java object monitors ({@code synchronized})
 * on threads that are not registered as Java threads. This class uses
 * {@link ReentrantLock} for all locks.
 */
public class TPipeBootstrap {

    //====================================================================
    // Error codes — match TPIPE_ERR_* defines in tpipe-abi.h
    //====================================================================

    public static final int TPIPE_OK = 0;
    public static final int TPIPE_ERR_INTERNAL = -0x01;
    public static final int TPIPE_ERR_NOT_INITIALIZED = -0x02;
    public static final int TPIPE_ERR_INVALID_HANDLE = -0x03;
    public static final int TPIPE_ERR_INVALID_ARGUMENT = -0x04;
    public static final int TPIPE_ERR_NULL_POINTER = -0x05;
    public static final int TPIPE_ERR_BUFFER_TOO_SMALL = -0x06;
    public static final int TPIPE_ERR_OUT_OF_MEMORY = -0x0B;
    public static final int TPIPE_ERR_NOT_IMPLEMENTED = -0x10;
    public static final int TPIPE_ERR_INVALID_STATE = -0x12;
    public static final int TPIPE_ERR_TYPE_MISMATCH = -0x13;
    public static final int TPIPE_ERR_OPERATION_TIMEOUT = -0x15;
    public static final int TPIPE_ERR_HANDLE_LIMIT = -0x16;
    public static final int TPIPE_ERR_REFCOUNT_OVERFLOW = -0x17;
    public static final int TPIPE_ERR_SHUTDOWN_REJECTED = -0x1A;
    public static final int TPIPE_ERR_ALREADY_INITIALIZED = -0x1B;
    public static final int TPIPE_ERR_OPERATION_CANCELLED = -0x1C;
    public static final int TPIPE_ERR_BINARY_TOO_LARGE = -0x1D;
    public static final int TPIPE_ERR_STRING_TOO_LONG = -0x1E;
    public static final int TPIPE_ERR_EMPTY_CONTENT = -0x15;

    //====================================================================
    // Library constants
    //====================================================================

    public static final String TPIPE_VERSION = "1.0.0-native-c-abi";
    public static final int CAPABILITY_HANDLE_REGISTRY = 1 << 0;
    public static final int CAPABILITY_CONTENT = 1 << 1;
    public static final int CAPABILITY_BINARY = 1 << 2;
    public static final int CAPABILITY_PIPE = 1 << 3;
    public static final int CAPABILITY_PIPELINE = 1 << 4;
    public static final int CAPABILITY_CONTEXT = 1 << 5;
    public static final int CAPABILITY_PCP = 1 << 6;
    public static final int CAPABILITY_P2P = 1 << 7;
    public static final int CAPABILITY_LIST_MAP = 1 << 8;
    public static final int CAPABILITY_ASYNC = 1 << 9;
    public static final int CAPABILITY_LOREBOOK = 1 << 10;
    public static final int CAPABILITY_CONVERSE_HISTORY = 1 << 11;
    public static final int CAPABILITY_PIPE_SETTINGS = 1 << 12;
    public static final int TPIPE_CAPABILITIES =
        CAPABILITY_HANDLE_REGISTRY | CAPABILITY_CONTENT | CAPABILITY_BINARY |
        CAPABILITY_PIPE | CAPABILITY_PIPELINE | CAPABILITY_CONTEXT | CAPABILITY_PCP |
        CAPABILITY_P2P | CAPABILITY_LIST_MAP | CAPABILITY_ASYNC |
        CAPABILITY_LOREBOOK | CAPABILITY_CONVERSE_HISTORY | CAPABILITY_PIPE_SETTINGS;

    //====================================================================
    // Unsafe for C string / output pointer I/O
    //====================================================================

    private static final sun.misc.Unsafe UNSAFE;
    static {
        try {
            java.lang.reflect.Field f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            UNSAFE = (sun.misc.Unsafe) f.get(null);
        } catch (Exception e) {
            throw new RuntimeException("Unable to access sun.misc.Unsafe", e);
        }
    }

    /**
     * Holder for {@code sun.misc.Unsafe} constants that are not part of
     * the public API but are stable across supported JVM versions.
     *
     * <p>SubstrateVM exposes the same constants; native-image compilation
     * preserves the values used here.
     */
    private static final class UnsafeHelpers {
        /**
         * Offset in bytes of the first element of a Java {@code byte[]}
         * array. Mirrors {@code sun.misc.Unsafe.ARRAY_BYTE_BASE_OFFSET}.
         */
        static final int ARRAY_BYTE_BASE_OFFSET = UNSAFE.arrayBaseOffset(byte[].class);
    }

    //====================================================================
    // C string / output pointer helpers
    //====================================================================

    private static String readCString(long addr) {
        if (addr == 0L) return null;
        int maxBytes = GapVerification.MAX_STRING_LEN;
        int capacity = 4096;
        byte[] buf = new byte[capacity];
        int total = 0;
        while (total < maxBytes) {
            if (total == capacity) {
                if (capacity >= maxBytes) {
                    throw new RuntimeException("TPIPE_ERR_STRING_TOO_LONG: input exceeds " + maxBytes + " bytes without null terminator");
                }
                int newCapacity = Math.min(capacity * 2, maxBytes);
                byte[] newBuf = new byte[newCapacity];
                System.arraycopy(buf, 0, newBuf, 0, total);
                buf = newBuf;
                capacity = newCapacity;
            }
            byte b = UNSAFE.getByte(addr + total);
            if (b == 0) {
                return new String(buf, 0, total, java.nio.charset.StandardCharsets.UTF_8);
            }
            buf[total++] = b;
        }
        throw new RuntimeException("TPIPE_ERR_STRING_TOO_LONG: input exceeds " + maxBytes + " bytes without null terminator");
    }

    private static String readCString(long addr, int knownLength) {
        if (addr == 0L) return null;
        if (knownLength <= 0) return "";
        if (knownLength > GapVerification.MAX_STRING_LEN) {
            knownLength = GapVerification.MAX_STRING_LEN;
        }
        byte[] buf = new byte[knownLength];
        for (int i = 0; i < knownLength; i++) {
            buf[i] = UNSAFE.getByte(addr + i);
        }
        return new String(buf, java.nio.charset.StandardCharsets.UTF_8);
    }

    /**
     * Phase 3 — read a C string from a caller-provided buffer using
     * CCharPointer instead of `sun.misc.Unsafe`. This is the
     * GraalVM-recommended way; the {@code sun.misc.Unsafe::getByte}
     * deprecation warning no longer fires for entry points that
     * route through this helper.
     *
     * Reads up to {@code bufferSize} bytes (or up to the first NUL
     * terminator, whichever comes first) and returns the result as a
     * Java String. Returns null if the buffer is null.
     */
    static String readCStringViaWord(CCharPointer addr, int bufferSize) {
        if (addr.isNull()) return null;
        if (bufferSize <= 0) return "";
        int maxBytes = Math.min(bufferSize, GapVerification.MAX_STRING_LEN);
        byte[] buf = new byte[maxBytes];
        for (int i = 0; i < maxBytes; i++) {
            byte b = addr.read(i);
            if (b == 0) {
                return new String(buf, 0, i, java.nio.charset.StandardCharsets.UTF_8);
            }
            buf[i] = b;
        }
        return new String(buf, 0, maxBytes, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static int writeCString(long addr, int bufferSize, String s) {
        if (addr == 0L) return TPIPE_ERR_NULL_POINTER;
        if (bufferSize <= 0) return TPIPE_ERR_INVALID_ARGUMENT;
        if (s == null) s = "";
        byte[] bytes = s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        int writeLen = Math.min(bytes.length, bufferSize - 1);
        for (int i = 0; i < writeLen; i++) {
            UNSAFE.putByte(addr + i, bytes[i]);
        }
        UNSAFE.putByte(addr + writeLen, (byte) 0);
        return writeLen;
    }
    /**
     * Phase 3 — write a C string to a caller-provided buffer, using
     * the GraalVM Word-based CCharPointer instead of `sun.misc.Unsafe`.
     * This is the public method that converted @CEntryPoint methods
     * call. The long-based writeCString remains for the
     * not-yet-converted entry points.
     *
     * Returns {@link #TPIPE_OK} on success, {@link #TPIPE_ERR_NULL_POINTER}
     * if the buffer is null, or {@link #TPIPE_ERR_INVALID_ARGUMENT} if
     * the bufferSize is <= 0.
     */
    static int writeCStringViaWord(CCharPointer addr, int bufferSize, String s) {
        if (addr.isNull()) return TPIPE_ERR_NULL_POINTER;
        if (bufferSize <= 0) return TPIPE_ERR_INVALID_ARGUMENT;
        if (s == null) s = "";
        byte[] bytes = s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        int writeLen = Math.min(bytes.length, bufferSize - 1);
        for (int i = 0; i < writeLen; i++) {
            addr.write(i, bytes[i]);
        }
        addr.write(writeLen, (byte) 0);
        return writeLen;
    }


    /**
     * Write a 4-byte int to a caller-provided buffer. Validates the
     * buffer is at least 4 bytes long when {@code bufferSize > 0}.
     * Pass {@code bufferSize = 0} when the C ABI does not provide a
     * size to the caller (legacy entry points that take only a
     * pointer) — the bounds check is then skipped.
     *
     * Returns {@link #TPIPE_OK} on success, {@link #TPIPE_ERR_NULL_POINTER}
     * if the address is null, or {@link #TPIPE_ERR_BUFFER_TOO_SMALL} if
     * the buffer is too small for the type.
     */
    static int writeInt(long addr, int bufferSize, int value) {
        if (addr == 0L) return TPIPE_ERR_NULL_POINTER;
        if (bufferSize > 0 && bufferSize < 4) return TPIPE_ERR_BUFFER_TOO_SMALL;
        UNSAFE.putInt(addr, value);
        return TPIPE_OK;
    }

    /**
     * Write a 4-byte float to a caller-provided buffer. See
     * {@link #writeInt} for the {@code bufferSize} contract.
     */
    static int writeFloat(long addr, int bufferSize, float value) {
        if (addr == 0L) return TPIPE_ERR_NULL_POINTER;
        if (bufferSize > 0 && bufferSize < 4) return TPIPE_ERR_BUFFER_TOO_SMALL;
        UNSAFE.putFloat(addr, value);
        return TPIPE_OK;
    }

    /**
     * Write an 8-byte pointer/long to a caller-provided buffer. See
     * {@link #writeInt} for the {@code bufferSize} contract.
     */
    static int writePtr(long addr, int bufferSize, long value) {
        if (addr == 0L) return TPIPE_ERR_NULL_POINTER;
        if (bufferSize > 0 && bufferSize < 8) return TPIPE_ERR_BUFFER_TOO_SMALL;
        UNSAFE.putLong(addr, value);
        return TPIPE_OK;
    }

    //====================================================================
    // TPipe_Handle typedef sanity check (Phase 2)
    //====================================================================
    //
    // The C ABI exposes handles as opaque uint64_t. The high 8 bits are
    // the handle type discriminator; the low 56 bits are the registry id.
    // These helpers let any @CEntryPoint (or test) verify a handle is
    // well-formed before dereferencing it. They are package-private so
    // the test can call them directly; they are NOT @CEntryPoint methods
    // (no C ABI symbol, no audit impact).

    /**
     * True iff {@code type} is a known handle type (one of the
     * 1..20 {@code HandleTypes} constants). Type 0 (BASE) is reserved
     * and types 21+ are not defined.
     */
    static boolean isValidHandleType(int type) {
        return type >= 1 && type <= 20;
    }

    /**
     * Decode the type byte from a handle (high 8 bits). Returns -1 if
     * the handle is 0 or the type byte is not in the valid 1..20 range.
     */
    static int decodeHandleType(long handle) {
        if (handle == 0L) return -1;
        int type = (int) ((handle >>> 56) & 0xFFL);
        if (type < 1 || type > 20) return -1;
        return type;
    }

    /**
     * Decode the registry id from a handle (low 56 bits). Returns -1
     * if the handle is 0 or the type byte is not in the valid 1..20
     * range (a malformed handle cannot be trusted to yield a valid id).
     */
    static int decodeHandleId(long handle) {
        if (handle == 0L) return -1;
        int type = (int) ((handle >>> 56) & 0xFFL);
        if (type < 1 || type > 20) return -1;
        return (int) (handle & 0x00FFFFFFFFFFFFFFL);
    }

    //====================================================================
    // State check helper
    //====================================================================

    private static int requireReady() {
        return NativeBridge.isReady() ? TPIPE_OK : TPIPE_ERR_NOT_INITIALIZED;
    }

    //====================================================================
    // Library lifecycle
    //====================================================================

    @CEntryPoint(name = "TPipe_init")
    public static int init(IsolateThread thread) {
        return NativeBridge.init();
    }

    @CEntryPoint(name = "TPipe_shutdown")
    public static int shutdown(IsolateThread thread) {
        return NativeBridge.shutdown();
    }

    @CEntryPoint(name = "TPipe_getState")
    public static int getState(IsolateThread thread) {
        return NativeBridge.getState();
    }

    @CEntryPoint(name = "TPipe_isInitialized")
    public static int isInitialized(IsolateThread thread) {
        return NativeBridge.isReady() ? 1 : 0;
    }

    @CEntryPoint(name = "TPipe_getLastError")
    public static int getLastError(IsolateThread thread, CCharPointer buffer, int bufferSize) {
        String msg = NativeBridge.getLastError();
        return writeCStringViaWord(buffer, bufferSize, msg == null ? "" : msg);
    }

    @CEntryPoint(name = "TPipe_getVersion")
    public static int getVersion(IsolateThread thread, CCharPointer buffer, int bufferSize) {
        /* Phase 3: now uses CCharPointer + writeCStringViaWord. The
         * `sun.misc.Unsafe::getByte` deprecation warning no longer
         * fires for this entry point. The C ABI signature is unchanged
         * (both `long` and `CCharPointer` are pointer-sized). */
        return writeCStringViaWord(buffer, bufferSize, TPIPE_VERSION);
    }

    @CEntryPoint(name = "TPipe_getCapabilities")
    public static int getCapabilities(IsolateThread thread, long capabilities, int capabilitiesSize) {
        if (capabilities == 0L || capabilitiesSize <= 0) return TPIPE_ERR_INVALID_ARGUMENT;
        UNSAFE.putInt(capabilities, TPIPE_CAPABILITIES);
        return TPIPE_OK;
    }

    //====================================================================
    // Handle primitives
    //====================================================================

    @CEntryPoint(name = "TPipe_Handle_addRef")
    public static int handleAddRef(IsolateThread thread, long handle) {
        int rc = requireReady(); if (rc != TPIPE_OK) return rc;
        return HandleRegistry.INSTANCE.addRef(handle);
    }

    @CEntryPoint(name = "TPipe_Handle_release")
    public static int handleRelease(IsolateThread thread, long handle) {
        int rc = requireReady(); if (rc != TPIPE_OK) return rc;
        return HandleRegistry.INSTANCE.release(handle);
    }

    @CEntryPoint(name = "TPipe_Handle_getRefCount")
    public static int handleGetRefCount(IsolateThread thread, long handle, long refCount) {
        int rc = requireReady(); if (rc != TPIPE_OK) return rc;
        int c = HandleRegistry.INSTANCE.getRefCount(handle);
        if (c < 0) return c;
        return writeInt(refCount, 0, c);
    }

    @CEntryPoint(name = "TPipe_Handle_isValid")
    public static int handleIsValid(IsolateThread thread, long handle) {
        return HandleRegistry.INSTANCE.isValid(handle) ? 1 : 0;
    }

    @CEntryPoint(name = "TPipe_Result_free")
    public static int resultFree(IsolateThread thread, long operationHandle) {
        int rc = requireReady(); if (rc != TPIPE_OK) return rc;
        if (HandleRegistry.INSTANCE.getType(operationHandle) != HandleTypes.OPERATION) {
            return TPIPE_ERR_INVALID_HANDLE;
        }
        return HandleRegistry.INSTANCE.release(operationHandle);
    }

    //====================================================================
    // Content API
    //====================================================================

    @CEntryPoint(name = "TPipe_Content_create")
    public static long contentCreate(IsolateThread thread, CCharPointer text) {
        int rc = requireReady(); if (rc != TPIPE_OK) return 0L;
        try {
            return NativeBridge.contentCreate(readCStringViaWord(text, GapVerification.MAX_STRING_LEN));
        } catch (Exception e) {
            NativeBridge.setLastError(e.getMessage());
            return 0L;
        }
    }

    @CEntryPoint(name = "TPipe_Content_createWithText")
    public static long contentCreateWithText(IsolateThread thread, CCharPointer text, int length) {
        int rc = requireReady(); if (rc != TPIPE_OK) return 0L;
        try {
            return NativeBridge.contentCreate(readCStringViaWord(text, length));
        } catch (Exception e) {
            NativeBridge.setLastError(e.getMessage());
            return 0L;
        }
    }

    @CEntryPoint(name = "TPipe_Content_release")
    public static int contentRelease(IsolateThread thread, long contentHandle) {
        int rc = requireReady(); if (rc != TPIPE_OK) return rc;
        if (HandleRegistry.INSTANCE.getType(contentHandle) != HandleTypes.CONTENT) {
            return TPIPE_ERR_INVALID_HANDLE;
        }
        return HandleRegistry.INSTANCE.release(contentHandle);
    }

    @CEntryPoint(name = "TPipe_Content_clone")
    public static long contentClone(IsolateThread thread, long sourceHandle) {
        int rc = requireReady(); if (rc != TPIPE_OK) return 0L;
        long h = NativeBridge.contentClone(sourceHandle);
        return h < 0 ? 0L : h;
    }

    @CEntryPoint(name = "TPipe_Content_getText")
    public static int contentGetText(IsolateThread thread, long contentHandle, CCharPointer buffer, int bufferSize) {
        int rc = requireReady(); if (rc != TPIPE_OK) return rc;
        String s = NativeBridge.contentGetText(contentHandle);
        if (s == null) return TPIPE_ERR_INVALID_HANDLE;
        return writeCStringViaWord(buffer, bufferSize, s);
    }

    @CEntryPoint(name = "TPipe_Content_setText")
    public static int contentSetText(IsolateThread thread, long contentHandle, CCharPointer text) {
        int rc = requireReady(); if (rc != TPIPE_OK) return rc;
        NativeBridge.contentSetText(contentHandle, readCStringViaWord(text, GapVerification.MAX_STRING_LEN));
        return TPIPE_OK;
    }

    @CEntryPoint(name = "TPipe_Content_getContext")
    public static int contentGetContext(IsolateThread thread, long contentHandle, long buffer, int bufferSize) {
        int rc = requireReady(); if (rc != TPIPE_OK) return rc;
        String s = NativeBridge.contentGetContext(contentHandle);
        if (s == null) return TPIPE_ERR_INVALID_HANDLE;
        return writeCString(buffer, bufferSize, s);
    }

    @CEntryPoint(name = "TPipe_Content_setContext")
    public static int contentSetContext(IsolateThread thread, long contentHandle, long context) {
        int rc = requireReady(); if (rc != TPIPE_OK) return rc;
        NativeBridge.contentSetContext(contentHandle, readCString(context));
        return TPIPE_OK;
    }

    @CEntryPoint(name = "TPipe_Content_getMiniBank")
    public static int contentGetMiniBank(IsolateThread thread, long contentHandle, long buffer, int bufferSize) {
        int rc = requireReady(); if (rc != TPIPE_OK) return rc;
        String s = NativeBridge.contentGetMiniBank(contentHandle);
        if (s == null) return TPIPE_ERR_INVALID_HANDLE;
        return writeCString(buffer, bufferSize, s);
    }

    @CEntryPoint(name = "TPipe_Content_setMiniBank")
    public static int contentSetMiniBank(IsolateThread thread, long contentHandle, long miniBank) {
        int rc = requireReady(); if (rc != TPIPE_OK) return rc;
        NativeBridge.contentSetMiniBank(contentHandle, readCString(miniBank));
        return TPIPE_OK;
    }

    @CEntryPoint(name = "TPipe_Content_addBinary")
    public static int contentAddBinary(IsolateThread thread, long contentHandle, int variant, long data, int dataLength, long mimeType, long filename) {
        int rc = requireReady(); if (rc != TPIPE_OK) return rc;
        if (dataLength < 0) return TPIPE_ERR_INVALID_ARGUMENT;
        byte[] bytes = new byte[dataLength];
        for (int i = 0; i < dataLength; i++) bytes[i] = UNSAFE.getByte(data + i);
        return NativeBridge.contentAddBinary(contentHandle, variant, bytes, readCString(mimeType), readCString(filename));
    }

    @CEntryPoint(name = "TPipe_Content_getBinary")
    public static int contentGetBinary(IsolateThread thread, long contentHandle, int index, long buffer, int bufferSize) {
        int rc = requireReady(); if (rc != TPIPE_OK) return rc;
        String json = NativeBridge.contentGetBinaryJson(contentHandle, index);
        if (json == null) return TPIPE_ERR_INVALID_ARGUMENT;
        return writeCString(buffer, bufferSize, json);
    }

    @CEntryPoint(name = "TPipe_Content_getBinaries")
    public static int contentGetBinaries(IsolateThread thread, long contentHandle, long buffer, int bufferSize) {
        int rc = requireReady(); if (rc != TPIPE_OK) return rc;
        String json = NativeBridge.contentGetBinariesJson(contentHandle);
        if (json == null) return TPIPE_ERR_INVALID_HANDLE;
        return writeCString(buffer, bufferSize, json);
    }

    @CEntryPoint(name = "TPipe_Content_clearBinary")
    public static int contentClearBinary(IsolateThread thread, long contentHandle) {
        int rc = requireReady(); if (rc != TPIPE_OK) return rc;
        NativeBridge.contentClearBinary(contentHandle);
        return TPIPE_OK;
    }

    @CEntryPoint(name = "TPipe_Content_setJumpTo")
    public static int contentSetJumpTo(IsolateThread thread, long contentHandle, long jumpTo) {
        int rc = requireReady(); if (rc != TPIPE_OK) return rc;
        NativeBridge.contentSetJumpTo(contentHandle, readCString(jumpTo));
        return TPIPE_OK;
    }

    @CEntryPoint(name = "TPipe_Content_clearJumpTo")
    public static int contentClearJumpTo(IsolateThread thread, long contentHandle) {
        int rc = requireReady(); if (rc != TPIPE_OK) return rc;
        NativeBridge.contentClearJumpTo(contentHandle);
        return TPIPE_OK;
    }

    @CEntryPoint(name = "TPipe_Content_getJumpTo")
    public static int contentGetJumpTo(IsolateThread thread, long contentHandle, long buffer, int bufferSize) {
        int rc = requireReady(); if (rc != TPIPE_OK) return rc;
        String s = NativeBridge.contentGetJumpTo(contentHandle);
        if (s == null) return TPIPE_ERR_INVALID_HANDLE;
        return writeCString(buffer, bufferSize, s);
    }

    @CEntryPoint(name = "TPipe_Content_setJumpToPipe")
    public static int contentSetJumpToPipe(IsolateThread thread, long contentHandle, long pipeName) {
        int rc = requireReady(); if (rc != TPIPE_OK) return rc;
        NativeBridge.contentSetJumpToPipe(contentHandle, readCString(pipeName));
        return TPIPE_OK;
    }

    @CEntryPoint(name = "TPipe_Content_setTerminate")
    public static int contentSetTerminate(IsolateThread thread, long contentHandle, int terminate) {
        int rc = requireReady(); if (rc != TPIPE_OK) return rc;
        NativeBridge.contentSetTerminate(contentHandle, terminate != 0);
        return TPIPE_OK;
    }

    @CEntryPoint(name = "TPipe_Content_getTerminate")
    public static int contentGetTerminate(IsolateThread thread, long contentHandle, long terminate) {
        int rc = requireReady(); if (rc != TPIPE_OK) return rc;
        return writeInt(terminate, 0, NativeBridge.contentGetTerminate(contentHandle) ? 1 : 0);
    }

    @CEntryPoint(name = "TPipe_Content_setPass")
    public static int contentSetPass(IsolateThread thread, long contentHandle, int pass) {
        int rc = requireReady(); if (rc != TPIPE_OK) return rc;
        NativeBridge.contentSetPass(contentHandle, pass != 0);
        return TPIPE_OK;
    }

    @CEntryPoint(name = "TPipe_Content_setRepeat")
    public static int contentSetRepeat(IsolateThread thread, long contentHandle, int repeat) {
        int rc = requireReady(); if (rc != TPIPE_OK) return rc;
        NativeBridge.contentSetRepeat(contentHandle, repeat != 0);
        return TPIPE_OK;
    }

    @CEntryPoint(name = "TPipe_Content_setSkipReasoning")
    public static int contentSetSkipReasoning(IsolateThread thread, long contentHandle, int skip) {
        int rc = requireReady(); if (rc != TPIPE_OK) return rc;
        NativeBridge.contentSetSkipReasoning(contentHandle, skip != 0);
        return TPIPE_OK;
    }

    @CEntryPoint(name = "TPipe_Content_setRepeatPipe")
    public static int contentSetRepeatPipe(IsolateThread thread, long contentHandle, long pipeName) {
        int rc = requireReady(); if (rc != TPIPE_OK) return rc;
        NativeBridge.contentSetRepeatPipe(contentHandle, readCString(pipeName));
        return TPIPE_OK;
    }

    @CEntryPoint(name = "TPipe_Content_clearRepeat")
    public static int contentClearRepeat(IsolateThread thread, long contentHandle) {
        int rc = requireReady(); if (rc != TPIPE_OK) return rc;
        NativeBridge.contentClearRepeat(contentHandle);
        return TPIPE_OK;
    }

    @CEntryPoint(name = "TPipe_Content_getRepeat")
    public static int contentGetRepeat(IsolateThread thread, long contentHandle, long repeat) {
        int rc = requireReady(); if (rc != TPIPE_OK) return rc;
        return writeInt(repeat, 0, NativeBridge.contentGetRepeat(contentHandle) ? 1 : 0);
    }

    @CEntryPoint(name = "TPipe_Content_getSkip")
    public static int contentGetSkip(IsolateThread thread, long contentHandle, long skip) {
        int rc = requireReady(); if (rc != TPIPE_OK) return rc;
        return writeInt(skip, 0, NativeBridge.contentGetSkip(contentHandle) ? 1 : 0);
    }

    @CEntryPoint(name = "TPipe_Content_getJump")
    public static int contentGetJump(IsolateThread thread, long contentHandle, long jump) {
        int rc = requireReady(); if (rc != TPIPE_OK) return rc;
        return writeInt(jump, 0, NativeBridge.contentGetJump(contentHandle) ? 1 : 0);
    }

    @CEntryPoint(name = "TPipe_Content_setJump")
    public static int contentSetJump(IsolateThread thread, long contentHandle, int jump) {
        int rc = requireReady(); if (rc != TPIPE_OK) return rc;
        NativeBridge.contentSetJump(contentHandle, jump != 0);
        return TPIPE_OK;
    }

    //====================================================================
    // Binary API
    //====================================================================

    @CEntryPoint(name = "TPipe_Binary_create")
    public static long binaryCreate(IsolateThread thread, int variant, long data, int dataLength, long mimeType, long filename) {
        int rc = requireReady(); if (rc != TPIPE_OK) return 0L;
        try {
            if (dataLength < 0) return 0L;
            byte[] bytes = new byte[dataLength];
            for (int i = 0; i < dataLength; i++) bytes[i] = UNSAFE.getByte(data + i);
            return NativeBridge.binaryCreate(variant, bytes, readCString(mimeType), readCString(filename));
        } catch (Exception e) {
            NativeBridge.setLastError(e.getMessage());
            return 0L;
        }
    }

    @CEntryPoint(name = "TPipe_Binary_createEmpty")
    public static long binaryCreateEmpty(IsolateThread thread) {
        int rc = requireReady(); if (rc != TPIPE_OK) return 0L;
        return NativeBridge.binaryCreateEmpty();
    }

    @CEntryPoint(name = "TPipe_Binary_release")
    public static int binaryRelease(IsolateThread thread, long binaryHandle) {
        int rc = requireReady(); if (rc != TPIPE_OK) return rc;
        if (HandleRegistry.INSTANCE.getType(binaryHandle) != HandleTypes.BINARY) return TPIPE_ERR_INVALID_HANDLE;
        return HandleRegistry.INSTANCE.release(binaryHandle);
    }

    @CEntryPoint(name = "TPipe_Binary_getVariant")
    public static int binaryGetVariant(IsolateThread thread, long binaryHandle, long variant) {
        int rc = requireReady(); if (rc != TPIPE_OK) return rc;
        int v = NativeBridge.binaryGetVariant(binaryHandle);
        if (v < 0) return v;
        return writeInt(variant, 0, v);
    }

    /**
     * Get the raw bytes pointer and length from a binary handle. The C
     * ABI signature (per {@code tpipe-abi.h}) is:
     * <pre>
     *   int TPipe_Binary_getBytes(
     *       graal_isolatethread_t* thread,
     *       TPipe_BinaryHandle binaryHandle,
     *       const uint8_t** data,
     *       int* length);
     * </pre>
     * On success, a freshly-allocated native buffer holding the bytes is
     * written to {@code *data} and the buffer length to {@code *length}.
     * The C caller is responsible for releasing the buffer via
     * {@link #freeNative}.
     */
    @CEntryPoint(name = "TPipe_Binary_getBytes")
    public static int binaryGetBytes(IsolateThread thread, long binaryHandle, long dataAddress, long lengthAddress) {
        int rc = requireReady(); if (rc != TPIPE_OK) return rc;
        byte[] bytes = NativeBridge.binaryGetBytes(binaryHandle);
        if (bytes == null) return TPIPE_ERR_INVALID_HANDLE;
        long buf = 0L;
        if (bytes.length > 0) {
            buf = UNSAFE.allocateMemory(bytes.length);
            // Copy the bytes into the native buffer. The base offset of a
            // Java byte[] is calculated from its identity hash code trick
            // here via the ARRAY_BYTE_BASE_OFFSET constant.
            UNSAFE.copyMemory(bytes, UnsafeHelpers.ARRAY_BYTE_BASE_OFFSET, null, buf, bytes.length);
        }
        int rc2 = writeInt(lengthAddress, 0, bytes.length);
        if (rc2 != TPIPE_OK) {
            if (buf != 0L) UNSAFE.freeMemory(buf);
            return rc2;
        }
        // Binary data: caller-provided dataAddress; we trust the size contract.
        int rc3 = writePtr(dataAddress, 0, buf);
        if (rc3 != TPIPE_OK) {
            if (buf != 0L) UNSAFE.freeMemory(buf);
            return rc3;
        }
        return TPIPE_OK;
    }

    /**
     * Free a native buffer previously allocated and returned by
     * {@link #binaryGetBytes}. Mirrors the C standard library
     * {@code free(3)} for the JVM-side allocator.
     *
     * <p>Symbol: {@code TPipe_free}. Safe to call with a NULL pointer
     * (no-op, returns 0).
     */
    @CEntryPoint(name = "TPipe_free")
    public static int freeNative(IsolateThread thread, long ptr) {
        if (ptr != 0L) {
            UNSAFE.freeMemory(ptr);
        }
        return TPIPE_OK;
    }

    //====================================================================
    // Pipe API
    //====================================================================

    @CEntryPoint(name = "TPipe_Pipe_create")
    public static long pipeCreate(IsolateThread thread, int provider, long modelName, long region, long settings) {
        int rc = requireReady(); if (rc != TPIPE_OK) return 0L;
        try {
            String model = readCString(modelName);
            if (model == null) model = "anthropic.claude-3-7-sonnet-20250219-v1:0";
            String reg = readCString(region);
            if (reg == null) reg = "us-east-1";
            return NativeBridge.pipeCreate(provider, model, reg, settings);
        } catch (Exception e) {
            NativeBridge.setLastError(e.getMessage());
            return 0L;
        }
    }

    @CEntryPoint(name = "TPipe_Pipe_setProvider")
    public static int pipeSetProvider(IsolateThread thread, long pipe, int provider) {
        int rc = requireReady(); if (rc != TPIPE_OK) return rc;
        NativeBridge.pipeSetProvider(pipe, provider);
        return TPIPE_OK;
    }

    @CEntryPoint(name = "TPipe_Pipe_setTemperature")
    public static int pipeSetTemperature(IsolateThread thread, long pipe, float temperature) {
        int rc = requireReady(); if (rc != TPIPE_OK) return rc;
        NativeBridge.pipeSetTemperature(pipe, temperature);
        return TPIPE_OK;
    }

    @CEntryPoint(name = "TPipe_Pipe_setRepetitionPenalty")
    public static int pipeSetRepetitionPenalty(IsolateThread thread, long pipe, float penalty) {
        int rc = requireReady(); if (rc != TPIPE_OK) return rc;
        NativeBridge.pipeSetRepetitionPenalty(pipe, penalty);
        return TPIPE_OK;
    }

    @CEntryPoint(name = "TPipe_Pipe_setReasoning")
    public static int pipeSetReasoning(IsolateThread thread, long pipe, int reasoning) {
        int rc = requireReady(); if (rc != TPIPE_OK) return rc;
        NativeBridge.pipeSetReasoning(pipe, reasoning);
        return TPIPE_OK;
    }

    @CEntryPoint(name = "TPipe_Pipe_init")
    public static int pipeInit(IsolateThread thread, long pipe, long content, long context) {
        int rc = requireReady(); if (rc != TPIPE_OK) return rc;
        return NativeBridge.pipeInit(pipe, content, context);
    }

    @CEntryPoint(name = "TPipe_Pipe_execute")
    public static long pipeExecute(IsolateThread thread, long pipe, long content, long settings, long resultOut) {
        int rc = requireReady(); if (rc != TPIPE_OK) return 0L;
        long op = NativeBridge.pipeExecute(pipe, content);
        if (op == 0L) return 0L;
        // Extract the result content handle from the operation handle and write to resultOut
        if (resultOut != 0L) {
            writePtr(resultOut, 0, NativeBridge.operationGetResult(op));
        }
        return op;
    }

    @CEntryPoint(name = "TPipe_Pipe_executeContentAsync")
    public static long pipeExecuteContentAsync(IsolateThread thread, long pipe, long content, long settings) {
        int rc = requireReady(); if (rc != TPIPE_OK) return 0L;
        return NativeBridge.pipeExecuteAsync(pipe, content);
    }

    @CEntryPoint(name = "TPipe_Pipe_getTokenUsage")
    public static int pipeGetTokenUsage(IsolateThread thread, long pipe, long inputTokens, long outputTokens, long totalTokens, long cost) {
        int rc = requireReady(); if (rc != TPIPE_OK) return rc;
        int[] usage = NativeBridge.pipeGetTokenUsage(pipe);
        int rc2 = writeInt(inputTokens, 0, usage[0]); if (rc2 != TPIPE_OK) return rc2;
        rc2 = writeInt(outputTokens, 0, usage[1]); if (rc2 != TPIPE_OK) return rc2;
        rc2 = writeInt(totalTokens, 0, usage[2]); if (rc2 != TPIPE_OK) return rc2;
        return writeFloat(cost, 0, (float) usage[3]);
    }

    //====================================================================
    // Cycle 4 — Pipe prompt + sampling surface
    //====================================================================

    @CEntryPoint(name = "TPipe_Pipe_setSystemPrompt")
    public static int pipeSetSystemPrompt(IsolateThread thread, long pipe, long textPtr) {
        int rc = requireReady(); if (rc != TPIPE_OK) return rc;
        String text = readCString(textPtr);
        return NativeBridge.pipeSetSystemPrompt(pipe, text);
    }

    @CEntryPoint(name = "TPipe_Pipe_getSystemPrompt")
    public static int pipeGetSystemPrompt(IsolateThread thread, long pipe, long buffer, int bufferSize) {
        int rc = requireReady(); if (rc != TPIPE_OK) return rc;
        if (buffer == 0L) return TPIPE_ERR_NULL_POINTER;
        if (bufferSize <= 0) return TPIPE_ERR_INVALID_ARGUMENT;
        byte[] tmp = new byte[bufferSize];
        int n = NativeBridge.pipeGetSystemPrompt(pipe, tmp, 0, bufferSize - 1);
        if (n < 0) return n;
        return copyToNativeBuffer(buffer, bufferSize, tmp, n);
    }

    @CEntryPoint(name = "TPipe_Pipe_setUserPrompt")
    public static int pipeSetUserPrompt(IsolateThread thread, long pipe, long textPtr) {
        int rc = requireReady(); if (rc != TPIPE_OK) return rc;
        String text = readCString(textPtr);
        return NativeBridge.pipeSetUserPrompt(pipe, text);
    }

    @CEntryPoint(name = "TPipe_Pipe_setMiddlePrompt")
    public static int pipeSetMiddlePrompt(IsolateThread thread, long pipe, long textPtr) {
        int rc = requireReady(); if (rc != TPIPE_OK) return rc;
        String text = readCString(textPtr);
        return NativeBridge.pipeSetMiddlePrompt(pipe, text);
    }

    @CEntryPoint(name = "TPipe_Pipe_setFooterPrompt")
    public static int pipeSetFooterPrompt(IsolateThread thread, long pipe, long textPtr) {
        int rc = requireReady(); if (rc != TPIPE_OK) return rc;
        String text = readCString(textPtr);
        return NativeBridge.pipeSetFooterPrompt(pipe, text);
    }

    @CEntryPoint(name = "TPipe_Pipe_setTopP")
    public static int pipeSetTopP(IsolateThread thread, long pipe, long doubleBits) {
        int rc = requireReady(); if (rc != TPIPE_OK) return rc;
        return NativeBridge.pipeSetTopP(pipe, doubleBits);
    }

    @CEntryPoint(name = "TPipe_Pipe_setTopK")
    public static int pipeSetTopK(IsolateThread thread, long pipe, int top) {
        int rc = requireReady(); if (rc != TPIPE_OK) return rc;
        return NativeBridge.pipeSetTopK(pipe, top);
    }

    @CEntryPoint(name = "TPipe_Pipe_setMaxTokens")
    public static int pipeSetMaxTokens(IsolateThread thread, long pipe, int max) {
        int rc = requireReady(); if (rc != TPIPE_OK) return rc;
        return NativeBridge.pipeSetMaxTokens(pipe, max);
    }

    @CEntryPoint(name = "TPipe_Pipe_setSeed")
    public static int pipeSetSeed(IsolateThread thread, long pipe, long seedBits) {
        int rc = requireReady(); if (rc != TPIPE_OK) return rc;
        return NativeBridge.pipeSetSeed(pipe, seedBits);
    }

    @CEntryPoint(name = "TPipe_Pipe_setStopSequences")
    public static int pipeSetStopSequences(IsolateThread thread, long pipe, long textPtr) {
        int rc = requireReady(); if (rc != TPIPE_OK) return rc;
        String text = readCString(textPtr);
        return NativeBridge.pipeSetStopSequences(pipe, text);
    }

    //====================================================================
    // Cycle 5 — Pipe JSON / multimodal / binary surface
    //====================================================================

    @CEntryPoint(name = "TPipe_Pipe_setJsonInput")
    public static int pipeSetJsonInput(IsolateThread thread, long pipe, long jsonPtr) {
        int rc = requireReady(); if (rc != TPIPE_OK) return rc;
        String json = readCString(jsonPtr);
        return NativeBridge.pipeSetJsonInput(pipe, json);
    }

    @CEntryPoint(name = "TPipe_Pipe_setJsonOutput")
    public static int pipeSetJsonOutput(IsolateThread thread, long pipe, long jsonPtr) {
        int rc = requireReady(); if (rc != TPIPE_OK) return rc;
        String json = readCString(jsonPtr);
        return NativeBridge.pipeSetJsonOutput(pipe, json);
    }

    @CEntryPoint(name = "TPipe_Pipe_setJsonInputInstructions")
    public static int pipeSetJsonInputInstructions(IsolateThread thread, long pipe, long textPtr) {
        int rc = requireReady(); if (rc != TPIPE_OK) return rc;
        String text = readCString(textPtr);
        return NativeBridge.pipeSetJsonInputInstructions(pipe, text);
    }

    @CEntryPoint(name = "TPipe_Pipe_setJsonOutputInstructions")
    public static int pipeSetJsonOutputInstructions(IsolateThread thread, long pipe, long textPtr) {
        int rc = requireReady(); if (rc != TPIPE_OK) return rc;
        String text = readCString(textPtr);
        return NativeBridge.pipeSetJsonOutputInstructions(pipe, text);
    }

    @CEntryPoint(name = "TPipe_Pipe_requireJsonPromptInjection")
    public static int pipeRequireJsonPromptInjection(IsolateThread thread, long pipe, int stripExternalText) {
        int rc = requireReady(); if (rc != TPIPE_OK) return rc;
        return NativeBridge.pipeRequireJsonPromptInjection(pipe, stripExternalText);
    }

    @CEntryPoint(name = "TPipe_Pipe_setMultimodalInput")
    public static int pipeSetMultimodalInput(IsolateThread thread, long pipe, long content) {
        int rc = requireReady(); if (rc != TPIPE_OK) return rc;
        return NativeBridge.pipeSetMultimodalInput(pipe, content);
    }

    @CEntryPoint(name = "TPipe_Pipe_getCachedInput")
    public static int pipeGetCachedInput(IsolateThread thread, long pipe, long outContent) {
        int rc = requireReady(); if (rc != TPIPE_OK) return rc;
        if (outContent == 0L) return TPIPE_ERR_NULL_POINTER;
        long handleId = NativeBridge.pipeGetCachedInput(pipe);
        if (handleId < 0L) return (int) handleId;
        return writePtr(outContent, 0, handleId);
    }

    @CEntryPoint(name = "TPipe_Pipe_setMergedPcpJsonInstructions")
    public static int pipeSetMergedPcpJsonInstructions(IsolateThread thread, long pipe, long textPtr) {
        int rc = requireReady(); if (rc != TPIPE_OK) return rc;
        String text = readCString(textPtr);
        return NativeBridge.pipeSetMergedPcpJsonInstructions(pipe, text);
    }

    @CEntryPoint(name = "TPipe_Pipe_cacheInput")
    public static int pipeCacheInput(IsolateThread thread, long pipe) {
        int rc = requireReady(); if (rc != TPIPE_OK) return rc;
        return NativeBridge.pipeCacheInput(pipe);
    }

    @CEntryPoint(name = "TPipe_Pipe_forceCacheInput")
    public static int pipeForceCacheInput(IsolateThread thread, long pipe, long content) {
        int rc = requireReady(); if (rc != TPIPE_OK) return rc;
        return NativeBridge.pipeForceCacheInput(pipe, content);
    }

    //====================================================================
    // Cycle 6 — Pipe tracing / compression / token-budget surface
    //====================================================================

    @CEntryPoint(name = "TPipe_Pipe_enableTracing")
    public static int pipeEnableTracing(IsolateThread thread, long pipe) {
        int rc = requireReady(); if (rc != TPIPE_OK) return rc;
        return NativeBridge.pipeEnableTracing(pipe);
    }

    @CEntryPoint(name = "TPipe_Pipe_disableTracing")
    public static int pipeDisableTracing(IsolateThread thread, long pipe) {
        int rc = requireReady(); if (rc != TPIPE_OK) return rc;
        return NativeBridge.pipeDisableTracing(pipe);
    }

    @CEntryPoint(name = "TPipe_Pipe_addTraceId")
    public static int pipeAddTraceId(IsolateThread thread, long pipe, long idPtr) {
        int rc = requireReady(); if (rc != TPIPE_OK) return rc;
        String id = readCString(idPtr);
        return NativeBridge.pipeAddTraceId(pipe, id);
    }

    @CEntryPoint(name = "TPipe_Pipe_removeTraceId")
    public static int pipeRemoveTraceId(IsolateThread thread, long pipe, long idPtr) {
        int rc = requireReady(); if (rc != TPIPE_OK) return rc;
        String id = readCString(idPtr);
        return NativeBridge.pipeRemoveTraceId(pipe, id);
    }

    @CEntryPoint(name = "TPipe_Pipe_clearTraceIds")
    public static int pipeClearTraceIds(IsolateThread thread, long pipe) {
        int rc = requireReady(); if (rc != TPIPE_OK) return rc;
        return NativeBridge.pipeClearTraceIds(pipe);
    }

    @CEntryPoint(name = "TPipe_Pipe_getActiveTraceId")
    public static int pipeGetActiveTraceId(IsolateThread thread, long pipe, long buffer, int bufferSize) {
        int rc = requireReady(); if (rc != TPIPE_OK) return rc;
        if (buffer == 0L) return TPIPE_ERR_NULL_POINTER;
        if (bufferSize <= 0) return TPIPE_ERR_INVALID_ARGUMENT;
        byte[] tmp = new byte[bufferSize];
        int n = NativeBridge.pipeGetActiveTraceId(pipe, tmp, 0, bufferSize - 1);
        if (n < 0) return n;
        return copyToNativeBuffer(buffer, bufferSize, tmp, n);
    }

    @CEntryPoint(name = "TPipe_Pipe_enableSemanticCompression")
    public static int pipeEnableSemanticCompression(IsolateThread thread, long pipe) {
        int rc = requireReady(); if (rc != TPIPE_OK) return rc;
        return NativeBridge.pipeEnableSemanticCompression(pipe);
    }

    @CEntryPoint(name = "TPipe_Pipe_enableSemanticDecompression")
    public static int pipeEnableSemanticDecompression(IsolateThread thread, long pipe) {
        int rc = requireReady(); if (rc != TPIPE_OK) return rc;
        return NativeBridge.pipeEnableSemanticDecompression(pipe);
    }

    @CEntryPoint(name = "TPipe_Pipe_enableMaxTokenOverflow")
    public static int pipeEnableMaxTokenOverflow(IsolateThread thread, long pipe) {
        int rc = requireReady(); if (rc != TPIPE_OK) return rc;
        return NativeBridge.pipeEnableMaxTokenOverflow(pipe);
    }

    @CEntryPoint(name = "TPipe_Pipe_isAutoTruncateContextEnabled")
    public static int pipeIsAutoTruncateContextEnabled(IsolateThread thread, long pipe, long outEnabled) {
        int rc = requireReady(); if (rc != TPIPE_OK) return rc;
        int v = NativeBridge.pipeIsAutoTruncateContextEnabled(pipe);
        if (v < 0) return v;
        return writeInt(outEnabled, 0, v);
    }

    //====================================================================
    // Cycle 7 — Pipe hooks (DSL suspend-lambda stubs) + P2P/PCP/ContextBank
    //
    // All 10 of these are UNSUPPORTED stubs. They return TPIPE_ERR_NOT_IMPLEMENTED
    // (-0x10) on a valid pipe handle, after the null-handle check in the bridge
    // layer.
    //====================================================================

    @CEntryPoint(name = "TPipe_Pipe_setRetryFunction")
    public static int pipeSetRetryFunction(IsolateThread thread, long pipe) {
        int rc = requireReady(); if (rc != TPIPE_OK) return rc;
        return NativeBridge.pipeSetRetryFunction(pipe);
    }

    @CEntryPoint(name = "TPipe_Pipe_setExceptionFunction")
    public static int pipeSetExceptionFunction(IsolateThread thread, long pipe) {
        int rc = requireReady(); if (rc != TPIPE_OK) return rc;
        return NativeBridge.pipeSetExceptionFunction(pipe);
    }

    @CEntryPoint(name = "TPipe_Pipe_setStringValidatorFunction")
    public static int pipeSetStringValidatorFunction(IsolateThread thread, long pipe) {
        int rc = requireReady(); if (rc != TPIPE_OK) return rc;
        return NativeBridge.pipeSetStringValidatorFunction(pipe);
    }

    @CEntryPoint(name = "TPipe_Pipe_setTransformationFunction")
    public static int pipeSetTransformationFunction(IsolateThread thread, long pipe) {
        int rc = requireReady(); if (rc != TPIPE_OK) return rc;
        return NativeBridge.pipeSetTransformationFunction(pipe);
    }

    @CEntryPoint(name = "TPipe_Pipe_setPreInitFunction")
    public static int pipeSetPreInitFunction(IsolateThread thread, long pipe) {
        int rc = requireReady(); if (rc != TPIPE_OK) return rc;
        return NativeBridge.pipeSetPreInitFunction(pipe);
    }

    @CEntryPoint(name = "TPipe_Pipe_setPreValidationFunction")
    public static int pipeSetPreValidationFunction(IsolateThread thread, long pipe) {
        int rc = requireReady(); if (rc != TPIPE_OK) return rc;
        return NativeBridge.pipeSetPreValidationFunction(pipe);
    }

    @CEntryPoint(name = "TPipe_Pipe_setPreInvokeFunction")
    public static int pipeSetPreInvokeFunction(IsolateThread thread, long pipe) {
        int rc = requireReady(); if (rc != TPIPE_OK) return rc;
        return NativeBridge.pipeSetPreInvokeFunction(pipe);
    }

    @CEntryPoint(name = "TPipe_Pipe_setPostGenerateFunction")
    public static int pipeSetPostGenerateFunction(IsolateThread thread, long pipe) {
        int rc = requireReady(); if (rc != TPIPE_OK) return rc;
        return NativeBridge.pipeSetPostGenerateFunction(pipe);
    }

    @CEntryPoint(name = "TPipe_Pipe_setPcPContext")
    public static int pipeSetPcPContext(IsolateThread thread, long pipe) {
        int rc = requireReady(); if (rc != TPIPE_OK) return rc;
        return NativeBridge.pipeSetPcPContext(pipe);
    }

    @CEntryPoint(name = "TPipe_Pipe_enableMemoryIntrospection")
    public static int pipeEnableMemoryIntrospection(IsolateThread thread, long pipe) {
        int rc = requireReady(); if (rc != TPIPE_OK) return rc;
        return NativeBridge.pipeEnableMemoryIntrospection(pipe);
    }

    //====================================================================
    // PipeSettings API
    //====================================================================

    @CEntryPoint(name = "TPipe_PipeSettings_create")
    public static long pipeSettingsCreate(IsolateThread thread) {
        int rc = requireReady(); if (rc != TPIPE_OK) return 0L;
        return NativeBridge.pipeSettingsCreate();
    }

    @CEntryPoint(name = "TPipe_PipeSettings_setModel")
    public static int pipeSettingsSetModel(IsolateThread thread, long settings, long modelName) {
        int rc = requireReady(); if (rc != TPIPE_OK) return rc;
        String m = readCString(modelName);
        if (m == null) return TPIPE_ERR_INVALID_ARGUMENT;
        NativeBridge.pipeSettingsSetModel(settings, m);
        return TPIPE_OK;
    }

    @CEntryPoint(name = "TPipe_PipeSettings_setTemperature")
    public static int pipeSettingsSetTemperature(IsolateThread thread, long settings, float temperature) {
        int rc = requireReady(); if (rc != TPIPE_OK) return rc;
        NativeBridge.pipeSettingsSetTemperature(settings, temperature);
        return TPIPE_OK;
    }

    @CEntryPoint(name = "TPipe_PipeSettings_setMaxTokens")
    public static int pipeSettingsSetMaxTokens(IsolateThread thread, long settings, int maxTokens) {
        int rc = requireReady(); if (rc != TPIPE_OK) return rc;
        NativeBridge.pipeSettingsSetMaxTokens(settings, maxTokens);
        return TPIPE_OK;
    }

    @CEntryPoint(name = "TPipe_PipeSettings_setTimeout")
    public static int pipeSettingsSetTimeout(IsolateThread thread, long settings, int timeoutMs) {
        int rc = requireReady(); if (rc != TPIPE_OK) return rc;
        NativeBridge.pipeSettingsSetTimeout(settings, timeoutMs);
        return TPIPE_OK;
    }

    @CEntryPoint(name = "TPipe_PipeSettings_setProvider")
    public static int pipeSettingsSetProvider(IsolateThread thread, long settings, int provider) {
        int rc = requireReady(); if (rc != TPIPE_OK) return rc;
        NativeBridge.pipeSettingsSetProvider(settings, provider);
        return TPIPE_OK;
    }

    @CEntryPoint(name = "TPipe_PipeSettings_setString")
    public static int pipeSettingsSetString(IsolateThread thread, long settings, long key, long value) {
        int rc = requireReady(); if (rc != TPIPE_OK) return rc;
        String k = readCString(key);
        String v = readCString(value);
        if (k == null) return TPIPE_ERR_INVALID_ARGUMENT;
        NativeBridge.pipeSettingsSetString(settings, k, v == null ? "" : v);
        return TPIPE_OK;
    }

    @CEntryPoint(name = "TPipe_PipeSettings_setInt")
    public static int pipeSettingsSetInt(IsolateThread thread, long settings, long key, int value) {
        int rc = requireReady(); if (rc != TPIPE_OK) return rc;
        String k = readCString(key);
        if (k == null) return TPIPE_ERR_INVALID_ARGUMENT;
        NativeBridge.pipeSettingsSetInt(settings, k, value);
        return TPIPE_OK;
    }

    @CEntryPoint(name = "TPipe_PipeSettings_setFloat")
    public static int pipeSettingsSetFloat(IsolateThread thread, long settings, long key, float value) {
        int rc = requireReady(); if (rc != TPIPE_OK) return rc;
        String k = readCString(key);
        if (k == null) return TPIPE_ERR_INVALID_ARGUMENT;
        NativeBridge.pipeSettingsSetFloat(settings, k, value);
        return TPIPE_OK;
    }

    @CEntryPoint(name = "TPipe_PipeSettings_setBool")
    public static int pipeSettingsSetBool(IsolateThread thread, long settings, long key, int value) {
        int rc = requireReady(); if (rc != TPIPE_OK) return rc;
        String k = readCString(key);
        if (k == null) return TPIPE_ERR_INVALID_ARGUMENT;
        NativeBridge.pipeSettingsSetBool(settings, k, value != 0);
        return TPIPE_OK;
    }

    @CEntryPoint(name = "TPipe_PipeSettings_release")
    public static int pipeSettingsRelease(IsolateThread thread, long settings) {
        int rc = requireReady(); if (rc != TPIPE_OK) return rc;
        if (HandleRegistry.INSTANCE.getType(settings) != HandleTypes.PIPE_SETTINGS) return TPIPE_ERR_INVALID_HANDLE;
        return HandleRegistry.INSTANCE.release(settings);
    }

    //====================================================================
    // Pipeline API
    //====================================================================

    @CEntryPoint(name = "TPipe_Pipeline_create")
    public static long pipelineCreate(IsolateThread thread, long configJson) {
        int rc = requireReady(); if (rc != TPIPE_OK) return 0L;
        // configJson is reserved for future use; the C ABI uses a single-pipe pipeline by default
        return NativeBridge.pipelineCreate();
    }

    @CEntryPoint(name = "TPipe_Pipeline_add")
    public static int pipelineAdd(IsolateThread thread, long pipeline, long pipe) {
        int rc = requireReady(); if (rc != TPIPE_OK) return rc;
        return NativeBridge.pipelineAdd(pipeline, pipe);
    }

    /**
     * Execute a pipeline. The C ABI signature (per {@code tpipe-abi.h}) is:
     * <pre>
     *   int TPipe_Pipeline_execute(
     *       graal_isolatethread_t* thread,
     *       TPipe_PipelineHandle pipeline,
     *       TPipe_ContentHandle content,
     *       TPipe_ContentHandle* result);
     * </pre>
     * Returns 0 on success; on success, the resulting content handle is
     * written to {@code *result} (or 0 if the operation failed).
     */
    @CEntryPoint(name = "TPipe_Pipeline_execute")
    public static int pipelineExecute(IsolateThread thread, long pipeline, long content, long resultOut) {
        int rc = requireReady(); if (rc != TPIPE_OK) return rc;
        long op = NativeBridge.pipelineExecute(pipeline, content);
        if (op == 0L) {
            if (resultOut != 0L) writePtr(resultOut, 0, 0L);
            return TPIPE_ERR_INTERNAL;
        }
        long resultHandle = NativeBridge.operationGetResult(op);
        if (resultOut != 0L) writePtr(resultOut, 0, resultHandle);
        return TPIPE_OK;
    }

    @CEntryPoint(name = "TPipe_Pipeline_getOutcome")
    public static int pipelineGetOutcome(IsolateThread thread, long pipeline, long buffer, int bufferSize) {
        int rc = requireReady(); if (rc != TPIPE_OK) return rc;
        String s = NativeBridge.pipelineGetOutcome(pipeline);
        if (s == null) return TPIPE_ERR_INVALID_HANDLE;
        return writeCString(buffer, bufferSize, s);
    }

    @CEntryPoint(name = "TPipe_Pipeline_getName")
    public static int pipelineGetName(IsolateThread thread, long pipeline, long buffer, int bufferSize) {
        int rc = requireReady(); if (rc != TPIPE_OK) return rc;
        String s = NativeBridge.pipelineGetName(pipeline);
        if (s == null) return TPIPE_ERR_INVALID_HANDLE;
        return writeCString(buffer, bufferSize, s);
    }

    @CEntryPoint(name = "TPipe_Pipeline_setName")
    public static int pipelineSetName(IsolateThread thread, long pipeline, long name) {
        int rc = requireReady(); if (rc != TPIPE_OK) return rc;
        String n = readCString(name);
        if (n == null) return TPIPE_ERR_INVALID_ARGUMENT;
        NativeBridge.pipelineSetName(pipeline, n);
        return TPIPE_OK;
    }

    @CEntryPoint(name = "TPipe_Pipeline_getContextWindow")
    public static long pipelineGetContextWindow(IsolateThread thread, long pipeline) {
        int rc = requireReady(); if (rc != TPIPE_OK) return 0L;
        return NativeBridge.pipelineGetContextWindow(pipeline);
    }

    @CEntryPoint(name = "TPipe_Pipeline_getMiniBank")
    public static long pipelineGetMiniBank(IsolateThread thread, long pipeline) {
        int rc = requireReady(); if (rc != TPIPE_OK) return 0L;
        return NativeBridge.pipelineGetMiniBank(pipeline);
    }

    @CEntryPoint(name = "TPipe_Pipeline_release")
    public static int pipelineRelease(IsolateThread thread, long pipeline) {
        int rc = requireReady(); if (rc != TPIPE_OK) return rc;
        if (HandleRegistry.INSTANCE.getType(pipeline) != HandleTypes.PIPELINE) return TPIPE_ERR_INVALID_HANDLE;
        return HandleRegistry.INSTANCE.release(pipeline);
    }

    //====================================================================
    // Context API
    //====================================================================

    @CEntryPoint(name = "TPipe_LoreBook_create")
    public static long loreBookCreate(IsolateThread thread) {
        int rc = requireReady(); if (rc != TPIPE_OK) return 0L;
        return NativeBridge.loreBookCreate();
    }

    /**
     * Add an entry to a LoreBook. The C ABI signature (per
     * {@code tpipe-abi.h}) is {@code (loreBook, key, value)} — weight is
     * NOT part of the public C ABI; it is set separately via
     * {@link #loreBookSetWeight} which corresponds to
     * {@code TPipe_LoreBook_setWeight}.
     */
    @CEntryPoint(name = "TPipe_LoreBook_addEntry")
    public static int loreBookAddEntry(IsolateThread thread, long loreBook, long key, long value) {
        int rc = requireReady(); if (rc != TPIPE_OK) return rc;
        String k = readCString(key);
        String v = readCString(value);
        if (k == null || v == null) return TPIPE_ERR_INVALID_ARGUMENT;
        NativeBridge.loreBookAddEntry(loreBook, k, v);
        return TPIPE_OK;
    }

    //====================================================================
    // LoreBook field accessors (Phase 7 — full LoreBookHandle coverage)
    //====================================================================

    /**
     * Internal helper. Copies the prefix [tmp[0..n)] into the caller's native
     * buffer at [buffer] and appends a single null terminator. Returns the
     * number of bytes written (excluding the null terminator).
     */
    private static int copyToNativeBuffer(long buffer, int bufferSize, byte[] tmp, int n) {
        if (buffer == 0L) return TPIPE_ERR_NULL_POINTER;
        if (bufferSize <= 0) return TPIPE_ERR_INVALID_ARGUMENT;
        for (int i = 0; i < n; i++) {
            UNSAFE.putByte(buffer + i, tmp[i]);
        }
        UNSAFE.putByte(buffer + n, (byte) 0);
        return n;
    }

    @CEntryPoint(name = "TPipe_LoreBook_setKey")
    public static int loreBookSetKey(IsolateThread thread, long loreBook, long key) {
        int rc = requireReady(); if (rc != TPIPE_OK) return rc;
        String k = readCString(key);
        if (k == null) return TPIPE_ERR_INVALID_ARGUMENT;
        return NativeBridge.loreBookSetKey(loreBook, k);
    }

    @CEntryPoint(name = "TPipe_LoreBook_getKey")
    public static int loreBookGetKey(IsolateThread thread, long loreBook, long buffer, int bufferSize) {
        int rc = requireReady(); if (rc != TPIPE_OK) return rc;
        byte[] tmp = new byte[Math.max(1, bufferSize)];
        int n = NativeBridge.loreBookGetKey(loreBook, tmp, 0, bufferSize > 0 ? bufferSize - 1 : 0);
        if (n < 0) return n;
        return copyToNativeBuffer(buffer, bufferSize, tmp, n);
    }

    @CEntryPoint(name = "TPipe_LoreBook_setValue")
    public static int loreBookSetValue(IsolateThread thread, long loreBook, long value) {
        int rc = requireReady(); if (rc != TPIPE_OK) return rc;
        String v = readCString(value);
        if (v == null) return TPIPE_ERR_INVALID_ARGUMENT;
        return NativeBridge.loreBookSetValue(loreBook, v);
    }

    @CEntryPoint(name = "TPipe_LoreBook_getValue")
    public static int loreBookGetValue(IsolateThread thread, long loreBook, long buffer, int bufferSize) {
        int rc = requireReady(); if (rc != TPIPE_OK) return rc;
        byte[] tmp = new byte[Math.max(1, bufferSize)];
        int n = NativeBridge.loreBookGetValue(loreBook, tmp, 0, bufferSize > 0 ? bufferSize - 1 : 0);
        if (n < 0) return n;
        return copyToNativeBuffer(buffer, bufferSize, tmp, n);
    }

    @CEntryPoint(name = "TPipe_LoreBook_setWeight")
    public static int loreBookSetWeight(IsolateThread thread, long loreBook, int weight) {
        int rc = requireReady(); if (rc != TPIPE_OK) return rc;
        return NativeBridge.loreBookSetWeight(loreBook, weight);
    }

    @CEntryPoint(name = "TPipe_LoreBook_getWeight")
    public static int loreBookGetWeight(IsolateThread thread, long loreBook, long outWeight) {
        int rc = requireReady(); if (rc != TPIPE_OK) return rc;
        int w = NativeBridge.loreBookGetWeight(loreBook);
        if (w == TPIPE_ERR_INVALID_HANDLE) return w;
        return writeInt(outWeight, 0, w);
    }

    @CEntryPoint(name = "TPipe_LoreBook_addLinkedKey")
    public static int loreBookAddLinkedKey(IsolateThread thread, long loreBook, long key) {
        int rc = requireReady(); if (rc != TPIPE_OK) return rc;
        String k = readCString(key);
        if (k == null) return TPIPE_ERR_INVALID_ARGUMENT;
        return NativeBridge.loreBookAddLinkedKey(loreBook, k);
    }

    @CEntryPoint(name = "TPipe_LoreBook_getLinkedKeys")
    public static int loreBookGetLinkedKeys(IsolateThread thread, long loreBook, long buffer, int bufferSize) {
        int rc = requireReady(); if (rc != TPIPE_OK) return rc;
        byte[] tmp = new byte[Math.max(1, bufferSize)];
        int n = NativeBridge.loreBookGetLinkedKeys(loreBook, tmp, 0, bufferSize > 0 ? bufferSize - 1 : 0);
        if (n < 0) return n;
        return copyToNativeBuffer(buffer, bufferSize, tmp, n);
    }

    @CEntryPoint(name = "TPipe_LoreBook_addAliasKey")
    public static int loreBookAddAliasKey(IsolateThread thread, long loreBook, long key) {
        int rc = requireReady(); if (rc != TPIPE_OK) return rc;
        String k = readCString(key);
        if (k == null) return TPIPE_ERR_INVALID_ARGUMENT;
        return NativeBridge.loreBookAddAliasKey(loreBook, k);
    }

    @CEntryPoint(name = "TPipe_LoreBook_getAliasKeys")
    public static int loreBookGetAliasKeys(IsolateThread thread, long loreBook, long buffer, int bufferSize) {
        int rc = requireReady(); if (rc != TPIPE_OK) return rc;
        byte[] tmp = new byte[Math.max(1, bufferSize)];
        int n = NativeBridge.loreBookGetAliasKeys(loreBook, tmp, 0, bufferSize > 0 ? bufferSize - 1 : 0);
        if (n < 0) return n;
        return copyToNativeBuffer(buffer, bufferSize, tmp, n);
    }

    @CEntryPoint(name = "TPipe_LoreBook_addRequiredKey")
    public static int loreBookAddRequiredKey(IsolateThread thread, long loreBook, long key) {
        int rc = requireReady(); if (rc != TPIPE_OK) return rc;
        String k = readCString(key);
        if (k == null) return TPIPE_ERR_INVALID_ARGUMENT;
        return NativeBridge.loreBookAddRequiredKey(loreBook, k);
    }

    @CEntryPoint(name = "TPipe_LoreBook_getRequiredKeys")
    public static int loreBookGetRequiredKeys(IsolateThread thread, long loreBook, long buffer, int bufferSize) {
        int rc = requireReady(); if (rc != TPIPE_OK) return rc;
        byte[] tmp = new byte[Math.max(1, bufferSize)];
        int n = NativeBridge.loreBookGetRequiredKeys(loreBook, tmp, 0, bufferSize > 0 ? bufferSize - 1 : 0);
        if (n < 0) return n;
        return copyToNativeBuffer(buffer, bufferSize, tmp, n);
    }

    @CEntryPoint(name = "TPipe_LoreBook_combine")
    public static int loreBookCombine(IsolateThread thread, long loreBook, long other) {
        int rc = requireReady(); if (rc != TPIPE_OK) return rc;
        return NativeBridge.loreBookCombine(loreBook, other);
    }

    @CEntryPoint(name = "TPipe_LoreBook_toJson")
    public static int loreBookToJson(IsolateThread thread, long loreBook, long buffer, int bufferSize) {
        int rc = requireReady(); if (rc != TPIPE_OK) return rc;
        byte[] tmp = new byte[Math.max(1, bufferSize)];
        int n = NativeBridge.loreBookToJson(loreBook, tmp, 0, bufferSize > 0 ? bufferSize - 1 : 0);
        if (n < 0) return n;
        return copyToNativeBuffer(buffer, bufferSize, tmp, n);
    }

    @CEntryPoint(name = "TPipe_ConverseHistory_create")
    public static long converseHistoryCreate(IsolateThread thread) {
        int rc = requireReady(); if (rc != TPIPE_OK) return 0L;
        return NativeBridge.converseHistoryCreate();
    }

    @CEntryPoint(name = "TPipe_ConverseHistory_add")
    public static int converseHistoryAdd(IsolateThread thread, long history, int role, long content) {
        int rc = requireReady(); if (rc != TPIPE_OK) return rc;
        String c = readCString(content);
        if (c == null) return TPIPE_ERR_INVALID_ARGUMENT;
        NativeBridge.converseHistoryAdd(history, role, c);
        return TPIPE_OK;
    }

    //====================================================================
    // ConverseHistory field accessors (Phase 8 — full ConverseHistoryHandle coverage)
    //====================================================================

    @CEntryPoint(name = "TPipe_ConverseHistory_addString")
    public static int converseHistoryAddString(IsolateThread thread, long history, long role, long content) {
        int rc = requireReady(); if (rc != TPIPE_OK) return rc;
        String r = readCString(role);
        String c = readCString(content);
        if (r == null || c == null) return TPIPE_ERR_INVALID_ARGUMENT;
        return NativeBridge.converseHistoryAddString(history, r, c);
    }

    @CEntryPoint(name = "TPipe_ConverseHistory_size")
    public static int converseHistorySize(IsolateThread thread, long history) {
        int rc = requireReady(); if (rc != TPIPE_OK) return rc;
        return NativeBridge.converseHistorySize(history);
    }

    @CEntryPoint(name = "TPipe_ConverseHistory_isEmpty")
    public static int converseHistoryIsEmpty(IsolateThread thread, long history) {
        int rc = requireReady(); if (rc != TPIPE_OK) return rc;
        return NativeBridge.converseHistoryIsEmpty(history);
    }

    @CEntryPoint(name = "TPipe_ConverseHistory_clear")
    public static int converseHistoryClear(IsolateThread thread, long history) {
        int rc = requireReady(); if (rc != TPIPE_OK) return rc;
        return NativeBridge.converseHistoryClear(history);
    }

    @CEntryPoint(name = "TPipe_ConverseHistory_getAt")
    public static int converseHistoryGetAt(IsolateThread thread, long history, int index, long buffer, int bufferSize) {
        int rc = requireReady(); if (rc != TPIPE_OK) return rc;
        byte[] tmp = new byte[Math.max(1, bufferSize)];
        int n = NativeBridge.converseHistoryGetAt(history, index, tmp, 0, bufferSize > 0 ? bufferSize - 1 : 0);
        if (n < 0) return n;
        return copyToNativeBuffer(buffer, bufferSize, tmp, n);
    }

    @CEntryPoint(name = "TPipe_ConverseHistory_toJson")
    public static int converseHistoryToJson(IsolateThread thread, long history, long buffer, int bufferSize) {
        int rc = requireReady(); if (rc != TPIPE_OK) return rc;
        byte[] tmp = new byte[Math.max(1, bufferSize)];
        int n = NativeBridge.converseHistoryToJson(history, tmp, 0, bufferSize > 0 ? bufferSize - 1 : 0);
        if (n < 0) return n;
        return copyToNativeBuffer(buffer, bufferSize, tmp, n);
    }

    @CEntryPoint(name = "TPipe_MiniBank_create")
    public static long miniBankCreate(IsolateThread thread) {
        int rc = requireReady(); if (rc != TPIPE_OK) return 0L;
        return NativeBridge.miniBankCreate();
    }

    @CEntryPoint(name = "TPipe_MiniBank_set")
    public static int miniBankSet(IsolateThread thread, long miniBank, long key, long value) {
        int rc = requireReady(); if (rc != TPIPE_OK) return rc;
        String k = readCString(key);
        String v = readCString(value);
        if (k == null || v == null) return TPIPE_ERR_INVALID_ARGUMENT;
        NativeBridge.miniBankSet(miniBank, k, v);
        return TPIPE_OK;
    }

    //====================================================================
    // MiniBank field accessors (Phase 9 — full MiniBankHandle coverage)
    //====================================================================

    @CEntryPoint(name = "TPipe_MiniBank_isEmpty")
    public static int miniBankIsEmpty(IsolateThread thread, long miniBank) {
        int rc = requireReady(); if (rc != TPIPE_OK) return rc;
        return NativeBridge.miniBankIsEmpty(miniBank);
    }

    @CEntryPoint(name = "TPipe_MiniBank_clear")
    public static int miniBankClear(IsolateThread thread, long miniBank) {
        int rc = requireReady(); if (rc != TPIPE_OK) return rc;
        return NativeBridge.miniBankClear(miniBank);
    }

    @CEntryPoint(name = "TPipe_MiniBank_pageCount")
    public static int miniBankPageCount(IsolateThread thread, long miniBank) {
        int rc = requireReady(); if (rc != TPIPE_OK) return rc;
        return NativeBridge.miniBankPageCount(miniBank);
    }

    @CEntryPoint(name = "TPipe_MiniBank_getPageKeys")
    public static int miniBankGetPageKeys(IsolateThread thread, long miniBank, long buffer, int bufferSize) {
        int rc = requireReady(); if (rc != TPIPE_OK) return rc;
        byte[] tmp = new byte[Math.max(1, bufferSize)];
        int n = NativeBridge.miniBankGetPageKeys(miniBank, tmp, 0, bufferSize > 0 ? bufferSize - 1 : 0);
        if (n < 0) return n;
        return copyToNativeBuffer(buffer, bufferSize, tmp, n);
    }

    @CEntryPoint(name = "TPipe_MiniBank_getPageJson")
    public static int miniBankGetPageJson(IsolateThread thread, long miniBank, long key, long buffer, int bufferSize) {
        int rc = requireReady(); if (rc != TPIPE_OK) return rc;
        String k = readCString(key);
        if (k == null) return TPIPE_ERR_INVALID_ARGUMENT;
        byte[] tmp = new byte[Math.max(1, bufferSize)];
        int n = NativeBridge.miniBankGetPageJson(miniBank, k, tmp, 0, bufferSize > 0 ? bufferSize - 1 : 0);
        if (n < 0) return n;
        return copyToNativeBuffer(buffer, bufferSize, tmp, n);
    }

    @CEntryPoint(name = "TPipe_MiniBank_get")
    public static int miniBankGet(IsolateThread thread, long miniBank, long key, long buffer, int bufferSize) {
        int rc = requireReady(); if (rc != TPIPE_OK) return rc;
        String k = readCString(key);
        if (k == null) return TPIPE_ERR_INVALID_ARGUMENT;
        byte[] tmp = new byte[Math.max(1, bufferSize)];
        int n = NativeBridge.miniBankGet(miniBank, k, tmp, 0, bufferSize > 0 ? bufferSize - 1 : 0);
        if (n < 0) return n;
        return copyToNativeBuffer(buffer, bufferSize, tmp, n);
    }

    @CEntryPoint(name = "TPipe_MiniBank_merge")
    public static int miniBankMerge(IsolateThread thread, long miniBank, long other, int emplaceLorebookKeys, int appendKeys, int emplaceConverseHistory, int onlyEmplaceIfNull) {
        int rc = requireReady(); if (rc != TPIPE_OK) return rc;
        return NativeBridge.miniBankMerge(
            miniBank, other,
            emplaceLorebookKeys != 0,
            appendKeys != 0,
            emplaceConverseHistory != 0,
            onlyEmplaceIfNull != 0
        );
    }

    @CEntryPoint(name = "TPipe_ContextWindow_create")
    public static long contextWindowCreate(IsolateThread thread) {
        int rc = requireReady(); if (rc != TPIPE_OK) return 0L;
        return NativeBridge.contextWindowCreate();
    }

    //====================================================================
    // ContextHandle field accessors (Phase 10 — full ContextHandle coverage)
    //====================================================================

    @CEntryPoint(name = "TPipe_Context_getLoreBookKeys")
    public static int contextGetLoreBookKeys(IsolateThread thread, long context, long buffer, int bufferSize) {
        int rc = requireReady(); if (rc != TPIPE_OK) return rc;
        byte[] tmp = new byte[Math.max(1, bufferSize)];
        int n = NativeBridge.contextGetLoreBookKeys(context, tmp, 0, bufferSize > 0 ? bufferSize - 1 : 0);
        if (n < 0) return n;
        return copyToNativeBuffer(buffer, bufferSize, tmp, n);
    }

    @CEntryPoint(name = "TPipe_Context_getContextElementsCount")
    public static int contextGetContextElementsCount(IsolateThread thread, long context, long outCount) {
        int rc = requireReady(); if (rc != TPIPE_OK) return rc;
        int c = NativeBridge.contextGetContextElementsCount(context);
        if (c < 0) return c;
        return writeInt(outCount, 0, c);
    }

    @CEntryPoint(name = "TPipe_Context_getConverseHistorySize")
    public static int contextGetConverseHistorySize(IsolateThread thread, long context, long outSize) {
        int rc = requireReady(); if (rc != TPIPE_OK) return rc;
        int s = NativeBridge.contextGetConverseHistorySize(context);
        if (s < 0) return s;
        return writeInt(outSize, 0, s);
    }

    @CEntryPoint(name = "TPipe_Context_getVersion")
    public static int contextGetVersion(IsolateThread thread, long context, long outVersion) {
        int rc = requireReady(); if (rc != TPIPE_OK) return rc;
        if (outVersion == 0L) return TPIPE_ERR_NULL_POINTER;
        long[] tmp = new long[1];
        int code = NativeBridge.contextGetVersion(context, tmp);
        if (code != 0) return code;
        UNSAFE.putLong(outVersion, tmp[0]);
        return TPIPE_OK;
    }

    @CEntryPoint(name = "TPipe_Context_getContextJson")
    public static int contextGetContextJson(IsolateThread thread, long context, long buffer, int bufferSize) {
        int rc = requireReady(); if (rc != TPIPE_OK) return rc;
        byte[] tmp = new byte[Math.max(1, bufferSize)];
        int n = NativeBridge.contextGetContextJson(context, tmp, 0, bufferSize > 0 ? bufferSize - 1 : 0);
        if (n < 0) return n;
        return copyToNativeBuffer(buffer, bufferSize, tmp, n);
    }

    //====================================================================
    // PCP API
    //====================================================================

    @CEntryPoint(name = "TPipe_PCPHandle_create")
    public static long pcpHandleCreate(IsolateThread thread) {
        int rc = requireReady(); if (rc != TPIPE_OK) return 0L;
        return NativeBridge.pcpCreate();
    }

    /**
     * Execute a PCP request. The C ABI signature (per {@code tpipe-abi.h}) is:
     * <pre>
     *   int TPipe_PCPHandle_execute(
     *       graal_isolatethread_t* thread,
     *       TPipe_PCPHandle pcp,
     *       const char* requestJson,
     *       char* responseJson,
     *       int responseJsonSize);
     * </pre>
     * The {@code requestJson} string must be a JSON object containing
     * {@code "function"} and {@code "parameters"} fields:
     * <pre>{"function":"my_fn","parameters":{"input":"hello"}}</pre>
     * On success, the response JSON is written to {@code responseJson} (with
     * a trailing null terminator). The function return value is the number of
     * bytes written (excluding the null terminator), or a negative
     * TPIPE_ERR_* on failure.
     */
    @CEntryPoint(name = "TPipe_PCPHandle_execute")
    public static int pcpHandleExecute(IsolateThread thread, long pcp, long requestJson, long responseJson, int responseJsonSize) {
        int rc = requireReady(); if (rc != TPIPE_OK) return rc;
        String req = readCString(requestJson);
        if (req == null) return TPIPE_ERR_INVALID_ARGUMENT;
        // Parse {"function":"...","parameters":{...}} into a Pair<String, String>
        String[] parsed = parsePcpRequestJson(req);
        if (parsed == null) return TPIPE_ERR_INVALID_ARGUMENT;
        String result = NativeBridge.pcpExecute(pcp, parsed[0], parsed[1]);
        if (result == null) return TPIPE_ERR_INTERNAL;
        return writeCString(responseJson, responseJsonSize, result);
    }

    /**
     * Parse the C-ABI PCP request envelope. The shape is
     * {@code {"function":"<name>","parameters":<json>}}. Returns
     * {@code [functionName, parametersJson]} on success, or null on parse
     * failure.
     */
    private static String[] parsePcpRequestJson(String req) {
        // Minimal hand-rolled parser. The parameters sub-document is copied
        // verbatim into the resulting array; the function name is the
        // string value of the "function" key.
        String s = req.trim();
        if (!s.startsWith("{") || !s.endsWith("}")) return null;
        String body = s.substring(1, s.length() - 1).trim();
        // Split on the first top-level comma that separates "function"
        // from "parameters".
        int comma = findTopLevelComma(body);
        if (comma < 0) return null;
        String fnPart = body.substring(0, comma).trim();
        String paramsPart = body.substring(comma + 1).trim();
        String functionName = extractJsonStringValue(fnPart, "function");
        if (functionName == null) return null;
        // parameters may be any JSON literal (object, array, scalar).
        // We pass it through to the JVM-side parser as-is, omitting the
        // trailing comma if any.
        String parametersJson = paramsPart;
        int colon = paramsPart.indexOf(':');
        if (colon >= 0) parametersJson = paramsPart.substring(colon + 1).trim();
        return new String[] { functionName, parametersJson };
    }

    /**
     * Locate the top-level comma separating two object fields. A
     * naive bracket-aware scan is sufficient because parameter JSON
     * values are nested one level deep at most in practice.
     */
    private static int findTopLevelComma(String s) {
        int depth = 0;
        boolean inString = false;
        boolean escape = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (escape) { escape = false; continue; }
            if (inString) {
                if (c == '\\') { escape = true; continue; }
                if (c == '"') inString = false;
                continue;
            }
            if (c == '"') { inString = true; continue; }
            if (c == '{' || c == '[') depth++;
            else if (c == '}' || c == ']') depth--;
            else if (c == ',' && depth == 0) return i;
        }
        return -1;
    }

    /**
     * Pull the string value of {@code key} from a fragment of the form
     * {@code "<key>":"<value>"}. Returns null if the fragment does not
     * start with that key.
     */
    private static String extractJsonStringValue(String fragment, String key) {
        String prefix = "\"" + key + "\"";
        if (!fragment.startsWith(prefix)) return null;
        int colon = fragment.indexOf(':', prefix.length());
        if (colon < 0) return null;
        int q1 = fragment.indexOf('"', colon + 1);
        if (q1 < 0) return null;
        int q2 = findClosingQuote(fragment, q1 + 1);
        if (q2 < 0) return null;
        return fragment.substring(q1 + 1, q2);
    }

    private static int findClosingQuote(String s, int from) {
        for (int i = from; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\') { i++; continue; }
            if (c == '"') return i;
        }
        return -1;
    }

    //====================================================================
    // P2P API
    //====================================================================

    @CEntryPoint(name = "TPipe_P2PHandle_create")
    public static long p2pHandleCreate(IsolateThread thread) {
        int rc = requireReady(); if (rc != TPIPE_OK) return 0L;
        return NativeBridge.p2pCreate();
    }

    /**
     * Register an agent with P2P. The C ABI signature (per
     * {@code tpipe-abi.h}) is:
     * <pre>
     *   int TPipe_P2PHandle_registerAgent(
     *       graal_isolatethread_t* thread,
     *       TPipe_P2PHandle p2p,
     *       const char* agentId,
     *       const char* metadata);
     * </pre>
     * The {@code metadata} parameter is a JSON document (may be NULL) that
     * is stored alongside the agent registration.
     */
    @CEntryPoint(name = "TPipe_P2PHandle_registerAgent")
    public static int p2pHandleRegisterAgent(IsolateThread thread, long p2p, long agentId, long metadata) {
        int rc = requireReady(); if (rc != TPIPE_OK) return rc;
        String name = readCString(agentId);
        if (name == null) return TPIPE_ERR_INVALID_ARGUMENT;
        String meta = readCString(metadata); // may be null
        return NativeBridge.p2pRegisterAgent(p2p, name, meta);
    }

    @CEntryPoint(name = "TPipe_P2PHandle_connect")
    public static int p2pHandleConnect(IsolateThread thread, long p2p, long remoteAddress) {
        int rc = requireReady(); if (rc != TPIPE_OK) return rc;
        String addr = readCString(remoteAddress);
        if (addr == null) return TPIPE_ERR_INVALID_ARGUMENT;
        return NativeBridge.p2pConnect(p2p, addr);
    }

    /**
     * Send a message to a peer. The C ABI signature (per {@code tpipe-abi.h})
     * is:
     * <pre>
     *   int TPipe_P2PHandle_send(
     *       graal_isolatethread_t* thread,
     *       TPipe_P2PHandle p2p,
     *       const char* peerId,
     *       TPipe_ContentHandle message,
     *       TPipe_ContentHandle* response);
     * </pre>
     * On success, the response content handle is written to {@code *response}
     * (or 0 if the peer produced no response content).
     */
    @CEntryPoint(name = "TPipe_P2PHandle_send")
    public static int p2pHandleSend(IsolateThread thread, long p2p, long peerId, long message, long responseOut) {
        int rc = requireReady(); if (rc != TPIPE_OK) return rc;
        String agent = readCString(peerId);
        if (agent == null) return TPIPE_ERR_INVALID_ARGUMENT;
        if (message == 0L) return TPIPE_ERR_INVALID_ARGUMENT;
        long resp = NativeBridge.p2pSend(p2p, agent, message);
        if (responseOut != 0L) writePtr(responseOut, 0, resp);
        return TPIPE_OK;
    }

    //====================================================================
    // List API
    //====================================================================

    @CEntryPoint(name = "TPipe_List_create")
    public static long listCreate(IsolateThread thread) {
        int rc = requireReady(); if (rc != TPIPE_OK) return 0L;
        return NativeBridge.listCreate();
    }

    @CEntryPoint(name = "TPipe_List_append")
    public static int listAppend(IsolateThread thread, long list, long item) {
        int rc = requireReady(); if (rc != TPIPE_OK) return rc;
        return NativeBridge.listAppend(list, item);
    }

    @CEntryPoint(name = "TPipe_List_get")
    public static int listGet(IsolateThread thread, long list, int index, long item) {
        int rc = requireReady(); if (rc != TPIPE_OK) return rc;
        Long h = NativeBridge.listGet(list, index);
        if (h == null) return TPIPE_ERR_INVALID_ARGUMENT;
        return writePtr(item, 0, h);
    }

    @CEntryPoint(name = "TPipe_List_size")
    public static int listSize(IsolateThread thread, long list) {
        int rc = requireReady(); if (rc != TPIPE_OK) return rc;
        int sz = NativeBridge.listSize(list);
        return sz < 0 ? sz : sz;
    }

    //====================================================================
    // Map API
    //====================================================================

    @CEntryPoint(name = "TPipe_Map_create")
    public static long mapCreate(IsolateThread thread) {
        int rc = requireReady(); if (rc != TPIPE_OK) return 0L;
        return NativeBridge.mapCreate();
    }

    @CEntryPoint(name = "TPipe_Map_set")
    public static int mapSet(IsolateThread thread, long map, long key, long value) {
        int rc = requireReady(); if (rc != TPIPE_OK) return rc;
        String k = readCString(key);
        if (k == null) return TPIPE_ERR_INVALID_ARGUMENT;
        return NativeBridge.mapSet(map, k, value);
    }

    @CEntryPoint(name = "TPipe_Map_get")
    public static int mapGet(IsolateThread thread, long map, long key, long value) {
        int rc = requireReady(); if (rc != TPIPE_OK) return rc;
        String k = readCString(key);
        if (k == null) return TPIPE_ERR_INVALID_ARGUMENT;
        Long v = NativeBridge.mapGet(map, k);
        if (v == null) return TPIPE_ERR_INVALID_ARGUMENT;
        return writePtr(value, 0, v);
    }

    @CEntryPoint(name = "TPipe_Map_size")
    public static int mapSize(IsolateThread thread, long map) {
        int rc = requireReady(); if (rc != TPIPE_OK) return rc;
        int sz = NativeBridge.mapSize(map);
        return sz < 0 ? sz : sz;
    }

    @CEntryPoint(name = "TPipe_Map_has")
    public static int mapHas(IsolateThread thread, long map, long key, long has) {
        int rc = requireReady(); if (rc != TPIPE_OK) return rc;
        String k = readCString(key);
        if (k == null) return TPIPE_ERR_INVALID_ARGUMENT;
        if (has == 0L) return TPIPE_ERR_NULL_POINTER;
        int[] tmp = new int[1];
        int code = NativeBridge.mapHas(map, k, tmp);
        if (code != TPIPE_OK) return code;
        return writeInt(has, 0, tmp[0]);
    }

    //====================================================================
    // Async API
    //====================================================================

    @CEntryPoint(name = "TPipe_AsyncHandle_create")
    public static long asyncHandleCreate(IsolateThread thread) {
        int rc = requireReady(); if (rc != TPIPE_OK) return 0L;
        return NativeBridge.asyncCreate();
    }

    @CEntryPoint(name = "TPipe_AsyncHandle_cancel")
    public static int asyncHandleCancel(IsolateThread thread, long handle) {
        int rc = requireReady(); if (rc != TPIPE_OK) return rc;
        return NativeBridge.asyncCancel(handle);
    }

    @CEntryPoint(name = "TPipe_AsyncHandle_isDone")
    public static int asyncHandleIsDone(IsolateThread thread, long handle) {
        return NativeBridge.asyncIsDone(handle) ? 1 : 0;
    }

    @CEntryPoint(name = "TPipe_AsyncHandle_wait")
    public static int asyncHandleWait(IsolateThread thread, long handle, int timeoutMs) {
        int rc = requireReady(); if (rc != TPIPE_OK) return rc;
        return NativeBridge.asyncWait(handle, timeoutMs);
    }

    @CEntryPoint(name = "TPipe_AsyncHandle_poll")
    public static int asyncHandlePoll(IsolateThread thread, long handle, long status) {
        int rc = requireReady(); if (rc != TPIPE_OK) return rc;
        if (status == 0L) return TPIPE_ERR_NULL_POINTER;
        int[] tmp = new int[1];
        int code = NativeBridge.asyncPoll(handle, tmp);
        if (code != TPIPE_OK) return code;
        return writeInt(status, 0, tmp[0]);
    }

    @CEntryPoint(name = "TPipe_AsyncHandle_getResult")
    public static int asyncHandleGetResult(IsolateThread thread, long handle, long result) {
        int rc = requireReady(); if (rc != TPIPE_OK) return rc;
        if (result == 0L) return TPIPE_ERR_NULL_POINTER;
        long[] tmp = new long[1];
        int code = NativeBridge.asyncGetResult(handle, tmp);
        if (code != TPIPE_OK) return code;
        return writePtr(result, 0, tmp[0]);
    }

    //====================================================================
    // Manifold API
    //====================================================================

    @CEntryPoint(name = "TPipe_Manifold_create")
    public static long manifoldCreate(IsolateThread thread) {
        int rc = requireReady(); if (rc != TPIPE_OK) return 0L;
        return NativeBridge.manifoldCreate();
    }

    @CEntryPoint(name = "TPipe_Manifold_release")
    public static int manifoldRelease(IsolateThread thread, long manifold) {
        int rc = requireReady(); if (rc != TPIPE_OK) return rc;
        if (HandleRegistry.INSTANCE.getType(manifold) != HandleTypes.MANIFOLD) return TPIPE_ERR_INVALID_HANDLE;
        return NativeBridge.manifoldRelease(manifold);
    }

    @CEntryPoint(name = "TPipe_Manifold_init")
    public static int manifoldInit(IsolateThread thread, long manifold) {
        int rc = requireReady(); if (rc != TPIPE_OK) return rc;
        return NativeBridge.manifoldInit(manifold);
    }

    @CEntryPoint(name = "TPipe_Manifold_execute")
    public static long manifoldExecute(IsolateThread thread, long manifold, long content) {
        int rc = requireReady(); if (rc != TPIPE_OK) return 0L;
        return NativeBridge.manifoldExecute(manifold, content);
    }

    @CEntryPoint(name = "TPipe_Manifold_addWorker")
    public static int manifoldAddWorker(IsolateThread thread, long manifold, long name, long pipe) {
        int rc = requireReady(); if (rc != TPIPE_OK) return rc;
        String n = readCString(name);
        if (n == null) return TPIPE_ERR_INVALID_ARGUMENT;
        return NativeBridge.manifoldAddWorker(manifold, n, pipe);
    }

    @CEntryPoint(name = "TPipe_Manifold_getWorkerCount")
    public static int manifoldGetWorkerCount(IsolateThread thread, long manifold, long count) {
        int rc = requireReady(); if (rc != TPIPE_OK) return rc;
        int c = NativeBridge.manifoldGetWorkerCount(manifold);
        if (c < 0) return c;
        return writeInt(count, 0, c);
    }

    @CEntryPoint(name = "TPipe_Manifold_setMaxLoopIterations")
    public static int manifoldSetMaxLoopIterations(IsolateThread thread, long manifold, int limit) {
        int rc = requireReady(); if (rc != TPIPE_OK) return rc;
        return NativeBridge.manifoldSetMaxLoopIterations(manifold, limit);
    }

    @CEntryPoint(name = "TPipe_Manifold_serialize")
    public static int manifoldSerialize(IsolateThread thread, long manifold, long buffer, int bufferSize) {
        int rc = requireReady(); if (rc != TPIPE_OK) return rc;
        // Defensive null/size checks on the output buffer — Phase 7 audit
        // found that this entry point previously inlined UNSAFE.putByte
        // calls without a null-check on `buffer`, which would segfault if
        // a C caller passed 0. Use the same copyToNativeBuffer helper as
        // the other serialize methods (distributionGrid, junction,
        // connector, splitter) so the safety guards are uniform.
        if (buffer == 0L) return TPIPE_ERR_NULL_POINTER;
        if (bufferSize <= 0) return TPIPE_ERR_INVALID_ARGUMENT;
        byte[] tmp = new byte[bufferSize];
        int n = NativeBridge.manifoldSerialize(manifold, tmp, 0, bufferSize - 1);
        if (n < 0) return n;
        return copyToNativeBuffer(buffer, bufferSize, tmp, n);
    }

    //====================================================================
    // Manifold Configuration API (Cycle 3)
    //====================================================================

    @CEntryPoint(name = "TPipe_Manifold_setContextWindowSize")
    public static int manifoldSetContextWindowSize(IsolateThread thread, long manifold, int size) {
        int rc = requireReady(); if (rc != TPIPE_OK) return rc;
        return NativeBridge.manifoldSetContextWindowSize(manifold, size);
    }

    @CEntryPoint(name = "TPipe_Manifold_getContextWindowSize")
    public static int manifoldGetContextWindowSize(IsolateThread thread, long manifold, long outSize) {
        int rc = requireReady(); if (rc != TPIPE_OK) return rc;
        int s = NativeBridge.manifoldGetContextWindowSize(manifold);
        if (s < 0) return s;
        return writeInt(outSize, 0, s);
    }

    @CEntryPoint(name = "TPipe_Manifold_setTruncationMethod")
    public static int manifoldSetTruncationMethod(IsolateThread thread, long manifold, int method) {
        int rc = requireReady(); if (rc != TPIPE_OK) return rc;
        return NativeBridge.manifoldSetTruncationMethod(manifold, method);
    }

    @CEntryPoint(name = "TPipe_Manifold_getTruncationMethod")
    public static int manifoldGetTruncationMethod(IsolateThread thread, long manifold, long outMethod) {
        int rc = requireReady(); if (rc != TPIPE_OK) return rc;
        int m = NativeBridge.manifoldGetTruncationMethod(manifold);
        if (m < 0) return m;
        return writeInt(outMethod, 0, m);
    }

    @CEntryPoint(name = "TPipe_Manifold_setSummaryMode")
    public static int manifoldSetSummaryMode(IsolateThread thread, long manifold, int mode) {
        int rc = requireReady(); if (rc != TPIPE_OK) return rc;
        return NativeBridge.manifoldSetSummaryMode(manifold, mode);
    }

    @CEntryPoint(name = "TPipe_Manifold_getSummaryMode")
    public static int manifoldGetSummaryMode(IsolateThread thread, long manifold, long outMode) {
        int rc = requireReady(); if (rc != TPIPE_OK) return rc;
        int m = NativeBridge.manifoldGetSummaryMode(manifold);
        if (m < 0) return m;
        return writeInt(outMode, 0, m);
    }

    @CEntryPoint(name = "TPipe_Manifold_getMaxLoopIterations")
    public static int manifoldGetMaxLoopIterations(IsolateThread thread, long manifold, long outLimit) {
        int rc = requireReady(); if (rc != TPIPE_OK) return rc;
        int l = NativeBridge.manifoldGetMaxLoopIterations(manifold);
        if (l < 0) return l;
        return writeInt(outLimit, 0, l);
    }

    @CEntryPoint(name = "TPipe_Manifold_hasLoopLimit")
    public static int manifoldHasLoopLimit(IsolateThread thread, long manifold, long outHasLimit) {
        int rc = requireReady(); if (rc != TPIPE_OK) return rc;
        int h = NativeBridge.manifoldHasLoopLimit(manifold);
        if (h < 0) return h;
        return writeInt(outHasLimit, 0, h);
    }

    @CEntryPoint(name = "TPipe_Manifold_getWorkerPipelines")
    public static int manifoldGetWorkerPipelines(IsolateThread thread, long manifold, long buffer, int bufferSize) {
        int rc = requireReady(); if (rc != TPIPE_OK) return rc;
        if (buffer == 0L) return TPIPE_ERR_NULL_POINTER;
        if (bufferSize <= 0) return TPIPE_ERR_INVALID_ARGUMENT;
        byte[] tmp = new byte[bufferSize];
        int n = NativeBridge.manifoldGetWorkerPipelines(manifold, tmp, 0, bufferSize - 1);
        if (n < 0) return n;
        return copyToNativeBuffer(buffer, bufferSize, tmp, n);
    }

    @CEntryPoint(name = "TPipe_Manifold_setManagerTokenBudget")
    public static int manifoldSetManagerTokenBudget(IsolateThread thread, long manifold, int budget) {
        int rc = requireReady(); if (rc != TPIPE_OK) return rc;
        return NativeBridge.manifoldSetManagerTokenBudget(manifold, budget);
    }

    @CEntryPoint(name = "TPipe_Manifold_getManagerTokenBudget")
    public static int manifoldGetManagerTokenBudget(IsolateThread thread, long manifold, long outBudget) {
        int rc = requireReady(); if (rc != TPIPE_OK) return rc;
        int b = NativeBridge.manifoldGetManagerTokenBudget(manifold);
        if (b < 0) return b;
        return writeInt(outBudget, 0, b);
    }

    @CEntryPoint(name = "TPipe_Manifold_getManagerPipeline")
    public static int manifoldGetManagerPipeline(IsolateThread thread, long manifold, long outHasManager) {
        int rc = requireReady(); if (rc != TPIPE_OK) return rc;
        int h = NativeBridge.manifoldGetManagerPipeline(manifold);
        if (h < 0) return h;
        return writeInt(outHasManager, 0, h);
    }

    //====================================================================
    // DistributionGrid API (Phase 11 — stub-level handle exposure)
    //====================================================================

    @CEntryPoint(name = "TPipe_DistributionGrid_create")
    public static long distributionGridCreate(IsolateThread thread) {
        int rc = requireReady(); if (rc != TPIPE_OK) return 0L;
        return NativeBridge.distributionGridCreate();
    }

    @CEntryPoint(name = "TPipe_DistributionGrid_release")
    public static int distributionGridRelease(IsolateThread thread, long grid) {
        int rc = requireReady(); if (rc != TPIPE_OK) return rc;
        if (HandleRegistry.INSTANCE.getType(grid) != HandleTypes.DISTRIBUTION_GRID) return TPIPE_ERR_INVALID_HANDLE;
        return NativeBridge.distributionGridRelease(grid);
    }

    @CEntryPoint(name = "TPipe_DistributionGrid_getNodeCount")
    public static int distributionGridGetNodeCount(IsolateThread thread, long grid, long count) {
        int rc = requireReady(); if (rc != TPIPE_OK) return rc;
        int c = NativeBridge.distributionGridGetNodeCount(grid);
        if (c < 0) return c;
        return writeInt(count, 0, c);
    }

    @CEntryPoint(name = "TPipe_DistributionGrid_serialize")
    public static int distributionGridSerialize(IsolateThread thread, long grid, long buffer, int bufferSize) {
        int rc = requireReady(); if (rc != TPIPE_OK) return rc;
        byte[] tmp = new byte[Math.max(1, bufferSize)];
        int n = NativeBridge.distributionGridSerialize(grid, tmp, 0, bufferSize > 0 ? bufferSize - 1 : 0);
        if (n < 0) return n;
        return copyToNativeBuffer(buffer, bufferSize, tmp, n);
    }

    @CEntryPoint(name = "TPipe_DistributionGrid_getHealth")
    public static int distributionGridGetHealth(IsolateThread thread, long grid, long buffer, int bufferSize) {
        int rc = requireReady(); if (rc != TPIPE_OK) return rc;
        byte[] tmp = new byte[Math.max(1, bufferSize)];
        int n = NativeBridge.distributionGridGetHealth(grid, tmp, 0, bufferSize > 0 ? bufferSize - 1 : 0);
        if (n < 0) return n;
        return copyToNativeBuffer(buffer, bufferSize, tmp, n);
    }

    @CEntryPoint(name = "TPipe_DistributionGrid_rebalance_stub")
    public static int distributionGridRebalanceStub(IsolateThread thread, long grid, long buffer, int bufferSize) {
        int rc = requireReady(); if (rc != TPIPE_OK) return rc;
        byte[] tmp = new byte[Math.max(1, bufferSize)];
        int n = NativeBridge.distributionGridRebalanceStub(grid, tmp, 0, bufferSize > 0 ? bufferSize - 1 : 0);
        if (n < 0) return n;
        return copyToNativeBuffer(buffer, bufferSize, tmp, n);
    }

    //====================================================================
    // Cycle 8 — DistributionGrid configuration surface
    //====================================================================

    @CEntryPoint(name = "TPipe_DistributionGrid_setMaxHops")
    public static int distributionGridSetMaxHops(IsolateThread thread, long grid, int max) {
        int rc = requireReady(); if (rc != TPIPE_OK) return rc;
        return NativeBridge.distributionGridSetMaxHops(grid, max);
    }

    @CEntryPoint(name = "TPipe_DistributionGrid_getMaxHops")
    public static int distributionGridGetMaxHops(IsolateThread thread, long grid, long outMax) {
        int rc = requireReady(); if (rc != TPIPE_OK) return rc;
        int v = NativeBridge.distributionGridGetMaxHops(grid);
        if (v < 0) return v;
        return writeInt(outMax, 0, v);
    }

    @CEntryPoint(name = "TPipe_DistributionGrid_setRpcTimeout")
    public static int distributionGridSetRpcTimeout(IsolateThread thread, long grid, long millis) {
        int rc = requireReady(); if (rc != TPIPE_OK) return rc;
        return NativeBridge.distributionGridSetRpcTimeout(grid, millis);
    }

    @CEntryPoint(name = "TPipe_DistributionGrid_getRpcTimeout")
    public static int distributionGridGetRpcTimeout(IsolateThread thread, long grid, long outMillis) {
        int rc = requireReady(); if (rc != TPIPE_OK) return rc;
        long v = NativeBridge.distributionGridGetRpcTimeout(grid);
        if (v < 0) return (int) v;
        return writePtr(outMillis, 0, v);
    }

    @CEntryPoint(name = "TPipe_DistributionGrid_setMaxSessionDuration")
    public static int distributionGridSetMaxSessionDuration(IsolateThread thread, long grid, int seconds) {
        int rc = requireReady(); if (rc != TPIPE_OK) return rc;
        return NativeBridge.distributionGridSetMaxSessionDuration(grid, seconds);
    }

    @CEntryPoint(name = "TPipe_DistributionGrid_getMaxSessionDuration")
    public static int distributionGridGetMaxSessionDuration(IsolateThread thread, long grid, long outSeconds) {
        int rc = requireReady(); if (rc != TPIPE_OK) return rc;
        int v = NativeBridge.distributionGridGetMaxSessionDuration(grid);
        if (v < 0) return v;
        return writeInt(outSeconds, 0, v);
    }

    @CEntryPoint(name = "TPipe_DistributionGrid_setDiscoveryMode")
    public static int distributionGridSetDiscoveryMode(IsolateThread thread, long grid, int mode) {
        int rc = requireReady(); if (rc != TPIPE_OK) return rc;
        return NativeBridge.distributionGridSetDiscoveryMode(grid, mode);
    }

    @CEntryPoint(name = "TPipe_DistributionGrid_getDiscoveryMode")
    public static int distributionGridGetDiscoveryMode(IsolateThread thread, long grid, long outMode) {
        int rc = requireReady(); if (rc != TPIPE_OK) return rc;
        int v = NativeBridge.distributionGridGetDiscoveryMode(grid);
        if (v < 0) return v;
        return writeInt(outMode, 0, v);
    }

    @CEntryPoint(name = "TPipe_DistributionGrid_pause")
    public static int distributionGridPause(IsolateThread thread, long grid) {
        int rc = requireReady(); if (rc != TPIPE_OK) return rc;
        return NativeBridge.distributionGridPause(grid);
    }

    @CEntryPoint(name = "TPipe_DistributionGrid_isPaused")
    public static int distributionGridIsPaused(IsolateThread thread, long grid, long outPaused) {
        int rc = requireReady(); if (rc != TPIPE_OK) return rc;
        int v = NativeBridge.distributionGridIsPaused(grid);
        if (v < 0) return v;
        return writeInt(outPaused, 0, v);
    }

    // ---- Phase 6: additional read-only entry points ----

    /**
     * Write the grid's node count into the caller's int*. The
     * `out` parameter is an int* (32-bit). Returns 0 on success,
     * a negative error code on failure.
     */
    @CEntryPoint(name = "TPipe_DistributionGrid_getNodeCount_v2")
    public static int distributionGridGetNodeCountV2(IsolateThread thread, long grid, long out) {
        int rc = requireReady(); if (rc != TPIPE_OK) return rc;
        if (out == 0L) return TPIPE_ERR_NULL_POINTER;
        int count = NativeBridge.distributionGridGetNodeCount(grid);
        if (count < 0) return count;
        return writeInt(out, 4, count);
    }

    /**
     * Write the grid's status JSON into the caller's char* buffer.
     * Returns 0 on success, a negative error code on failure.
     */
    @CEntryPoint(name = "TPipe_DistributionGrid_getStatusJson")
    public static int distributionGridGetStatusJson(IsolateThread thread, long grid, long buffer, int bufferSize) {
        int rc = requireReady(); if (rc != TPIPE_OK) return rc;
        if (buffer == 0L) return TPIPE_ERR_NULL_POINTER;
        Object data = HandleRegistry.INSTANCE.getData(grid);
        if (!(data instanceof DistributionGridHandle)) return TPIPE_ERR_INVALID_HANDLE;
        String json = ((DistributionGridHandle) data).serialize();
        if (json == null || json.isEmpty()) return TPIPE_ERR_INVALID_HANDLE;
        byte[] bytes = json.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        int n = Math.min(bytes.length, bufferSize > 0 ? bufferSize - 1 : 0);
        return copyToNativeBuffer(buffer, bufferSize, bytes, n);
    }

    /**
     * Write the timestamp (ms since epoch) of the most recent
     * rebalance into the caller's int64_t*. Returns 0 on success.
     */
    @CEntryPoint(name = "TPipe_DistributionGrid_getLastRebalanceMs")
    public static int distributionGridGetLastRebalanceMs(IsolateThread thread, long grid, long out) {
        int rc = requireReady(); if (rc != TPIPE_OK) return rc;
        if (out == 0L) return TPIPE_ERR_NULL_POINTER;
        long ts = NativeBridge.distributionGridGetLastRebalanceMs(grid);
        if (ts < 0) return (int) ts;
        return writePtr(out, 8, ts);
    }

    //====================================================================
    // Junction API (Phase 12 — discussion harness C ABI surface)
    //====================================================================

    @CEntryPoint(name = "TPipe_Junction_create")
    public static long junctionCreate(IsolateThread thread) {
        int rc = requireReady(); if (rc != TPIPE_OK) return 0L;
        return NativeBridge.junctionCreate();
    }

    @CEntryPoint(name = "TPipe_Junction_release")
    public static int junctionRelease(IsolateThread thread, long junction) {
        int rc = requireReady(); if (rc != TPIPE_OK) return rc;
        if (HandleRegistry.INSTANCE.getType(junction) != HandleTypes.JUNCTION) return TPIPE_ERR_INVALID_HANDLE;
        return NativeBridge.junctionRelease(junction);
    }

    @CEntryPoint(name = "TPipe_Junction_init")
    public static int junctionInit(IsolateThread thread, long junction) {
        int rc = requireReady(); if (rc != TPIPE_OK) return rc;
        return NativeBridge.junctionInit(junction);
    }

    @CEntryPoint(name = "TPipe_Junction_execute")
    public static long junctionExecute(IsolateThread thread, long junction, long content) {
        int rc = requireReady(); if (rc != TPIPE_OK) return 0L;
        return NativeBridge.junctionExecute(junction, content);
    }

    @CEntryPoint(name = "TPipe_Junction_serialize")
    public static int junctionSerialize(IsolateThread thread, long junction, long buffer, int bufferSize) {
        int rc = requireReady(); if (rc != TPIPE_OK) return rc;
        byte[] tmp = new byte[Math.max(1, bufferSize)];
        int n = NativeBridge.junctionSerialize(junction, tmp, 0, bufferSize > 0 ? bufferSize - 1 : 0);
        if (n < 0) return n;
        return copyToNativeBuffer(buffer, bufferSize, tmp, n);
    }

    //====================================================================
    // Junction Configuration API (Cycle 3)
    //====================================================================

    @CEntryPoint(name = "TPipe_Junction_setStrategy")
    public static int junctionSetStrategy(IsolateThread thread, long junction, int strategy) {
        int rc = requireReady(); if (rc != TPIPE_OK) return rc;
        return NativeBridge.junctionSetStrategy(junction, strategy);
    }

    @CEntryPoint(name = "TPipe_Junction_getStrategy")
    public static int junctionGetStrategy(IsolateThread thread, long junction, long outStrategy) {
        int rc = requireReady(); if (rc != TPIPE_OK) return rc;
        int s = NativeBridge.junctionGetStrategy(junction);
        if (s < 0) return s;
        return writeInt(outStrategy, 0, s);
    }

    @CEntryPoint(name = "TPipe_Junction_setRounds")
    public static int junctionSetRounds(IsolateThread thread, long junction, int rounds) {
        int rc = requireReady(); if (rc != TPIPE_OK) return rc;
        return NativeBridge.junctionSetRounds(junction, rounds);
    }

    @CEntryPoint(name = "TPipe_Junction_getRounds")
    public static int junctionGetRounds(IsolateThread thread, long junction, long outRounds) {
        int rc = requireReady(); if (rc != TPIPE_OK) return rc;
        int r = NativeBridge.junctionGetRounds(junction);
        if (r < 0) return r;
        return writeInt(outRounds, 0, r);
    }

    @CEntryPoint(name = "TPipe_Junction_setVotingThreshold")
    public static int junctionSetVotingThreshold(IsolateThread thread, long junction, long thresholdBits) {
        int rc = requireReady(); if (rc != TPIPE_OK) return rc;
        return NativeBridge.junctionSetVotingThreshold(junction, thresholdBits);
    }

    @CEntryPoint(name = "TPipe_Junction_getVotingThreshold")
    public static int junctionGetVotingThreshold(IsolateThread thread, long junction, long outThreshold) {
        int rc = requireReady(); if (rc != TPIPE_OK) return rc;
        long t = NativeBridge.junctionGetVotingThreshold(junction);
        if (t < 0) return (int) t;
        return writePtr(outThreshold, 0, t);
    }

    @CEntryPoint(name = "TPipe_Junction_setMaxNestedDepth")
    public static int junctionSetMaxNestedDepth(IsolateThread thread, long junction, int depth) {
        int rc = requireReady(); if (rc != TPIPE_OK) return rc;
        return NativeBridge.junctionSetMaxNestedDepth(junction, depth);
    }

    @CEntryPoint(name = "TPipe_Junction_getMaxNestedDepth")
    public static int junctionGetMaxNestedDepth(IsolateThread thread, long junction, long outDepth) {
        int rc = requireReady(); if (rc != TPIPE_OK) return rc;
        int d = NativeBridge.junctionGetMaxNestedDepth(junction);
        if (d < 0) return d;
        return writeInt(outDepth, 0, d);
    }

    @CEntryPoint(name = "TPipe_Junction_setWorkflowRecipe")
    public static int junctionSetWorkflowRecipe(IsolateThread thread, long junction, int recipe) {
        int rc = requireReady(); if (rc != TPIPE_OK) return rc;
        return NativeBridge.junctionSetWorkflowRecipe(junction, recipe);
    }

    @CEntryPoint(name = "TPipe_Junction_getWorkflowRecipe")
    public static int junctionGetWorkflowRecipe(IsolateThread thread, long junction, long outRecipe) {
        int rc = requireReady(); if (rc != TPIPE_OK) return rc;
        int r = NativeBridge.junctionGetWorkflowRecipe(junction);
        if (r < 0) return r;
        return writeInt(outRecipe, 0, r);
    }

    @CEntryPoint(name = "TPipe_Junction_setMemoryPolicy")
    public static int junctionSetMemoryPolicy(IsolateThread thread, long junction, int outboundBudget, int summaryBudget) {
        int rc = requireReady(); if (rc != TPIPE_OK) return rc;
        return NativeBridge.junctionSetMemoryPolicy(junction, outboundBudget, summaryBudget);
    }

    @CEntryPoint(name = "TPipe_Junction_getMemoryPolicy")
    public static int junctionGetMemoryPolicy(IsolateThread thread, long junction, long outBudget) {
        int rc = requireReady(); if (rc != TPIPE_OK) return rc;
        int b = NativeBridge.junctionGetMemoryPolicy(junction);
        if (b < 0) return b;
        return writeInt(outBudget, 0, b);
    }

    @CEntryPoint(name = "TPipe_Junction_getMemoryPolicyEx")
    public static int junctionGetMemoryPolicyEx(IsolateThread thread, long junction, long outCombined) {
        int rc = requireReady(); if (rc != TPIPE_OK) return rc;
        long v = NativeBridge.junctionGetMemoryPolicyEx(junction);
        if (v < 0) return (int) v;
        return writePtr(outCombined, 0, v);
    }

    @CEntryPoint(name = "TPipe_Junction_enableTracing")
    public static int junctionEnableTracing(IsolateThread thread, long junction) {
        int rc = requireReady(); if (rc != TPIPE_OK) return rc;
        return NativeBridge.junctionEnableTracing(junction);
    }

    @CEntryPoint(name = "TPipe_Junction_disableTracing")
    public static int junctionDisableTracing(IsolateThread thread, long junction) {
        int rc = requireReady(); if (rc != TPIPE_OK) return rc;
        return NativeBridge.junctionDisableTracing(junction);
    }

    @CEntryPoint(name = "TPipe_Junction_getTraceId")
    public static int junctionGetTraceId(IsolateThread thread, long junction, long buffer, int bufferSize) {
        int rc = requireReady(); if (rc != TPIPE_OK) return rc;
        if (buffer == 0L) return TPIPE_ERR_NULL_POINTER;
        if (bufferSize <= 0) return TPIPE_ERR_INVALID_ARGUMENT;
        byte[] tmp = new byte[bufferSize];
        int n = NativeBridge.junctionGetTraceId(junction, tmp, 0, bufferSize - 1);
        if (n < 0) return n;
        return copyToNativeBuffer(buffer, bufferSize, tmp, n);
    }

    @CEntryPoint(name = "TPipe_Junction_getFailureAnalysis")
    public static int junctionGetFailureAnalysis(IsolateThread thread, long junction, long buffer, int bufferSize) {
        int rc = requireReady(); if (rc != TPIPE_OK) return rc;
        if (buffer == 0L) return TPIPE_ERR_NULL_POINTER;
        if (bufferSize <= 0) return TPIPE_ERR_INVALID_ARGUMENT;
        byte[] tmp = new byte[bufferSize];
        int n = NativeBridge.junctionGetFailureAnalysis(junction, tmp, 0, bufferSize - 1);
        if (n < 0) return n;
        return copyToNativeBuffer(buffer, bufferSize, tmp, n);
    }

    //====================================================================
    // Connector API (Phase 12 — branching container C ABI surface)
    //====================================================================

    @CEntryPoint(name = "TPipe_Connector_create")
    public static long connectorCreate(IsolateThread thread) {
        int rc = requireReady(); if (rc != TPIPE_OK) return 0L;
        return NativeBridge.connectorCreate();
    }

    @CEntryPoint(name = "TPipe_Connector_release")
    public static int connectorRelease(IsolateThread thread, long connector) {
        int rc = requireReady(); if (rc != TPIPE_OK) return rc;
        if (HandleRegistry.INSTANCE.getType(connector) != HandleTypes.CONNECTOR) return TPIPE_ERR_INVALID_HANDLE;
        return NativeBridge.connectorRelease(connector);
    }

    @CEntryPoint(name = "TPipe_Connector_init")
    public static int connectorInit(IsolateThread thread, long connector) {
        int rc = requireReady(); if (rc != TPIPE_OK) return rc;
        return NativeBridge.connectorInit(connector);
    }

    @CEntryPoint(name = "TPipe_Connector_execute")
    public static long connectorExecute(IsolateThread thread, long connector, long content) {
        int rc = requireReady(); if (rc != TPIPE_OK) return 0L;
        return NativeBridge.connectorExecute(connector, content);
    }

    @CEntryPoint(name = "TPipe_Connector_serialize")
    public static int connectorSerialize(IsolateThread thread, long connector, long buffer, int bufferSize) {
        int rc = requireReady(); if (rc != TPIPE_OK) return rc;
        byte[] tmp = new byte[Math.max(1, bufferSize)];
        int n = NativeBridge.connectorSerialize(connector, tmp, 0, bufferSize > 0 ? bufferSize - 1 : 0);
        if (n < 0) return n;
        return copyToNativeBuffer(buffer, bufferSize, tmp, n);
    }

    //====================================================================
    // Connector Configuration API (Cycle 3)
    //====================================================================

    @CEntryPoint(name = "TPipe_Connector_add")
    public static int connectorAdd(IsolateThread thread, long connector, long key, long pipeline) {
        int rc = requireReady(); if (rc != TPIPE_OK) return rc;
        String k = readCString(key);
        if (k == null) return TPIPE_ERR_INVALID_ARGUMENT;
        return NativeBridge.connectorAdd(connector, k, pipeline);
    }

    @CEntryPoint(name = "TPipe_Connector_get")
    public static long connectorGet(IsolateThread thread, long connector, long key) {
        int rc = requireReady(); if (rc != TPIPE_OK) return 0L;
        String k = readCString(key);
        if (k == null) return 0L;
        return NativeBridge.connectorGet(connector, k);
    }

    //====================================================================
    // Splitter API (Phase 12 — parallel-fanout container C ABI surface)
    //====================================================================

    @CEntryPoint(name = "TPipe_Splitter_create")
    public static long splitterCreate(IsolateThread thread) {
        int rc = requireReady(); if (rc != TPIPE_OK) return 0L;
        return NativeBridge.splitterCreate();
    }

    @CEntryPoint(name = "TPipe_Splitter_release")
    public static int splitterRelease(IsolateThread thread, long splitter) {
        int rc = requireReady(); if (rc != TPIPE_OK) return rc;
        if (HandleRegistry.INSTANCE.getType(splitter) != HandleTypes.SPLITTER) return TPIPE_ERR_INVALID_HANDLE;
        return NativeBridge.splitterRelease(splitter);
    }

    @CEntryPoint(name = "TPipe_Splitter_init")
    public static int splitterInit(IsolateThread thread, long splitter) {
        int rc = requireReady(); if (rc != TPIPE_OK) return rc;
        return NativeBridge.splitterInit(splitter);
    }

    @CEntryPoint(name = "TPipe_Splitter_execute")
    public static long splitterExecute(IsolateThread thread, long splitter, long content) {
        int rc = requireReady(); if (rc != TPIPE_OK) return 0L;
        return NativeBridge.splitterExecute(splitter, content);
    }

    @CEntryPoint(name = "TPipe_Splitter_serialize")
    public static int splitterSerialize(IsolateThread thread, long splitter, long buffer, int bufferSize) {
        int rc = requireReady(); if (rc != TPIPE_OK) return rc;
        byte[] tmp = new byte[Math.max(1, bufferSize)];
        int n = NativeBridge.splitterSerialize(splitter, tmp, 0, bufferSize > 0 ? bufferSize - 1 : 0);
        if (n < 0) return n;
        return copyToNativeBuffer(buffer, bufferSize, tmp, n);
    }

    //====================================================================
    // Splitter Configuration API (Cycle 3)
    //====================================================================

    @CEntryPoint(name = "TPipe_Splitter_addPipeline")
    public static int splitterAddPipeline(IsolateThread thread, long splitter, long pipeline) {
        int rc = requireReady(); if (rc != TPIPE_OK) return rc;
        return NativeBridge.splitterAddPipeline(splitter, pipeline);
    }

    @CEntryPoint(name = "TPipe_Splitter_removePipeline")
    public static int splitterRemovePipeline(IsolateThread thread, long splitter, long pipeline) {
        int rc = requireReady(); if (rc != TPIPE_OK) return rc;
        return NativeBridge.splitterRemovePipeline(splitter, pipeline);
    }

    @CEntryPoint(name = "TPipe_Splitter_getAllChildPipelines")
    public static int splitterGetAllChildPipelines(IsolateThread thread, long splitter, long outCount) {
        int rc = requireReady(); if (rc != TPIPE_OK) return rc;
        int c = NativeBridge.splitterGetAllChildPipelines(splitter);
        if (c < 0) return c;
        return writeInt(outCount, 0, c);
    }

    @CEntryPoint(name = "TPipe_Splitter_getChildCount")
    public static int splitterGetChildCount(IsolateThread thread, long splitter, long outCount) {
        int rc = requireReady(); if (rc != TPIPE_OK) return rc;
        int c = NativeBridge.splitterGetChildCount(splitter);
        if (c < 0) return c;
        return writeInt(outCount, 0, c);
    }

    //====================================================================
    // Top-level C entry point
    //====================================================================

    /**
     * Top-level C entry point. Bootstraps the library (auto-initializing if
     * the caller has not yet invoked {@link #init}), then dispatches to the
     * requested mode. Equivalent to invoking the JVM-side
     * {@code com.TTT.Application.main()} but reachable from a C program.
     *
     * <p>Mode values (case-insensitive, null/empty defaults to "http"):
     * <ul>
     *   <li>{@code stdio-once}      → P2PStdioHost.runOnce()</li>
     *   <li>{@code stdio-loop}      → P2PStdioHost.runLoop()</li>
     *   <li>{@code pcp-stdio-once}  → PcpStdioHost.runOnce()</li>
     *   <li>{@code pcp-stdio-loop}  → PcpStdioHost.runLoop()</li>
     *   <li>{@code http}            → Embedded Ktor HTTP server (blocks)</li>
     * </ul>
     *
     * @param thread   GraalVM isolate thread (auto-populated by the runtime).
     * @param modeAddr Raw address of a null-terminated C string holding the
     *                 mode selector. Pass 0 for the default ("http").
     * @return 0 on success; negative {@code TPIPE_ERR_*} code on failure.
     */
    @CEntryPoint(name = "TPipe_main")
    public static int TPipeMain(IsolateThread thread, long modeAddr) {
        // Auto-init so the C caller doesn't have to call TPipe_init first.
        if (!NativeBridge.isReady()) {
            NativeBridge.init();
            if (!NativeBridge.isReady()) {
                return TPIPE_ERR_NOT_INITIALIZED;
            }
        }
        String mode = readCString(modeAddr);
        return NativeBridge.tpipeMain(new String[]{ mode == null ? "" : mode });
    }
}
