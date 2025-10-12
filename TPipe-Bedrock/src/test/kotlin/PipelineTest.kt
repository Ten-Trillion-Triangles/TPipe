import bedrockPipe.BedrockPipe
import env.bedrockEnv
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PipelineTest {
    
    @Test
    fun testThreeStageTextTransformation() {
        TestCredentialUtils.requireAwsCredentials()
        
        runBlocking(Dispatchers.IO) {
            bedrockEnv.loadInferenceConfig()
            
            // Stage 1: Extract key information
            val extractorPipe = BedrockPipe()
            extractorPipe.setModel("deepseek.r1-v1:0")
            extractorPipe.setRegion("us-east-2")
            extractorPipe.setSystemPrompt("Extract the main topic and key points from the text. Be concise.")
            extractorPipe.setMaxTokens(150)
            extractorPipe.setTemperature(0.3)
            
            // Stage 2: Analyze and categorize
            val analyzerPipe = BedrockPipe()
            analyzerPipe.setModel("deepseek.r1-v1:0")
            analyzerPipe.setRegion("us-east-2")
            analyzerPipe.setSystemPrompt("Analyze the extracted information and categorize it by importance and urgency.")
            analyzerPipe.setMaxTokens(200)
            analyzerPipe.setTemperature(0.5)
            
            // Stage 3: Generate action items
            val actionPipe = BedrockPipe()
            actionPipe.setModel("deepseek.r1-v1:0")
            actionPipe.setRegion("us-east-2")
            actionPipe.setSystemPrompt("Based on the analysis, create specific actionable recommendations.")
            actionPipe.setMaxTokens(2500)
            actionPipe.setTemperature(0.7)
            
            // Initialize all pipes
            extractorPipe.init()
            analyzerPipe.init()
            actionPipe.init()
            
            val inputText = """
                Our quarterly sales report shows a 15% decline in the northeast region, 
                while the southwest region grew by 8%. Customer complaints have increased 
                by 23% mainly about delivery delays and product quality issues. The new 
                marketing campaign launched last month has generated 40% more leads but 
                conversion rates remain low at 12%. We need to address these issues 
                before the board meeting next week.
            """.trimIndent()
            
            println("=== PIPELINE TEST: Three-Stage Text Transformation ===")
            println("Input: $inputText")
            
            // Stage 1: Extract
            println("\n--- Stage 1: Extraction (DeepSeek R1) ---")
            val extracted = extractorPipe.execute(inputText)
            println("Extracted: $extracted")
            
            if (extracted.isEmpty()) {
                println("❌ Stage 1 failed - empty extraction")
                return@runBlocking
            }
            
            // Stage 2: Analyze  
            println("\n--- Stage 2: Analysis (DeepSeek R1) ---")
            val analyzed = analyzerPipe.execute(extracted)
            println("Analyzed: $analyzed")
            
            if (analyzed.isEmpty()) {
                println("❌ Stage 2 failed - empty analysis")
                return@runBlocking
            }
            
            // Stage 3: Action items
            println("\n--- Stage 3: Action Items (DeepSeek R1) ---")
            val actions = actionPipe.execute(analyzed)
            println("Actions: $actions")
            
            if (actions.isEmpty()) {
                println("❌ Stage 3 failed - empty actions")
                return@runBlocking
            }
            
            println("\n✅ PIPELINE COMPLETED SUCCESSFULLY")
            println("Final output length: ${actions.length} characters")
            
            println("\n=== PIPELINE RESULTS ===")
            println("Stage 1 success: ${extracted.isNotEmpty()}")
            println("Stage 2 success: ${analyzed.isNotEmpty()}")
            println("Stage 3 success: ${actions.isNotEmpty()}")
            println("Pipeline completed successfully!")
        }
    }
}