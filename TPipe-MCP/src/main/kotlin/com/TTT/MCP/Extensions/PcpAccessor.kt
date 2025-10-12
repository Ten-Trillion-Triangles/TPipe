package com.TTT.MCP.Extensions

import com.TTT.Pipe.Pipe
import com.TTT.PipeContextProtocol.PcpContext

/**
 * Extension property to access the protected pcpContext field from Pipe instances.
 * Uses reflection to safely access the protected field for MCP bridge operations.
 * 
 * @return The current PcpContext of this Pipe
 */
val Pipe.pcpContext: PcpContext
    get() = this.javaClass.getDeclaredField("pcpContext").let { field ->
        field.isAccessible = true
        field.get(this) as PcpContext
    }