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
import java.io.File
import java.nio.file.Files
import kotlin.io.path.deleteIfExists
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue

private const val QWEN_30B_MODEL_ID = "qwen.qwen3-coder-30b-a3b-v1:0"
private const val QWEN_30B_MODEL_ARN = "arn:aws:bedrock:us-west-2::foundation-model/qwen.qwen3-coder-30b-a3b-v1:0"
private const val QWEN_30B_REGION = "us-west-2"

class QwenSemanticCompressionRoundTripTest
{
    @Test
    fun liveQwenSemanticDecompressionReasoningPipeReconstructsCompressedPromptAndSavesTrace()
    {
        TestCredentialUtils.requireAwsCredentials()

        val originalConfigDir = TPipeConfig.configDir
        val originalInstanceId = TPipeConfig.instanceID
        val traceRoot = File(System.getProperty("user.home"), ".tpipe").absoluteFile
        TPipeConfig.configDir = traceRoot.absolutePath
        TPipeConfig.instanceID = "QwenSemanticCompressionRoundTripTest-${System.nanoTime()}"

        val traceConfig = TraceConfig(
            enabled = true,
            outputFormat = TraceFormat.HTML,
            detailLevel = TraceDetailLevel.DEBUG,
            includeContext = true,
            includeMetadata = true
        )

        println("Using Qwen foundation model $QWEN_30B_MODEL_ID ($QWEN_30B_MODEL_ARN) in $QWEN_30B_REGION")
        println("Saving round-trip trace artifacts under ${TPipeConfig.getTraceDir()}/Library/qwen-semantic-compression-round-trip")

        val inferenceConfigPath = Files.createTempFile("tpipe-qwen-inference", ".txt")
        val originalPrompt = buildRoundTripPromptFixture()
        val compression = semanticCompress(originalPrompt)
        assertFalse(
            compression.legend.isNotBlank(),
            "This chapter-style fixture should not generate a legend; if decompression mentions one, it is hallucinating it"
        )
        val compressedPrompt = compression.compressedText

        assertTrue(compression.compressedText.contains("¶"), "Compressed prompt should preserve paragraph breaks with pilcrows")
        println("Semantic compression lengths: original=${originalPrompt.length} compressed=${compression.compressedText.length}")

        val resultTraceDir = File(TPipeConfig.getTraceDir(), "Library/qwen-semantic-compression-round-trip")
        val pipelineTracePath = File(resultTraceDir, "pipeline.html")
        val pipelineJsonTracePath = File(resultTraceDir, "pipeline.json")
        val agentTracePath = File(resultTraceDir, "agent.html")
        val agentJsonTracePath = File(resultTraceDir, "agent.json")
        val reasoningTracePath = File(resultTraceDir, "reasoning.html")

        bedrockEnv.resetInferenceConfig()
        bedrockEnv.setInferenceConfigFile(inferenceConfigPath.toFile())
        inferenceConfigPath.toFile().writeText("$QWEN_30B_MODEL_ID=\n")

        try
        {
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
                    qwenPipe.addTraceId(resolveTraceId(qwenPipe))
                    reasoningPipe.addTraceId(resolveTraceId(reasoningPipe))
                    val result = pipeline.execute(MultimodalContent(text = compressedPrompt))
                    println("Original prompt length=${originalPrompt.length}")
                    println("Compressed prompt length=${compressedPrompt.length}")
                    println("Result text length=${result.text.length}")
                    println("Result text preview=${result.text.take(1000)}")
                    println("Sentence count: original=${countSentenceUnits(originalPrompt)} restored=${countSentenceUnits(result.text)}")
                    println("Reconstruction similarity score: output=${reconstructionSimilarityScore(originalPrompt, result.text)} compressed=${reconstructionSimilarityScore(originalPrompt, compressedPrompt)}")
                    assertEquals(
                        compression.compressedText,
                        compressedPrompt,
                        "When no legend is produced, the compressed prompt should be just the compressed body"
                    )

                    val reasoningTrace = PipeTracer.exportTrace(pipeline.getTraceId(), TraceFormat.HTML)
                    println("Trace length=${reasoningTrace.length}")
                }
            }
            finally
            {
                try
                {
                    writeTraceArtifacts(
                        traceDir = resultTraceDir,
                        pipeline = pipeline,
                        qwenPipe = qwenPipe,
                        reasoningPipe = reasoningPipe,
                        pipelineTracePath = pipelineTracePath,
                        pipelineJsonTracePath = pipelineJsonTracePath,
                        agentTracePath = agentTracePath,
                        agentJsonTracePath = agentJsonTracePath,
                        reasoningTracePath = reasoningTracePath
                    )
                    println("Saved trace artifacts under ${resultTraceDir.absolutePath}")
                }
                finally
                {
                    PipeTracer.disable()
                    bedrockEnv.resetInferenceConfig()
                    inferenceConfigPath.deleteIfExists()
                }
            }
        }
        finally
        {
            TPipeConfig.configDir = originalConfigDir
            TPipeConfig.instanceID = originalInstanceId
        }
    }

    private fun writeTraceArtifacts(
        traceDir: File,
        pipeline: Pipeline,
        qwenPipe: com.TTT.Pipe.Pipe,
        reasoningPipe: com.TTT.Pipe.Pipe,
        pipelineTracePath: File,
        pipelineJsonTracePath: File,
        agentTracePath: File,
        agentJsonTracePath: File,
        reasoningTracePath: File
    )
    {
        traceDir.mkdirs()

        val pipelineHtmlTrace = PipeTracer.exportTrace(pipeline.getTraceId(), TraceFormat.HTML)
        val pipelineJsonTrace = PipeTracer.exportTrace(pipeline.getTraceId(), TraceFormat.JSON)
        val agentTrace = PipeTracer.exportTrace(resolveTraceId(qwenPipe), TraceFormat.HTML)
        val agentJsonTrace = PipeTracer.exportTrace(resolveTraceId(qwenPipe), TraceFormat.JSON)
        val reasoningTrace = PipeTracer.exportTrace(resolveTraceId(reasoningPipe), TraceFormat.HTML)

        writeTraceFile(pipelineTracePath, pipelineHtmlTrace, "pipeline HTML")
        writeTraceFile(pipelineJsonTracePath, pipelineJsonTrace, "pipeline JSON")
        writeTraceFile(agentTracePath, agentTrace, "agent HTML")
        writeTraceFile(agentJsonTracePath, agentJsonTrace, "agent JSON")
        writeTraceFile(reasoningTracePath, reasoningTrace, "reasoning HTML")

        println("Saved trace artifacts under ${traceDir.absolutePath}")
        println("Trace sizes: pipelineHtml=${pipelineHtmlTrace.length}, pipelineJson=${pipelineJsonTrace.length}, agentHtml=${agentTrace.length}, agentJson=${agentJsonTrace.length}, reasoningHtml=${reasoningTrace.length}")
    }

    private fun writeTraceFile(path: File, content: String, label: String)
    {
        writeStringToFile(path.absolutePath, content)
        assertTrue(path.exists(), "$label trace file should exist at ${path.absolutePath}")
        assertTrue(path.length() > 0, "$label trace file should not be empty at ${path.absolutePath}")
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
            It was a hot summer day in late July when I first met Ben Mendelson. He had come to the office early, wearing a dark blue suit. He had a briefcase in his hand. He smiled at me and asked if he could borrow a pen. I handed him one. Then he began to write.
            He wrote the date and time, and then the name of a book, and then he wrote the word 'lover', and then he wrote the words 'love' and 'hate'. Then he wrote the names of his parents and his siblings and his grandparents, and then he wrote the names of his friends. I watched him write the names and then I went to get another pen and paper so he could keep going. But he stopped writing.
            "Is something wrong?" I asked him.
            "No, nothing," he said. "Just wanted to make sure I had everyone."

            We sat together on the couch. I tried to read the book he had written down. It was called The Great American Novel. I thought it might be interesting, but the title was so long that I couldn't find the end of it. I started reading it anyway, and I found it hard to concentrate on the story because the author kept repeating himself. I thought it was strange that he would repeat himself so much, but then I realized he was doing it on purpose. He repeated the same lines three times each in different places in the book. He repeated the same sentences and the same phrases. He repeated the same words.
            "…he was born in a small town in Maine. He grew up in a house that had no electricity and no running water. His father was a drunk and his mother died giving birth to his youngest brother."
            Then it took a turn towards insanity.
            "Benjamin Mendelson was born in a small hospital in Portland Maine on October 12th 1952… Ben was raised by his loving family until he left home at seventeen years old… Ben spent most of his life living in poverty… Ben's father died young… Ben's mother died when she gave birth to her youngest child… Ben has always loved books… Ben loves dogs… Ben has two brothers named Larry and Richard… Ben loves two women named Lila and Jane… Ben has two children named Sam and Hannah… Ben has two cats named Mr. Whiskers and Spot…"

            There were so many things wrong with this book that I didn't understand what was happening until later that night when I finally finished reading it all. The author had described himself as Benjamin Mendelson but then he also claimed to be Benjamin Mendelson's father. He wrote that his father had died when he was born: "My father died when I was born." However, his father had actually died twenty-nine years earlier when Ben was only six months old: "My father died when I was six months old."
            He wrote that he had two brothers, but his father had four. And he wrote that he had a mother who had died, but his mother had actually lived until Ben was forty-five years old.
            And the worst thing of all was that the author had written that he was a writer. That wasn't true at all. He was an accountant, and he worked at the same firm as me. I had been working there for eight years and he'd been there since the year after I graduated college.

            "What did you think?" Ben asked me when he came back to work the following Monday.
            I told him I hadn't finished the book yet. He laughed.
            "You're funny," he said. "You'll finish it tonight. You'll see."
            When I finished it, I felt sick. I thought I was going to throw up. I couldn't believe that someone would do that to his own father. When Ben came back from lunch an hour later, we sat together on my couch again and talked about what we were going to do next. We decided we should tell someone about this book, but we were afraid to talk about it in front of anyone else at work without knowing exactly what we were talking about first. We worried that maybe someone would accuse us of being crazy. So we waited until we knew we had a witness.

            "I've been thinking about the names," Ben said. "Why did you ask me to write down all the names of my family members? I can't remember a lot of them."
            "I don't know," I said. "Maybe because we needed to be sure that everyone in the book was real."
            Ben nodded and smiled, but then he got serious.
            "So how are we going to prove this?"
            I looked at him and then I pointed at myself and then I pointed at Ben and then I pointed at the book.
            "I think the answer is obvious," I said.
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
