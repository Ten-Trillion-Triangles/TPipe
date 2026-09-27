import bedrockPipe.BedrockPipe
import com.TTT.Pipe.StreamingCallbackManager
import com.TTT.Util.ReasoningStream
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Bedrock-side routing proof for the reasoning-stream normalization
 * (plan task-3). Bedrock's streaming executors (Converse + Invoke) now
 * route reasoning deltas through the base hooks
 * [com.TTT.Pipe.Pipe.emitReasoningStreamingChunk] and friends; because those
 * hooks deliver bytes through the provider's [BedrockPipe.emitStreamingChunk]
 * override, this test drives the override's real delivery path and proves:
 *
 * 1. With the base flag on, reasoning bytes reach BOTH the legacy
 *    single-callback field AND the StreamingCallbackManager callbacks,
 *    wrapped in a [ReasoningStream.OPEN_TAG] / [ReasoningStream.CLOSE_TAG]
 *    segment.
 * 2. With the base flag off, zero reasoning bytes (and zero markers) reach
 *    either delivery path.
 * 3. The reasoning->text transition ordering holds: close marker lands
 *    before the first text byte.
 *
 * The AWS wire path itself (converseStream / invokeModelWithResponseStream
 * delta loops) is live-credential gated and out of unit scope; this pins
 * the delivery contract the loops now depend on.
 */
class BedrockReasoningStreamingTest
{
    /** Subclass exposing the protected base hooks for direct driving. */
    private class ExposingBedrockPipe : BedrockPipe()
    {
        suspend fun driveReasoningStart() = emitReasoningStreamingStart()
        suspend fun driveReasoningChunk(chunk: String) = emitReasoningStreamingChunk(chunk)
        suspend fun driveReasoningEnd() = emitReasoningStreamingEnd()
        suspend fun driveStreamEnd() = emitStreamEnd()
        suspend fun driveTextChunk(chunk: String) = emitStreamingChunk(chunk)
    }

    private class Collector
    {
        val legacy = mutableListOf<String>()
        val manager = mutableListOf<String>()
        var managerComplete = 0
        val legacyCallback: suspend (String) -> Unit = { chunk -> legacy.add(chunk) }
        val managerCallback: suspend (String) -> Unit = { chunk -> manager.add(chunk) }
        val managerCompleteCallback: suspend () -> Unit = { managerComplete++ }
    }

    private fun wiredPipe(flag: Boolean, legacy: Collector, useLegacy: Boolean = true): ExposingBedrockPipe
    {
        val pipe = ExposingBedrockPipe()
        // setPipeName returns the base Pipe (Kotlin variance): assign without
        // chaining so `pipe` keeps the concrete ExposingBedrockPipe type.
        pipe.setPipeName("bedrock-reasoning-routing")
        if(useLegacy)
        {
            pipe.enableStreaming(legacy.legacyCallback, showReasoning = false, streamReasoning = flag)
        }
        else
        {
            pipe.enableStreaming(null, showReasoning = false, streamReasoning = flag)
        }
        val manager: StreamingCallbackManager = pipe.obtainStreamingCallbackManager()
        manager.addCallback(legacy.managerCallback)
        manager.addCompleteCallback(legacy.managerCompleteCallback)
        return pipe
    }

    @Test
    fun reasoningBytesReachLegacyFieldAndManagerWhenFlagOn() = runBlocking {
        val collector = Collector()
        val pipe = wiredPipe(flag = true, legacy = collector)
        pipe.driveReasoningStart()
        pipe.driveReasoningChunk("thought-a")
        pipe.driveReasoningChunk("thought-b")
        pipe.driveReasoningEnd()

        val expected = listOf(
            ReasoningStream.OPEN_TAG, "thought-a", "thought-b", ReasoningStream.CLOSE_TAG
        )
        assertEquals(expected, collector.legacy, "legacy single-callback field sequence")
        assertEquals(expected, collector.manager, "manager callback sequence")
    }

    @Test
    fun flagOffDeliversZeroReasoningBytesToEitherPath() = runBlocking {
        val collector = Collector()
        val pipe = wiredPipe(flag = false, legacy = collector)
        pipe.driveReasoningStart()
        pipe.driveReasoningChunk("thought")
        pipe.driveReasoningEnd()
        assertTrue(collector.legacy.isEmpty(), "flag off must not deliver to legacy field: ${collector.legacy}")
        assertTrue(collector.manager.isEmpty(), "flag off must not deliver to manager: ${collector.manager}")
    }

    @Test
    fun managerOnlyPipeStillReceivesReasoningSegment() = runBlocking {
        // enableStreaming(null) leaves the legacy field empty; the manager
        // path alone must carry the full delimited segment.
        val collector = Collector()
        val pipe = wiredPipe(flag = true, legacy = collector, useLegacy = false)
        pipe.driveReasoningChunk("thought")
        pipe.driveReasoningEnd()
        assertEquals(
            listOf(ReasoningStream.OPEN_TAG, "thought", ReasoningStream.CLOSE_TAG),
            collector.manager
        )
        assertTrue(collector.legacy.isEmpty())
    }

    @Test
    fun textTransitionOrdersCloseMarkerBeforeTextByte() = runBlocking {
        // Mirror of the provider loop rework: reasoning deltas, explicit
        // segment close at the reasoning->text transition, then text bytes.
        val collector = Collector()
        val pipe = wiredPipe(flag = true, legacy = collector)
        pipe.driveReasoningChunk("thinking")
        pipe.driveReasoningEnd()
        pipe.driveTextChunk("answer")
        assertEquals(
            listOf(ReasoningStream.OPEN_TAG, "thinking", ReasoningStream.CLOSE_TAG, "answer"),
            collector.manager
        )
    }

    @Test
    fun streamEndSafetyNetClosesOpenSegmentBeforeCompletion() = runBlocking {
        val collector = Collector()
        val pipe = wiredPipe(flag = true, legacy = collector)
        pipe.driveReasoningChunk("thinking")
        // No explicit close — the terminal safety net must close it.
        pipe.driveStreamEnd()
        assertEquals(
            listOf(ReasoningStream.OPEN_TAG, "thinking", ReasoningStream.CLOSE_TAG),
            collector.manager
        )
        assertEquals(1, collector.managerComplete)
    }
}
