package com.TTT.Pipeline

import com.TTT.Pipe.MultimodalContent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Regression tests for [PumpStationSteeringService] — the thread-safe steering
 * instruction store used by the harness loop to inject MultimodalContent into
 * turnHistory at PumpStationPausePhase boundaries.
 *
 * These tests pin the behavior of the one-shot queue, persistent overlay,
 * and combined drain semantics. Future refactors of the service must keep
 * these tests green.
 */
class PumpStationSteeringServiceTest
{
    private fun content(text: String): MultimodalContent = MultimodalContent(text = text)

    //=====================================One-shot queue (Task 8)================================================

    @Test
    fun `one-shot queue dequeues at drain and reports empty after`() = runTest {
        val service = PumpStationSteeringService()
        val phase = PumpStationPausePhase.BeforeJudge

        service.enqueueOneShot(phase, content("first nudge"))
        service.enqueueOneShot(phase, content("second nudge"))

        val firstDrain = service.drainForPhase(phase)
        assertEquals(2, firstDrain.size, "expected both queued entries on first drain")
        assertEquals("first nudge", firstDrain[0].text)
        assertEquals("second nudge", firstDrain[1].text)

        val secondDrain = service.drainForPhase(phase)
        assertTrue(secondDrain.isEmpty(), "expected empty drain after one-shot consumption")
    }

    @Test
    fun `one-shot queue is FIFO across multiple enqueues`() = runTest {
        val service = PumpStationSteeringService()
        val phase = PumpStationPausePhase.AfterDispatch

        listOf("a", "b", "c", "d").forEach { service.enqueueOneShot(phase, content(it)) }
        val drained = service.drainForPhase(phase)

        assertEquals(listOf("a", "b", "c", "d"), drained.map { it.text })
    }

    @Test
    fun `one-shot queue is per-phase isolated`() = runTest {
        val service = PumpStationSteeringService()
        service.enqueueOneShot(PumpStationPausePhase.BeforeJudge, content("judge"))
        service.enqueueOneShot(PumpStationPausePhase.AfterDispatch, content("dispatch"))

        val judgeDrain = service.drainForPhase(PumpStationPausePhase.BeforeJudge)
        val dispatchDrain = service.drainForPhase(PumpStationPausePhase.AfterDispatch)

        assertEquals(1, judgeDrain.size)
        assertEquals("judge", judgeDrain[0].text)
        assertEquals(1, dispatchDrain.size)
        assertEquals("dispatch", dispatchDrain[0].text)
    }

    @Test
    fun `drain returns empty list for unconfigured phase`() = runTest {
        val service = PumpStationSteeringService()
        val drained = service.drainForPhase(PumpStationPausePhase.BeforeGoalValidation)
        assertTrue(drained.isEmpty())
    }

    //=====================================Persistent overlay (Task 9)============================================

    @Test
    fun `persistent overlay survives drain and fires on next match`() = runTest {
        val service = PumpStationSteeringService()
        val phase = PumpStationPausePhase.BeforeJudge

        service.setPersistent(phase, content("always check the user"))

        val firstDrain = service.drainForPhase(phase)
        assertEquals(1, firstDrain.size)
        assertEquals("always check the user", firstDrain[0].text)
        assertTrue(service.hasPersistentOverlay(phase))

        val secondDrain = service.drainForPhase(phase)
        assertEquals(1, secondDrain.size, "persistent overlay must still fire on second drain")
        assertEquals("always check the user", secondDrain[0].text)
    }

    @Test
    fun `persistent overlay is replaced by a subsequent setPersistent`() = runTest {
        val service = PumpStationSteeringService()
        val phase = PumpStationPausePhase.BeforeJudge

        service.setPersistent(phase, content("first"))
        service.setPersistent(phase, content("second"))

        val drained = service.drainForPhase(phase)
        assertEquals(1, drained.size)
        assertEquals("second", drained[0].text, "second setPersistent must replace the prior overlay")
    }

    @Test
    fun `clearPersistent removes the overlay so subsequent drains are empty`() = runTest {
        val service = PumpStationSteeringService()
        val phase = PumpStationPausePhase.BeforeJudge

        service.setPersistent(phase, content("transient message"))
        assertTrue(service.hasPersistentOverlay(phase))

        service.clearPersistent(phase)
        assertEquals(false, service.hasPersistentOverlay(phase))

        val drained = service.drainForPhase(phase)
        assertTrue(drained.isEmpty())
    }

    //=====================================Combined semantics===================================================

    @Test
    fun `drain combines persistent overlay first then one-shot queue in FIFO order`() = runTest {
        val service = PumpStationSteeringService()
        val phase = PumpStationPausePhase.BeforeJudge

        service.setPersistent(phase, content("OVERLAY"))
        service.enqueueOneShot(phase, content("ONE-SHOT-1"))
        service.enqueueOneShot(phase, content("ONE-SHOT-2"))
        service.enqueueOneShot(phase, content("ONE-SHOT-3"))

        val drained = service.drainForPhase(phase)
        assertEquals(4, drained.size)
        assertEquals(listOf("OVERLAY", "ONE-SHOT-1", "ONE-SHOT-2", "ONE-SHOT-3"), drained.map { it.text })

        val secondDrain = service.drainForPhase(phase)
        assertEquals(1, secondDrain.size, "persistent overlay survives, one-shots are consumed")
        assertEquals("OVERLAY", secondDrain[0].text)
    }

    @Test
    fun `initial configuration seeds persistent overlays and one-shot queues`() = runTest {
        val config = PumpStationSteeringConfiguration(
            initialPersistentOverlays = mapOf(
                PumpStationPausePhase.BeforeJudge to content("seeded overlay")
            ),
            initialOneShotInstructions = mapOf(
                PumpStationPausePhase.BeforeDispatch to listOf(content("seeded one-shot"))
            )
        )

        val service = PumpStationSteeringService(config)

        val judgeDrain = service.drainForPhase(PumpStationPausePhase.BeforeJudge)
        assertEquals(1, judgeDrain.size)
        assertEquals("seeded overlay", judgeDrain[0].text)

        val dispatchDrain = service.drainForPhase(PumpStationPausePhase.BeforeDispatch)
        assertEquals(1, dispatchDrain.size)
        assertEquals("seeded one-shot", dispatchDrain[0].text)
    }
}
