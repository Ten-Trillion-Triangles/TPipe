package com.TTT.AgentCore.Runtime.AgUi

/**
 * One canonical AG-UI event before transport encoding.
 *
 * @param type AG-UI event type.
 * @param threadId Thread associated with the run.
 * @param runId Run associated with the event.
 * @param messageId Optional assistant message identifier.
 * @param delta Optional streamed text delta.
 * @param error Optional failure message.
 * @param role Optional message role.
 */
data class AgentCoreAgUiEvent(
    val type: String,
    val threadId: String,
    val runId: String,
    val messageId: String? = null,
    val delta: String? = null,
    val error: String? = null,
    val role: String? = null
)

/** Maps TPipe execution milestones to the conservative AG-UI event sequence. */
object AgentCoreAgUiEventMapper {
    /** Begin a run and its assistant text message.
     *
     * @param input Incoming AG-UI request.
     * @return The run-start and message-start events.
     */
    fun started(input: RunAgentInput): List<AgentCoreAgUiEvent> = listOf(
        AgentCoreAgUiEvent("RUN_STARTED", input.threadId, input.runId),
        AgentCoreAgUiEvent(
            "TEXT_MESSAGE_START",
            input.threadId,
            input.runId,
            messageId(input),
            role = "assistant"
        )
    )

    /** Map one streamed TPipe text chunk.
     *
     * @param input Incoming AG-UI request.
     * @param chunk Streamed text.
     * @return The corresponding content event.
     */
    fun content(input: RunAgentInput, chunk: String): AgentCoreAgUiEvent =
        AgentCoreAgUiEvent("TEXT_MESSAGE_CONTENT", input.threadId, input.runId, messageId(input), delta = chunk)

    /** Finish the text message and run.
     *
     * @param input Incoming AG-UI request.
     * @return The message-end and run-finished events.
     */
    fun finished(input: RunAgentInput): List<AgentCoreAgUiEvent> = listOf(
        AgentCoreAgUiEvent("TEXT_MESSAGE_END", input.threadId, input.runId, messageId(input)),
        AgentCoreAgUiEvent("RUN_FINISHED", input.threadId, input.runId)
    )

    /** Map an execution failure to a terminal AG-UI event.
     *
     * @param input Incoming AG-UI request.
     * @param message Failure message.
     * @return The run-error event.
     */
    fun failed(input: RunAgentInput, message: String): AgentCoreAgUiEvent =
        AgentCoreAgUiEvent("RUN_ERROR", input.threadId, input.runId, error = message)

    private fun messageId(input: RunAgentInput): String = "${input.runId}:assistant"
}
