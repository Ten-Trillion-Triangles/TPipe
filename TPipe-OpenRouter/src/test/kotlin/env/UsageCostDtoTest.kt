package env

import com.TTT.Util.deserialize
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** OR-A: OpenRouter's current `usage` object now carries `cost` + OpenRouter-specific breakdowns. */
class UsageCostDtoTest
{
    @Test
    fun usageDeserializesCostAndOpenRouterFields()
    {
        val json = """
            {"prompt_tokens":10,"completion_tokens":20,"total_tokens":30,
             "cost":0.0004,"is_byok":false,
             "cost_details":{"upstream_inference_cost":0.0003,"upstream_inference_prompt_cost":0.0001,"upstream_inference_completions_cost":0.0002,"server_tool_cost":0},
             "deferred_tools_details":{"prompt_tokens":1,"completion_tokens":2,"total_tokens":3,"turns":1},
             "server_tool_use_details":{"tool_calls_executed":2,"tool_calls_requested":3,"web_search_requests":1}}
        """.trimIndent()
        val u = deserialize<UsageInfo>(json)
        assertNotNull(u)
        assertEquals(30, u.totalTokens)
        val cost = u.cost
        assertEquals(0.0004, cost!!, 1e-9)
        assertEquals(false, u.isByok)
        val cd = u.costDetails
        assertNotNull(cd)
        assertEquals(0.0003, cd.upstreamInferenceCost!!, 1e-9)
        assertEquals(0.0001, cd.upstreamInferencePromptCost!!, 1e-9)
        val deferred = u.deferredToolsDetails
        assertNotNull(deferred)
        assertEquals(3, deferred.totalTokens)
        assertEquals(1, deferred.turns)
        val st = u.serverToolUseDetails
        assertNotNull(st)
        assertEquals(2, st.toolCallsExecuted)
        assertEquals(3, st.toolCallsRequested)
    }

    @Test
    fun usageWithoutCostFieldsIsNullsThem()
    {
        val u = deserialize<UsageInfo>("""{"prompt_tokens":1,"completion_tokens":2,"total_tokens":3}""")
        assertNotNull(u)
        assertNull(u.cost)
        assertNull(u.costDetails)
        assertNull(u.deferredToolsDetails)
        assertNull(u.serverToolUseDetails)
    }
}
