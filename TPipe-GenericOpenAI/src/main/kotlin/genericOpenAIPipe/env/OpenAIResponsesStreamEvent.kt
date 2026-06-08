package genericOpenAIPipe.env

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Sealed hierarchy of every SSE event the OpenAI `/v1/responses` stream can emit.
 *
 * The set of events modelled here is the full wire spec the pipe can handle today:
 *  - lifecycle (`response.created`, `response.in_progress`, `response.completed`, `response.failed`)
 *  - text deltas (`response.output_text.delta`, `response.output_text.done`)
 *  - **reasoning deltas** (`response.reasoning_text.delta`, `response.reasoning_text.done`) —
 *    critical for reasoning-capable models like `MiniMax-M2.7`, `o3`, `o4-mini`
 *  - tool calls (`response.function_call_arguments.delta`, `response.function_call_arguments.done`)
 *  - `error`
 *
 * Anything else maps to [OpenAIResponsesStreamEvent.Unknown] so the stream does not
 * terminate prematurely when OpenAI adds new event types.
 */
sealed class OpenAIResponsesStreamEvent
{
    /** Stream was just opened; the response object is still empty. */
    data class ResponseCreated(
        val response: OpenAIResponsesResponse
    ) : OpenAIResponsesStreamEvent()

    /** Response is still in progress (incremental updates may follow). */
    data class ResponseInProgress(
        val response: OpenAIResponsesResponse
    ) : OpenAIResponsesStreamEvent()

    /**
     * Final lifecycle event — the model finished the response and no more
     * deltas will be emitted. Terminal.
     */
    data class ResponseCompleted(
        val response: OpenAIResponsesResponse
    ) : OpenAIResponsesStreamEvent()
    {
        val isTerminal: Boolean get() = true
    }

    /**
     * Stream was aborted by the server (e.g. upstream error). Terminal.
     */
    data class ResponseFailed(
        val response: OpenAIResponsesResponse
    ) : OpenAIResponsesStreamEvent()
    {
        val isTerminal: Boolean get() = true
    }

    /**
     * Assistant emitted a fragment of the user-visible answer — the main
     * incremental event the pipe consumes for non-reasoning output.
     */
    data class ResponseOutputTextDelta(
        val itemId: String? = null,
        val outputIndex: Int = 0,
        val contentIndex: Int = 0,
        val delta: String
    ) : OpenAIResponsesStreamEvent()

    /**
     * The model finished emitting text for a given `output_text` part; the
     * accumulated text is available as [text]. Non-terminal.
     */
    data class ResponseOutputTextDone(
        val itemId: String? = null,
        val outputIndex: Int = 0,
        val contentIndex: Int = 0,
        val text: String
    ) : OpenAIResponsesStreamEvent()

    /**
     * Reasoning-capable model emitted a fragment of internal chain-of-thought.
     * The pipe concatenates these deltas into a single reasoning transcript
     * that surfaces on `MultimodalContent.modelReasoning` and ends up in the
     * trace as `reasoningContent` (see [com.TTT.Pipe.Pipe.trace] line 4675).
     */
    data class ResponseReasoningTextDelta(
        val itemId: String? = null,
        val outputIndex: Int = 0,
        val contentIndex: Int = 0,
        val delta: String
    ) : OpenAIResponsesStreamEvent()

    /**
     * Reasoning text for a given `reasoning_text` part is fully emitted; the
     * accumulated reasoning text is available as [text]. Non-terminal.
     */
    data class ResponseReasoningTextDone(
        val itemId: String? = null,
        val outputIndex: Int = 0,
        val contentIndex: Int = 0,
        val text: String
    ) : OpenAIResponsesStreamEvent()

    /** Function-call argument fragment (used for tool calls; not yet plumbed into the pipe). */
    data class ResponseFunctionCallArgumentsDelta(
        val itemId: String? = null,
        val outputIndex: Int = 0,
        val delta: String
    ) : OpenAIResponsesStreamEvent()

    /** Function-call argument completion. */
    data class ResponseFunctionCallArgumentsDone(
        val itemId: String? = null,
        val outputIndex: Int = 0,
        val arguments: String
    ) : OpenAIResponsesStreamEvent()

    /** Catch-all for event types the pipe does not model (or `data: [DONE]` sentinels). */
    data class Unknown(
        val raw: String
    ) : OpenAIResponsesStreamEvent()
}
