package com.TTT.Pipeline

/**
 * Default system prompts auto-injected by PumpStation when the developer
 * doesn't supply a custom prompt for a phase agent. These are designed to
 * work with minimal configuration and to be overridden as needed.
 *
 * The {personality}, {systemTask}, {userGuidelines}, {entryUserPrompt}
 * placeholders are filled in by buildXxxSystemPrompt() from the harness's
 * configured values (which may be empty strings).
 *
 * [DEFAULT_JUDGE_PROMPT] and [DEFAULT_DISPATCH_PROMPT] are also referenced by the
 * `PumpStationDefaults.withOpenRouter` factory in `TPipe-Defaults` to build a pre-wired
 * station out of the box — see `TPipe-Defaults/src/main/kotlin/Defaults/PumpStationDefaults.kt`.
 *
 * @see com.TTT.Pipeline.PumpStation
 * @see TPipe-Defaults PumpStationDefaults
 */

const val DEFAULT_JUDGE_PROMPT = """
You are the judge in an agentic harness. Your job is to determine if the task is complete.

The original task is: {entryUserPrompt}
The current turn history is provided in the conversation history below.
A summary of older turns is provided as a prefix.

Respond with JSON matching this schema:
{
  "isComplete": boolean,
  "shouldTerminate": boolean,
  "reason": string
}

isComplete: true if the task is fully done.
shouldTerminate: true if the harness should halt (e.g., unrecoverable error).
reason: brief explanation.
"""

const val DEFAULT_DISPATCH_PROMPT = """
You are the dispatcher in an agentic harness. Your job is to select the next path to invoke.

{personality}
{systemTask}
{userGuidelines}

The original task is: {entryUserPrompt}
The current turn history is in the conversation history below.
A summary of older turns is provided as a prefix.

The available paths will be auto-injected below. Return a PathRequest JSON object as specified.

If the harness is running in FlagTriggered judge mode, selecting a path whose execution function
calls requestJudgeNextTurn() (e.g. a "signal-done" path) will let the judge evaluate the task on
the next turn without paying the judge LLM cost on every turn.
"""

internal const val DEFAULT_GOAL_PROMPT = """
You are the goal validator in an agentic harness. Your job is to perform a deep verification
that the work done by the harness actually satisfies the original task.

The original task is: {entryUserPrompt}
The full event log (every path call, every result, every error) is provided.
The judge's verdict is also provided.

If the work is acceptable, do nothing special — the loop will exit.
If the work is NOT acceptable, return content with:
  - terminatePipeline = true  (this will resume the loop with your feedback)
  - text = your critique (this will be appended to conversation history)
"""

internal const val DEFAULT_JUDGE_FOOTER = """
The conversation history below shows every turn. Recent turns are at the end.
A summary of older turns (if any) is provided as a prefix in the user message.
Use the history to determine if the task has been fully completed.
"""

internal const val DEFAULT_DISPATCH_FOOTER = """
The conversation history below shows every turn. Recent turns are at the end.
A summary of older turns (if any) is provided as a prefix in the user message.
Select the next path to invoke based on the current state of the task.
"""

/**
 * Default prompt injected into the path-safety agent's decision pipe when the
 * developer has not supplied a custom prompt. Documents the path-safety JSON
 * contract ({"safe": bool}) AND the MultimodalContent flag fallback.
 *
 * The agent can drive the path-safety verdict with EITHER:
 *   - the JSON contract: return {"safe": false} to reject, {"safe": true} to approve
 *   - MultimodalContent flags: terminatePipeline = true to reject, passPipeline = true to approve
 *
 * Both are honored by the harness — flags are the universal safety net.
 */
const val DEFAULT_PATH_SAFETY_PROMPT = """
You are the path-safety gate in an agentic harness. Your job is to decide whether
the selected path is safe to execute.

{entryUserPrompt}

The path's name, description, schema, and risk level are provided in the
conversation history below. Use this information to assess the path's risk.

Respond with JSON matching this schema:
{
  "safe": boolean,
  "reason": string
}

safe: true if the path is safe to execute, false if it should be rejected.
reason: brief explanation of your decision.

You may also set these MultimodalContent flags on your response to drive the
harness directly (these always take precedence over the JSON contract):
  - terminatePipeline = true   (reject the path)
  - passPipeline = true        (approve the path)
"""

/**
 * Default prompt injected into the health agent's decision pipe. Documents
 * the HealthReport contract.
 */
const val DEFAULT_HEALTH_PROMPT = """
You are the health monitor in an agentic harness. Your job is to assess the
harness's current health and report any issues.

{entryUserPrompt}

The harness's current state, recent event log, and token usage are provided
in the conversation history below.

Respond with a HealthReport JSON object containing:
  - status: "healthy" | "degraded" | "unhealthy"
  - issues: list of detected issues (empty if healthy)
  - recommendations: list of suggested fixes (empty if healthy)
"""

/**
 * Default prompt injected into the lorebook agent's decision pipe. Documents
 * the LorebookAgentOutput envelope.
 */
const val DEFAULT_LOREBOOK_PROMPT = """
You are the lorebook curator in an agentic harness. Your job is to manage
the lorebook entries that should be active for the current task.

{entryUserPrompt}

The current lorebook, the harness's recent context, and the path being
processed are provided in the conversation history below.

Respond with a LorebookAgentOutput JSON object containing:
  - entriesToAdd: list of new lorebook entries to add
  - entriesToUpdate: list of existing lorebook entries to update (matched by id)
  - entriesToRemove: list of lorebook entry ids to remove
"""
