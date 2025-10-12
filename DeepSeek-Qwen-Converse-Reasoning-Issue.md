# DeepSeek & Qwen Converse Reasoning Issue

## Background
- Module: `TPipe/TPipe-Bedrock/src/main/kotlin/bedrockPipe/BedrockPipe.kt`
- Models affected:
  - DeepSeek R1 *Converse* path (Invoke fallback works today)
  - DeepSeek V3.x thinking models (Converse primary)
  - Qwen3 thinking models (Converse primary)
  - GPT-OSS reasoning toggles (Converse)
- Tracing relies on metadata populated by `generateText`, `handleConverseGeneration`, and `executeConverseStream`.

## Expected behaviour
- When `useConverseApi == true`, reasoning/thinking content returned in a `ContentBlock.ReasoningContent` (or `<think>` block for Qwen) should be captured and written to trace metadata just like the Invoke path does via `extractReasoningContent(...)`.
- `setReasoning()` overloads should consistently control the request payload:
  - DeepSeek/Qwen: enable Bedrock reasoning fields when available.
  - GPT-OSS: map boolean toggle (or string overload) to the correct `reasoning_effort` plus any required prompt tag.
- Traces should distinguish between model response text and reasoning content, regardless of which API was used.

## Observed behaviour
- **DeepSeek Converse (`generateTextWithConverseApi`, ~`BedrockPipe.kt:1640-1720`)**
  - Attempts to peel text out of `ReasoningContent` using reflection but frequently returns an empty string.
  - Even when it succeeds, the reasoning string is folded into the user response and never added to `trace(...)` metadata, so the trace shows no reasoning.
  - Streaming path (`executeConverseStream`) *does* capture reasoning deltas, which is why R1 appears correct when streaming or when Invoke fallback triggers.
- **Qwen Converse (`generateWithConverseApi`, ~`BedrockPipe.kt:2320-2387`)**
  - Only collects `ContentBlock.Text` and ignores `ReasoningContent` blocks and `<think>` spans inside the text payload.
  - Result: traces never show Qwen thinking output unless Invoke (non-default) is used.
- **GPT-OSS reasoning toggle (`buildGptOssRequest`, ~`BedrockPipe.kt:1327-1370`)**
  - `setReasoning(custom: String)` works (`modelReasoningSettingsV3` → `reasoning_effort`).
  - Plain `setReasoning()` (boolean) just flips `useModelReasoning` and currently leaves `reasoning_effort` at the default "low".
  - No automatic system prompt tag is injected, so models that require the deliberate tag remain in low-effort mode unless the caller manually sets the string overload.

## Root cause summary
1. Converse handlers do not reuse `extractReasoningFromConverseResponse(...)`; reasoning is silently dropped for DeepSeek/Qwen.
2. GPT-OSS builder does not respect the boolean reasoning toggle, so most callers stay on low effort even when they expect reasoning.
3. Reasoning text is merged into the final response string, preventing traces from distinguishing between answer vs. thought.

## Remediation plan (for coding agent)
1. **Capture reasoning in Converse responses**
   - After `client.converse(...)` returns, call `extractReasoningFromConverseResponse(response)` and store the value.
   - Populate `trace(...)` metadata (`reasoningContent`, `hasReasoning`, `reasoningEnabled`) before returning.
   - Return the final text **without** inlining reasoning; keep reasoning separate so traces and downstream consumers can decide whether to display it.
2. **Refactor DeepSeek Converse helper**
   - Replace the ad-hoc reflection block with logic that first checks the `ReasoningContent` block via the helper function; only fall back to legacy reflection when necessary.
   - Ensure the Invoke fallback keeps its current behaviour.
3. **Update Qwen Converse path**
   - Reuse the same reasoning-extraction helper.
   - Additionally parse `<think>...</think>` segments in `ContentBlock.Text` for models that inject thoughts into plain text, appending them to the reasoning metadata while stripping them from the final answer if desired.
4. **Align GPT-OSS reasoning toggles**
   - If `useModelReasoning == true` and `modelReasoningSettingsV3` is blank, default `reasoning_effort` to "medium" (or whatever tier we want for the boolean toggle).
   - Optionally inject the required deliberate-mode system tag when reasoning is enabled and allow callers to override via the string overload.
   - Keep reasoning content out of the final answer string and rely on metadata instead.
5. **Tracing consistency**
   - Update trace invocations in both Invoke and Converse paths to always include `reasoningContent` when non-empty and to mark `modelSupportsReasoning` appropriately.
   - Verify streaming (`executeConverseStream`) continues to append reasoning deltas to the metadata map.
6. **Testing / verification checklist**
   - Unit/integration tests for DeepSeek and Qwen showing reasoning metadata populated when `useConverseApi` is true.
   - Manual run against GPT-OSS with plain `setReasoning()` and with `setReasoning("high")` to confirm the request payload changes and traces capture reasoning.
   - Regression test for Invoke path ensuring behaviour remains unchanged.
   - Inspect generated trace HTML (e.g., `standard-pipeline-trace.html`) to confirm reasoning appears in the correct field without being merged into the main response text.

## Additional notes
- Relevant helper functions: `extractReasoningFromConverseResponse` (~`BedrockPipe.kt:1840-1890`), `extractReasoningContent` (~`BedrockPipe.kt:3035-3094`).
- When modifying `buildGptOssRequest`, keep `additionalModelRequestFields` reflection code intact; only adjust how `reasoning_effort` is chosen and whether the system prompt modifier is injected.
- Ensure any new parsing logic remains ASCII-only and keeps current exception-safety (default to empty string rather than throwing).
