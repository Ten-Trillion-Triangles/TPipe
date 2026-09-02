package com.TTT.AgentCore.memory

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json

/** Core-compatible JSON decoding for exact TPipe memory payloads. */
@OptIn(ExperimentalSerializationApi::class)
internal object AgentCoreMemoryJson {
    private val format = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
        explicitNulls = false
        coerceInputValues = true
        allowSpecialFloatingPointValues = true
        allowStructuredMapKeys = true
        useArrayPolymorphism = false
        useAlternativeNames = true
        allowTrailingComma = true
        allowComments = true
        decodeEnumsCaseInsensitive = true
    }

    /** Decode an exact TPipe value with the same leniency as Core [com.TTT.Util.deserialize]. */
    fun <T> decode(serialized: String, serializer: KSerializer<T>): T
    {
        return format.decodeFromString(serializer, serialized)
    }
}
