package com.TTT.Pipeline

import com.TTT.Context.ConverseData
import com.TTT.Context.ConverseRole
import com.TTT.Context.ContextBank
import com.TTT.Context.TodoList
import com.TTT.Context.TodoListTask
import com.TTT.Debug.TracePhase
import com.TTT.Enums.PumpStationForceTarget
import com.TTT.Enums.PumpStationGatewayHistorySource
import com.TTT.Enums.PumpStationGatewayPromptPlacement
import com.TTT.Enums.PumpStationGatewaySetting
import com.TTT.P2P.KillSwitch
import com.TTT.P2P.P2PInterface
import com.TTT.P2P.P2PRequest
import com.TTT.P2P.P2PResponse
import com.TTT.Pipe.MultimodalContent
import com.TTT.Pipe.Pipe
import com.TTT.Pipe.TokenBudgetSettings
import com.TTT.Structs.PipeSettings
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Tests for the PumpStation gateway agent and gateway system.
 *
 * A gateway is a named, LLM-driven gate that judges whether a step of a task has been
 * completed. Its verdict either advances an ordered stage - which reconfigures the
 * harness for the work that comes next - or leaves the stage locked and injects an
 * instruction into turn history.
 *
 * The surface tests come first: the phase entry, the enums, the request registry, the
 * content carrier and the station API all have to exist before the behavioral cases
 * can be expressed.
 */
class PumpStationGatewayTest
{
    //=====================================Phase entry=============================================================

    @Test
    fun `the phase enum has a gateway entry`() {
        val names = PumpStationPhase.entries.map { it.name }.toSet()

        assertTrue(
            "Gateway" in names,
            "PumpStationPhase must contain a Gateway entry"
        )
    }

    @Test
    fun `the gateway phase maps to the agent communication trace phase`() {
        val mapped = mapPumpStationPhaseToTracePhase(PumpStationPhase.Gateway)

        assertEquals(
            TracePhase.AGENT_COMMUNICATION,
            mapped,
            "the gateway phase must map onto the agent communication bucket"
        )
    }

    //=====================================Enums================================================================

    @Test
    fun `the gateway history source has a summarized and a full entry`() {
        assertEquals(
            setOf("Summarized", "Full"),
            PumpStationGatewayHistorySource.entries.map { it.name }.toSet(),
            "the gateway must be able to read either history view"
        )
    }

    @Test
    fun `the gateway prompt placement covers the system prompt and the user message`() {
        assertEquals(
            setOf("SystemPrompt", "BeforeHistory", "AfterHistory", "Suffix"),
            PumpStationGatewayPromptPlacement.entries.map { it.name }.toSet(),
            "the injectable prompt must be placeable in the system prompt and on either side of the history block"
        )
    }

    @Test
    fun `the gateway setting enum lists the settable harness settings`() {
        val names = PumpStationGatewaySetting.entries.map { it.name }.toSet()

        assertTrue("ForegroundTurnInterval" in names, "the foreground interval must be settable")
        assertTrue("BackgroundTurnInterval" in names, "the background interval must be settable")
        assertTrue("MaxConcurrentForegroundAgents" in names, "the foreground concurrency must be settable")
        assertTrue("MaxConcurrentBackgroundAgents" in names, "the background concurrency must be settable")
        assertTrue("CompactionThreshold" in names, "the compaction threshold must be settable")
        assertTrue("MaxTurnHistorySize" in names, "the history size must be settable")
        assertTrue("MaxTurns" in names, "the turn budget must be settable")
    }

    //=====================================Request registry=======================================================

    @Test
    fun `a requested gateway is drained once`() {
        val service = PumpStationGatewayService()
        service.request("todo-check")

        assertTrue(service.isRequested("todo-check"), "the request must be visible before it is drained")
        assertEquals(listOf("todo-check"), service.drain(), "the drain must return the requested gateway")
        assertFalse(service.isRequested("todo-check"), "a drained request is consumed")
        assertEquals(emptyList<String>(), service.drain(), "a second drain must be empty")
    }

    @Test
    fun `repeated requests for the same gateway drain once`() {
        val service = PumpStationGatewayService()
        service.request("todo-check")
        service.request("todo-check")

        assertEquals(
            listOf("todo-check"),
            service.drain(),
            "asking twice in one turn must produce one evaluation, not two"
        )
    }

    @Test
    fun `clear discards pending gateway requests`() {
        val service = PumpStationGatewayService()
        service.request("todo-check")

        service.clear()

        assertFalse(service.isRequested("todo-check"), "clear must discard the pending request")
    }

    //=====================================Content carrier========================================================

    @Test
    fun `the content object carries a gateway request`() {
        val content = MultimodalContent()
        content.requestGateway("todo-check")

        assertEquals(
            listOf("todo-check"),
            content.gatewayRequests,
            "the content object must carry the requested gateway"
        )
    }

    @Test
    fun `a copied content object keeps its gateway request`() {
        val content = MultimodalContent()
        content.requestGateway("todo-check")

        val copied = content.copy()

        assertEquals(
            listOf("todo-check"),
            copied.gatewayRequests,
            "a copied content object must keep the gateway request"
        )
    }

    //=====================================Station API==========================================================

    @Test
    fun `the station signals a gateway`() {
        val station = PumpStation()

        assertFalse(station.isGatewayRequested("todo-check"), "nothing is requested initially")
        station.signalGateway("todo-check")

        assertTrue(station.isGatewayRequested("todo-check"), "the signal must be visible")
    }

    @Test
    fun `signalGateway returns the station for chaining`() {
        val station = PumpStation()

        val returned = station.signalGateway("todo-check")

        assertSame(station, returned, "signalGateway must return this for chaining")
    }

    @Test
    fun `clearGatewayRequests discards every pending request`() {
        val station = PumpStation()
        station.signalGateway("todo-check")
        station.signalGateway("other")

        station.clearGatewayRequests()

        assertFalse(station.isGatewayRequested("todo-check"), "the first request must be cleared")
        assertFalse(station.isGatewayRequested("other"), "the second request must be cleared")
    }

    @Test
    fun `the station registers a named gateway`() {
        val station = PumpStation()

        station.setGateway(PumpStationGatewayConfig(name = "todo-check"))

        assertNotNull(station.getGateways()["todo-check"], "the gateway must be registered by name")
        assertEquals("todo-check", station.getGateways()["todo-check"]?.name, "the gateway keeps its name")
    }

    @Test
    fun `the station registers the ordered stage sequence`() {
        val station = PumpStation()

        station.setGatewayStages(
            listOf(
                PumpStationGatewayStage(name = "setup", gateway = "todo-check"),
                PumpStationGatewayStage(name = "implementation", gateway = "todo-check")
            )
        )

        assertEquals(2, station.getGatewayStages().size, "both stages must be registered")
        assertEquals("setup", station.getGatewayStages().first().name, "the stage order must be preserved")
        assertEquals(0, station.getGatewayStageIndex(), "the first stage is active initially")
    }

    @Test
    fun `the stage action model exposes the full reconfiguration set`() {
        val actions: List<PumpStationStageAction> = listOf(
            PumpStationStageAction.RevealPath("p1"),
            PumpStationStageAction.HidePath("p2"),
            PumpStationStageAction.SetVisiblePaths(listOf("p1", "p2")),
            PumpStationStageAction.SetHarnessAgentEnabled("reviewer", true),
            PumpStationStageAction.SetHarnessAgents(emptyList()),
            PumpStationStageAction.SetSetting(
                PumpStationGatewaySetting.MaxConcurrentBackgroundAgents,
                PumpStationGatewaySettingValue.IntValue(1)
            ),
            PumpStationStageAction.Custom { }
        )

        assertEquals(7, actions.size, "every reconfiguration kind must be expressible")
    }

    @Test
    fun `a harness agent slot carries a name and an enabled flag`() {
        val slot = HarnessAgentSlot(
            agent = null,
            concurrency = PumpStationConcurrencyMode.Blocking,
            name = "reviewer",
            enabled = false
        )

        assertEquals("reviewer", slot.name, "the slot must keep its name")
        assertFalse(slot.enabled, "the slot must report itself disabled")
    }

    @Test
    fun `a harness agent slot is enabled by default`() {
        val slot = HarnessAgentSlot(agent = null, concurrency = PumpStationConcurrencyMode.Blocking)

        assertTrue(slot.enabled, "existing slots must stay enabled")
    }

    //=====================================Fixtures================================================================

    private val completeVerdict = """{"complete": true, "reason": "all done", "instruction": ""}"""
    private val notCompleteVerdict =
        """{"complete": false, "reason": "tests not written", "instruction": "Write the unit test for task 3."}"""

    /**
     * Pipe that records the content it is handed and answers with a scripted verdict.
     *
     * The recorded content is what the gateway agent actually saw, so the prompt
     * assembly can be asserted without reaching into the station.
     */
    private class GatewayTestPipe(
        var verdict: String,
        private val orderLog: MutableList<String>? = null
    ) : Pipe()
    {
        val received = mutableListOf<MultimodalContent>()

        override suspend fun generateContent(content: MultimodalContent): MultimodalContent
        {
            received.add(content)
            orderLog?.add("gateway")
            return MultimodalContent(text = verdict)
        }

        override suspend fun generateText(promptInjector: String): String = verdict

        override fun truncateModuleContext(): Pipe = this
    }

    /**
     * P2P agent that records the order in which it was invoked.
     */
    private class RecordingAgent(
        private val orderLog: MutableList<String>,
        private val label: String
    ) : P2PInterface
    {
        override var killSwitch: KillSwitch? = null
        val received = mutableListOf<MultimodalContent>()

        override suspend fun executeLocal(content: MultimodalContent): MultimodalContent
        {
            received.add(content)
            orderLog.add(label)
            return content
        }

        override suspend fun executeP2PRequest(request: P2PRequest): P2PResponse? = null
        override fun setParentInterface(parent: P2PInterface) {}
        override fun getParentP2PInterface(): P2PInterface? = null
        override fun getPaths(): String = ""
        override fun setTokenBudgetRecursive(budget: TokenBudgetSettings) {}
        override fun getTokenBudgetSettings(): TokenBudgetSettings? = null
        override fun setPipeSettingsRecursively(settings: PipeSettings) {}
        override suspend fun P2PInit() {}
    }

    private fun notDoneJudge(): Pipeline
    {
        val pipe = ScriptedTestPipe(response = """{"isComplete": false, "shouldTerminate": false}""")
        return Pipeline().apply { add(pipe) }
    }

    private fun dispatchAlwaysPicks(pathName: String, gatewayRequest: String? = null): Pipeline
    {
        val gatewayField = gatewayRequest?.let { ", \"gatewayRequest\": \"$it\"" } ?: ""
        val pipe = ScriptedTestPipe(response = """{"pathName": "$pathName", "pathSchema": "{}"$gatewayField}""")
        return Pipeline().apply { add(pipe) }
    }

    /**
     * A path whose result asks for a gateway evaluation.
     */
    private fun requestingPath(
        name: String,
        gateway: String,
        orderLog: MutableList<String>? = null
    ): PathObject
    {
        return PathObject().apply {
            pathName = name
            pathDescription = "Test path: $name"
            setExecutionFunction { _, _, _, _ ->
                orderLog?.add("path")
                MultimodalContent(text = "path ran").also { it.requestGateway(gateway) }
            }
        }
    }

    private fun gatewayStation(
        pipe: GatewayTestPipe,
        stages: List<PumpStationGatewayStage> = emptyList(),
        config: PumpStationGatewayConfig = PumpStationGatewayConfig(name = "todo-check")
    ): PumpStation
    {
        val station = buildTestStation(maxHarnessTurns = 1)
            .setJudgeAgent(notDoneJudge())
            .setDispatchAgent(dispatchAlwaysPicks("p1"))
        station.setGateway(config.copy(agent = Pipeline().apply { add(pipe) }))
        station.setGatewayStages(stages)
        station.addPath(requestingPath("p1", "todo-check"))
        return station
    }

    //=====================================Triggers=============================================================

    @Test
    fun `a path result requesting a gateway fires it`() = runBlocking {
        val pipe = GatewayTestPipe(completeVerdict)
        val station = gatewayStation(pipe)

        station.executeLocal(MultimodalContent(text = "task"))

        assertTrue(pipe.received.isNotEmpty(), "the gateway agent must run when a path result requests it")
    }

    @Test
    fun `a direct station signal fires the gateway`() = runBlocking {
        val pipe = GatewayTestPipe(completeVerdict)
        val station = buildTestStation(maxHarnessTurns = 1)
            .setJudgeAgent(notDoneJudge())
            .setDispatchAgent(dispatchAlwaysPicks("p1"))
        station.setGateway(PumpStationGatewayConfig(name = "todo-check", agent = Pipeline().apply { add(pipe) }))
        station.addPath(testPath("p1"))
        station.signalGateway("todo-check")

        station.executeLocal(MultimodalContent(text = "task"))

        assertTrue(pipe.received.isNotEmpty(), "the gateway agent must run when the station is signalled")
    }

    @Test
    fun `a dispatch gatewayRequest fires the gateway`() = runBlocking {
        val pipe = GatewayTestPipe(completeVerdict)
        val station = buildTestStation(maxHarnessTurns = 1)
            .setJudgeAgent(notDoneJudge())
            .setDispatchAgent(dispatchAlwaysPicks("p1", gatewayRequest = "todo-check"))
        station.setGateway(PumpStationGatewayConfig(name = "todo-check", agent = Pipeline().apply { add(pipe) }))
        station.addPath(testPath("p1"))

        station.executeLocal(MultimodalContent(text = "task"))

        assertTrue(pipe.received.isNotEmpty(), "the gateway agent must run when the dispatch agent requests it")
    }

    @Test
    fun `the gateway phase does not run when nothing is requested`() = runBlocking {
        val pipe = GatewayTestPipe(completeVerdict)
        val station = buildTestStation(maxHarnessTurns = 1)
            .setJudgeAgent(notDoneJudge())
            .setDispatchAgent(dispatchAlwaysPicks("p1"))
        station.setGateway(PumpStationGatewayConfig(name = "todo-check", agent = Pipeline().apply { add(pipe) }))
        station.addPath(testPath("p1"))

        station.executeLocal(MultimodalContent(text = "task"))

        assertTrue(pipe.received.isEmpty(), "the gateway agent must not run when no gateway was requested")
    }

    @Test
    fun `an unknown gateway request is skipped without crashing`() = runBlocking {
        val events = mutableListOf<PumpStationEvent>()
        val station = buildTestStation(maxHarnessTurns = 1)
            .setJudgeAgent(notDoneJudge())
            .setDispatchAgent(dispatchAlwaysPicks("p1"))
            .setEventObserver { ev -> synchronized(events) { events.add(ev) } }
        station.addPath(testPath("p1"))
        station.signalGateway("does-not-exist")

        station.executeLocal(MultimodalContent(text = "task"))

        val skipped = synchronized(events) { events.filterIsInstance<GatewaySkipped>() }
        assertTrue(skipped.isNotEmpty(), "an unknown gateway must be reported as skipped")
        assertEquals("does-not-exist", skipped.first().gatewayName, "the skipped gateway must be named")
    }

    //=====================================Ordering==============================================================

    @Test
    fun `the gateway runs between path execution and the foreground agents`() = runBlocking {
        val order = mutableListOf<String>()
        val pipe = GatewayTestPipe(completeVerdict, order)
        val station = buildTestStation(maxHarnessTurns = 1)
            .setJudgeAgent(notDoneJudge())
            .setDispatchAgent(dispatchAlwaysPicks("p1"))
            .setForegroundTurnInterval(1)
        station.setGateway(PumpStationGatewayConfig(name = "todo-check", agent = Pipeline().apply { add(pipe) }))
        station.addPath(requestingPath("p1", "todo-check", order))
        station.addHarnessAgent(RecordingAgent(order, "foreground"), PumpStationConcurrencyMode.Blocking)

        station.executeLocal(MultimodalContent(text = "task"))

        assertEquals(
            listOf("path", "gateway", "foreground"),
            order,
            "the gateway must run after the path and before the foreground agents"
        )
    }

    //=====================================History view==========================================================

    @Test
    fun `the gateway reads the summarized history by default`() = runBlocking {
        val pipe = GatewayTestPipe(completeVerdict)
        val station = gatewayStation(pipe)
        station.turnHistory.add(ConverseData(role = ConverseRole.assistant, content = MultimodalContent(text = "CURATED-MARKER")))
        station.rawTurnHistory.add(ConverseData(role = ConverseRole.assistant, content = MultimodalContent(text = "FULL-MARKER")))

        station.executeLocal(MultimodalContent(text = "task"))

        val seen = pipe.received.first().text
        assertTrue("CURATED-MARKER" in seen, "the gateway must see the summarized history")
        assertFalse("FULL-MARKER" in seen, "the gateway must not see the full history by default")
    }

    @Test
    fun `the gateway reads the full history when configured`() = runBlocking {
        val pipe = GatewayTestPipe(completeVerdict)
        val station = gatewayStation(
            pipe,
            config = PumpStationGatewayConfig(
                name = "todo-check",
                historySource = PumpStationGatewayHistorySource.Full
            )
        )
        station.turnHistory.add(ConverseData(role = ConverseRole.assistant, content = MultimodalContent(text = "CURATED-MARKER")))
        station.rawTurnHistory.add(ConverseData(role = ConverseRole.assistant, content = MultimodalContent(text = "FULL-MARKER")))

        station.executeLocal(MultimodalContent(text = "task"))

        val seen = pipe.received.first().text
        assertTrue("FULL-MARKER" in seen, "the gateway must see the full history when configured")
        assertFalse("CURATED-MARKER" in seen, "the full history must not carry the curated-only entry")
    }

    //=====================================Prompt assembly=======================================================

    @Test
    fun `the saved system prompt is appended to the contract prompt`() = runBlocking {
        val criteria = "The task is only complete once the unit test file exists."
        val pipe = GatewayTestPipe(completeVerdict)
        val station = gatewayStation(
            pipe,
            config = PumpStationGatewayConfig(name = "todo-check", systemPrompt = criteria)
        )

        station.executeLocal(MultimodalContent(text = "task"))

        val systemPrompt = pipe.getSystemPromptForTest()
        assertTrue(
            DEFAULT_GATEWAY_PROMPT in systemPrompt,
            "the contract prompt must always be present"
        )
        assertTrue(
            criteria in systemPrompt,
            "the saved criteria must be injected into the system prompt"
        )
        assertTrue(
            systemPrompt.indexOf(criteria) > systemPrompt.indexOf(DEFAULT_GATEWAY_PROMPT),
            "the saved criteria must be appended after the contract prompt"
        )
    }

    @Test
    fun `the injectable user prompt is placed before the history by default`() = runBlocking {
        val instruction = "FOCUS-ON-TASK-3"
        val pipe = GatewayTestPipe(completeVerdict)
        val station = gatewayStation(
            pipe,
            config = PumpStationGatewayConfig(name = "todo-check", userPrompt = instruction)
        )

        station.executeLocal(MultimodalContent(text = "task"))

        val seen = pipe.received.first().text
        val instructionIndex = seen.indexOf(instruction)
        val historyIndex = seen.indexOf("[CONVERSATION HISTORY]")
        assertTrue(instructionIndex >= 0, "the instruction must reach the gateway")
        assertTrue(historyIndex >= 0, "the history block must be present")
        assertTrue(instructionIndex < historyIndex, "the default placement must sit before the history block")
    }

    @Test
    fun `the injectable user prompt can be placed in the system prompt`() = runBlocking {
        val instruction = "FOCUS-ON-TASK-3"
        val pipe = GatewayTestPipe(completeVerdict)
        val station = gatewayStation(
            pipe,
            config = PumpStationGatewayConfig(
                name = "todo-check",
                userPrompt = instruction,
                userPromptPlacement = PumpStationGatewayPromptPlacement.SystemPrompt
            )
        )

        station.executeLocal(MultimodalContent(text = "task"))

        assertTrue(
            instruction in pipe.getSystemPromptForTest(),
            "the instruction must land in the system prompt"
        )
        assertFalse(
            instruction in pipe.received.first().text,
            "the instruction must not also land in the user message"
        )
    }

    @Test
    fun `the injectable user prompt can be placed after the history`() = runBlocking {
        val instruction = "FOCUS-ON-TASK-3"
        val pipe = GatewayTestPipe(completeVerdict)
        val station = gatewayStation(
            pipe,
            config = PumpStationGatewayConfig(
                name = "todo-check",
                userPrompt = instruction,
                userPromptPlacement = PumpStationGatewayPromptPlacement.AfterHistory
            )
        )

        station.executeLocal(MultimodalContent(text = "task"))

        val seen = pipe.received.first().text
        val instructionIndex = seen.indexOf(instruction)
        val historyEndIndex = seen.indexOf("[/CONVERSATION HISTORY]")
        assertTrue(instructionIndex >= 0, "the instruction must reach the gateway")
        assertTrue(historyEndIndex >= 0, "the history block must be present")
        assertTrue(instructionIndex > historyEndIndex, "the instruction must sit after the history block")
    }

    @Test
    fun `the injectable user prompt can be placed at the suffix`() = runBlocking {
        val instruction = "FOCUS-ON-TASK-3"
        val pipe = GatewayTestPipe(completeVerdict)
        val station = gatewayStation(
            pipe,
            config = PumpStationGatewayConfig(
                name = "todo-check",
                userPrompt = instruction,
                userPromptPlacement = PumpStationGatewayPromptPlacement.Suffix
            )
        )

        station.executeLocal(MultimodalContent(text = "task"))

        val built = station.buildGatewayContent(station.getGateways().getValue("todo-check"))
        assertTrue(
            built.text.trimEnd().endsWith("[/GATEWAY INSTRUCTION]"),
            "the suffix placement must leave the instruction at the end of the user message"
        )

        val seen = pipe.received.first().text
        val instructionIndex = seen.indexOf(instruction)
        val historyEndIndex = seen.indexOf("[/CONVERSATION HISTORY]")
        assertTrue(instructionIndex >= 0, "the instruction must reach the gateway")
        assertTrue(instructionIndex > historyEndIndex, "the instruction must sit after the history block")
    }

    //=====================================Verdict handling======================================================

    @Test
    fun `a not-complete verdict injects the instruction into turn history`() = runBlocking {
        val pipe = GatewayTestPipe(notCompleteVerdict)
        val station = gatewayStation(pipe)

        station.executeLocal(MultimodalContent(text = "task"))

        val injected = station.turnHistory.history.filter { it.content.text.contains("Write the unit test for task 3.") }
        assertTrue(injected.isNotEmpty(), "the instruction must be injected into turn history")
        assertEquals(
            ConverseRole.harness,
            injected.first().role,
            "the instruction must be injected as a harness-role entry"
        )
    }

    @Test
    fun `a not-complete verdict keeps the stage locked`() = runBlocking {
        val pipe = GatewayTestPipe(notCompleteVerdict)
        val station = gatewayStation(
            pipe,
            stages = listOf(
                PumpStationGatewayStage(name = "setup", gateway = "todo-check"),
                PumpStationGatewayStage(name = "implementation", gateway = "todo-check")
            )
        )

        station.executeLocal(MultimodalContent(text = "task"))

        assertEquals(0, station.getGatewayStageIndex(), "an unmet gate must leave the stage locked")
    }

    @Test
    fun `a complete verdict advances the stage`() = runBlocking {
        val pipe = GatewayTestPipe(completeVerdict)
        val station = gatewayStation(
            pipe,
            stages = listOf(
                PumpStationGatewayStage(name = "setup", gateway = "todo-check"),
                PumpStationGatewayStage(name = "implementation", gateway = "todo-check")
            )
        )

        station.executeLocal(MultimodalContent(text = "task"))

        assertEquals(1, station.getGatewayStageIndex(), "a met gate must advance the stage")
        assertEquals(
            "implementation",
            station.getTaskState().gatewayStageName,
            "the advanced stage must be recorded by name"
        )
    }

    @Test
    fun `an advisory stage advances on a not-complete verdict`() = runBlocking {
        val pipe = GatewayTestPipe(notCompleteVerdict)
        val station = gatewayStation(
            pipe,
            stages = listOf(
                PumpStationGatewayStage(name = "setup", gateway = "todo-check", gateBlocking = false),
                PumpStationGatewayStage(name = "implementation", gateway = "todo-check")
            )
        )

        station.executeLocal(MultimodalContent(text = "task"))

        assertEquals(1, station.getGatewayStageIndex(), "an advisory gate must not hold the stage")
    }

    @Test
    fun `an unparseable verdict fails closed`() = runBlocking {
        val events = mutableListOf<PumpStationEvent>()
        val pipe = GatewayTestPipe("this is not json at all")
        val station = buildTestStation(maxHarnessTurns = 1)
            .setJudgeAgent(notDoneJudge())
            .setDispatchAgent(dispatchAlwaysPicks("p1"))
            .setEventObserver { ev -> synchronized(events) { events.add(ev) } }
        station.setGateway(PumpStationGatewayConfig(name = "todo-check", agent = Pipeline().apply { add(pipe) }))
        station.setGatewayStages(listOf(PumpStationGatewayStage(name = "setup", gateway = "todo-check")))
        station.addPath(requestingPath("p1", "todo-check"))

        station.executeLocal(MultimodalContent(text = "task"))

        val evaluated = synchronized(events) { events.filterIsInstance<GatewayEvaluated>() }
        assertTrue(evaluated.isNotEmpty(), "the evaluation must be reported")
        assertFalse(evaluated.first().complete, "an unparseable verdict must fail closed")
        assertTrue(evaluated.first().parseFailed, "the parse failure must be reported")
        assertEquals(0, station.getGatewayStageIndex(), "an unparseable verdict must keep the stage locked")
    }

    @Test
    fun `a throwing gateway agent leaves the stage locked`() = runBlocking {
        val throwing = object : Pipe()
        {
            override suspend fun generateContent(content: MultimodalContent): MultimodalContent
            {
                throw RuntimeException("gateway exploded")
            }

            override suspend fun generateText(promptInjector: String): String = ""

            override fun truncateModuleContext(): Pipe = this
        }
        val station = buildTestStation(maxHarnessTurns = 1)
            .setJudgeAgent(notDoneJudge())
            .setDispatchAgent(dispatchAlwaysPicks("p1"))
        station.setGateway(PumpStationGatewayConfig(name = "todo-check", agent = Pipeline().apply { add(throwing) }))
        station.setGatewayStages(listOf(PumpStationGatewayStage(name = "setup", gateway = "todo-check")))
        station.addPath(requestingPath("p1", "todo-check"))

        station.executeLocal(MultimodalContent(text = "task"))

        assertEquals(0, station.getGatewayStageIndex(), "a throwing gateway must keep the stage locked")
    }

    @Test
    fun `the gateway verdict parses a valid json verdict`() {
        val content = MultimodalContent(text = """{"complete": true, "reason": "done", "instruction": "none"}""")

        val verdict = parseGatewayVerdict(content)

        assertTrue(verdict.complete, "a valid verdict must parse")
        assertEquals("done", verdict.reason, "the reason must survive the parse")
    }

    @Test
    fun `the gateway verdict falls back to the pass flag`() {
        val content = MultimodalContent(text = "no json here")
        content.passPipeline = true

        val verdict = parseGatewayVerdict(content)

        assertTrue(verdict.complete, "the pass flag must complete the verdict")
    }

    @Test
    fun `an unparseable verdict without flags fails closed`() {
        val content = MultimodalContent(text = "no json here")

        val verdict = parseGatewayVerdict(content)

        assertFalse(verdict.complete, "an unparseable verdict must fail closed")
    }

    //=====================================Regression: the turn still finishes===================================

    @Test
    fun `a failing gateway still lets the memory update phase run`() = runBlocking {
        val lorebook = MockP2PAgent(script = listOf(
            MultimodalContent(text = """{"compactedThroughTurn": 0, "updates": [], "deletions": []}""")
        ))
        val events = mutableListOf<PumpStationEvent>()
        val pipe = GatewayTestPipe(notCompleteVerdict)
        val station = buildTestStation(maxHarnessTurns = 1)
            .setJudgeAgent(notDoneJudge())
            .setDispatchAgent(dispatchAlwaysPicks("p1"))
            .setLorebookAgent(lorebook)
            .setEventObserver { ev -> synchronized(events) { events.add(ev) } }
        station.setGateway(PumpStationGatewayConfig(name = "todo-check", agent = Pipeline().apply { add(pipe) }))
        station.addPath(requestingPath("p1", "todo-check"))
        station.force(PumpStationForceTarget.Lorebook)

        station.executeLocal(MultimodalContent(text = "task"))

        val memory = synchronized(events) { events.filterIsInstance<MemoryUpdateStarted>() }
        assertTrue(memory.isNotEmpty(), "the memory update phase must still run after a failing gateway")
        assertTrue(lorebook.callLog.isNotEmpty(), "the memory agents must still run after a failing gateway")
    }

    @Test
    fun `a failing gateway still lets the compaction phase run`() = runBlocking {
        val summary = MockP2PAgent(script = listOf(MultimodalContent(text = "compacted")))
        val events = mutableListOf<PumpStationEvent>()
        val pipe = GatewayTestPipe(notCompleteVerdict)
        val station = buildTestStation(maxHarnessTurns = 1)
            .setJudgeAgent(notDoneJudge())
            .setDispatchAgent(dispatchAlwaysPicks("p1"))
            .setSummaryAgent(summary)
            .setEventObserver { ev -> synchronized(events) { events.add(ev) } }
        station.setGateway(PumpStationGatewayConfig(name = "todo-check", agent = Pipeline().apply { add(pipe) }))
        station.addPath(requestingPath("p1", "todo-check"))
        station.force(PumpStationForceTarget.Compaction)

        station.executeLocal(MultimodalContent(text = "task"))

        val compaction = synchronized(events) { events.filterIsInstance<CompactionStarted>() }
        assertTrue(compaction.isNotEmpty(), "the compaction phase must still run after a failing gateway")
    }

    @Test
    fun `a failing gateway still lets the foreground agents run`() = runBlocking {
        val foreground = RecordingAgent(mutableListOf(), "foreground")
        val pipe = GatewayTestPipe(notCompleteVerdict)
        val station = buildTestStation(maxHarnessTurns = 1)
            .setJudgeAgent(notDoneJudge())
            .setDispatchAgent(dispatchAlwaysPicks("p1"))
            .setForegroundTurnInterval(1)
        station.setGateway(PumpStationGatewayConfig(name = "todo-check", agent = Pipeline().apply { add(pipe) }))
        station.addPath(requestingPath("p1", "todo-check"))
        station.addHarnessAgent(foreground, PumpStationConcurrencyMode.Blocking)

        station.executeLocal(MultimodalContent(text = "task"))

        assertTrue(foreground.received.isNotEmpty(), "the foreground agents must still run after a failing gateway")
    }

    //=====================================Todo binding==========================================================

    @Test
    fun `a todo-bound gateway receives the linked task`() = runBlocking {
        val todoList = TodoList()
        todoList.tasks.tasks.add(
            TodoListTask(
                taskNumber = 3,
                task = "Write the unit test",
                completionRequirements = "A failing test exists for task 3"
            )
        )
        ContextBank.emplaceTodoList("gateway-todo", todoList, allowUpdatesOnly = false)

        val pipe = GatewayTestPipe(completeVerdict)
        val station = gatewayStation(
            pipe,
            config = PumpStationGatewayConfig(
                name = "todo-check",
                todoPageKey = "gateway-todo",
                todoTaskNumber = 3
            )
        )

        // The built content is what carries the metadata: the pipe rebuilds the content
        // object before it reaches the model, so the binding is asserted on the content
        // the gateway phase assembled.
        val built = station.buildGatewayContent(station.getGateways().getValue("todo-check"))
        assertNotNull(built.metadata["todoTask"], "the linked todo task must be exposed in the metadata")
        assertTrue(
            "Write the unit test" in built.text,
            "the linked todo task must be rendered into the gateway message"
        )

        station.executeLocal(MultimodalContent(text = "task"))

        assertTrue(
            "Write the unit test" in pipe.received.first().text,
            "the linked todo task must reach the gateway agent"
        )
    }

    //=====================================Stage reconfiguration==================================================

    /**
     * Build a station whose gateway advances from "setup" into "implementation", where the
     * second stage carries the supplied reconfiguration.
     */
    private fun stagedStation(
        pipe: GatewayTestPipe,
        onEnter: List<PumpStationStageAction>,
        gateBlocking: Boolean = true
    ): PumpStation
    {
        val station = buildTestStation(maxHarnessTurns = 1)
            .setJudgeAgent(notDoneJudge())
            .setDispatchAgent(dispatchAlwaysPicks("p1"))
        station.setGateway(PumpStationGatewayConfig(name = "todo-check", agent = Pipeline().apply { add(pipe) }))
        station.setGatewayStages(
            listOf(
                PumpStationGatewayStage(name = "setup", gateway = "todo-check"),
                PumpStationGatewayStage(name = "implementation", gateway = "todo-check", gateBlocking = gateBlocking, onEnter = onEnter)
            )
        )
        station.addPath(requestingPath("p1", "todo-check"))
        station.addPath(testPath("p2"))
        station.addReservePath(testPath("p3"))
        return station
    }

    @Test
    fun `a complete verdict applies the stage reconfiguration`() = runBlocking {
        val pipe = GatewayTestPipe(completeVerdict)
        val station = stagedStation(
            pipe,
            listOf(
                PumpStationStageAction.SetSetting(
                    PumpStationGatewaySetting.MaxConcurrentBackgroundAgents,
                    PumpStationGatewaySettingValue.IntValue(1)
                )
            )
        )

        station.executeLocal(MultimodalContent(text = "task"))

        assertEquals(
            1,
            station.maxConcurrentBackgroundAgentsInternal,
            "the stage reconfiguration must change the station setting"
        )
    }

    @Test
    fun `SetVisiblePaths replaces the visible path set`() = runBlocking {
        val pipe = GatewayTestPipe(completeVerdict)
        val station = stagedStation(
            pipe,
            listOf(PumpStationStageAction.SetVisiblePaths(listOf("p2")))
        )

        station.executeLocal(MultimodalContent(text = "task"))

        val visible = station.getVisiblePathNames()
        assertTrue("p2" in visible, "the listed path must stay visible")
        assertFalse("p1" in visible, "an unlisted path must be hidden")
        assertFalse("p3" in visible, "a reserve path must stay hidden")
    }

    @Test
    fun `RevealPath reveals a reserve path`() = runBlocking {
        val pipe = GatewayTestPipe(completeVerdict)
        val station = stagedStation(
            pipe,
            listOf(PumpStationStageAction.RevealPath("p3"))
        )

        station.executeLocal(MultimodalContent(text = "task"))

        assertTrue(
            "p3" in station.getVisiblePathNames(),
            "the revealed path must become visible to the dispatch agent"
        )
    }

    @Test
    fun `HidePath hides a visible path`() = runBlocking {
        val pipe = GatewayTestPipe(completeVerdict)
        val station = stagedStation(
            pipe,
            listOf(PumpStationStageAction.HidePath("p1"))
        )

        station.executeLocal(MultimodalContent(text = "task"))

        assertFalse(
            "p1" in station.getVisiblePathNames(),
            "the hidden path must leave the visible set"
        )
    }

    @Test
    fun `SetHarnessAgentEnabled enables a named slot`() = runBlocking {
        val pipe = GatewayTestPipe(completeVerdict)
        val station = stagedStation(
            pipe,
            listOf(PumpStationStageAction.SetHarnessAgentEnabled("reviewer", true))
        )
        station.addHarnessAgent(RecordingAgent(mutableListOf(), "reviewer"), PumpStationConcurrencyMode.Blocking, name = "reviewer", enabled = false)

        station.executeLocal(MultimodalContent(text = "task"))

        val slot = station.getAdditionalHarnessAgentSlots().firstOrNull { it.name == "reviewer" }
        assertNotNull(slot, "the named slot must still be registered")
        assertTrue(slot!!.enabled, "the stage action must enable the named slot")
    }

    @Test
    fun `SetHarnessAgents replaces the roster`() = runBlocking {
        val pipe = GatewayTestPipe(completeVerdict)
        val station = stagedStation(
            pipe,
            listOf(
                PumpStationStageAction.SetHarnessAgents(
                    listOf(
                        HarnessAgentSlot(
                            agent = RecordingAgent(mutableListOf(), "replacement"),
                            concurrency = PumpStationConcurrencyMode.Async,
                            name = "replacement"
                        )
                    )
                )
            )
        )
        station.addHarnessAgent(RecordingAgent(mutableListOf(), "original"), PumpStationConcurrencyMode.Blocking, name = "original")

        station.executeLocal(MultimodalContent(text = "task"))

        val slots = station.getAdditionalHarnessAgentSlots()
        assertEquals(1, slots.size, "the roster must be replaced wholesale")
        assertEquals("replacement", slots.first().name, "the replacement slot must be the one that remains")
    }

    @Test
    fun `a setting value of the wrong type is rejected`() = runBlocking {
        val events = mutableListOf<PumpStationEvent>()
        val pipe = GatewayTestPipe(completeVerdict)
        val station = stagedStation(
            pipe,
            listOf(
                PumpStationStageAction.SetSetting(
                    PumpStationGatewaySetting.MaxConcurrentBackgroundAgents,
                    PumpStationGatewaySettingValue.StringValue("not a number")
                )
            )
        )
        station.setEventObserver { ev -> synchronized(events) { events.add(ev) } }

        station.executeLocal(MultimodalContent(text = "task"))

        val advanced = synchronized(events) { events.filterIsInstance<GatewayStageAdvanced>() }
        val stageAdvance = advanced.lastOrNull { it.toStage == "implementation" }
        assertNotNull(stageAdvance, "the stage advance must be reported")
        assertTrue(stageAdvance!!.failed.isNotEmpty(), "the mistyped setting must be reported as a failure")
        assertEquals(
            3,
            station.maxConcurrentBackgroundAgentsInternal,
            "a rejected setting must not change the station"
        )
    }

    @Test
    fun `a custom stage action runs`() = runBlocking {
        var ran = false
        val pipe = GatewayTestPipe(completeVerdict)
        val station = stagedStation(
            pipe,
            listOf(PumpStationStageAction.Custom { ran = true })
        )

        station.executeLocal(MultimodalContent(text = "task"))

        assertTrue(ran, "the custom reconfiguration must run when the stage advances")
    }

    @Test
    fun `a failing stage action does not stop the remaining actions`() = runBlocking {
        val pipe = GatewayTestPipe(completeVerdict)
        val station = stagedStation(
            pipe,
            listOf(
                PumpStationStageAction.RevealPath("no-such-path"),
                PumpStationStageAction.SetSetting(
                    PumpStationGatewaySetting.MaxConcurrentBackgroundAgents,
                    PumpStationGatewaySettingValue.IntValue(2)
                )
            )
        )

        station.executeLocal(MultimodalContent(text = "task"))

        assertEquals(
            2,
            station.maxConcurrentBackgroundAgentsInternal,
            "a failing action must not stop the actions that follow it"
        )
    }

    @Test
    fun `the first stage reconfiguration is applied at startup`() = runBlocking {
        val pipe = GatewayTestPipe(completeVerdict)
        val station = buildTestStation(maxHarnessTurns = 1)
            .setJudgeAgent(notDoneJudge())
            .setDispatchAgent(dispatchAlwaysPicks("p1"))
        station.setGateway(PumpStationGatewayConfig(name = "todo-check", agent = Pipeline().apply { add(pipe) }))
        station.setGatewayStages(
            listOf(
                PumpStationGatewayStage(
                    name = "setup",
                    gateway = "todo-check",
                    onEnter = listOf(
                        PumpStationStageAction.SetSetting(
                            PumpStationGatewaySetting.MaxConcurrentBackgroundAgents,
                            PumpStationGatewaySettingValue.IntValue(4)
                        )
                    )
                )
            )
        )
        station.addPath(testPath("p1"))

        station.executeLocal(MultimodalContent(text = "task"))

        assertEquals(
            4,
            station.maxConcurrentBackgroundAgentsInternal,
            "the first stage is active from the start, so its reconfiguration must already apply"
        )
    }

    //=====================================DSL surface================================================================

    @Test
    fun `the DSL registers a gateway and its stages`() {
        val station = pumpStation("gateway-dsl") {
            // The gateway is declared before the path block on purpose: declaring a path
            // promotes the builder, so the gateway configuration has to survive that
            // promotion. A dropped carry-over silently loses the gateway.
            gateway("todo-check") {
                agent = Pipeline()
                systemPrompt = "The step is done when the tests pass."
                userPrompt = "Focus on task 3."
                userPromptPlacement = PumpStationGatewayPromptPlacement.AfterHistory
                historySource = PumpStationGatewayHistorySource.Full
                todoPageKey = "main"
                todoTaskNumber = 3
            }

            dispatchAgent = Pipeline()
            path("p1") { }

            gatewayStage("setup") {
                gateway = "todo-check"
            }

            gatewayStage("implementation") {
                gateway = "todo-check"
                gateBlocking = false
                revealPath("p2")
                hidePath("p3")
                setVisiblePaths("p1", "p2")
                enableHarnessAgent("reviewer")
                disableHarnessAgent("other")
                setSetting(
                    PumpStationGatewaySetting.MaxConcurrentBackgroundAgents,
                    PumpStationGatewaySettingValue.IntValue(1)
                )
                onEnter { }
            }
        }

        val gateway = station.getGateways()["todo-check"]
        assertNotNull(gateway, "the DSL must register the gateway")
        assertEquals("The step is done when the tests pass.", gateway!!.systemPrompt, "the criteria must survive the builder promotion")
        assertEquals("Focus on task 3.", gateway.userPrompt, "the injectable instruction must survive the builder promotion")
        assertEquals(PumpStationGatewayPromptPlacement.AfterHistory, gateway.userPromptPlacement, "the placement must survive")
        assertEquals(PumpStationGatewayHistorySource.Full, gateway.historySource, "the history source must survive")
        assertEquals("main", gateway.todoPageKey, "the todo binding must survive")
        assertEquals(3, gateway.todoTaskNumber, "the todo task number must survive")

        val stages = station.getGatewayStages()
        assertEquals(2, stages.size, "both stages must be registered")
        assertEquals("setup", stages[0].name, "the stage order must be preserved")
        assertTrue(stages[0].gateBlocking, "a stage is blocking by default")
        assertFalse(stages[1].gateBlocking, "the advisory flag must survive")
        assertEquals(7, stages[1].onEnter.size, "every reconfiguration kind must be expressible in the DSL")
    }

    @Test
    fun `the DSL harness agent carries a name and an enabled flag`() {
        val station = pumpStation("gateway-dsl-slots") {
            dispatchAgent = Pipeline()
            path("p1") { }
            harnessAgent(
                MockP2PAgent(),
                concurrency = PumpStationConcurrencyMode.Blocking,
                name = "reviewer",
                enabled = false
            ) { }
        }

        val slot = station.getAdditionalHarnessAgentSlots().firstOrNull { it.name == "reviewer" }
        assertNotNull(slot, "the named slot must be registered")
        assertFalse(slot!!.enabled, "the disabled flag must survive the DSL build")
    }

    //=====================================Event Serialization====================================================

    @Test
    fun `gateway events round-trip through the polymorphic event serializer`() {
        val evaluated: PumpStationEvent = GatewayEvaluated(
            runId = "ps-gateway-1",
            turnIndex = 3,
            gatewayName = "todo-check",
            stageName = "tests",
            complete = false,
            reason = "the suite was not run",
            instruction = "run the suite"
        )
        val advanced: PumpStationEvent = GatewayStageAdvanced(
            runId = "ps-gateway-1",
            turnIndex = 3,
            fromStage = "tests",
            toStage = "implementation",
            applied = listOf("reveal path implement"),
            failed = emptyList()
        )
        val skipped: PumpStationEvent = GatewaySkipped(
            runId = "ps-gateway-1",
            turnIndex = 3,
            gatewayName = "missing",
            reason = "no gateway named missing is configured"
        )

        val restoredEvaluated = com.TTT.Util.deserialize<PumpStationEvent>(
            com.TTT.Util.serialize(evaluated)
        )
        val restoredAdvanced = com.TTT.Util.deserialize<PumpStationEvent>(
            com.TTT.Util.serialize(advanced)
        )
        val restoredSkipped = com.TTT.Util.deserialize<PumpStationEvent>(
            com.TTT.Util.serialize(skipped)
        )

        assertNotNull(restoredEvaluated, "the evaluated event must survive a round-trip")
        assertTrue(
            restoredEvaluated is GatewayEvaluated,
            "the concrete event subtype must be restored, not the base interface"
        )
        val evaluatedBack = restoredEvaluated as GatewayEvaluated
        assertEquals("todo-check", evaluatedBack.gatewayName, "the gateway name must survive")
        assertEquals("tests", evaluatedBack.stageName, "the stage name must survive")
        assertEquals("the suite was not run", evaluatedBack.reason, "the reason must survive")
        assertEquals("run the suite", evaluatedBack.instruction, "the instruction must survive")
        assertFalse(evaluatedBack.complete, "the verdict must survive")
        assertEquals(PumpStationPhase.Gateway, evaluatedBack.phase, "the phase must survive")

        assertNotNull(restoredAdvanced, "the stage event must survive a round-trip")
        assertTrue(
            restoredAdvanced is GatewayStageAdvanced,
            "the stage event subtype must be restored"
        )
        val advancedBack = restoredAdvanced as GatewayStageAdvanced
        assertEquals("tests", advancedBack.fromStage, "the previous stage must survive")
        assertEquals("implementation", advancedBack.toStage, "the new stage must survive")
        assertEquals(listOf("reveal path implement"), advancedBack.applied, "the applied actions must survive")

        assertNotNull(restoredSkipped, "the skipped event must survive a round-trip")
        assertTrue(
            restoredSkipped is GatewaySkipped,
            "the skipped event subtype must be restored"
        )
        val skippedBack = restoredSkipped as GatewaySkipped
        assertEquals("missing", skippedBack.gatewayName, "the gateway name must survive")
        assertEquals(
            "no gateway named missing is configured",
            skippedBack.reason,
            "the skip reason must survive"
        )
    }

    //=====================================Review findings=========================================================

    @Test
    fun `HidePath hides a reserve path that was revealed by its predicate`() = runBlocking {
        val events = mutableListOf<PumpStationEvent>()
        val pipe = GatewayTestPipe(completeVerdict)
        val station = buildTestStation(maxHarnessTurns = 1)
            .setJudgeAgent(notDoneJudge())
            .setDispatchAgent(dispatchAlwaysPicks("p1"))
            .setEventObserver { ev -> synchronized(events) { events.add(ev) } }
        station.setGateway(PumpStationGatewayConfig(name = "todo-check", agent = Pipeline().apply { add(pipe) }))
        station.setGatewayStages(
            listOf(
                PumpStationGatewayStage(name = "setup", gateway = "todo-check"),
                PumpStationGatewayStage(
                    name = "implementation",
                    gateway = "todo-check",
                    onEnter = listOf(PumpStationStageAction.HidePath("p3"))
                )
            )
        )
        station.addPath(requestingPath("p1", "todo-check"))
        station.addReservePath(testPath("p3").apply { revealWhen = { _, _ -> true } })

        station.executeLocal(MultimodalContent(text = "task"))

        val revealed = synchronized(events) { events.filterIsInstance<ReservePathRevealed>() }
        assertTrue(
            revealed.any { it.pathName == "p3" },
            "the reserve path must have been revealed by its predicate before the hide"
        )
        assertFalse(
            "p3" in station.getVisiblePathNames(),
            "a reserve path revealed by its predicate must be hideable"
        )
        val advanced = synchronized(events) { events.filterIsInstance<GatewayStageAdvanced>() }
        val advance = advanced.lastOrNull { it.toStage == "implementation" }
        assertNotNull(advance, "the stage advance must be reported")
        assertTrue(
            advance!!.failed.isEmpty(),
            "the hide must not be recorded as a failed action"
        )
    }

    @Test
    fun `a throwing Custom stage action is recorded and the remaining actions still run`() = runBlocking {
        val events = mutableListOf<PumpStationEvent>()
        val pipe = GatewayTestPipe(completeVerdict)
        val station = buildTestStation(maxHarnessTurns = 1)
            .setJudgeAgent(notDoneJudge())
            .setDispatchAgent(dispatchAlwaysPicks("p1"))
            .setEventObserver { ev -> synchronized(events) { events.add(ev) } }
        station.setGateway(PumpStationGatewayConfig(name = "todo-check", agent = Pipeline().apply { add(pipe) }))
        station.setGatewayStages(
            listOf(
                PumpStationGatewayStage(name = "setup", gateway = "todo-check"),
                PumpStationGatewayStage(
                    name = "implementation",
                    gateway = "todo-check",
                    onEnter = listOf(
                        PumpStationStageAction.Custom { _ -> throw IllegalStateException("boom") },
                        PumpStationStageAction.SetSetting(
                            PumpStationGatewaySetting.MaxConcurrentBackgroundAgents,
                            PumpStationGatewaySettingValue.IntValue(3)
                        )
                    )
                )
            )
        )
        station.addPath(requestingPath("p1", "todo-check"))

        station.executeLocal(MultimodalContent(text = "task"))

        assertEquals(
            3,
            station.maxConcurrentBackgroundAgentsInternal,
            "the action after the throwing one must still run"
        )
        val advanced = synchronized(events) { events.filterIsInstance<GatewayStageAdvanced>() }
        val advance = advanced.lastOrNull { it.toStage == "implementation" }
        assertNotNull(advance, "the stage advance must still be reported")
        assertTrue(
            advance!!.failed.isNotEmpty(),
            "the throwing action must be recorded as a failed action"
        )
    }

    @Test
    fun `a gateway agent that is not a Pipeline is skipped with a diagnostic`() = runBlocking {
        val events = mutableListOf<PumpStationEvent>()
        val station = buildTestStation(maxHarnessTurns = 1)
            .setJudgeAgent(notDoneJudge())
            .setDispatchAgent(dispatchAlwaysPicks("p1"))
            .setEventObserver { ev -> synchronized(events) { events.add(ev) } }
        station.setGateway(
            PumpStationGatewayConfig(
                name = "todo-check",
                builderFunction = { RecordingAgent(mutableListOf(), "not-a-pipeline") }
            )
        )
        station.setGatewayStages(listOf(PumpStationGatewayStage(name = "setup", gateway = "todo-check")))
        station.addPath(requestingPath("p1", "todo-check"))

        station.executeLocal(MultimodalContent(text = "task"))

        val skipped = synchronized(events) { events.filterIsInstance<GatewaySkipped>() }
        assertTrue(
            skipped.isNotEmpty(),
            "a gateway agent that is not a Pipeline must be skipped with a diagnostic"
        )
        assertTrue(
            "Pipeline" in skipped.first().reason,
            "the skip reason must name the cause"
        )
        assertEquals(0, station.getGatewayStageIndex(), "the stage must stay locked")
    }

    @Test
    fun `the request registry drains in request order`() {
        val service = PumpStationGatewayService()

        service.request("todo-check")
        service.request("review")
        service.request("build")
        service.request("deploy")

        assertEquals(
            listOf("todo-check", "review", "build", "deploy"),
            service.drain(),
            "the drain must return the names in request order"
        )
    }

    @Test
    fun `a request that races a drain is never lost`() {
        val service = PumpStationGatewayService()
        val drained = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        val requested = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        val stop = java.util.concurrent.atomic.AtomicBoolean(false)

        val requesters = (0 until 3).map { worker ->
            Thread {
                var counter = 0
                while(!stop.get())
                {
                    val name = "gateway-${worker}-${counter++}"
                    service.request(name)
                    requested.add(name)
                }
            }
        }
        val drainers = (0 until 2).map {
            Thread {
                while(!stop.get())
                {
                    service.drain().forEach { drained.add(it) }
                }
            }
        }

        requesters.forEach { it.start() }
        drainers.forEach { it.start() }
        Thread.sleep(800)
        stop.set(true)
        requesters.forEach { it.join() }
        drainers.forEach { it.join() }
        service.drain().forEach { drained.add(it) }

        val lost = requested - drained
        assertTrue(
            lost.isEmpty(),
            "a request must never be silently lost"
        )
    }

    @Test
    fun `a cancelled gateway agent propagates the cancellation`() = runBlocking {
        val events = mutableListOf<PumpStationEvent>()
        // A pipe swallows its own exceptions, so the cancellation is raised from the
        // pipeline's pre-validation seam - the path that rethrows to its caller.
        val gateway = Pipeline().apply {
            add(GatewayTestPipe(completeVerdict))
            preValidationFunction = { _, _, _ -> throw kotlinx.coroutines.CancellationException("cancelled") }
        }
        val station = buildTestStation(maxHarnessTurns = 1)
            .setJudgeAgent(notDoneJudge())
            .setDispatchAgent(dispatchAlwaysPicks("p1"))
            .setEventObserver { ev -> synchronized(events) { events.add(ev) } }
        station.setGateway(PumpStationGatewayConfig(name = "todo-check", agent = gateway))
        station.addPath(requestingPath("p1", "todo-check"))

        try
        {
            station.executeLocal(MultimodalContent(text = "task"))
        }
        catch(e: Throwable)
        {
            // The cancellation is expected to leave the harness; the assertion below
            // checks that it was not mis-reported as a gateway failure.
        }

        val evaluated = synchronized(events) { events.filterIsInstance<GatewayEvaluated>() }
        assertTrue(
            evaluated.isEmpty(),
            "a cancellation must not be reported as a gateway evaluation failure"
        )
    }

    @Test
    fun `SetVisiblePaths reports every missing path name`() = runBlocking {
        val events = mutableListOf<PumpStationEvent>()
        val pipe = GatewayTestPipe(completeVerdict)
        val station = buildTestStation(maxHarnessTurns = 1)
            .setJudgeAgent(notDoneJudge())
            .setDispatchAgent(dispatchAlwaysPicks("p1"))
            .setEventObserver { ev -> synchronized(events) { events.add(ev) } }
        station.setGateway(PumpStationGatewayConfig(name = "todo-check", agent = Pipeline().apply { add(pipe) }))
        station.setGatewayStages(
            listOf(
                PumpStationGatewayStage(name = "setup", gateway = "todo-check"),
                PumpStationGatewayStage(
                    name = "implementation",
                    gateway = "todo-check",
                    onEnter = listOf(PumpStationStageAction.SetVisiblePaths(listOf("nope1", "nope2")))
                )
            )
        )
        station.addPath(requestingPath("p1", "todo-check"))

        station.executeLocal(MultimodalContent(text = "task"))

        val advanced = synchronized(events) { events.filterIsInstance<GatewayStageAdvanced>() }
        val advance = advanced.lastOrNull { it.toStage == "implementation" }
        assertNotNull(advance, "the stage advance must be reported")
        val failures = advance!!.failed.joinToString(" ")
        assertTrue("nope1" in failures, "the first missing path must be reported")
        assertTrue("nope2" in failures, "the second missing path must be reported")
    }
}
