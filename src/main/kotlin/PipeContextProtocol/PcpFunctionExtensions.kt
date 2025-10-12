package com.TTT.PipeContextProtocol

import com.TTT.Pipe.Pipe
import kotlin.reflect.KFunction

/**
 * Extensions to PcpContext for native function binding support.
 * Automatically registers functions and adds them to PCP context for LLM visibility.
 * 
 * @param name The name to register the function under
 * @param function The KFunction to bind
 * @return This PcpContext for method chaining
 */
fun PcpContext.bindFunction(name: String, function: KFunction<*>): PcpContext 
{
    // Register function in global registry
    val signature = FunctionRegistry.registerFunction(name, function)
    
    // Convert to TPipe context option and add to PCP
    val tpipeOption = TPipeContextOptions().fromFunctionSignature(signature)
    this.addTPipeOption(tpipeOption)
    
    return this
}

/**
 * Extensions to TPipeContextOptions for enhanced function metadata.
 * Converts function signature to PCP-compatible format for LLM consumption.
 * 
 * @param signature The function signature to convert
 * @return This TPipeContextOptions populated with signature data
 */
fun TPipeContextOptions.fromFunctionSignature(signature: FunctionSignature): TPipeContextOptions 
{
    this.functionName = signature.name
    this.description = signature.description
    
    // Convert parameters to PCP format
    signature.parameters.forEach { param ->
        val paramTriple = Triple(param.type, param.description, param.enumValues)
        this.params[param.name] = paramTriple
    }
    
    return this
}

/**
 * Extensions to Pipe class for function binding.
 * Main user-facing API for binding native functions to pipes.
 * 
 * @param name The name to register the function under
 * @param function The KFunction to bind
 * @return This Pipe for method chaining
 */
fun Pipe.bindNativeFunction(name: String, function: KFunction<*>): Pipe 
{
    // Get or create PCP context
    val currentContext = this.getPcpContext()
    
    // Bind function to context
    currentContext.bindFunction(name, function)
    
    // Update pipe's PCP context
    this.setPcPContext(currentContext)
    
    return this
}

/**
 * Lambda function binding extension for simplified usage.
 * Allows binding lambda expressions with explicit signature.
 * 
 * @param name The name to register the lambda under
 * @param lambda The lambda function to bind
 * @param signature The explicit function signature
 * @return This Pipe for method chaining
 */
fun <T> Pipe.bindLambda(name: String, lambda: T, signature: FunctionSignature): Pipe 
{
    // Register lambda in registry
    FunctionRegistry.registerLambda(name, lambda, signature)
    
    // Add to PCP context
    val currentContext = this.getPcpContext()
    val tpipeOption = TPipeContextOptions().fromFunctionSignature(signature)
    currentContext.addTPipeOption(tpipeOption)
    this.setPcPContext(currentContext)
    
    return this
}

/**
 * Helper to get current PCP context from pipe, creating if needed.
 */
fun Pipe.getPcpContext(): PcpContext 
{
    // Use reflection to access protected pcpContext field
    val field = Pipe::class.java.getDeclaredField("pcpContext")
    field.isAccessible = true
    return field.get(this) as PcpContext? ?: PcpContext()
}