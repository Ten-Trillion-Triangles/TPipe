package com.TTT.Native

/**
 * Configuration object for Pipe execution.
 *
 * Corresponds to TPipe's PipeSettings / BedrockPipe configuration.
 * All configuration is done via builder pattern before executing.
 *
 * @param model The model identifier for LLM calls (e.g., "anthropic.claude-3-7-sonnet-20250219-v1:0")
 * @param temperature Sampling temperature (0.0-1.0, default 0.7)
 * @param maxTokens Maximum tokens in response (default 4000)
 * @param timeoutMs Request timeout in milliseconds (default 60000)
 * @param providerName The LLM provider name (AWS, Ollama, etc.)
 * @param region Provider region (e.g., "us-east-1")
 * @param reasoning Number of reasoning tokens (0 = disabled)
 * @param systemPrompt Optional system prompt override
 * @param jsonOutput Optional JSON schema for structured output
 * @param temperatureOverride Override the Pipe's default temperature
 * @param topP Nucleus sampling parameter
 * @param topK Top-k sampling parameter
 * @param stopSequences Custom stop sequences
 */
class PipeSettingsHandle private constructor(
    var model: String = "anthropic.claude-3-7-sonnet-20250219-v1:0",
    var temperature: Float = 0.7f,
    var maxTokens: Int = 4000,
    var timeoutMs: Int = 60000,
    var providerName: String = "AWS",
    var region: String = "us-east-1",
    var reasoning: Int = 0,  // reasoning tokens, 0 = disabled
    var systemPrompt: String? = null,
    var jsonOutput: String? = null,  // optional JSON schema for structured output
    var temperatureOverride: Float? = null,  // override Pipe's default temperature
    var topP: Float? = null,  // nucleus sampling parameter
    var topK: Int? = null,  // top-k sampling parameter
    var stopSequences: List<String>? = null,  // custom stop sequences
    var repetitionPenalty: Float? = null  // repetition penalty parameter
) {
    fun setModel(model: String): PipeSettingsHandle {
        this.model = model
        return this
    }

    fun setTemperature(temperature: Float): PipeSettingsHandle {
        this.temperature = temperature
        return this
    }

    fun setMaxTokens(maxTokens: Int): PipeSettingsHandle {
        this.maxTokens = maxTokens
        return this
    }

    fun setTimeout(timeoutMs: Int): PipeSettingsHandle {
        this.timeoutMs = timeoutMs
        return this
    }

    fun setProvider(provider: String): PipeSettingsHandle {
        this.providerName = provider
        return this
    }

    fun setRegion(region: String): PipeSettingsHandle {
        this.region = region
        return this
    }

    fun setReasoning(reasoningTokens: Int): PipeSettingsHandle {
        this.reasoning = reasoningTokens
        return this
    }

    fun setSystemPrompt(prompt: String): PipeSettingsHandle {
        this.systemPrompt = prompt
        return this
    }

    fun setJsonOutput(schema: String): PipeSettingsHandle {
        this.jsonOutput = schema
        return this
    }

    fun setTopP(topP: Float): PipeSettingsHandle {
        this.topP = topP
        return this
    }

    fun setTopK(topK: Int): PipeSettingsHandle {
        this.topK = topK
        return this
    }

    fun setStopSequences(sequences: List<String>): PipeSettingsHandle {
        this.stopSequences = sequences
        return this
    }

    fun setRepetitionPenalty(penalty: Float): PipeSettingsHandle {
        this.repetitionPenalty = penalty
        return this
    }

    companion object {
        fun create(): PipeSettingsHandle = PipeSettingsHandle()
    }
}