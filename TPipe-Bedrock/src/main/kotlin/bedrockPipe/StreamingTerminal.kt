package bedrockPipe

import com.TTT.Pipeline.Pipeline
import com.TTT.Pipe.Pipe

/**
 * Enables streaming output on each provided pipe (and its reasoning pipe) and prints incoming chunks to the terminal.
 *
 * This helper ensures that every Bedrock-backed pipe in the set emits tokens as they arrive, including any
 * reasoning pipes that have been associated via [Pipe.setReasoningPipe].
 */
fun streamOutputToTerminal(vararg pipes: Pipe) {
    streamOutputToTerminal(pipes.asList())
}

/**
 * Enables streaming output on the provided pipes and their reasoning workflows.
 */
fun streamOutputToTerminal(pipes: Iterable<Pipe>) {
    val configured = mutableSetOf<Pipe>()
    for (pipe in pipes) {
        configureStreamingForPipe(pipe, configured, isReasoning = false)
    }
}

private fun configureStreamingForPipe(
    pipe: Pipe?,
    configured: MutableSet<Pipe>,
    isReasoning: Boolean
) {
    if (pipe == null || !configured.add(pipe)) {
        return
    }

    configureBedrockStreaming(pipe, isReasoning)
    configureStreamingForPipe(pipe.reasoningPipe, configured, isReasoning = true)
}

private fun configureBedrockStreaming(pipe: Pipe, isReasoning: Boolean) {
    if (pipe !is BedrockPipe) {
        return
    }

    val label = buildStreamLabel(pipe, isReasoning)
    pipe.enableStreaming()
        .setStreamingCallback({ chunk: String ->
            printChunk(label, chunk)
        } as (String) -> Unit)
}

private fun buildStreamLabel(pipe: Pipe, isReasoning: Boolean): String {
    val baseLabel = pipe.pipeName.takeIf(String::isNotBlank) ?: pipe::class.simpleName ?: "Pipe"
    return if (isReasoning) "$baseLabel (reasoning)" else baseLabel
}

private fun printChunk(label: String, chunk: String) {
    print("[$label]$chunk")
    System.out.flush()
}

/**
 * Enables streaming output for every pipe registered with the pipeline.
 */
fun streamPipelineOutputToTerminal(pipeline: Pipeline) {
    streamOutputToTerminal(pipeline.getPipes())
}
