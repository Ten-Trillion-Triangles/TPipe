# Bedrock Mantle

## Table of Contents
- [Overview](#overview)
- [What Mantle is](#what-mantle-is)
- [When to use Mantle vs `BedrockPipe` (Converse)](#when-to-use-mantle-vs-bedrockpipe-converse)
- [Quick Start](#quick-start)
- [Models](#models)
- [Authentication](#authentication)
- [Environment Variable Reference](#environment-variable-reference)
- [Reasoning on Mantle](#reasoning-on-mantle)
- [Streaming](#streaming)
- [Function Calling, Structured Output, Tool Choice](#function-calling-structured-output-tool-choice)
- [Error Conditions](#error-conditions)
- [API Reference](#api-reference)
- [Next Steps](#next-steps)

## Overview

Bedrock Mantle is an AWS endpoint surface that exposes selected foundation models over an OpenAI-compatible HTTP wire format. The TPipe integration lives in the `TPipe-GenericOpenAI` module and is wired through `GenericOpenAIPipe` — the same pipe that drives OpenAI, Anthropic (Messages), and OpenAI Responses traffic. Mantle does **not** go through `BedrockPipe`.

```kotlin
val pipe = GenericOpenAIPipe()
    .setBedrockMantle(region = "us-east-2", modelId = "google.gemma-4-31b")
    .setMaxTokens(64)
    .init()

val response = pipe.execute("What is the capital of France?")
```

The pipe handles endpoint construction, authentication header signing, request serialization, SSE wire parsing, and the special short-form reasoning event names Mantle uses.

## What Mantle is

Mantle is documented by AWS as a regional endpoint surface that accepts OpenAI's `/v1/chat/completions` and `/v1/responses` request shapes. The URL pattern is:

```
https://bedrock-mantle.{region}.api.aws/openai/v1
```

The endpoint exists separately from `bedrock-runtime`. Mantle is selected for models that AWS chooses to expose over the OpenAI-compatible shape. AWS recommends preferring the Mantle endpoint over `bedrock-runtime` whenever the model is available there.

Mantle does **not** speak the AWS Converse API. Models that Mantle exposes cannot be reached through `BedrockPipe` (`Converse`/`ConverseStream`); they must be reached through `GenericOpenAIPipe` with the Mantle endpoint and either Bearer or SigV4 authentication.

## When to use Mantle vs `BedrockPipe` (Converse)

| Concern | Use `GenericOpenAIPipe` + `setBedrockMantle(...)` | Use `BedrockPipe` |
|---------|---------------------------------------------------|------------------|
| Endpoint URL | `https://bedrock-mantle.{region}.api.aws/openai/v1` | `https://bedrock-runtime.{region}.amazonaws.com` |
| Wire format | OpenAI `/v1/chat/completions` or `/v1/responses` | AWS Converse / ConverseStream |
| Models available | Mantle-selected subset (see [Models](#models)) | All Bedrock-hosted models |
| Request builder | Standard `GenericOpenAI*` builders | Bedrock-specific request builders per model family |
| Reasoning surface | `MultimodalContent.modelReasoning` | `MultimodalContent.modelReasoning` |
| Streaming | SSE with Mantle-specific event names | EventStream with ConverseStream events |

If the model you want is available on both surfaces, prefer Mantle when the rest of your application already targets OpenAI-shaped endpoints.

## Quick Start

The minimum configuration is a region and a model identifier. Authentication is resolved automatically from environment variables.

```kotlin
import genericOpenAIPipe.GenericOpenAIPipe

val pipe = GenericOpenAIPipe()
    .setBedrockMantle(region = "us-east-2", modelId = "google.gemma-4-31b")
    .setMaxTokens(64)
    .setTemperature(0.0)
    .init()

println(pipe.execute("Reply with the single word 'pong'."))
// pong
```

For the Responses API wire format (when the model supports `output` items of type `reasoning`, `function_call`, etc.), use `setBedrockMantleWithResponses`:

```kotlin
val pipe = GenericOpenAIPipe()
    .setBedrockMantleWithResponses(region = "us-east-2", modelId = "google.gemma-4-31b")
    .setMaxTokens(32)
    .setReasoningConfig(ReasoningConfig(effort = "high"))
    .init()
```

`setBedrockMantle` selects `ApiMode.OpenAI` (Chat Completions endpoint). `setBedrockMantleWithResponses` selects `ApiMode.OpenAIResponses` (Responses endpoint). Both target the same Mantle base URL — only the path and event shapes differ.

## Models

The pipe passes the `modelId` argument to Mantle verbatim in the request body. TPipe does not maintain its own model catalog; the list of Mantle-reachable models is owned by AWS and changes as new models are added or retired.

The identifier format is `<provider>.<model>` — for example `google.gemma-4-31b` or `openai.gpt-5.6-sol`. To find the current catalog:

- The AWS Bedrock console lists Mantle-reachable models per region under the model's "details" panel.
- The AWS documentation for the Mantle endpoint surface enumerates the current set.

Mantle exposes two distinct wire shapes per model. Pick the builder that matches the shape the model supports:

| Wire shape | Builder | Models that expose it |
|------------|---------|----------------------|
| OpenAI Chat Completions | `setBedrockMantle(region, modelId)` | Models with `/v1/chat/completions` support |
| OpenAI Responses (with `output` items of type `reasoning`, `function_call`, etc.) | `setBedrockMantleWithResponses(region, modelId)` | Models with `/v1/responses` support |

For the Responses API surface, models that natively support reasoning expose a separate `output` item of type `reasoning` and accept a `reasoning: {"effort": "low"|"medium"|"high"}` field on the request. The chain-of-thought surfaces on `MultimodalContent.modelReasoning` (see [Reasoning on Mantle](#reasoning-on-mantle)).

Sampling-parameter constraints enforced by the Mantle engine:

- `max_output_tokens >= 16` on the Responses API. Lower values produce HTTP 400.
- `top_k = 0` is rejected by the Mantle engine. The pipe suppresses the field on the wire when its inherited default is `0`; pass `setTopK(40)` or higher to send it explicitly.

Errors surface as `P2PException(P2PError.json, …)`. See [Error Conditions](#error-conditions).

## Authentication

Three authentication shapes are supported. The pipe picks one automatically based on what is configured at `init()` time, in this precedence order:

1. **AWS SigV4** — when both `AWS_ACCESS_KEY_ID` and `AWS_SECRET_ACCESS_KEY` (or the Mantle-specific overrides) are resolvable, the pipe uses SigV4 for non-streaming requests.
2. **AWS SigV4 chunked streaming** — when streaming is enabled and SigV4 credentials are resolvable, the pipe uses the chunked-encoding variant of SigV4 for the streaming body.
3. **Bearer API key** — when no IAM credentials are resolvable, the pipe falls back to Bearer mode using either the programmatic `setApiKey(...)` value or the `BEDROCK_MANTLE_API_KEY` env var.

### Bearer authentication

```kotlin
val pipe = GenericOpenAIPipe()
    .setBedrockMantle(region = "us-east-2", modelId = "google.gemma-4-31b")
    .setApiKey(System.getenv("BEDROCK_MANTLE_API_KEY"))
    .init()
```

Or set the env var and skip the builder call:

```bash
export BEDROCK_MANTLE_API_KEY=...
```

Bearer sends `Authorization: Bearer <apiKey>` on every request. The Mantle API key is a long-term or short-term Bedrock API key — **not** an IAM access key id.

### SigV4 authentication (non-streaming)

Set the standard AWS credential environment variables before invoking the pipe:

```bash
export AWS_ACCESS_KEY_ID=AKIA...
export AWS_SECRET_ACCESS_KEY=...
export AWS_REGION=us-east-2
# Optional, for temporary credentials:
export AWS_SESSION_TOKEN=...
```

The pipe resolves these at request time and produces a SigV4 signature for each request using the AWS service identifier `bedrock-mantle`. The signed request headers are:

```
authorization: AWS4-HMAC-SHA256 Credential=<key>/<date>/<region>/bedrock-mantle/aws4_request, SignedHeaders=..., Signature=...
content-type: application/json
host: bedrock-mantle.us-east-2.api.aws
x-amz-content-sha256: <sha256(body)>
x-amz-date: <YYYYMMDDTHHMMSSZ>
```

For temporary credentials, `x-amz-security-token` is added automatically when `AWS_SESSION_TOKEN` (or `BEDROCK_MANTLE_SESSION_TOKEN`) is set.

### SigV4 chunked-streaming authentication (streaming requests)

Streaming SigV4 uses the same credentials as non-streaming SigV4 but writes the body as chunked-encoded blocks. The wire format per chunk is:

```
<size_hex>;<signature>\r\n
<chunk_body>\r\n
```

where `<size_hex>` is the chunk byte length as a 5-character lowercase hex and `<signature>` is the per-chunk SigV4 signature chained from the seed signature. After the last body chunk, a 0-byte terminator chunk (`0;<terminator-sig>\r\n\r\n`) closes the stream.

The initial request headers carry the seed signature (SigV4 over the canonical request whose payload hash is the streaming-payload marker `STREAMING-AWS4-HMAC-SHA256-PAYLOAD`), plus:

```
transfer-encoding: chunked
content-encoding: aws-chunked
x-amz-content-sha256: STREAMING-AWS4-HMAC-SHA256-PAYLOAD
x-amz-decoded-content-length: <body bytes>
```

The pipe switches to chunked-streaming SigV4 automatically when `setStreamingEnabled(true)` is in effect AND SigV4 credentials are resolvable. No builder call is required. To opt out of chunked-streaming SigV4 (for example to force Bearer-on-streaming), pass an explicit `BedrockMantleAuth.Streaming` or `BedrockMantleAuth.Bearer` via `setBedrockMantleAuth(...)`.

### Explicit auth injection

For tests or for callers that hold resolved credentials, `setBedrockMantleAuth(...)` accepts any `BedrockMantleAuth` shape:

```kotlin
import genericOpenAIPipe.mantle.BedrockMantleAuth

val auth = BedrockMantleAuth.bearer(System.getenv("BEDROCK_MANTLE_API_KEY"))
val pipe = GenericOpenAIPipe()
    .setBedrockMantle(region = "us-east-2", modelId = "google.gemma-4-31b")
    .setBedrockMantleAuth(auth)
    .init()
```

Pass `null` to clear the previously-set Mantle auth and fall back to the bearer/x-api-key defaults produced by the generic `getAuthHeaders` path.

## Environment Variable Reference

The pipe resolves Mantle credentials through `genericOpenAIPipe.env.BedrockMantleEnv`. The precedence chain (top wins) for the access key id:

1. Programmatic setter (test-only): `BedrockMantleEnv.setAccessKeyId(...)`
2. System property: `tpipe.bedrockMantle.accessKeyId`
3. Env var: `BEDROCK_MANTLE_ACCESS_KEY_ID`
4. Standard AWS env var: `AWS_ACCESS_KEY_ID`

The same chain applies symmetrically to:

| Field | Mantle-specific env | Mantle-specific system property | AWS fall-through env |
|-------|---------------------|---------------------------------|----------------------|
| Access key id | `BEDROCK_MANTLE_ACCESS_KEY_ID` | `tpipe.bedrockMantle.accessKeyId` | `AWS_ACCESS_KEY_ID` |
| Secret access key | `BEDROCK_MANTLE_SECRET_ACCESS_KEY` | `tpipe.bedrockMantle.secretAccessKey` | `AWS_SECRET_ACCESS_KEY` |
| Session token | `BEDROCK_MANTLE_SESSION_TOKEN` | `tpipe.bedrockMantle.sessionToken` | `AWS_SESSION_TOKEN` |
| Region | `BEDROCK_MANTLE_REGION` | `tpipe.bedrockMantle.region` | `AWS_REGION` (defaults to `us-east-1` when nothing is set) |
| Bearer API key | `BEDROCK_MANTLE_API_KEY` | — | — |

Callers who already set `AWS_ACCESS_KEY_ID` and `AWS_SECRET_ACCESS_KEY` for the AWS SDK do not have to mirror them under the Mantle-specific names — the AWS fall-through covers them.

## Reasoning on Mantle

Mantle models that support reasoning (such as `google.gemma-4-31b`) emit reasoning content as a separate `output` item of type `reasoning` on the Responses API. The OpenAI Responses wire spec defines this item with a `summary` array and a chain of `delta` events during streaming.

```kotlin
val pipe = GenericOpenAIPipe()
    .setBedrockMantleWithResponses(region = "us-east-2", modelId = "google.gemma-4-31b")
    .setMaxTokens(8192)
    .setTemperature(1.0)
    .setReasoningConfig(ReasoningConfig(effort = "high"))
    .setStreamingEnabled(true)
    .init()

val input = MultimodalContent(text = "If a train leaves Boston at 9am ... at what time do the two trains meet? Show reasoning.")
val response = pipe.execute(input)

println(response.text)             // visible answer
println(response.modelReasoning)    // chain-of-thought, populated from the `reasoning` output items
```

`response.modelReasoning` carries the accumulated reasoning content end-to-end on both the non-streaming and streaming paths. The pipe populates it from the wire response just as `BedrockPipe` and `OllamaPipe` do.

The `response.reasoning.delta` event emitted by Mantle uses a shorter event name than OpenAI proper (`response.reasoning_text.delta`). The pipe's SSE parser accepts both shapes — Mantle reasoning deltas route to the same `ResponseReasoningTextDelta` sealed-class variant as OpenAI's longer event name. No builder switch is required.

`reasoning.effort` accepts `low`, `medium`, or `high`. The Responses serializer emits the field verbatim as `reasoning: {"effort": "<value>"}`.

## Streaming

```kotlin
val pipe = GenericOpenAIPipe()
    .setBedrockMantle(region = "us-east-2", modelId = "google.gemma-4-31b")
    .setMaxTokens(32)
    .setStreamingEnabled(true)
    .init()

pipe.setStreamingCallback { chunk -> print(chunk) }
val response = pipe.execute("Reply with the single word 'pong'.")
```

Streaming on Mantle is byte-incremental: each SSE delta fires the streaming callback as it arrives on the socket. The pipe opens a direct `HttpURLConnection` rather than going through Ktor CIO because Ktor's `bodyAsChannel` buffers the entire chunked-transfer-encoded response before any callback fires.

The streaming path accumulates the response in three buffers:

- `textBuilder` — visible answer
- `reasoningBuilder` — chain-of-thought (Responses API only)
- `streamingReasoning`, `streamingInputTokens`, `streamingOutputTokens`, `streamingReasoningTokens` — surfaced on the `API_CALL_SUCCESS` trace event

The returned `MultimodalContent` carries both `text` and `modelReasoning` even on the streaming path.

The pipe's internal failure guard raises `P2PException(P2PError.transport, …)` when an OpenAI-family stream completes with empty output text — this distinguishes "the model produced no content" from "the validator pipe terminated the pipeline".

## Function Calling, Structured Output, Tool Choice

Mantle models accept the same `tools`, `toolChoice`, `parallelToolCalls`, and `responseFormat` fields as OpenAI proper. Wire these through the standard `GenericOpenAIPipe` builders:

```kotlin
val pipe = GenericOpenAIPipe()
    .setBedrockMantleWithResponses(region = "us-east-2", modelId = "google.gemma-4-31b")
    .setTools(listOf(myToolDefinition))
    .setToolChoice("auto")
    .setResponseFormat(ResponseFormat(type = "json_object"))
    .init()
```

Mantle's parser returns tool calls in the same `GenericOpenAIChatResponse` shape used by OpenAI proper. Function-call streaming events on the Responses API (`response.function_call_arguments.delta` / `…_done`) are accumulated into `streamingToolCallArguments` and surfaced on the trace.

## Error Conditions

The pipe surfaces Mantle errors as `P2PException` with the following mapping:

| Mantle response | Pipe exception |
|-----------------|----------------|
| HTTP 400 — request validation (e.g. `top_k=0`, `max_output_tokens < 16`) | `P2PException(P2PError.json, …)` |
| HTTP 401 — invalid Bearer key or expired SigV4 credentials | `P2PException(P2PError.auth, …)` |
| HTTP 403 — IAM permission denied (e.g. `bedrock-mantle:CreateInference`) | `P2PException(P2PError.auth, …)` |
| HTTP 429 — rate limit | `P2PException(P2PError.transport, …)` |
| HTTP 5xx — provider-side failure | `P2PException(P2PError.transport, …)` |
| SSE `response.failed` event | `P2PException(P2PError.transport, "response.failed: …")` |
| Empty streaming output text (Responses or Chat API) | `P2PException(P2PError.transport, "OpenAI streaming produced no output text …")` |

Required IAM actions for SigV4-authenticated calls:

- `bedrock-mantle:CreateInference`
- the standard `Get*` and `List*` actions for the model being called

The exact minimum IAM policy is documented in the AWS Bedrock Mantle IAM reference. Bearer-key users authenticate through the Bedrock API key gateway and do not require IAM permissions on the caller.

## API Reference

For the complete public surface — every sealed-class variant, every companion-object factory, every env-var precedence rule, every KDoc parameter — see:

- [`docs/api/bedrock-mantle.md`](../api/bedrock-mantle.md) — `BedrockMantleConfiguration`, `BedrockMantleAuth` (with `Bearer` / `SigV4` / `Streaming` variants), `BedrockMantleEnv`
- [`docs/api/generic-openai-pipe.md`](../api/generic-openai-pipe.md) — `GenericOpenAIPipe.setBedrockMantle(...)`, `setBedrockMantleWithResponses(...)`, `setBedrockMantleAuth(...)`, and the Mantle builders' place in the broader `GenericOpenAIPipe` API

## Next Steps

- [Installation and Setup](../getting-started/installation-and-setup.md) — how to wire AWS credentials into the runtime environment
- [GenericOpenAI Getting Started](../generic-openai/getting-started.md) — full tour of the pipe that drives Mantle
- [MultimodalContent API](../api/multimodal-content.md) — the `modelReasoning` field on `MultimodalContent` and how it is populated across non-streaming and streaming paths
- [Bedrock Getting Started](getting-started.md) — the AWS Converse/ConverseStream path for non-Mantle Bedrock models