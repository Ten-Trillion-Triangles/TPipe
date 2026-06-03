package com.TTT.Native

import com.TTT.Pipe.BinaryContent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith

/**
 * TDD tests for the BinaryHandle class (GraalVM C ABI handle layer).
 *
 * BinaryHandle wraps TPipe's [BinaryContent] with 4 variant types: BYTES, BASE64,
 * CLOUD_REF, TEXT_DOC. This test covers the handle's public API:
 * construction per variant, BinaryContent conversion, deep cloning,
 * sensitive-field sanitization (GAP-15), and the 100MB MAX_BINARY_SIZE limit
 * (GAP-14). Reference counting and type-discriminator encoding are exercised
 * through the [HandleRegistry] integration path (the registry carries the
 * refcount and the type bits for a registered handle).
 *
 * RED PHASE: These tests define expected behavior. Some may fail until the
 * ABI implementation in Phase 3 (GREEN) is complete. Following TDD:
 * RED first, then GREEN.
 */
class BinaryHandleTest {

    //==========================================================================
    // Variant Construction Tests
    //==========================================================================

    @Test
    fun testCreateBytesHandle() {
        // BYTES variant: store raw bytes, verify the variant and payload round-trip
        val payload = byteArrayOf(0x01, 0x02, 0x03, 0x04)
        val handle = BinaryHandle(
            variant = BinaryHandle.BinaryVariant.BYTES,
            bytes = payload,
            base64Data = null,
            cloudRef = null,
            textDocRef = null
        )
        assertEquals(BinaryHandle.BinaryVariant.BYTES, handle.variant)
        assertNotNull(handle.bytes)
        assertTrue(handle.bytes!!.contentEquals(payload))
    }

    @Test
    fun testCreateBase64Handle() {
        // BASE64 variant: store a base64 string, verify the variant
        val b64 = "SGVsbG8gV29ybGQ="
        val handle = BinaryHandle(
            variant = BinaryHandle.BinaryVariant.BASE64,
            bytes = null,
            base64Data = b64,
            cloudRef = null,
            textDocRef = null
        )
        assertEquals(BinaryHandle.BinaryVariant.BASE64, handle.variant)
        assertEquals(b64, handle.base64Data)
    }

    @Test
    fun testCreateCloudRefHandle() {
        // CLOUD_REF variant: store a cloud URI, verify the variant
        val uri = "s3://bucket/key/object.png"
        val handle = BinaryHandle(
            variant = BinaryHandle.BinaryVariant.CLOUD_REF,
            bytes = null,
            base64Data = null,
            cloudRef = uri,
            textDocRef = null
        )
        assertEquals(BinaryHandle.BinaryVariant.CLOUD_REF, handle.variant)
        assertEquals(uri, handle.cloudRef)
    }

    @Test
    fun testCreateTextDocHandle() {
        // TEXT_DOC variant: store a text document, verify the variant
        val doc = "This is a sample text document."
        val handle = BinaryHandle(
            variant = BinaryHandle.BinaryVariant.TEXT_DOC,
            bytes = null,
            base64Data = null,
            cloudRef = null,
            textDocRef = doc
        )
        assertEquals(BinaryHandle.BinaryVariant.TEXT_DOC, handle.variant)
        assertEquals(doc, handle.textDocRef)
    }

    //==========================================================================
    // Variant Accessor Tests
    //==========================================================================

    @Test
    fun testGetVariant() {
        // Each variant reports the correct BinaryVariant discriminator
        val bytes = BinaryHandle(BinaryHandle.BinaryVariant.BYTES, byteArrayOf(1), null, null, null)
        val b64 = BinaryHandle(BinaryHandle.BinaryVariant.BASE64, null, "AAA=", null, null)
        val cloud = BinaryHandle(BinaryHandle.BinaryVariant.CLOUD_REF, null, null, "s3://x", null)
        val text = BinaryHandle(BinaryHandle.BinaryVariant.TEXT_DOC, null, null, null, "doc")
        assertEquals(BinaryHandle.BinaryVariant.BYTES, bytes.variant)
        assertEquals(BinaryHandle.BinaryVariant.BASE64, b64.variant)
        assertEquals(BinaryHandle.BinaryVariant.CLOUD_REF, cloud.variant)
        assertEquals(BinaryHandle.BinaryVariant.TEXT_DOC, text.variant)
    }

    @Test
    fun testGetBytes() {
        // BYTES variant returns the original byte array (content-equal, not just same reference)
        val original = byteArrayOf(0x0A, 0x0B, 0x0C)
        val handle = BinaryHandle(BinaryHandle.BinaryVariant.BYTES, original, null, null, null)
        val retrieved = handle.bytes
        assertNotNull(retrieved)
        assertEquals(original.size, retrieved.size)
        assertTrue(retrieved.contentEquals(original))
    }

    //==========================================================================
    // Size Limit Tests (GAP-14: 100MB MAX_BINARY_SIZE)
    //==========================================================================

    @Test
    fun testMaxSizeLimit() {
        // Attempting to construct a binary handle whose payload exceeds
        // MAX_BINARY_SIZE (100MB) must be rejected at construction time
        // so that the registry never sees oversized payloads.
        val oversized = ByteArray((BinaryHandle.MAX_BINARY_SIZE + 1).toInt())
        assertFailsWith<IllegalArgumentException>(
            "constructing a BinaryHandle larger than MAX_BINARY_SIZE must throw"
        ) {
            BinaryHandle(
                variant = BinaryHandle.BinaryVariant.BYTES,
                bytes = oversized,
                base64Data = null,
                cloudRef = null,
                textDocRef = null
            )
        }
    }

    @Test
    fun testMaxBinarySizeConstantIs100MB() {
        // Sanity: the 100MB limit must be exposed and equal to 104857600
        assertEquals(104857600L, BinaryHandle.MAX_BINARY_SIZE)
    }

    //==========================================================================
    // BinaryContent Conversion Tests
    //==========================================================================

    @Test
    fun testToBinaryContent() {
        // toBinaryContent() maps each variant to the matching BinaryContent subclass
        val bytesPayload = byteArrayOf(0x10, 0x20, 0x30)
        val bytesHandle = BinaryHandle(
            BinaryHandle.BinaryVariant.BYTES,
            bytesPayload, null, null, null,
            mimeType = "image/png",
            filename = "a.png"
        )
        val bcBytes: BinaryContent = bytesHandle.toBinaryContent()
        assertTrue(bcBytes is BinaryContent.Bytes, "BYTES must map to BinaryContent.Bytes")
        assertTrue(bcBytes.data.contentEquals(bytesPayload))
        assertEquals("image/png", bcBytes.mimeType)
        assertEquals("a.png", bcBytes.filename)
    }

    @Test
    fun testFromBinaryContent() {
        // fromBinaryContent() is the inverse of toBinaryContent()
        val original: BinaryContent = BinaryContent.Base64String(
            data = "SGVsbG8=",
            mimeType = "text/plain",
            filename = "hello.txt"
        )
        val handle = BinaryHandle.fromBinaryContent(original)
        assertEquals(BinaryHandle.BinaryVariant.BASE64, handle.variant)
        assertEquals("SGVsbG8=", handle.base64Data)
        assertEquals("text/plain", handle.mimeType)
        assertEquals("hello.txt", handle.filename)
    }

    @Test
    fun testFromBinaryContentRoundTripsAllVariants() {
        // Each BinaryContent subclass must round-trip to the matching variant
        val bytes = BinaryContent.Bytes(byteArrayOf(1, 2, 3), "application/octet-stream", "f.bin")
        val b64 = BinaryContent.Base64String("AQID", "application/octet-stream", "f.b64")
        val cloud = BinaryContent.CloudReference("s3://b/k", "image/png", "f.png")
        val doc = BinaryContent.TextDocument("body", "text/plain", "f.txt")

        assertEquals(BinaryHandle.BinaryVariant.BYTES, BinaryHandle.fromBinaryContent(bytes).variant)
        assertEquals(BinaryHandle.BinaryVariant.BASE64, BinaryHandle.fromBinaryContent(b64).variant)
        assertEquals(BinaryHandle.BinaryVariant.CLOUD_REF, BinaryHandle.fromBinaryContent(cloud).variant)
        assertEquals(BinaryHandle.BinaryVariant.TEXT_DOC, BinaryHandle.fromBinaryContent(doc).variant)
    }

    //==========================================================================
    // Cloning Tests
    //==========================================================================

    @Test
    fun testClone() {
        // clone() returns a deep copy: bytes are copied (not aliased), strings are independent
        val original = BinaryHandle(
            BinaryHandle.BinaryVariant.BYTES,
            byteArrayOf(0x42, 0x43),
            null, null, null,
            mimeType = "application/octet-stream",
            filename = "orig.bin"
        )
        val copy = original.clone()
        assertEquals(original, copy, "clone must equal original by value")
        assertNotNull(copy.bytes)
        assertFalse(original.bytes === copy.bytes, "bytes array must be a fresh copy, not the same reference")
        assertTrue(copy.bytes!!.contentEquals(original.bytes!!))
    }

    //==========================================================================
    // Sanitization Tests (GAP-15)
    //==========================================================================

    @Test
    fun testSanitize() {
        // sanitize() must zero sensitive string fields so post-release memory
        // forensics cannot recover API tokens, cloud URIs, or document bodies.
        val handle = BinaryHandle(
            variant = BinaryHandle.BinaryVariant.CLOUD_REF,
            bytes = null,
            base64Data = "secret-base64",
            cloudRef = "s3://secret/key",
            textDocRef = "secret-doc",
            filename = "secret.txt"
        )
        handle.sanitize()
        assertNull(handle.base64Data, "base64Data must be nulled")
        assertNull(handle.cloudRef, "cloudRef must be nulled")
        assertNull(handle.textDocRef, "textDocRef must be nulled")
        assertNull(handle.filename, "filename must be nulled")
    }

    @Test
    fun testSanitizeZerosBytes() {
        // sanitize() must zero out the byte array contents (not just drop the reference)
        val payload = byteArrayOf(0x11, 0x22, 0x33, 0x44)
        val handle = BinaryHandle(
            BinaryHandle.BinaryVariant.BYTES, payload, null, null, null
        )
        handle.sanitize()
        assertNotNull(handle.bytes)
        assertTrue(handle.bytes!!.all { it == 0.toByte() }, "byte array must be zeroed")
    }

    //==========================================================================
    // Reference Counting Tests (via HandleRegistry)
    //==========================================================================

    @Test
    fun testRefCounting() {
        // A BinaryHandle registered with the registry starts at refCount=1;
        // addRef/release cycles modify the count, and final release invalidates
        // the handle.
        val bh = BinaryHandle(BinaryHandle.BinaryVariant.BYTES, byteArrayOf(1), null, null, null)
        val handleId = HandleRegistry.allocate(HandleTypes.BINARY, bh)
        assertTrue(handleId >= 0, "allocate must succeed")
        try {
            assertEquals(1, HandleRegistry.getRefCount(handleId), "newly registered handle has refCount=1")
            assertEquals(0, HandleRegistry.addRef(handleId), "addRef returns 0 on success")
            assertEquals(2, HandleRegistry.getRefCount(handleId), "refCount increments to 2 after addRef")
            assertEquals(0, HandleRegistry.release(handleId), "release returns 0 on success")
            assertEquals(1, HandleRegistry.getRefCount(handleId), "refCount decrements to 1 after release")
        } finally {
            HandleRegistry.release(handleId)
        }
        assertFalse(HandleRegistry.isValid(handleId), "handle invalid after final release")
    }

    //==========================================================================
    // Type Discriminator Tests (via HandleRegistry)
    //==========================================================================

    @Test
    fun testTypeDiscriminator() {
        // When a BinaryHandle is registered, the high 8 bits of the resulting
        // handle must equal HandleTypes.BINARY (=2). The low 56 bits carry
        // the registry ID.
        val bh = BinaryHandle(BinaryHandle.BinaryVariant.BYTES, byteArrayOf(1), null, null, null)
        val handleId = HandleRegistry.allocate(HandleTypes.BINARY, bh)
        try {
            assertEquals(HandleTypes.BINARY, HandleRegistry.getType(handleId),
                "high 8 bits must encode HandleTypes.BINARY")
            val typeBits = (handleId shr 56) and 0xFF
            assertEquals(HandleTypes.BINARY.toLong(), typeBits,
                "raw high-byte extraction must match HandleTypes.BINARY")
        } finally {
            HandleRegistry.release(handleId)
        }
    }
}
