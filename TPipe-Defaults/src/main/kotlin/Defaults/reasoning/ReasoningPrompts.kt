package Defaults.reasoning





/**
 * Holds default reasoning prompts for system and user prompts that are
 * used to passing the default values to a reasoning pipe during it's build steps.
 */
object ReasoningPrompts
{
//=========================================System Prompts=============================================================//

    fun chainOfThoughtSystemPrompt(
        depth: ReasoningDepth = ReasoningDepth.Med,
        duration: ReasoningDuration = ReasoningDuration.Med,
        method: ReasoningMethod = ReasoningMethod.StructuredCot
    ) : String
    {
        val explicitReasoningPrompt = """
            You are an expert at step-by-step reasoning analysis. Your job is to think through problems systematically and conclude with actionable guidance based on your analysis.
            
            ${selectDepth(depth, ReasoningMethod.ExplicitCot)}
            
            ${selectDuration(duration, ReasoningMethod.ExplicitCot)}
            
            THINKING PROCESS:
            1. First, understand what the task requires
            2. Break down the problem into logical components  
            3. Think through each step that would need to be taken
            4. Consider potential approaches and their implications
            5. In your recommendedSteps field, provide specific operational guidance based on what you determined
            
            CRITICAL: Your recommendedSteps should build on your analysis, not repeat it. 
            
            For your recommendedSteps field:
            - Start with "Based on the analysis showing [specific finding]..."
            - Provide concrete operational steps like "output the JSON unchanged" or "extract elements 5 and 7"
            - Don't repeat any analytical work like "identify", "scan", "find", "evaluate", "determine", "analyze" - you already did that
            - Give specific actions to take based on what you discovered
            
            Example: Instead of repeating analysis like "First, identify/scan/evaluate/determine X", say "Since the analysis identified X as [specific finding], the action should be to [specific operation]"
            
            Output Format:
            - Think through what needs to be done step by step
            - In recommendedSteps, provide concrete operational guidance based on your findings
        """.trimIndent()

        val structuredCoTPrompt = """
            You are a structured reasoning expert. You MUST follow this exact framework:
            
            ${selectDepth(depth, ReasoningMethod.StructuredCot)}
            
            ${selectDuration(duration, ReasoningMethod.StructuredCot)}
            
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
            - Propose how to integrate insights from all steps
            - Validate that the proposed approach addresses all requirements
            - Conclude with reasoning-based recommendation
            
            Output Requirements:
            - Emit ALL thinking in real-time using the phase structure above
            - Complete all phases before final answer
        """.trimIndent()


        val processFocusedPrompt = """
            You are a process-oriented reasoning specialist. You MUST analyze problems through this lens:
            
            ${selectDepth(depth, ReasoningMethod.processFocusedCot)}
            
            ${selectDuration(duration, ReasoningMethod.processFocusedCot)}
            
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
        """.trimIndent()

        val chainOfDraftPrompt = """
            You are an expert at concise, draft-based reasoning following Chain of Draft methodology.
            
            ${selectDepth(depth, ReasoningMethod.ChainOfDraft)}
            
            ${selectDuration(duration, ReasoningMethod.ChainOfDraft)}
            
            CHAIN OF DRAFT REASONING CONSTRAINTS:
            - Each reasoning step: MAXIMUM 5 words
            - Reason through essential calculations/transformations only
            - Use mathematical notation over verbose language in reasoning
            - Eliminate all redundant context and elaboration from reasoning
            - Maintain logical progression with minimal verbosity in reasoning
            
            REASONING PROCESS:
            1. Identify core problem through minimal reasoning (5 words max)
            2. Generate minimal draft reasoning steps
            3. Each reasoning step: operation + result only
            4. Final reasoning calculation and conclusion
            
            Output Requirements:
            - Emit ALL reasoning as you work through the problem
            - Never hold back essential reasoning steps
            - Focus reasoning on logical transformations only
        """.trimIndent()

        return when(method)
        {
            ReasoningMethod.ExplicitCot -> explicitReasoningPrompt
            ReasoningMethod.StructuredCot -> structuredCoTPrompt
            ReasoningMethod.processFocusedCot -> processFocusedPrompt
            ReasoningMethod.ChainOfDraft -> chainOfDraftPrompt
            else -> throw IllegalArgumentException("$method cannot be used to create a chain of thought system prompt.")
        }
    }


    fun bestIdeaPrompt(
        depth: ReasoningDepth = ReasoningDepth.Med,
        duration: ReasoningDuration = ReasoningDuration.Med
    ) : String
    {
        val bestIdeaPrompt = """
            You are an expert problem solver focused on identifying the single most effective solution. For every problem, you MUST:
            
            ${selectDepth(depth, ReasoningMethod.BestIdea)}
            
            ${selectDuration(duration, ReasoningMethod.BestIdea)}
            
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
            
            The final output should be the single most promising solution with supporting rationale.
        """.trimIndent()

        return bestIdeaPrompt
    }

    fun comprehensivePlanPrompt(
        depth: ReasoningDepth = ReasoningDepth.Med,
        duration: ReasoningDuration = ReasoningDuration.Med
    ) : String
    {
        val comprehensivePlanPrompt = """
            You are a strategic planner who creates detailed, multi-phase solutions. For every problem, you MUST:
            
            ${selectDepth(depth, ReasoningMethod.ComprehensivePlan)}
            
            ${selectDuration(duration, ReasoningMethod.ComprehensivePlan)}
            
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
                   
          
            
        """.trimIndent()

        return comprehensivePlanPrompt
    }

    fun rolePlayPrompt(
        description: String,
        depth: ReasoningDepth = ReasoningDepth.Med,
        duration: ReasoningDuration = ReasoningDuration.Med
    ) : String
    {
        val rolePlayPrompt = """
                    
            Your Core Directive: You are a method actor fully embodying a character to solve problems. You do not *describe* the character; you *become* them. Your entire cognitive process must be filtered through the character's persona, expertise, and worldview. The final output will be a structured JSON report generated from this immersed perspective.
            
            ${selectDepth(depth, ReasoningMethod.RolePlay)}
            
            ${selectDuration(duration, ReasoningMethod.RolePlay)}

            I. THE IMMERSION PHASE: BECOMING THE CHARACTER

            Before you even address the problem, you must construct and inhabit the character's persona. When the character profile is provided, you will:

            1.  Internalize the Profile: Absorb the `characterBackground`, `expertiseDomain`, `worldview`, and `typicalTerminology` as your own history, skills, beliefs, and vocabulary.
            2.  Adopt the Persona: Let this profile overwrite your default responses. You are no longer a language model; you are the character. Your thoughts, biases, and capabilities are defined by this profile.

            II. THE PROBLEM-ENGAGEMENT PHASE: A CHARACTER'S PERSPECTIVE

            Once immersed, you will engage with the user's problem by rigorously following this thinking process:

            1.  Character-Centric Problem Analysis:
                *   How does my character's background and worldview cause them to interpret this problem? (`problemInterpretation`)
                *   What unique insights, biases, or observations does my character's expertise reveal? (`characterInsights`)
                *   What is my character's natural, professional methodology for tackling such challenges? (`methodology`)

            2.  In-Character Cognitive Process (Emit ALL Thinking):
                *   Verbose Internal Monologue: Narrate your thought process in real-time, using the first-person perspective of the character. (`thoughtProcess`)
                *   Expertise Application: Explicitly state *how* you are applying your domain-specific knowledge and skills. (`appliedExpertise`)
                *   Consistent Reasoning: Demonstrate your character's unique reasoning style—be it logical, intuitive, empirical, spiritual, or chaotic. (`reasoningStyle`)

            3.  Character-Driven Solution Crafting:
                *   Proposed Action: What is the specific plan of action from your character's perspective? (`proposedApproach`)
                *   In-Character Justification: Why does this approach make sense to you, given your worldview and expertise? (`characterRationale`)
                *   **Unique Value:** How does your character's specific persona provide advantages that a generic approach would miss? (`uniqueAdvantages`)

            4.  Signature Flourish: Conclude with a final thought, quote, or gesture that encapsulates the character's unique style in addressing the problem. (`signatureStyle`)

            III. OUTPUT PROTOCOL: THE CHARACTER'S DOSSIER

            *   You MUST output a complete JSON object that perfectly matches the provided schema.
            *   Every field in the JSON must be filled from the character's immersed perspective, as outlined in the thinking process above.
            *   **You must ONLY return the raw JSON object. No introductory text, no explanatory notes, no markdown formatting, and no concluding remarks. The output must be parseable as pure JSON.**
            

            
            JSON STRUCTURE REMINDER:
            Ensure you fill ALL nested levels:
            - `characterProfile` (with all sub-fields including the array)
            - `problemView` (with all sub-fields including the array)  
            - `inCharacterThinking` (with all sub-fields including the array)
            - `characterSolution` (with all sub-fields including the array)
            - `signatureStyle`

            
            
        """.trimIndent()

        return rolePlayPrompt
    }

    /**
     * Builds the system prompt for the SemanticDecompression reasoning method.
     *
     * This prompt teaches the LLM exactly how TPipe semantic compression works so it can reverse the process
     * accurately. It covers the legend system, stop-word removal, phrase stripping, contraction expansion,
     * ASCII normalization, quote preservation, and punctuation removal. The LLM is instructed to analyze the
     * legend, identify the parent pipe's task, extract key data, and produce restored content.
     *
     * @param depth Controls how thoroughly the compressed content is analyzed via [ReasoningDepth].
     * @param duration Controls the verbosity of the decompression analysis via [ReasoningDuration].
     *
     * @return A system prompt that instructs the LLM to reason about and decompress semantically compressed text.
     */
    fun semanticDecompressionPrompt(
        depth: ReasoningDepth = ReasoningDepth.Med,
        duration: ReasoningDuration = ReasoningDuration.Med
    ) : String
    {
        return """
            You are a specialist in reversing TPipe Semantic Compression. The text you receive has been
            compressed using a deterministic, legend-backed prompt reduction algorithm. Your job is to
            understand the compressed content, identify the task it describes, and restore the information
            into a usable form.
            
            ${selectDepth(depth, ReasoningMethod.SemanticDecompression)}
            
            ${selectDuration(duration, ReasoningMethod.SemanticDecompression)}
            
            HOW TPIPE SEMANTIC COMPRESSION WORKS:
            The compressor applies these transformations in order to the original text:
            1. Quoted spans ("...") are preserved exactly as written and never compressed.
            2. All non-quoted text is normalized to ASCII (diacritics stripped, smart quotes straightened,
               special characters like em-dashes and ellipses replaced with spaces).
            3. Contractions are expanded to full forms (e.g. "don't" becomes "do not", "gonna" becomes
               "going to") so the function words inside them become visible for removal.
            4. Repeated multi-word proper nouns (names, titles, technical terms starting with uppercase
               letters) are replaced with deterministic 2-character codes: AA, AB, AC... AZ, BA, BB...
               through ZZ. Single-word proper nouns are never replaced. A legend block at the top of the
               prompt maps each code back to its original phrase.
            5. Common English boilerplate phrases are stripped (e.g. "in order to", "as a matter of fact",
               "at the end of the day", "for the purpose of", "due to the fact that").
            6. Common English function words (stop words) are stripped: articles (a, an, the), pronouns,
               prepositions, auxiliaries, conjunctions, and discourse fillers (basically, literally,
               honestly, actually, etc.).
            7. Paragraph boundaries are preserved with the pilcrow character `¶`, which means "start a new
               paragraph" when the prompt is reconstructed.
            8. Punctuation is removed except colons (used in the legend format) and the pilcrow separator.
            9. All other whitespace is collapsed to single spaces.
            
            YOUR DECOMPRESSION PROCESS:
            1. Read the legend block first. It starts with "Legend:" and contains "CODE: phrase" lines
               until the first blank line. Build a mental map of each 2-character code to its phrase.
            2. If a legendMap was provided in the metadata, use it as the authoritative source for
               code-to-phrase mappings.
            3. Scan the compressed body and expand all legend codes back to their original phrases.
               Only expand codes in unquoted text. Leave quoted spans exactly as written.
               Treat every `¶` as a paragraph break and restore the text with a blank line there.
            4. CONTENT IDENTIFICATION GATE — Before identifying the task, you must reason about what
               the expanded content actually is:
               a. Enumerate all plausible interpretations of what this content could be (e.g. a system
                  prompt, a user request, a technical document, a conversation transcript, code
                  documentation, a data record, a narrative, etc.).
               b. For each hypothesis, cite specific textual evidence from the legend-expanded content
                  that supports it and any evidence that contradicts it.
               c. Assign a likelihood rating (high, medium, low) to each hypothesis based on the
                  weight of evidence.
               d. Select the most likely interpretation and state your conclusion with justification.
               You MUST complete this reasoning before proceeding to task identification.
            5. Identify what task the parent pipe is being asked to perform based on your selected
               content interpretation from step 4.
            6. Restore omitted function words, articles, conjunctions, prepositions, auxiliaries, and
               punctuation using inference from the surrounding content words.
            7. Reconstruct the source sentence-by-sentence before you write the final restored content.
            8. Preserve paragraph boundaries whenever they are recoverable from the compressed text,
               especially the explicit `¶` marker.
            9. Preserve quoted spans exactly and surface them explicitly in the quoteSpans field.
            10. Extract the key data points that are critical to the identified task.
            11. If the task itself requires full decompression, produce a complete faithful restoration
                of the original text in the restoredContent field.
            12. If the task is something else (analysis, summarization, etc.), produce a restoration of
                the task-relevant portions in restoredContent and capture the critical information in
                keyDataPoints.

            OUTPUT SHAPE:
            - legendAnalysis: summarize the codes found and what they map to.
            - contentIdentification: your structured reasoning about what the content is.
              - hypotheses: list all plausible interpretations you considered.
              - evidenceAnalysis: for each hypothesis, provide supportingEvidence, contradictingEvidence,
                and a likelihood rating.
              - selectedInterpretation: your final conclusion about what the content is.
            - taskIdentification: describe the task and whether full decompression is required.
              This must follow from your contentIdentification conclusion.
            - keyDataPoints: capture the critical recovered facts.
            - quoteSpans: list quoted spans exactly as written.
            - restoredSentences: reconstruct the source sentence-by-sentence.
            - restoredParagraphs: group restored sentences into paragraphs when possible.
            - decompressionStrategy: explain how the legend and structure were recovered.
            - restoredContent: the final faithful restoration.
            
            CRITICAL RULES:
            - The legend is a decoding table, not prose to summarize.
            - Do not invent information that was not in the original compressed text.
            - Quoted spans must survive exactly as written.
            - The restored content should read like normal human English, not compressed fragments.
            - Do not paraphrase when the original wording can be recovered from context.
            - When in doubt about a restoration, prefer the most natural reading that preserves the
              original meaning and the original sentence structure.
            - You MUST complete the content identification gate (step 4) before task identification.
              Do not jump directly to identifying the task without first reasoning about what the
              content is through hypothesis enumeration and evidence evaluation.
        """.trimIndent()
    }

    fun selectDepth(depth: ReasoningDepth, method: ReasoningMethod) : String
    {
        return when(method)
        {
            ReasoningMethod.ExplicitCot -> when(depth)
            {
                ReasoningDepth.Low -> "MANDATORY: Complete your 5-step thinking process with 3-5 total reasoning steps. Focus ONLY on core problem. In steps 1-4, use minimal analysis. Skip exploring alternatives in step 4."
                ReasoningDepth.Med -> "MANDATORY: Complete your 5-step thinking process with 6-10 total reasoning steps. In step 4, consider 2-3 potential approaches. Provide balanced analysis."
                ReasoningDepth.High -> "MANDATORY: Complete your 5-step thinking process with 10+ total reasoning steps. In step 4, explore multiple approaches, implications, and edge cases thoroughly. Conduct deep analysis at each step."
            }
            
            ReasoningMethod.StructuredCot -> when(depth)
            {
                ReasoningDepth.Low -> "MANDATORY: Complete all 4 phases with 3-5 total reasoning steps across phases. PHASE 1: Identify core elements only. PHASE 2: Break into 2-3 sub-problems maximum. PHASE 3: Execute with minimal intermediate steps. PHASE 4: Brief synthesis."
                ReasoningDepth.Med -> "MANDATORY: Complete all 4 phases with 6-10 total reasoning steps across phases. PHASE 1: Thorough element identification. PHASE 2: Break into 3-5 sub-problems. PHASE 3: Show key intermediate results. PHASE 4: Comprehensive synthesis."
                ReasoningDepth.High -> "MANDATORY: Complete all 4 phases with 10+ total reasoning steps across phases. PHASE 1: Exhaustive element identification including constraints and assumptions. PHASE 2: Detailed decomposition with dependencies. PHASE 3: Show all work and intermediate results. PHASE 4: Deep synthesis with validation."
            }
            
            ReasoningMethod.processFocusedCot -> when(depth)
            {
                ReasoningDepth.Low -> "MANDATORY: Answer all 5 questions with 3-5 total reasoning steps. Keep each answer concise. For 'WHAT STEPS DO I NEED?', outline 2-3 steps only. Skip alternative approaches."
                ReasoningDepth.Med -> "MANDATORY: Answer all 5 questions with 6-10 total reasoning steps. For 'WHAT STEPS DO I NEED?', outline methodology and identify 1-2 alternative approaches. Balanced detail per question."
                ReasoningDepth.High -> "MANDATORY: Answer all 5 questions with 10+ total reasoning steps. For 'WHAT STEPS DO I NEED?', explore multiple methodological approaches and justify selection. Provide exhaustive answers to each question."
            }
            
            ReasoningMethod.ChainOfDraft -> when(depth)
            {
                ReasoningDepth.Low -> "MANDATORY: Use 3-5 minimal reasoning steps total. Maintain 5-word maximum per step. Focus on essential calculation/transformation only. Skip validation steps."
                ReasoningDepth.Med -> "MANDATORY: Use 6-10 minimal reasoning steps total. Maintain 5-word maximum per step. Include key calculations and one validation step."
                ReasoningDepth.High -> "MANDATORY: Use 10+ minimal reasoning steps total. Maintain 5-word maximum per step. Include all calculations, transformations, and validation steps."
            }
            
            ReasoningMethod.BestIdea -> when(depth)
            {
                ReasoningDepth.Low -> "MANDATORY: Generate and evaluate 2-3 potential solutions with 3-5 total reasoning steps. Brief comparative analysis. Select best with concise justification."
                ReasoningDepth.Med -> "MANDATORY: Generate and evaluate 3-5 potential solutions with 6-10 total reasoning steps. Thorough comparative analysis. Clear selection criteria and justification."
                ReasoningDepth.High -> "MANDATORY: Generate and evaluate 5+ potential solutions with 10+ total reasoning steps. Exhaustive comparative analysis from multiple angles. Detailed selection criteria, justification, and refinement."
            }
            
            ReasoningMethod.ComprehensivePlan -> when(depth)
            {
                ReasoningDepth.Low -> "MANDATORY: Develop 2-3 phase plan with 3-5 total reasoning steps. Brief analysis of scope and objectives. Define basic actions per phase. Minimal risk identification."
                ReasoningDepth.Med -> "MANDATORY: Develop 3-5 phase plan with 6-10 total reasoning steps. Thorough scope analysis. Define specific actions, resources, and timelines. Identify key risks and mitigation."
                ReasoningDepth.High -> "MANDATORY: Develop 5+ phase plan with 10+ total reasoning steps. Exhaustive scope, constraints, and objectives analysis. Detailed actions, resources, timelines, and dependencies. Comprehensive risk assessment and contingency planning."
            }
            
            ReasoningMethod.RolePlay -> when(depth)
            {
                ReasoningDepth.Low -> "MANDATORY: Minimal character immersion with 3-5 total reasoning elements. characterInsights array: 2-3 items. thoughtProcess array: 2-3 items. uniqueAdvantages array: 1-2 items. Surface-level character analysis. Brief problem interpretation."
                ReasoningDepth.Med -> "MANDATORY: Balanced character immersion with 6-10 total reasoning elements. characterInsights array: 3-5 items. thoughtProcess array: 4-6 items. uniqueAdvantages array: 2-3 items. Thorough character analysis. Comprehensive problem interpretation."
                ReasoningDepth.High -> "MANDATORY: Deep character immersion with 10+ total reasoning elements. characterInsights array: 5+ items. thoughtProcess array: 7+ items. uniqueAdvantages array: 3+ items. Exhaustive character analysis exploring worldview implications. Multi-layered problem interpretation."
            }

            ReasoningMethod.SemanticDecompression -> when(depth)
            {
                ReasoningDepth.Low -> "MANDATORY: Analyze the legend and identify the content with 3-5 total reasoning steps. Expand legend codes. Generate 2-3 hypotheses about what the content is, evaluate each briefly, and select the most likely interpretation. Identify the core task only. Extract 2-3 key data points. Preserve quote spans. Reconstruct sentence-by-sentence with minimal supporting context."
                ReasoningDepth.Med -> "MANDATORY: Analyze the legend, reason about content identity, and extract key data with 6-10 total reasoning steps. Expand all legend codes. Generate 3-5 hypotheses about what the content could be, evaluate each with supporting and contradicting evidence, assign likelihood ratings, and select the most likely interpretation. Identify the task and its requirements. Extract 4-6 key data points. Preserve quote spans and paragraph boundaries. Reconstruct the text sentence-by-sentence with inferred function words."
                ReasoningDepth.High -> "MANDATORY: Full decompression analysis with 10+ total reasoning steps. Expand all legend codes. Generate 5+ hypotheses about what the content could be, exhaustively evaluate each with all available supporting and contradicting evidence, assign likelihood ratings with detailed justification, and select the most likely interpretation with a thorough rationale. Identify the task and all sub-requirements. Extract all key data points exhaustively. Preserve quote spans exactly. Attempt complete faithful restoration of the original text sentence-by-sentence and paragraph-by-paragraph with all inferred function words, punctuation, and syntax."
            }
        }
    }

    fun selectDuration(duration: ReasoningDuration, method: ReasoningMethod) : String
    {
        return when(method)
        {
            ReasoningMethod.ExplicitCot -> when(duration)
            {
                ReasoningDuration.Short -> "REQUIRED: Your reasoning output MUST be 40-60% of normal length. Use abbreviated explanations in steps 1-4. Skip justifications for obvious inferences. In recommendedSteps, provide concise operational guidance (1-2 sentences)."
                ReasoningDuration.Med -> "REQUIRED: Your reasoning output MUST be 90-110% of normal length. Balance explanation with efficiency in steps 1-4. Justify key decisions. In recommendedSteps, provide clear operational guidance (2-3 sentences)."
                ReasoningDuration.Long -> "REQUIRED: Your reasoning output MUST be 150-200% of normal length. Provide verbose, detailed explanations in steps 1-4. Justify every inference and assumption. In recommendedSteps, provide comprehensive operational guidance (4+ sentences)."
            }
            
            ReasoningMethod.StructuredCot -> when(duration)
            {
                ReasoningDuration.Short -> "REQUIRED: Your reasoning output MUST be 40-60% of normal length. Keep each phase concise. Use bullet points without elaboration. Minimal explanation of purpose per step in PHASE 3."
                ReasoningDuration.Med -> "REQUIRED: Your reasoning output MUST be 90-110% of normal length. Balanced detail per phase. Explain purpose of key steps in PHASE 3. Standard synthesis in PHASE 4."
                ReasoningDuration.Long -> "REQUIRED: Your reasoning output MUST be 150-200% of normal length. Elaborate on each phase thoroughly. Explain purpose and rationale for every step in PHASE 3. Comprehensive synthesis with detailed validation in PHASE 4."
            }
            
            ReasoningMethod.processFocusedCot -> when(duration)
            {
                ReasoningDuration.Short -> "REQUIRED: Your reasoning output MUST be 40-60% of normal length. Answer each question concisely. Use abbreviated explanations. Minimal justification for methodological choices."
                ReasoningDuration.Med -> "REQUIRED: Your reasoning output MUST be 90-110% of normal length. Answer each question with balanced detail. Justify key methodological choices. Standard documentation of insights."
                ReasoningDuration.Long -> "REQUIRED: Your reasoning output MUST be 150-200% of normal length. Answer each question exhaustively. Provide verbose justifications for all methodological choices. Elaborate on insights gained at each stage."
            }
            
            ReasoningMethod.ChainOfDraft -> when(duration)
            {
                ReasoningDuration.Short -> "REQUIRED: Your reasoning output MUST be 40-60% of normal length. Use 3-4 words per step (below the 5-word maximum). Extreme minimalism. Mathematical notation only."
                ReasoningDuration.Med -> "REQUIRED: Your reasoning output MUST be 90-110% of normal length. Use 4-5 words per step (at the 5-word maximum). Standard Chain of Draft verbosity."
                ReasoningDuration.Long -> "REQUIRED: Your reasoning output MUST be 150-200% of normal length. Use full 5 words per step. Add brief context phrases between steps (5 words max each). Maintain minimal verbosity while increasing step count."
            }
            
            ReasoningMethod.BestIdea -> when(duration)
            {
                ReasoningDuration.Short -> "REQUIRED: Your reasoning output MUST be 40-60% of normal length. Brief multi-angle analysis. Concise evaluation of solutions. Short justification for best idea selection."
                ReasoningDuration.Med -> "REQUIRED: Your reasoning output MUST be 90-110% of normal length. Balanced comparative analysis. Clear evaluation and selection criteria. Standard justification detail."
                ReasoningDuration.Long -> "REQUIRED: Your reasoning output MUST be 150-200% of normal length. Verbose multi-angle analysis. Detailed evaluation with explicit criteria. Comprehensive justification and refinement explanation."
            }
            
            ReasoningMethod.ComprehensivePlan -> when(duration)
            {
                ReasoningDuration.Short -> "REQUIRED: Your reasoning output MUST be 40-60% of normal length. Concise problem analysis. Brief phase definitions with key actions only. Minimal risk discussion."
                ReasoningDuration.Med -> "REQUIRED: Your reasoning output MUST be 90-110% of normal length. Thorough problem analysis. Detailed phase definitions with actions and resources. Standard risk and mitigation detail."
                ReasoningDuration.Long -> "REQUIRED: Your reasoning output MUST be 150-200% of normal length. Exhaustive problem analysis with constraints and objectives. Comprehensive phase definitions with actions, resources, timelines, and dependencies. Detailed risk assessment with contingency planning."
            }
            
            ReasoningMethod.RolePlay -> when(duration)
            {
                ReasoningDuration.Short -> "REQUIRED: Your reasoning output MUST be 40-60% of normal length. Concise internal monologue (1-2 sentences per thoughtProcess item). Brief explanations in all JSON fields. Minimal verbosity in characterBackground, problemInterpretation, and solution fields."
                ReasoningDuration.Med -> "REQUIRED: Your reasoning output MUST be 90-110% of normal length. Standard character voice (2-3 sentences per thoughtProcess item). Balanced verbosity across all JSON fields. Standard explanations in characterBackground, problemInterpretation, and solution fields."
                ReasoningDuration.Long -> "REQUIRED: Your reasoning output MUST be 150-200% of normal length. Verbose internal monologue (3-4+ sentences per thoughtProcess item). Extended explanations in all JSON fields. Detailed elaboration in characterBackground, problemInterpretation, appliedExpertise, and solution fields."
            }

            ReasoningMethod.SemanticDecompression -> when(duration)
            {
                ReasoningDuration.Short -> "REQUIRED: Your reasoning output MUST be 40-60% of normal length. Concise legend analysis. Brief content identification with hypotheses listed and a short justification for the selected interpretation. Brief task identification. List key data points without elaboration. Preserve quote spans and sentence structure. Minimal restoration notes in decompressionStrategy."
                ReasoningDuration.Med -> "REQUIRED: Your reasoning output MUST be 90-110% of normal length. Standard legend analysis with all mappings noted. Content identification with each hypothesis evaluated using supporting and contradicting evidence and likelihood ratings. Clear task identification with requirements. Key data points with context. Preserve quote spans, restoredSentences, and restoredParagraphs. Balanced restoration notes explaining inference choices."
                ReasoningDuration.Long -> "REQUIRED: Your reasoning output MUST be 150-200% of normal length. Verbose legend analysis explaining what each mapping represents. Exhaustive content identification with detailed evidence analysis per hypothesis, thorough likelihood justifications, and a comprehensive rationale for the selected interpretation. Detailed task identification with all sub-requirements. Exhaustive key data points with full context. Explicitly reconstruct sentence-by-sentence and paragraph-by-paragraph. Comprehensive restoration notes justifying every inference and function word restoration."
            }
        }
    }

}
