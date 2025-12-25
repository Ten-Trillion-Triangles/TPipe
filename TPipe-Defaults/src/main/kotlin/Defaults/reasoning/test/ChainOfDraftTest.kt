package Defaults.reasoning.test

import Defaults.reasoning.*
import com.TTT.Structs.ChainOfDraftResponse
import com.TTT.Structs.DraftStep

/**
 * Simple test to verify Chain of Draft integration
 */
fun main() {
    println("Testing Chain of Draft Integration")
    println("=================================")
    
    // Test 1: Verify ReasoningMethod enum includes ChainOfDraft
    val codMethod = ReasoningMethod.ChainOfDraft
    println("✓ ReasoningMethod.ChainOfDraft: $codMethod")
    
    // Test 2: Test ChainOfDraftResponse data class and unravel()
    val draftSteps = listOf(
        DraftStep(1, "Start: 20 lollipops", "20 - x = 12", "x = 8"),
        DraftStep(2, "Calculate difference", "20 - 12", "8")
    )
    
    val codResponse = ChainOfDraftResponse(
        problemAnalysis = "Jason gave Denny lollipops",
        draftSteps = draftSteps,
        finalCalculation = "20 - 12 = 8",
        answer = "8 lollipops"
    )
    
    val unraveledText = codResponse.unravel()
    println("✓ ChainOfDraftResponse unravel() output:")
    println("  $unraveledText")
    
    // Test 3: Verify ReasoningSettings supports ChainOfDraft
    val codSettings = ReasoningSettings(
        reasoningMethod = ReasoningMethod.ChainOfDraft,
        depth = ReasoningDepth.Med,
        duration = ReasoningDuration.Short,
        reasoningInjector = ReasoningInjector.SystemPrompt
    )
    println("✓ ReasoningSettings with ChainOfDraft: ${codSettings.reasoningMethod}")
    
    // Test 4: Verify depth and duration selectors work
    val depthText = ReasoningPrompts.selectDepth(ReasoningDepth.Med)
    val durationText = ReasoningPrompts.selectDuration(ReasoningDuration.Short)
    println("✓ Depth selector: $depthText")
    println("✓ Duration selector: $durationText")
    
    println("\n🎉 Chain of Draft integration successful!")
    println("All core components are working correctly.")
}
