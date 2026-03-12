package ollamaPipe

import com.TTT.Pipe.MultimodalContent
import com.TTT.PipeContextProtocol.*
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PcpToolBugTest {

    fun createWidget(name: String, color: String, count: String): String {
        return "Widget created: name=$name, color=$color, count=$count"
    }

    @Test
    fun testPcpNamedArgumentsBug() = runBlocking {
        // Setup a simple function with multiple parameters
        FunctionRegistry.registerFunction("create_widget", ::createWidget)

        // Setup PCP Context
        val context = PcpContext().apply {
            transport = Transport.Auto
            addTPipeOption(TPipeContextOptions().apply {
                functionName = "create_widget"
                description = "Creates a widget with specific properties"
                params = mutableMapOf(
                    "name" to ContextOptionParameter(ParamType.String, "Name of the widget"),
                    "color" to ContextOptionParameter(ParamType.String, "Color of the widget"),
                    "count" to ContextOptionParameter(ParamType.String, "Number of widgets")
                )
            })
        }

        // Setup OllamaPipe
        val pipe = OllamaPipe()
            .setIP("127.0.0.1")
            .setPort(11434)
            .setModel("tinydolphin")
            .setTemperature(0.0)
            .setSystemPrompt("You have access to the following tools via Pipe Context Protocol (PCP). To use them, return a JSON object matching PcPRequest schema.")

        pipe.init()

        // 1. Prove the LLM produces output
        val prompt = "Say 'LLM is online' and nothing else."
        val resultContent = pipe.execute(MultimodalContent(prompt))
        val response = resultContent.text
        assertTrue(response.isNotBlank(), "LLM failed to respond or API error occurred")

        // 2. Test the parsing and handler fix directly to avoid tiny model hallucinations
        val expectedJsonResponse = """{
            "tPipeContextOptions": {
                "functionName": "create_widget",
                "params": {}
            },
            "callParams": {
                "name": "super_widget",
                "color": "red",
                "count": "5"
            }
        }"""

        // Parse PCP Request
        val parser = PcpResponseParser()
        val parseResult = parser.extractPcpRequests(expectedJsonResponse)

        assertTrue(parseResult.success, "Failed to parse simulated LLM response: ${parseResult.errors}")

        val request = parseResult.requests.first()
        val handler = PcpFunctionHandler()
        val result = handler.execute(request, context)

        // It should successfully execute with the correct named arguments merged
        assertEquals("Widget created: name=super_widget, color=red, count=5", result.output)
    }
}
