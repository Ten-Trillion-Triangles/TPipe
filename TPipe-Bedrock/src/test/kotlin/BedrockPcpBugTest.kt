import bedrockPipe.BedrockPipe
import com.TTT.Pipe.MultimodalContent
import com.TTT.PipeContextProtocol.*
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BedrockPcpBugTest {

    fun createWidget(name: String, color: String, count: String): String {
        return "Widget created: name=$name, color=$color, count=$count"
    }

    @Test
    fun testPcpNamedArgumentsBugWithAws() = runBlocking {
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

        // Setup BedrockPipe
        val pipe = BedrockPipe()
        pipe.setModel("anthropic.claude-3-haiku-20240307-v1:0")
        pipe.setRegion("us-east-1")
        pipe.setTemperature(0.0)
        pipe.useConverseApi()

        // Use system prompt to enforce schema JSON structure exactly as it would be expected
        pipe.setSystemPrompt("You have access to the following tools via Pipe Context Protocol (PCP). To use them, return a JSON object exactly matching the requested schema.")

        pipe.init()

        // This is the bug-triggering prompt: we instruct the LLM to put the arguments into the `params` map of `tPipeContextOptions`.
        // This was the original behavior before `callParams` was introduced, causing the deserialization to fail.
        val pcpSchema = """
        {
          "tPipeContextOptions": {
            "functionName": "",
            "description": "",
            "params": {}
          },
          "callParams": {}
        }
        """.trimIndent()

        val prompt = """
            You are a helpful assistant that creates widgets.
            Call the 'create_widget' function to create 5 red widgets named 'super_widget'.
            You MUST return a JSON object that matches the PcPRequest schema below.

            PcPRequest Schema:
            $pcpSchema

            Crucially, instead of positional arguments, use the 'callParams' object to pass the named arguments. 'params' should remain empty.
            Like this:
            {
                "tPipeContextOptions": {
                    "functionName": "create_widget",
                    "params": {}
                },
                "callParams": {
                    "name": "super_widget",
                    "color": "red",
                    "count": "5"
                }
            }

            Return ONLY the raw JSON object and nothing else. No markdown block formatting! Just the JSON string.
        """.trimIndent()

        val resultContent = pipe.execute(prompt)
        val response = resultContent

        println("LLM Response:\n$response")

        // 1. The LLM must return a response, API shouldn't fail
        assertTrue(response.isNotBlank(), "LLM failed to respond or API error occurred")

        // Parse PCP Request
        val parser = PcpResponseParser()
        val parseResult = parser.extractPcpRequests(response)

        if (parseResult.success) {
            val request = parseResult.requests.first()
            val handler = PcpFunctionHandler()
            val result = handler.execute(request, context)

            // It should successfully execute with the correct named arguments
            println("Output: ${result.output}"); println("Handler error: ${result.error}"); println("Request contents: ${request.callParams}"); println("Request params: ${request.tPipeContextOptions.params}")
            assertEquals("Widget created: name=super_widget, color=red, count=5", result.output)
        } else {
            // It failed to parse, capturing the bug.
            println("Bug captured: Failed to parse request: ${parseResult.errors}")
            println("Bug captured: Failed to parse request: ${parseResult.errors}")
            println("Original response: ${parseResult.originalResponse}")
            // Assert failure to make the test fail when the bug is present
            assertTrue(parseResult.success, "Bug captured: PCP request parsing failed due to type mismatch or missing parameters. Errors: ${parseResult.errors}\nResponse was:\n${parseResult.originalResponse}")
        }
    }
}
