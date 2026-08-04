package com.TTT.Pipeline

import com.TTT.Context.ConverseHistory
import com.TTT.P2P.KillSwitch
import com.TTT.P2P.P2PInterface
import com.TTT.P2P.P2PRequest
import com.TTT.P2P.P2PResponse
import com.TTT.Pipe.MultimodalContent
import com.TTT.Pipe.Pipe
import com.TTT.Pipe.TokenBudgetSettings
import com.TTT.Structs.PipeSettings

/**
 * Test P2PInterface that records every call and returns scripted content in order.
 * If the script is exhausted, returns the input unchanged.
 */
class MockP2PAgent(
    override var killSwitch: KillSwitch? = null,
    val script: List<MultimodalContent> = emptyList()
) : P2PInterface
{
    val callLog = mutableListOf<MultimodalContent>()
    private var scriptIndex = 0
    var initCount = 0
        private set

    override suspend fun executeLocal(content: MultimodalContent): MultimodalContent
    {
        callLog.add(content)
        return if (scriptIndex < script.size) {
            val next = script[scriptIndex]
            scriptIndex++
            next
        } else {
            content
        }
    }

    override suspend fun executeP2PRequest(request: P2PRequest): P2PResponse? = null
    override fun setParentInterface(parent: P2PInterface) {}
    override fun getParentP2PInterface(): P2PInterface? = null
    override fun getPaths(): String = ""
    override fun setTokenBudgetRecursive(budget: TokenBudgetSettings) {}
    override fun getTokenBudgetSettings(): TokenBudgetSettings? = null
    override fun setPipeSettingsRecursively(settings: PipeSettings) {}
    override suspend fun P2PInit() { initCount++ }
}

/**
 * Test Pipeline wrapper around a MockP2PAgent. Since [Pipeline] is a final
 * class with no exposed extension points for swapping pipes, this wrapper
 * exposes the [mockAgent] directly for tests to drive.
 */
class MockPipeline(
    override var killSwitch: KillSwitch? = null,
    val mockAgent: MockP2PAgent = MockP2PAgent()
) : P2PInterface
{
    init {
        // The mock is exposed via mockAgent; tests can call mockAgent.executeLocal
        // directly without going through Pipeline's pipe chain.
    }

    override suspend fun executeLocal(content: MultimodalContent): MultimodalContent
    {
        return mockAgent.executeLocal(content)
    }

    override suspend fun executeP2PRequest(request: P2PRequest): P2PResponse? = null
    override fun setParentInterface(parent: P2PInterface) {}
    override fun getParentP2PInterface(): P2PInterface? = null
    override fun getPaths(): String = ""
    override fun setTokenBudgetRecursive(budget: TokenBudgetSettings) {}
    override fun getTokenBudgetSettings(): TokenBudgetSettings? = null
    override fun setPipeSettingsRecursively(settings: PipeSettings) {}
    override suspend fun P2PInit() { mockAgent.P2PInit() }
}

/**
 * Build a JudgeVerdict-serialized MultimodalContent for use as a scripted
 * judge agent response.
 */
fun judgeScriptedResponse(
    isComplete: Boolean = false,
    shouldTerminate: Boolean = false
): MultimodalContent {
    val json = """{"isComplete": $isComplete, "shouldTerminate": $shouldTerminate, "reason": ""}"""
    return MultimodalContent(text = json)
}

/**
 * Build a PathRequest-serialized MultimodalContent for use as a scripted
 * dispatch agent response.
 */
fun dispatchScriptedResponse(
    pathName: String,
    inputData: Map<String, String> = emptyMap()
): MultimodalContent {
    val inputJson = inputData.entries.joinToString(",") { "\"${it.key}\":\"${it.value}\"" }
    val json = """{"pathName": "$pathName", "inputData": {$inputJson}}"""
    return MultimodalContent(text = json)
}

/**
 * Build a PathObject suitable for testing: a simple execution function
 * that returns the input content with a marker.
 */
fun testPath(
    name: String,
    returnText: String = "test result",
    callCount: IntArray? = null,
    withExecutionFunction: Boolean = true
): PathObject {
    val path = PathObject().apply {
        pathName = name
        pathDescription = "Test path: $name"
        if (withExecutionFunction) {
            setExecutionFunction { content, _, _, _ ->
                callCount?.set(0, callCount[0] + 1)
                MultimodalContent(text = returnText, context = content.context)
            }
        }
    }
    return path
}

/**
 * Build a minimal valid PumpStation for tests, with sensible defaults.
 */
fun buildTestStation(
    maxHarnessTurns: Int = 50,
    maxGoalFailAttempts: Int = 3,
    blowoutThreshold: Double = 0.9
): PumpStation {
    return PumpStation()
        .setMaxHarnessTurns(maxHarnessTurns)
        .setMaxGoalFailAttempts(maxGoalFailAttempts)
        .setBlowoutThreshold(blowoutThreshold)
}

/**
 * Scripted test pipe used by pump-station phase tests. Returns a configured
 * JSON string from [generateText] so the pipeline execution produces a
 * deterministic [MultimodalContent] for the harness parser.
 *
 * @param name Display name (defaults to "scripted").
 * @param response The text returned by [generateText]. Use a JudgeVerdict or
 * PathRequest JSON literal to exercise the parsers.
 */
class ScriptedTestPipe(
    private val name: String = "scripted",
    var response: String = ""
) : Pipe()
{
    init {
        pipeName = name
    }

    override suspend fun generateText(promptInjector: String): String
    {
        return response
    }

    override fun truncateModuleContext(): Pipe
    {
        return this
    }
}
