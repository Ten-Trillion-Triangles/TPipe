package com.TTT.PipeContextProtocol

/**
 * Runs the intentionally non-terminating Kotlin timeout case in an isolated
 * JVM. The executor returns its outward timeout while the daemon script thread
 * is reclaimed when this child exits.
 */
object KotlinTimeoutProbeMain
{
    @JvmStatic
    fun main(args: Array<String>) = kotlinx.coroutines.runBlocking {
        val executor = KotlinExecutor()
        val sleeping = args.firstOrNull() == "sleep"
        val script = if(sleeping) "Thread.sleep(Long.MAX_VALUE)" else "while (true) { }"
        val timeoutMs = if(sleeping) 100 else 200
        val request = PcPRequest(argumentsOrFunctionParams = listOf(script))
        val context = PcpContext().apply { kotlinOptions.timeoutMs = timeoutMs }
        val start = System.currentTimeMillis()
        val probeResult = executor.execute(request, context)
        val elapsed = System.currentTimeMillis() - start
        print("${probeResult.error ?: "missing timeout error"}|elapsedMs=$elapsed")
    }
}
