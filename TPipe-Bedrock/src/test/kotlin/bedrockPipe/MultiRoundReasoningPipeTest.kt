package bedrockPipe

import com.TTT.Enums.ProviderName
import com.TTT.Pipeline.Pipeline
import com.TTT.Pipe.MultimodalContent
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Assertions.*

class MultiRoundReasoningPipeTest {
    @BeforeEach
    fun setup() {
        TestCredentialUtils.requireAwsCredentials()
    }

    @Test
    fun testBedrockSingleRoundReasoningPipe() {
        println("=== Single Round ===")
        val reasoningPipe = BedrockPipe()
            .setProvider(ProviderName.Aws)
            .setModel("us.amazon.nova-pro-v1:0")
        (reasoningPipe as BedrockPipe).setRegion("us-west-2")
        reasoningPipe.useConverseApi()
        reasoningPipe.pipeMetadata["reasoningRounds"] = 1
        reasoningPipe.pipeName = "Reasoning"

        val mainPipe = BedrockPipe()
            .setProvider(ProviderName.Aws)
            .setModel("us.amazon.nova-pro-v1:0")
        (mainPipe as BedrockPipe).setRegion("us-west-2")
        mainPipe.useConverseApi()
        mainPipe.pipeName = "Main"
        mainPipe.setReasoningPipe(reasoningPipe)

        val pipeline = Pipeline()
        pipeline.add(mainPipe)

        runBlocking {
            pipeline.init(initPipes = true)
            mainPipe.setMaxTokens(2000)

            val result = pipeline.execute(MultimodalContent(text = "What is 2+2? Answer in one word. Don't be confused."))
            System.err.println("Single Round Result Text: ${result.text}")
            System.err.println("Single Round Result Reasoning: ${result.modelReasoning}")
            assertFalse(result.text.contains("{\"history\":["), "Result text should not contain ConverseHistory JSON")
            assertFalse(result.text.contains("DEVELOPER PROMPT"), "Result text should not contain DEVELOPER PROMPT")
        }
    }

    @Test
    fun testBedrockMultiRoundReasoningPipe() {
        println("=== Multi Round ===")
        val reasoningPipe = BedrockPipe()
            .setProvider(ProviderName.Aws)
            .setModel("us.amazon.nova-pro-v1:0")
        (reasoningPipe as BedrockPipe).setRegion("us-west-2")
        reasoningPipe.useConverseApi()
        reasoningPipe.pipeMetadata["reasoningRounds"] = 2
        reasoningPipe.pipeMetadata["injectionMethod"] = "BeforeUserPrompt"
        reasoningPipe.pipeName = "Reasoning"

        val mainPipe = BedrockPipe()
            .setProvider(ProviderName.Aws)
            .setModel("us.amazon.nova-pro-v1:0")
        (mainPipe as BedrockPipe).setRegion("us-west-2")
        mainPipe.useConverseApi()
        mainPipe.pipeName = "Main"
        mainPipe.setReasoningPipe(reasoningPipe)

        val pipeline = Pipeline()
        pipeline.add(mainPipe)

        runBlocking {
            pipeline.init(initPipes = true)
            mainPipe.setMaxTokens(2000)

            val result = pipeline.execute(MultimodalContent(text = "What is 2+2? Answer in one word. I am confused."))
            System.err.println("Multi Round Result Text: ${result.text}")
            System.err.println("Multi Round Result Reasoning: ${result.modelReasoning}")
            assertFalse(result.text.contains("{\"history\":["), "Result text should not contain ConverseHistory JSON")
            assertFalse(result.text.contains("DEVELOPER PROMPT"), "Result text should not contain DEVELOPER PROMPT")
        }
    }

    @Test
    fun testNestedReasoningPipe() {
        println("=== Nested Reasoning ===")
        val innerReasoningPipe = BedrockPipe()
            .setProvider(ProviderName.Aws)
            .setModel("us.amazon.nova-pro-v1:0")
        (innerReasoningPipe as BedrockPipe).setRegion("us-west-2")
        innerReasoningPipe.useConverseApi()
        innerReasoningPipe.pipeMetadata["reasoningRounds"] = 1
        innerReasoningPipe.pipeName = "InnerReasoning"

        val outerReasoningPipe = BedrockPipe()
            .setProvider(ProviderName.Aws)
            .setModel("us.amazon.nova-pro-v1:0")
        (outerReasoningPipe as BedrockPipe).setRegion("us-west-2")
        outerReasoningPipe.useConverseApi()
        outerReasoningPipe.pipeMetadata["reasoningRounds"] = 1
        outerReasoningPipe.pipeName = "OuterReasoning"
        outerReasoningPipe.setReasoningPipe(innerReasoningPipe)

        val mainPipe = BedrockPipe()
            .setProvider(ProviderName.Aws)
            .setModel("us.amazon.nova-pro-v1:0")
        (mainPipe as BedrockPipe).setRegion("us-west-2")
        mainPipe.useConverseApi()
        mainPipe.pipeName = "Main"
        mainPipe.setReasoningPipe(outerReasoningPipe)

        val pipeline = Pipeline()
        pipeline.add(mainPipe)

        runBlocking {
            pipeline.init(initPipes = true)
            mainPipe.setMaxTokens(2000)

            val result = pipeline.execute(MultimodalContent(text = "What is 2+2? Answer in one word. Explain briefly."))
            System.err.println("Nested Round Result Text: ${result.text}")
            System.err.println("Nested Round Result Reasoning: ${result.modelReasoning}")
            assertFalse(result.text.contains("{\"history\":["), "Result text should not contain ConverseHistory JSON")
            assertFalse(result.text.contains("DEVELOPER PROMPT"), "Result text should not contain DEVELOPER PROMPT")
        }
    }
}
