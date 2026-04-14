package bedrockPipe

import com.TTT.Context.ConverseHistory
import com.TTT.Context.ConverseRole
import com.TTT.P2P.AgentRequest
import com.TTT.P2P.InputSchema
import com.TTT.Pipe.MultimodalContent
import com.TTT.Util.deserialize
import com.TTT.Util.serialize
import com.TTT.Util.serializeConverseHistory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * BUG INVESTIGATION: Root cause of ConverseHistory wrapping corruption.
 *
 * The bug is in ConverseHistory.add() at ConverseData.kt lines 82-87:
 *
 *     val contentJson: ConverseHistory? = extractJson<ConverseHistory>(content.text)
 *     if(contentJson != null)
 *     {
 *         history.addAll(contentJson.history)
 *         return  // <-- BUG: Early return prevents actual content from being added!
 *     }
 *
 * When you call history.add(ConverseRole.agent, MultimodalContent(text = agentRequestJson)):
 * 1. add() extracts ConverseHistory from the AgentRequest JSON text
 * 2. If extractJson returns non-null (lenient parsing), it does early return
 * 3. The actual AgentRequest content is NEVER stored in history
 * 4. serializeConverseHistory then returns {} because history is empty!
 */
class ConverseHistoryWrappingBugTest
{
    /**
     * BUG CONFIRMED: When AgentRequest JSON is added to ConverseHistory,
     * the add() function incorrectly treats it as a nested ConverseHistory due to lenient
     * extractJson parsing, and does early return without adding the actual content.
     *
     * Evidence:
     * - History size was 0 before and 0 after add()
     * - serializeConverseHistory returned "{}" because history was empty
     */
    @Test
    fun bugReproduction_converseHistoryAddTreatsJsonAsNestedHistory() {
        val agentRequest = AgentRequest(
            agentName = "Stepping Agent",
            prompt = "Debug the function",
            promptSchema = InputSchema.plainText
        )
        val agentRequestJson = serialize(agentRequest)

        val history = ConverseHistory()
        assertEquals(0, history.history.size, "History should start empty")

        // This is what embedContentIntoInternalConverse does
        history.add(ConverseRole.agent, MultimodalContent(text = agentRequestJson))

        // BUG: history is still empty because add() did early return
        // The add function parsed AgentRequest JSON as ConverseHistory and returned early
        assertEquals(0, history.history.size,
            "BUG CONFIRMED: ConverseHistory.add() did early return, history is empty. " +
            "The add() function incorrectly parsed AgentRequest JSON as ConverseHistory and returned early.")
    }

    /**
     * BUG CONFIRMED: embedContentIntoInternalConverse simulation produces {} output.
     *
     * This is the exact mechanism that causes MANAGER_DECISION to show responseLength: 2.
     */
    @Test
    fun bugReproduction_embedContentIntoInternalConverseReturnsEmptyJson() {
        val agentRequest = AgentRequest(
            agentName = "Stepping Agent",
            prompt = "Debug the function",
            promptSchema = InputSchema.plainText
        )
        val agentRequestJson = serialize(agentRequest)

        // Simulate embedContentIntoInternalConverse
        val history = ConverseHistory()
        history.add(ConverseRole.agent, MultimodalContent(text = agentRequestJson))

        val result = serializeConverseHistory(history)

        // BUG: Result is {} because history is empty
        // This is EXACTLY what we see in the trace: MANAGER_DECISION responseLength: 2
        assertEquals("{}", result,
            "BUG CONFIRMED: serializeConverseHistory returns {} because ConverseHistory.add() " +
            "did early return and never added the AgentRequest content. " +
            "This is the exact bug causing MANAGER_DECISION to show responseLength: 2")
    }

    /**
     * Valid JSON that is NOT a ConverseHistory also gets corrupted by the same bug.
     * This is because extractJson is lenient and parses any JSON structure.
     */
    @Test
    fun bugReproduction_anyJsonInConverseHistoryContentGetsCorrupted() {
        val validJson = """{"agentName": "Test Agent", "prompt": "test", "promptSchema": "plainText"}"""

        val history = ConverseHistory()
        history.add(ConverseRole.agent, MultimodalContent(text = validJson))

        val result = serializeConverseHistory(history)

        // BUG: Any JSON content triggers the same early return bug
        assertEquals("{}", result,
            "BUG CONFIRMED: Any JSON content gets corrupted because ConverseHistory.add() " +
            "uses lenient extractJson that returns non-null for any JSON structure")
    }

    /**
     * Plain text (non-JSON) content works correctly.
     * This proves the bug is specifically in how JSON content is handled.
     */
    @Test
    fun workingCase_plainTextInHistorySurvivesRoundTrip() {
        val history = ConverseHistory()
        history.add(ConverseRole.agent, MultimodalContent(text = "Debug this function"))

        val result = serializeConverseHistory(history)

        assertTrue(result.contains("Debug this function"),
            "Plain text should be preserved in history")
        assertTrue(result.contains("history"),
            "Result should contain history structure")
    }

    /**
     * Demonstrate the fix: use the correct add method that doesn't do JSON extraction.
     */
    @Test
    fun workaround_useAddWithConverseDataDirectly() {
        val agentRequest = AgentRequest(
            agentName = "Stepping Agent",
            prompt = "Debug the function",
            promptSchema = InputSchema.plainText
        )
        val agentRequestJson = serialize(agentRequest)

        val history = ConverseHistory()
        // Use add(ConverseData) directly instead of add(role, MultimodalContent)
        // This bypasses the JSON extraction logic
        val converseData = com.TTT.Context.ConverseData(
            role = ConverseRole.agent,
            content = MultimodalContent(text = agentRequestJson)
        )
        history.add(converseData)

        val result = serializeConverseHistory(history)

        assertTrue(result.contains("Stepping Agent"),
            "Using add(ConverseData) directly preserves the content. " +
            "This is the workaround for the bug.")
    }
}
