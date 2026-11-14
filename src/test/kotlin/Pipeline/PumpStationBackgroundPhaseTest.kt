package com.TTT.Pipeline

import com.TTT.Debug.TracePhase
import com.TTT.Util.deserialize
import com.TTT.Util.serialize
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Tests for the [PumpStationPhase.BackgroundAgents] phase entry.
 *
 * The harness has two kinds of harness agent slots - Blocking (foreground) and
 * Async (background) - but only the foreground one originally had a phase of its
 * own. Background agent activity was reported under [PumpStationPhase.ForegroundAgents],
 * which made the two indistinguishable by phase alone.
 */
class PumpStationBackgroundPhaseTest
{
    //=====================================Phase entry=============================================================

    @Test
    fun `the phase enum has a background agent entry`() {
        val names = PumpStationPhase.entries.map { it.name }.toSet()

        assertTrue(
            "BackgroundAgents" in names,
            "PumpStationPhase must contain a BackgroundAgents entry"
        )
    }

    @Test
    fun `the background agent entry is distinct from the foreground entry`() {
        val background = PumpStationPhase.valueOf("BackgroundAgents")

        assertNotEquals(
            PumpStationPhase.ForegroundAgents,
            background,
            "the background phase must not alias the foreground phase"
        )
    }

    //=====================================Event defaults=========================================================

    @Test
    fun `a background agent queued event defaults to the background phase`() {
        val event = BackgroundAgentQueued(runId = "run-1", turnIndex = 0, agentName = "slot")

        assertEquals(
            PumpStationPhase.BackgroundAgents,
            event.phase,
            "a background agent event must report the background phase"
        )
    }

    @Test
    fun `a foreground agent completed event still reports the foreground phase`() {
        val event = ForegroundAgentCompleted(runId = "run-1", turnIndex = 0, agentName = "slot", result = null)

        assertEquals(
            PumpStationPhase.ForegroundAgents,
            event.phase,
            "a foreground agent event must keep reporting the foreground phase"
        )
    }

    //=====================================Serialization===========================================================

    @Test
    fun `the background phase survives a serialization round trip`() {
        val event = BackgroundAgentQueued(runId = "run-1", turnIndex = 0, agentName = "slot")

        // Defaults are encoded so the phase is actually written out; the default
        // serializer omits default-valued properties.
        val json = serialize(event, encodedefault = true)
        val restored = deserialize<BackgroundAgentQueued>(json)

        assertNotNull(restored, "the event must survive the round trip")
        assertEquals(
            PumpStationPhase.BackgroundAgents,
            restored!!.phase,
            "the phase must persist by name, not by ordinal"
        )
        assertTrue(
            json.contains("\"BackgroundAgents\""),
            "the phase must be written by name, not as an ordinal: $json"
        )
    }

    //=====================================Trace mapping==========================================================

    @Test
    fun `the background phase maps to the agent communication trace phase`() {
        val mapped = mapPumpStationPhaseToTracePhase(PumpStationPhase.BackgroundAgents)

        assertEquals(
            TracePhase.AGENT_COMMUNICATION,
            mapped,
            "the background phase must map onto the agent communication bucket"
        )
    }
}
