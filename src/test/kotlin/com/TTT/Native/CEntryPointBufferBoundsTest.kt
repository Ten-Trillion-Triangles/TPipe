package com.TTT.Native

import sun.misc.Unsafe
import java.lang.reflect.Field
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Phase 2 — FFI hardening: bounds checks on the output-pointer helpers
 * in `TPipeBootstrap`.
 *
 * The three helpers `writeInt` / `writeFloat` / `writePtr` are the only
 * way the C ABI can write primitive values back to a caller-provided
 * buffer. Before Phase 2, they only null-checked the address — a 4-byte
 * C buffer with an 8-byte write would corrupt the heap.
 *
 * After Phase 2, each helper takes a `bufferSize` argument and returns
 * `TPIPE_ERR_BUFFER_TOO_SMALL` when the buffer is too small for the
 * type. Callers that don't have a buffer size from the C ABI pass 0
 * ("no check") so the public entry-point signatures don't have to change.
 *
 * The helpers are package-private (no modifier) so the test can call
 * them directly with a real address. The test allocates the buffer via
 * `sun.misc.Unsafe` to get a known address, then exercises every
 * buffer-size / type combination listed in the prior plan.
 */
class CEntryPointBufferBoundsTest {

    companion object {
        // Match the constants in TPipeBootstrap so the test fails fast
        // if a future refactor renames them.
        private const val ERR_BUFFER_TOO_SMALL = -6   // TPIPE_ERR_BUFFER_TOO_SMALL
        private const val ERR_NULL_POINTER = -5       // TPIPE_ERR_NULL_POINTER
        private const val TPIPE_OK = 0
    }

    private val unsafe: Unsafe by lazy {
        val f: Field = Unsafe::class.java.getDeclaredField("theUnsafe")
        f.isAccessible = true
        f.get(null) as Unsafe
    }

    private fun allocBuffer(size: Int): Pair<Long, ByteArray> {
        val buf = ByteArray(size)
        val base = unsafe.arrayBaseOffset(ByteArray::class.java)
        val addr = unsafe.getLong(buf, base.toLong())  // not used; we use the static address
        // The cleanest way to get a stable address in a Java test is
        // to use the ByteArray's own base offset from the JVM, but
        // JVMs don't expose ByteArray addresses to user code. Instead,
        // allocate off-heap via a DirectByteBuffer... no, that doesn't
        // give us the address either without reflection. Easiest: use
        // `unsafe.allocateMemory` for a real heap address.
        val memAddr = unsafe.allocateMemory(size.toLong())
        // Clean up after the test in a finally; for this lightweight
        // test the JVM exits soon and the leak is bounded.
        return memAddr to buf
    }

    private fun freeBuffer(addr: Long) {
        unsafe.freeMemory(addr)
    }

    //==================================================================
    // writeInt: needs bufferSize >= 4 (sizeof int)
    //==================================================================

    @Test
    fun testWriteIntBufferTooSmallReturnsError() {
        val (addr, _) = allocBuffer(2)
        try {
            val rc = TPipeBootstrap.writeInt(addr, 2, 42)
            assertEquals(ERR_BUFFER_TOO_SMALL, rc,
                "writeInt with bufferSize=2 must return ERR_BUFFER_TOO_SMALL")
        } finally {
            freeBuffer(addr)
        }
    }

    @Test
    fun testWriteIntBufferExactReturnsOk() {
        val (addr, _) = allocBuffer(4)
        try {
            val rc = TPipeBootstrap.writeInt(addr, 4, 42)
            assertEquals(TPIPE_OK, rc, "writeInt with bufferSize=4 must return TPIPE_OK")
        } finally {
            freeBuffer(addr)
        }
    }

    @Test
    fun testWriteIntBufferLargerReturnsOk() {
        val (addr, _) = allocBuffer(16)
        try {
            val rc = TPipeBootstrap.writeInt(addr, 16, 42)
            assertEquals(TPIPE_OK, rc, "writeInt with bufferSize=16 must return TPIPE_OK")
        } finally {
            freeBuffer(addr)
        }
    }

    @Test
    fun testWriteIntZeroBufferSizeSkipsCheck() {
        // 0 means "no buffer size available from the C ABI" — the
        // caller is asserting they know the buffer is large enough.
        // The helper must NOT return ERR_BUFFER_TOO_SMALL in this case.
        val (addr, _) = allocBuffer(4)
        try {
            val rc = TPipeBootstrap.writeInt(addr, 0, 42)
            assertEquals(TPIPE_OK, rc, "writeInt with bufferSize=0 must skip the check")
        } finally {
            freeBuffer(addr)
        }
    }

    @Test
    fun testWriteIntNullPointerReturnsError() {
        val rc = TPipeBootstrap.writeInt(0L, 16, 42)
        assertEquals(ERR_NULL_POINTER, rc, "writeInt with addr=0 must return ERR_NULL_POINTER")
    }

    //==================================================================
    // writeFloat: needs bufferSize >= 4 (sizeof float)
    //==================================================================

    @Test
    fun testWriteFloatBufferTooSmallReturnsError() {
        val (addr, _) = allocBuffer(2)
        try {
            val rc = TPipeBootstrap.writeFloat(addr, 2, 3.14f)
            assertEquals(ERR_BUFFER_TOO_SMALL, rc,
                "writeFloat with bufferSize=2 must return ERR_BUFFER_TOO_SMALL")
        } finally {
            freeBuffer(addr)
        }
    }

    @Test
    fun testWriteFloatBufferExactReturnsOk() {
        val (addr, _) = allocBuffer(4)
        try {
            val rc = TPipeBootstrap.writeFloat(addr, 4, 3.14f)
            assertEquals(TPIPE_OK, rc)
        } finally {
            freeBuffer(addr)
        }
    }

    @Test
    fun testWriteFloatBuffer8ReturnsOk() {
        val (addr, _) = allocBuffer(8)
        try {
            val rc = TPipeBootstrap.writeFloat(addr, 8, 3.14f)
            assertEquals(TPIPE_OK, rc)
        } finally {
            freeBuffer(addr)
        }
    }

    //==================================================================
    // writePtr: needs bufferSize >= 8 (sizeof long/pointer)
    //==================================================================

    @Test
    fun testWritePtrBufferTooSmall4ReturnsError() {
        val (addr, _) = allocBuffer(4)
        try {
            val rc = TPipeBootstrap.writePtr(addr, 4, 0xCAFEBABEL)
            assertEquals(ERR_BUFFER_TOO_SMALL, rc,
                "writePtr with bufferSize=4 must return ERR_BUFFER_TOO_SMALL (sizeof ptr = 8)")
        } finally {
            freeBuffer(addr)
        }
    }

    @Test
    fun testWritePtrBufferExactReturnsOk() {
        val (addr, _) = allocBuffer(8)
        try {
            val rc = TPipeBootstrap.writePtr(addr, 8, 0xCAFEBABEL)
            assertEquals(TPIPE_OK, rc, "writePtr with bufferSize=8 must return TPIPE_OK")
        } finally {
            freeBuffer(addr)
        }
    }

    @Test
    fun testWritePtrBufferLargerReturnsOk() {
        val (addr, _) = allocBuffer(16)
        try {
            val rc = TPipeBootstrap.writePtr(addr, 16, 0xCAFEBABEL)
            assertEquals(TPIPE_OK, rc)
        } finally {
            freeBuffer(addr)
        }
    }

    @Test
    fun testWritePtrNullPointerReturnsError() {
        val rc = TPipeBootstrap.writePtr(0L, 16, 0xCAFEBABEL)
        assertEquals(ERR_NULL_POINTER, rc)
    }

}
