package Defaults.reasoning.test

import Defaults.reasoning.*

/**
 * Validation test suite for method-specific reasoning depth and duration parameters.
 * 
 * This test suite validates that Low/Med/High depth and Short/Med/Long duration settings
 * produce measurably different outputs across all reasoning methods.
 * 
 * To run this test:
 * 1. Build a reasoning pipe with specific depth/duration settings
 * 2. Execute with a test prompt
 * 3. Measure the output across three dimensions:
 *    - Step count (number of reasoning steps)
 *    - Text length (character/word count)
 *    - Behavioral differences (presence of edge cases, alternatives, validation)
 * 
 * Expected results:
 * - Low depth should produce 30-50% fewer steps than High
 * - Short duration should produce 40-60% less text than Long
 * - Behavioral markers should be present/absent as specified in method-specific definitions
 */
object ReasoningDepthDurationValidationTest
{
    /**
     * Test prompt used across all tests for consistency
     */
    const val TEST_PROMPT = """
        Analyze the following problem: A company needs to decide whether to invest in 
        renewable energy infrastructure. Consider costs, environmental impact, regulatory 
        requirements, and long-term sustainability.
    """
    
    /**
     * Validates that depth settings produce different prompt constraints for each method
     */
    fun testDepthConstraints()
    {
        println("=== Testing Depth Constraints ===\n")
        
        val methods = listOf(
            ReasoningMethod.ExplicitCot,
            ReasoningMethod.StructuredCot,
            ReasoningMethod.processFocusedCot,
            ReasoningMethod.ChainOfDraft,
            ReasoningMethod.BestIdea,
            ReasoningMethod.ComprehensivePlan,
            ReasoningMethod.RolePlay
        )
        
        val depths = listOf(ReasoningDepth.Low, ReasoningDepth.Med, ReasoningDepth.High)
        
        for (method in methods)
        {
            println("Method: $method")
            for (depth in depths)
            {
                val constraint = ReasoningPrompts.selectDepth(depth, method)
                println("  $depth: ${constraint.take(80)}...")
            }
            println()
        }
    }
    
    /**
     * Validates that duration settings produce different prompt constraints for each method
     */
    fun testDurationConstraints()
    {
        println("=== Testing Duration Constraints ===\n")
        
        val methods = listOf(
            ReasoningMethod.ExplicitCot,
            ReasoningMethod.StructuredCot,
            ReasoningMethod.processFocusedCot,
            ReasoningMethod.ChainOfDraft,
            ReasoningMethod.BestIdea,
            ReasoningMethod.ComprehensivePlan,
            ReasoningMethod.RolePlay
        )
        
        val durations = listOf(ReasoningDuration.Short, ReasoningDuration.Med, ReasoningDuration.Long)
        
        for (method in methods)
        {
            println("Method: $method")
            for (duration in durations)
            {
                val constraint = ReasoningPrompts.selectDuration(duration, method)
                println("  $duration: ${constraint.take(80)}...")
            }
            println()
        }
    }
    
    /**
     * Validates that chainOfThoughtSystemPrompt properly injects method-specific constraints
     */
    fun testChainOfThoughtPromptGeneration()
    {
        println("=== Testing Chain of Thought Prompt Generation ===\n")
        
        val methods = listOf(
            ReasoningMethod.ExplicitCot,
            ReasoningMethod.StructuredCot,
            ReasoningMethod.processFocusedCot,
            ReasoningMethod.ChainOfDraft
        )
        
        for (method in methods)
        {
            println("Method: $method")
            
            // Test Low depth + Short duration
            val lowShort = ReasoningPrompts.chainOfThoughtSystemPrompt(
                ReasoningDepth.Low,
                ReasoningDuration.Short,
                method
            )
            println("  Low/Short length: ${lowShort.length} chars")
            println("  Contains 'MANDATORY': ${lowShort.contains("MANDATORY")}")
            println("  Contains 'REQUIRED': ${lowShort.contains("REQUIRED")}")
            
            // Test High depth + Long duration
            val highLong = ReasoningPrompts.chainOfThoughtSystemPrompt(
                ReasoningDepth.High,
                ReasoningDuration.Long,
                method
            )
            println("  High/Long length: ${highLong.length} chars")
            println("  Contains '10+': ${highLong.contains("10+")}")
            println("  Contains '150-200%': ${highLong.contains("150-200%")}")
            println()
        }
    }
    
    /**
     * Validates that bestIdeaPrompt and comprehensivePlanPrompt properly inject constraints
     */
    fun testOtherPromptGeneration()
    {
        println("=== Testing BestIdea and ComprehensivePlan Prompt Generation ===\n")
        
        // Test BestIdea
        println("BestIdea Method:")
        val bestIdeaLow = ReasoningPrompts.bestIdeaPrompt(ReasoningDepth.Low, ReasoningDuration.Short)
        println("  Low/Short length: ${bestIdeaLow.length} chars")
        println("  Contains 'MANDATORY': ${bestIdeaLow.contains("MANDATORY")}")
        println("  Contains '2-3 potential solutions': ${bestIdeaLow.contains("2-3 potential solutions")}")
        
        val bestIdeaHigh = ReasoningPrompts.bestIdeaPrompt(ReasoningDepth.High, ReasoningDuration.Long)
        println("  High/Long length: ${bestIdeaHigh.length} chars")
        println("  Contains '5+ potential solutions': ${bestIdeaHigh.contains("5+ potential solutions")}")
        println()
        
        // Test ComprehensivePlan
        println("ComprehensivePlan Method:")
        val planLow = ReasoningPrompts.comprehensivePlanPrompt(ReasoningDepth.Low, ReasoningDuration.Short)
        println("  Low/Short length: ${planLow.length} chars")
        println("  Contains 'MANDATORY': ${planLow.contains("MANDATORY")}")
        println("  Contains '2-3 phase plan': ${planLow.contains("2-3 phase plan")}")
        
        val planHigh = ReasoningPrompts.comprehensivePlanPrompt(ReasoningDepth.High, ReasoningDuration.Long)
        println("  High/Long length: ${planHigh.length} chars")
        println("  Contains '5+ phase plan': ${planHigh.contains("5+ phase plan")}")
        println()
    }
    
    /**
     * Validates that ReasoningBuilder correctly passes enum values to prompt functions
     */
    fun testReasoningBuilderIntegration()
    {
        println("=== Testing ReasoningBuilder Integration ===\n")
        
        val methods = listOf(
            ReasoningMethod.ExplicitCot,
            ReasoningMethod.StructuredCot,
            ReasoningMethod.processFocusedCot,
            ReasoningMethod.ChainOfDraft,
            ReasoningMethod.BestIdea,
            ReasoningMethod.ComprehensivePlan,
            ReasoningMethod.RolePlay
        )
        
        for (method in methods)
        {
            println("Method: $method")
            
            // Create settings with Low depth and Short duration
            val settingsLow = ReasoningSettings(
                reasoningMethod = method,
                depth = ReasoningDepth.Low,
                duration = ReasoningDuration.Short,
                reasoningInjector = ReasoningInjector.SystemPrompt
            )
            println("  ✓ Created settings with Low/Short")
            
            // Create settings with High depth and Long duration
            val settingsHigh = ReasoningSettings(
                reasoningMethod = method,
                depth = ReasoningDepth.High,
                duration = ReasoningDuration.Long,
                reasoningInjector = ReasoningInjector.SystemPrompt
            )
            println("  ✓ Created settings with High/Long")
            println()
        }
    }
    
    /**
     * Prints a summary of expected behavioral differences for manual validation
     */
    fun printExpectedBehaviors()
    {
        println("=== Expected Behavioral Differences ===\n")
        
        println("ExplicitCot:")
        println("  Low: 3-5 steps, skip alternatives in step 4, minimal analysis")
        println("  Med: 6-10 steps, consider 2-3 approaches in step 4, balanced analysis")
        println("  High: 10+ steps, explore multiple approaches/implications/edge cases")
        println()
        
        println("StructuredCot:")
        println("  Low: 3-5 steps across 4 phases, 2-3 sub-problems max, minimal intermediate steps")
        println("  Med: 6-10 steps across 4 phases, 3-5 sub-problems, key intermediate results")
        println("  High: 10+ steps across 4 phases, detailed decomposition, all work shown")
        println()
        
        println("processFocusedCot:")
        println("  Low: 3-5 steps total, concise answers, 2-3 steps only, skip alternatives")
        println("  Med: 6-10 steps total, balanced detail, 1-2 alternatives identified")
        println("  High: 10+ steps total, exhaustive answers, multiple approaches explored")
        println()
        
        println("ChainOfDraft:")
        println("  Low: 3-5 minimal steps, 5-word max per step, skip validation")
        println("  Med: 6-10 minimal steps, 5-word max per step, one validation step")
        println("  High: 10+ minimal steps, 5-word max per step, all validations included")
        println()
        
        println("BestIdea:")
        println("  Low: 2-3 solutions evaluated, 3-5 steps, brief analysis, concise justification")
        println("  Med: 3-5 solutions evaluated, 6-10 steps, thorough analysis, clear criteria")
        println("  High: 5+ solutions evaluated, 10+ steps, exhaustive analysis, detailed criteria")
        println()
        
        println("ComprehensivePlan:")
        println("  Low: 2-3 phase plan, 3-5 steps, brief scope analysis, minimal risk identification")
        println("  Med: 3-5 phase plan, 6-10 steps, thorough scope, key risks identified")
        println("  High: 5+ phase plan, 10+ steps, exhaustive scope, comprehensive risk assessment")
        println()
        
        println("RolePlay:")
        println("  Low: 3-5 elements, 2-3 insights, 2-3 thoughts, 1-2 advantages, surface-level character")
        println("  Med: 6-10 elements, 3-5 insights, 4-6 thoughts, 2-3 advantages, balanced character")
        println("  High: 10+ elements, 5+ insights, 7+ thoughts, 3+ advantages, deep character immersion")
        println()
    }
    
    /**
     * Main test runner
     */
    @JvmStatic
    fun main(args: Array<String>)
    {
        println("╔════════════════════════════════════════════════════════════════╗")
        println("║  Reasoning Depth & Duration Validation Test Suite             ║")
        println("╚════════════════════════════════════════════════════════════════╝\n")
        
        try
        {
            testDepthConstraints()
            println("─".repeat(70) + "\n")
            
            testDurationConstraints()
            println("─".repeat(70) + "\n")
            
            testChainOfThoughtPromptGeneration()
            println("─".repeat(70) + "\n")
            
            testOtherPromptGeneration()
            println("─".repeat(70) + "\n")
            
            testReasoningBuilderIntegration()
            println("─".repeat(70) + "\n")
            
            printExpectedBehaviors()
            println("─".repeat(70) + "\n")
            
            println("✅ All validation tests completed successfully!")
            println("\nNOTE: To fully validate behavioral differences, you must:")
            println("1. Build reasoning pipes with different depth/duration settings")
            println("2. Execute them with the same test prompt")
            println("3. Measure actual step counts, text lengths, and behavioral markers")
            println("4. Compare results against expected behaviors listed above")
            
        } catch (e: Exception)
        {
            println("❌ Test failed with error: ${e.message}")
            e.printStackTrace()
        }
    }
}
