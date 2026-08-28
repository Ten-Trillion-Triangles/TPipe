package com.TTT.Pipe

/**
 * Normalize a developer-supplied implementation plan before it is attached to a pipe.
 *
 * Blank plans are treated as disabled so callers can clear a plan with either null or
 * whitespace-only input.
 *
 * @param plan The plan to normalize, or null to disable it.
 * @return The trimmed plan, or null when the plan is blank.
 */
internal fun normalizeImplementationPlan(plan: String?): String? =
    plan?.trim()?.takeIf { it.isNotEmpty() }

/**
 * Compose the stable implementation-plan block with an existing raw system prompt.
 *
 * The raw prompt is never modified by this helper. Rebuilding from that raw value keeps
 * repeated prompt refreshes idempotent.
 *
 * @param basePrompt The raw system prompt to preserve.
 * @param implementationPlan The optional plan to append.
 * @return The raw prompt with the normalized plan appended when enabled.
 */
internal fun composeImplementationPlanPrompt(
    basePrompt: String,
    implementationPlan: String?
): String
{
    val normalizedPlan = normalizeImplementationPlan(implementationPlan) ?: return basePrompt
    val planBlock = "Implementation plan:\n$normalizedPlan"
    if(basePrompt.isEmpty()) return planBlock

    val separator = when
    {
        basePrompt.endsWith("\n\n") -> ""
        basePrompt.endsWith("\n") -> "\n"
        else -> "\n\n"
    }
    return basePrompt + separator + planBlock
}
