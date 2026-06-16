package com.TTT.Pipeline

import com.TTT.P2P.KillSwitch
import com.TTT.P2P.P2PInterface
import com.TTT.P2P.P2PRequest
import com.TTT.P2P.P2PResponse
import com.TTT.Pipe.MultimodalContent
import com.TTT.Pipe.TokenBudgetSettings
import com.TTT.Structs.PipeSettings
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class HarnessAgentSlotTest
{
    @Test
    fun testSlotWithAgentOnly()
    {
        val agent = StubAgent()
        val slot = HarnessAgentSlot(agent = agent, concurrency = PumpStationConcurrencyMode.Blocking)
        assertEquals(agent, slot.agent)
        assertEquals(PumpStationConcurrencyMode.Blocking, slot.concurrency)
        assertNull(slot.builderFunction)
    }

    @Test
    fun testSlotWithBuilderFunction()
    {
        val slot = HarnessAgentSlot(
            agent = null,
            concurrency = PumpStationConcurrencyMode.Async,
            builderFunction = { _ -> StubAgent() }
        )
        assertNotNull(slot.builderFunction)
        assertNull(slot.agent)
    }

    private class StubAgent : P2PInterface
    {
        override var killSwitch: KillSwitch? = null
        override suspend fun P2PInit() {}
        override suspend fun executeLocal(content: MultimodalContent): MultimodalContent = content
        override suspend fun executeP2PRequest(request: P2PRequest): P2PResponse? = null
        override fun setParentInterface(parent: P2PInterface) {}
        override fun getParentP2PInterface(): P2PInterface? = null
        override fun getPaths(): String = ""
        override fun setTokenBudgetRecursive(budget: TokenBudgetSettings) {}
        override fun getTokenBudgetSettings(): TokenBudgetSettings? = null
        override fun setPipeSettingsRecursively(settings: PipeSettings) {}
    }
}
