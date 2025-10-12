package com.TTT.PipeContextProtocol

import kotlinx.serialization.Serializable

/**
 * Represents a complete function signature with all metadata required for invocation.
 * Stores function name, parameters, return type, and invocation information needed
 * for automatic function binding and execution within the PCP protocol.
 */
@Serializable
data class FunctionSignature(
    val name: String,
    val parameters: List<ParameterInfo>,
    val returnType: ReturnTypeInfo,
    val description: String = "",
    val permissions: List<Permissions> = emptyList()
)

/**
 * Detailed parameter information including type, validation, and conversion metadata.
 * Contains all information needed to convert PCP string parameters to native types
 * and validate parameter values before function invocation.
 */
@Serializable
data class ParameterInfo(
    val name: String,
    val type: ParamType,
    val kotlinType: String,
    val isOptional: Boolean = false,
    val defaultValue: String? = null,
    val enumValues: List<String> = emptyList(),
    val description: String = ""
)

/**
 * Return type information for proper result handling and conversion.
 * Enables automatic conversion of function return values back to PCP-compatible
 * string format and proper handling of nullable return types.
 */
@Serializable
data class ReturnTypeInfo(
    val type: ParamType,
    val kotlinType: String,
    val isNullable: Boolean = false,
    val description: String = ""
)