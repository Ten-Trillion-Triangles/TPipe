# TPipe-Bedrock Findings

## LLM call orchestration in `bedrockPipe.BedrockPipe`
- `BedrockPipe` inherits `Pipe` and wires `aws.sdk.kotlin.services.bedrockruntime` clients in `init()`, loading the `~/.aws/inference.txt` map via `bedrockEnv` so provisioned-throughput inference profiles can replace the plain model ID when available.
- The pipe sets defaults (Claude 3 Sonnet) and enforces region requirements (OpenAI GPT-OSS -> `us-west-2`) before building a `BedrockRuntimeClient` with `OkHttpEngine` and the configurable `readTimeoutSeconds`, since some models take up to 60 minutes.
- `generateText` (and its multimodal sibling) choose between Converse vs. legacy Invoke APIs controlled by `useConverseApi`; when streaming is toggled, it tries the streaming endpoints (`InvokeModelWithResponseStream` or `ConverseStream`) first, emitting chunks through the registered callback and falling back to the non-streaming response if streaming fails or is disabled.
- All calls are wrapped in tracing (`TraceEventType`) so every step (API selection, streaming, overflow detection, metadata) is logged, and errors return an empty string while still logging the failure details.

## Model-specific request/response handling
- Each foundation model family has a dedicated builder that maps TPipe parameters (`maxTokens`, `temperature`, `topP/topK`, `stopSequences`, `systemPrompt`, caching, tools) to the model’s expected JSON or Converse structure: Claude, Nova, Titan, AI21 Jurassic, Cohere Command, Meta Llama, Mistral, Qwen3, DeepSeek R1, GPT-OSS, plus a catch-all generic builder and Converse variants for each.
- GPT-OSS building reuses commodity structures but also injects PCP context, tooling instructions, and `reasoning_effort` inside `additionalModelRequestFields`; the same builder path is used by Converse streaming builders, which also mirror inference config and service tier.
- Responses are parsed by `extractTextFromResponse`, `extractReasoningContent`, `extractStopReasonFromInvokeResponse`, and `extractTokenUsageFromInvokeResponse` with special-case logic for each family (e.g., `choices.message.content` for GPT-OSS, `output.message.content` for Nova, `content[0].text` for Claude, `results` for Titan), so TPipe consistently returns just the text plus optional reasoning metadata.
- When streaming, `executeInvokeStream` and `executeConverseStream` collect partial chunks, decode various possible JSON payload shapes, track reasoning deltas and usage/events, and apply `allowMaxTokenOverflow` rules; streaming chunks are emitted through `emitStreamingChunk`, and overflow errors are traced rather than thrown.

## AWS Bedrock features exposed by the pipe
- Priority tiers are exposed via `BedrockPriorityTier` and mapped to `ServiceTierType`; every invoke/converse call sets `serviceTier = mapServiceTier()` to respect reserved/priority/flex samples.
- Caching can be enabled through `enableCaching(control: String)` and is injected into Claude/Nova requests via `cache_control` blocks; tool definitions and tool choice are also surfaced so Claude models can coordinate function calling from TPipe.
- Streaming is a first-class option: `enableStreaming` toggles streaming on (with optional callbacks) for both the Invoke and Converse APIs, and `setStreamingCallback` registers suspendable handlers.
- Token-overflow handling is centralized via `isMaxTokenStopReason`, `allowMaxTokenOverflow`, and reasoning-enabled metadata, ensuring consistent behavior across different AWS stop-reason conventions.
- The pipe exposes helpers for reasoning (`useModelReasoning`/`modelReasoningSettingsV3`) and PCP context injection, turning these into additional Converse request blocks or GPT-OSS `additionalModelRequestFields` when supported.

## Multimodal support via `BedrockMultimodalPipe`
- `BedrockMultimodalPipe` extends `BedrockPipe`, overriding `generateContent` to route through Converse (preferred) or the legacy Invoke API when necessary, reusing the parent’s request/response helpers for text reasoning but adding binary handling.
- Binary inputs are converted to AWS `ContentBlock` instances (`Image`, `Document`, etc.) via MIME-to-format helpers; the Converse path feeds these blocks directly, while the Invoke fallback serializes binary content to descriptive text snippets so the JSON-only endpoint still sees the inputs.
- Converse responses are reverse-converted to `BinaryContent` (`Bytes`, `CloudReference`, `TextDocument`) when Bedrock returns images/documents, and reasoning extraction reuses `extractReasoningFromConverseResponse`; streaming is also supported for multimodal converse calls.
- GPT-OSS and DeepSeek require bespoke handling inside the multimodal pipe for conversing when reasoning or unknown content blocks are involved, reusing the general-purpose `generateGptOssWithConverseApiAndResponse` helper.

## Context & operational notes
- Context window tuning in `truncateModuleContext()` dynamically adjusts truncation strategy/counters based on the current model family (Claude, Nova, Llama, Qwen, DeepSeek, etc.), ensuring tokens are counted with heuristics like favoring whole words or adjusting `contextWindowSize` before invoking Bedrock.
- Multimodal helper utilities (`convertBinaryToContentBlock`, MIME mapping, PCP serialization) keep AWS-specific payload construction centralized, letting Bedrock-specific models seamlessly accept text, image, and document data.
- Tracing around every API call captures metadata (prompt length, region/model, streaming flag, service tier, token usage), which assists debugging and auditing the pipe’s orchestration of AWS Bedrock features.
