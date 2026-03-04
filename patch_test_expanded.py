with open("src/test/kotlin/TraceVisualizationTest.kt", "r") as f:
    content = f.read()

# I will modify the generateMockPipelineTrace to include requestObject and generatedContent in the metadata map of one of the events.

search = r"""TraceEvent\(timestamp = baseTime \+ 1500, pipeId = "pipe-001", pipeName = "BedrockPipe-Claude", eventType = TraceEventType\.API_CALL_SUCCESS, phase = TracePhase\.EXECUTION, content = MultimodalContent\("API response"\), contextSnapshot = null, metadata = mapOf\("responseTokens" to 300\)\),"""

replace = r"""TraceEvent(timestamp = baseTime + 1500, pipeId = "pipe-001", pipeName = "BedrockPipe-Claude", eventType = TraceEventType.API_CALL_SUCCESS, phase = TracePhase.EXECUTION, content = MultimodalContent("API response"), contextSnapshot = null, metadata = mapOf("responseTokens" to 300, "requestObject" to "{ \"type\": \"test_request\" }", "generatedContent" to "Some generated content block")),"""

import re
content = re.sub(search, replace, content)

search_assert = r"""        assertTrue\(htmlReport\.contains\("Output Content"\), "HTML did not contain Output Content"\)"""
replace_assert = r"""        assertTrue(htmlReport.contains("Output Content"), "HTML did not contain Output Content")
        assertTrue(htmlReport.contains("Request Object"), "HTML did not contain Request Object")
        assertTrue(htmlReport.contains("Generated Content"), "HTML did not contain Generated Content")
        assertTrue(htmlReport.contains("{ &quot;type&quot;: &quot;test_request&quot; }"), "HTML did not contain test request block")"""

content = re.sub(search_assert, replace_assert, content)

with open("src/test/kotlin/TraceVisualizationTest.kt", "w") as f:
    f.write(content)
