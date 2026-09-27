# Reasoning Streaming

When a model produces internal reasoning (chain-of-thought traces, thinking
blocks), TPipe captures it into the result's `MultimodalContent.modelReasoning`
on **every** provider. This page covers the streaming side: making those
reasoning deltas ride the live streaming callback channel, wrapped in a
delimited segment, so subscribers can watch the model think in real time.

## The contract

Reasoning deltas are delivered on the **same** chunk callback you already use
for text — no second channel to subscribe to. When a provider streams
reasoning, the bytes arrive wrapped in a reasoning segment: an open marker
chunk, the raw reasoning bytes, then a close marker chunk before text
resumes. The markers are the constants `ReasoningStream.OPEN_TAG` and
`ReasoningStream.CLOSE_TAG` (the think-tag convention the Ollama think-model
wire already ships, so one regex parses both streamed segments and legacy
embedded tags).

## Electing it on

The election flag lives on the base `Pipe` and defaults to **true** (Bedrock
parity): `streamModelReasoning`. The public builder surface:

```kotlin
val pipe = GenericOpenAIPipe()
    .setBaseUrl("https://api.example.com/v1")
    .setModel("some-reasoning-model")
    .setStreamModelReasoning(true)   // on (default); set false to suppress reasoning bytes
    .setStreamingCallback { chunk ->
        // chunk stream: text deltas, plus — when a reasoning segment is open —
        // the open marker, reasoning bytes, then the close marker before text resumes.
        print(chunk)
    }
```

Bedrock's existing `enableStreaming(callback, showReasoning, streamReasoning)`
param sets the same base flag — no new surface. For providers whose streaming
binder does not expose a reasoning param, the base `setStreamModelReasoning`
setter is the single uniform election point.

**What the flag gates and what it does not:**

| Behavior | Gated by `streamModelReasoning`? |
|---|---|
| Reasoning bytes reaching streaming callbacks | Yes |
| `modelReasoning` populated on the returned `MultimodalContent` | **No** — capture is unconditional |
| `useModelReasoning` (requesting the model to think at all) | No — separate flag |

## Splitting the stream back

Subscribers who collected a raw stream (print-to-terminal, log, agent
dispatchers) recover the reasoning trace and the visible answer with
`com.TTT.Util.ReasoningStream.split`:

```kotlin
val collected = chunks.joinToString("")
val split = ReasoningStream.split(collected)

println(split.reasoning)   // the thinking trace (segments joined with newlines)
println(split.text)        // the visible answer, markers removed
```

`split` handles: no markers (all text), one or more complete segments, an
unclosed trailing open tag (aborted stream — the tail is reasoning), and
case-insensitive legacy tag variants.

## Which providers stream reasoning

Every provider that streams reasoning now routes it through the base hooks
(`emitReasoningStreamingStart/Chunk/End`), so delivery behavior is uniform:

| Provider | Wire reasoning source | Streams to callbacks when flag on |
|---|---|---|
| Bedrock (Converse + Invoke) | `asReasoningContentOrNull()` / extracted reasoning deltas | Yes |
| GenericOpenAI — OpenAI chat | `choices[].delta.reasoning_content` | Yes |
| GenericOpenAI — OpenAI Responses | `ResponseReasoningTextDelta` | Yes |
| GenericOpenAI — Anthropic | `ThinkingDelta` | Yes |
| Codex | = GenericOpenAI Responses branch (forced wire streaming) | Yes |
| Ollama | `message.thinking` (chat) / `thinking` (generate) | Yes |
| OpenRouter | `delta.reasoning` / `delta.reasoning_details[]` | Yes |

Providers whose model emits no reasoning produce zero segment bytes — the
open/close markers only appear when reasoning deltas actually flow.

## Subscribers must register

Reasoning delivery is **additive, not implicit**: registering a streaming
callback (`setStreamingCallback`, `streamingCallbacks{}`,
`enableStreaming(...)`) opts you into the chunk channel, and the channel
carries reasoning segments when the flag allows. Nothing about a pipe's
non-streaming behavior changes; and no subscriber receives reasoning bytes
unless they registered for streaming in the first place. Existing text-only
subscribers see reasoning appear in their stream only when they are already
streaming — that is the intended, documented behavior change for Bedrock
(reasoning deltas are now wrapped in the segment markers instead of arriving
raw and undelimited).

## Non-streaming note

On non-streaming calls, reasoning reaches you via
`MultimodalContent.modelReasoning` (and Ollama's legacy think-tag splitting
in the final text). The segment markers are a streaming-channel convention
only.

## See also

- [Pipe API — Streaming](../api/pipe.md#streaming) — callback manager surface
- [Util package — ReasoningStream](../api/util-package.md) — split helper reference
- Provider pages: [Bedrock](../bedrock/), [GenericOpenAI](../generic-openai/), [Ollama](../ollama/), [OpenRouter](../openrouter/), [Codex](../codex/)
