package com.TTT

import com.TTT.Pipe.MultimodalContent
import com.TTT.Pipe.Pipe

class TestTokenPipe(private val displayName: String) : Pipe()
{
    init {
        pipeName = displayName
    }

    override fun truncateModuleContext(): Pipe = this

    override suspend fun generateText(promptInjector: String): String {
        return "$displayName generated text: $promptInjector"
    }

    override suspend fun generateContent(content: MultimodalContent): MultimodalContent {
        return MultimodalContent(text = "${content.text} -> handled by $displayName")
    }
}
