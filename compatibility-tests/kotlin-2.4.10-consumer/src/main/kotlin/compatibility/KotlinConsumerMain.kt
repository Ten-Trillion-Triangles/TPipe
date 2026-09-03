package compatibility

import com.TTT.PipeContextProtocol.KotlinContext
import com.TTT.PipeContextProtocol.KotlinExecutor
import com.TTT.PipeContextProtocol.PcPRequest
import com.TTT.PipeContextProtocol.PcpContext
import kotlinx.coroutines.runBlocking

/** Verifies Kotlin 2.4.10 source compatibility with the published artifact. */
fun main() = runBlocking {
    val executor = KotlinExecutor()
    val hostValue = KotlinHostValue(4)
    executor.registerBinding("hostValue", hostValue)
    val context = PcpContext().apply {
        kotlinOptions.allowHostApplicationAccess = true
        kotlinOptions.exposedBindings["hostValue"] = "consumer host"
    }
    val request = PcPRequest(
        kotlinContextOptions = KotlinContext(cinit = true),
        argumentsOrFunctionParams = listOf("hostValue.increment(); hostValue.value")
    )
    val execution = executor.execute(request, context)
    check(execution.success) { execution.error ?: "consumer script failed" }
    check(execution.output == "Result: 5") { execution.output }
    check(hostValue.value == 5) { "Consumer binding was not mutated: ${hostValue.value}" }
    println("Kotlin 2.4.10 consumer compatibility passed")
}

class KotlinHostValue(var value: Int)
{
    fun increment()
    {
        value += 1
    }
}
