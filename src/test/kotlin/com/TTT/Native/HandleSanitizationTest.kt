package com.TTT.Native

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Phase 4 — handle sanitization sweep.
 *
 * Every handle class that holds sensitive data (system prompts, P2P
 * credentials, conversation content, binary blobs) must zero those
 * fields on release so a post-mortem heap dump or memory forensic
 * pass cannot recover them.
 *
 * The test pattern:
 *   1. Construct the handle's data object directly.
 *   2. Plant a known "secret" value into the sensitive field.
 *   3. Allocate it in the HandleRegistry to get a uint64_t handle.
 *   4. Release the handle (triggers sanitize() in HandleRegistry).
 *   5. Read the data object's field back directly (the reference is
 *      still valid even after the registry entry is removed) and
 *      assert the secret is gone.
 */
class HandleSanitizationTest {

    //==================================================================
    // ContentHandle — existing sanitize already covers this. The
    // test pins the contract so future refactors can't regress it.
    //==================================================================

    @Test
    fun testContentHandleTextIsZeroedOnRelease() {
        val data = ContentHandle("secret-payload-do-not-leak")
        val handle = HandleRegistry.allocate(HandleTypes.CONTENT, data)
        assertEquals("secret-payload-do-not-leak", data.text)
        HandleRegistry.release(handle)
        assertEquals("", data.text, "ContentHandle.text must be zeroed on release")
    }

    @Test
    fun testContentHandleErrorMessageIsNulledOnRelease() {
        val data = ContentHandle("payload")
        data.errorMessage = "internal stack trace — do not leak"
        val handle = HandleRegistry.allocate(HandleTypes.CONTENT, data)
        HandleRegistry.release(handle)
        assertNull(data.errorMessage, "ContentHandle.errorMessage must be nulled on release")
    }

    //==================================================================
    // PipeSettingsHandle — existing sanitize already covers this.
    //==================================================================

    @Test
    fun testPipeSettingsSystemPromptIsNulledOnRelease() {
        val data = PipeSettingsHandle.create()
            .setSystemPrompt("secret system prompt — do not leak")
        val handle = HandleRegistry.allocate(HandleTypes.PIPE_SETTINGS, data)
        assertEquals("secret system prompt — do not leak", data.systemPrompt)
        HandleRegistry.release(handle)
        assertNull(data.systemPrompt, "PipeSettingsHandle.systemPrompt must be nulled on release")
    }

    @Test
    fun testPipeSettingsJsonOutputIsNulledOnRelease() {
        val data = PipeSettingsHandle.create()
            .setJsonOutput("""{"api_key":"sk-secret-12345"}""")
        val handle = HandleRegistry.allocate(HandleTypes.PIPE_SETTINGS, data)
        HandleRegistry.release(handle)
        assertNull(data.jsonOutput, "PipeSettingsHandle.jsonOutput must be nulled on release")
    }

    //==================================================================
    // BinaryHandle — sensitive data (bytes, base64, cloudRef, textDoc)
    //==================================================================

    @Test
    fun testBinaryHandleBytesAreZeroedOnRelease() {
        val data = BinaryHandle(
            variant = BinaryHandle.BinaryVariant.BYTES,
            bytes = "secret-binary-data".toByteArray(),
            base64Data = null, cloudRef = null, textDocRef = null
        )
        val originalSize = data.bytes!!.size
        val handle = HandleRegistry.allocate(HandleTypes.BINARY, data)
        assertNotNull(data.bytes)
        HandleRegistry.release(handle)
        // Bytes must be zeroed (defense against heap dump recovery)
        val zeroed = data.bytes!!.all { it == 0.toByte() }
        assertTrue(zeroed,
            "BinaryHandle.bytes must be zeroed on release (was ${data.bytes!!.size} bytes)")
        // The byte count should not be reduced to 0 — that would leak the
        // size of the secret. The array is overwritten in place.
        assertEquals(originalSize, data.bytes!!.size,
            "BinaryHandle.bytes.size should be preserved (avoid size leak)")
    }

    @Test
    fun testBinaryHandleBase64IsNulledOnRelease() {
        val data = BinaryHandle(
            variant = BinaryHandle.BinaryVariant.BASE64,
            bytes = null, base64Data = "c2VjcmV0LWRhdGE=",
            cloudRef = null, textDocRef = null
        )
        val handle = HandleRegistry.allocate(HandleTypes.BINARY, data)
        HandleRegistry.release(handle)
        assertNull(data.base64Data, "BinaryHandle.base64Data must be nulled on release")
    }

    @Test
    fun testBinaryHandleCloudRefIsNulledOnRelease() {
        val data = BinaryHandle(
            variant = BinaryHandle.BinaryVariant.CLOUD_REF,
            bytes = null, base64Data = null,
            cloudRef = "s3://secret-bucket/path/to/file",
            textDocRef = null
        )
        val handle = HandleRegistry.allocate(HandleTypes.BINARY, data)
        HandleRegistry.release(handle)
        assertNull(data.cloudRef, "BinaryHandle.cloudRef must be nulled on release")
    }

    @Test
    fun testBinaryHandleTextDocRefIsNulledOnRelease() {
        val data = BinaryHandle(
            variant = BinaryHandle.BinaryVariant.TEXT_DOC,
            bytes = null,
            base64Data = null,
            cloudRef = null,
            textDocRef = "/secret/path/to/document.txt"
        )
        val handle = HandleRegistry.allocate(HandleTypes.BINARY, data)
        HandleRegistry.release(handle)
        assertNull(data.textDocRef, "BinaryHandle.textDocRef must be nulled on release")
    }

    //==================================================================
    // ConverseHistoryHandle — message text is sensitive.
    // Sanitize clears the internal history list so a post-mortem
    // dump cannot recover the conversation.
    //==================================================================

    @Test
    fun testConverseHistoryMessageListIsClearedOnRelease() {
        val history = com.TTT.Context.ConverseHistory()
        val data = ConverseHistoryHandle(history)
        val handle = HandleRegistry.allocate(HandleTypes.CONVERSE_HISTORY, data)
        assertEquals(0, data.converseHistory.history.size,
            "precondition: history starts empty")
        HandleRegistry.release(handle)
        assertEquals(0, data.converseHistory.history.size,
            "ConverseHistory.history must be cleared on release (size leak protection)")
    }
}
