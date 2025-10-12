package bedrockPipe

import TestCredentialUtils
import com.TTT.Enums.ProviderName
import com.TTT.Pipe.MultimodalContent
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.assertThrows

class GptOssTest {

    private lateinit var pipe: BedrockPipe

    @BeforeEach
    fun setup() {
        // Configure inference profile before creating pipe
        env.bedrockEnv.loadInferenceConfig()
        env.bedrockEnv.bindInferenceProfile("openai.gpt-oss-20b-1:0", "openai.gpt-oss-20b-1:0")
        env.bedrockEnv.bindInferenceProfile("openai.gpt-oss-120b-1:0", "openai.gpt-oss-120b-1:0")

        pipe = BedrockPipe()
            .setProvider(ProviderName.Aws)
            .setModel("openai.gpt-oss-20b-1:0")
            .setSystemPrompt("You are a helpful assistant")
            .setTemperature(1.0)
            .setMaxTokens(4000) as BedrockPipe

        pipe.useConverseApi()

        pipe.setRegion("us-west-2")
    }

    @Test
    fun `test GPT-OSS basic text generation`() = runBlocking {
        pipe.init()

        val result = pipe.execute("Write a fictional story about novelist Ben Mendelson's career implosion")


        assertNotNull(result)
        assertTrue(result.isNotEmpty(), "Response should not be empty")
        assertTrue(result.length > 1, "Response should contain actual content")
    }

    @Test
    fun `test GPT-OSS with reasoning mode`() = runBlocking {
        pipe.setModel("openai.gpt-oss-120b-1:0")
            .setReasoning()
            .setMaxTokens(200)

        pipe.init()
        val result = pipe.execute("Write a fictional story about novelist Ben Mendelson's career implosion")

        assertNotNull(result)
        assertTrue(result.isNotEmpty())
    }

    @Test
    fun `test GPT-OSS with Converse API`() = runBlocking {
        pipe.useConverseApi()

        pipe.init()
        val result = pipe.execute("Hello, how are you?")

        assertNotNull(result)
        assertTrue(result.isNotEmpty())
    }

    @Test
    fun `test GPT-OSS region validation`() {
        pipe.setRegion("us-east-1")

        assertThrows<IllegalArgumentException> {
            runBlocking { pipe.init() }
        }
    }


}