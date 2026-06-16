package com.TTT.Pipeline

import com.TTT.Context.ConverseData
import com.TTT.PipeContextProtocol.FunctionRegistry
import com.TTT.PipeContextProtocol.PcpContext
import com.TTT.PipeContextProtocol.TPipeContextOptions
import com.TTT.PipeContextProtocol.fromFunctionSignature
import com.TTT.Util.serialize
import kotlin.reflect.KFunction

/**
 * Binds a Kotlin function to this PathObject, registering it in [FunctionRegistry] and
 * storing a serialized [PcpContext] JSON schema in [PathObject.pcpSchema].
 *
 * This enables the dispatch agent to see this path as a PCP-callable function with
 * full parameter schema when deciding which path to invoke.
 *
 * Follows the same pattern as [com.TTT.PipeContextProtocol.bindNativeFunction] on [com.TTT.Pipe.Pipe].
 *
 * @param name The name to register the function under. Must match the functionName the dispatch
 *             agent will emit when requesting this path.
 * @param function The KFunction to bind. Must be a top-level function or bound member reference.
 * @return This PathObject for method chaining.
 * @throws IllegalArgumentException if name is blank.
 */
fun PathObject.bindFunction(name: String, function: KFunction<*>): PathObject
{
    require(name.isNotBlank())
    {
        "Function name must not be blank"
    }

    // Step 1: Register function in FunctionRegistry (creates FunctionSignature via reflection)
    FunctionRegistry.registerFunction(name, function)

    // Step 2: Build TPipeContextOptions from the registered signature (LLM-readable schema)
    val signature = FunctionRegistry.getSignature(name)
        ?: throw IllegalStateException("Function '$name' was registered but signature is not retrievable")

    val tpipeOption = TPipeContextOptions().fromFunctionSignature(signature)

    // Step 3: Initialize pcpSchema if null, then add the new tpipeOption
    if(this.pcpSchema == null)
    {
        this.pcpSchema = PcpContext()
    }
    this.pcpSchema!!.addTPipeOption(tpipeOption)

    return this
}

/**
 * Retrieve stashed content from the parent PumpStation by stash ID.
 * Returns null if no station is set or no stash entry exists with that ID.
 */
fun PathObject.getStashContent(stashId: String, station: PumpStation?): ConverseData?
{
    if (station == null) return null
    return station.retrieveStash(stashId)
}