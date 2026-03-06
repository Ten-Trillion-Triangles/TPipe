package bedrockPipe

import com.TTT.Pipeline.Pipeline
import com.TTT.Pipe.Pipe

/**
 * Enables streaming output on each provided pipe (and its reasoning pipe) and prints incoming chunks to the terminal.
 *
 * This helper ensures that every Bedrock-backed pipe in the set emits tokens as they arrive, including any
 * reasoning pipes that have been associated via [Pipe.setReasoningPipe].
 */
fun streamOutputToTerminal(vararg pipes: Pipe)
{
    streamOutputToTerminal(pipes.asList())
}

/**
 * Enables streaming output on the provided pipes and their reasoning workflows.
 */
fun streamOutputToTerminal(pipes: Iterable<Pipe>)
{
    val configured = mutableSetOf<Pipe>()
    for(pipe in pipes)
    {
        configureStreamingForPipe(pipe, configured)
    }
}

private fun configureStreamingForPipe(
    pipe: Pipe?,
    configured: MutableSet<Pipe>,
) {
    if(pipe == null || !configured.add(pipe))
    {
        return
    }

    configureBedrockStreaming(pipe)
    configureStreamingForPipe(pipe.reasoningPipe, configured)
}

private fun configureBedrockStreaming(pipe: Pipe)
{
    if(pipe !is BedrockPipe)
    {
        return
    }

    pipe.enableStreaming()
        .setStreamingCallback({ chunk: String ->
            printChunk(chunk)
        } as (String) -> Unit)
}

private fun printChunk(chunk: String)
{
    print(chunk)
    System.out.flush()
}

/**
 * Enables streaming output for every pipe registered with the pipeline.
 */
fun streamPipelineOutputToTerminal(pipeline: Pipeline)
{
    streamOutputToTerminal(pipeline.getPipes())
}
