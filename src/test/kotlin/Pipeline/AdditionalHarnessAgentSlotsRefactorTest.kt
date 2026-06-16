package com.TTT.Pipeline

import com.TTT.P2P.P2PInterface
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class AdditionalHarnessAgentSlotsRefactorTest
{
    @Test
    fun testAddHarnessAgentCreatesSlot()
    {
        val agent = SgTestAgent(agentTag = "test")
        val station = PumpStation().addHarnessAgent(agent)
        // The internal slot list should contain the agent with default Blocking concurrency
        val slotCount = station.getAdditionalHarnessAgentSlots().size
        assertEquals(1, slotCount)
        assertEquals(agent, station.getAdditionalHarnessAgentSlots()[0].agent)
        assertEquals(PumpStationConcurrencyMode.Blocking, station.getAdditionalHarnessAgentSlots()[0].concurrency)
    }

    @Test
    fun testAddHarnessAgentWithExplicitConcurrency()
    {
        val agent = SgTestAgent(agentTag = "async-agent")
        val station = PumpStation().addHarnessAgent(agent, PumpStationConcurrencyMode.Async)
        assertEquals(PumpStationConcurrencyMode.Async, station.getAdditionalHarnessAgentSlots()[0].concurrency)
    }

    @Test
    fun testAddHarnessAgentBuilderCreatesBuilderSlot()
    {
        val builderFn: (suspend (harness: PumpStation) -> P2PInterface) = { SgTestAgent(agentTag = "builder") }
        val station = PumpStation().addHarnessAgentBuilder(builderFn)
        val slot = station.getAdditionalHarnessAgentSlots()[0]
        assertNull(slot.agent)
        assertNotNull(slot.builderFunction)
    }

    @Test
    fun testClearHarnessAgentsRemovesAllSlots()
    {
        val builderFn: (suspend (harness: PumpStation) -> P2PInterface) = { SgTestAgent(agentTag = "b") }
        val station = PumpStation()
            .addHarnessAgent(SgTestAgent(agentTag = "a"))
            .addHarnessAgentBuilder(builderFn)
        assertEquals(2, station.getAdditionalHarnessAgentSlots().size)
        station.clearHarnessAgents()
        assertEquals(0, station.getAdditionalHarnessAgentSlots().size)
    }

    @Test
    fun testClearHarnessAgentBuildersRemovesOnlyBuilderSlots()
    {
        val builderFn: (suspend (harness: PumpStation) -> P2PInterface) = { SgTestAgent(agentTag = "b") }
        val station = PumpStation()
            .addHarnessAgent(SgTestAgent(agentTag = "a"))
            .addHarnessAgentBuilder(builderFn)
        station.clearHarnessAgentBuilders()
        val slots = station.getAdditionalHarnessAgentSlots()
        assertEquals(1, slots.size)
        assertNotNull(slots[0].agent)
        assertEquals("a", (slots[0].agent as SgTestAgent).agentTag)
    }
}
