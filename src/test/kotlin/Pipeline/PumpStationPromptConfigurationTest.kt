package com.TTT.Pipeline

import com.TTT.Context.ConverseData
import com.TTT.Context.ConverseRole
import com.TTT.Enums.PumpStationGoalHistorySource
import com.TTT.Enums.PumpStationHistoryTransport
import com.TTT.Enums.PumpStationLatestContentPosition
import com.TTT.Pipe.MultimodalContent
import kotlinx.coroutines.runBlocking
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicReference

/**
 * Coverage for PumpStation prompt transport, goal-history selection, and
 * latest-output placement.
 */
class PumpStationPromptConfigurationTest
{
    private fun addHistory(station: PumpStation, text: String)
    {
        station.turnHistory.add(
            ConverseData(
                role = ConverseRole.agent,
                content = MultimodalContent(text = text)
            )
        )
    }

    private fun addRawHistory(station: PumpStation, text: String)
    {
        station.rawTurnHistory.add(
            ConverseData(
                role = ConverseRole.agent,
                content = MultimodalContent(text = text)
            )
        )
    }

    @Test
    fun defaultTransportUsesOnlyFlattenedHistory()
    {
        val station = PumpStation()
        addHistory(station, "TextOnlyCanary")

        val content = station.buildTurnContent()

        assertTrue(content.text.contains("TextOnlyCanary"))
        assertTrue(content.context.converseHistory.history.isEmpty())
    }

    @Test
    fun contextOnlyTransportAvoidsFlattenedHistory()
    {
        val station = PumpStation()
            .setHistoryTransport(PumpStationHistoryTransport.ContextOnly)
        addHistory(station, "ContextOnlyCanary")

        val content = station.buildTurnContent()

        assertFalse(content.text.contains("ContextOnlyCanary"))
        assertEquals(station.turnHistory, content.context.converseHistory)
    }

    @Test
    fun textAndContextTransportPreservesBothRepresentations()
    {
        val station = PumpStation()
            .setHistoryTransport(PumpStationHistoryTransport.TextAndContext)
        addHistory(station, "TextAndContextCanary")

        val content = station.buildTurnContent()

        assertTrue(content.text.contains("TextAndContextCanary"))
        assertEquals(station.turnHistory, content.context.converseHistory)
    }

    @Test
    fun goalAgentReceivesCuratedHistoryByDefault()
    {
        val goalAgent = MockP2PAgent()
        val station = PumpStation().setGoalAgent(goalAgent)
        addHistory(station, "CuratedHistoryCanary")
        addRawHistory(station, "RawHistoryCanary")

        runBlocking {
            station.runExitFlow(station.takeInterruptSnapshot())
        }

        val content = goalAgent.callLog.single()
        assertTrue(content.text.contains("CuratedHistoryCanary"))
        assertFalse(content.text.contains("RawHistoryCanary"))
        assertTrue(content.context.converseHistory.history.isEmpty())
    }

    @Test
    fun goalAgentCanReceiveFullHistory()
    {
        val goalAgent = MockP2PAgent()
        val station = PumpStation()
            .setGoalAgent(goalAgent)
            .setGoalHistorySource(PumpStationGoalHistorySource.Full)
        addHistory(station, "FullModeCuratedCanary")
        addRawHistory(station, "FullModeRawCanary")

        runBlocking {
            station.runExitFlow(station.takeInterruptSnapshot())
        }

        val content = goalAgent.callLog.single()
        assertTrue(content.text.contains("FullModeRawCanary"))
        assertFalse(content.text.contains("FullModeCuratedCanary"))
    }

    @Test
    fun fullGoalHistoryUsesStructuredContextWhenConfigured()
    {
        val goalAgent = MockP2PAgent()
        val station = PumpStation()
            .setGoalAgent(goalAgent)
            .setGoalHistorySource(PumpStationGoalHistorySource.Full)
            .setHistoryTransport(PumpStationHistoryTransport.ContextOnly)
        addHistory(station, "ContextModeCuratedCanary")
        addRawHistory(station, "ContextModeRawCanary")

        runBlocking {
            station.runExitFlow(station.takeInterruptSnapshot())
        }

        val content = goalAgent.callLog.single()
        assertFalse(content.text.contains("ContextModeRawCanary"))
        assertEquals(1, content.context.converseHistory.history.size)
        assertEquals("ContextModeRawCanary", content.context.converseHistory.history.single().content.text)
    }

    @Test
    fun goalHistorySourceDoesNotChangeRegularTurnContent()
    {
        val station = PumpStation()
            .setGoalHistorySource(PumpStationGoalHistorySource.Full)
        addHistory(station, "RegularTurnCuratedCanary")
        addRawHistory(station, "RegularTurnRawCanary")

        val content = station.buildTurnContent()

        assertTrue(content.text.contains("RegularTurnCuratedCanary"))
        assertFalse(content.text.contains("RegularTurnRawCanary"))
    }

    @Test
    fun noGoalPostGoalInputRetainsRawHistory()
    {
        val capturedContent = AtomicReference<MultimodalContent?>()
        val station = PumpStation().setPostGoalFunction { content, _ ->
            capturedContent.set(content)
            content
        }
        addHistory(station, "PostGoalCuratedCanary")
        addRawHistory(station, "PostGoalRawCanary")

        runBlocking {
            station.runExitFlow(station.takeInterruptSnapshot())
        }

        val content = requireNotNull(capturedContent.get())
        assertTrue(content.text.contains("PostGoalRawCanary"))
        assertFalse(content.text.contains("PostGoalCuratedCanary"))
    }

    @Test
    fun latestContentDefaultsToSuffix()
    {
        val station = PumpStation()
        addHistory(station, "HistoryBoundary")
        station.taskState.latestContent = MultimodalContent(text = "LatestSuffixCanary")

        val content = station.buildDispatchContent()

        assertTrue(content.text.indexOf("HistoryBoundary") < content.text.indexOf("LatestSuffixCanary"))
        assertTrue(content.text.endsWith("[/LATEST PRIOR AGENT OUTPUT]"))
    }

    @Test
    fun latestContentSupportsAllPositions()
    {
        val positions = listOf(
            PumpStationLatestContentPosition.Prefix,
            PumpStationLatestContentPosition.BeforeHistory,
            PumpStationLatestContentPosition.AfterHistory,
            PumpStationLatestContentPosition.Suffix
        )

        for(position in positions)
        {
            val station = PumpStation()
                .setLatestContentPosition(position)
            addHistory(station, "HISTORY_$position")
            station.taskState.latestContent = MultimodalContent(text = "LATEST_$position")

            val content = station.buildDispatchContent()

            assertTrue(content.text.contains("LATEST_$position"))
            assertTrue(content.text.contains("HISTORY_$position"))
        }
    }

    @Test
    fun latestContentDeduplicatesAgainstHistoryByDefault()
    {
        val station = PumpStation()
        addHistory(station, "DuplicateCanary")
        station.taskState.latestContent = MultimodalContent(text = "DuplicateCanary")

        val content = station.buildDispatchContent()

        assertEquals(1, content.text.windowed("DuplicateCanary".length).count { it == "DuplicateCanary" })
    }

    @Test
    fun latestContentDeduplicationCanBeDisabled()
    {
        val station = PumpStation()
            .setDeduplicateLatestContentAgainstHistory(false)
        addHistory(station, "DuplicateDisabledCanary")
        station.taskState.latestContent = MultimodalContent(text = "DuplicateDisabledCanary")

        val content = station.buildDispatchContent()

        assertEquals(2, content.text.windowed("DuplicateDisabledCanary".length).count { it == "DuplicateDisabledCanary" })
    }

    @Test
    fun latestContentInjectionCanBeDisabled()
    {
        val station = PumpStation()
            .setLatestContentInjectionEnabled(false)
        station.taskState.latestContent = MultimodalContent(text = "DisabledLatestCanary")

        val content = station.buildDispatchContent()

        assertFalse(content.text.contains("DisabledLatestCanary"))
    }

    @Test
    fun fluentSettersReturnTheSameStation()
    {
        val station = PumpStation()

        assertSame(station, station.setHistoryTransport(PumpStationHistoryTransport.ContextOnly))
        assertSame(station, station.setGoalHistorySource(PumpStationGoalHistorySource.Full))
        assertSame(station, station.setLatestContentInjectionEnabled(false))
        assertSame(station, station.setLatestContentPosition(PumpStationLatestContentPosition.Prefix))
        assertSame(station, station.setDeduplicateLatestContentAgainstHistory(false))
        assertEquals(PumpStationGoalHistorySource.Full, station.getGoalHistorySource())
    }

    @Test
    fun dslPromptConfigurationSurvivesPathPromotion()
    {
        val station = pumpStation("prompt-config-dsl") {
            dispatchAgent = Pipeline()
            promptConfiguration {
                historyTransport = PumpStationHistoryTransport.ContextOnly
                goalHistorySource = PumpStationGoalHistorySource.Full
                latestContentInjectionEnabled = false
                latestContentPosition = PumpStationLatestContentPosition.Prefix
                deduplicateLatestContentAgainstHistory = false
            }
            path("alpha") {
                description = "alpha path"
                setInternalAgent(SgTestAgent(agentTag = "alpha-agent"))
            }
        }

        addHistory(station, "DslHistoryCanary")
        station.taskState.latestContent = MultimodalContent(text = "DslLatestCanary")
        val content = station.buildDispatchContent()

        assertFalse(content.text.contains("DslHistoryCanary"))
        assertFalse(content.text.contains("DslLatestCanary"))
        assertEquals(1, content.context.converseHistory.history.size)
        assertEquals(PumpStationGoalHistorySource.Full, station.getGoalHistorySource())
    }
}
