package com.TTT.P2P

import com.TTT.Pipeline.Pipeline
import com.TTT.Pipeline.Manifold
import com.TTT.Pipeline.Connector
import com.TTT.Pipeline.Splitter
import com.TTT.Pipeline.MultiConnector
import com.TTT.Pipeline.Junction
import com.TTT.Pipeline.PumpStation
import com.TTT.Pipeline.DistributionGrid
import com.TTT.P2P.AgentRequest
import bedrockPipe.BedrockPipe
import com.TTT.Pipe.Pipe
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Verifies that setStreamingCallbackRecursive on any P2PInterface container
 * propagates the callback to every leaf Pipe via propagateStreamingCallback.
 *
 * Propagation contract: a callback registered on a container fires on ALL descendant
 * pipes — it is a broadcast, not a per-pipe channel. Each call to
 * setStreamingCallbackRecursive walks the full tree and registers the callback
 * on every leaf pipe. Subsequent calls add the same callback to more pipes.
 *
 * Test strategy: verify a callback registered on the root fires for emits from
 * any descendant pipe (global broadcast). For containers with multiple branches,
 * verify the callback fires from each branch.
 */
class P2PRecursiveStreamingTest
{
    // Test double: plain Pipe subclass that exposes emitStreamingChunk for unit testing.
    // Avoids BedrockPipe's agent-call machinery that requires live infrastructure.
    // Implements the 2 abstract Pipe members and sets AgentRequest schema so
    // Manifold.setManagerPipeline validation passes.
    private class DummyPipe : Pipe()
    {
        init
        {
            @Suppress("UNCHECKED_CAST")
            setJsonOutput(AgentRequest())
        }

        override fun truncateModuleContext(): Pipe = this
        override suspend fun generateText(promptInjector: String): String = promptInjector

        suspend fun emit(chunk: String)
        {
            this.emitStreamingChunk(chunk)
        }
    }

    // RecordingPipe uses BedrockPipe for non-Manifold tests where infrastructure isn't needed.
    private class RecordingPipe : BedrockPipe()
    {
        val recordedChunks = mutableListOf<String>()

        suspend fun emit(chunk: String)
        {
            emitStreamingChunk(chunk)
        }
    }

    @Test
    fun `Pipeline setStreamingCallbackRecursive delivers chunks from the pipe`()
    {
        runBlocking {
            val pipe = RecordingPipe()
            val pipeline = Pipeline()
            pipeline.add(pipe)

            val chunks = mutableListOf<String>()
            val callback: suspend (String) -> Unit = { chunks.add(it) }

            pipeline.setStreamingCallbackRecursive(callback)

            pipe.emit("hello")
            pipe.emit(" world")

            assertEquals(listOf("hello", " world"), chunks)
        }
    }

    @Test
    fun `Pipeline setStreamingCallbackRecursive broadcasts callback to every pipe`()
    {
        runBlocking {
            val pipe1 = RecordingPipe()
            val pipe2 = RecordingPipe()
            val pipeline = Pipeline()
            pipeline.add(pipe1)
            pipeline.add(pipe2)

            val chunks = mutableListOf<String>()
            val callback: suspend (String) -> Unit = { chunks.add(it) }

            // One callback registration on the root — should fire for both pipes
            pipeline.setStreamingCallbackRecursive(callback)

            pipe1.emit("from pipe1")
            pipe2.emit("from pipe2")

            // Callback is a broadcast — fires for every emit from any pipe
            assertEquals(listOf("from pipe1", "from pipe2"), chunks)
        }
    }

    @Test
    fun `Manifold setStreamingCallbackRecursive broadcasts to manager and worker pipelines`()
    {
        runBlocking {
            val managerPipe = DummyPipe()
            val workerPipe = DummyPipe()
            val managerPipeline = Pipeline()
            managerPipeline.add(managerPipe)
            val workerPipeline = Pipeline()
            workerPipeline.add(workerPipe)

            val manifold = Manifold()
            manifold.setManagerPipeline(managerPipeline)
            manifold.addWorkerPipeline(workerPipeline)

            val chunks = mutableListOf<String>()
            val callback: suspend (String) -> Unit = { chunks.add(it) }

            // One callback on the manifold — broadcasts to ALL descendant pipes
            manifold.setStreamingCallbackRecursive(callback)

            managerPipe.emit("manager")
            workerPipe.emit("worker")

            assertEquals(listOf("manager", "worker"), chunks)
        }
    }

    @Test
    fun `Connector setStreamingCallbackRecursive broadcasts to all branch pipelines`()
    {
        runBlocking {
            val pipe1 = RecordingPipe()
            val pipe2 = RecordingPipe()
            val p1 = Pipeline(); p1.add(pipe1)
            val p2 = Pipeline(); p2.add(pipe2)
            val connector = Connector()
            connector.add("a", p1)
            connector.add("b", p2)

            val chunks = mutableListOf<String>()
            val callback: suspend (String) -> Unit = { chunks.add(it + "_processed") }

            // One callback on the connector — broadcasts to both branches
            connector.setStreamingCallbackRecursive(callback)

            pipe1.emit("branch1")
            pipe2.emit("branch2")

            // Callback fires for both branches since it was registered once on the root
            assertEquals(listOf("branch1_processed", "branch2_processed"), chunks)
        }
    }

    @Test
    fun `Splitter setStreamingCallbackRecursive broadcasts to all activator pipelines`()
    {
        runBlocking {
            val pipe1 = RecordingPipe()
            val pipe2 = RecordingPipe()
            val p1 = Pipeline(); p1.add(pipe1)
            val p2 = Pipeline(); p2.add(pipe2)
            val splitter = Splitter()
            splitter.addPipeline("key1", p1)
            splitter.addPipeline("key2", p2)

            val chunks = mutableListOf<String>()
            splitter.setStreamingCallbackRecursive { chunks.add(it) }

            pipe1.emit("key1")
            pipe2.emit("key2")

            assertEquals(listOf("key1", "key2"), chunks)
        }
    }

    @Test
    fun `Junction setStreamingCallbackRecursive broadcasts to moderator and participants`()
    {
        runBlocking {
            val modPipe = RecordingPipe()
            val partPipe = RecordingPipe()
            val modPipeline = Pipeline(); modPipeline.add(modPipe)
            val partPipeline = Pipeline(); partPipeline.add(partPipe)

            val junction = Junction()
            junction.setModerator("mod", modPipeline)
            junction.addParticipant("p1", partPipeline)

            val chunks = mutableListOf<String>()
            val callback: suspend (String) -> Unit = { chunks.add(it) }

            // One callback on the junction — broadcasts to moderator AND participants
            junction.setStreamingCallbackRecursive(callback)

            modPipe.emit("from mod")
            partPipe.emit("from part")

            assertEquals(listOf("from mod", "from part"), chunks)
        }
    }

    @Test
    fun `setStreamingCallbackRecursive is idempotent — same callback does not double-fire on one pipe`()
    {
        runBlocking {
            val pipe = RecordingPipe()
            val pipeline = Pipeline()
            pipeline.add(pipe)

            val chunks = mutableListOf<String>()
            val callback: suspend (String) -> Unit = { chunks.add(it) }

            // Calling twice with the same callback on the same pipeline
            // StreamingCallbackManager dedupes by reference, so no double-fire
            pipeline.setStreamingCallbackRecursive(callback)
            pipeline.setStreamingCallbackRecursive(callback)

            pipe.emit("once")

            assertEquals(listOf("once"), chunks) // not ["once", "once"]
        }
    }

    @Test
    fun `MultiConnector setStreamingCallbackRecursive broadcasts through all connectors`()
    {
        runBlocking {
            val pipe1 = RecordingPipe()
            val pipe2 = RecordingPipe()
            val p1 = Pipeline(); p1.add(pipe1)
            val p2 = Pipeline(); p2.add(pipe2)
            val c1 = Connector(); c1.add("x", p1)
            val c2 = Connector(); c2.add("y", p2)
            val multi = MultiConnector()
            multi.add(c1)
            multi.add(c2)

            val chunks = mutableListOf<String>()
            multi.setStreamingCallbackRecursive { chunks.add(it) }

            pipe1.emit("multi1")
            pipe2.emit("multi2")

            assertEquals(listOf("multi1", "multi2"), chunks)
        }
    }
}
