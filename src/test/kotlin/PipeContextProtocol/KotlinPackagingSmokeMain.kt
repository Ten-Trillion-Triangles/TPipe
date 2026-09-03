package com.TTT.PipeContextProtocol

import kotlinx.coroutines.runBlocking

/**
 * Small main-class runner used by thin- and shadow-JAR packaging checks.
 */
object KotlinPackagingSmokeMain
{
    @JvmStatic
    fun main(args: Array<String>) = runBlocking {
        val executor = KotlinExecutor()
        val simpleRequest = PcPRequest(
            kotlinContextOptions = KotlinContext(cinit = true),
            argumentsOrFunctionParams = listOf("val x = 2", "x * 2")
        )
        val simpleExecution = executor.execute(simpleRequest, PcpContext())
        check(simpleExecution.success) { "simple smoke execution failed: ${simpleExecution.error}" }
        check(simpleExecution.output == "Result: 4") { simpleExecution.output }

        executor.registerBinding("packagingHost", KotlinPackagingHost(9))
        val bindingContext = PcpContext().apply {
            kotlinOptions.allowHostApplicationAccess = true
            kotlinOptions.exposedBindings["packagingHost"] = "smoke host"
        }
        val bindingRequest = PcPRequest(
            kotlinContextOptions = KotlinContext(cinit = true),
            argumentsOrFunctionParams = listOf("packagingHost.value")
        )
        val bindingExecution = executor.execute(bindingRequest, bindingContext)
        check(bindingExecution.success) { "binding smoke execution failed: ${bindingExecution.error}" }
        check(bindingExecution.output == "Result: 9") { bindingExecution.output }
        print("Kotlin scripting packaging smoke passed")
    }
}

/** Host-consumer type used to prove application-class visibility from a script. */
class KotlinPackagingHost(val value: Int)
