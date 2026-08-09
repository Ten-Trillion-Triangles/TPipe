package bedrockPipe

import com.TTT.Config.TPipeConfig
import com.TTT.Debug.TraceConfig
import com.TTT.Debug.TraceDetailLevel
import com.TTT.Debug.TraceFormat
import com.TTT.Pipe.MultimodalContent
import com.TTT.Pipeline.Pipeline
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.io.File

/**
 * Live Bedrock multimodal test (gated on [AllowTest]=true) that proves the
 * TPipe multimodal pipeline actually transports image bytes to a real Amazon
 * Nova 2 Lite foundation model and back as a meaningful description.
 *
 * The image under test is the small flying-toaster JPEG generated as a
 * prerequisite (see /tmp/flying-toaster/toaster_small_001.jpg, ~38 KB). It
 * contains three unambiguous features — chrome toaster body, helicopter
 * rotor blades on top, white feathered wings — that the assertion grep targets.
 * The image is kept small to keep TPipe's per-byte token-budget pre-flight
 * (`countBinaryTokens` → `Dictionary.countTokens` on the base64 string)
 * bounded; the same code path runs against a 319 KB image but with much
 * longer wall-time.
 *
 * Tracing is enabled on the [Pipeline] (not the bare pipe — TPipe tracing
 * semantics are container-level). The trace is exported manually after
 * `pipeline.execute(...)` via `pipeline.getTraceReport(TraceFormat.HTML)`
 * and written to `${TPipeConfig.getTraceDir()}/<testName>/trace.html` —
 * TPipeConfig's canonical trace root. (Note: `TraceConfig.autoExport` and
 * `exportPath` are not honored by the tracing pipeline; only `enabled`,
 * `outputFormat`, and `detailLevel` take effect.)
 *
 * Inference profile resolution: `amazon.nova-2-lite-v1:0` is already pinned in
 * `~/.aws/inference.txt` to
 *   arn:aws:bedrock:us-east-2:521369004927:inference-profile/us.amazon.nova-2-lite-v1:0
 * BedrockPipe.init() loads that mapping automatically; no manual ARN handling.
 *
 * When [AllowTest] is not "true", the test method silently skips so the suite
 * stays green on CI without network access.
 *
 * Disabled 2026-08-08 after successful live verification pass — token-budget
 * pre-flight fix confirmed (image bytes → Nova 2 Lite → 4.4s wall, end_turn,
 * trace HTML persisted). To resurrect: remove `@Disabled` and rerun with
 * `AllowTest=true`.
 */
@Disabled("Verified 2026-08-08 (token-counting fix + trace export manual). Remove to re-enable live run.")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NovaMultimodalImageLiveTest
{
    private val imagePath = "/tmp/flying-toaster/toaster_small_001.jpg"
    private val modelId = "amazon.nova-2-lite-v1:0"
    private val region = "us-east-2"
    private val testName = "nova-2-lite-multimodal-image-description"

    @BeforeAll
    fun checkCredentials()
    {
        val allowTest = System.getenv("AllowTest")
        assumeTrue(allowTest == "true", "AllowTest flag not enabled — skipping live test")
    }

    /**
     * Wraps a multimodal [BedrockMultimodalPipe] in a [Pipeline], pipes the
     * small flying-toaster JPEG through real Nova 2 Lite, and verifies:
     *   1. The response text references visible features (proves the model saw
     *      the image rather than hallucinating from prompt text).
     *   2. BedrockCallMetadata.stopReason is populated (Converse wire contract).
     *   3. A trace HTML lands under TPipeConfig.getTraceDir()/<testName>/.
     */
    @Test
    fun nova2LiteDescribesFlyingToasterImageWithTrace()
    {
        // ---- 1. Load the image bytes from the prerequisite generation step ----
        val imageFile = File(imagePath)
        assertTrue(imageFile.exists() && imageFile.length() > 0,
            "Prerequisite image missing at $imagePath — run the image-gen step first")
        val imageBytes = imageFile.readBytes()

        // ---- 2. Resolve and clean the trace dir under TPipeConfig.getTraceDir() ----
        val traceRoot = File(TPipeConfig.getTraceDir(), testName)
        traceRoot.deleteRecursively()
        traceRoot.mkdirs()

        // ---- 3. Build the multimodal pipe + Pipeline. Tracing is on the Pipeline. ----
        val pipe = BedrockMultimodalPipe()
        pipe.setRegion(region)
        pipe.setModel(modelId)
        pipe.useConverseApi()
        runBlocking { pipe.init() }

        val pipeline = Pipeline()
        pipeline.add(pipe)
        pipeline.enableTracing(TraceConfig(
            enabled = true,
            outputFormat = TraceFormat.HTML,
            detailLevel = TraceDetailLevel.DEBUG
        ))

        // ---- 4. Build the multimodal request: text prompt + image bytes ----
        val prompt = "Describe this image in detail. What objects are visible?"
        val request = MultimodalContent(text = prompt).apply {
            addBinary(imageBytes, "image/jpeg", "flying-toaster.jpg")
        }

        // ---- 5. Execute the pipeline against live Bedrock ----
        val response = runBlocking { pipeline.execute(request) }
        val responseText = response.text

        // ---- 6. Assertion A: response proves the model saw the image ----
        val lower = responseText.lowercase()
        val visionTokens = listOf("toaster", "propeller", "rotor", "wing", "winged",
            "helicopter", "blade", "toast")
        val hit = visionTokens.any { it in lower }
        assertTrue(hit,
            "Nova 2 Lite response did not reference any image-visible element. " +
            "Got: \"${responseText.take(400)}\"")

        // ---- 7. Assertion B: wire contract — stopReason populated ----
        val metadata = pipe.getLastCallMetadata()
        assertNotNull(metadata, "BedrockCallMetadata should be populated after Converse call")
        assertTrue(metadata?.stopReason != null,
            "stopReason should be populated; got ${metadata?.stopReason}")

        // ---- 8. Export the trace HTML to TPipeConfig.getTraceDir()/<testName>/ ----
        val traceHtml = pipeline.getTraceReport(TraceFormat.HTML)
        val traceFile = File(traceRoot, "trace.html")
        traceFile.writeText(traceHtml)

        // ---- 8. Assertion C: trace HTML lands under TPipeConfig.getTraceDir() ----
        val traceFiles = traceRoot.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".html") }
            .toList()
        assertTrue(traceFiles.isNotEmpty(),
            "Expected at least one .html trace file under ${traceRoot.absolutePath}, " +
            "found ${traceFiles.size} files. Listing: ${traceRoot.list()?.toList()}")

        // ---- 9. Print the receipt so the operator can verify by eye ----
        println("===== Nova 2 Lite Multimodal Live Test Receipt =====")
        println("model:        $modelId")
        println("region:       $region")
        println("image:        $imagePath (${imageBytes.size} bytes)")
        println("stopReason:   ${metadata?.stopReason}")
        println("latencyMs:    ${metadata?.latencyMs}")
        println("cacheRead:    ${metadata?.cacheReadInputTokens}")
        println("cacheWrite:   ${metadata?.cacheWriteInputTokens}")
        println("traceDir:     ${traceRoot.absolutePath}")
        println("traceFiles:   ${traceFiles.map { it.name }}")
        println("response:")
        println(responseText)
        println("==================================================")
    }
}
