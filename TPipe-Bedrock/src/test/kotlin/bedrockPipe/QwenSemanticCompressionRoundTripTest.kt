package bedrockPipe

import com.TTT.Config.TPipeConfig
import com.TTT.Debug.PipeTracer
import com.TTT.Debug.TraceConfig
import com.TTT.Debug.TraceDetailLevel
import com.TTT.Debug.TraceFormat
import com.TTT.Enums.ProviderName
import com.TTT.Pipeline.Pipeline
import com.TTT.Pipe.MultimodalContent
import com.TTT.Structs.PipeSettings
import Defaults.BedrockConfiguration
import Defaults.reasoning.ReasoningBuilder
import Defaults.reasoning.ReasoningDepth
import Defaults.reasoning.ReasoningDuration
import Defaults.reasoning.ReasoningInjector
import Defaults.reasoning.ReasoningMethod
import Defaults.reasoning.ReasoningSettings
import com.TTT.Util.buildSemanticDecompressionInstructions
import com.TTT.Util.semanticCompress
import com.TTT.Util.writeStringToFile
import env.bedrockEnv
import java.nio.file.Files
import kotlin.io.path.deleteIfExists
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

private const val QWEN_30B_MODEL_ID = "qwen.qwen3-coder-30b-a3b-v1:0"
private const val QWEN_30B_MODEL_ARN = "arn:aws:bedrock:us-west-2::foundation-model/qwen.qwen3-coder-30b-a3b-v1:0"
private const val QWEN_30B_REGION = "us-west-2"

class QwenSemanticCompressionRoundTripTest
{
    @Test
    fun liveQwenSemanticDecompressionReasoningPipeReconstructsCompressedPromptAndSavesTrace()
    {
        TestCredentialUtils.requireAwsCredentials()

        val traceConfig = TraceConfig(
            enabled = true,
            outputFormat = TraceFormat.HTML,
            detailLevel = TraceDetailLevel.DEBUG,
            includeContext = true,
            includeMetadata = true
        )

        println("Using Qwen foundation model $QWEN_30B_MODEL_ID ($QWEN_30B_MODEL_ARN) in $QWEN_30B_REGION")

        val inferenceConfigPath = Files.createTempFile("tpipe-qwen-inference", ".txt")
        val originalPrompt = buildRoundTripPromptFixture()
        val compression = semanticCompress(originalPrompt)
        val compressedPrompt = if(compression.legend.isNotBlank())
        {
            "${compression.legend}\n\n${compression.compressedText}"
        }
        else
        {
            compression.compressedText
        }

        println("Semantic compression lengths: original=${originalPrompt.length} compressed=${compression.compressedText.length}")

        val resultTraceDir = "${TPipeConfig.getTraceDir()}/Library/qwen-semantic-compression-round-trip"
        val pipelineTracePath = "$resultTraceDir/pipeline.html"
        val agentTracePath = "$resultTraceDir/agent.html"
        val reasoningTracePath = "$resultTraceDir/reasoning.html"

        bedrockEnv.resetInferenceConfig()
        bedrockEnv.setInferenceConfigFile(inferenceConfigPath.toFile())
        inferenceConfigPath.toFile().writeText("$QWEN_30B_MODEL_ID=\n")

        val reasoningPipe = (ReasoningBuilder.reasonWithBedrock(
            BedrockConfiguration(
                region = QWEN_30B_REGION,
                model = QWEN_30B_MODEL_ID,
                inferenceProfile = "",
                useConverseApi = true
            ),
            ReasoningSettings(
                reasoningMethod = ReasoningMethod.SemanticDecompression,
                depth = ReasoningDepth.High,
                duration = ReasoningDuration.Long,
                reasoningInjector = ReasoningInjector.BeforeUserPrompt,
                numberOfRounds = 1,
                focusPoints = mutableMapOf()
            ),
            PipeSettings(
                pipeName = "qwen semantic decompression reasoning",
                provider = ProviderName.Aws,
                model = QWEN_30B_MODEL_ID,
                temperature = 0.1,
                topP = 0.2,
                maxTokens = 4096,
                contextWindowSize = 10000
            )
        ) as BedrockMultimodalPipe).apply {
            setPipeName("qwen semantic decompression reasoning")
            setReadTimeout(600)
            enableMaxTokenOverflow()
            enableTracing(traceConfig)
        }

        val qwenPipe = BedrockMultimodalPipe().apply {
            setProvider(ProviderName.Aws)
            setModel(QWEN_30B_MODEL_ID)
            setRegion(QWEN_30B_REGION)
            useConverseApi()
            setPipeName("qwen semantic compression decompressor")
            setTemperature(0.1)
            setTopP(0.2)
            setMaxTokens(8192)
            setReadTimeout(600)
            enableMaxTokenOverflow()
            setReasoningPipe(reasoningPipe)
            setSystemPrompt(
                buildSemanticDecompressionInstructions() + "\n\n" + """
                    You are a reconstruction agent for a TPipe semantic-compressed prompt.
                    Rebuild the original text into normal, fully readable English.
                    Restore omitted grammar, glue words, and syntax wherever needed to recover the original meaning.
                    Preserve quoted spans exactly.
                    Return only the reconstructed text with no summary, no commentary, and no compressed style.
                """.trimIndent()
            )
            enableTracing(traceConfig)
        }
        println("Reasoning method=${reasoningPipe.pipeMetadata["reasoningMethod"]}")

        val pipeline = Pipeline().apply {
            setPipelineName("qwen semantic compression round trip")
            add(qwenPipe)
            enableTracing(traceConfig)
        }

        PipeTracer.enable()

        try
        {
            runBlocking(Dispatchers.IO)
            {
                pipeline.init(initPipes = true)
                val result = pipeline.execute(MultimodalContent(text = compressedPrompt))
                println("Original prompt length=${originalPrompt.length}")
                println("Compressed prompt length=${compressedPrompt.length}")
                println("Result text length=${result.text.length}")
                println("Result text preview=${result.text.take(1000)}")
                println("Sentence count: original=${countSentenceUnits(originalPrompt)} restored=${countSentenceUnits(result.text)}")
                println("Reconstruction similarity score: output=${reconstructionSimilarityScore(originalPrompt, result.text)} compressed=${reconstructionSimilarityScore(originalPrompt, compressedPrompt)}")

                val reasoningTrace = PipeTracer.exportTrace(pipeline.getTraceId(), TraceFormat.HTML)
                println("Trace length=${reasoningTrace.length}")

            }
        }
        finally
        {
            try
            {
                writeTraceArtifacts(
                    pipeline = pipeline,
                    qwenPipe = qwenPipe,
                    reasoningPipe = reasoningPipe,
                    pipelineTracePath = pipelineTracePath,
                    agentTracePath = agentTracePath,
                    reasoningTracePath = reasoningTracePath
                )
            }
            catch(traceError: Throwable)
            {
                println("Unable to save Qwen round-trip traces: ${traceError.message}")
                traceError.printStackTrace()
            }

            PipeTracer.disable()
            bedrockEnv.resetInferenceConfig()
            inferenceConfigPath.deleteIfExists()
        }
    }

    private fun writeTraceArtifacts(
        pipeline: Pipeline,
        qwenPipe: com.TTT.Pipe.Pipe,
        reasoningPipe: com.TTT.Pipe.Pipe,
        pipelineTracePath: String,
        agentTracePath: String,
        reasoningTracePath: String
    )
    {
        val pipelineTrace = PipeTracer.exportTrace(pipeline.getTraceId(), TraceFormat.HTML)
        val agentTrace = PipeTracer.exportTrace(resolveTraceId(qwenPipe), TraceFormat.HTML)
        val reasoningTrace = PipeTracer.exportTrace(resolveTraceId(reasoningPipe), TraceFormat.HTML)

        writeStringToFile(pipelineTracePath, pipelineTrace)
        writeStringToFile(agentTracePath, agentTrace)
        writeStringToFile(reasoningTracePath, reasoningTrace)
        println("Saved trace artifacts: pipeline=${pipelineTracePath}, agent=${agentTracePath}, reasoning=${reasoningTracePath}")
        println("Trace sizes: pipeline=${pipelineTrace.length}, agent=${agentTrace.length}, reasoning=${reasoningTrace.length}")
    }

    private fun resolveTraceId(pipe: com.TTT.Pipe.Pipe): String
    {
        val field = com.TTT.Pipe.Pipe::class.java.getDeclaredField("pipeId")
        field.isAccessible = true
        return field.get(pipe) as String
    }

    private fun buildRoundTripPromptFixture(): String
    {
        return """
            It was a hot day in late July when I first discussed the book with Ben Mendelson. I had arrived at 5:00 am to open up, as I was the dayshift manager (our firm ran 24 hours a day). Ben had come to the office before anyone else. He was pinstriped in dark blue. He threw his briefcase off to the side. It was mostly an ornament of his position (nobody brought their papers home, thanks to our ample storage space, so it was always empty). He smiled at me as he entered my office. “Got a pen?”
            	I tossed him a solid gold one from my desk (a gift to me from my late friend Joe). He caught it even as it traveled at 200 miles per hour (something which he was known to do). He sat down on the other side of the desk from me and began to write on my notepad. He wrote the date and time, Benign Skies, and then he wrote the word “lover,” and then he wrote the words “love,” and “hate.” He wrote these last two words each 42  times on the page, and next to each in accordance to his feelings he wrote the names of his parents, his siblings and grandparents, and finally the names of his friends. He ran out of space and began scribbling in the margins. I laughed, asking, “Need more paper?” He shook his head.
            	“I’m pretty sure I’ve got everyone.”
            	We sat down together on the couch in the lounge room. He handed me the book whose name he had written down and which he had brought in his briefcase. “Benign Skies?” He nodded. I cracked open the cover and started from the first page. I attempted to skim it, but found that even as I flipped the pages like slides of a flipbook, I couldn’t reach the end—it was an infinitely long book. That was the first oddity. 
            	Concluding that I couldn’t skim it, I resigned to read it normally. It was impossible to concentrate properly on what the author had written because he repeated himself frequently. He repeated every line three times, sometimes in the same sentence or the same page, and sometimes not. He repeated the same sentences and the same phrases. He repeated the same words. I thought it was strange he would repeat himself so much, at first assuming the author to be schizophrenic, only to realize that it was intentional. He repeated the same sentences and the same phrases, three times in different places in the book. He repeated the same words.
            	“...he was born in a small town in rural Maine. He grew up in a house with no electricity and no running water. His father was a drunk. His mother died giving birth to his youngest brother.” 
            	"Benjamin Mendelson was born in a small hospital in Portland, Maine on October 12th 1952….Ben was raised by his loving family until he left home at 17 years old….Ben spent most of his life living in poverty….Ben's father died young….Ben's mother died when she gave birth to his youngest child….Ben has always loved books….Ben loves dogs….Ben has two brothers named Larry and Richard….Ben loves two women named Lila and Jane….Ben has two children named Sam and Hannah….Ben has two cats named Mr. Whiskers and Spot…"
            	I closed the book to double check what I had seen on the front cover. The author was Gabe Anderson. “Weird,” I said, resuming. “Impossible,” said Ben. His voice sounded like it came from miles away. And I agreed: it was impossible. The author described himself as Benjamin Mendelson, but claimed to be Benjamin Mendelson’s father. He wrote that his father had died when he was born (“My father died when I was born,”), but also wrote that Ben’s father had died 29 years earlier, when Ben was 6 months old (“My father died when I was 6 months old.”). He wrote that he had two brothers, but Ben’s father had four. He wrote that his mother had died young, but Ben’s mother had lived until Ben was 45.
            	The worst part of all was that the author had written that he was a writer. That wasn’t true at all (obvious, given the quality of the writing). Ben was an accountant, and he worked at the same firm as me. I’d been working there for 8 years, and Ben joined the year after I graduated from college. 
            	I bookmarked my place. “Ben, this is ridiculous.” He pushed it back into my hands.
            	“Finish it tonight,” he said. “You’ll see.”
            	I stayed awake until four in the morning on Monday, to read the whole thing before I saw Ben again. When I finished, I felt nauseous. I couldn’t believe that anyone would do such a thing to his own father. After lunch, Ben and I returned to the couch in the lounge room. There was something sinister about this book, and we knew we needed to do something about it. But we also knew that we would be accused of being crazy if we tried to talk about it to anyone else at the firm. Of course they would: we had no evidence, and no idea even of what was wrong with the book, aside from the seemingly infinite length, and the fact that Ben, the author, could not remove from the cover the name of the man who had slaughtered his grandparents to replace it with his own. We needed a witness, someone else who had read the book and understood the horrifying properties. 
            	“That’s why I wrote the list,” said Ben, handing it to me. “We need to show it to these people.” He tapped on the left side of the page, where the list of “love” was written. I looked it over. “And the ones on the right? The ones you hate?” He looked at me. His eyes were grave.
            	“Whatever happens,” said Ben, “especially if something should happen to me, keep the book away from them. You can’t know what will happen if they find out this exists.”
            	Ben frowned. The weight of the world showed in the lines on his face as he said, “but, I still don’t know how to prove what we know.” 
            	I looked at him, and then I pointed at myself, and then I pointed at Ben, and then I pointed at the book. 
            	“I think the answer is obvious,” I said.
            	Ben nodded, and we both smiled.
        """.trimIndent()
    }

    private fun reconstructionSimilarityScore(reference: String, candidate: String): Double
    {
        val referenceTokens = tokenize(reference)
        val candidateTokens = tokenize(candidate)
        if(referenceTokens.isEmpty())
        {
            return 0.0
        }

        val dp = Array(referenceTokens.size + 1) { IntArray(candidateTokens.size + 1) }

        for(i in referenceTokens.indices)
        {
            for(j in candidateTokens.indices)
            {
                dp[i + 1][j + 1] = if(referenceTokens[i] == candidateTokens[j])
                {
                    dp[i][j] + 1
                }
                else
                {
                    maxOf(dp[i][j + 1], dp[i + 1][j])
                }
            }
        }

        return dp[referenceTokens.size][candidateTokens.size].toDouble() / referenceTokens.size.toDouble()
    }

    private fun tokenize(text: String): List<String>
    {
        return Regex("[A-Za-z0-9']+").findAll(text)
            .map { it.value.lowercase() }
            .toList()
    }

    private fun countSentenceUnits(text: String): Int
    {
        val trimmed = text.trim()
        if(trimmed.isBlank())
        {
            return 0
        }

        return Regex("""[^.!?]+(?:[.!?]+|$)""").findAll(trimmed).count()
    }
}
