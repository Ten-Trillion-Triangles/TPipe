package com.TTT.Pipeline

/**
 * Default system prompts auto-injected by PumpStation when the developer
 * doesn't supply a custom prompt for a phase agent. These are designed to
 * work with minimal configuration and to be overridden as needed.
 *
 * The {personality}, {systemTask}, {userGuidelines}, {entryUserPrompt}
 * placeholders are filled in by buildXxxSystemPrompt() from the harness's
 * configured values (which may be empty strings).
 */

internal const val DEFAULT_JUDGE_PROMPT = """
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

internal const val DEFAULT_DISPATCH_PROMPT = """
You are the dispatcher in an agentic harness. Your job is to select the next path to invoke.

{personality}
{systemTask}
{userGuidelines}

The original task is: {entryUserPrompt}
The current turn history is in the conversation history below.
A summary of older turns is provided as a prefix.

The available paths will be auto-injected below. Return a PathRequest JSON object as specified.
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
