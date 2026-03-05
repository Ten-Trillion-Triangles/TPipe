package com.TTT.Pipe

import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BinaryContentTest {

    @Test
    fun `test Bytes toBase64`() {
        val originalText = "Hello, world!"
        val bytes = originalText.toByteArray()
        val mimeType = "text/plain"
        val filename = "hello.txt"

        val binaryBytes = BinaryContent.Bytes(bytes, mimeType, filename)
        val base64String = binaryBytes.toBase64()

        val expectedBase64 = Base64.getEncoder().encodeToString(bytes)
        assertEquals(expectedBase64, base64String.data)
        assertEquals(mimeType, base64String.mimeType)
        assertEquals(filename, base64String.filename)
    }

    @Test
    fun `test Bytes toBase64 with empty array`() {
        val emptyBytes = ByteArray(0)
        val binaryBytes = BinaryContent.Bytes(emptyBytes, "application/octet-stream", null)
        val base64String = binaryBytes.toBase64()

        assertEquals("", base64String.data)
        assertEquals("application/octet-stream", base64String.mimeType)
        assertNull(base64String.filename)
    }

    @Test
    fun `test Bytes equals and hashCode`() {
        val data1 = "data".toByteArray()
        val data2 = "data".toByteArray()
        val data3 = "different".toByteArray()

        val bytes1 = BinaryContent.Bytes(data1, "text/plain", "file.txt")
        val bytes2 = BinaryContent.Bytes(data2, "text/plain", "file.txt")
        val bytesDifferentData = BinaryContent.Bytes(data3, "text/plain", "file.txt")
        val bytesDifferentMime = BinaryContent.Bytes(data1, "text/html", "file.txt")
        val bytesDifferentFile = BinaryContent.Bytes(data1, "text/plain", "other.txt")
        val bytesNullFile = BinaryContent.Bytes(data1, "text/plain", null)

        assertEquals(bytes1, bytes1) // Identity
        assertEquals(bytes1, bytes2) // Equivalency
        assertEquals(bytes1.hashCode(), bytes2.hashCode())

        assertNotEquals(bytes1, bytesDifferentData)
        assertNotEquals(bytes1, bytesDifferentMime)
        assertNotEquals(bytes1, bytesDifferentFile)
        assertNotEquals(bytes1, bytesNullFile)

        assertNotEquals(bytes1, null as BinaryContent.Bytes?)
        assertNotEquals<Any>(bytes1, "some string")
    }

    @Test
    fun `test Base64String toBytes`() {
        val originalText = "Hello, world!"
        val bytes = originalText.toByteArray()
        val base64Data = Base64.getEncoder().encodeToString(bytes)
        val mimeType = "text/plain"
        val filename = "hello.txt"

        val base64String = BinaryContent.Base64String(base64Data, mimeType, filename)
        val binaryBytes = base64String.toBytes()

        assertTrue(bytes.contentEquals(binaryBytes.data))
        assertEquals(mimeType, binaryBytes.mimeType)
        assertEquals(filename, binaryBytes.filename)
    }

    @Test
    fun `test Base64String toBytes with empty string`() {
        val base64String = BinaryContent.Base64String("", "application/octet-stream", null)
        val binaryBytes = base64String.toBytes()

        assertTrue(ByteArray(0).contentEquals(binaryBytes.data))
        assertEquals("application/octet-stream", binaryBytes.mimeType)
        assertNull(binaryBytes.filename)
    }

    @Test
    fun `test Base64String toBytes with invalid Base64`() {
        // "This is not valid base64!!!!" contains invalid characters like '!' and spaces
        val invalidBase64 = "This is not valid base64!!!!"
        val base64String = BinaryContent.Base64String(invalidBase64, "text/plain", null)

        assertFailsWith<IllegalArgumentException> {
            base64String.toBytes()
        }
    }

    @Test
    fun `test getMimeType across all subclasses`() {
        val bytes = BinaryContent.Bytes("data".toByteArray(), "application/octet-stream", null)
        val base64 = BinaryContent.Base64String("ZGF0YQ==", "image/png", null)
        val cloud = BinaryContent.CloudReference("s3://bucket/file", "video/mp4", null)
        val text = BinaryContent.TextDocument("Some text", "text/plain", null)

        assertEquals("application/octet-stream", bytes.getMimeType())
        assertEquals("image/png", base64.getMimeType())
        assertEquals("video/mp4", cloud.getMimeType())
        assertEquals("text/plain", text.getMimeType())
    }

    @Test
    fun `test getFilename across all subclasses`() {
        val bytesWithFile = BinaryContent.Bytes("data".toByteArray(), "text/plain", "file1.txt")
        val bytesNoFile = BinaryContent.Bytes("data".toByteArray(), "text/plain", null)

        val base64WithFile = BinaryContent.Base64String("ZGF0YQ==", "image/png", "image.png")
        val base64NoFile = BinaryContent.Base64String("ZGF0YQ==", "image/png", null)

        val cloudWithFile = BinaryContent.CloudReference("s3://bucket/file", "video/mp4", "video.mp4")
        val cloudNoFile = BinaryContent.CloudReference("s3://bucket/file", "video/mp4", null)

        val textWithFile = BinaryContent.TextDocument("Some text", "text/plain", "doc.txt")
        val textNoFile = BinaryContent.TextDocument("Some text", "text/plain", null)

        assertEquals("file1.txt", bytesWithFile.getFilename())
        assertNull(bytesNoFile.getFilename())

        assertEquals("image.png", base64WithFile.getFilename())
        assertNull(base64NoFile.getFilename())

        assertEquals("video.mp4", cloudWithFile.getFilename())
        assertNull(cloudNoFile.getFilename())

        assertEquals("doc.txt", textWithFile.getFilename())
        assertNull(textNoFile.getFilename())
    }
}
