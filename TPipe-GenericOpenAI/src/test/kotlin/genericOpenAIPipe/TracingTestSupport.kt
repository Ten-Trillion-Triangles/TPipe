package genericOpenAIPipe

import com.TTT.Config.TPipeConfig
import com.TTT.Debug.PipeTracer
import com.TTT.Debug.TraceConfig
import com.TTT.Debug.TraceDetailLevel
import com.TTT.Debug.TraceFormat
import com.TTT.Pipe.Pipe
import java.io.File

/**
 * Shared tracing helpers for the live LLM test classes in
 * [genericOpenAIPipe]. Centralizes the [TraceConfig] shape, the trace
 * subdirectory layout, and the [PipeTracer] pipeline-id registration so
 * every live test produces comparable artifacts under
 * [TPipeConfig.getTraceDir].
 *
 * Layout convention (matches the Bedrock live-test convention):
 *   `${TPipeConfig.getTraceDir()}/Library/<test-name>/`
 *
 * The `Library` segment keeps operator-curated traces grouped together
 * for diffing across test classes.
 */

/**
 * Compute the trace subdirectory for [testClass]. Returns an absolute
 * path under [TPipeConfig.getTraceDir], creating the directory tree on
 * disk so the trace writer has somewhere to land.
 *
 * @param testClass The test class to scope traces under. Its simple
 *                  name (e.g. `BedrockMantleLiveTest`) becomes the leaf
 *                  directory name; the kebab-case form (e.g.
 *                  `bedrock-mantle-live`) is used.
 */
fun setupTraceDirectory(testClass: Class<*>): String
{
    val subdir = "${TPipeConfig.getTraceDir()}/Library/${testClass.simpleName?.toKebabCase()}"
    File(subdir).mkdirs()
    return subdir
}

/**
 * Convert a CamelCase class name to kebab-case. `BedrockMantleLiveTest`
 * becomes `bedrock-mantle-live`, `MiniMaxLiveTest` becomes
 * `minimax-live`, etc.
 */
private fun String.toKebabCase(): String
{
    val builder = StringBuilder()
    for ((index, char) in this.withIndex())
    {
        when
        {
            char.isUpperCase() && index > 0 -> builder.append('-').append(char.lowercaseChar())
            char.isUpperCase() -> builder.append(char.lowercaseChar())
            else -> builder.append(char)
        }
    }
    return builder.toString()
}

/**
 * Build the canonical DEBUG-level [TraceConfig] used by every live LLM
 * test class. Detail level is [TraceDetailLevel.DEBUG] so the operator
 * gets every event with full metadata on a live run. Output format is
 * [TraceFormat.HTML] so the resulting trace report renders directly in
 * a browser without post-processing.
 */
fun traceConfig(): TraceConfig =
    TraceConfig(
        enabled = true,
        outputFormat = TraceFormat.HTML,
        detailLevel = TraceDetailLevel.DEBUG,
        includeContext = true,
        includeMetadata = true,
    )

/**
 * Wire up tracing on a pipe so that events are actually persisted to
 * the [PipeTracer] global store. Three things are required:
 *
 *   1. [PipeTracer.enable] — global gate (Pipe-base-class `trace()` early
 *      returns when the global tracer is disabled).
 *   2. `pipe.enableTracing(config)` — sets `tracingEnabled = true` on the
 *      pipe (otherwise `Pipe.trace()` early returns on line `if
 *      (!tracingEnabled) return`).
 *   3. `pipe.addTraceId(pipelineId)` — populates `activeTraceIds` so the
 *      `pipe.trace()` event loop has a destination key. Without this,
 *      events fire but never reach `PipeTracer.addEvent`.
 *
 * This helper does (2) and (3) in the right order. The caller is
 * responsible for (1) [PipeTracer.enable] in `@BeforeAll` and
 * [PipeTracer.disable] in `@AfterAll`.
 *
 * @param pipe The pipe to instrument.
 * @param pipelineId A stable id used to key the trace in
 *                   [PipeTracer.getAllTraces]. Per-class unique (e.g.
 *                   `"bedrock-mantle-live"`).
 */
fun instrumentPipeForTracing(pipe: Pipe, pipelineId: String)
{
    PipeTracer.startTrace(pipelineId)
    pipe.enableTracing(traceConfig())
    pipe.addTraceId(pipelineId)
}
