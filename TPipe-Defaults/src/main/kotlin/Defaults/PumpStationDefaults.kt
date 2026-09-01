package Defaults

import Defaults.providers.OpenRouterDefaults
import Defaults.providers.CodexDefaults
import com.TTT.Debug.TraceConfig
import com.TTT.P2P.KillSwitch
import com.TTT.Pipeline.DEFAULT_DISPATCH_PROMPT
import com.TTT.Pipeline.DEFAULT_JUDGE_PROMPT
import com.TTT.Pipeline.Pipeline
import com.TTT.Pipeline.PumpStation
import com.TTT.Pipeline.PumpStationBuilder
import com.TTT.Pipeline.PumpStationMemoryManagementMode
import com.TTT.Pipeline.PumpStationStage
import com.TTT.Pipeline.pumpStation
import codexPipe.auth.CodexAuthManager
import genericOpenAIPipe.GenericOpenAIPipe
import openrouterPipe.OpenRouterPipe

/**
 * Central factory for creating pre-configured `PumpStation` instances with provider-specific defaults.
 *
 * Mirrors the shape of [ManifoldDefaults] (which targets the `Manifold` container) but for the
 * `PumpStation` runtime-harness class. Each `with*` entry point returns a fully-wired station
 * ready to receive `executeLocal` calls; the caller may further customize the result by passing
 * a `build: PumpStationBuilder.() -> Unit` block.
 *
 * The recommended `recommendedMemoryConfig()` default is `(Truncation, 0.85)` so a first live
 * run is cheap to debug. Developers who want the v3 compaction path can assign
 * `memoryManagementMode = PumpStationMemoryManagementMode.Compaction` in the builder block.
 *
 * Cross-reference: the prompt strings used as the judge/dispatch defaults live in
 * `Pipeline/PumpStationDefaults.kt` (made public for this purpose).
 *
 * @see ManifoldDefaults.withOpenRouter for the Manifold-parity factory
 * @see PumpStationBuilder for the DSL surface applied on top of the factory output
 */
object PumpStationDefaults
{
    /**
     * Recommended memory configuration for a first live run.
     *
     * Defaults to `(Truncation, 0.85)`. Truncation is the pre-v3 path that has shipped in
     * the codebase the longest and is cheaper to debug on a first run. Developers who want
     * the v3 compaction default can assign `memoryManagementMode = Compaction` in the builder
     * block, or override this entire pair via the DSL.
     */
    fun recommendedMemoryConfig(): Pair<PumpStationMemoryManagementMode, Double> =
        PumpStationMemoryManagementMode.Truncation to 0.85

    /**
     * Recommended kill-switch configuration: a 50K input-token cap and a 10K output-token cap.
     * Generous enough for a 10-turn multi-path task on a small model, tight enough to halt
     * a runaway loop before the operator has to `kill -9` the JVM.
     */
    fun recommendedKillSwitchConfig(): KillSwitchConfig = KillSwitchConfig(
        inputTokenLimit = 50_000,
        outputTokenLimit = 10_000
    )

    /**
     * Creates a PumpStation instance configured for OpenRouter with optimized defaults.
     *
     * The returned station has:
     *  - `judgeAgent` = a single `OpenRouterPipe` pipeline with [DEFAULT_JUDGE_PROMPT]
     *  - `dispatchAgent` = a single `OpenRouterPipe` pipeline with [DEFAULT_DISPATCH_PROMPT]
     *  - `killSwitch` = the result of [recommendedKillSwitchConfig]
     *  - `memoryManagementMode` = the first element of [recommendedMemoryConfig]
     *  - `compactionThreshold` = the second element of [recommendedMemoryConfig]
     *  - `tracingEnabled` = true (developer can disable via the builder block by setting
     *    `tracingConfiguration = null` or to a disabled config)
     *
     * Note: the factory itself does NOT register any paths — `PumpStationBuilder.build()` requires
     * at least one path. The caller MUST register at least one path either in the `build` block
     * or by calling `addPath` / `addReservePath` on the returned station before `executeLocal`.
     *
     * @param configuration OpenRouter-specific configuration including model, API key, and endpoint settings
     * @param build Optional builder block applied after the defaults are wired; the developer can override
     *  any slot (judge, dispatch, killSwitch, memory, paths, etc.) and must register at least one path
     * @return Fully configured PumpStation instance ready for `executeLocal` after paths are registered
     * @throws IllegalArgumentException if configuration is invalid
     * @throws RuntimeException if OpenRouter provider is not available
     */
    fun withOpenRouter(
        configuration: OpenRouterConfiguration,
        configure: PumpStationBuilder<PumpStationStage.Initial>.() -> Unit = {}
    ): PumpStation
    {
        require(configuration.validate()) { "Invalid OpenRouter configuration: $configuration" }

        return try
        {
            pumpStation("openrouter-defaults") {
                // Assign defaults via the builder's public fields (DSL pattern).
                judgeAgent = buildJudgePipeline(configuration)
                dispatchAgent = buildDispatchPipeline(configuration)

                val ksConfig = recommendedKillSwitchConfig()
                killSwitchConfiguration = KillSwitch(
                    inputTokenLimit = ksConfig.inputTokenLimit,
                    outputTokenLimit = ksConfig.outputTokenLimit
                )

                val (mode, threshold) = recommendedMemoryConfig()
                memoryManagementMode = mode
                compactionThreshold = threshold

                tracingConfiguration = TraceConfig(enabled = true)

                // Apply caller's overrides last.
                configure()
            }
        }
        catch(e: Exception)
        {
            throw RuntimeException("Failed to create OpenRouter PumpStation: ${e.message}", e)
        }
    }

    /**
     * Creates a PumpStation using Codex OAuth for both its judge and dispatch agents.
     *
     * @param configuration Codex model, credential-store, and import settings.
     * @param configure Optional builder overrides applied after the defaults.
     * @return Configured PumpStation ready for paths to be registered.
     */
    fun withCodex(
        configuration: CodexConfiguration,
        configure: PumpStationBuilder<PumpStationStage.Initial>.() -> Unit = {}
    ): PumpStation
    {
        require(configuration.validate()) { "Invalid Codex configuration: $configuration" }
        return try
        {
            val authManager = CodexDefaults.createAuthManager(configuration)
            pumpStation("codex-defaults") {
                judgeAgent = buildCodexPipeline(configuration, authManager, DEFAULT_JUDGE_PROMPT)
                dispatchAgent = buildCodexPipeline(configuration, authManager, DEFAULT_DISPATCH_PROMPT)

                val ksConfig = recommendedKillSwitchConfig()
                killSwitchConfiguration = KillSwitch(
                    inputTokenLimit = ksConfig.inputTokenLimit,
                    outputTokenLimit = ksConfig.outputTokenLimit
                )

                val (mode, threshold) = recommendedMemoryConfig()
                memoryManagementMode = mode
                compactionThreshold = threshold
                tracingConfiguration = TraceConfig(enabled = true)
                configure()
            }
        }
        catch(e: Exception)
        {
            throw RuntimeException("Failed to create Codex PumpStation: ${e.message}", e)
        }
    }

    /**
     * Build a `Pipeline` of one `OpenRouterPipe` configured to act as the judge agent.
     * The pipe's system prompt is set to [DEFAULT_JUDGE_PROMPT] so the harness's prompt-injection
     * machinery renders the standard judge JSON schema request.
     */
    private fun buildJudgePipeline(config: OpenRouterConfiguration): Pipeline
    {
        val pipe: OpenRouterPipe = OpenRouterDefaults.createOpenRouterPipe(config)
        pipe.setSystemPrompt(DEFAULT_JUDGE_PROMPT)
        return Pipeline().apply { add(pipe) }
    }

    /**
     * Build a `Pipeline` of one `OpenRouterPipe` configured to act as the dispatch agent.
     * The pipe's system prompt is set to [DEFAULT_DISPATCH_PROMPT] so the harness's prompt-injection
     * machinery renders the path descriptor protocol and the standard dispatch JSON schema.
     */
    private fun buildDispatchPipeline(config: OpenRouterConfiguration): Pipeline
    {
        val pipe: OpenRouterPipe = OpenRouterDefaults.createOpenRouterPipe(config)
        pipe.setSystemPrompt(DEFAULT_DISPATCH_PROMPT)
        return Pipeline().apply { add(pipe) }
    }

    /** Builds one Codex agent pipeline with the supplied harness prompt. */
    private fun buildCodexPipeline(
        config: CodexConfiguration,
        authManager: CodexAuthManager,
        prompt: String,
    ): Pipeline
    {
        val pipe: GenericOpenAIPipe = CodexDefaults.createCodexPipe(config, authManager)
        pipe.setSystemPrompt(prompt)
        return Pipeline().apply { add(pipe) }
    }
}

/**
 * Data carrier for [PumpStationDefaults.recommendedKillSwitchConfig]. Plain `data class` so the
 * values can be unpacked into the [KillSwitch] constructor at the call site.
 *
 * @property inputTokenLimit Maximum input tokens (prompt + context). `null` disables the input limit.
 * @property outputTokenLimit Maximum output tokens (response + reasoning). `null` disables the output limit.
 */
data class KillSwitchConfig(
    val inputTokenLimit: Int? = 50_000,
    val outputTokenLimit: Int? = 10_000
)
