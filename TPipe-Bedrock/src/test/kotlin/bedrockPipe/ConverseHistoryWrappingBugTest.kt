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
 * Tests to verify that ConverseHistory.add() correctly preserves JSON content.
 *
 * These tests verify the FIX for the bug where ConverseHistory.add() incorrectly
 * treated any JSON as a nested ConverseHistory and did early return.
 *
 * The fix checks if JSON starts with "{\"history\"" before parsing as ConverseHistory.
 */
class ConverseHistoryWrappingBugTest
{
    /**
     * Verify that AgentRequest JSON is correctly preserved in ConverseHistory.
     * After the fix, history should contain the content (not be empty).
     */
    @Test
    fun serializeConverseHistoryPreservesAgentRequestJson() {
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

        // After fix: history should contain the content (not be empty)
        assertTrue(history.history.isNotEmpty(),
            "ConverseHistory.add() should preserve AgentRequest JSON content")
        assertEquals(1, history.history.size, "History should have one entry")
    }

    /**
     * Verify that embedContentIntoInternalConverse returns valid output (not "{}").
     * After the fix, serializeConverseHistory should NOT return "{}".
     */
    @Test
    fun embedContentIntoInternalConversePreservesValidOutput() {
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

        // After fix: Result should NOT be "{}"
        assertTrue(result.isNotEmpty() && result != "{}",
            "serializeConverseHistory should NOT return empty JSON {}. Got: $result")
        assertTrue(result.contains("Stepping Agent"),
            "Result should contain the AgentRequest content")
    }

    /**
     * Verify that any valid JSON survives the round-trip through ConverseHistory.
     */
    @Test
    fun anyValidJsonSurvivesRoundTrip() {
        val validJson = """{"agentName": "Test Agent", "prompt": "test", "promptSchema": "plainText"}"""

        val history = ConverseHistory()
        history.add(ConverseRole.agent, MultimodalContent(text = validJson))

        val result = serializeConverseHistory(history)

        // After fix: Any valid JSON should survive round-trip
        assertTrue(result.contains("Test Agent"),
            "Any JSON content should survive round-trip through ConverseHistory")
    }

    /**
     * Verify that plain text (non-JSON) content works correctly.
     * This should have always worked and continues to work after the fix.
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
     * Verify that actual nested ConverseHistory JSON is still handled correctly.
     * This is the legitimate use case that the fix should preserve.
     */
    @Test
    fun nestedConverseHistoryJsonStillWorks() {
        // Create a valid ConverseHistory JSON that contains "history" key
        // We need at least one entry so the "history" field is serialized
        val nestedHistory = ConverseHistory()
        nestedHistory.add(ConverseRole.user, MultimodalContent(text = "Hello"))
        nestedHistory.add(ConverseRole.agent, MultimodalContent(text = "Hi there"))
        val nestedHistoryJson = serializeConverseHistory(nestedHistory)

        // Verify the serialized JSON contains "history" key (it's pretty-printed so check for presence)
        assertTrue(nestedHistoryJson.contains("\"history\""),
            "Nested history JSON should contain \"history\" key. Got: ${nestedHistoryJson.take(50)}")

        // Now add this as content to another history
        val outerHistory = ConverseHistory()
        outerHistory.add(ConverseRole.system, MultimodalContent(text = "You are a helpful assistant"))
        outerHistory.add(ConverseRole.agent, MultimodalContent(text = nestedHistoryJson))

        val result = serializeConverseHistory(outerHistory)

        // After fix: nested ConverseHistory should still be properly handled
        assertTrue(result.contains("Hello"), "Nested history content should be preserved")
        assertTrue(result.contains("Hi there"), "Nested history content should be preserved")
    }

    /**
     * Comprehensive round-trip test: add AgentRequest, serialize, deserialize, verify.
     */
    @Test
    fun agentRequestRoundTripThroughConverseHistory() {
        val agentRequest = AgentRequest(
            agentName = "Stepping Agent",
            prompt = "Debug the function",
            promptSchema = InputSchema.plainText
        )
        val agentRequestJson = serialize(agentRequest)

        // Add to history
        val history = ConverseHistory()
        history.add(ConverseRole.agent, MultimodalContent(text = agentRequestJson))

        // Serialize
        val historyJson = serializeConverseHistory(history)
        assertTrue(historyJson.contains("Stepping Agent"), "Serialized history should contain agentName")

        // Deserialize
        val deserialized = deserialize<ConverseHistory>(historyJson)
        assertNotNull(deserialized, "Deserialized history should not be null")
        assertTrue(deserialized!!.history.isNotEmpty(), "History should have entries after deserialization")

        // Extract AgentRequest
        val lastEntry = deserialized.history.lastOrNull()
        assertNotNull(lastEntry, "Should have a last entry")
        val extractedRequest = deserialize<AgentRequest>(lastEntry!!.content.text)
        assertNotNull(extractedRequest, "Should be able to extract AgentRequest")
        assertEquals("Stepping Agent", extractedRequest?.agentName,
            "AgentRequest should survive round-trip through ConverseHistory")
    }
}
