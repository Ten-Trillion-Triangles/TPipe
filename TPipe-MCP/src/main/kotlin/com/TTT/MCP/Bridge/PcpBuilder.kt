package com.TTT.MCP.Bridge

import com.TTT.PipeContextProtocol.*

/**
 * Builder for constructing PCP contexts programmatically.
 */
class PcpBuilder 
{
    private val pcpContext = PcpContext()

    /**
     * Returns the built PCP context.
     * 
     * @return The constructed PcpContext
     */
    fun buildPcpContext(): PcpContext 
    {
        // Return the constructed PCP context
        return pcpContext
    }

    /**
     * Adds a TPipe function to the PCP context.
     * 
     * @param name The function name
     * @param description Function description
     * @param params Map of parameter names to type information
     * @return This builder for method chaining
     */
    fun addTPipeFunction(
        name: String, 
        description: String, 
        params: Map<String, ContextOptionParameter>
    ): PcpBuilder 
    {
        // Create TPipe context option with provided parameters
        val option = TPipeContextOptions().apply {
            functionName = name
            this.description = description
            this.params = params.toMutableMap()
        }
        // Add the option to the PCP context
        pcpContext.addTPipeOption(option)
        return this
    }

    /**
     * Adds a stdio command to the PCP context.
     * 
     * @param command The shell command
     * @param args Command arguments
     * @param permissions List of allowed permissions
     * @return This builder for method chaining
     */
    fun addStdioCommand(
        command: String, 
        args: List<String>, 
        permissions: List<Permissions>
    ): PcpBuilder 
    {
        // Create stdio context option with provided command and permissions
        val option = StdioContextOptions().apply {
            this.command = command
            this.args = args.toMutableList()
            this.permissions = permissions.toMutableList()
        }
        // Add the option to the PCP context
        pcpContext.addStdioOption(option)
        return this
    }
}