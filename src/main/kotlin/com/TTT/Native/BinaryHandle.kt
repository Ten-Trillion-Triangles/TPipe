package com.TTT.Native

import com.TTT.Pipe.*

/**
 * BinaryContent handle for the C ABI.
 *
 * Wraps TPipe's BinaryContent (4 variant types: bytes, base64, cloudRef, textDoc).
 * Each variant is discriminated by the BinaryVariant enum.
 *
 * @param variant The type discriminator for this binary content
 * @param bytes Raw byte array (for BYTES variant)
 * @param base64Data Base64-encoded string (for BASE64 variant)
 * @param cloudRef Cloud storage URI (for CLOUD_REF variant)
 * @param textDocRef Text document content (for TEXT_DOC variant)
 * @param mimeType The MIME type of the content
 * @param filename Optional filename associated with this content
 * @throws IllegalArgumentException if binary content exceeds MAX_BINARY_SIZE (100MB)
 */
class BinaryHandle(
    val variant: BinaryVariant,
    var bytes: ByteArray?,
    var base64Data: String?,
    var cloudRef: String?,
    var textDocRef: String?,
    var mimeType: String = "application/octet-stream",
    var filename: String? = null
) {
    /**
     * Discriminates the 4 binary content variant types.
     */
    enum class BinaryVariant {
        /** Raw binary data stored as byte array */
        BYTES,
        /** Base64 encoded binary data */
        BASE64,
        /** Reference to binary data stored in cloud storage */
        CLOUD_REF,
        /** Text content treated as a document */
        TEXT_DOC
    }

    /**
     * Validates that this handle's content does not exceed MAX_BINARY_SIZE.
     * @throws IllegalArgumentException if content exceeds the limit
     */
    private fun validateSize() {
        val size = when (variant) {
            BinaryVariant.BYTES -> bytes?.size?.toLong() ?: 0L
            BinaryVariant.BASE64 -> (base64Data?.length ?: 0).toLong()
            BinaryVariant.CLOUD_REF -> (cloudRef?.length ?: 0).toLong()
            BinaryVariant.TEXT_DOC -> (textDocRef?.length ?: 0).toLong()
        }
        if (size > MAX_BINARY_SIZE) {
            throw IllegalArgumentException(
                "Binary content size $size exceeds MAX_BINARY_SIZE $MAX_BINARY_SIZE"
            )
        }
    }

    /**
     * Converts this handle to TPipe's BinaryContent.
     *
     * @return BinaryContent in the appropriate variant form
     * @throws IllegalArgumentException if binary content exceeds MAX_BINARY_SIZE
     */
    fun toBinaryContent(): BinaryContent {
        validateSize()
        return when (variant) {
            BinaryVariant.BYTES -> BinaryContent.Bytes(
                data = bytes ?: ByteArray(0),
                mimeType = mimeType,
                filename = filename
            )
            BinaryVariant.BASE64 -> BinaryContent.Base64String(
                data = base64Data ?: "",
                mimeType = mimeType,
                filename = filename
            )
            BinaryVariant.CLOUD_REF -> BinaryContent.CloudReference(
                uri = cloudRef ?: "",
                mimeType = mimeType,
                filename = filename
            )
            BinaryVariant.TEXT_DOC -> BinaryContent.TextDocument(
                content = textDocRef ?: "",
                mimeType = mimeType,
                filename = filename
            )
        }
    }

    /**
     * Sanitize sensitive fields in this binary handle (GAP-15).
     * Zeros out string fields to prevent memory forensics.
     */
    fun sanitize() {
        bytes?.fill(0)
        base64Data = null
        cloudRef = null
        textDocRef = null
        filename = null
    }

    companion object {
        /** Maximum binary data size (100MB) - GAP-14 */
        const val MAX_BINARY_SIZE = 104857600L // 100MB

        /**
         * Create a BinaryHandle from a TPipe BinaryContent.
         *
         * @param bc The TPipe BinaryContent to wrap
         * @return A BinaryHandle representing the same content
         */
        fun fromBinaryContent(bc: BinaryContent): BinaryHandle {
            return when (bc) {
                is BinaryContent.Bytes -> BinaryHandle(
                    variant = BinaryVariant.BYTES,
                    bytes = bc.data,
                    base64Data = null,
                    cloudRef = null,
                    textDocRef = null,
                    mimeType = bc.mimeType,
                    filename = bc.filename
                )
                is BinaryContent.Base64String -> BinaryHandle(
                    variant = BinaryVariant.BASE64,
                    bytes = null,
                    base64Data = bc.data,
                    cloudRef = null,
                    textDocRef = null,
                    mimeType = bc.mimeType,
                    filename = bc.filename
                )
                is BinaryContent.CloudReference -> BinaryHandle(
                    variant = BinaryVariant.CLOUD_REF,
                    bytes = null,
                    base64Data = null,
                    cloudRef = bc.uri,
                    textDocRef = null,
                    mimeType = bc.mimeType,
                    filename = bc.filename
                )
                is BinaryContent.TextDocument -> BinaryHandle(
                    variant = BinaryVariant.TEXT_DOC,
                    bytes = null,
                    base64Data = null,
                    cloudRef = null,
                    textDocRef = bc.content,
                    mimeType = bc.mimeType,
                    filename = bc.filename
                )
            }
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as BinaryHandle
        return variant == other.variant &&
                bytes?.contentEquals(other.bytes) == true &&
                base64Data == other.base64Data &&
                cloudRef == other.cloudRef &&
                textDocRef == other.textDocRef &&
                mimeType == other.mimeType &&
                filename == other.filename
    }

    override fun hashCode(): Int {
        var result = variant.hashCode()
        result = 31 * result + (bytes?.contentHashCode() ?: 0)
        result = 31 * result + (base64Data?.hashCode() ?: 0)
        result = 31 * result + (cloudRef?.hashCode() ?: 0)
        result = 31 * result + (textDocRef?.hashCode() ?: 0)
        result = 31 * result + mimeType.hashCode()
        result = 31 * result + (filename?.hashCode() ?: 0)
        return result
    }

    /**
     * Creates a deep copy of this BinaryHandle.
     * The byte array is copied using clone() to ensure independence.
     * @return A new BinaryHandle with copied data
     */
    fun clone(): BinaryHandle {
        return BinaryHandle(
            variant = this.variant,
            bytes = this.bytes?.clone(),
            base64Data = this.base64Data,
            cloudRef = this.cloudRef,
            textDocRef = this.textDocRef,
            mimeType = this.mimeType,
            filename = this.filename
        )
    }
}