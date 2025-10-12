package com.TTT.Pipe

/**
 * Interface that allows the Pipe class to invoke instructions against itself as a child without creating clutter
 * or undesired codebase messes. We need this because there are some cases of some runtime provider specific fix
 * or behavior to be handled by the pipe class during its execution.
 */
interface ProviderInterface
{
    /**
     * Cleans prompt text if needed to remove illegal chars, formatting issues and other provider specific issues.
     * with the user prompt, input text, and system prompt.
     */
    fun cleanPromptText(content: MultimodalContent) : MultimodalContent
    {
        //Default to do nothing but some providers will need a bunch of cleanup actions taken.
        return content
    }
}