package com.TTT.Native

import com.TTT.Context.ContextWindow
import com.TTT.Context.MiniBank
import com.TTT.Pipe.MultimodalContent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * TDD tests for ContentHandle (C ABI wrapper around TPipe MultimodalContent).
 *
 * RED PHASE: These tests define expected behavior for the C ABI content handle.
 * Tests target the public API surface used by native callers: text content,
 * control flags (terminate, repeat, pass, skip, jump), binary content,
 * context handles, and HandleRegistry refcounting.
 *
 * Tests follow the conventions established in HandleRegistryTest.kt.
 */
class ContentHandleTest {

    //==========================================================================
    // Construction / Allocation
    //==========================================================================

    @Test
    fun testCreateWithText() {
        val handle = ContentHandle("hello world")
        assertEquals("hello world", handle.text, "text should match constructor arg")
        assertFalse(handle.terminate, "terminate should default to false")
        assertFalse(handle.repeat, "repeat should default to false")
        assertFalse(handle.pass, "pass should default to false")
        assertFalse(handle.skip, "skip should default to false")
        assertNull(handle.jump, "jump should default to null")

        // Allocation via HandleRegistry gives a uint64_t handle with refCount=1
        val registryHandle = HandleRegistry.allocate(HandleTypes.CONTENT, handle)
        assertTrue(registryHandle >= 0, "registry handle should be non-negative")
        assertEquals(1, HandleRegistry.getRefCount(registryHandle), "new handle refCount should be 1")
        assertEquals(HandleTypes.CONTENT, HandleRegistry.getType(registryHandle), "type should be CONTENT")
        HandleRegistry.release(registryHandle)
    }

    //==========================================================================
    // Text Property
    //==========================================================================

    @Test
    fun testGetSetText() {
        val handle = ContentHandle()
        handle.text = "first text"
        assertEquals("first text", handle.text, "text getter should return assigned value")
        handle.text = "second text"
        assertEquals("second text", handle.text, "text should be updatable")
        // Empty string is valid
        handle.text = ""
        assertEquals("", handle.text, "text should accept empty string")
    }

    @Test
    fun testUnicodeText() {
        val handle = ContentHandle()
        val unicode = "Hello, 世界! 🚀 Emoji test"
        handle.text = unicode
        assertEquals(unicode, handle.text, "text should preserve unicode")
    }

    //==========================================================================
    // Control Flags
    //==========================================================================

    @Test
    fun testTerminateFlag() {
        val handle = ContentHandle()
        assertFalse(handle.terminate, "terminate should default to false")
        handle.terminate = true
        assertTrue(handle.terminate, "terminate should be true after set")
        handle.terminate = false
        assertFalse(handle.terminate, "terminate should be false after clear")
    }

    @Test
    fun testRepeatFlag() {
        val handle = ContentHandle()
        assertFalse(handle.repeat, "repeat should default to false")
        handle.repeat = true
        assertTrue(handle.repeat, "repeat should be true after set")
        handle.repeat = false
        assertFalse(handle.repeat, "repeat should be false after clear")
    }

    @Test
    fun testPassFlag() {
        val handle = ContentHandle()
        assertFalse(handle.pass, "pass should default to false")
        handle.pass = true
        assertTrue(handle.pass, "pass should be true after set")
        handle.pass = false
        assertFalse(handle.pass, "pass should be false after clear")
    }

    @Test
    fun testSkipFlag() {
        val handle = ContentHandle()
        assertFalse(handle.skip, "skip should default to false")
        handle.skip = true
        assertTrue(handle.skip, "skip should be true after set")
        handle.skip = false
        assertFalse(handle.skip, "skip should be false after clear")
    }

    @Test
    fun testJumpFlag() {
        val handle = ContentHandle()
        assertNull(handle.jump, "jump should default to null")
        handle.jump = "target-pipe"
        assertEquals("target-pipe", handle.jump, "jump should be set")
        handle.jump = null
        assertNull(handle.jump, "jump should be clearable")
    }

    @Test
    fun testErrorMessage() {
        val handle = ContentHandle()
        assertNull(handle.errorMessage, "errorMessage should default to null")
        handle.errorMessage = "something failed"
        assertEquals("something failed", handle.errorMessage, "errorMessage should be settable")
    }

    //==========================================================================
    // Binary Content
    //==========================================================================

    @Test
    fun testAddBinary() {
        val handle = ContentHandle("with binary")
        assertEquals(0, handle.binaryContent.size, "binaryContent should start empty")
        val bytes = BinaryHandle(
            variant = BinaryHandle.BinaryVariant.BYTES,
            bytes = byteArrayOf(0x01.toByte(), 0x02.toByte(), 0x03.toByte()),
            base64Data = null,
            cloudRef = null,
            textDocRef = null,
            mimeType = "image/png",
            filename = "test.png"
        )
        handle.binaryContent.add(bytes)
        assertEquals(1, handle.binaryContent.size, "binaryContent should have 1 entry after add")
        assertEquals(bytes, handle.binaryContent[0], "added binary should match")
    }

    @Test
    fun testAddMultipleBinaries() {
        val handle = ContentHandle()
        val a = BinaryHandle(
            variant = BinaryHandle.BinaryVariant.BYTES,
            bytes = byteArrayOf(0x01.toByte()),
            base64Data = null,
            cloudRef = null,
            textDocRef = null,
            mimeType = "image/png"
        )
        val b = BinaryHandle(
            variant = BinaryHandle.BinaryVariant.BASE64,
            bytes = null,
            base64Data = "AQID",
            cloudRef = null,
            textDocRef = null,
            mimeType = "image/jpeg"
        )
        val c = BinaryHandle(
            variant = BinaryHandle.BinaryVariant.CLOUD_REF,
            bytes = null,
            base64Data = null,
            cloudRef = "s3://bucket/key",
            textDocRef = null,
            mimeType = "application/pdf"
        )
        handle.binaryContent.add(a)
        handle.binaryContent.add(b)
        handle.binaryContent.add(c)
        assertEquals(3, handle.binaryContent.size, "binaryContent should hold multiple entries")
        assertEquals(a, handle.binaryContent[0])
        assertEquals(b, handle.binaryContent[1])
        assertEquals(c, handle.binaryContent[2])
    }

    @Test
    fun testClearBinary() {
        val handle = ContentHandle()
        val bh = BinaryHandle(
            variant = BinaryHandle.BinaryVariant.BYTES,
            bytes = byteArrayOf(0xFF.toByte()),
            base64Data = null,
            cloudRef = null,
            textDocRef = null,
            mimeType = "application/octet-stream"
        )
        handle.binaryContent.add(bh)
        handle.binaryContent.add(bh)
        assertEquals(2, handle.binaryContent.size, "should have 2 binaries before clear")
        handle.binaryContent.clear()
        assertEquals(0, handle.binaryContent.size, "binaryContent should be empty after clear")
    }

    @Test
    fun testGetBinaries() {
        val handle = ContentHandle()
        val bh1 = BinaryHandle(
            variant = BinaryHandle.BinaryVariant.BYTES,
            bytes = byteArrayOf(0x01.toByte()),
            base64Data = null,
            cloudRef = null,
            textDocRef = null,
            mimeType = "image/png"
        )
        val bh2 = BinaryHandle(
            variant = BinaryHandle.BinaryVariant.TEXT_DOC,
            bytes = null,
            base64Data = null,
            cloudRef = null,
            textDocRef = "document content",
            mimeType = "text/plain"
        )
        handle.binaryContent.add(bh1)
        handle.binaryContent.add(bh2)
        val binaries = handle.binaryContent
        assertEquals(2, binaries.size, "should return list of 2 binaries")
        assertEquals(bh1, binaries[0], "first binary should match")
        assertEquals(bh2, binaries[1], "second binary should match")
        assertTrue(binaries.all { it is BinaryHandle }, "all entries should be BinaryHandle")
    }

    //==========================================================================
    // Context (current API: String? — documented for future ContextHandle type)
    //==========================================================================

    @Test
    fun testGetSetContext() {
        val handle = ContentHandle()
        assertNull(handle.context, "context should default to null")
        // Current API: context is a String? for serialization. Future API may
        // expose setContext(ContextHandle) — see Phase 3 follow-up.
        handle.context = "serialized-context-data"
        assertEquals("serialized-context-data", handle.context, "context should round-trip")
    }

    @Test
    fun testContextWithContextWindow() {
        // Validates that we can construct a ContextWindow-derived payload
        val handle = ContentHandle()
        val cw = ContextWindow()
        // We only verify the String slot accepts arbitrary serialized payload
        handle.context = "ctx-window-v1"
        assertEquals("ctx-window-v1", handle.context)
    }

    //==========================================================================
    // MiniBank
    //==========================================================================

    @Test
    fun testGetSetMiniBank() {
        val handle = ContentHandle()
        assertNull(handle.miniBank, "miniBank should default to null")
        handle.miniBank = "serialized-minibank-data"
        assertEquals("serialized-minibank-data", handle.miniBank, "miniBank should round-trip")
    }

    @Test
    fun testMiniBankWithRealInstance() {
        val handle = ContentHandle()
        val mb = MiniBank()
        // Just verify that the slot is settable with arbitrary string data
        handle.miniBank = "minibank-key1,minibank-key2"
        assertEquals("minibank-key1,minibank-key2", handle.miniBank)
    }

    //==========================================================================
    // Model Reasoning
    //==========================================================================

    @Test
    fun testModelReasoning() {
        val handle = ContentHandle()
        assertNull(handle.modelReasoning, "modelReasoning should default to null")
        handle.modelReasoning = "step 1: think about the problem"
        assertEquals("step 1: think about the problem", handle.modelReasoning)
    }

    //==========================================================================
    // Independence (Clone)
    //==========================================================================

    @Test
    fun testClone() {
        val original = ContentHandle("original text")
        original.terminate = true
        original.jump = "next-pipe"
        val originalBh = BinaryHandle(
            variant = BinaryHandle.BinaryVariant.BYTES,
            bytes = byteArrayOf(0x01.toByte(), 0x02.toByte()),
            base64Data = null,
            cloudRef = null,
            textDocRef = null,
            mimeType = "image/png"
        )
        original.binaryContent.add(originalBh)

        // Build a deep copy of the handle's fields manually. This is the
        // contract that a future clone() method should satisfy.
        val copy = ContentHandle(
            text = original.text,
            terminate = original.terminate,
            repeat = original.repeat,
            pass = original.pass,
            skip = original.skip,
            jump = original.jump,
            errorMessage = original.errorMessage
        )
        copy.modelReasoning = original.modelReasoning
        copy.context = original.context
        copy.miniBank = original.miniBank
        for (bh in original.binaryContent) {
            copy.binaryContent.add(bh.clone())
        }

        assertEquals(original.text, copy.text, "cloned text should match")
        assertEquals(original.terminate, copy.terminate, "cloned terminate should match")
        assertEquals(original.jump, copy.jump, "cloned jump should match")
        assertEquals(original.binaryContent.size, copy.binaryContent.size, "cloned binaryContent size should match")
        assertNotSame(original, copy, "clone should be a different instance")
        assertNotSame(original.binaryContent, copy.binaryContent, "clone binaryContent should be a different list")

        // Mutating the copy does not affect the original
        copy.text = "modified"
        assertEquals("original text", original.text, "mutating clone should not affect original")
    }

    //==========================================================================
    // Type Discriminator
    //==========================================================================

    @Test
    fun testTypeDiscriminator() {
        val handle = ContentHandle("data")
        val registryHandle = HandleRegistry.allocate(HandleTypes.CONTENT, handle)
        try {
            assertEquals(1, HandleTypes.CONTENT, "HandleTypes.CONTENT should be 1")
            assertEquals(HandleTypes.CONTENT, HandleRegistry.getType(registryHandle),
                "type discriminator should be CONTENT (=1)")
        } finally {
            HandleRegistry.release(registryHandle)
        }
    }

    @Test
    fun testTypeDiscriminatorMultipleContentHandles() {
        val h1 = ContentHandle("a")
        val h2 = ContentHandle("b")
        val h3 = ContentHandle("c")
        val r1 = HandleRegistry.allocate(HandleTypes.CONTENT, h1)
        val r2 = HandleRegistry.allocate(HandleTypes.CONTENT, h2)
        val r3 = HandleRegistry.allocate(HandleTypes.CONTENT, h3)
        try {
            assertEquals(HandleTypes.CONTENT, HandleRegistry.getType(r1))
            assertEquals(HandleTypes.CONTENT, HandleRegistry.getType(r2))
            assertEquals(HandleTypes.CONTENT, HandleRegistry.getType(r3))
        } finally {
            HandleRegistry.release(r1)
            HandleRegistry.release(r2)
            HandleRegistry.release(r3)
        }
    }

    //==========================================================================
    // MultimodalContent Conversion
    //==========================================================================

    @Test
    fun testToMultimodalContent() {
        val handle = ContentHandle(
            text = "convert me",
            terminate = true,
            repeat = false,
            pass = true,
            skip = false,
            jump = null
        )
        handle.modelReasoning = "thinking..."
        val mc: MultimodalContent = handle.toMultimodalContent()
        assertEquals("convert me", mc.text, "toMultimodalContent should copy text")
        assertTrue(mc.terminatePipeline, "toMultimodalContent should copy terminate flag")
        assertTrue(mc.passPipeline, "toMultimodalContent should copy pass flag")
        assertEquals("thinking...", mc.modelReasoning, "toMultimodalContent should copy model reasoning")
    }

    @Test
    fun testToMultimodalContentWithBinary() {
        val handle = ContentHandle("with binary")
        val bh = BinaryHandle(
            variant = BinaryHandle.BinaryVariant.BYTES,
            bytes = byteArrayOf(0x01.toByte(), 0x02.toByte(), 0x03.toByte()),
            base64Data = null,
            cloudRef = null,
            textDocRef = null,
            mimeType = "image/png",
            filename = "pixel.png"
        )
        handle.binaryContent.add(bh)
        val mc = handle.toMultimodalContent()
        assertEquals(1, mc.binaryContent.size, "toMultimodalContent should include binary content")
    }

    @Test
    fun testToMultimodalContentWithJump() {
        val handle = ContentHandle("jump test")
        handle.jump = "destination-pipe"
        val mc = handle.toMultimodalContent()
        assertEquals("destination-pipe", mc.getJumpToPipe(), "toMultimodalContent should copy jump")
    }

    @Test
    fun testFromMultimodalContent() {
        val mc = MultimodalContent("imported text")
        mc.terminatePipeline = true
        mc.passPipeline = false
        mc.skipReasoningPipe = true
        mc.modelReasoning = "imported reasoning"
        mc.jumpToPipe("my-target")

        val handle = ContentHandle.fromMultimodalContent(mc)
        assertEquals("imported text", handle.text, "fromMultimodalContent should copy text")
        assertTrue(handle.terminate, "fromMultimodalContent should copy terminate flag")
        assertTrue(handle.skip, "fromMultimodalContent should copy skip flag")
        assertEquals("my-target", handle.jump, "fromMultimodalContent should copy jump")
        assertEquals("imported reasoning", handle.modelReasoning, "fromMultimodalContent should copy reasoning")
    }

    @Test
    fun testFromMultimodalContentEmpty() {
        val mc = MultimodalContent("")
        val handle = ContentHandle.fromMultimodalContent(mc)
        assertEquals("", handle.text, "fromMultimodalContent should handle empty text")
        assertNull(handle.jump, "fromMultimodalContent should return null jump for empty")
        assertNull(handle.modelReasoning, "fromMultimodalContent should return null reasoning for empty")
    }

    @Test
    fun testFromMultimodalContentWithBinary() {
        val mc = MultimodalContent("with bin")
        mc.addBinary(byteArrayOf(0x10.toByte(), 0x20.toByte()), "application/octet-stream", "data.bin")
        val handle = ContentHandle.fromMultimodalContent(mc)
        assertEquals(1, handle.binaryContent.size, "fromMultimodalContent should copy binary content")
        assertEquals("application/octet-stream", handle.binaryContent[0].mimeType)
    }

    @Test
    fun testRoundTripConversion() {
        val original = ContentHandle("round trip")
        original.terminate = true
        original.repeat = true
        original.skip = true
        original.jump = "next"
        original.modelReasoning = "step 1: analyze"

        val mc = original.toMultimodalContent()
        val restored = ContentHandle.fromMultimodalContent(mc)

        assertEquals(original.text, restored.text, "round-trip text should match")
        assertEquals(original.terminate, restored.terminate, "round-trip terminate should match")
        assertEquals(original.repeat, restored.repeat, "round-trip repeat should match")
        assertEquals(original.skip, restored.skip, "round-trip skip should match")
        assertEquals(original.jump, restored.jump, "round-trip jump should match")
        assertEquals(original.modelReasoning, restored.modelReasoning, "round-trip reasoning should match")
    }

    //==========================================================================
    // Safety Limits
    //==========================================================================

    @Test
    fun testMaxStringLength() {
        val handle = ContentHandle()
        // Verify the constant exists and matches GapVerification
        assertEquals(1048576, GapVerification.MAX_STRING_LEN, "MAX_STRING_LEN should be 1MB (1048576)")
        // Build a string just under the limit — assignment should succeed
        val underLimit = "a".repeat(1024)
        handle.text = underLimit
        assertEquals(underLimit.length, handle.text.length, "under-limit text should be accepted")
    }

    @Test
    fun testMaxStringLengthConstantMatchesHeader() {
        // The MAX_STRING_LEN constant in GapVerification (1MB) is the safety
        // limit. Currently ContentHandle does not enforce this limit at
        // assignment time — when it does, this test will assert that an
        // over-limit string is rejected.
        assertEquals(1048576, GapVerification.MAX_STRING_LEN)
    }

    //==========================================================================
    // Reference Counting (via HandleRegistry)
    //==========================================================================

    @Test
    fun testRefCounting() {
        val handle = ContentHandle("refcount test")
        val registryHandle = HandleRegistry.allocate(HandleTypes.CONTENT, handle)
        try {
            // Initial refcount
            assertEquals(1, HandleRegistry.getRefCount(registryHandle), "initial refCount should be 1")

            // Add a reference
            HandleRegistry.addRef(registryHandle)
            assertEquals(2, HandleRegistry.getRefCount(registryHandle), "refCount should be 2 after addRef")

            // Add more references
            HandleRegistry.addRef(registryHandle)
            HandleRegistry.addRef(registryHandle)
            assertEquals(4, HandleRegistry.getRefCount(registryHandle), "refCount should be 4 after 3 addRefs")

            // Release down to 1
            HandleRegistry.release(registryHandle)
            HandleRegistry.release(registryHandle)
            assertEquals(2, HandleRegistry.getRefCount(registryHandle), "refCount should be 2 after 2 releases")

            // Final releases
            HandleRegistry.release(registryHandle)
            assertEquals(1, HandleRegistry.getRefCount(registryHandle), "refCount should be 1")
        } finally {
            // Clean up final reference
            HandleRegistry.release(registryHandle)
        }
    }

    @Test
    fun testRefCountingFinalReleaseFrees() {
        val handle = ContentHandle("final release")
        val registryHandle = HandleRegistry.allocate(HandleTypes.CONTENT, handle)
        assertTrue(HandleRegistry.isValid(registryHandle), "handle should be valid before release")
        HandleRegistry.addRef(registryHandle)
        HandleRegistry.release(registryHandle)
        assertTrue(HandleRegistry.isValid(registryHandle), "handle should still be valid (refCount=1)")
        HandleRegistry.release(registryHandle)
        assertFalse(HandleRegistry.isValid(registryHandle), "handle should be invalid after final release")
    }

    @Test
    fun testRefCountingMultipleContentHandles() {
        val h1 = ContentHandle("a")
        val h2 = ContentHandle("b")
        val r1 = HandleRegistry.allocate(HandleTypes.CONTENT, h1)
        val r2 = HandleRegistry.allocate(HandleTypes.CONTENT, h2)
        try {
            HandleRegistry.addRef(r1)
            HandleRegistry.addRef(r2)
            assertEquals(2, HandleRegistry.getRefCount(r1))
            assertEquals(2, HandleRegistry.getRefCount(r2))
            HandleRegistry.release(r1)
            HandleRegistry.release(r2)
            assertEquals(1, HandleRegistry.getRefCount(r1))
            assertEquals(1, HandleRegistry.getRefCount(r2))
        } finally {
            HandleRegistry.release(r1)
            HandleRegistry.release(r2)
        }
    }
}
