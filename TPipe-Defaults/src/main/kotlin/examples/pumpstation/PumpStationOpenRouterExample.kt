package examples.pumpstation

import Defaults.OpenRouterConfiguration
import Defaults.PumpStationDefaults
import com.TTT.Pipe.MultimodalContent
import com.TTT.Pipeline.PumpStation
import com.TTT.Pipeline.PumpStationJudgeRunMode
import kotlinx.coroutines.runBlocking

/**
 * TPipe-Defaults PumpStation example: end-to-end harness against a live OpenRouter model.
 *
 * Demonstrates all 3 legitimate exit mechanisms of the `PumpStation` runtime harness
 * plus the independent kill-switch exit, using the `PumpStationDefaults.withOpenRouter`
 * factory. Each example is a self-contained `private suspend fun` invoked from `main` in
 * order; on success each prints "OK" and on failure prints the exception message.
 *
 * To run:
 * ```
 * export OPENROUTER_API_KEY=sk-or-...
 * ./gradlew :TPipe-Defaults:run --args="pumpStationOpenRouter"
 * ```
 *
 * The 4 examples:
 *  1. **Always-on judge** (exit mechanism 1 of 3): judge evaluates `isComplete` every turn.
 *  2. **FlagTriggered judge** (exit mechanism 2 of 3): path calls `requestJudgeNextTurn()`
 *     to opt-in to a judge evaluation; judge skips other turns.
 *  3. **PathPassPipeline** (exit mechanism 3 of 3): path returns `passPipeline = true` to
 *     signal the harness that the user task is done.
 *  4. **Kill switch trip**: a tight `inputTokenLimit` forces the kill switch to trip
 *     on a 3-turn task. Demonstrates the independent 4th exit.
 *
 * @see PumpStationDefaults.withOpenRouter for the factory
 * @see PumpStation for the runtime harness class
 */
fun main()
{
    val apiKey = System.getenv("OPENROUTER_API_KEY")
    if (apiKey.isNullOrBlank())
    {
        System.err.println("Set OPENROUTER_API_KEY before running this example.")
        return
    }

    val baseConfig = OpenRouterConfiguration(
        model = "openai/gpt-4o-mini",
        apiKey = apiKey,
        pipeCount = 1
    )

    runExample("1 (Always-on judge)") { example1_alwaysOnJudge(baseConfig) }
    runExample("2 (FlagTriggered judge)") { example2_flagTriggeredJudge(baseConfig) }
    runExample("3 (PathPassPipeline)") { example3_pathPassPipeline(baseConfig) }
    runExample("4 (KillSwitch trip)") { example4_killSwitchTrip(baseConfig) }
}

/**
 * Helper: run an example in a `runBlocking` scope, catching any exception and printing the result.
 */
private fun runExample(name: String, block: suspend () -> PumpStation)
{
    runCatching { runBlocking { block() } }
        .onSuccess { println("Example $name: OK -> ${it.getTaskState().exitReason}") }
        .onFailure { System.err.println("Example $name FAILED: ${it.message}") }
}

/**
 * Example 1: minimal station with an Always-on judge and one "answer" path.
 * The judge's default prompt asks the model to determine completion; once the model
 * says `isComplete = true`, the harness exits with `JudgeComplete`.
 */
private suspend fun example1_alwaysOnJudge(config: OpenRouterConfiguration): PumpStation
{
    val station = PumpStationDefaults.withOpenRouter(config) {
        path("answer") {
            description = "Produces a one-sentence answer and signals pass-pipeline."
            setExecutionFunction { content, _, _, _ ->
                MultimodalContent(text = "ok: ${content.text}").apply { passPipeline = true }
            }
        }
    }
    station.executeLocal(MultimodalContent(text = "Say 'hello' and stop."))
    return station
}

/**
 * Example 2: FlagTriggered judge mode. The path calls `station.requestJudgeNextTurn()`
 * to opt-in to a judge evaluation; the harness skips the judge on every other turn.
 * The default `PumpStationDefaults` configures the Always-on judge — we override to
 * FlagTriggered in this example's builder block.
 */
private suspend fun example2_flagTriggeredJudge(config: OpenRouterConfiguration): PumpStation
{
    val station = PumpStationDefaults.withOpenRouter(config) {
        judgeRunMode = PumpStationJudgeRunMode.FlagTriggered
        path("signalDone") {
            description = "Signals that the task is done by calling requestJudgeNextTurn on the station."
            setExecutionFunction { content, station, _, _ ->
                station.requestJudgeNextTurn()
                MultimodalContent(text = "done: ${content.text}")
            }
        }
    }
    station.executeLocal(MultimodalContent(text = "Count to 3 and signal done."))
    return station
}

/**
 * Example 3: path returns `passPipeline = true` to exit the harness cleanly.
 * This is the simplest exit mechanism — no judge needed, no request-judge toggle.
 * The harness exits as soon as the path returns a content with `passPipeline = true`.
 */
private suspend fun example3_pathPassPipeline(config: OpenRouterConfiguration): PumpStation
{
    val station = PumpStationDefaults.withOpenRouter(config) {
        // No judge set; rely on the path's passPipeline signal.
        judgeAgent = null
        path("finish") {
            description = "Immediately signals pass-pipeline; the harness exits as soon as this returns."
            setExecutionFunction { content, _, _, _ ->
                MultimodalContent(text = "finished: ${content.text}").apply { passPipeline = true }
            }
        }
    }
    station.executeLocal(MultimodalContent(text = "Run anything."))
    return station
}

/**
 * Example 4: tight kill switch that trips after a few turns. Demonstrates the
 * 4th independent exit — the kill switch fires on token usage regardless of judge state.
 * We override the recommended kill switch with a 100-token input cap.
 */
private suspend fun example4_killSwitchTrip(config: OpenRouterConfiguration): PumpStation
{
    val station = PumpStationDefaults.withOpenRouter(config) {
        // Override the recommended 50K/10K kill switch with a tight 100-token cap.
        killSwitchConfiguration = com.TTT.P2P.KillSwitch(
            inputTokenLimit = 100,
            outputTokenLimit = 50
        )
        path("chatter") {
            description = "Produces a long response that the kill switch will trip on."
            setExecutionFunction { content, _, _, _ ->
                // Echo a long-ish string; the judge + dispatch will chew through input tokens.
                MultimodalContent(text = "echo ${"x".repeat(200)}: ${content.text}")
            }
        }
    }
    station.executeLocal(MultimodalContent(text = "Echo this back, but in a very long way."))
    return station
}
