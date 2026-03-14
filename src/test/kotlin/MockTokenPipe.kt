package com.TTT

import com.TTT.Pipe.MultimodalContent
import com.TTT.Pipe.Pipe

class MockTokenPipe(private val displayName: String) : Pipe()
{
    init {
        pipeName = displayName
    }

    override fun truncateModuleContext(): Pipe = this

    override suspend fun generateText(promptInjector: String): String {
        return "$displayName generated text: $promptInjector"
    }

    // Do NOT override generateContent so that Pipe's original
    // generation logic wrapper around token counts is executed.
}
