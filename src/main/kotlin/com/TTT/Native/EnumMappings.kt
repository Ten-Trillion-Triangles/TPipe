package com.TTT.Native

import com.TTT.Enums.PromptMode as KotlinPromptMode
import com.TTT.Enums.ProviderName as KotlinProviderName
import com.TTT.Enums.SummaryMode as KotlinSummaryMode
import com.TTT.Enums.ContextWindowSettings as KotlinContextWindowSettings
import com.TTT.Context.ConverseRole as KotlinConverseRole
import com.TTT.Context.StorageMode as KotlinStorageMode
import com.TTT.PipeContextProtocol.Transport as KotlinTransport
import com.TTT.PipeContextProtocol.Permissions as KotlinPermissions
import com.TTT.PipeContextProtocol.ParamType as KotlinParamType
import com.TTT.Debug.TraceEventType as KotlinTraceEventType
import com.TTT.Native.BinaryHandle.BinaryVariant as KotlinBinaryVariant

/**
 * Enum mappings between TPipe Kotlin enums and C ABI integer constants.
 * These values must match the enum definitions in tpipe-abi.h exactly.
 *
 * The C ABI uses integer constants for cross-language compatibility.
 * This object provides bidirectional conversion between Kotlin enum values
 * and their corresponding C ABI integer representations.
 */
object EnumMappings {

    //================================================================
    // ConverseRole — TPipe_ConverseRole
    // C ABI: TPIPE_ROLE_USER=0, TPIPE_ROLE_ASSISTANT=1, TPIPE_ROLE_SYSTEM=2,
    //        TPIPE_ROLE_TOOL=3, TPIPE_ROLE_FUNCTION=4, TPIPE_ROLE_VISUAL=5
    // Kotlin: developer=0, system=1, user=2, agent=3, assistant=4
    //================================================================
    enum class ConverseRole(private val cValue: Int) {
        USER(0),        // TPIPE_ROLE_USER
        ASSISTANT(1),   // TPIPE_ROLE_ASSISTANT
        SYSTEM(2),      // TPIPE_ROLE_SYSTEM
        TOOL(3),        // TPIPE_ROLE_TOOL
        FUNCTION(4),    // TPIPE_ROLE_FUNCTION
        VISUAL(5);      // TPIPE_ROLE_VISUAL

        companion object {
            fun fromInt(value: Int): ConverseRole = entries.find { it.cValue == value } ?: USER
            fun toInt(role: KotlinConverseRole): Int = when (role) {
                KotlinConverseRole.user -> USER.cValue
                KotlinConverseRole.assistant -> ASSISTANT.cValue
                KotlinConverseRole.system -> SYSTEM.cValue
                KotlinConverseRole.supervisor -> FUNCTION.cValue
                KotlinConverseRole.agent -> TOOL.cValue
                KotlinConverseRole.developer -> FUNCTION.cValue
                KotlinConverseRole.tool_response -> VISUAL.cValue
                KotlinConverseRole.pcp_response -> VISUAL.cValue
                KotlinConverseRole.mcp_response -> VISUAL.cValue
            }
        }
    }

    //================================================================
    // ProviderName — TPipe_ProviderName
    // C ABI: MINIMAX=0, OPENAI=1, ANTHROPIC=2, BEDROCK=3, OLLAMA=4,
    //        MISTRAL=5, GROQ=6, DEEPSEEK=7, TOGETHER=8, UNKNOWN=9,
    //        OPENROUTER=10, GENERIC_OPENAI=11
    // Kotlin: Aws, Nai, Gemini, Gpt, Ollama, OpenRouter
    //================================================================
    enum class ProviderName(private val cValue: Int) {
        MINIMAX(0),
        OPENAI(1),
        ANTHROPIC(2),
        BEDROCK(3),
        OLLAMA(4),
        MISTRAL(5),
        GROQ(6),
        DEEPSEEK(7),
        TOGETHER(8),
        UNKNOWN(9),         // Forward compatibility for providers not in C ABI
        OPENROUTER(10),     // TPIPE_PROVIDER_OPENROUTER
        GENERIC_OPENAI(11); // TPIPE_PROVIDER_GENERIC_OPENAI

        companion object {
            fun fromInt(value: Int): ProviderName = entries.find { it.cValue == value } ?: UNKNOWN
            fun toInt(provider: KotlinProviderName): Int = when (provider) {
                KotlinProviderName.Aws -> BEDROCK.cValue
                KotlinProviderName.Nai -> UNKNOWN.cValue  // Nai not in C ABI
                KotlinProviderName.Gemini -> UNKNOWN.cValue  // Gemini not in C ABI
                KotlinProviderName.Gpt -> OPENAI.cValue
                KotlinProviderName.Ollama -> OLLAMA.cValue
                KotlinProviderName.OpenRouter -> OPENROUTER.cValue
            }

            /**
             * Returns the C ABI integer for a GenericOpenAI provider.
             *
             * The Kotlin [KotlinProviderName] enum does not currently expose a
             * GenericOpenAI entry, but the C ABI shim accepts a provider id of
             * 11 to mean "OpenAI-compatible endpoint that is not the official
             * OpenAI service" (e.g. the `TPipe-GenericOpenAI` sub-module).
             * The C caller passes the integer 11 through the ABI and the JVM
             * side resolves it via this helper.
             *
             * @return the C ABI provider id (11) for GenericOpenAI.
             */
            @JvmStatic
            fun toIntGenericOpenAI(): Int = GENERIC_OPENAI.cValue
        }
    }

    //================================================================
    // PromptMode — TPipe_PromptMode
    // C ABI: AUTO=0, SYSTEM_ONLY=1, NO_CONTEXT=2, INJECT=3
    // Kotlin: singlePrompt, chat, internalContext
    //================================================================
    enum class PromptMode(private val cValue: Int) {
        AUTO(0),           // TPIPE_MODE_AUTO
        SYSTEM_ONLY(1),     // TPIPE_MODE_SYSTEM_ONLY
        NO_CONTEXT(2),     // TPIPE_MODE_NO_CONTEXT
        INJECT(3);          // TPIPE_MODE_INJECT

        companion object {
            fun fromInt(value: Int): PromptMode = entries.find { it.cValue == value } ?: AUTO
            fun toInt(mode: KotlinPromptMode): Int = when (mode) {
                KotlinPromptMode.singlePrompt -> AUTO.cValue
                KotlinPromptMode.chat -> SYSTEM_ONLY.cValue  // Approximate mapping
                KotlinPromptMode.internalContext -> NO_CONTEXT.cValue  // Approximate mapping
            }
        }
    }

    //================================================================
    // Transport — TPipe_Transport
    // C ABI: STDIO=0, HTTP=1, WEBSOCKET=2, GRPC=3
    // Kotlin: Auto, Stdio, Tpipe, Http, Python, Kotlin, JavaScript, Unknown
    //================================================================
    enum class Transport(private val cValue: Int) {
        STDIO(0),      // TPIPE_TRANSPORT_STDIO
        HTTP(1),       // TPIPE_TRANSPORT_HTTP
        WEBSOCKET(2),  // TPIPE_TRANSPORT_WEBSOCKET
        GRPC(3);       // TPIPE_TRANSPORT_GRPC

        companion object {
            fun fromInt(value: Int): Transport = entries.find { it.cValue == value } ?: STDIO
            fun toInt(transport: KotlinTransport): Int = when (transport) {
                KotlinTransport.Stdio -> STDIO.cValue
                KotlinTransport.Http -> HTTP.cValue
                KotlinTransport.Auto -> STDIO.cValue  // Auto defaults to STDIO
                KotlinTransport.Tpipe -> WEBSOCKET.cValue  // Approximate mapping
                KotlinTransport.Python -> WEBSOCKET.cValue  // Approximate mapping
                KotlinTransport.Kotlin -> WEBSOCKET.cValue  // Approximate mapping
                KotlinTransport.JavaScript -> WEBSOCKET.cValue  // Approximate mapping
                KotlinTransport.Unknown -> GRPC.cValue  // Approximate mapping
            }
        }
    }

    //================================================================
    // Permissions — TPipe_Permissions (bit flags)
    // C ABI: READ=(1<<0), WRITE=(1<<1), EXECUTE=(1<<2)
    // Kotlin: Read, Write, Delete, Execute
    // Note: Permissions are bit flags, not enum. Multiple can be combined.
    //================================================================
    object Permissions {
        const val READ = 1 shl 0    // 0x01
        const val WRITE = 1 shl 1   // 0x02
        const val EXECUTE = 1 shl 2 // 0x04

        fun fromInt(value: Int): Set<KotlinPermissions> = buildSet {
            if (value and READ != 0) add(KotlinPermissions.Read)
            if (value and WRITE != 0) add(KotlinPermissions.Write)
            if (value and (1 shl 2) != 0) add(KotlinPermissions.Execute)
            // Delete is not in C ABI - forward compatibility
        }

        fun toInt(permissions: Set<KotlinPermissions>): Int {
            var result = 0
            permissions.forEach { perm ->
                result = when (perm) {
                    KotlinPermissions.Read -> result or READ
                    KotlinPermissions.Write -> result or WRITE
                    KotlinPermissions.Delete -> result  // Delete not in C ABI
                    KotlinPermissions.Execute -> result or EXECUTE
                }
            }
            return result
        }
    }

    //================================================================
    // ParamType — TPipe_ParamType
    // C ABI: STRING=0, INT=1, FLOAT=2, BOOL=3, BINARY=4, LIST=5, MAP=6
    // Kotlin: String, Int, Bool, Float, Enum, List, Map, Object, Any
    //================================================================
    enum class ParamType(private val cValue: Int) {
        STRING(0),   // TPIPE_TYPE_STRING
        INT(1),      // TPIPE_TYPE_INT
        FLOAT(2),    // TPIPE_TYPE_FLOAT
        BOOL(3),     // TPIPE_TYPE_BOOL
        BINARY(4),   // TPIPE_TYPE_BINARY
        LIST(5),     // TPIPE_TYPE_LIST
        MAP(6);      // TPIPE_TYPE_MAP

        companion object {
            fun fromInt(value: Int): ParamType = entries.find { it.cValue == value } ?: STRING
            fun toInt(type: KotlinParamType): Int = when (type) {
                KotlinParamType.String -> STRING.cValue
                KotlinParamType.Int -> INT.cValue
                KotlinParamType.Bool -> BOOL.cValue
                KotlinParamType.Float -> FLOAT.cValue
                KotlinParamType.Enum -> STRING.cValue  // Enum mapped to string
                KotlinParamType.List -> LIST.cValue
                KotlinParamType.Map -> MAP.cValue
                KotlinParamType.Object -> MAP.cValue  // Object mapped to map
                KotlinParamType.Any -> STRING.cValue  // Any mapped to string
            }
        }
    }

    //================================================================
    // TraceEventType — TPipe_TraceEventType
    // C ABI: ENTER=0, EXIT=1, ERROR=2, INFO=3, DEBUG=4, WARNING=5
    // Kotlin: Very large enum with many more values
    // Note: C ABI has only 6 basic types, Kotlin has 100+ specific events
    //================================================================
    enum class TraceEventType(private val cValue: Int) {
        ENTER(0),     // TPIPE_TRACE_ENTER
        EXIT(1),      // TPIPE_TRACE_EXIT
        ERROR(2),     // TPIPE_TRACE_ERROR
        INFO(3),      // TPIPE_TRACE_INFO
        DEBUG(4),     // TPIPE_TRACE_DEBUG
        WARNING(5);   // TPIPE_TRACE_WARNING

        companion object {
            fun fromInt(value: Int): TraceEventType = entries.find { it.cValue == value } ?: INFO
            fun toInt(type: KotlinTraceEventType): Int = when (type) {
                // Map Kotlin trace event types to nearest C ABI equivalent
                KotlinTraceEventType.PIPE_START -> INFO.cValue
                KotlinTraceEventType.PIPE_END -> DEBUG.cValue
                KotlinTraceEventType.PIPE_SUCCESS -> DEBUG.cValue
                KotlinTraceEventType.PIPE_FAILURE -> ERROR.cValue
                KotlinTraceEventType.PIPE_TIMEOUT -> WARNING.cValue
                KotlinTraceEventType.PIPE_RETRY -> INFO.cValue
                else -> INFO.cValue  // Default for unmapped types
            }
        }
    }

    //================================================================
    // StorageMode — TPipe_StorageMode
    // C ABI: MEMORY=0, DISK=1, DISTRIBUTED=2
    // Kotlin: MEMORY_ONLY, MEMORY_AND_DISK, DISK_ONLY, DISK_WITH_CACHE, REMOTE
    //================================================================
    enum class StorageMode(private val cValue: Int) {
        MEMORY(0),       // TPIPE_STORAGE_MEMORY
        DISK(1),         // TPIPE_STORAGE_DISK
        DISTRIBUTED(2);  // TPIPE_STORAGE_DISTRIBUTED

        companion object {
            fun fromInt(value: Int): StorageMode = entries.find { it.cValue == value } ?: MEMORY
            fun toInt(mode: KotlinStorageMode): Int = when (mode) {
                KotlinStorageMode.MEMORY_ONLY -> MEMORY.cValue
                KotlinStorageMode.MEMORY_AND_DISK -> MEMORY.cValue  // Approximate
                KotlinStorageMode.DISK_ONLY -> DISK.cValue
                KotlinStorageMode.DISK_WITH_CACHE -> DISK.cValue  // Approximate
                KotlinStorageMode.REMOTE -> DISTRIBUTED.cValue
            }
        }
    }

    //================================================================
    // BinaryVariant — TPipe_BinaryVariant
    // C ABI: BYTES=0, BASE64=1, CLOUD_REF=2, TEXT_DOC=3
    // Kotlin: BYTES, BASE64, CLOUD_REF, TEXT_DOC
    // Note: Already in BinaryHandle.kt, reference from there
    //================================================================
    enum class BinaryVariant(private val cValue: Int) {
        BYTES(0),     // TPIPE_BINARY_BYTES
        BASE64(1),    // TPIPE_BINARY_BASE64
        CLOUD_REF(2), // TPIPE_BINARY_CLOUD_REF
        TEXT_DOC(3);  // TPIPE_BINARY_TEXT_DOC

        companion object {
            fun fromInt(value: Int): BinaryVariant = entries.find { it.cValue == value } ?: BYTES
            fun toInt(variant: KotlinBinaryVariant): Int = when (variant) {
                KotlinBinaryVariant.BYTES -> BYTES.cValue
                KotlinBinaryVariant.BASE64 -> BASE64.cValue
                KotlinBinaryVariant.CLOUD_REF -> CLOUD_REF.cValue
                KotlinBinaryVariant.TEXT_DOC -> TEXT_DOC.cValue
            }
        }
    }

    //================================================================
    // OperationStatus — for TPipe_AsyncHandle_poll
    // C ABI: PENDING=0, COMPLETE=1, FAILED=2
    // Not a Kotlin enum, used for async operation results
    //================================================================
    enum class OperationStatus(val cValue: Int) {
        PENDING(0),    // TPIPE_OPERATION_PENDING
        COMPLETE(1),   // TPIPE_OPERATION_COMPLETE
        FAILED(2);     // TPIPE_OPERATION_FAILED

        companion object {
            fun fromInt(value: Int): OperationStatus = entries.find { it.cValue == value } ?: PENDING
        }
    }

    //================================================================
    // LibraryState — TPipe_GetState return values
    // C ABI: UNINITIALIZED=0, INITIALIZING=1, READY=2, SHUTTING_DOWN=3, SHUTDOWN=4
    //================================================================
    enum class LibraryState(val cValue: Int) {
        UNINITIALIZED(0),  // TPIPE_STATE_UNINITIALIZED
        INITIALIZING(1),   // TPIPE_STATE_INITIALIZING
        READY(2),          // TPIPE_STATE_READY
        SHUTTING_DOWN(3),  // TPIPE_STATE_SHUTTING_DOWN
        SHUTDOWN(4);       // TPIPE_STATE_SHUTDOWN

        companion object {
            fun fromInt(value: Int): LibraryState = entries.find { it.cValue == value } ?: UNINITIALIZED
        }
    }
}