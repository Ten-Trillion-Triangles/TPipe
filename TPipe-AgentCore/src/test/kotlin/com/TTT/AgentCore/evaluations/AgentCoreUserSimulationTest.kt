package com.TTT.AgentCore.evaluations

import aws.smithy.kotlin.runtime.content.Document
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class AgentCoreUserSimulationTest
{
    @Test
    fun stopsAfterSimulatorReturnsStopTurn()
    {
        val calls = mutableListOf<Int>()
        val simulator = AgentCoreUserSimulator { context ->
            calls += context.turnIndex
            AgentCoreSimulationTurn(
                input = Document.String("turn-${context.turnIndex}"),
                stop = context.turnIndex == 1,
                metadata = if(context.turnIndex == 1) mapOf("stopReason" to "scenario-complete") else emptyMap()
            )
        }

        val result = runBlocking { simulator.simulate(Document.String("scenario"), maxTurns = 5) }

        assertEquals(listOf(0, 1), calls)
        assertEquals(2, result.turns.size)
        assertEquals("scenario-complete", result.stoppedBy)
    }

    @Test
    fun stopsAfterSimulatorReturnsNull()
    {
        val simulator = AgentCoreUserSimulator { null }

        val result = runBlocking { simulator.simulate(Document.String("scenario"), maxTurns = 5) }

        assertEquals(emptyList(), result.turns)
        assertEquals("simulator-null", result.stoppedBy)
    }
}
