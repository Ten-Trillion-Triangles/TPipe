package com.TTT.MCP.Models

import com.TTT.PipeContextProtocol.PcpContext
import kotlinx.serialization.Serializable

/**
 * Result of MCP-PCP conversion operations containing status and context information.
 * 
 * @property success Whether the conversion operation succeeded
 * @property pcpContext The resulting PCP context if conversion was successful
 * @property errors List of error messages if conversion failed
 * @property warnings List of warning messages for non-critical issues
 */
@Serializable
data class ConversionResult(
    val success: Boolean,
    val pcpContext: PcpContext? = null,
    val errors: List<String> = emptyList(),
    val warnings: List<String> = emptyList()
)