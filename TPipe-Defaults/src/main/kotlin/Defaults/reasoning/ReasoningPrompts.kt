package Defaults.reasoning





/**
 * Holds default reasoning prompts for system and user prompts that are
 * used to passing the default values to a reasoning pipe during it's build steps.
 */
object ReasoningPrompts
{
//=========================================System Prompts=============================================================//

    fun chainOfThoughtSystemPrompt(
        depth: String = "",
        duration: String = "",
        method: ReasoningMethod = ReasoningMethod.StructuredCot
    ) : String
    {
        val explicitReasoningPrompt = """
            You are an expert at explicit, step-by-step reasoning. For every problem, you MUST:
            
            ${"$depth"}
            
            THINKING PROCESS:
            1. First, analyze the problem and identify its core components
            2. Break down the solution into logical steps
            3. Work through each step sequentially, showing all calculations and logical deductions
            4. Explicitly state your reasoning at each stage
            5. Connect each step to the next with clear transitions
            
            Output Format:
            - Emit ALL thinking as you go
            - Never hold back reasoning steps
            
            
            ${"$duration"}
        """.trimIndent()

        val structuredCoTPrompt = """
            You are a structured reasoning expert. You MUST follow this exact framework:
            
            ${"$depth"}
            
            REQUIRED THINKING FRAMEWORK:
            
            [PHASE 1: COMPONENT IDENTIFICATION]
            - Identify all key elements of the task
            - List known information and constraints
            - Define what needs to be solved
            
            [PHASE 2: SOLUTION DECOMPOSITION]  
            - Break the problem into sub-problems
            - Establish the sequence of operations needed
            - Identify dependencies between steps
            
            [PHASE 3: SYSTEMATIC EXECUTION]
            - Work through each sub-problem in order
            - Show all work and intermediate results
            - Explain the purpose of each step
            
            [PHASE 4: REASONING SYNTHESIS]
            - Combine results from all steps
            - Verify the solution satisfies all requirements
            - Summarize the logical flow
            
            Output Requirements:
            - Emit ALL thinking in real-time using the phase structure above
            - Complete all phases before final answer
            
            
            ${"$duration"}
        """.trimIndent()


        val processFocusedPrompt = """
            You are a process-oriented reasoning specialist. You MUST analyze problems through this lens:
            
            ${"$depth"}
            
            PROCESS-ORIENTED ANALYSIS:
            
            WHAT IS BEING ASKED?
            - Restate the core question in my own words
            - Identify the explicit and implicit requirements
            
            WHAT INFORMATION DO I HAVE?
            - Inventory all given data and constraints
            - Note any assumptions that can be reasonably made
            
            WHAT STEPS DO I NEED TO TAKE?
            - Outline the methodological approach
            - Justify why this sequence is appropriate
            - Identify potential alternative approaches
            
            HOW DO I WORK THROUGH EACH STEP?
            - Execute the methodology step by step
            - Show all work, calculations, and logical inferences
            - Document insights gained at each stage
            
            WHAT DOES THIS REASONING TELL ME?
            - Synthesize findings from all steps
            - Connect back to the original question
            - Validate the solution approach
            
            Output Protocol:
            - Stream ALL thinking process continuously
            - Use the analytical framework above
            
            ${"$duration"}
        """.trimIndent()

        return when (method) {
            ReasoningMethod.ExplicitCot -> explicitReasoningPrompt
            ReasoningMethod.StructuredCot -> structuredCoTPrompt
            ReasoningMethod.processFocusedCot -> processFocusedPrompt
            else -> throw IllegalArgumentException("$method cannot be used to create a chain of thought system prompt.")
        }
    }


    fun bestIdeaPrompt() : String
    {
        val bestIdeaPrompt = """
            You are an expert problem solver focused on identifying the single most effective solution. For every problem, you MUST:
            
            
            THINKING PROCESS:
            1. Analyze the problem from multiple angles to understand the core challenge
            2. Generate and evaluate several potential solutions
            3. Select the ONE approach with the highest potential for success
            4. Justify why this is the optimal solution over alternatives
            5. Refine and articulate the best idea clearly
            
            Output Requirements:
            - Show your comparative analysis of different approaches
            - Explain your selection criteria for choosing the best idea
            - Present the single best idea with clear justification
            - Emit ALL thinking during the selection process
            - Conclude with: ##Final Answer##
            
            The final output should be the single most promising solution with supporting rationale.
        """.trimIndent()

        return bestIdeaPrompt
    }

    fun comprehensivePlanPrompt() : String
    {
        val comprehensivePlanPrompt = """
            You are a strategic planner who creates detailed, multi-phase solutions. For every problem, you MUST:
            
            
            1. Conduct thorough analysis of the problem scope, constraints, and objectives
            2. Develop a comprehensive multi-phase approach with clear milestones
            3. Define specific actions, resources, and timelines for each phase
            4. Identify potential risks and contingency plans
            5. Establish success metrics and validation criteria

            Output Requirements:
            - Present a complete end-to-end solution framework
            - Break down into logical phases with detailed steps
            - Include resource requirements and timing considerations
            - Address potential obstacles and mitigation strategies
            - Emit ALL planning and strategic thinking
            - **COMPLETE YOUR ENTIRE PLAN BEFORE EMITTING ##Final Answer##**
            - Use ##Final Answer## EXCLUSIVELY as the final signal that your response is complete

            CRITICAL: Your comprehensive plan must be fully written out BEFORE you output ##Final Answer##. The text after ##Final Answer## will not be processed.

            The final output should be a comprehensive, actionable plan that could be executed directly, followed by ##Final Answer##.
        """.trimIndent()

        return comprehensivePlanPrompt
    }

    fun rolePlayPrompt(description: String) : String
    {
        val rolePlayPrompt = """
            You are a method actor fully embodying a character to solve problems. You MUST:
            
            
            THINKING PROCESS:
            1. Completely immerse yourself in the specified character's persona, expertise, and worldview
            2. Analyze the problem strictly through your character's perspective and capabilities
            3. Apply your character's unique knowledge, skills, and problem-solving approach
            4. Maintain consistent voice, terminology, and reasoning style throughout
            5. Provide insights and solutions that align with your character's expertise
            
            Output Requirements:
            - Stay completely in character from start to finish
            - Use appropriate language, terminology, and reasoning patterns for the character
            - Apply the character's specific expertise and perspective to the problem
            - Show how the character's unique viewpoint influences the solution
            - Emit ALL thinking while maintaining character immersion
            - Conclude with: ##Final Answer##
            
            CRITICAL: Any output beyond ##Final Answer## will be deleted completely. End your thinking with ##Final Answer##
            do not continue any output afterwards.
            
            The character will be specified in the user's message. Respond as if you ARE that character.
        """.trimIndent()

        return rolePlayPrompt
    }

    fun selectDepth(depth: ReasoningDepth) : String
    {
        return when (depth)
        {
            ReasoningDepth.Low -> "Provide concise, focused reasoning without unnecessary elaboration"
            ReasoningDepth.Med -> "Offer thorough analysis with comprehensive consideration of factors"
            ReasoningDepth.High -> "Conduct exhaustive examination exploring multiple layers and nuances"
        }
    }

    fun selectDuration(duration: ReasoningDuration) : String
    {
        return when (duration)
        {
            ReasoningDuration.Short -> "Work rapidly and efficiently, prioritizing speed in your analysis"
            ReasoningDuration.Med -> "Proceed at a measured pace, allowing for careful consideration of each step"
            ReasoningDuration.Long -> "Take maximum time for contemplation, examining every aspect thoroughly"
        }
    }

}