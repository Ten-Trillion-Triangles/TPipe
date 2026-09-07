package com.TTT.AgentCore.runtime

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** AgentCore Runtime interactive-shell channel identifiers. */
sealed interface AgentCoreShellChannel
{
    /** Wire channel byte. */
    val code: Int

    data object Stdin : AgentCoreShellChannel { override val code: Int = 0x00 }
    data object Stdout : AgentCoreShellChannel { override val code: Int = 0x01 }
    data object Stderr : AgentCoreShellChannel { override val code: Int = 0x02 }
    data object Status : AgentCoreShellChannel { override val code: Int = 0x03 }
    data object Resize : AgentCoreShellChannel { override val code: Int = 0x04 }
    data object Heartbeat : AgentCoreShellChannel { override val code: Int = 0x05 }
    data object Close : AgentCoreShellChannel { override val code: Int = 0xFF }
    data class Unknown(override val code: Int) : AgentCoreShellChannel

    companion object
    {
        /** Decode a channel byte while retaining unknown future channels. */
        fun fromCode(code: Int): AgentCoreShellChannel = when(code and 0xFF)
        {
            0x00 -> Stdin
            0x01 -> Stdout
            0x02 -> Stderr
            0x03 -> Status
            0x04 -> Resize
            0x05 -> Heartbeat
            0xFF -> Close
            else -> Unknown(code and 0xFF)
        }
    }
}

/** One AgentCore shell binary frame with its one-byte channel prefix. */
data class AgentCoreShellFrame(
    val channel: AgentCoreShellChannel,
    val payload: ByteArray
)
{
    init { require(payload.size <= MAX_PAYLOAD_BYTES) { "AgentCore shell payload must be at most 64 KiB." } }

    /** Encode the channel prefix followed by payload bytes. */
    fun encode(): ByteArray = byteArrayOf(channel.code.toByte()) + payload

    override fun equals(other: Any?): Boolean = other is AgentCoreShellFrame &&
        channel == other.channel && payload.contentEquals(other.payload)

    override fun hashCode(): Int = 31 * channel.hashCode() + payload.contentHashCode()

    companion object
    {
        const val MAX_PAYLOAD_BYTES: Int = 64 * 1024

        /** Decode one binary frame, retaining unknown channel bytes. */
        fun decode(bytes: ByteArray): AgentCoreShellFrame
        {
            require(bytes.isNotEmpty()) { "AgentCore shell frames require a channel byte." }
            return AgentCoreShellFrame(
                AgentCoreShellChannel.fromCode(bytes[0].toInt()),
                bytes.copyOfRange(1, bytes.size)
            )
        }
    }
}

/** Parsed terminal status carried by a shell STATUS frame. */
data class AgentCoreShellStatus(
    val status: String,
    val exitCode: Int? = null,
    val message: String? = null
)
{
    companion object
    {
        /** Parse JSON status payloads while accepting plain-text status values. */
        fun decode(payload: ByteArray): AgentCoreShellStatus
        {
            val text = payload.decodeToString()
            return runCatching {
                val json = Json.parseToJsonElement(text).jsonObject
                AgentCoreShellStatus(
                    status = json["status"]?.jsonPrimitive?.content ?: "UNKNOWN",
                    exitCode = json["exitCode"]?.jsonPrimitive?.intOrNull
                        ?: json["exit_code"]?.jsonPrimitive?.intOrNull,
                    message = json["message"]?.jsonPrimitive?.content
                )
            }.getOrElse { AgentCoreShellStatus(text) }
        }
    }
}

/** Bounded reconnect behavior for a Runtime shell session. */
data class AgentCoreShellReconnectConfig(
    val maxAttempts: Int = 3,
    val initialDelayMillis: Long = 100L,
    val maxDelayMillis: Long = 2_000L
)
{
    init
    {
        require(maxAttempts in 0..10) { "Shell reconnect attempts must be between 0 and 10." }
        require(initialDelayMillis >= 0L) { "Shell reconnect delay must not be negative." }
        require(maxDelayMillis >= initialDelayMillis) { "Shell max reconnect delay must be >= initial delay." }
    }
}

/** Validate a Runtime session identifier before any shell network operation. */
internal fun validateAgentCoreRuntimeSessionId(value: String): String = value.also {
    require(it.isNotBlank() && it.length <= 256 && it.all { char -> char.code in 0x21..0x7E }) {
        "Invalid AgentCore Runtime session id."
    }
}

/** Validate a shell identifier before it is inserted into a signed request. */
internal fun validateAgentCoreShellId(value: String): String = value.also {
    require(it.isNotBlank() && it.length <= 256 && it.matches(Regex("[A-Za-z0-9._:-]+"))) {
        "Invalid AgentCore shell id."
    }
}
