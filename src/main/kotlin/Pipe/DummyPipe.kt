package com.TTT.Pipe

/**
 * A no-op pipe that delegates entirely to a [containerPtr].
 *
 * Used as a developer-friendly placeholder in pipelines when the intent is to embed
 * a container (Manifold, Splitter, etc.) without any additional pipe-level logic.
 * The pipe functions as a pure redirect — it defers all execution to the contained
 * object via [P2PInterface.executeLocal].
 *
 * @see [Pipe.setContainerPtr]
 */
@kotlinx.serialization.Serializable
class DummyPipe : Pipe()
{
    override fun truncateModuleContext(): Pipe = this

    override suspend fun generateText(promptInjector: String): String = promptInjector
}
