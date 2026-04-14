package bedrockPipe

import Defaults.BedrockConfiguration
import Defaults.reasoning.ReasoningBuilder.reasonWithBedrock
import Defaults.reasoning.ReasoningDepth
import Defaults.reasoning.ReasoningDuration
import Defaults.reasoning.ReasoningInjector
import Defaults.reasoning.ReasoningMethod
import Defaults.reasoning.ReasoningSettings
import com.TTT.Config.TPipeConfig
import com.TTT.Debug.TraceConfig
import com.TTT.Debug.TraceFormat
import com.TTT.Pipe.TokenBudgetSettings
import com.TTT.Pipeline.Pipeline
import com.TTT.Structs.PipeSettings
import com.TTT.Util.writeStringToFile
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.Ignore

class BestIdeaTest
{
    @Test @Ignore
    fun testBestIdea()
    {

         val QWEN_30B_MODEL_ID = "qwen.qwen3-coder-30b-a3b-v1:0"
         val QWEN_30B_MODEL_ARN = "arn:aws:bedrock:us-west-2::foundation-model/qwen.qwen3-coder-30b-a3b-v1:0"
         val QWEN_30B_REGION = "us-west-2"


        /**
         * Compose the pipe
         */
        val pipeline = Pipeline().apply {
            val bedrockSettings = BedrockConfiguration(region = "us-west-2", model = QWEN_30B_MODEL_ID)
            val reasoningSettings = ReasoningSettings(
                reasoningMethod = ReasoningMethod.BestIdea,
                depth = ReasoningDepth.High,
                duration = ReasoningDuration.Long,
                reasoningInjector = ReasoningInjector.AfterUserPrompt
            )
            val pipeSettings = PipeSettings(contextWindowSize = 32000, maxTokens = 5000)

            //declare reasoning pipe for testing set to best idea mode.
            val reasoningPipe = reasonWithBedrock(bedrockSettings, reasoningSettings, pipeSettings)

            val parentPipe = BedrockMultimodalPipe().apply {
                setRegion("us-west-2")
                setModel(QWEN_30B_MODEL_ID)
                setPipeName("best idea test pipe parent")
                setTokenBudget(TokenBudgetSettings(contextWindowSize = 32000, maxTokens = 5000))
                setSystemPrompt("Solve this riddle: I speak without a mouth and hear without ears. I have no body, but I come alive with the wind. What am I?")
                allowEmptyContentObject()
                allowEmptyUserPrompt()
                setTemperature(1.0)
                setTopP(.8)
                setReasoningPipe(reasoningPipe)
            }

            add(parentPipe)

            val traceConfig = TraceConfig(enabled = true,  outputFormat = TraceFormat.HTML)
            enableTracing(traceConfig)
            runBlocking { init(true) }
        }

        //Execute the pipeline and collect the trace result.
        runBlocking {
            val result = pipeline.execute("solve the riddle")
            val trace = pipeline.getTraceReport(TraceFormat.HTML)
            writeStringToFile("${TPipeConfig.getTraceDir()}/best-idea-test/trace.html", trace)
        }
    }
}