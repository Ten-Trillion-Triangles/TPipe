package com.TTT.testing

import com.TTT.Pipe.Pipe

/**
 * Test pipe that captures the composed system prompt after applySystemPrompt() runs.
 * Used by tests verifying the path-injection block at Pipe.kt:2319-2341 fires.
 *
 * @param name Display name (defaults to "capturing").
 * @param response The text returned by generateText().
 */
class TestCapturingPipe(
    private val name: String = "capturing",
    var response: String = """{"pathName": "x", "pathSchema": "{}"}"""
) : Pipe()
{
    init
    {
        pipeName = name
    }

    /** Captured system prompt after applySystemPrompt() runs. */
    var composedSystemPrompt: String = ""

    override suspend fun generateText(promptInjector: String): String = response

    override fun onApplySystemPromptComplete()
    {
        super.onApplySystemPromptComplete()
        composedSystemPrompt = systemPrompt
    }

    override fun truncateModuleContext(): Pipe = this
}
