package com.TTT.Native

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse

/**
 * Phase 2 — TPipe_Handle typedef sanity check.
 *
 * The C ABI exposes handles as opaque `uint64_t`. From the Kotlin side
 * we encode the handle type in the high 8 bits and the registry id in
 * the low 56 bits. The sanity check verifies a handle decodes to:
 *   - a known type (one of the 21 HandleTypes constants, range 1..20)
 *   - a non-negative id
 *
 * Exposed as two package-private Java helpers on `TPipeBootstrap`:
 *   - `isValidHandleType(int type)` — true iff 1..20
 *   - `decodeHandleType(long handle)` — type byte, or -1 if invalid
 *   - `decodeHandleId(long handle)` — id, or -1 if invalid
 *
 * This is a defense-in-depth check: any code path that holds a handle
 * across a trust boundary can call these helpers to fail fast on a
 * malformed handle.
 */
class HandleTypedefSanityTest {

    //==================================================================
    // isValidHandleType: 1..20 is valid; 0 (BASE) and 21+ are not
    //==================================================================

    @Test
    fun testIsValidTypeForKnownTypes() {
        for (type in 1..20) {
            assertTrue(
                TPipeBootstrap.isValidHandleType(type),
                "isValidHandleType($type) must be true"
            )
        }
    }

    @Test
    fun testIsValidTypeRejectsBase() {
        // 0 is BASE — intentionally not a usable handle type for @CEntryPoint
        assertFalse(TPipeBootstrap.isValidHandleType(0))
    }

    @Test
    fun testIsValidTypeRejectsOutOfRange() {
        assertFalse(TPipeBootstrap.isValidHandleType(21))
        assertFalse(TPipeBootstrap.isValidHandleType(100))
        assertFalse(TPipeBootstrap.isValidHandleType(0xFF))
        assertFalse(TPipeBootstrap.isValidHandleType(-1))
    }

    //==================================================================
    // decodeHandleType: extracts high 8 bits
    //==================================================================

    @Test
    fun testDecodeTypeRoundTripsForEachValidType() {
        for (type in 1..20) {
            val handle = HandleRegistry.allocate(type, "data-for-$type")
            val decoded = TPipeBootstrap.decodeHandleType(handle)
            assertEquals(type, decoded, "type byte should round-trip for type=$type")
            HandleRegistry.release(handle)
        }
    }

    @Test
    fun testDecodeTypeRejectsInvalidTypeByte() {
        // 0x21 is beyond TYPE_COUNT (21) — must be rejected (-1)
        val bogusHandle = (0x21L shl 56) or 1L
        assertEquals(-1, TPipeBootstrap.decodeHandleType(bogusHandle))
    }

    @Test
    fun testDecodeTypeRejectsMaxTypeByte() {
        val bogusHandle = (0xFFL shl 56) or 1L
        assertEquals(-1, TPipeBootstrap.decodeHandleType(bogusHandle))
    }

    @Test
    fun testDecodeTypeRejectsZeroHandle() {
        assertEquals(-1, TPipeBootstrap.decodeHandleType(0L))
    }

    //==================================================================
    // decodeHandleId: extracts low 56 bits
    //==================================================================

    @Test
    fun testDecodeIdPreservesNonZeroId() {
        // Allocate several handles so the id counter is non-trivial
        val released = (1..5).map { HandleRegistry.allocate(HandleTypes.CONTENT, "x$it") }
        released.forEach { HandleRegistry.release(it) }
        // Allocate a fresh one; its id should be > 0 (the counter is monotonic)
        val handle = HandleRegistry.allocate(HandleTypes.PIPE, "fresh")
        val decodedId = TPipeBootstrap.decodeHandleId(handle)
        assertTrue(decodedId > 0, "id should be > 0, got $decodedId")
        HandleRegistry.release(handle)
    }

    @Test
    fun testDecodeIdRejectsInvalidTypeByte() {
        val bogusHandle = (0x21L shl 56) or 42L
        assertEquals(-1, TPipeBootstrap.decodeHandleId(bogusHandle),
            "id decode must return -1 if the type byte is invalid")
    }

    @Test
    fun testDecodeIdRejectsZeroHandle() {
        assertEquals(-1, TPipeBootstrap.decodeHandleId(0L))
    }
}
